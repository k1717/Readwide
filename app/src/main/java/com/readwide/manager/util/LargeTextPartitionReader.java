package com.readwide.manager.util;

import android.content.Context;

import androidx.annotation.NonNull;

import com.readwide.manager.model.LargeTextLinePartitionResult;
import com.readwide.manager.model.LargeTextLineStats;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;

/**
 * File I/O helper for large TXT partition loading.
 *
 * This class intentionally owns only deterministic file/line-window assembly.
 * ReaderActivity still decides when to switch partitions, how exact page anchors
 * are applied, and how scroll handoff is performed. Keeping the partition body
 * range construction here makes the continuity contract explicit: lookbehind is
 * captured only when requested for manual scroll handoff, while the canonical
 * body start/end are reported separately for exact tap/page navigation.
 */
public final class LargeTextPartitionReader {
    private LargeTextPartitionReader() {}

    // Minimum trimmed length of an anchor line fragment that is specific enough to
    // locate a unique partition. Shorter fragments fall back to char-offset.
    private static final int MIN_ANCHOR_LINE_NEEDLE = 16;

    public static float estimateBytesPerChar(@NonNull File file,
                                             int previewBytes) {
        try {
            byte[] sample = readPreviewBytesAt(file, 0L, Math.min(128 * 1024, previewBytes));
            String decoded = decodePreviewBytes(file, sample);
            if (decoded == null || decoded.isEmpty()) return 1f;
            return Math.max(1f, sample.length / (float) Math.max(1, decoded.length()));
        } catch (Exception ignored) {
            return 1f;
        }
    }

    public static BufferedReader openReader(@NonNull File file,
                                            @NonNull String encoding) throws IOException {
        return new BufferedReader(new InputStreamReader(
                new FileInputStream(file), Charset.forName(encoding)), 64 * 1024);
    }

    public static LargeTextLineStats scanLineStats(@NonNull Context context,
                                                   @NonNull File file,
                                                   @NonNull String encoding,
                                                   boolean collapseBlankLines,
                                                   int targetCharPosition) throws IOException {
        int targetChar = Math.max(0, targetCharPosition);
        long charCount = 0L;
        int line = 1;
        int targetLine = -1;
        boolean sawAnyLine = false;

        List<TextDisplayRule> activeRules = TextDisplayRuleManager.getActiveRules(
                context.getApplicationContext(), file.getAbsolutePath());
        TxtBlankLineCollapser.Filter collapseFilter = new TxtBlankLineCollapser.Filter(collapseBlankLines);
        try (BufferedReader reader = openReader(file, encoding)) {
            String lineText;
            while ((lineText = reader.readLine()) != null) {
                sawAnyLine = true;
                String normalized = FileUtils.enforceTextPresentationSelectors(lineText);
                normalized = TextDisplayRuleManager.apply(normalized, activeRules);
                String emitted = collapseFilter.accept(normalized);
                if (emitted == null) continue;
                normalized = emitted;
                int lineChars = normalized.length() + 1; // TextView normalizes line breaks to '\n'.
                if (targetLine < 0 && targetChar >= charCount && targetChar < charCount + lineChars) {
                    targetLine = line;
                }
                charCount += lineChars;
                line++;
            }
        }

        int totalLines = sawAnyLine ? Math.max(1, line - 1) : 1;
        if (targetLine < 0) targetLine = totalLines;
        return new LargeTextLineStats(targetLine, totalLines,
                (int) Math.max(0L, Math.min(Integer.MAX_VALUE, charCount)));
    }

