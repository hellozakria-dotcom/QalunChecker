package com.qalun.checker.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Follows a recitation word by word and flags mistakes.
 *
 * Feed it every speech-recognition result (partial or final). Partial results
 * only colour words tentatively; a final result commits them and returns the
 * mistakes found, so the app can notify the reciter.
 */
public final class RecitationTracker {

    public enum State { PENDING, CORRECT, WRONG, MISSED }

    public static final class Word {
        public final int index, sura, aya;
        public final String display;
        final String norm;
        final String alt; // spoken form for huruf muqatta'at (letter names), else null
        final String alt2; // Uthmani-spelling form (e.g. صلوة with waw) if it differs, else null
        public State state = State.PENDING;
        public String heard;
        public List<String> letterNotes = Collections.emptyList();

        Word(int index, int sura, int aya, String display, String norm, String alt, String alt2) {
            this.index = index; this.sura = sura; this.aya = aya;
            this.display = display; this.norm = norm; this.alt = alt; this.alt2 = alt2;
        }

        boolean accepts(String form) {
            return form.equals(norm) || form.equals(alt) || form.equals(alt2);
        }

        void clear() { state = State.PENDING; heard = null; letterNotes = Collections.emptyList(); }
    }

    public static final class Mistake {
        public final Word word;
        public final State type;
        Mistake(Word w) { word = w; type = w.state; }

        public String describe() {
            String where = "آية " + word.aya + ": «" + word.display + "»";
            if (type == State.MISSED) return where + " — كلمة متروكة";
            StringBuilder sb = new StringBuilder(where + " — خطأ");
            if (word.heard != null) sb.append(" (سُمِع: ").append(word.heard).append(")");
            for (String n : word.letterNotes) sb.append("\n   • ").append(n);
            return sb.toString();
        }
    }

    // Surahs whose first ayah starts with huruf muqatta'at.
    private static final Set<Integer> MUQATTAAT = new HashSet<>();
    static {
        int[] s = {2,3,7,10,11,12,13,14,15,19,20,26,27,28,29,30,31,32,36,38,40,41,42,43,44,45,46,50,68};
        for (int x : s) MUQATTAAT.add(x);
    }

    private static String letterName(char c) {
        switch (c) {
            case 'ا': case 'أ': return "ألف";
            case 'ل': return "لام";
            case 'م': return "ميم";
            case 'ر': return "را";
            case 'ص': return "صاد";
            case 'ك': return "كاف";
            case 'ه': return "ها";
            case 'ي': return "يا";
            case 'ع': return "عين";
            case 'ط': return "طا";
            case 'س': return "سين";
            case 'ح': return "حا";
            case 'ق': return "قاف";
            case 'ن': return "نون";
            default: return null;
        }
    }

