package com.readwide.manager.document.doc;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.document.render.TextStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes legacy Word (.doc, [MS-DOC]) character formatting.
 *
 * Character properties are stored as CHPX (character property exceptions)
 * packed into 512-byte Formatted Disk Pages (FKPs) inside the WordDocument
 * stream. The bin table PlcBteChpx (in the table stream) maps ranges of file
 * character positions (FCs) to the FKP page that holds their CHPX runs. Each
 * CHPX is a list of SPRMs (single property modifiers); this class decodes the
 * handful that affect visible appearance in stage 2: bold, italic, underline,
 * strike-through, font size, and color.
 *
 * After construction, {@link #styleForFc(int)} returns the effective style for
 * any FC, so the text reconstruction can split paragraphs into styled runs.
 */
final class DocCharacterProperties {

    // Character SPRM opcodes (sgc == 2) decoded in this stage.
    private static final int SPRM_C_F_BOLD = 0x0835;      // ToggleOperand, 1 byte
    private static final int SPRM_C_F_ITALIC = 0x0836;    // ToggleOperand, 1 byte
    private static final int SPRM_C_F_STRIKE = 0x0837;    // ToggleOperand, 1 byte
    private static final int SPRM_C_KUL = 0x2A3E;         // underline kind, 1 byte
    private static final int SPRM_C_HPS = 0x4A43;         // font size in half points, 2 bytes
    private static final int SPRM_C_CV = 0x6870;          // COLORREF 0x00BBGGRR, 4 bytes
    private static final int SPRM_C_ICO = 0x2A42;         // palette color index, 1 byte

    // Legacy 17-entry Word color palette used by sprmCIco (index 0 == auto).
    private static final String[] ICO_PALETTE = {
            null, "#000000", "#0000FF", "#00FFFF", "#00FF00", "#FF00FF", "#FF0000",
            "#FFFF00", "#FFFFFF", "#000080", "#008080", "#008000", "#800080",
            "#800000", "#808000", "#808080", "#C0C0C0"
    };

    private final int[] fcStarts;
    private final int[] fcEnds;
    private final ChpStyle[] styles;

    private DocCharacterProperties(int[] fcStarts, int[] fcEnds, ChpStyle[] styles) {
        this.fcStarts = fcStarts;
        this.fcEnds = fcEnds;
        this.styles = styles;
    }

    /** An empty table: every FC resolves to the default style. */
    static DocCharacterProperties none() {
        return new DocCharacterProperties(new int[0], new int[0], new ChpStyle[0]);
    }

    static DocCharacterProperties parse(@NonNull byte[] wd, @NonNull byte[] table, int fcPlcbte, int lcbPlcbte) {
        if (lcbPlcbte < 8 || fcPlcbte < 0 || (long) fcPlcbte + lcbPlcbte > table.length) {
            return none();
        }
        int m = (lcbPlcbte - 4) / 8;
        if (m <= 0) return none();
        int aFcBase = fcPlcbte;
        int aPnBase = fcPlcbte + (m + 1) * 4;

        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        List<ChpStyle> list = new ArrayList<>();

        for (int j = 0; j < m; j++) {
            int pn = i32(table, aPnBase + j * 4) & 0x3FFFFF;
            int fkp = pn * 512;
            if (fkp < 0 || fkp + 512 > wd.length) continue;
            int crun = wd[fkp + 511] & 0xFF;
            if (crun == 0) continue;
            int rgfcBase = fkp;
            int rgbBase = fkp + 4 * (crun + 1);
            if (rgbBase + crun > wd.length) continue;
            for (int r = 0; r < crun; r++) {
                int f0 = i32(wd, rgfcBase + r * 4);
                int f1 = i32(wd, rgfcBase + (r + 1) * 4);
                if (f1 <= f0) continue;
                int boff = wd[rgbBase + r] & 0xFF;
                ChpStyle style = ChpStyle.DEFAULT;
                if (boff != 0) {
                    int cpos = fkp + boff * 2;
                    if (cpos >= 0 && cpos < wd.length) {
                        int cb = wd[cpos] & 0xFF;
                        int grpStart = cpos + 1;
                        if (grpStart + cb <= wd.length) {
                            style = decodeChpx(wd, grpStart, cb);
                        }
                    }
                }
                starts.add(f0);
                ends.add(f1);
                list.add(style);
            }
        }

        int size = list.size();
        int[] fs = new int[size];
        int[] fe = new int[size];
        ChpStyle[] st = new ChpStyle[size];
        for (int i = 0; i < size; i++) {
            fs[i] = starts.get(i);
            fe[i] = ends.get(i);
            st[i] = list.get(i);
        }
        // Bin-table entries are emitted in ascending FC order already, but sort
        // defensively so the floor search below is always valid.
        sortByStart(fs, fe, st);
        return new DocCharacterProperties(fs, fe, st);
    }

    /** Effective character style for a file character position (FC). */
    @NonNull
    ChpStyle styleForFc(int fc) {
        int lo = 0;
        int hi = fcStarts.length - 1;
        int floor = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (fcStarts[mid] <= fc) {
                floor = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (floor >= 0 && fc < fcEnds[floor]) return styles[floor];
        return ChpStyle.DEFAULT;
    }

    // ---- SPRM decoding ----

    private static ChpStyle decodeChpx(byte[] b, int start, int len) {
        boolean bold = false, italic = false, underline = false, strike = false;
        float sizePt = 0f;
        String color = null;
        int i = start;
        int end = start + len;
        while (i + 2 <= end) {
            int sprm = u16(b, i);
            i += 2;
            int spra = (sprm >> 13) & 7;
            int operandStart;
            int operandLen;
            if (spra == 6) {
                if (i >= end) break;
                operandLen = b[i] & 0xFF;
                i += 1;
                operandStart = i;
            } else {
                operandLen = spraSize(spra);
                operandStart = i;
            }
            if (operandStart + operandLen > end) break;
            switch (sprm) {
                case SPRM_C_F_BOLD:
                    bold = toggleOn(b[operandStart] & 0xFF);
                    break;
                case SPRM_C_F_ITALIC:
                    italic = toggleOn(b[operandStart] & 0xFF);
                    break;
                case SPRM_C_F_STRIKE:
                    strike = toggleOn(b[operandStart] & 0xFF);
                    break;
                case SPRM_C_KUL:
                    underline = (b[operandStart] & 0xFF) != 0;
                    break;
                case SPRM_C_HPS:
                    if (operandLen >= 2) sizePt = u16(b, operandStart) / 2.0f;
                    break;
                case SPRM_C_CV:
                    if (operandLen >= 4) {
                        color = String.format("#%02X%02X%02X",
                                b[operandStart] & 0xFF, b[operandStart + 1] & 0xFF, b[operandStart + 2] & 0xFF);
                    }
                    break;
                case SPRM_C_ICO:
                    if (color == null) {
                        int ico = b[operandStart] & 0xFF;
                        if (ico < ICO_PALETTE.length) color = ICO_PALETTE[ico];
                    }
                    break;
                default:
                    break;
            }
            i = operandStart + operandLen;
        }
        if (!bold && !italic && !underline && !strike && sizePt <= 0f && color == null) {
            return ChpStyle.DEFAULT;
        }
        return new ChpStyle(bold, italic, underline, strike, sizePt, color);
    }

    private static boolean toggleOn(int operand) {
        // ToggleOperand: 0 off, 1 on, 128 inherit, 129 negate base. The stage 1
        // base is unformatted, so both 1 and 129 resolve to on.
        return operand == 1 || operand == 129;
    }

    private static int spraSize(int spra) {
        switch (spra) {
            case 0:
            case 1:
                return 1;
            case 2:
            case 4:
            case 5:
                return 2;
            case 3:
                return 4;
            case 7:
                return 3;
            default:
                return 0;
        }
    }

    private static void sortByStart(int[] fs, int[] fe, ChpStyle[] st) {
        // Simple insertion sort; span counts are small and already near-sorted.
        for (int i = 1; i < fs.length; i++) {
            int s = fs[i], e = fe[i];
            ChpStyle c = st[i];
            int j = i - 1;
            while (j >= 0 && fs[j] > s) {
                fs[j + 1] = fs[j];
                fe[j + 1] = fe[j];
                st[j + 1] = st[j];
                j--;
            }
            fs[j + 1] = s;
            fe[j + 1] = e;
            st[j + 1] = c;
        }
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    /** Immutable snapshot of the character appearance decoded from a CHPX. */
    static final class ChpStyle {
        static final ChpStyle DEFAULT = new ChpStyle(false, false, false, false, 0f, null);

        final boolean bold;
        final boolean italic;
        final boolean underline;
        final boolean strike;
        final float sizePt;      // 0 == inherit default
        final String color;      // null == inherit default

        ChpStyle(boolean bold, boolean italic, boolean underline, boolean strike, float sizePt, @Nullable String color) {
            this.bold = bold;
            this.italic = italic;
            this.underline = underline;
            this.strike = strike;
            this.sizePt = sizePt;
            this.color = color;
        }

        boolean isDefault() {
            return !bold && !italic && !underline && !strike && sizePt <= 0f && color == null;
        }

        @NonNull
        TextStyle toTextStyle() {
            TextStyle.Builder b = new TextStyle.Builder();
            if (bold) b.bold(true);
            if (italic) b.italic(true);
            if (underline) b.underline(true);
            if (strike) b.strike(true);
            if (sizePt > 0f) b.fontSizePt(sizePt);
            if (color != null) b.color(color);
            return b.build();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChpStyle)) return false;
            ChpStyle s = (ChpStyle) o;
            return bold == s.bold && italic == s.italic && underline == s.underline
                    && strike == s.strike && Float.compare(sizePt, s.sizePt) == 0
                    && (color == null ? s.color == null : color.equals(s.color));
        }

        @Override
        public int hashCode() {
            int h = (bold ? 1 : 0);
            h = 31 * h + (italic ? 1 : 0);
            h = 31 * h + (underline ? 1 : 0);
            h = 31 * h + (strike ? 1 : 0);
            h = 31 * h + Float.floatToIntBits(sizePt);
            h = 31 * h + (color == null ? 0 : color.hashCode());
            return h;
        }
    }
}
