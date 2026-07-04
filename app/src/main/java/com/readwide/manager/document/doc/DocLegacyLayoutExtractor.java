package com.readwide.manager.document.doc;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.document.doc.DocCharacterProperties.ChpStyle;
import com.readwide.manager.document.render.ParagraphStyle;
import com.readwide.manager.document.render.RenderedBlock;
import com.readwide.manager.document.render.RenderedDocument;
import com.readwide.manager.document.render.RenderedPage;
import com.readwide.manager.document.render.RenderedParagraph;
import com.readwide.manager.document.render.RenderedRun;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Stage 3 renderer for legacy binary Word (.doc, [MS-DOC]) documents.
 *
 * It reconstructs the main document text from the piece table (CLX) in the OLE2
 * table stream and maps it to the shared {@link RenderedDocument} model. Stage 1
 * produced readable paragraphs; stage 2 added character formatting (bold,
 * italic, underline, strike, size, color) as styled runs; stage 3 adds
 * paragraph formatting - horizontal alignment and left/right/first-line indents
 * - decoded from the PAPX runs and applied per paragraph. Real table structure
 * and inline images remain out of scope: table cell marks still split into
 * separate paragraphs and pictures are omitted.
 *
 * Every reconstructed character keeps its file character position (FC) so its
 * CHPX character style and the paragraph's PAPX style can both be resolved.
 */
public final class DocLegacyLayoutExtractor {

    private static final float PAGE_WIDTH_PT = 595f;
    private static final float PAGE_HEIGHT_PT = 842f;
    private static final float MARGIN_PT = 54f;
    private static final int DEFAULT_PARAGRAPHS_PER_PAGE = 28;
    // Defensive ceiling so a crafted piece table (many pieces each re-reading the
    // stream) cannot amplify a small file into an unbounded reconstruction.
    private static final int MAX_TEXT_CHARS = 8 * 1024 * 1024;

    private static final int FIB_WIDENT = 0xA5EC;
    private static final int FIB_FLAG_WHICH_TABLE = 0x0200;
    // Pair indices within fibRgFcLcb97 (stable across later FIB versions).
    private static final int CLX_PAIR_INDEX = 33;   // fcClx / lcbClx
    private static final int CHPX_PAIR_INDEX = 12;  // fcPlcfbteChpx / lcbPlcfbteChpx
    private static final int PAPX_PAIR_INDEX = 13;  // fcPlcfbtePapx / lcbPlcfbtePapx

    private static final Charset CP1252 = charset("windows-1252");

    private DocLegacyLayoutExtractor() {}

    @Nullable
    public static RenderedDocument extract(@NonNull File file, @Nullable String title, int paragraphsPerPage)
            throws IOException {
        return extractFromBytes(readAllBytes(file), title, paragraphsPerPage);
    }

    @Nullable
    static RenderedDocument extractFromBytes(@NonNull byte[] bytes, @Nullable String title, int paragraphsPerPage)
            throws IOException {
        CompoundFileReader cfb = new CompoundFileReader(bytes);
        byte[] wd = cfb.getStream("WordDocument");
        if (wd == null || wd.length < 34) throw new IOException("Not a Word document (no WordDocument stream)");

        int wIdent = u16(wd, 0);
        if (wIdent != FIB_WIDENT) throw new IOException("Unrecognized Word FIB signature");
        int flags = u16(wd, 0x0A);
        boolean whichTable1 = (flags & FIB_FLAG_WHICH_TABLE) != 0;

        byte[] table = cfb.getStream(whichTable1 ? "1Table" : "0Table");
        if (table == null) {
            table = cfb.getStream(whichTable1 ? "0Table" : "1Table");
        }
        if (table == null) throw new IOException("Word document has no table stream");

        // Walk the variable-length FIB to the fc/lcb array. Every step advances p
        // by a file-controlled count, so re-check the range before each read.
        int p = 32;
        requireRange(wd, p, 2);
        int csw = u16(wd, p); p += 2 + csw * 2;
        requireRange(wd, p, 2);
        int cslw = u16(wd, p); p += 2;
        requireRange(wd, p, cslw * 4);
        p += cslw * 4;
        requireRange(wd, p, 2);
        int cbRgFcLcb = u16(wd, p); p += 2;
        if (cbRgFcLcb <= CLX_PAIR_INDEX) throw new IOException("Word FIB has no piece table locator");
        requireRange(wd, p, cbRgFcLcb * 8);
        int fcClx = i32(wd, p + CLX_PAIR_INDEX * 8);
        int lcbClx = i32(wd, p + CLX_PAIR_INDEX * 8 + 4);
        if (fcClx < 0 || lcbClx <= 0 || (long) fcClx + lcbClx > table.length) {
            throw new IOException("Word piece table is missing or out of range");
        }

        // Character and paragraph property bin tables (stages 2-3); absence is tolerated.
        int fcPlcbteChpx = i32(wd, p + CHPX_PAIR_INDEX * 8);
        int lcbPlcbteChpx = i32(wd, p + CHPX_PAIR_INDEX * 8 + 4);
        DocCharacterProperties chp = DocCharacterProperties.parse(wd, table, fcPlcbteChpx, lcbPlcbteChpx);

        int fcPlcbtePapx = i32(wd, p + PAPX_PAIR_INDEX * 8);
        int lcbPlcbtePapx = i32(wd, p + PAPX_PAIR_INDEX * 8 + 4);
        DocParagraphProperties pap = DocParagraphProperties.parse(wd, table, fcPlcbtePapx, lcbPlcbtePapx);

        Reconstructed rec = reconstructText(wd, table, fcClx, lcbClx);
        if (rec == null || rec.text.isEmpty()) return null;

        List<RenderedParagraph> paragraphs = buildStyledParagraphs(rec, chp, pap);
        if (paragraphs.isEmpty()) return null;

        return buildDocument(paragraphs, title, paragraphsPerPage);
    }

