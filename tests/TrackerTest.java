import com.qalun.checker.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class TrackerTest {
    static int fails = 0;
    static void check(boolean ok, String msg) { if (!ok) { fails++; System.out.println("FAIL: " + msg); } }

    static String plain(String raw) { // what a speech engine might print: letters only, no marks
        raw = raw.replaceAll("[يى]\u0670(?=[\u0621-\u064A])", "ا");
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c >= 0x0621 && c <= 0x064A && c != 0x0640) sb.append(c);
            else if (c == 0x06D2) sb.append('ي');
        }
        return sb.toString();
    }

    public static void main(String[] a) throws Exception {
        String dir = a[0], assets = a[1];

        // 1. Normalizer parity with the tuned Python reference
        int bad = 0, total = 0;
        for (String line : Files.readAllLines(Paths.get(dir, "norm_expected.tsv"), StandardCharsets.UTF_8)) {
            String[] f = line.split("\t", -1);
            total++;
            if (!ArabicNormalizer.normalize(f[0]).equals(f[1])) { if (bad++ < 5) System.out.println("norm diff: " + f[0] + " java=" + ArabicNormalizer.normalize(f[0]) + " py=" + f[1]); }
        }
        System.out.println("[1] normalizer parity: " + (total - bad) + "/" + total);
        check(bad == 0, "normalizer parity");

        // 2. Correct reading in ordinary spelling (Hafs imla'i as proxy) -> false alarms
        QuranText hafs = QuranText.load(new FileInputStream(dir + "/hafs.tsv"), new FileInputStream(assets + "/suras.tsv"));
        Map<String, String> iml = new HashMap<>();
        for (String line : Files.readAllLines(Paths.get(dir, "hafs_imlaei.tsv"), StandardCharsets.UTF_8)) {
            String[] f = line.split("\t", 3); iml.put(f[0] + ":" + f[1], f[2]);
        }
        int words = 0, falseAlarms = 0;
        for (int s = 1; s <= 114; s++) {
            List<QuranText.Ayah> ay = hafs.ayat(s);
            RecitationTracker t = new RecitationTracker(ay);
            for (QuranText.Ayah x : ay) falseAlarms += t.onResult(iml.get(s + ":" + x.aya), true).size();
            words += t.words().size();
            check(t.finished(), "hafs sura " + s + " not finished, pos=" + t.position());
        }
        System.out.printf("[2] correct reading, normal spelling: %d false alarms in %d words (%.2f%%)%n", falseAlarms, words, 100.0 * falseAlarms / words);

        // 3. Qalun text with injected mistakes
        QuranText q = QuranText.load(new FileInputStream(assets + "/qalun.tsv"), new FileInputStream(assets + "/suras.tsv"));
        check(q.suras().size() == 114, "114 suras");
        Random rnd = new Random(7);
        String[] kinds = {"drop-letter", "swap-letter", "skip-word", "repeat-word"};
        int[] injected = new int[4], caught = new int[4];
        int fa = 0, totalWords = 0;
        String letters = "بتثجحخدذرزسشصضطظعغفقكلمنهوي";
        for (int s = 1; s <= 114; s++) {
            List<QuranText.Ayah> ay = q.ayat(s);
            RecitationTracker t = new RecitationTracker(ay);
            List<RecitationTracker.Word> W = t.words();
            totalWords += W.size();
            Map<Integer, Integer> target = new HashMap<>(); // word index -> kind
            int wi = 0;
            for (QuranText.Ayah x : ay) {
                StringBuilder heard = new StringBuilder(s == 1 || s == 9 || x.aya != 1 ? "" : "بسم الله الرحمن الرحيم ");
                int ayStart = wi;
                while (wi < W.size() && W.get(wi).aya == x.aya && W.get(wi).sura == s) {
                    String p = plain(W.get(wi).display);
                    boolean candidate = wi > ayStart + 1 && wi < W.size() - 1 && rnd.nextInt(25) == 0 && !target.containsKey(wi - 1);
                    int kind = candidate ? rnd.nextInt(4) : -1;
                    if (kind == 0 || kind == 1) {
                        List<Integer> pos = new ArrayList<>();
                        for (int k = 0; k < p.length(); k++) if (letters.indexOf(p.charAt(k)) >= 0) pos.add(k);
                        if (pos.size() < 2) kind = -1;
                        else {
                            int k = pos.get(rnd.nextInt(pos.size()));
                            String np;
                            if (kind == 0) np = p.substring(0, k) + p.substring(k + 1);
                            else {
                                char r; do { r = letters.charAt(rnd.nextInt(letters.length())); } while (r == p.charAt(k));
                                np = p.substring(0, k) + r + p.substring(k + 1);
                            }
                            // only count it if the change survives normalisation (otherwise it's not a mistake)
                            if (ArabicNormalizer.normalize(np).equals(ArabicNormalizer.normalize(W.get(wi).display))) kind = -1; else p = np;
                        }
                    }
                    if (kind == 2) p = null;
                    if (kind == 3 && wi > 0) p = p + " " + p;
                    if (kind >= 0) { target.put(wi, kind); injected[kind]++; }
                    if (p != null) heard.append(p).append(' ');
                    wi++;
                }
                // stream it like Android does: growing partials, then the final result
                String[] toks = heard.toString().trim().split(" ");
                StringBuilder part = new StringBuilder();
                for (String tk : toks) { part.append(tk).append(' '); t.onResult(part.toString(), false); }
                for (RecitationTracker.Mistake m : t.onResult(heard.toString(), true)) {
                    Integer k = target.get(m.word.index);
                    if (k != null && k != 3) caught[k]++; else fa++;
                }
            }
            check(t.finished(), "qalun sura " + s + " not finished, pos=" + t.position() + "/" + W.size());
        }
        System.out.println("[3] Qalun text, " + totalWords + " words, injected mistakes:");
        for (int k = 0; k < 3; k++) System.out.printf("    %-12s caught %d/%d (%.1f%%)%n", kinds[k], caught[k], injected[k], 100.0 * caught[k] / injected[k]);
        System.out.printf("    repeat-word (should be ignored): %d injected%n", injected[3]);
        System.out.printf("    false alarms on correct words: %d (%.2f%%)%n", fa, 100.0 * fa / totalWords);

        // 4. Specific checks
        RecitationTracker f = new RecitationTracker(q.ayat(1));
        List<RecitationTracker.Mistake> m = f.onResult("الحمد لله رب العالمين الرحمن الرحيم ملك يوم الدين", true);
        check(m.isEmpty(), "Fatiha 1-3 clean: " + m.size());
        m = f.onResult("اياك نعبد واياك نستعن", true);
        check(m.size() == 1 && m.get(0).word.letterNotes.toString().contains("ي"), "missing ya detected");
        if (!m.isEmpty()) System.out.println("[4] example: " + m.get(0).describe());
        m = f.onResult("اهدنا الصراط صراط الذين انعمت عليهم", true);
        check(m.size() == 1 && m.get(0).type == RecitationTracker.State.MISSED, "skipped المستقيم");
        if (!m.isEmpty()) System.out.println("    example: " + m.get(0).describe());

        RecitationTracker b = new RecitationTracker(q.ayat(2));
        m = b.onResult("ألف لام ميم ذلك الكتاب لا ريب فيه", true);
        check(m.isEmpty(), "muqattaat letter names: " + (m.isEmpty() ? "" : m.get(0).describe()));
        RecitationTracker k = new RecitationTracker(q.ayat(19));
        m = k.onResult("كاف ها يا عين صاد ذكر رحمة ربك", true);
        check(m.isEmpty(), "kaf-ha-ya-ain-sad: " + (m.isEmpty() ? "" : m.get(0).describe()));

        String[][] same = {{"لَنَرَيٰكَ", "لنراك"}, {"نَرَيٰكَ", "نراك"}, {"اَ۬لصَّلَوٰةَ", "الصلاة"}, {"عَلَيٰ", "على"}};
        for (String[] p : same)
            check(ArabicNormalizer.normalize(p[0]).equals(ArabicNormalizer.normalize(p[1])),
                    "should match: " + p[0] + " / " + p[1] + " -> " + ArabicNormalizer.normalize(p[0]) + " vs " + ArabicNormalizer.normalize(p[1]));

        System.out.println(fails == 0 ? "ALL CHECKS PASSED" : fails + " CHECK(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
