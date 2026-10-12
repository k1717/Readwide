package com.readwide.manager.archive;

import static org.junit.Assert.*;
import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RarSplitScanCancellationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void cancelledTokenStopsBothScannersBeforeSourceOpen() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("scan", null);
        progress.cancel();
        for (Class<?> owner : owners()) assertThrows(InterruptedIOException.class, () -> scan(owner, progress));
    }

    @Test public void threadInterruptionStopsBothScannersWithoutToken() throws Exception {
        Thread.currentThread().interrupt();
        try {
            for (Class<?> owner : owners()) assertThrows(InterruptedIOException.class, () -> scan(owner, null));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void cancelledRewriteEntriesStopBeforeMetadataOrPasswordWork() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("rewrite", null);
        progress.cancel();
        File absent = new File(temporary.getRoot(), "absent.rar");
        assertThrows(InterruptedIOException.class, () -> Rar4SplitPayloadRewriter.buildSingleEntryCopy(
                absent, 0, Collections.emptyList(), 0, progress));
        assertThrows(InterruptedIOException.class, () -> Rar4EncryptedSplitPayloadRewriter.buildSingleEntryDecryptedCopy(
                absent, 0, Collections.emptyList(), 0, new char[0], progress));
    }

    private static Class<?>[] owners() {
        return new Class<?>[]{Rar4SplitPayloadRewriter.class, Rar4EncryptedSplitPayloadRewriter.class};
    }

    private void scan(Class<?> owner, FileOperationProgress progress) throws Exception {
        Method method = owner.getDeclaredMethod("findFileHeaderForDataOffset",
                File.class, long.class, FileOperationProgress.class);
        method.setAccessible(true);
        try { method.invoke(null, new File(temporary.getRoot(), "absent.rar"), 0L, progress); }
        catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw wrapped;
        }
    }
}
