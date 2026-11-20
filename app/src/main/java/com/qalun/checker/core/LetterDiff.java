package com.qalun.checker.core;

import java.util.ArrayList;
import java.util.List;

/** Letter-by-letter comparison of an expected word and what was heard. */
public final class LetterDiff {
    private LetterDiff() {}

    /** Human-readable list of letter differences, e.g. "ناقص: ح" / "قلتَ ه بدل ح". */
    public static List<String> describe(String expected, String heard) {
        String a = expected, b = heard;
        int n = a.length(), m = b.length();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++)
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
            }
        List<String> ops = new ArrayList<>();
        int i = n, j = m;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && a.charAt(i - 1) == b.charAt(j - 1) && d[i][j] == d[i - 1][j - 1]) {
                i--; j--;
            } else if (i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + 1) {
                ops.add(0, "قلتَ «" + b.charAt(j - 1) + "» بدل «" + a.charAt(i - 1) + "»");
                i--; j--;
            } else if (i > 0 && d[i][j] == d[i - 1][j] + 1) {
                ops.add(0, "حرف ناقص: «" + a.charAt(i - 1) + "»");
                i--;
            } else {
                ops.add(0, "حرف زائد: «" + b.charAt(j - 1) + "»");
                j--;
            }
        }
        return ops;
    }
}
