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
}
