package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;

import com.readwide.manager.model.LargeTextLinePartitionResult;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Regression coverage for the large-TXT full-scan/forward-cursor contract. */
public class LargeTextForwardCursorEquivalenceTest {
    private static final int RULES_VERSION = 73;
    private static final LargeTextPartitionReader.LineTransform TRANSFORM = line ->
            line.replace("[blank]", "").replace("x", "xx");

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void adversarialRequestChain_isFieldEquivalent() throws Exception {
        ArrayList<String> lines = new ArrayList<>();
        for (int i = 0; i < 96; i++) {
            if (i % 13 == 0) {
                lines.add("[blank]");
            } else if (i % 11 == 0) {
                lines.add("   ");
            } else if (i % 7 == 0) {
                lines.add("");
            } else {
                lines.add("line-" + i + "-x-" + (i * 17));
            }
        }
        File file = writeFile("adversarial.txt", lines, true, new Random(11L));
        assertRequestChainEquivalent(file, lines, false, new Request[] {
                new Request(1, 9, 5, 4, false),
                new Request(10, 9, 5, 4, true),
                new Request(19, 9, 18, 4, false),
                new Request(37, 7, 2, 6, true),
                new Request(23, 7, 11, 6, true), // backward reset
                new Request(44, 13, 1, 2, false),
                new Request(44, 13, 1, 2, false), // repeated-window reset
                new Request(83, 5, 20, 12, true)  // EOF inside lookahead
        });
        assertRequestChainEquivalent(file, lines, true, new Request[] {
                new Request(1, 6, 12, 5, false),
                new Request(7, 6, 12, 5, true),
                new Request(13, 6, 1, 5, false),
                new Request(31, 10, 8, 3, true),
                new Request(11, 10, 8, 3, true),
                new Request(61, 8, 30, 7, false)
        });
    }

    @Test
    public void randomizedRequestChains_areFieldEquivalent() throws Exception {
        Random random = new Random(0x5EEDC0DEL);
        for (int trial = 0; trial < 120; trial++) {
            int sourceLineCount = 20 + random.nextInt(180);
            ArrayList<String> lines = randomLines(random, sourceLineCount);
            File file = writeFile("random-" + trial + ".txt", lines, random.nextBoolean(), random);
            boolean collapse = random.nextBoolean();
            Canonical canonical = canonical(lines, collapse);
            LargeTextPartitionReader.ForwardCursor cursor = new LargeTextPartitionReader.ForwardCursor();
            try {
                int requestLine = 1;
                for (int request = 0; request < 45; request++) {
                    int partitionLines = 1 + random.nextInt(24);
                    if (request % 5 <= 2) {
                        requestLine += random.nextInt(partitionLines * 2 + 1);
                    } else if (request % 5 == 3) {
                        requestLine -= random.nextInt(partitionLines * 2 + 1);
                    } else {
                        requestLine = 1 + random.nextInt(canonical.totalLines);
                    }
                    requestLine = Math.max(1, Math.min(canonical.totalLines, requestLine));
                    Request spec = new Request(
                            requestLine,
                            partitionLines,
                            random.nextInt(30),
                            random.nextInt(20),
                            random.nextBoolean());
                    assertEquivalent(
                            "trial=" + trial + ", request=" + request,
                            read(file, collapse, canonical, spec, null),
                            read(file, collapse, canonical, spec, cursor));
                }
            } finally {
                cursor.closeQuietly();
            }
        }
    }

    @Test
    public void sequentialBodies_tileCanonicalTextWithoutSkipOrDuplication() throws Exception {
        Random random = new Random(9917L);
        ArrayList<String> lines = randomLines(random, 137);
        File file = writeFile("tiling.txt", lines, true, random);
        for (boolean collapse : new boolean[] {false, true}) {
            Canonical canonical = canonical(lines, collapse);
            LargeTextPartitionReader.ForwardCursor cursor = new LargeTextPartitionReader.ForwardCursor();
            StringBuilder tiled = new StringBuilder();
            try {
                int partitionLines = 7;
                boolean first = true;
                for (int start = 1; start <= canonical.totalLines; start += partitionLines) {
                    Request spec = new Request(start, partitionLines, 9, 5, start > 1);
                    LargeTextLinePartitionResult result = read(file, collapse, canonical, spec, cursor);
                    if (!first) tiled.append('\n');
                    first = false;
                    tiled.append(result.content, result.bodyStartCharCount, result.bodyCharCount);
                }
            } finally {
                cursor.closeQuietly();
            }
            assertEquals("collapse=" + collapse, String.join("\n", canonical.lines), tiled.toString());
        }
    }

