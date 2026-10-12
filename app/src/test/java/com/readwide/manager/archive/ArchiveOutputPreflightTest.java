package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ArchiveOutputPreflightTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void childThenParentFileIsRejectedWithoutCreatingAnything() throws Exception {
        File root = temporary.newFolder();
        ArchiveOutputPreflight plan = new ArchiveOutputPreflight(root);
        plan.add("chapter/page.jpg", false);
        assertThrows(IOException.class, () -> plan.add("chapter", false));
        assertEquals(0, root.list().length);
    }

    @Test public void parentFileThenChildIsRejected() throws Exception {
        ArchiveOutputPreflight plan = new ArchiveOutputPreflight(temporary.newFolder());
        plan.add("chapter", false);
        assertThrows(IOException.class, () -> plan.add("chapter/page.jpg", false));
    }

    @Test public void explicitDirectoryCannotAliasFile() throws Exception {
        ArchiveOutputPreflight plan = new ArchiveOutputPreflight(temporary.newFolder());
        plan.add("chapter/", true);
        assertThrows(IOException.class, () -> plan.add("chapter", false));
    }

    @Test public void existingParentFileIsPreserved() throws Exception {
        File root = temporary.newFolder(), original = new File(root, "chapter");
        Files.write(original.toPath(), new byte[]{42});
        ArchiveOutputPreflight plan = new ArchiveOutputPreflight(root);
        assertThrows(IOException.class, () -> plan.add("chapter/page.jpg", false));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(original.toPath()));
    }

    @Test public void directoryDeclarationsAndFileDuplicatesRemainAllowed() throws Exception {
        File root = temporary.newFolder();
        ArchiveOutputPreflight plan = new ArchiveOutputPreflight(root);
        File child = plan.add("chapter/page.jpg", false);
        plan.add("chapter/", true);
        assertEquals(child, plan.add("./chapter/page.jpg", false));
        assertEquals(0, root.list().length);
    }

    @Test public void unsafeOrRootFilePathsAreRejected() throws Exception {
        for (String path : Arrays.asList("../escape", "/absolute", "C:/absolute", ".")) {
            ArchiveOutputPreflight plan = new ArchiveOutputPreflight(temporary.newFolder());
            assertThrows(path, IOException.class, () -> plan.add(path, false));
        }
    }

    @Test public void rarSplitContinuationDoesNotCreateAnotherDestination() throws Exception {
        RarArchiveReader.RarEntry head = new RarArchiveReader.RarEntry("file", false,
                2, 1, 0, 4, 0x30, false, false, true, null, 0, 0);
        RarArchiveReader.RarEntry tail = new RarArchiveReader.RarEntry("file", false,
                2, 1, 0, 4, 0x30, false, true, false, null, 0, 0);
        assertEquals(1, ArchiveOutputPreflight.rar(temporary.newFolder(), Arrays.asList(head, tail)).size());
    }

    @Test public void interruptedPreflightCreatesNoOutput() throws Exception {
        File root = temporary.newFolder();
        ArchiveOutputPreflight plan = new ArchiveOutputPreflight(root);
        try {
            Thread.currentThread().interrupt();
            assertThrows(java.io.InterruptedIOException.class, () -> plan.add("file", false));
        } finally { Thread.interrupted(); }
        assertEquals(0, root.list().length);
    }
}
