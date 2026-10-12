package com.readwide.manager.util;

import static org.junit.Assert.*;

import com.readwide.manager.model.LargeTextLinePartitionResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.BufferedReader;
import java.io.File;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class LargeTextSingleScanEquivalenceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final LargeTextPartitionReader.LineTransform TRANSFORM = line ->
            line.replace("[blank]", "").replace("x", "xx").replace("[break]", "\n");

    @Test public void canonicalFieldsMatchTwoPassAtBoundariesAndEof() throws Exception {
        String[] texts = {"", "\n", "\n\n\n", "last", "last\n", "a\r\nb\rc\n\n",
                "[blank]\n[blank]\nx😀 e\u0301\n한글\n[break]inside\n\t\nend\n\n"};
        for (String encoding : new String[]{"UTF-8", "UTF-16LE"}) {
            for (String text : texts) {
                File file = temporary.newFile();
                Files.write(file.toPath(), text.getBytes(Charset.forName(encoding)));
                for (boolean collapse : new boolean[]{false, true}) {
                    for (int partition : new int[]{1, 2, 5}) {
                        for (int ahead : new int[]{0, 1, 8}) {
                            for (int target = -1; target <= text.length() * 2 + 2; target++) {
                                compare(file, encoding, collapse, target, partition, ahead);
                            }
                            compare(file, encoding, collapse, Integer.MAX_VALUE, partition, ahead);
                        }
                    }
                }
            }
        }
    }

    @Test public void randomizedWindowsAndLongLinesMatchTwoPass() throws Exception {
        Random random = new Random(193520L);
        File file = temporary.newFile();
        for (int trial = 0; trial < 80; trial++) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 60; i++) {
                String[] fragments = {"", "[blank]", "x", "😀한글", "[break]", "  "};
                text.append(fragments[random.nextInt(fragments.length)]);
                text.append(i % 3 == 0 ? "\r\n" : "\n");
            }
            if (trial == 0) for (int i = 0; i < 16000; i++) text.append("x😀");
            Files.write(file.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
            for (int target : new int[]{0, random.nextInt(text.length() + 1), text.length(), Integer.MAX_VALUE}) {
                compare(file, "UTF-8", random.nextBoolean(), target,
                        1 + random.nextInt(11), random.nextInt(20));
            }
        }
    }

    @Test public void transformsEachSourceLineOnlyOnceEvenForEndJump() throws Exception {
        File file = temporary.newFile();
        Files.write(file.toPath(), "one\ntwo\nthree\nfour\nfive".getBytes(StandardCharsets.UTF_8));
        AtomicInteger transformed = new AtomicInteger();
        LargeTextLinePartitionResult actual = LargeTextPartitionReader.readPartitionForCharTransformed(
                file, "UTF-8", false, Integer.MAX_VALUE, 2, 1,
                line -> { transformed.incrementAndGet(); return TRANSFORM.apply(line); });
        assertEquals(5, transformed.get());
        assertFields(reference(file, "UTF-8", false, Integer.MAX_VALUE, 2, 1), actual);
    }

    @Test public void cancellationAlsoRejectsEmptyFileAndLastLine() throws Exception {
        File file = temporary.newFile();
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () ->
                    LargeTextPartitionReader.readPartitionForCharTransformed(
                            file, "UTF-8", false, 0, 2, 1, TRANSFORM));
        } finally { Thread.interrupted(); }
        Files.write(file.toPath(), "last".getBytes(StandardCharsets.UTF_8));
        try {
            assertThrows(InterruptedIOException.class, () ->
                    LargeTextPartitionReader.readPartitionForCharTransformed(
                            file, "UTF-8", false, 0, 2, 1,
                            line -> { Thread.currentThread().interrupt(); return line; }));
        } finally { Thread.interrupted(); }
        compare(file, "UTF-8", false, 0, 2, 1);
    }

    @Test public void changedSourceIsNotPublishedAsConsistentStatsAndPartition() throws Exception {
        File file = temporary.newFile();
        Files.write(file.toPath(), "first\nlast".getBytes(StandardCharsets.UTF_8));
        AtomicInteger transformed = new AtomicInteger();
        assertThrows(java.io.IOException.class, () ->
                LargeTextPartitionReader.readPartitionForCharTransformed(
                        file, "UTF-8", false, 0, 2, 1, line -> {
                            if (transformed.getAndIncrement() == 0) {
                                try { Files.write(file.toPath(), new byte[]{'!'}, StandardOpenOption.APPEND); }
                                catch (java.io.IOException failure) { throw new AssertionError(failure); }
                            }
                            return line;
                        }));
    }

    private static void compare(File file, String encoding, boolean collapse,
            int target, int partition, int ahead) throws Exception {
        assertFields(reference(file, encoding, collapse, target, partition, ahead),
                LargeTextPartitionReader.readPartitionForCharTransformed(
                        file, encoding, collapse, target, partition, ahead, TRANSFORM));
    }

    /** Frozen stats+selection logic; the known-stats partition reader is unchanged. */
    private static LargeTextLinePartitionResult reference(File file, String encoding,
            boolean collapse, int requestedChar, int partition, int ahead) throws Exception {
        int target = Math.max(0, requestedChar), line = 1, targetLine = -1;
        long chars = 0;
        boolean sawAnyLine = false;
        TxtBlankLineCollapser.Filter filter = new TxtBlankLineCollapser.Filter(collapse);
        try (BufferedReader reader = LargeTextPartitionReader.openReader(file, encoding)) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                sawAnyLine = true;
                String emitted = filter.accept(TRANSFORM.apply(raw));
                if (emitted == null) continue;
                int lineChars = emitted.length() + 1;
                if (targetLine < 0 && target >= chars && target < chars + lineChars) targetLine = line;
                chars += lineChars;
                line++;
            }
        }
        int totalLines = sawAnyLine ? Math.max(1, line - 1) : 1;
        if (targetLine < 0) targetLine = totalLines;
        int start = LargeTextContinuityMath.partitionStartLineForLine(targetLine, partition);
        return LargeTextPartitionReader.readPartitionAtStartLineTransformed(
                file, encoding, collapse, start, totalLines,
                (int) Math.max(0L, Math.min(Integer.MAX_VALUE, chars)),
                partition, ahead, 7, false, 1, TRANSFORM, null);
    }

    private static void assertFields(LargeTextLinePartitionResult expected, LargeTextLinePartitionResult actual) {
        assertEquals(expected.content, actual.content);
        assertEquals(expected.lineCount, actual.lineCount);
        assertEquals(expected.startLine, actual.startLine);
        assertEquals(expected.endLine, actual.endLine);
        assertEquals(expected.totalLines, actual.totalLines);
        assertEquals(expected.baseCharOffset, actual.baseCharOffset);
        assertEquals(expected.bodyStartCharCount, actual.bodyStartCharCount);
        assertEquals(expected.bodyCharCount, actual.bodyCharCount);
        assertEquals(expected.windowStartLine, actual.windowStartLine);
        assertEquals(expected.includesLookbehind, actual.includesLookbehind);
        assertEquals(expected.totalChars, actual.totalChars);
    }
}
