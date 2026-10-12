package com.readwide.manager.util;

import java.io.File;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LargeTextAnchorRecoveryTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final String HEADING = "A repeated chapter heading";

    private int resolve(String text, boolean collapse, int fallback, String before, String after) throws Exception {
        File file = folder.newFile();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return LargeTextPartitionReader.findCharPositionForAnchorTransformed(
                file, "UTF-8", collapse, fallback, before, after, line -> line);
    }

    @Test public void contextBeatsCloserButWrongRepeatedHeading() throws Exception {
        String text = "first context\n" + HEADING + "\nfirst body\n"
                + "second context\n" + HEADING + "\nsecond body\n";
        assertEquals(text.lastIndexOf(HEADING), resolve(text, false, text.indexOf(HEADING),
                "second context\n", HEADING + "\nsecond body"));
    }

    @Test public void identicalContextUsesNearestSavedPosition() throws Exception {
        String text = HEADING + "\nsame body\n" + HEADING + "\nsame body\n";
        assertEquals(text.lastIndexOf(HEADING), resolve(text, false, text.lastIndexOf(HEADING) + 2,
                "", HEADING + "\nsame body"));
    }

    @Test public void midLineCursorAndCollapsedOffsetsAreRetained() throws Exception {
        String text = "intro\n\n\n\n" + HEADING + "\nend";
        String collapsed = TxtBlankLineCollapser.collapse(text);
        String before = "intro\n\n\n\n" + HEADING.substring(0, 7);
        assertEquals(collapsed.indexOf(HEADING) + 7, resolve(text, true,
                text.indexOf(HEADING) + 7, before, HEADING.substring(7) + "\nend"));
    }

    @Test public void matchesCrossInputChunksAndConsiderOverlappingOccurrences() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 4090; i++) text.append('x');
        int start = text.length();
        text.append(HEADING).append('\n');
        assertEquals(start, resolve(text.toString(), false, start, "", HEADING));
        assertEquals(4, resolve("aaaaaaaaaaaaaaaaaaaa", false, 4, "", "aaaaaaaaaaaaaaaa"));
    }

    @Test public void crlfAndMissingAnchorKeepExistingFallbackContract() throws Exception {
        assertEquals(6, resolve("intro\r\n" + HEADING, false, 6, "", HEADING));
        assertEquals(-1, resolve("unrelated content", false, 5, "", HEADING));
        assertEquals(-1, resolve("tiny", false, 2, "", "tiny"));
        assertEquals(2, resolve("\n\n" + HEADING, false, 0, "", "\n\n" + HEADING));
    }

    @Test public void eofContextAndRuleTransformedPositionsUseDisplayedText() throws Exception {
        File file = folder.newFile();
        Files.write(file.toPath(), ("x\n" + HEADING).getBytes(StandardCharsets.UTF_8));
        assertEquals(5, LargeTextPartitionReader.findCharPositionForAnchorTransformed(
                file, "UTF-8", false, 2, "xxxx\n", HEADING, line -> line.replace("x", "xxxx")));
    }

    @Test public void cancellationDoesNotClearInterruptFlag() throws Exception {
        File file = folder.newFile();
        Files.write(file.toPath(), HEADING.getBytes(StandardCharsets.UTF_8));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () ->
                    LargeTextPartitionReader.findCharPositionForAnchorTransformed(
                            file, "UTF-8", false, 0, "", HEADING, line -> line));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