    /**
     * Resolve the absolute character position (in the current display coordinate
     * space) of a saved anchor. Used to pick the correct large-text partition and
     * the exact restore position after a blank-line-collapse or display-rule
     * change, when the saved character offset belongs to the previous coordinate
     * space and can no longer be trusted.
     *
     * The cursor line text is collapse-invariant because blank-line collapsing
     * never rewrites a non-blank line, so it matches in either coordinate space.
     * Returns a 0-based absolute character offset (cursor mid-line), or -1 when no
     * sufficiently specific anchor line can be located (the caller then falls back
     * to the saved char offset).
     */
    public static int findCharPositionForAnchor(@NonNull Context context,
                                                @NonNull File file,
                                                @NonNull String encoding,
                                                boolean collapseBlankLines,
                                                String anchorBefore,
                                                String anchorAfter) throws IOException {
        String before = anchorBefore != null ? anchorBefore : "";
        String after = anchorAfter != null ? anchorAfter : "";

        int lastBreak = before.lastIndexOf('\n');
        String beforeTail = lastBreak >= 0 ? before.substring(lastBreak + 1) : before;
        int firstBreak = after.indexOf('\n');
        String afterHead = firstBreak >= 0 ? after.substring(0, firstBreak) : after;

        String needle;
        int cursorInNeedle;
        String currentLine = beforeTail + afterHead;
        if (currentLine.trim().length() >= MIN_ANCHOR_LINE_NEEDLE) {
            needle = currentLine;
            cursorInNeedle = beforeTail.length();
        } else {
            needle = firstLongLine(after);
            cursorInNeedle = 0;
            if (needle == null) return -1;
        }

        List<TextDisplayRule> activeRules = TextDisplayRuleManager.getActiveRules(
                context.getApplicationContext(), file.getAbsolutePath());
        TxtBlankLineCollapser.Filter collapseFilter = new TxtBlankLineCollapser.Filter(collapseBlankLines);
        long charCount = 0L;
        try (BufferedReader reader = openReader(file, encoding)) {
            String lineText;
            while ((lineText = reader.readLine()) != null) {
                String normalized = FileUtils.enforceTextPresentationSelectors(lineText);
                normalized = TextDisplayRuleManager.apply(normalized, activeRules);
                String emitted = collapseFilter.accept(normalized);
                if (emitted == null) continue;
                int hit = emitted.indexOf(needle);
                if (hit >= 0) {
                    long pos = charCount + hit + cursorInNeedle;
                    return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, pos));
                }
                charCount += emitted.length() + 1;
            }
        }
        return -1;
    }

    /**
     * First upcoming display line whose trimmed length is specific enough to match
     * safely. Used when the cursor sits on a blank or very short line. Returns null
     * when none is found.
     */
    private static String firstLongLine(String text) {
        for (String candidate : text.split("\n", -1)) {
            if (candidate.trim().length() >= MIN_ANCHOR_LINE_NEEDLE) {
                return candidate;
            }
        }
        return null;
    }

    public static LargeTextLinePartitionResult readPartitionForChar(@NonNull Context context,
                                                                     @NonNull File file,
                                                                     @NonNull String encoding,
                                                                     boolean collapseBlankLines,
                                                                     int targetCharPosition,
                                                                     int partitionLines,
                                                                     int lookaheadLines,
                                                                     int lookbehindLines) throws IOException {
        LargeTextLineStats stats = scanLineStats(context, file, encoding, collapseBlankLines, targetCharPosition);
        int startLine = LargeTextContinuityMath.partitionStartLineForLine(stats.targetLine, partitionLines);
        return readPartitionAtStartLine(context, file, encoding, collapseBlankLines, startLine, stats.totalLines, stats.totalChars,
                partitionLines, lookaheadLines, lookbehindLines, false);
    }

    public static LargeTextLinePartitionResult readPartitionAtStartLine(@NonNull Context context,
                                                                         @NonNull File file,
                                                                         @NonNull String encoding,
                                                                         boolean collapseBlankLines,
                                                                         int requestedStartLine,
                                                                         int partitionLines,
                                                                         int lookaheadLines,
                                                                         int lookbehindLines) throws IOException {
        LargeTextLineStats stats = scanLineStats(context, file, encoding, collapseBlankLines, 0);
        int startLine = LargeTextContinuityMath.partitionStartLineForLine(requestedStartLine, partitionLines);
        if (startLine > stats.totalLines) {
            startLine = LargeTextContinuityMath.partitionStartLineForLine(stats.totalLines, partitionLines);
        }
        return readPartitionAtStartLine(context, file, encoding, collapseBlankLines, startLine, stats.totalLines, stats.totalChars,
                partitionLines, lookaheadLines, lookbehindLines, false);
    }

    public static LargeTextLinePartitionResult readPartitionAtStartLine(@NonNull Context context,
                                                                         @NonNull File file,
                                                                         @NonNull String encoding,
                                                                         boolean collapseBlankLines,
                                                                         int requestedStartLine,
                                                                         int knownTotalLines,
                                                                         int knownTotalChars,
                                                                         int partitionLines,
                                                                         int lookaheadLines,
                                                                         int lookbehindLines) throws IOException {
        return readPartitionAtStartLine(context, file, encoding, collapseBlankLines, requestedStartLine, knownTotalLines, knownTotalChars,
                partitionLines, lookaheadLines, lookbehindLines, false);
    }

    public static LargeTextLinePartitionResult readPartitionAtStartLine(@NonNull Context context,
                                                                         @NonNull File file,
                                                                         @NonNull String encoding,
                                                                         boolean collapseBlankLines,
                                                                         int requestedStartLine,
                                                                         int knownTotalLines,
                                                                         int knownTotalChars,
                                                                         int partitionLines,
                                                                         int lookaheadLines,
                                                                         int lookbehindLines,
                                                                         boolean includeLookbehind) throws IOException {
        return readPartitionAtStartLine(context, file, encoding, collapseBlankLines, requestedStartLine,
                knownTotalLines, knownTotalChars, partitionLines, lookaheadLines, lookbehindLines,
                includeLookbehind, null);
    }

    /**
     * Session-scoped forward read cursor. A partition's first load used to
     * stream the file from line 1 every time, so sequential reading of a huge
     * file cost O(N^2) total decode work. The cursor keeps the session's
     * reader open together with the tiny streaming state (next canonical line,
     * emitted-char total, the blank-collapse flag, and the one already-decoded
     * carry line consumed past the previous window), so FORWARD requests
     * continue from where the last read stopped; backward jumps, file or
     * encoding or collapse changes, and display-rule edits (versioned) reset
     * it to a fresh scan, which also re-primes it. Owner (the read controller)
     * must serialize access and close it on teardown.
     */
    public static final class ForwardCursor {
        String filePath;
        long fileLength;
        long fileModified;
        String encoding;
        boolean collapseBlankLines;
        int rulesVersion;
        BufferedReader reader;
        /** Number of the first line represented by {@link #queue}. */
        int queueStartLine;
        /** Emitted chars (len+1 each) of every line below {@link #queueStartLine}. */
        long charsBeforeQueue;
        boolean prevEmittedBlank;
        /**
         * Already-decoded emitted lines numbered consecutively from
         * {@link #queueStartLine}: the last lookbehind-many lines BELOW the
         * body-end checkpoint (manual-scroll handoffs request windows that
         * start a lookbehind BEFORE the next body), then the previous read's
         * lookahead region, then its carry line. Consecutive partitions
         * overlap by these regions and a BufferedReader cannot rewind - but
         * emitted text can simply be replayed, which is what makes forward
         * continuation possible at all.
         */
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();

        public void closeQuietly() {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
                reader = null;
            }
            filePath = null;
            queue.clear();
        }

        boolean canResume(File file, String enc, boolean collapse, int rulesVer, int windowStartLine) {
            return (reader != null || !queue.isEmpty())
                    && filePath != null
                    && filePath.equals(file.getAbsolutePath())
                    && fileLength == file.length()
                    && fileModified == file.lastModified()
                    && encoding != null && encoding.equals(enc)
                    && collapseBlankLines == collapse
                    && rulesVersion == rulesVer
                    && queueStartLine <= windowStartLine;
        }
    }

    /**
     * Per-line presentation transform used by the partition core. Keeping this
     * tiny seam package-private lets the JVM regression suite execute the real
     * full-scan/cursor implementation without constructing an Android Context.
     * Production still supplies the exact selector + display-rule transform.
     */
    @FunctionalInterface
    interface LineTransform {
        String apply(String line);
    }

    public static LargeTextLinePartitionResult readPartitionAtStartLine(@NonNull Context context,
                                                                         @NonNull File file,
                                                                         @NonNull String encoding,
                                                                         boolean collapseBlankLines,
                                                                         int requestedStartLine,
                                                                         int knownTotalLines,
                                                                         int knownTotalChars,
                                                                         int partitionLines,
                                                                         int lookaheadLines,
                                                                         int lookbehindLines,
                                                                         boolean includeLookbehind,
                                                                         ForwardCursor cursor) throws IOException {
        List<TextDisplayRule> activeRules = TextDisplayRuleManager.getActiveRules(
                context.getApplicationContext(), file.getAbsolutePath());
        int rulesVersion = TextDisplayRuleManager.getRulesVersion();
        return readPartitionAtStartLineTransformed(
                file,
                encoding,
                collapseBlankLines,
                requestedStartLine,
                knownTotalLines,
                knownTotalChars,
                partitionLines,
                lookaheadLines,
                lookbehindLines,
                includeLookbehind,
                rulesVersion,
                lineText -> TextDisplayRuleManager.apply(
                        FileUtils.enforceTextPresentationSelectors(lineText), activeRules),
                cursor);
    }

    static LargeTextLinePartitionResult readPartitionAtStartLineTransformed(
            @NonNull File file,
            @NonNull String encoding,
            boolean collapseBlankLines,
            int requestedStartLine,
            int knownTotalLines,
            int knownTotalChars,
            int partitionLines,
            int lookaheadLines,
            int lookbehindLines,
            boolean includeLookbehind,
            int rulesVersion,
            @NonNull LineTransform lineTransform,
            ForwardCursor cursor) throws IOException {
        LargeTextContinuityMath.PartitionWindow window =
                LargeTextContinuityMath.partitionWindowForStartLine(
                        requestedStartLine,
                        knownTotalLines,
                        partitionLines,
                        lookaheadLines,
                        lookbehindLines,
                        includeLookbehind);
        int startLine = window.startLine;
        int bodyEndLine = window.bodyEndLine;
        int windowStartLine = window.windowStartLine;
        int captureEndLine = window.captureEndLine;

        StringBuilder out = new StringBuilder();
        long baseChars = 0L;
        int line = 1;
        int bodyStartCharCount = 0;
        int bodyCharCount = 0;
        boolean capturedAny = false;

        TxtBlankLineCollapser.Filter collapseFilter;
        BufferedReader reader;
        java.util.ArrayDeque<String> pending;
        boolean resumed = cursor != null
                && cursor.canResume(file, encoding, collapseBlankLines, rulesVersion, windowStartLine);
        if (resumed) {
            reader = cursor.reader;
            cursor.reader = null; // this read owns it now; reattached on suspend
            pending = cursor.queue;
            cursor.queue = new java.util.ArrayDeque<>();
            line = cursor.queueStartLine;
            baseChars = cursor.charsBeforeQueue;
            collapseFilter = new TxtBlankLineCollapser.Filter(collapseBlankLines, cursor.prevEmittedBlank);
        } else {
            if (cursor != null) cursor.closeQuietly();
            reader = openReader(file, encoding);
            pending = new java.util.ArrayDeque<>();
            collapseFilter = new TxtBlankLineCollapser.Filter(collapseBlankLines);
        }

        // Emitted lines processed past bodyEndLine this read (the lookahead
        // region), collected so the cursor can replay them next time.
        java.util.ArrayList<String> lookaheadEmitted = cursor != null ? new java.util.ArrayList<>() : null;
        // The last lookbehind-many emitted lines at or below bodyEndLine, kept
        // so a following manual-handoff window (which starts a lookbehind
        // BEFORE the next body) can still resume instead of rescanning.
        int tailKeep = cursor != null ? Math.max(0, lookbehindLines) : 0;
        java.util.ArrayDeque<String> tailBuf = cursor != null ? new java.util.ArrayDeque<>() : null;
        long tailChars = 0L;
        String carryText = null;
        boolean hitEof = false;
        try {
            boolean firstCapturedLine = true;
            while (true) {
                String emitted;
                boolean fromPending = !pending.isEmpty();
                if (fromPending) {
                    // Already normalized, rule-applied, and collapse-accepted in
                    // the previous read; never re-filter.
                    emitted = pending.pollFirst();
                } else {
                    if (reader == null) { // resumed queue exhausted at a prior EOF
                        hitEof = true;
                        break;
                    }
                    String lineText = reader.readLine();
                    if (lineText == null) {
                        hitEof = true;
                        break;
                    }
                    String normalized = lineTransform.apply(lineText);
                    emitted = collapseFilter.accept(normalized);
                    if (emitted == null) continue;
                }
                int lineChars = emitted.length() + 1; // TextView normalizes line breaks to '\n'.

                if (tailBuf != null && line <= bodyEndLine && tailKeep > 0) {
                    tailBuf.addLast(emitted);
                    tailChars += lineChars;
                    if (tailBuf.size() > tailKeep) {
                        tailChars -= tailBuf.pollFirst().length() + 1;
                    }
                }
                if (line < windowStartLine) {
                    baseChars += lineChars;
                } else if (line <= captureEndLine) {
                    capturedAny = true;
                    // Rebuild the partition with line breaks between captured lines,
                    // but do not append a synthetic trailing newline at the very end
                    // of the active buffer. This preserves the exact terminal-page
                    // behavior of the live renderer.
                    if (!firstCapturedLine) {
                        out.append('\n');
                    }
                    firstCapturedLine = false;
                    if (line == startLine) {
                        bodyStartCharCount = out.length();
                    }
                    out.append(emitted);
                    if (line <= bodyEndLine) {
                        bodyCharCount = out.length();
                    } else if (lookaheadEmitted != null) {
                        lookaheadEmitted.add(emitted);
                    }
                } else {
                    // First emitted line beyond the capture window.
                    carryText = emitted;
                    break;
                }
                line++;
            }
        } catch (IOException e) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
            if (cursor != null) cursor.closeQuietly();
            throw e;
        }

        if (cursor != null) {
            // Checkpoint at the END OF THE BODY, not the capture end:
            // consecutive partitions overlap by the lookahead, so the replay
            // queue below is what lets the next forward read resume. Every
            // emitted line below the checkpoint is accounted for: pre-window
            // lines in baseChars, window lines up to the body end in
            // bodyCharCount (k joined lines always total length+1 chars).
            int checkpointLine = Math.min(bodyEndLine + 1, line);
            java.util.ArrayDeque<String> nextQueue = new java.util.ArrayDeque<>();
            int tailSize = tailBuf != null ? tailBuf.size() : 0;
            if (tailBuf != null) nextQueue.addAll(tailBuf);
            if (lookaheadEmitted != null) nextQueue.addAll(lookaheadEmitted);
            if (carryText != null) nextQueue.addLast(carryText);
            nextQueue.addAll(pending); // unprocessed remainder keeps its order
            boolean sourceLeft = !hitEof || !nextQueue.isEmpty();
            if (sourceLeft) {
                cursor.filePath = file.getAbsolutePath();
                cursor.fileLength = file.length();
                cursor.fileModified = file.lastModified();
                cursor.encoding = encoding;
                cursor.collapseBlankLines = collapseBlankLines;
                cursor.rulesVersion = rulesVersion;
                if (hitEof && reader != null) {
                    try {
                        reader.close();
                    } catch (IOException ignored) {
                    }
                    reader = null;
                }
                cursor.reader = reader;
                cursor.queueStartLine = checkpointLine - tailSize;
                cursor.charsBeforeQueue =
                        baseChars + (capturedAny ? bodyCharCount + 1L : 0L) - tailChars;
                cursor.prevEmittedBlank = collapseFilter.isPrevEmittedBlank();
                cursor.queue = nextQueue;
            } else {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException ignored) {
                    }
                }
                cursor.closeQuietly();
            }
        } else if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
        }

        String content = out.toString();
        if (!capturedAny) {
            content = "";
            bodyStartCharCount = 0;
            bodyCharCount = 0;
        }
        return new LargeTextLinePartitionResult(
                content,
                TextSearchMath.countLines(content),
                startLine,
                bodyEndLine,
                knownTotalLines,
                (int) Math.max(0L, Math.min(Integer.MAX_VALUE, baseChars)),
                bodyStartCharCount,
                bodyCharCount,
                windowStartLine,
                includeLookbehind,
                knownTotalChars);
    }

    public static String joinWindow(@NonNull ArrayList<String> lines) {
        if (lines.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append('\n');
            out.append(lines.get(i));
        }
        return out.toString();
    }

    public static byte[] readPreviewBytesAt(@NonNull File file,
                                            long startByte,
                                            int maxBytes) throws IOException {
        long clampedStart = Math.max(0L, Math.min(startByte, Math.max(0L, file.length())));
        int limit = (int) Math.max(1, Math.min(file.length() - clampedStart, maxBytes));
        ByteArrayOutputStream out = new ByteArrayOutputStream(limit);

        byte[] buffer = new byte[Math.min(64 * 1024, limit)];
        int remaining = limit;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(clampedStart);

            while (remaining > 0) {
                int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }

        return out.toByteArray();
    }

    public static String decodePreviewBytes(@NonNull File file,
                                            @NonNull byte[] data) throws IOException {
        String encoding = FileUtils.detectEncoding(file);
        try {
            CharsetDecoder decoder = Charset.forName(encoding)
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            String decoded = decoder.decode(ByteBuffer.wrap(data)).toString();
            if (!decoded.isEmpty() && decoded.charAt(0) == '\uFEFF') {
                decoded = decoded.substring(1);
            }
            return FileUtils.enforceTextPresentationSelectors(decoded);
        } catch (Exception e) {
            throw new IOException("Cannot decode text preview", e);
        }
    }
}
