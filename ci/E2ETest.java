import com.qalun.checker.core.*;
import com.qalun.checker.core.asr.*;
import java.io.*; import java.nio.*; import java.nio.file.*; import java.util.*;

/** Runs the Java Whisper engine on real Qalun recitation chunks and checks them with RecitationTracker. */
public class E2ETest {
    public static void main(String[] a) throws Exception {
        File model = new File(a[0]); String listing = a[1], refFile = a[2], assets = a[3], label = a[4];
        Map<String, String> ref = new HashMap<>();
        for (String l : Files.readAllLines(Paths.get(refFile))) { String[] f = l.split("\t", 2); if (f.length == 2) ref.put(f[0], f[1]); }
        QuranText q = QuranText.load(new FileInputStream(assets + "/qalun.tsv"), new FileInputStream(assets + "/suras.tsv"));
        long t0 = System.nanoTime();
        WhisperEngine eng = new WhisperEngine(model, 2);
        System.out.println("load ms=" + (System.nanoTime() - t0) / 1000000);
        Map<Integer, RecitationTracker> trackers = new LinkedHashMap<>();
        Map<Integer, StringBuilder> flagged = new LinkedHashMap<>(), heardAll = new LinkedHashMap<>();
        int same = 0, total = 0; double audioSec = 0, procSec = 0;
        for (String l : Files.readAllLines(Paths.get(listing))) {
            if (l.isBlank()) continue;
            String[] f = l.split("\t"); int sura = Integer.parseInt(f[0]);
            byte[] b = Files.readAllBytes(Paths.get(f[1]));
            float[] audio = new float[b.length / 4];
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(audio);
            long s = System.nanoTime();
            String text = eng.transcribe(audio);
            double sec = (System.nanoTime() - s) / 1e9;
            audioSec += audio.length / 16000.0; procSec += sec; total++;
            String r = ref.getOrDefault(f[1], "");
            if (r.equals(text)) same++;
            System.out.printf("%s %.1fs audio, %.2fs | java: %s%n   ref: %s%n", f[1], audio.length / 16000.0, sec, text, r);
            RecitationTracker t = trackers.computeIfAbsent(sura, k -> new RecitationTracker(q.ayat(k)));
            for (RecitationTracker.Mistake m : t.onResult(text, true)) {
                System.out.println("   flagged: " + m.describe().replace('\n', ' '));
                flagged.computeIfAbsent(sura, k -> new StringBuilder()).append(m.word.display).append(m.type == RecitationTracker.State.MISSED ? "(missed)" : "(heard " + m.word.heard + ")").append(" ");
            }
            heardAll.computeIfAbsent(sura, k -> new StringBuilder()).append(text).append(" / ");
        }
        StringBuilder sum = new StringBuilder();
        for (Map.Entry<Integer, RecitationTracker> e : trackers.entrySet()) {
            RecitationTracker t = e.getValue();
            sum.append(String.format("S%d: %d/%d correct, %d wrong, %d missed, reached %d; ", e.getKey(),
                    t.count(RecitationTracker.State.CORRECT), t.words().size(), t.count(RecitationTracker.State.WRONG),
                    t.count(RecitationTracker.State.MISSED), t.position()));
        }
        String msg = String.format("[%s] java==python on %d/%d chunks; speed %.2fx realtime (2 threads). %s",
                label, same, total, procSec / Math.max(audioSec, 1e-9), sum);
        System.out.println(msg);
        System.out.println("::notice title=E2E " + label + "::" + msg);
        if (label.equals(System.getenv().getOrDefault("DETAIL_VARIANT", "mixedpc")))
            for (int sura : heardAll.keySet())
                System.out.println("::notice title=S" + sura + " " + label + "::heard: " + heardAll.get(sura) + " %0Aflagged: " + flagged.getOrDefault(sura, new StringBuilder("none")));
    }
}
