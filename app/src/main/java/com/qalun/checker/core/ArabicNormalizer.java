package com.qalun.checker.core;

/**
 * Turns Uthmani (KFGQPC Qalun) text and ordinary Arabic speech-recognition
 * output into the same comparable letter skeleton.
 *
 * Rules (tuned so a correct reading in standard spelling matches the
 * Uthmani text for ~99.85% of words):
 *  - all harakat, tanween, shadda, sukun, waqf and small Quranic marks removed
 *  - every alif and every hamza form removed (spelling of these differs
 *    between the mushaf and normal writing; speech engines are unreliable on them)
 *  - waw written in place of alif (salawat) removed
 *  - alif maqsura: at word end -> ي, inside a word -> removed
 *  - ة -> ت, undotted final yeh (ے) -> ي, small high yeh (ۧ) -> ي
 *  - doubled letters collapsed (shadda vs. written double lam, etc.)
 */
public final class ArabicNormalizer {
    private ArabicNormalizer() {}

    private static boolean isDropped(char c) {
        if (c >= 0x064B && c <= 0x0658) return true;      // harakat, shadda, sukun, madda, small hamza...
        if (c == 0x065E || c == 0x0640 || c == 0x0670) return true; // two-dot fatha, tatweel, dagger alif
        if (c == 0x200E || c == 0x200F) return true;      // direction marks
        if (c >= 0x06D6 && c <= 0x06ED && c != 0x06E7) return true; // Quranic annotation marks
        if (c >= 0x0660 && c <= 0x0669) return true;      // Arabic-Indic digits
        if (c >= '0' && c <= '9') return true;
        return false;
    }

    private static String mapChar(char c) {
        switch (c) {
            case 'ا': case 'أ': case 'إ': case 'آ': case 'ٱ':
            case 'ء': case 'ؤ': case 'ئ':
                return "";
            case 'ة': return "ت";
            case 'ے': case 'ۧ': case 'ی': return "ي";
            case 'ک': return "ك";
            default: return String.valueOf(c);
        }
    }

    public static String normalize(String word) {
        if (word == null) return "";
        // a waw or ya carrying a small alif directly is read as alif (صلوة، لنريٰك): drop it
        // (at the end of a word ىٰ/يٰ stays a ya, as in على / موسى)
        String w = word.replace("و\u0670", "")
                .replaceAll("[يى]\u0670(?=[\u0621-\u064A])", "")
                .replace('\u06D2', 'ي');
        StringBuilder kept = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (!isDropped(c)) kept.append(c);
        }
        StringBuilder mapped = new StringBuilder(kept.length());
        int n = kept.length();
        for (int i = 0; i < n; i++) {
            char c = kept.charAt(i);
            if (c == 'ى') {
                if (i == n - 1) mapped.append('ي');
                continue;
            }
            mapped.append(mapChar(c));
        }
        return collapse(mapped);
    }

    /** Collapse runs of the same letter into one. */
    public static String collapse(CharSequence s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (out.length() == 0 || out.charAt(out.length() - 1) != c) out.append(c);
        }
        return out.toString();
    }

    /** Splits text into raw words (spaces, no-break spaces). */
    public static String[] splitWords(String text) {
        String t = text.trim();
        if (t.isEmpty()) return new String[0];
        return t.split("[\\s\\u00A0]+");
    }

    public static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /** 1.0 = identical, 0.0 = nothing in common. */
    public static double similarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1.0;
        return 1.0 - (double) levenshtein(a, b) / max;
    }
}