    private void assertRequestChainEquivalent(File file,
                                              List<String> sourceLines,
                                              boolean collapse,
                                              Request[] requests) throws Exception {
        Canonical canonical = canonical(sourceLines, collapse);
        LargeTextPartitionReader.ForwardCursor cursor = new LargeTextPartitionReader.ForwardCursor();
        try {
            for (int i = 0; i < requests.length; i++) {
                assertEquivalent(
                        "collapse=" + collapse + ", request=" + i,
                        read(file, collapse, canonical, requests[i], null),
                        read(file, collapse, canonical, requests[i], cursor));
            }
        } finally {
            cursor.closeQuietly();
        }
    }

    private static LargeTextLinePartitionResult read(File file,
                                                     boolean collapse,
                                                     Canonical canonical,
                                                     Request request,
                                                     LargeTextPartitionReader.ForwardCursor cursor) throws Exception {
        return LargeTextPartitionReader.readPartitionAtStartLineTransformed(
                file,
                StandardCharsets.UTF_8.name(),
                collapse,
                request.startLine,
                canonical.totalLines,
                canonical.totalChars,
                request.partitionLines,
                request.lookaheadLines,
                request.lookbehindLines,
                request.includeLookbehind,
                RULES_VERSION,
                TRANSFORM,
                cursor);
    }

    private static void assertEquivalent(String label,
                                         LargeTextLinePartitionResult expected,
                                         LargeTextLinePartitionResult actual) {
        assertEquals(label + " content", expected.content, actual.content);
        assertEquals(label + " baseCharOffset", expected.baseCharOffset, actual.baseCharOffset);
        assertEquals(label + " bodyStartCharCount", expected.bodyStartCharCount, actual.bodyStartCharCount);
        assertEquals(label + " bodyCharCount", expected.bodyCharCount, actual.bodyCharCount);
        assertEquals(label + " lineCount", expected.lineCount, actual.lineCount);
        assertEquals(label + " startLine", expected.startLine, actual.startLine);
        assertEquals(label + " endLine", expected.endLine, actual.endLine);
        assertEquals(label + " totalLines", expected.totalLines, actual.totalLines);
        assertEquals(label + " windowStartLine", expected.windowStartLine, actual.windowStartLine);
        assertEquals(label + " includesLookbehind", expected.includesLookbehind, actual.includesLookbehind);
        assertEquals(label + " totalChars", expected.totalChars, actual.totalChars);
    }

    private File writeFile(String name,
                           List<String> lines,
                           boolean trailingNewline,
                           Random random) throws Exception {
        File file = temporaryFolder.newFile(name);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            text.append(lines.get(i));
            if (i + 1 < lines.size() || trailingNewline) {
                int kind = random.nextInt(3);
                text.append(kind == 0 ? "\n" : kind == 1 ? "\r\n" : "\r");
            }
        }
        Files.write(file.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static ArrayList<String> randomLines(Random random, int count) {
        ArrayList<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int kind = random.nextInt(12);
            if (kind == 0) {
                lines.add("");
            } else if (kind == 1) {
                lines.add(" \t ");
            } else if (kind == 2) {
                lines.add("[blank]");
            } else {
                lines.add("row-" + i + "-x-" + Integer.toHexString(random.nextInt()));
            }
        }
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.set(lines.size() - 1, "last-x");
        }
        return lines;
    }

    private static Canonical canonical(List<String> sourceLines, boolean collapse) {
        TxtBlankLineCollapser.Filter filter = new TxtBlankLineCollapser.Filter(collapse);
        ArrayList<String> emitted = new ArrayList<>();
        int totalChars = 0;
        for (String source : sourceLines) {
            String line = filter.accept(TRANSFORM.apply(source));
            if (line == null) continue;
            emitted.add(line);
            totalChars += line.length() + 1;
        }
        return new Canonical(emitted, Math.max(1, emitted.size()), totalChars);
    }

    private static final class Canonical {
        final ArrayList<String> lines;
        final int totalLines;
        final int totalChars;

        Canonical(ArrayList<String> lines, int totalLines, int totalChars) {
            this.lines = lines;
            this.totalLines = totalLines;
            this.totalChars = totalChars;
        }
    }

    private static final class Request {
        final int startLine;
        final int partitionLines;
        final int lookaheadLines;
        final int lookbehindLines;
        final boolean includeLookbehind;

        Request(int startLine,
                int partitionLines,
                int lookaheadLines,
                int lookbehindLines,
                boolean includeLookbehind) {
            this.startLine = startLine;
            this.partitionLines = partitionLines;
            this.lookaheadLines = lookaheadLines;
            this.lookbehindLines = lookbehindLines;
            this.includeLookbehind = includeLookbehind;
        }
    }
}
