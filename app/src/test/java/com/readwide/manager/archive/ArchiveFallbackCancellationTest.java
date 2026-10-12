package com.readwide.manager.archive;

import static org.junit.Assert.*;
import com.readwide.manager.util.FileOperationProgress;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ArchiveFallbackCancellationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Object call(String name, Object... args) throws Exception {
        Method method = Arrays.stream(ArchiveSupport.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().get();
        method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw error;
        }
    }
    private File sentinel(File root) throws Exception {
        File output = new File(root, "keep"); Files.write(output.toPath(), new byte[]{42}); return output;
    }
    @Test public void wrappedCancellationIsTerminalWithoutThreadFlag() {
        InterruptedIOException cancelled = new InterruptedIOException("cancelled");
        IOException wrapped = new IOException("outer", new IOException("inner", cancelled));
        assertSame(cancelled, assertThrows(InterruptedIOException.class,
                () -> ArchiveCancellation.rethrowIfCancelled(wrapped, null)));
    }
    @Test(timeout=1000) public void cyclicOrdinaryCausesDoNotLoopOrBecomeCancellation() throws Exception {
        IOException first = new IOException("first"), second = new IOException("second");
        first.initCause(second); second.initCause(first);
        ArchiveCancellation.rethrowIfCancelled(first, null);
    }
    @Test public void cancelledBulkFallbackDoesNotClearWorkingDirectory() throws Exception {
        File root = temporary.newFolder(), keep = sentinel(root);
        FileOperationProgress progress = new FileOperationProgress("extract", null); progress.cancel();
        assertThrows(InterruptedIOException.class, () -> call("tryExtractArchiveWithLibarchiveAfterDedicatedFailure",
                new File(root, "missing.zip"), ArchiveSupport.Type.ZIP, root, null, progress, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(keep.toPath()));
    }
    @Test public void cancelledSelectedFallbackDoesNotDeleteExistingOutput() throws Exception {
        File root = temporary.newFolder(), keep = sentinel(root);
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> call("tryExtractSingleEntryWithLibarchiveAfterDedicatedFailure",
                    new File(root, "missing.tar"), ArchiveSupport.Type.TAR, "page", keep, null, null));
        } finally { Thread.interrupted(); }
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(keep.toPath()));
    }
    @Test public void interruptedSpecialCoderProbeDoesNotBecomeUnsupported() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(
                    new File(temporary.getRoot(), "missing.7z"), null));
        } finally { Thread.interrupted(); }
    }
    @Test public void interruptedSupplementalListingDoesNotBecomeNull() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> call("tryListSevenZBcj2Entries",
                    new File(temporary.getRoot(), "missing.7z"), null));
            assertThrows(InterruptedIOException.class, () -> call("tryListEntriesWithLibarchive",
                    new File(temporary.getRoot(), "missing.zip"), ArchiveSupport.Type.ZIP, null));
        } finally { Thread.interrupted(); }
    }
    @Test public void cancelledSupplementalExtractionDoesNotOpenOrReplaceOutput() throws Exception {
        File root = temporary.newFolder(), keep = sentinel(root);
        FileOperationProgress progress = new FileOperationProgress("extract", null); progress.cancel();
        assertThrows(InterruptedIOException.class, () -> call("tryExtractSevenZBcj2IntoDirectory",
                new File(root, "missing.7z"), root, null, progress, null));
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> call("tryExtractSevenZBcj2SingleEntry",
                    new File(root, "missing.7z"), "page", keep, null));
        } finally { Thread.interrupted(); }
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(keep.toPath()));
    }
}
