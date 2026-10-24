package com.readwide.manager.util;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Uses isolated cache files; never touches a selected document or app settings. */
@RunWith(AndroidJUnit4.class)
public class FileMutationGuardInstrumentedTest {
    @Test public void nativeDescriptorMustBeTheCapturedFileEvenWithSameSizeAndMtime() throws Exception {
        File directory = directory();
        File source = new File(directory, "source");
        File other = new File(directory, "other");
        try {
            write(source, "original");
            write(other, "replaced");
            assertTrue(other.setLastModified(source.lastModified()));
            FileMutationGuard guard = FileMutationGuard.capture(source);
            try (FileInputStream stream = new FileInputStream(other)) {
                try { guard.verifyOpened(stream); fail("different descriptor accepted"); }
                catch (IOException expected) { }
            }
            guard.recheck();
        } finally { source.delete(); other.delete(); directory.delete(); }
    }

    @Test public void nativeDescriptorAndPathRemainValidAfterOrdinaryRead() throws Exception {
        File directory = directory();
        File source = new File(directory, "source");
        try {
            write(source, "original");
            FileMutationGuard guard = FileMutationGuard.capture(source);
            try (FileInputStream stream = new FileInputStream(source)) {
                guard.verifyOpened(stream);
                while (stream.read() >= 0) { }
                guard.verifyOpened(stream); // atime changes must not invalidate the source.
            }
            guard.recheck();
        } finally { source.delete(); directory.delete(); }
    }

    private static File directory() throws IOException {
        File file = File.createTempFile("rw-mutation-", ".test",
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        if (!file.delete() || !file.mkdir()) throw new IOException("Cannot create test directory");
        return file;
    }
    private static void write(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
