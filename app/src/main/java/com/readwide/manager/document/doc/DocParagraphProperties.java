package com.readwide.manager.document.doc;

import androidx.annotation.NonNull;

import com.readwide.manager.document.render.ParagraphStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes legacy Word (.doc, [MS-DOC]) paragraph formatting.
 *
 * Paragraph properties are stored as PAPX (paragraph property exceptions) packed
 * into 512-byte Formatted Disk Pages (FKPs) in the WordDocument stream, indexed
 * by the bin table PlcBtePapx in the table stream. The PAPX FKP layout differs
 * from the CHPX one: after the (cpara + 1) file-position entries come cpara
 * 13-byte BxPap structures whose first byte is a word offset to a PapxInFkp, and
 * each PapxInFkp begins with a length byte followed by a 2-byte style index
 * (istd) and then the SPRM list.
 *
 * This stage decodes the properties that affect visible layout: horizontal
 * alignment and left/right/first-line indents. After construction,
 * {@link #styleForFc(int)} returns the effective paragraph style for the file
 * character position of a paragraph.
 */
final class DocParagraphProperties {

    // Paragraph SPRM opcodes (sgc == 1) decoded in this stage.
    private static final int SPRM_P_JC80 = 0x2461;       // alignment, 1 byte
    private static final int SPRM_P_JC = 0x2403;         // alignment (legacy), 1 byte
    private static final int SPRM_P_DXA_LEFT = 0x845E;   // left indent, twips, 2 bytes
    private static final int SPRM_P_DXA_RIGHT = 0x845D;  // right indent, twips, 2 bytes
    private static final int SPRM_P_DXA_LEFT1 = 0x8460;  // first-line indent, signed twips, 2 bytes

    private static final float TWIPS_PER_POINT = 20f;

    private final int[] fcStarts;
    private final int[] fcEnds;
    private final ParaStyle[] styles;

    private DocParagraphProperties(int[] fcStarts, int[] fcEnds, ParaStyle[] styles) {
        this.fcStarts = fcStarts;
        this.fcEnds = fcEnds;
        this.styles = styles;
    }

    /** An empty table: every FC resolves to the default paragraph style. */
    static DocParagraphProperties none() {
        return new DocParagraphProperties(new int[0], new int[0], new ParaStyle[0]);
    }

    static DocParagraphProperties parse(@NonNull byte[] wd, @NonNull byte[] table, int fcPlcbte, int lcbPlcbte) {
        if (lcbPlcbte < 8 || fcPlcbte < 0 || (long) fcPlcbte + lcbPlcbte > table.length) {
            return none();
        }
        int m = (lcbPlcbte - 4) / 8;
        if (m <= 0) return none();
        int aPnBase = fcPlcbte + (m + 1) * 4;

        List<int[]> ranges = new ArrayList<>();
        List<ParaStyle> list = new ArrayList<>();

        for (int j = 0; j < m; j++) {
            int pn = i32(table, aPnBase + j * 4) & 0x3FFFFF;
            int fkp = pn * 512;
            if (fkp < 0 || fkp + 512 > wd.length) continue;
            int cpara = wd[fkp + 511] & 0xFF;
            if (cpara == 0) continue;
            int rgfcBase = fkp;
            int rgbxBase = fkp + 4 * (cpara + 1);
            if (rgbxBase + cpara * 13 > wd.length) continue;
            for (int r = 0; r < cpara; r++) {
                int f0 = i32(wd, rgfcBase + r * 4);
                int f1 = i32(wd, rgfcBase + (r + 1) * 4);
                if (f1 <= f0) continue;
                int boff = wd[rgbxBase + r * 13] & 0xFF; // BxPap.bOffset (word offset into the FKP)
                ParaStyle style = ParaStyle.DEFAULT;
                if (boff != 0) {
                    int cpos = fkp + boff * 2;
                    if (cpos >= 0 && cpos < wd.length) {
                        int cb = wd[cpos] & 0xFF;
                        int grpStart;
                        int grpLen;
                        if (cb != 0) {
                            grpLen = 2 * cb - 1;
                            grpStart = cpos + 1;
                        } else if (cpos + 1 < wd.length) {
                            grpLen = 2 * (wd[cpos + 1] & 0xFF);
                            grpStart = cpos + 2;
                        } else {
                            grpLen = 0;
                            grpStart = cpos + 2;
                        }
                        if (grpLen >= 2 && grpStart + grpLen <= wd.length) {
                            style = decodePapx(wd, grpStart, grpLen);
                        }
                    }
                }
                ranges.add(new int[]{f0, f1});
                list.add(style);
            }
        }

        int size = list.size();
        int[] fs = new int[size];
        int[] fe = new int[size];
        ParaStyle[] st = new ParaStyle[size];
        for (int i = 0; i < size; i++) {
            fs[i] = ranges.get(i)[0];
            fe[i] = ranges.get(i)[1];
            st[i] = list.get(i);
        }
        sortByStart(fs, fe, st);
        return new DocParagraphProperties(fs, fe, st);
    }

    /** Effective paragraph style for a paragraph identified by a file character position. */
    @NonNull
    ParaStyle styleForFc(int fc) {
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
        return ParaStyle.DEFAULT;
    }

    // ---- SPRM decoding ----

    private static ParaStyle decodePapx(byte[] b, int grpStart, int grpLen) {
        int alignment = -1;
        float left = 0f;
        float right = 0f;
        float firstLine = 0f;
        boolean hasLeft = false;
        boolean hasRight = false;
        boolean hasFirstLine = false;
        int i = grpStart + 2; // skip the 2-byte istd that precedes the grpprl
        int end = grpStart + grpLen;
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
                case SPRM_P_JC80:
                case SPRM_P_JC: {
                    int v = b[operandStart] & 0xFF;
                    if (v <= 3) alignment = v;
                    break;
                }
                case SPRM_P_DXA_LEFT:
                    if (operandLen >= 2) {
                        float v = s16(b, operandStart) / TWIPS_PER_POINT;
                        if (v != 0f) {
                            left = v;
                            hasLeft = true;
                        }
                    }
                    break;
                case SPRM_P_DXA_RIGHT:
                    if (operandLen >= 2) {
                        float v = s16(b, operandStart) / TWIPS_PER_POINT;
                        if (v != 0f) {
                            right = v;
                            hasRight = true;
                        }
                    }
                    break;
                case SPRM_P_DXA_LEFT1:
                    if (operandLen >= 2) {
                        float v = s16(b, operandStart) / TWIPS_PER_POINT;
                        if (v != 0f) {
                            firstLine = v;
                            hasFirstLine = true;
                        }
                    }
                    break;
                default:
                    break;
            }
            i = operandStart + operandLen;
        }
        if (alignment < 0 && !hasLeft && !hasRight && !hasFirstLine) {
            return ParaStyle.DEFAULT;
        }
        return new ParaStyle(alignment, left, right, firstLine, hasLeft, hasRight, hasFirstLine);
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

    private static void sortByStart(int[] fs, int[] fe, ParaStyle[] st) {
        for (int i = 1; i < fs.length; i++) {
            int s = fs[i], e = fe[i];
            ParaStyle c = st[i];
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

    private static short s16(byte[] b, int off) {
        return (short) ((b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8));
    }

    private static int i32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    /** Immutable snapshot of paragraph layout decoded from a PAPX. */
    static final class ParaStyle {
        static final ParaStyle DEFAULT = new ParaStyle(-1, 0f, 0f, 0f, false, false, false);

        final int alignment;          // -1 default, 0 left, 1 center, 2 right, 3 justify
        final float leftIndentPt;
        final float rightIndentPt;
        final float firstLineIndentPt; // may be negative (hanging indent)
        final boolean hasLeft;
        final boolean hasRight;
        final boolean hasFirstLine;

        ParaStyle(int alignment, float leftIndentPt, float rightIndentPt, float firstLineIndentPt,
                  boolean hasLeft, boolean hasRight, boolean hasFirstLine) {
            this.alignment = alignment;
            this.leftIndentPt = leftIndentPt;
            this.rightIndentPt = rightIndentPt;
            this.firstLineIndentPt = firstLineIndentPt;
            this.hasLeft = hasLeft;
            this.hasRight = hasRight;
            this.hasFirstLine = hasFirstLine;
        }

        boolean isDefault() {
            return alignment < 0 && !hasLeft && !hasRight && !hasFirstLine;
        }

        @NonNull
        ParagraphStyle toParagraphStyle() {
            ParagraphStyle.Builder b = new ParagraphStyle.Builder();
            switch (alignment) {
                case 1:
                    b.alignment(ParagraphStyle.Alignment.CENTER);
                    break;
                case 2:
                    b.alignment(ParagraphStyle.Alignment.RIGHT);
                    break;
                case 3:
                    b.alignment(ParagraphStyle.Alignment.JUSTIFY);
                    break;
                default:
                    break; // 0 or unset: LEFT is the model default
            }
            if (hasLeft) b.marginLeftPt(leftIndentPt);
            if (hasRight) b.marginRightPt(rightIndentPt);
            if (hasFirstLine) b.textIndentPt(firstLineIndentPt);
            return b.build();
        }
    }
}
