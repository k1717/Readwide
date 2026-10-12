package com.readwide.manager.util;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AtomicUtf8FileInstrumentedTest {
    private File directory;
    @Before public void setUp() {
        directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                "atomic-restore-test-" + UUID.randomUUID());
        assertTrue(directory.mkdir());
    }
    @After public void tearDown() {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) assertTrue(file.delete());
        assertTrue(directory.delete());
    }
    private static void write(File file, String value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test public void recoversBackupEvenWhenBaseFileIsAbsent() throws Exception {
        File file = new File(directory, "bookmarks.json");
        write(new File(file.getPath() + ".bak"), "previous data");
        assertFalse(file.exists());
        assertEquals("previous data", AtomicUtf8File.readIfPresent(file));
        assertTrue(file.isFile());
    }

    @Test public void recoveryCopyWinsOverIncompleteBaseFile() throws Exception {
        File file = new File(directory, "reading_states.json");
        write(file, "partial");
        write(new File(file.getPath() + ".bak"), "complete");
        assertEquals("complete", AtomicUtf8File.readIfPresent(file));
    }

    @Test public void trulyMissingStoreReturnsNull() throws Exception {
        assertNull(AtomicUtf8File.readIfPresent(new File(directory, "absent.json")));
    }

    @Test public void checkedWriteRoundTripsAndReportsUnwritablePath() throws Exception {
        File file = new File(directory, "themes.json");
        AtomicUtf8File.write(file, "이전 設定");
        assertEquals("이전 設定", AtomicUtf8File.readIfPresent(file));
        AtomicUtf8File.write(file, "new settings");
        assertEquals("new settings", AtomicUtf8File.readIfPresent(file));
        // A regular file cannot be used as the parent of another file.
        try { AtomicUtf8File.write(new File(file, "child.json"), "bad"); fail("Expected I/O error"); }
        catch (IOException expected) { assertEquals("new settings", AtomicUtf8File.readIfPresent(file)); }
    }

    @Test public void validUnicodeIncludingLiteralReplacementCharacterRoundTrips() throws Exception {
        File file = new File(directory, "unicode.json");
        String text = "한글 café \uD83D\uDE80 \uFE0F \uFFFD";
        AtomicUtf8File.write(file, text);
        assertEquals(text, AtomicUtf8File.readIfPresent(file));
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), raw(file));
    }

    @Test public void malformedAndTruncatedUtf8AreErrorsNotReplacementText() throws Exception {
        File file = new File(directory, "bad.json");
        for (byte[] bytes : new byte[][]{
                {'"', (byte) 0xc3, '(', '"'}, {'"', (byte) 0xe2, (byte) 0x82},
                {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, {(byte) 0xff}}) {
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
            try { AtomicUtf8File.readIfPresent(file); fail("Malformed UTF-8 accepted"); }
            catch (java.nio.charset.CharacterCodingException expected) { assertArrayEquals(bytes, raw(file)); }
        }
    }

    @Test public void malformedRecoveryIsNotTreatedAsAnAbsentStore() throws Exception {
        File file = new File(directory, "recover.json"), backup = new File(file.getPath() + ".bak");
        byte[] bytes = {(byte) 0xc3};
        try (FileOutputStream out = new FileOutputStream(backup)) { out.write(bytes); }
        try { AtomicUtf8File.readIfPresent(file); fail("Malformed recovery accepted"); }
        catch (java.nio.charset.CharacterCodingException expected) {
            // AtomicFile may promote .bak before the decoder reports corruption.
            assertArrayEquals(bytes, raw(file.isFile() ? file : backup));
        }
    }

    @Test public void multibyteTextCrossesDecoderBufferBoundariesWithoutChangingCharacters() throws Exception {
        File file = new File(directory, "buffers.json");
        StringBuilder characters = new StringBuilder();
        for (int i = 0; i < 8191; i++) characters.append('a');
        characters.append("한\uD83D\uDE80");
        for (int i = 0; i < 9000; i++) characters.append('é');
        String text = characters.toString();
        AtomicUtf8File.write(file, text);
        assertEquals(text, AtomicUtf8File.read(file));
    }

    @Test public void invalidLaterReadRollsBackEarlierBackupFileWrite() throws Exception {
        File first = new File(directory, "first.json"), second = new File(directory, "second.json");
        AtomicUtf8File.write(first, "old first");
        byte[] corrupt = {(byte) 0xc3};
        try (FileOutputStream out = new FileOutputStream(second)) { out.write(corrupt); }
        java.util.List<BackupImportTransaction.Step> steps = new java.util.ArrayList<>();
        steps.add(BackupImportTransaction.step(() -> AtomicUtf8File.write(first, "new first"),
                () -> AtomicUtf8File.write(first, "old first")));
        steps.add(BackupImportTransaction.step(() -> AtomicUtf8File.read(second), () -> {}));
        try { BackupImportTransaction.run(steps); fail("Invalid read accepted"); }
        catch (java.nio.charset.CharacterCodingException expected) {
            assertEquals("old first", AtomicUtf8File.read(first));
            assertArrayEquals(corrupt, raw(second));
        }
    }

    private static byte[] raw(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }
}
