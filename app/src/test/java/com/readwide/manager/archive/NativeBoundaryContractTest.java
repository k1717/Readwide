package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import org.junit.Test;

/** Java-side boundary contracts only. These tests do not invoke native decoding. */
public class NativeBoundaryContractTest {
    private Object call(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = LibarchiveNativeBridge.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }
    private String normalize(String path) throws Exception {
        return (String) call("normalizeForCompare", new Class<?>[]{String.class}, path);
    }

    @Test public void unsafePathsAreNotRenamedIntoRelativeFiles() throws Exception {
        for (String path : new String[]{"/page", "\\page", "//host/share", "../page", "dir/../page", "C:/page", ""}) {
            assertNull(path, normalize(path));
        }
    }
    @Test public void namesShareJavaIdentityWithoutTrimmingOrCaseFolding() throws Exception {
        for (String path : new String[]{"./chapter//page", "chapter\\page", " page ", "Page", "page"}) {
            assertEquals(ArchiveSupport.sanitizeEntryPathForList(path), normalize(path));
        }
        assertNotEquals(normalize("Page"), normalize("page"));
        assertNotEquals(normalize(" page "), normalize("page"));
    }
    @Test public void rootMarkersDoNotAdmitAbsoluteOrParentDirectories() throws Exception {
        for (String path : new String[]{".", "./", "././", ".\\"}) {
            assertEquals(true, call("isRelativeRootMarker", new Class<?>[]{String.class}, path));
        }
        for (String path : new String[]{"/", "../", "./../", "", "C:/", " //"}) {
            assertEquals(false, call("isRelativeRootMarker", new Class<?>[]{String.class}, path));
        }
    }
    @Test public void wrappedCancellationSurvivesNativeErrorClassification() throws Exception {
        InterruptedIOException cancellation = new InterruptedIOException("cancel");
        IOException wrapped = new IOException("unsupported encrypted archive", cancellation);
        assertSame(cancellation, call("toIOException",
                new Class<?>[]{String.class, String.class, Throwable.class}, "read", wrapped.getMessage(), wrapped));
    }
    @Test public void cancellationPrecedesAvailabilityAndSourceOpening() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> LibarchiveNativeBridge.listEntries("missing.rar", null));
            assertThrows(InterruptedIOException.class, () -> LibarchiveNativeBridge.requiresPassword("missing.rar"));
            assertThrows(InterruptedIOException.class, () -> LibarchiveNativeBridge.openForwardStream(new String[]{"missing.rar"}, null));
            assertThrows(InterruptedIOException.class, () -> LibarchiveNativeBridge.openRawForwardStream("missing.zst"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
