package com.textview.reader.util;

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
            char cl = left.charAt(i);
            char cr = right.charAt(j);
            if (Character.isDigit(cl) && Character.isDigit(cr)) {
                int si = i;
                int sj = j;
                while (i < nl && left.charAt(i) == '0') i++;
                while (j < nr && right.charAt(j) == '0') j++;
                int di = i;
                int dj = j;
                while (i < nl && Character.isDigit(left.charAt(i))) i++;
                while (j < nr && Character.isDigit(right.charAt(j))) j++;
                int lenL = i - di;
                int lenR = j - dj;
                if (lenL != lenR) return lenL - lenR;
                for (int k = 0; k < lenL; k++) {
                    int diff = left.charAt(di + k) - right.charAt(dj + k);
                    if (diff != 0) return diff;
                }
                int zeroDiff = (di - si) - (dj - sj);
                if (zeroDiff != 0) return zeroDiff;
            } else {
                int diff = Character.toLowerCase(cl) - Character.toLowerCase(cr);
                if (diff != 0) return diff;
                i++;
                j++;
            }
        }
        return (nl - i) - (nr - j);
    }
}
