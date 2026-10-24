package com.readwide.manager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.util.FileMutationGuard;
import com.readwide.manager.util.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.Assert.*;

/** Source-only regression cases; these require Android filesystem execution. */
@RunWith(AndroidJUnit4.class)
public class TxtSourceWriteInstrumentedTest {
    private File directory;
    private File original;

    @Before public void prepare() throws IOException {
        directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                "txt-source-write-" + UUID.randomUUID());
        assertTrue(directory.mkdir());
        original = new File(directory, "original.txt");
        try (FileOutputStream out = new FileOutputStream(original)) {
            out.write("original".getBytes(StandardCharsets.UTF_8));
        }
    }

    @After public void clean() {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) assertTrue(file.delete());
        assertTrue(directory.delete());
    }

    @Test public void representableReplacementKeepsRequestedEncoding() throws IOException {
        replace("replacement", "US-ASCII", FileMutationGuard.capture(original));
        assertEquals("replacement", readOriginal());
        assertEquals(1, directory.list().length);
    }

    @Test public void unmappableReplacementPreservesOriginalAndRemovesTemporaryFile() throws IOException {
        expectFailure("\uD55C\uAE00", "US-ASCII", FileMutationGuard.capture(original));
    }

    @Test public void malformedSurrogateAtWriterClosePreservesOriginalAndRemovesTemporaryFile() throws IOException {
        expectFailure("text\uD800", "UTF-8", FileMutationGuard.capture(original));
    }

    @Test public void replacedIdentityWithSameLengthAndTimestampRejectsReplacement() throws IOException {
        FileMutationGuard before = FileMutationGuard.capture(original);
        long modified = original.lastModified();
        File replaced = new File(directory, "external.txt");
        write(replaced, "original");
        assertTrue(replaced.setLastModified(modified));
        try { android.system.Os.rename(replaced.getPath(), original.getPath()); }
        catch (android.system.ErrnoException e) { throw new IOException(e); }
        expectFailure("replacement", "UTF-8", before);
    }

    @Test public void separateCopyDoesNotReplaceOriginal() throws IOException {
        File copy = new File(directory, "original_edited.txt");
        ReaderTextDisplayRuleDialogController.writeTextWithEncodingSafely(
                copy, "copy", "UTF-8", FileMutationGuard.capture(original), null);
        assertEquals("original", readOriginal());
        assertEquals(4, copy.length());
        assertEquals(2, directory.list().length);
    }

    @Test public void appearedCopyTargetIsNotOverwritten() throws IOException {
        File copy = new File(directory, "original_edited.txt");
        FileMutationGuard before = ReaderTextDisplayRuleDialogController.captureExistingOutput(copy);
        assertNull(before);
        write(copy, "external");
        try {
            ReaderTextDisplayRuleDialogController.writeTextWithEncodingSafely(
                    copy, "copy", "UTF-8", FileMutationGuard.capture(original), before);
            fail("New target must be retained");
        } catch (IOException expected) {
            assertEquals("external", FileUtils.readTextFile(copy, "UTF-8"));
            assertEquals(2, directory.list().length);
        }
    }

    @Test public void changedCopyTargetIsNotOverwritten() throws IOException {
        File copy = new File(directory, "original_edited.txt");
        write(copy, "old");
        FileMutationGuard before = ReaderTextDisplayRuleDialogController.captureExistingOutput(copy);
        write(copy, "external");
        try {
            ReaderTextDisplayRuleDialogController.writeTextWithEncodingSafely(
                    copy, "copy", "UTF-8", FileMutationGuard.capture(original), before);
            fail("Changed target must be retained");
        } catch (IOException expected) {
            assertEquals("external", FileUtils.readTextFile(copy, "UTF-8"));
            assertEquals(2, directory.list().length);
        }
    }

    @Test public void confirmedUnchangedCopyTargetCanStillBeReplaced() throws IOException {
        File copy = new File(directory, "original_edited.txt");
        write(copy, "old");
        ReaderTextDisplayRuleDialogController.writeTextWithEncodingSafely(copy, "copy", "UTF-8",
                FileMutationGuard.capture(original), ReaderTextDisplayRuleDialogController.captureExistingOutput(copy));
        assertEquals("copy", FileUtils.readTextFile(copy, "UTF-8"));
        assertEquals("original", readOriginal());
    }

    @Test public void changedSourceAlsoRejectsCopyMode() throws IOException {
        FileMutationGuard source = FileMutationGuard.capture(original);
        write(original, "external change");
        File copy = new File(directory, "original_edited.txt");
        try {
            ReaderTextDisplayRuleDialogController.writeTextWithEncodingSafely(copy, "copy", "UTF-8", source, null);
            fail("Changed source must not publish a stale copy");
        } catch (IOException expected) {
            assertFalse(copy.exists());
            assertEquals("external change", readOriginal());
            assertEquals(1, directory.list().length);
        }
    }

    @Test public void openedSourceUsesSameDecodingPolicyWithoutClosingStream() throws IOException {
        write(original, "\ufeff\uD55C\uAE00\r\ntext");
        FileMutationGuard guard = FileMutationGuard.capture(original);
        String expected = FileUtils.readTextFile(original, "UTF-8");
        try (FileInputStream input = new FileInputStream(original)) {
            guard.verifyOpened(input);
            assertEquals(expected, FileUtils.readTextFromStream(input, "UTF-8"));
            guard.verifyOpened(input);
            assertEquals(-1, input.read());
        }
    }

    private static void write(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void expectFailure(String value, String encoding, FileMutationGuard before) throws IOException {
        try {
            replace(value, encoding, before);
            fail("Write should fail before replacing the original");
        } catch (IOException expected) {
            assertEquals("original", readOriginal());
            assertEquals(1, directory.list().length);
        }
    }

    private void replace(String value, String encoding, FileMutationGuard before) throws IOException {
        ReaderTextDisplayRuleDialogController.writeTextWithEncodingSafely(
                original, value, encoding, before, before);
    }

    private String readOriginal() throws IOException {
        byte[] bytes = new byte[(int) original.length()];
        try (FileInputStream in = new FileInputStream(original)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = in.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IOException("Unexpected EOF");
                offset += count;
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