    /** Spoken (letter-name) form of a muqatta'at word, normalized; null if not convertible. */
    static String muqattaatSpoken(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 0x0621 && c <= 0x064A && c != 0x0640) {
                String n = letterName(c);
                if (n == null) return null;
                sb.append(ArabicNormalizer.normalize(n));
            }
        }
        return sb.length() == 0 ? null : ArabicNormalizer.collapse(sb);
    }

    /** Phrases said before/between recitation that are not part of the text. */
    private static final List<List<String>> IGNORED_PHRASES = new ArrayList<>();
    static {
        String[] p = {
            "بسم الله الرحمن الرحيم",
            "أعوذ بالله من الشيطان الرجيم",
            "صدق الله العظيم",
        };
        for (String ph : p) {
            List<String> toks = new ArrayList<>();
            for (String w : ArabicNormalizer.splitWords(ph)) toks.add(ArabicNormalizer.normalize(w));
            IGNORED_PHRASES.add(toks);
        }
    }

    private static int ignoredPhraseAt(List<String> tokens, int i) {
        for (List<String> ph : IGNORED_PHRASES) {
            if (i + ph.size() > tokens.size()) continue;
            boolean ok = true;
            for (int k = 0; k < ph.size() && ok; k++) ok = tokens.get(i + k).equals(ph.get(k));
            if (ok) return ph.size();
        }
        return 0;
    }

    private final List<Word> words = new ArrayList<>();
    private int committed = 0;
    private int tentativeEnd = 0;

    public RecitationTracker(List<QuranText.Ayah> ayat) {
        for (QuranText.Ayah a : ayat) {
            String[] raw = ArabicNormalizer.splitWords(a.text);
            int inAyah = 0;
            for (String r : raw) {
                String n = ArabicNormalizer.normalize(r);
                if (n.isEmpty()) continue; // ۞, numbers
                String alt = null;
                boolean muq = a.aya == 1 && MUQATTAAT.contains(a.sura)
                        && (inAyah == 0 || (a.sura == 42 && inAyah == 1));
                if (muq) alt = muqattaatSpoken(r);
                String alt2 = null;
                if (r.contains("و\u0670")) {
                    String keepWaw = ArabicNormalizer.normalize(r.replace("و\u0670", "و"));
                    if (!keepWaw.equals(n)) alt2 = keepWaw;
                }
                words.add(new Word(words.size(), a.sura, a.aya, r, n, alt, alt2));
                inAyah++;
            }
        }
    }

    public List<Word> words() { return words; }

    /** Index of the next word the reciter is expected to say. */
    public int position() { return tentativeEnd; }

    public boolean finished() { return committed >= words.size(); }

    /** Restart checking from a given word (e.g. the user tapped it). */
    public void restartAt(int index) {
        index = Math.max(0, Math.min(index, words.size()));
        for (int k = index; k < words.size(); k++) words.get(k).clear();
        committed = index;
        tentativeEnd = index;
    }

    public int count(State s) {
        int c = 0;
        for (Word w : words) if (w.state == s) c++;
        return c;
    }

    /** Process one recognition hypothesis. Returns newly committed mistakes (only for final results). */
    public List<Mistake> onResult(String text, boolean isFinal) {
        for (int k = committed; k < tentativeEnd && k < words.size(); k++) words.get(k).clear();

        List<String> tokens = new ArrayList<>();
        for (String r : ArabicNormalizer.splitWords(text == null ? "" : text)) {
            String n = ArabicNormalizer.normalize(r);
            if (!n.isEmpty()) tokens.add(n);
        }

        int c = committed + align(tokens, committed);

        List<Mistake> found = new ArrayList<>();
        if (isFinal) {
            for (int k = committed; k < c; k++) {
                Word w = words.get(k);
                if (w.state == State.WRONG || w.state == State.MISSED) found.add(new Mistake(w));
            }
            committed = c;
        }
        tentativeEnd = c;
        return found;
    }

    // ---- alignment ---------------------------------------------------------
    // Global alignment of the recognised tokens against the next expected words
    // (open end: words not reached yet cost nothing). Costs:
    //   word said correctly (1..6 tokens, or 2 words in 1 token)  0
    //   basmala / isti'adha                                         0
    //   repeating a word already said                               0.2
    //   word said differently (letter mistake)           0.75 .. 1.0
    //   unrelated token instead of the word                         1.2
    //   extra token                                                 1.0
    //   word skipped                          1.0 (+0.3 per further word in a row)
    private static final byte OP_MATCH = 1, OP_MERGE2 = 2, OP_SUB = 3, OP_INS = 4, OP_DEL = 5, OP_PHRASE = 6;

    /** Aligns tokens from word index `from`; marks word states; returns number of words consumed. */
    private int align(List<String> tokens, int from) {
        int n = tokens.size();
        int m = Math.min(words.size() - from, n + 12);
        if (n == 0 || m <= 0) return 0;
        double INF = Double.MAX_VALUE / 4;
        double[][] d = new double[n + 1][m + 1];
        byte[][] op = new byte[n + 1][m + 1];
        int[][] len = new int[n + 1][m + 1];
        for (double[] row : d) java.util.Arrays.fill(row, INF);
        d[0][0] = 0;
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= m; j++) {
                double cur = d[i][j];
                if (cur >= INF) continue;
                if (j < m) relax(d, op, len, i, j + 1, cur + (op[i][j] == OP_DEL ? 0.3 : 1.0), OP_DEL, 1);
                if (i < n) {
                    String tok = tokens.get(i);
                    int ph = ignoredPhraseAt(tokens, i);
                    if (ph > 0) relax(d, op, len, i + ph, j, cur, OP_PHRASE, ph);
                    relax(d, op, len, i + 1, j, cur + (isRepeatOf(tok, from + j) ? 0.2 : 1.0), OP_INS, 1);
                    if (j < m) {
                        int u = matchAt(tokens, i, from + j);
                        if (u > 0) relax(d, op, len, i + u, j + 1, cur, OP_MATCH, u);
                        if (j + 1 < m && tok.equals(ArabicNormalizer.collapse(
                                words.get(from + j).norm + words.get(from + j + 1).norm)))
                            relax(d, op, len, i + 1, j + 2, cur, OP_MERGE2, 1);
                        double sim = ArabicNormalizer.similarity(tok, words.get(from + j).norm);
                        double cost = sim >= 0.5 ? 0.75 + 0.5 * (1 - sim) : 1.2;
                        relax(d, op, len, i + 1, j + 1, cur + cost, OP_SUB, 1);
                    }
                }
            }
        }
        int bestJ = 0;
        for (int j = 1; j <= m; j++) if (d[n][j] <= d[n][bestJ] + 1e-9) bestJ = j; // ties: prefer progress

        int i = n, j = bestJ;
        while (i > 0 || j > 0) {
            byte o = op[i][j];
            int l = len[i][j];
            switch (o) {
                case OP_MATCH: mark(from + j - 1, State.CORRECT, null); i -= l; j -= 1; break;
                case OP_MERGE2: mark(from + j - 1, State.CORRECT, null); mark(from + j - 2, State.CORRECT, null); i -= 1; j -= 2; break;
                case OP_SUB: {
                    String tok = tokens.get(i - 1);
                    double sim = ArabicNormalizer.similarity(tok, words.get(from + j - 1).norm);
                    mark(from + j - 1, State.WRONG, tok);
                    if (sim < 0.5) words.get(from + j - 1).letterNotes = Collections.emptyList();
                    i -= 1; j -= 1; break;
                }
                case OP_INS: i -= 1; break;
                case OP_PHRASE: i -= l; break;
                case OP_DEL: mark(from + j - 1, State.MISSED, null); j -= 1; break;
                default: i = 0; j = 0; // unreachable
            }
        }
        return bestJ;
    }

    private static void relax(double[][] d, byte[][] op, int[][] len, int i, int j, double v, byte o, int l) {
        if (i < d.length && j < d[0].length && v < d[i][j] - 1e-9) { d[i][j] = v; op[i][j] = o; len[i][j] = l; }
    }

    /** Token repeats one of the few words before position p (reciter went back). */
    private boolean isRepeatOf(String tok, int p) {
        for (int back = 1; back <= 4 && p - back >= 0; back++)
            if (words.get(p - back).accepts(tok)) return true;
        return false;
    }

    private void mark(int idx, State s, String heard) {
        Word w = words.get(idx);
        w.state = s;
        w.heard = heard;
        w.letterNotes = (s == State.WRONG && heard != null)
                ? LetterDiff.describe(w.norm, heard)
                : Collections.<String>emptyList();
    }

    /** Number of tokens (1..6) starting at i whose joined form equals word c; 0 if none. */
    private int matchAt(List<String> tokens, int i, int c) {
        if (c >= words.size()) return 0;
        Word w = words.get(c);
        int maxLen = Math.max(w.norm.length(), w.alt == null ? 0 : w.alt.length());
        StringBuilder joined = new StringBuilder();
        for (int u = 1; u <= 6 && i + u - 1 < tokens.size(); u++) {
            joined.append(tokens.get(i + u - 1));
            String j = ArabicNormalizer.collapse(joined);
            if (w.accepts(j)) return u;
            if (j.length() > maxLen + 1) break;
        }
        return 0;
    }

}
