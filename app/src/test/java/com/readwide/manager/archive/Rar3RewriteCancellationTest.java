package com.readwide.manager.archive;

import static org.junit.Assert.*;
import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class Rar3RewriteCancellationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void cancelledTokenStopsAllRewriteRoutesBeforeBackendAndOutput() throws Exception {
        File output = temp.newFile();
        Files.write(output.toPath(), new byte[]{42});
        FileOperationProgress progress = new FileOperationProgress("RAR", null);
        progress.cancel();
        for (IOCall route : routes(output, progress)) assertThrows(InterruptedIOException.class, route::run);
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void interruptedThreadStopsAllRewriteRoutesWithoutProgressToken() throws Exception {
        File output = temp.newFile();
        Files.write(output.toPath(), new byte[]{42});
        IOCall[] routes = routes(output, null);
        Thread.currentThread().interrupt();
        try {
            for (IOCall route : routes) {
                assertThrows(InterruptedIOException.class, route::run);
                assertTrue(Thread.currentThread().isInterrupted());
            }
        } finally { Thread.interrupted(); }
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void typedCancellationIsRethrownEvenAfterInterruptFlagWasConsumed() throws Exception {
        InterruptedIOException cancellation = new InterruptedIOException("stopped");
        assertSame(cancellation, assertThrows(InterruptedIOException.class, () -> policy(cancellation, null)));
    }

    @Test public void cancelledTokenPreservesOriginalFailureAsCause() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("RAR", null);
        progress.cancel();
        IOException source = new IOException("backend stopped");
        assertSame(source, assertThrows(InterruptedIOException.class, () -> policy(source, progress)).getCause());
    }

    @Test public void interruptedThreadPreservesOriginalFailureAsCause() throws Exception {
        SecurityException source = new SecurityException("source unavailable");
        Thread.currentThread().interrupt();
        try {
            assertSame(source, assertThrows(InterruptedIOException.class, () -> policy(source, null)).getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void ordinaryFailureStillAllowsFallback() throws Exception {
        policy(new IOException("unsupported payload"), null);
        policy(new SecurityException("source unavailable"), new FileOperationProgress("RAR", null));
    }

    private IOCall[] routes(File output, FileOperationProgress progress) {
        File source = new File(temp.getRoot(), "absent.rar");
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry("entry.bin", false,
                8, 16, 0, 4, 0x33, false, false, true,
                RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[8]), 0, 0);
        entry.sourceArchive = source;
        return new IOCall[]{
                () -> Rar3Or4SpecialCaseExtractor.tryExtractArchiveWithDecryptedCopy(source,
                        Collections.singletonList(entry), output, new char[]{'p'}, progress, null),
                () -> Rar3Or4SpecialCaseExtractor.tryExtractEntryWithDecryptedCopy(entry,
                        output, new char[]{'p'}, progress),
                () -> Rar3Or4SpecialCaseExtractor.tryExtractSplitEntryWithDecryptedCopy(entry,
                        Collections.singletonList(entry), output, new char[]{'p'}, progress),
                () -> Rar3Or4SpecialCaseExtractor.tryExtractSplitEntryWithRewrittenCopy(entry,
                        Collections.singletonList(entry), output, progress)
        };
    }

    private static void policy(Exception failure, FileOperationProgress progress) throws Exception {
        java.lang.reflect.Method method = Rar3Or4SpecialCaseExtractor.class.getDeclaredMethod(
                "rethrowCancellation", Exception.class, FileOperationProgress.class);
        method.setAccessible(true);
        try { method.invoke(null, failure, progress); }
        catch (java.lang.reflect.InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw wrapped;
        }
    }

    private interface IOCall { void run() throws IOException; }
}