    // ---- text reconstruction from the piece table (keeps FC per character) ----

    @Nullable
    private static Reconstructed reconstructText(byte[] wd, byte[] table, int fcClx, int lcbClx) throws IOException {
        int i = fcClx;
        int end = fcClx + lcbClx;
        // Skip any Prc entries (0x01 marker, 2-byte length, then that many bytes).
        while (i < end && (table[i] & 0xFF) == 0x01) {
            if (i + 3 > table.length) throw new IOException("Word piece table Prc out of range");
            int cb = u16(table, i + 1);
            i += 3 + cb;
        }
        if (i >= end || (table[i] & 0xFF) != 0x02) throw new IOException("Word piece table has no Pcdt");
        if (i + 5 > table.length) throw new IOException("Word piece table Pcdt out of range");
        long lcbPlc = u32(table, i + 1);
        i += 5;
        if (lcbPlc < 4 || (long) i + lcbPlc > table.length) throw new IOException("Word piece table length invalid");
        int plc = i;
        int pieces = (int) ((lcbPlc - 4) / 12);
        if (pieces <= 0) return null;

        int cpBase = plc;
        int pcdBase = plc + (pieces + 1) * 4;

        StringBuilder sb = new StringBuilder();
        int[] fc = new int[256];
        int fcLen = 0;

        for (int k = 0; k < pieces; k++) {
            if (sb.length() >= MAX_TEXT_CHARS) break;
            int cpStart = i32(table, cpBase + k * 4);
            int cpEnd = i32(table, cpBase + (k + 1) * 4);
            int chars = cpEnd - cpStart;
            if (chars <= 0) continue;
            int fcRaw = i32(table, pcdBase + k * 8 + 2);
            boolean compressed = (fcRaw & 0x40000000) != 0;
            int offset = fcRaw & 0x3FFFFFFF;
            String piece;
            int base;
            int step;
            if (compressed) {
                base = offset / 2;
                step = 1;
                int avail = Math.max(0, Math.min(chars, wd.length - base));
                if (avail <= 0) continue;
                piece = new String(wd, base, avail, CP1252);
            } else {
                base = offset;
                step = 2;
                int byteLen = chars * 2;
                int avail = Math.max(0, Math.min(byteLen, wd.length - offset));
                avail -= (avail % 2);
                if (avail <= 0) continue;
                piece = new String(wd, offset, avail, StandardCharsets.UTF_16LE);
            }
            int pieceLen = piece.length();
            if (fcLen + pieceLen > fc.length) {
                fc = Arrays.copyOf(fc, Math.max(fc.length * 2, fcLen + pieceLen));
            }
            for (int c = 0; c < pieceLen; c++) {
                fc[fcLen++] = base + c * step;
            }
            sb.append(piece);
        }
        if (fcLen == 0) return null;
        return new Reconstructed(sb.toString(), (fcLen == fc.length) ? fc : Arrays.copyOf(fc, fcLen));
    }

    // ---- paragraph splitting into styled runs with paragraph styles ----

