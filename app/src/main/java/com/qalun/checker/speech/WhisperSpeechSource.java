package com.qalun.checker.speech;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import com.qalun.checker.core.asr.WhisperEngine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Offline Quran recogniser: records the microphone, cuts the audio at pauses,
 * and transcribes each piece with the Quran-trained Whisper model on the phone.
 * While a piece is still being recited it is re-transcribed every few seconds
 * so words light up before the pause.
 */
public final class WhisperSpeechSource implements SpeechSource {
    private static final int SR = 16000, CHUNK = 1600; // 100 ms
    private static final int END_SILENCE_MS = 700, PREROLL_MS = 300;
    private static final double MAX_SEGMENT_S = 24.0, PARTIAL_EVERY_MS = 2500;

    private static WhisperEngine engine; // shared, loaded once per process
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private Thread recorder;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicInteger segmentId = new AtomicInteger();

    public WhisperSpeechSource(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    /** True if the model files were packaged into the app. */
    public static boolean available(Context ctx) {
        try {
            String[] f = ctx.getAssets().list("whisper");
            return f != null && f.length >= 5;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void start() {
        running = true;
        if (engine == null) post(() -> listener.onStatus("جارٍ تجهيز نموذج القراءة…"));
        WORKER.submit(() -> {
            try {
                ensureEngine(ctx);
                post(() -> listener.onStatus(""));
            } catch (Throwable t) {
                running = false;
                post(() -> listener.onError("تعذّر تحميل النموذج: " + t.getMessage()));
                return;
            }
            if (running) startRecorder();
        });
    }

    @Override
    public void stop() {
        running = false; // recorder thread flushes the last segment itself
    }

    @Override
    public void destroy() {
        running = false;
    }

    private static synchronized void ensureEngine(Context ctx) throws Exception {
        if (engine != null) return;
        File dir = new File(ctx.getFilesDir(), "whisper");
        File marker = new File(dir, ".version");
        String version;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            version = String.valueOf(pi.lastUpdateTime);
        } catch (Exception e) {
            version = "0";
        }
        boolean fresh = marker.exists() && version.equals(readSmall(marker));
        if (!fresh) {
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create model folder");
            AssetManager am = ctx.getAssets();
            String[] files = am.list("whisper");
            if (files == null) throw new IOException("model not packaged");
            for (String f : files) copy(am.open("whisper/" + f), new File(dir, f));
            try (OutputStream o = new FileOutputStream(marker)) { o.write(version.getBytes("UTF-8")); }
        }
        int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        engine = new WhisperEngine(dir, threads);
    }

    private static String readSmall(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[64];
            int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n, "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    private static void copy(InputStream in, File out) throws IOException {
        try (InputStream i = in; OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    @SuppressLint("MissingPermission") // checked by the activity before start()
    private void startRecorder() {
        recorder = new Thread(() -> {
            int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SR));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                running = false;
                post(() -> listener.onError("تعذّر فتح الميكروفون"));
                return;
            }
            rec.startRecording();
            short[] buf = new short[CHUNK];
            float[] pre = new float[SR * PREROLL_MS / 1000];
            int preLen = 0;
            FloatBuf seg = new FloatBuf();
            boolean speech = false;
            int silenceMs = 0;
            double noise = 0.003;
            long lastPartial = 0;
            try {
                while (running) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    float[] f = new float[n];
                    double sum = 0;
                    for (int i = 0; i < n; i++) { f[i] = buf[i] / 32768f; sum += f[i] * f[i]; }
                    double rms = Math.sqrt(sum / n);
                    double startThr = Math.max(0.012, noise * 3.5), keepThr = Math.max(0.008, noise * 2.0);
                    if (!speech) {
                        noise = 0.95 * noise + 0.05 * Math.min(rms, 0.05);
                        // keep a short pre-roll so the first letter is not cut
                        int keep = Math.min(pre.length, preLen + n);
                        float[] np = new float[pre.length];
                        int fromOld = keep - n;
                        if (fromOld > 0) System.arraycopy(pre, preLen - fromOld, np, 0, fromOld);
                        System.arraycopy(f, Math.max(0, n - keep), np, Math.max(0, fromOld), Math.min(n, keep));
                        pre = np;
                        preLen = keep;
                        if (rms > startThr) {
                            speech = true;
                            silenceMs = 0;
                            seg = new FloatBuf();
                            seg.add(pre, preLen);
                            segmentId.incrementAndGet();
                            lastPartial = System.currentTimeMillis();
                        }
                        continue;
                    }
                    seg.add(f, n);
                    silenceMs = rms < keepThr ? silenceMs + n * 1000 / SR : 0;
                    double secs = seg.size / (double) SR;
                    if ((silenceMs >= END_SILENCE_MS && secs > 0.8) || secs >= MAX_SEGMENT_S) {
                        submitFinal(seg.toArray());
                        speech = false;
                        preLen = 0;
                    } else if (System.currentTimeMillis() - lastPartial >= PARTIAL_EVERY_MS && !busy.get()) {
                        lastPartial = System.currentTimeMillis();
                        submitPartial(seg.toArray(), segmentId.get());
                    }
                }
                if (speech && seg.size > SR / 2) submitFinal(seg.toArray());
            } finally {
                rec.stop();
                rec.release();
            }
        }, "qalun-mic");
        recorder.start();
    }

    private void submitPartial(float[] audio, int id) {
        busy.set(true);
        WORKER.submit(() -> {
            try {
                String t = engine.transcribe(audio);
                if (id == segmentId.get() && !t.isEmpty()) post(() -> listener.onPartial(t));
            } catch (Throwable ignored) {
            } finally {
                busy.set(false);
            }
        });
    }

    private void submitFinal(float[] audio) {
        segmentId.incrementAndGet(); // late partials of this segment are now ignored
        WORKER.submit(() -> {
            try {
                String t = engine.transcribe(audio);
                post(() -> listener.onFinal(t));
            } catch (Throwable e) {
                post(() -> listener.onError("خطأ في التعرّف: " + e.getMessage()));
            }
        });
    }

    private void post(Runnable r) { main.post(r); }

    private static final class FloatBuf {
        float[] a = new float[SR * 4];
        int size;

        void add(float[] src, int n) {
            if (size + n > a.length) a = java.util.Arrays.copyOf(a, Math.max(a.length * 2, size + n));
            System.arraycopy(src, 0, a, size, n);
            size += n;
        }

        float[] toArray() { return java.util.Arrays.copyOf(a, size); }
    }
}
