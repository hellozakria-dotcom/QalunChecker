package com.qalun.checker.core.asr;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Offline Quran speech recognition: Tarteel's whisper-tiny-ar-quran exported to ONNX.
 * Model folder: encoder.onnx, decoder.onnx, tokens.txt, mel_filters.bin, whisper.properties
 * Greedy decoding, Arabic, transcribe, no timestamps. Not thread-safe: call from one thread.
 */
public final class WhisperEngine implements AutoCloseable {
    private final OrtEnvironment env;
    private final OrtSession encoder, decoder;
    private final int layers, heads, headDim;
    private final MelSpectrogram mel;
    private final WhisperTokenizer tokenizer;
    private final long[] prompt;
    private final int eot, firstSpecial, vocabLimit;
    private final java.util.Set<Integer> suppress = new java.util.HashSet<>(), beginSuppress = new java.util.HashSet<>();
    private static final int MAX_TOKENS = 200;

    public WhisperEngine(File dir, int threads) throws IOException, OrtException {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(new File(dir, "whisper.properties"))) { p.load(in); }
        eot = Integer.parseInt(p.getProperty("eot"));
        firstSpecial = eot;
        prompt = new long[]{
                Long.parseLong(p.getProperty("sot")), Long.parseLong(p.getProperty("lang_ar")),
                Long.parseLong(p.getProperty("transcribe")), Long.parseLong(p.getProperty("notimestamps"))};
        vocabLimit = Integer.parseInt(p.getProperty("vocab_size"));
        parseList(p.getProperty("suppress", ""), suppress);
        parseList(p.getProperty("begin_suppress", ""), beginSuppress);

        byte[] mf = Files.readAllBytes(new File(dir, "mel_filters.bin").toPath());
        float[] filters = new float[mf.length / 4];
        ByteBuffer.wrap(mf).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(filters);
        mel = new MelSpectrogram(filters);
        try (InputStream in = new FileInputStream(new File(dir, "tokens.txt"))) {
            tokenizer = new WhisperTokenizer(in, firstSpecial);
        }

        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.setIntraOpNumThreads(Math.max(1, threads));
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        encoder = env.createSession(new File(dir, "encoder.onnx").getAbsolutePath(), so);
        decoder = env.createSession(new File(dir, "decoder.onnx").getAbsolutePath(), so);
        layers = Integer.parseInt(p.getProperty("layers"));
        heads = Integer.parseInt(p.getProperty("heads"));
        headDim = Integer.parseInt(p.getProperty("head_dim"));
    }

    /** @param audio 16 kHz mono samples in [-1, 1], up to 30 s. */
    public String transcribe(float[] audio) throws OrtException {
        float[] feats = mel.compute(audio);
        List<Integer> out = new ArrayList<>();
        try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(feats), new long[]{1, MelSpectrogram.N_MELS, MelSpectrogram.N_FRAMES});
             OrtSession.Result er = encoder.run(singleton("input_features", in))) {
            OnnxTensor crossK = (OnnxTensor) er.get(0), crossV = (OnnxTensor) er.get(1);
            // KV cache: each step gives the decoder only the newest token plus its memory
            OnnxTensor pastK = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), new long[]{layers, 1, heads, 0, headDim});
            OnnxTensor pastV = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), new long[]{layers, 1, heads, 0, headDim});
            OrtSession.Result prev = null;
            long[] ids = prompt.clone();
            long offset = 0;
            try {
                for (int step = 0; step < MAX_TOKENS; step++) {
                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    OnnxTensor idsT = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[]{1, ids.length});
                    OnnxTensor offT = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{offset}), new long[]{1});
                    inputs.put("input_ids", idsT);
                    inputs.put("offset", offT);
                    inputs.put("past_k", pastK);
                    inputs.put("past_v", pastV);
                    inputs.put("cross_k", crossK);
                    inputs.put("cross_v", crossV);
                    OrtSession.Result r = decoder.run(inputs);
                    idsT.close();
                    offT.close();
                    if (prev != null) prev.close(); // its tensors were this step's memory
                    else { pastK.close(); pastV.close(); }
                    prev = r;
                    offset += ids.length;

                    FloatBuffer fb = ((OnnxTensor) r.get(0)).getFloatBuffer(); // [1, V]
                    int vocab = fb.remaining();
                    int next = eot;
                    float best = Float.NEGATIVE_INFINITY;
                    int limit = Math.min(vocab, vocabLimit);
                    for (int v = 0; v < limit; v++) {
                        if (v > eot) continue; // suppress special / timestamp tokens
                        if (suppress.contains(v) || (step == 0 && beginSuppress.contains(v))) continue;
                        float x = fb.get(v);
                        if (x > best) { best = x; next = v; }
                    }
                    if (next == eot) break;
                    out.add(next);
                    int rep = repeatedTail(out);
                    if (rep > 0) { // model got stuck repeating itself: drop the repeats and stop
                        out.subList(out.size() - rep, out.size()).clear();
                        break;
                    }
                    pastK = (OnnxTensor) r.get(1);
                    pastV = (OnnxTensor) r.get(2);
                    ids = new long[]{next};
                }
            } finally {
                if (prev != null) prev.close();
            }
        }
        return tokenizer.decode(out);
    }

    private static void parseList(String s, java.util.Set<Integer> into) {
        for (String x : s.split(",")) {
            x = x.trim();
            if (!x.isEmpty()) into.add(Integer.parseInt(x));
        }
    }

    /**
     * If the output ends with a block of 1..8 tokens repeated (4 times for single tokens,
     * 3 times otherwise), returns how many trailing tokens are repeats to drop; else 0.
     */
    static int repeatedTail(List<Integer> t) {
        int n = t.size();
        for (int k = 1; k <= 8; k++) {
            int times = k == 1 ? 4 : 3;
            if (n < times * k) continue;
            boolean rep = true;
            for (int r = 1; r < times && rep; r++)
                for (int i = 0; i < k && rep; i++)
                    if (!t.get(n - 1 - i).equals(t.get(n - 1 - i - r * k))) rep = false;
            if (rep) return (times - 1) * k;
        }
        return 0;
    }

    private static Map<String, OnnxTensor> singleton(String k, OnnxTensor v) {
        Map<String, OnnxTensor> m = new HashMap<>();
        m.put(k, v);
        return m;
    }

    @Override
    public void close() throws OrtException {
        encoder.close();
        decoder.close();
    }
}