    private static List<RenderedParagraph> buildStyledParagraphs(Reconstructed rec,
                                                                 DocCharacterProperties chp,
                                                                 DocParagraphProperties pap) {
        String text = rec.text;
        int[] fc = rec.fc;
        List<RenderedParagraph> paragraphs = new ArrayList<>();
        List<RenderedRun> paraRuns = new ArrayList<>();
        StringBuilder runText = new StringBuilder();
        ChpStyle runStyle = ChpStyle.DEFAULT;
        int runAnchorStart = 0;
        int anchor = 0;
        int fieldState = 0; // 0 normal, 1 inside field code (drop), 2 field result (keep)
        int paraFirstFc = -1;
        int n = text.length();

        for (int idx = 0; idx < n; idx++) {
            char c = text.charAt(idx);
            if (c == 0x13) { fieldState = 1; continue; }
            if (c == 0x14) { fieldState = 2; continue; }
            if (c == 0x15) { fieldState = 0; continue; }
            if (fieldState == 1) continue;

            int cfc = (idx < fc.length) ? fc[idx] : -1;
            if (paraFirstFc < 0) paraFirstFc = cfc;

            if (c == '\r' || c == 0x07 || c == 0x0C || c == 0x0E) {
                if (runText.length() > 0) {
                    paraRuns.add(new RenderedRun(runText.toString(), runStyle.toTextStyle(), runAnchorStart, anchor));
                    runText.setLength(0);
                }
                paragraphs.add(makeParagraph(pap, paraFirstFc, paraRuns));
                paraRuns = new ArrayList<>();
                paraFirstFc = -1;
                anchor++;
                continue;
            }

            char toAppend;
            if (c == 0x0B) toAppend = '\n';
            else if (c == 0x1E) toAppend = '-';
            else if (c == 0x1F) continue;
            else if (c == '\u00A0') toAppend = ' ';
            else if (c == '\t' || c >= 0x20) toAppend = c;
            else continue;

            ChpStyle style = (idx < fc.length) ? chp.styleForFc(fc[idx]) : ChpStyle.DEFAULT;
            if (runText.length() > 0 && !style.equals(runStyle)) {
                paraRuns.add(new RenderedRun(runText.toString(), runStyle.toTextStyle(), runAnchorStart, anchor));
                runText.setLength(0);
            }
            if (runText.length() == 0) {
                runStyle = style;
                runAnchorStart = anchor;
            }
            runText.append(toAppend);
            anchor++;
        }
        if (runText.length() > 0) {
            paraRuns.add(new RenderedRun(runText.toString(), runStyle.toTextStyle(), runAnchorStart, anchor));
        }
        if (!paraRuns.isEmpty()) {
            paragraphs.add(makeParagraph(pap, paraFirstFc, paraRuns));
        }

        while (!paragraphs.isEmpty() && paragraphs.get(paragraphs.size() - 1).plainText().isEmpty()) {
            paragraphs.remove(paragraphs.size() - 1);
        }
        return paragraphs;
    }

    private static RenderedParagraph makeParagraph(DocParagraphProperties pap, int paraFc, List<RenderedRun> runs) {
        ParagraphStyle style = pap.styleForFc(paraFc >= 0 ? paraFc : 0).toParagraphStyle();
        List<RenderedRun> runList = runs.isEmpty() ? listOf(RenderedRun.text("")) : runs;
        return new RenderedParagraph(style, runList);
    }

    // ---- map paragraphs onto the shared render model ----

    private static RenderedDocument buildDocument(List<RenderedParagraph> paragraphs, @Nullable String title, int paragraphsPerPage) {
        int perPage = paragraphsPerPage > 0 ? paragraphsPerPage : DEFAULT_PARAGRAPHS_PER_PAGE;

        RenderedDocument.Builder document =
                RenderedDocument.builder("doc").title(title != null ? title : "Word");

        int pageIndex = 0;
        int inPage = 0;
        RenderedPage.Builder page = newPage(pageIndex);

        for (RenderedParagraph paragraph : paragraphs) {
            if (inPage >= perPage) {
                document.addPage(page.build());
                pageIndex++;
                page = newPage(pageIndex);
                inPage = 0;
            }
            page.addBlock(RenderedBlock.paragraph(paragraph));
            inPage++;
        }
        document.addPage(page.build());
        return document.build();
    }

    private static RenderedPage.Builder newPage(int pageIndex) {
        return RenderedPage.builder(pageIndex)
                .pageSizePt(PAGE_WIDTH_PT, PAGE_HEIGHT_PT)
                .marginsPt(MARGIN_PT, MARGIN_PT, MARGIN_PT, MARGIN_PT);
    }

    private static List<RenderedRun> listOf(RenderedRun run) {
        List<RenderedRun> list = new ArrayList<>(1);
        list.add(run);
        return list;
    }

    // ---- helpers ----

    private static final class Reconstructed {
        final String text;
        final int[] fc; // fc[i] is the file character position of text.charAt(i)

        Reconstructed(String text, int[] fc) {
            this.text = text;
            this.fc = fc;
        }
    }

    private static byte[] readAllBytes(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, (int) Math.min(file.length(), Integer.MAX_VALUE)));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    private static Charset charset(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    private static void requireRange(byte[] b, int off, int len) throws IOException {
        if (off < 0 || len < 0 || (long) off + len > b.length) throw new IOException("Word FIB read out of range");
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

    private static long u32(byte[] b, int off) {
        return i32(b, off) & 0xFFFFFFFFL;
    }
}
