package com.readwide.manager.util;

import androidx.annotation.Nullable;

/** Case-insensitive natural string comparator: page2 < page10. */
public final class NaturalSort {
    private NaturalSort() {}

    public static int compare(@Nullable String left, @Nullable String right) {
        if (left == null && right == null) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        int i = 0;
        int j = 0;
        int nl = left.length();
        int nr = right.length();
        while (i < nl && j < nr) {
            int cl = left.codePointAt(i);
            int cr = right.codePointAt(j);
            if (Character.isDigit(cl) && Character.isDigit(cr)) {
                int zerosL = 0, zerosR = 0;
                while (i < nl && Character.digit(left.codePointAt(i), 10) == 0) {
                    i += Character.charCount(left.codePointAt(i)); zerosL++;
                }
                while (j < nr && Character.digit(right.codePointAt(j), 10) == 0) {
                    j += Character.charCount(right.codePointAt(j)); zerosR++;
                }
                int di = i;
                int dj = j;
                int lenL = 0, lenR = 0;
                while (i < nl && Character.isDigit(left.codePointAt(i))) {
                    i += Character.charCount(left.codePointAt(i)); lenL++;
                }
                while (j < nr && Character.isDigit(right.codePointAt(j))) {
                    j += Character.charCount(right.codePointAt(j)); lenR++;
                }
                if (lenL != lenR) return lenL - lenR;
                for (int k = 0; k < lenL; k++) {
                    int dl = left.codePointAt(di), dr = right.codePointAt(dj);
                    int diff = Character.digit(dl, 10) - Character.digit(dr, 10);
                    if (diff != 0) return diff;
                    di += Character.charCount(dl);
                    dj += Character.charCount(dr);
                }
                int zeroDiff = zerosL - zerosR;
                if (zeroDiff != 0) return zeroDiff;
            } else {
                // All decimal scripts occupy the same digit ordering band.
                // Mixing numeric length with raw Unicode ordering could create
                // cycles such as 22 < c < Arabic-Indic 1 < 22.
                int l = Character.isDigit(cl) ? '0' + Character.digit(cl, 10) : Character.toLowerCase(cl);
                int r = Character.isDigit(cr) ? '0' + Character.digit(cr, 10) : Character.toLowerCase(cr);
                int diff = l - r;
                if (diff != 0) return diff;
                i += Character.charCount(cl);
                j += Character.charCount(cr);
            }
        }
        return (nl - i) - (nr - j);
    }
}
