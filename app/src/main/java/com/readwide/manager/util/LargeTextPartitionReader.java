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
import java.io.InterruptedIOException;
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
 * This class owns file/line-window assembly and serialized cursor resource ownership.
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

    private static final ThreadLocal<java.util.function.BooleanSupplier> READ_CHECKPOINT = new ThreadLocal<>();

    @FunctionalInterface
    public interface ReadOperation<T> { T run() throws IOException; }

    /** Scope only this worker read; never interrupt a shared executor's next task. */
    public static <T> T readWhile(java.util.function.BooleanSupplier keepReading,
                                  ReadOperation<T> operation) throws IOException {
        java.util.function.BooleanSupplier previous = READ_CHECKPOINT.get();
        READ_CHECKPOINT.set(previous == null ? keepReading
                : () -> previous.getAsBoolean() && keepReading.getAsBoolean());
        try {
            checkInterrupted();
            T result = operation.run();
            checkInterrupted();
            return result;
        } finally {
            if (previous == null) READ_CHECKPOINT.remove();
            else READ_CHECKPOINT.set(previous);
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("TXT read cancelled");
        java.util.function.BooleanSupplier checkpoint = READ_CHECKPOINT.get();
        if (checkpoint != null && !checkpoint.getAsBoolean()) {
            throw new InterruptedIOException("TXT read superseded");
        }
    }

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
        checkInterrupted();
        long sourceLength = file.length(), sourceModified = file.lastModified();
        int targetChar = Math.max(0, targetCharPosition);
        long charCount = 0L;
        long line = 1L;
        int targetLine = -1;
        boolean sawAnyLine = false;

        TextDisplayRuleManager.CompiledRules activeRules = TextDisplayRuleManager.captureActive(
                context.getApplicationContext(), file.getAbsolutePath()).compiled;
        TxtBlankLineCollapser.Filter collapseFilter = new TxtBlankLineCollapser.Filter(collapseBlankLines);
        try (BufferedReader reader = openReader(file, encoding)) {
            String lineText;
            while ((lineText = reader.readLine()) != null) {
                checkInterrupted();
                sawAnyLine = true;
                String normalized = FileUtils.enforceTextPresentationSelectors(lineText);
                normalized = TextDisplayRuleManager.apply(normalized, activeRules);
                String emitted = collapseFilter.accept(normalized);
                if (emitted == null) continue;
                normalized = emitted;
                int currentLine = LargeTextCoordinateMath.checkedLineNumber(line);
                long nextChar = LargeTextCoordinateMath.advancePastLine(charCount, normalized.length());
                if (targetLine < 0 && targetChar >= charCount && targetChar < nextChar) {
                    targetLine = currentLine;
                }
                charCount = nextChar;
                line++;
            }
        }

        checkInterrupted();
        if (!file.isFile() || file.length() != sourceLength || file.lastModified() != sourceModified) {
            throw new IOException("TXT changed while reading partition statistics");
        }
        int totalLines = sawAnyLine ? LargeTextCoordinateMath.checkedLineNumber(Math.max(1L, line - 1L)) : 1;
        if (targetLine < 0) targetLine = totalLines;
        // Only a validated final synthetic separator may exceed the real coordinate range.
        return new LargeTextLineStats(targetLine, totalLines,
                (int) Math.min(Integer.MAX_VALUE, charCount));
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
        return findCharPositionForAnchor(context, file, encoding, collapseBlankLines, 0, anchorBefore, anchorAfter);
    }

    public static int findCharPositionForAnchor(@NonNull Context context,
                                                @NonNull File file,
                                                @NonNull String encoding,
                                                boolean collapseBlankLines,
                                                int fallbackPosition,
                                                String anchorBefore,
                                                String anchorAfter) throws IOException {
        TextDisplayRuleManager.CompiledRules activeRules = TextDisplayRuleManager.captureActive(
                context.getApplicationContext(), file.getAbsolutePath()).compiled;
        return findCharPositionForAnchorTransformed(file, encoding, collapseBlankLines,
                fallbackPosition, anchorBefore, anchorAfter, line -> TextDisplayRuleManager.apply(
                        FileUtils.enforceTextPresentationSelectors(line), activeRules));
    }

    static int findCharPositionForAnchorTransformed(File file, String encoding, boolean collapseBlankLines,
                                                   int fallbackPosition, String anchorBefore, String anchorAfter,
                                                   LineTransform transform) throws IOException {
        AnchorSearch search = new AnchorSearch(anchorBefore, anchorAfter, fallbackPosition);
        if (search.needle == null) return -1;
        TxtBlankLineCollapser.Filter filter = new TxtBlankLineCollapser.Filter(collapseBlankLines);
        checkInterrupted();
        try (BufferedReader reader = openReader(file, encoding)) {
            String line;
            while ((line = reader.readLine()) != null) {
                checkInterrupted();
                String emitted = filter.accept(transform.apply(line));
                if (emitted != null) search.appendLine(emitted);
            }
        }
        search.scan(true);
        return search.bestPosition;
    }

    /** A rolling context window, not a whole-file buffer or a list of matching positions. */
    private static final class AnchorSearch {
        final String before, after, needle;
        final int cursorInNeedle, fallback;
        final String normalizedBefore, normalizedAfter;
        final StringBuilder window = new StringBuilder();
        long base;
        int scanFrom, bestPosition = -1, bestContext = -1;
        long bestDistance = Long.MAX_VALUE;

        AnchorSearch(String anchorBefore, String anchorAfter, int fallbackPosition) {
            String before = anchorBefore != null ? anchorBefore : "";
            String after = anchorAfter != null ? anchorAfter : "";
            int lastBreak = before.lastIndexOf('\n');
            String beforeTail = lastBreak >= 0 ? before.substring(lastBreak + 1) : before;
            int firstBreak = after.indexOf('\n');
            String afterHead = firstBreak >= 0 ? after.substring(0, firstBreak) : after;

            String currentLine = beforeTail + afterHead;
            if (currentLine.trim().length() >= MIN_ANCHOR_LINE_NEEDLE) {
                needle = currentLine;
                cursorInNeedle = beforeTail.length();
                this.before = before;
                this.after = after;
            } else {
                needle = firstLongLine(after);
                cursorInNeedle = 0;
                // Preserve the old short/blank-line fallback to the next specific line.
                this.before = "";
                this.after = needle != null ? needle : "";
            }
            fallback = Math.max(0, fallbackPosition);
            normalizedBefore = TxtBlankLineCollapser.collapse(this.before);
            normalizedAfter = TxtBlankLineCollapser.collapse(this.after);
        }

        void appendLine(String line) throws IOException {
            LargeTextCoordinateMath.advancePastLine(base + window.length(), line.length());
            // Do not retain a second full copy of unusually long source lines.
            for (int start = 0; start < line.length();) {
                int end = (int) Math.min(line.length(), (long) start + 4096);
                window.append(line, start, end);
                scan(false);
                start = end;
            }
            window.append('\n');
            scan(false);
        }

        void scan(boolean eof) throws IOException {
            while (scanFrom < window.length()) {
                checkInterrupted();
                int hit = window.indexOf(needle, scanFrom);
                if (hit < 0) {
                    scanFrom = Math.max(scanFrom, window.length() - needle.length() + 1);
                    break;
                }
                int cursor = hit + cursorInNeedle;
                if (!eof && (long) cursor + after.length() > window.length()) {
                    scanFrom = hit; // wait for the following lines before comparing context
                    break;
                }
                String actualBefore = window.substring(Math.max(0, cursor - before.length()), cursor);
                String actualAfter = window.substring(cursor, (int) Math.min(window.length(), (long) cursor + after.length()));
                int context = 0;
                if (!normalizedBefore.isEmpty() && TxtBlankLineCollapser.collapse(actualBefore).endsWith(normalizedBefore)) context++;
                if (!normalizedAfter.isEmpty() && TxtBlankLineCollapser.collapse(actualAfter).startsWith(normalizedAfter)) context++;
                long position = base + cursor;
                long distance = Math.abs(position - fallback);
                if (context > bestContext || (context == bestContext && distance < bestDistance)) {
                    bestContext = context;
                    bestDistance = distance;
                    bestPosition = LargeTextCoordinateMath.checkedPosition(position);
                }
                scanFrom = hit + 1; // consider overlapping occurrences, too
            }
            int discard = Math.max(0, scanFrom - before.length());
            if (discard > 0) {
                window.delete(0, discard);
                base += discard;
                scanFrom -= discard;
            }
        }
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
        TextDisplayRuleManager.CompiledRules rules = TextDisplayRuleManager.captureActive(
                context.getApplicationContext(), file.getAbsolutePath()).compiled;
        return readPartitionForCharTransformed(file, encoding, collapseBlankLines,
                targetCharPosition, partitionLines, lookaheadLines, line -> TextDisplayRuleManager.apply(
                        FileUtils.enforceTextPresentationSelectors(line), rules));
    }

    /**
     * Stats and the requested canonical partition share one decoded pass. Before
     * finding the target, retain only its candidate body; afterwards retain that
     * body plus lookahead while continuing to count the rest of the file. This
     * does not combine layout with startup or retain the entire document. The
     * known-stats/forward-cursor and manual-lookbehind paths stay unchanged.
     */
    static LargeTextLinePartitionResult readPartitionForCharTransformed(
            File file, String encoding, boolean collapseBlankLines, int targetCharPosition,
            int partitionLines, int lookaheadLines, LineTransform transform) throws IOException {
        checkInterrupted();
        long length = file.length(), modified = file.lastModified();
        int targetChar = Math.max(0, targetCharPosition);
        long line = 1L;
        int startLine = 1, bodyCharCount = 0;
        long charCount = 0L, baseChars = 0L;
        boolean targetFound = false, capturedAny = false;
        StringBuilder out = new StringBuilder();
        LargeTextContinuityMath.PartitionWindow window =
                LargeTextContinuityMath.partitionWindowForStartLine(
                        startLine, Integer.MAX_VALUE, partitionLines, lookaheadLines, 0, false);
        TxtBlankLineCollapser.Filter filter = new TxtBlankLineCollapser.Filter(collapseBlankLines);
        try (BufferedReader reader = openReader(file, encoding)) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                checkInterrupted();
                String emitted = filter.accept(transform.apply(raw));
                if (emitted == null) continue;
                int currentLine = LargeTextCoordinateMath.checkedLineNumber(line);
                long nextChar = LargeTextCoordinateMath.advancePastLine(charCount, emitted.length());
                if (!targetFound) {
                    int candidateStart = LargeTextContinuityMath.partitionStartLineForLine(currentLine, partitionLines);
                    if (candidateStart != startLine) {
                        startLine = candidateStart;
                        baseChars = charCount;
                        out.setLength(0);
                        capturedAny = false;
                        bodyCharCount = 0;
                        window = LargeTextContinuityMath.partitionWindowForStartLine(
                                startLine, Integer.MAX_VALUE, partitionLines, lookaheadLines, 0, false);
                    }
                }
                if (!targetFound && targetChar >= charCount && targetChar < nextChar) {
                    targetFound = true;
                }
                if (line <= window.captureEndLine) {
                    // Preserve the reader's no-synthetic-terminal-newline contract.
                    if (capturedAny) out.append('\n');
                    out.append(emitted);
                    capturedAny = true;
                    if (line <= window.bodyEndLine) bodyCharCount = out.length();
                }
                charCount = nextChar;
                line++;
            }
        }
        checkInterrupted();
        if (!file.isFile() || file.length() != length || file.lastModified() != modified) {
            throw new IOException("TXT changed while reading partition statistics");
        }
        int totalLines = LargeTextCoordinateMath.checkedLineNumber(Math.max(1L, line - 1L));
        // If the saved character lies beyond EOF, the last candidate is already
        // the same last canonical partition selected by the former stats+clamp.
        window = LargeTextContinuityMath.partitionWindowForStartLine(
                startLine, totalLines, partitionLines, lookaheadLines, 0, false);
        String content = out.toString();
        LargeTextCoordinateMath.checkedPosition(baseChars + content.length());
        return new LargeTextLinePartitionResult(content, TextSearchMath.countLines(content),
                window.startLine, window.bodyEndLine, totalLines,
                LargeTextCoordinateMath.checkedPosition(baseChars), 0, bodyCharCount,
                window.windowStartLine, false,
                (int) Math.min(Integer.MAX_VALUE, charCount));
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

    /** Serializes worker reads; retirement never waits for the I/O monitor on the caller. */
    public static final class ForwardCursorSession {
        private final Object lock = new Object();
        private final java.util.concurrent.atomic.AtomicBoolean closing =
                new java.util.concurrent.atomic.AtomicBoolean();
        private ForwardCursor cursor;

        @FunctionalInterface
        public interface Read<T> { T run(ForwardCursor cursor) throws IOException; }

        public <T> T read(Read<T> operation) throws IOException {
            checkInterrupted();
            synchronized (lock) {
                checkInterrupted();
                if (closing.get()) throw new InterruptedIOException("TXT cursor session retired");
                if (cursor == null) cursor = new ForwardCursor();
                try { return operation.run(cursor); }
                finally { if (closing.get()) closeLocked(); }
            }
        }

        public void closeAsync() {
            if (!closing.compareAndSet(false, true)) return;
            // Independent of the activity executors: shutdownNow must not discard cleanup.
            new Thread(() -> {
                synchronized (lock) { closeLocked(); }
            }, "ReadwideTxtCursorClose").start();
        }

        private void closeLocked() {
            if (cursor != null) {
                cursor.closeQuietly();
                cursor = null;
            }
        }
    }

    /**
     * Forward reads reuse the decoded line queue and reader position. Backward
     * jumps or source/encoding/transform changes reset to a fresh scan. Access
     * and teardown are serialized by the owning ForwardCursorSession.
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
        TextDisplayRuleManager.ActiveSnapshot rules = TextDisplayRuleManager.captureActive(
                context.getApplicationContext(), file.getAbsolutePath());
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
                rules.version,
                lineText -> TextDisplayRuleManager.apply(
                        FileUtils.enforceTextPresentationSelectors(lineText), rules.compiled),
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
        // Bind both a fresh stream and a resumed queue to the source observed
        // before the read. Never stamp old buffered text with newer metadata.
        final long sourceLength = file.length();
        final long sourceModified = file.lastModified();
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
        long line = 1L;
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
        long nextLineChar = baseChars;
        try {
            boolean firstCapturedLine = true;
            while (true) {
                checkInterrupted();
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
                LargeTextCoordinateMath.checkedLineNumber(line);
                long afterLineChar = LargeTextCoordinateMath.advancePastLine(nextLineChar, emitted.length());
                long lineChars = afterLineChar - nextLineChar;
                nextLineChar = afterLineChar;

                if (tailBuf != null && line <= bodyEndLine && tailKeep > 0) {
                    tailBuf.addLast(emitted);
                    tailChars += lineChars;
                    if (tailBuf.size() > tailKeep) {
                        tailChars -= tailBuf.pollFirst().length() + 1L;
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
            // Cancellation must close the in-progress reader before publishing a cursor.
            checkInterrupted();
            LargeTextCoordinateMath.checkedPosition(baseChars);
            LargeTextCoordinateMath.checkedPosition(baseChars + out.length());
            if (!file.isFile() || file.length() != sourceLength
                    || file.lastModified() != sourceModified) {
                throw new IOException("TXT changed while reading a partition");
            }
        } catch (IOException | RuntimeException | Error e) {
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
            long checkpointLine = Math.min((long) bodyEndLine + 1L, line);
            java.util.ArrayDeque<String> nextQueue = new java.util.ArrayDeque<>();
            int tailSize = tailBuf != null ? tailBuf.size() : 0;
            if (tailBuf != null) nextQueue.addAll(tailBuf);
            if (lookaheadEmitted != null) nextQueue.addAll(lookaheadEmitted);
            if (carryText != null) nextQueue.addLast(carryText);
            nextQueue.addAll(pending); // unprocessed remainder keeps its order
            boolean sourceLeft = !hitEof || !nextQueue.isEmpty();
            if (sourceLeft) {
                cursor.filePath = file.getAbsolutePath();
                cursor.fileLength = sourceLength;
                cursor.fileModified = sourceModified;
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
                // A one-past-last checkpoint may be MAX+1; queued real rows
                // bring it back into the validated line-number range.
                cursor.queueStartLine = (int) (checkpointLine - tailSize);
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
                (int) baseChars, // range checked before publishing or retaining a cursor
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
