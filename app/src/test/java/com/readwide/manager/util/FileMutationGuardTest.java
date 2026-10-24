package com.readwide.manager.util;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.FileTime;

import static org.junit.Assert.*;

public class FileMutationGuardTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void unchangedFileAndOpenedStreamRemainAccepted() throws Exception {
        File source = source();
        FileMutationGuard guard = FileMutationGuard.capture(source);
        try (FileInputStream stream = new FileInputStream(source)) {
            guard.verifyOpened(stream);
            assertEquals('o', stream.read());
            guard.verifyOpened(stream);
        }
        guard.recheck();
    }

    @Test public void sameSizeSameMtimeReplacementIsNotOriginalIdentity() throws Exception {
        File source = source();
        FileTime modified = Files.getLastModifiedTime(source.toPath());
        FileMutationGuard guard = FileMutationGuard.capture(source);
        File retired = new File(temporary.getRoot(), "retired.txt");
        assertTrue(source.renameTo(retired));
        write(source, "replaced");
        Files.setLastModifiedTime(source.toPath(), modified);
        expectChanged(guard);
        assertTrue(retired.exists());
    }

    @Test public void sameSizeRestoredMtimeRewriteUsesStatusChangeTimeWhenAvailable() throws Exception {
        File source = source();
        Object initialChange;
        try { initialChange = Files.getAttribute(source.toPath(), "unix:ctime", LinkOption.NOFOLLOW_LINKS); }
        catch (UnsupportedOperationException unavailable) { Assume.assumeNoException(unavailable); return; }
        FileTime modified = Files.getLastModifiedTime(source.toPath());
        FileMutationGuard guard = FileMutationGuard.capture(source);
        write(source, "modified");
        Files.setLastModifiedTime(source.toPath(), modified);
        Object finalChange = Files.getAttribute(source.toPath(), "unix:ctime", LinkOption.NOFOLLOW_LINKS);
        Assume.assumeTrue("Requires observable filesystem status-change time", !initialChange.equals(finalChange));
        expectChanged(guard);
    }

    @Test public void deletedOrNonRegularSourceIsRejected() throws Exception {
        File source = source();
        FileMutationGuard guard = FileMutationGuard.capture(source);
        assertTrue(source.delete());
        expectChanged(guard);
        assertTrue(source.mkdir());
        try { FileMutationGuard.capture(source); fail("directory accepted"); }
        catch (IOException expected) { }
    }

    @Test public void sizeChangeIsRejectedWithoutTimestampChange() throws Exception {
        File source = source();
        FileTime modified = Files.getLastModifiedTime(source.toPath());
        FileMutationGuard guard = FileMutationGuard.capture(source);
        write(source, "changed length");
        Files.setLastModifiedTime(source.toPath(), modified);
        expectChanged(guard);
    }

    private File source() throws Exception {
        File file = temporary.newFile("source.txt");
        write(file, "original");
        return file;
    }
    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    private static void expectChanged(FileMutationGuard guard) throws Exception {
        try { guard.recheck(); fail("changed source accepted"); }
        catch (IOException expected) { }
    }
}
