package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Synthetic Swap4/AES containers exercise the production bulk path, not a mock parser. */
@RunWith(Parameterized.class)
public class SevenZOutputPreflightTest {
    @Parameterized.Parameters(name="AES={0},split={1}")
    public static Collection<Object[]> variants() {
        return Arrays.asList(new Object[][]{{false,false},{true,false},{false,true},{true,true}});
    }
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final boolean aes, split;
    public SevenZOutputPreflightTest(boolean aes, boolean split) { this.aes = aes; this.split = split; }
    private char[] password() { return aes ? Round4Fixtures.PASSWORD : null; }

    @Test public void lateUnsafePathDoesNotReplaceEarlierFile() throws Exception {
        for (String name : new String[]{"../escape", "/absolute", "C:/absolute"}) {
            File archive = fixture("first", name), root = temporary.newFolder();
            File first = new File(root, "first");
            Files.write(first.toPath(), new byte[]{99});
            assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(
                    archive, root, password(), null, null));
            assertArrayEquals(new byte[]{99}, Files.readAllBytes(first.toPath()));
            assertEquals(1, root.list().length);
        }
    }

    @Test public void parentAndChildFileConflictFailsInEitherOrder() throws Exception {
        for (String[] names : new String[][]{{"chapter", "chapter/page"}, {"chapter/page", "chapter"}}) {
            File archive = fixture(names[0], names[1]), root = temporary.newFolder();
            assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(
                    archive, root, password(), null, null));
            assertEquals(0, root.list().length);
        }
    }

    @Test public void existingDirectoryCannotBeOverwrittenAsFile() throws Exception {
        File archive = fixture("first", "second"), root = temporary.newFolder();
        assertTrue(new File(root, "second").mkdir());
        assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(
                archive, root, password(), null, null));
        assertFalse(new File(root, "first").exists());
        assertTrue(new File(root, "second").isDirectory());
    }

    @Test public void normalizedNamesMatchListingAndBulkOutput() throws Exception {
        String first = "./chapter//one", second = "chapter\\two";
        File archive = fixture(first, second), root = temporary.newFolder();
        assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, root, password(), null, null));
        assertArrayEquals(new byte[]{1,2}, Files.readAllBytes(
                new File(root, ArchiveSupport.sanitizeEntryPathForList(first)).toPath()));
        assertArrayEquals(new byte[]{3,4}, Files.readAllBytes(
                new File(root, ArchiveSupport.sanitizeEntryPathForList(second)).toPath()));
    }

    @Test public void selectedExtractionStillIgnoresUnrelatedUnsafeName() throws Exception {
        File archive = fixture("first", "../escape"), output = temporary.newFile();
        assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, "first", output, password()));
        assertArrayEquals(new byte[]{1,2}, Files.readAllBytes(output.toPath()));
    }

    private File fixture(String first, String second) throws Exception {
        Round4Fixtures.Graph graph = Round4Fixtures.graph(new byte[]{1,2,3,4}, 4, aes);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(1); header.write(4); Round4Fixtures.streams(header, graph, 0, true, false);
        header.write(5); Round4Fixtures.number(header, 2);
        byte[] names = (first + '\0' + second + '\0').getBytes(StandardCharsets.UTF_16LE);
        header.write(17); Round4Fixtures.number(header, names.length + 1);
        header.write(0); header.write(names); header.write(0); header.write(0);
        byte[] next = header.toByteArray();
        ByteArrayOutputStream start = new ByteArrayOutputStream();
        Round4Fixtures.le(start, graph.packed.length, 8); Round4Fixtures.le(start, next.length, 8);
        Round4Fixtures.le(start, Round4Fixtures.crc(next), 4);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(new byte[]{'7','z',(byte)0xbc,(byte)0xaf,0x27,0x1c,0,4});
        Round4Fixtures.le(archive, Round4Fixtures.crc(start.toByteArray()), 4);
        archive.write(start.toByteArray()); archive.write(graph.packed); archive.write(next);
        File directory = temporary.newFolder();
        if (!split) return Round4Fixtures.write(directory, "fixture.7z", archive.toByteArray());
        byte[] bytes = archive.toByteArray();
        File firstPart = Round4Fixtures.write(directory, "fixture.7z.001", Arrays.copyOf(bytes, 35));
        Round4Fixtures.write(directory, "fixture.7z.002", Arrays.copyOfRange(bytes, 35, bytes.length));
        return firstPart;
    }
}
