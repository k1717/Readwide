package com.readwide.manager.archive;

import static org.junit.Assert.*;

import com.readwide.manager.util.FileOperationProgress;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Each block owns its CRC; no unused archive-wide accumulator is needed. */
public class EggBlockCrcBoundaryTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void independentBlockCrcsPreserveBulkSelectedAndForwardBytes() throws Exception {
        byte[] first = first(), second = {9,8,7};
        for (int method : new int[]{0,1}) {
            File archive = fixture(first, second, method, -1), directory = temporary.newFolder();
            FileOperationProgress progress = new FileOperationProgress("EGG", null);
            assertTrue(EggArchiveReader.extractArchiveIntoDirectory(archive, directory, null, progress, null));
            assertArrayEquals(first, Files.readAllBytes(new File(directory, "first").toPath()));
            assertArrayEquals(second, Files.readAllBytes(new File(directory, "second").toPath()));
            assertEquals(first.length + second.length, progress.snapshot().doneBytes);
            File selected = temporary.newFile();
            assertTrue(EggArchiveReader.extractSingleEntry(archive, "second", selected, null));
            assertArrayEquals(second, Files.readAllBytes(selected.toPath()));
            File spool = temporary.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader = EggArchiveReader.openSolidForwardReader(archive, spool, null)) {
                assertEquals("first", reader.nextEntry().path);
                assertEquals("second", reader.nextEntry().path); // Drain and check the first block.
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[2];
                for (int n; (n = reader.read(buffer)) != -1;) bytes.write(buffer, 0, n);
                assertArrayEquals(second, bytes.toByteArray());
                assertNull(reader.nextEntry());
            }
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void badLaterBlockKeepsVerifiedPrefixAndRestoresPendingTarget() throws Exception {
        byte[] first = first(), second = {9,8,7}, original = {42};
        for (int method : new int[]{0,1}) {
            File archive = fixture(first, second, method, 1), directory = temporary.newFolder();
            File target = new File(directory, "second"); Files.write(target.toPath(), original);
            IOException failure = assertThrows(IOException.class,
                    () -> EggArchiveReader.extractArchiveIntoDirectory(archive, directory, null));
            assertTrue(failure.getMessage().contains("CRC mismatch"));
            assertArrayEquals(first, Files.readAllBytes(new File(directory, "first").toPath()));
            assertArrayEquals(original, Files.readAllBytes(target.toPath()));
            assertEquals(2, directory.list().length);
        }
    }

    @Test public void zeroBlockCrcRetainsUnspecifiedSentinelBehavior() throws Exception {
        byte[] first = first(), second = {9,8,7};
        File archive = fixture(first, second, 0, -1);
        byte[] packed = Files.readAllBytes(archive.toPath());
        int blocks = 0, last = -1;
        for (int i = 0; i + 22 <= packed.length; i++) {
            if ((packed[i] & 255) == 0x13 && (packed[i+1] & 255) == 0x0c
                    && (packed[i+2] & 255) == 0xb5 && (packed[i+3] & 255) == 0x02) {
                blocks++; last = i;
            }
        }
        assertEquals(2, blocks);
        Arrays.fill(packed, last + 14, last + 18, (byte) 0);
        packed[last + 22] ^= 1; second[0] ^= 1;
        Files.write(archive.toPath(), packed);
        File directory = temporary.newFolder();
        assertTrue(EggArchiveReader.extractArchiveIntoDirectory(archive, directory, null));
        assertArrayEquals(first, Files.readAllBytes(new File(directory, "first").toPath()));
        assertArrayEquals(second, Files.readAllBytes(new File(directory, "second").toPath()));
    }

    private byte[] first() {
        byte[] bytes = new byte[65539]; Arrays.fill(bytes, (byte) 15); return bytes;
    }

    private File fixture(byte[] first, byte[] second, int method, int badBlock) throws Exception {
        return Round5Fixtures.egg(temporary.newFolder(), new String[]{"first", "second"},
                new byte[][]{first, second}, true, new int[]{first.length, first.length + second.length}, badBlock, method);
    }
}
