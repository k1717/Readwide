package com.readwide.manager.archive;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ArchiveTestTextFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void preservesUtf8UnicodeAndLineEndingsWithoutAddingNewline() throws Exception {
        Path path = temporary.getRoot().toPath().resolve("unicode.txt");
        String text = "\uD398\uC774\uC9C0 \uD83D\uDCD6\r\nsecond\nthird\r";
        assertEquals(path, ArchiveTestTextFiles.writeString(path, new StringBuilder(text)));
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(path));
        assertEquals(text, ArchiveTestTextFiles.readString(path));
    }

    @Test public void defaultWriteCreatesAndTruncatesIncludingEmptyContent() throws Exception {
        Path path = temporary.getRoot().toPath().resolve("truncate.txt");
        ArchiveTestTextFiles.writeString(path, "original-long-content");
        ArchiveTestTextFiles.writeString(path, "new");
        assertArrayEquals(new byte[]{'n', 'e', 'w'}, Files.readAllBytes(path));
        ArchiveTestTextFiles.writeString(path, "");
        assertEquals(0, Files.size(path));
        assertEquals("", ArchiveTestTextFiles.readString(path));
    }

    @Test public void malformedUtf8FailsInsteadOfReplacingBytes() throws Exception {
        Path path = temporary.newFile().toPath();
        Files.write(path, new byte[]{(byte) 0xC3, 0x28});
        assertThrows(CharacterCodingException.class, () -> ArchiveTestTextFiles.readString(path));
    }

    @Test public void malformedUtf16DoesNotTruncateExistingDestination() throws Exception {
        Path path = temporary.newFile().toPath();
        Files.write(path, new byte[]{'k', 'e', 'e', 'p'});
        assertThrows(CharacterCodingException.class,
                () -> ArchiveTestTextFiles.writeString(path, "\uD800"));
        assertArrayEquals(new byte[]{'k', 'e', 'e', 'p'}, Files.readAllBytes(path));
    }

    @Test public void missingSourceIsNotTreatedAsEmptyText() throws Exception {
        Path path = temporary.getRoot().toPath().resolve("missing.txt");
        assertThrows(NoSuchFileException.class, () -> ArchiveTestTextFiles.readString(path));
    }
}
