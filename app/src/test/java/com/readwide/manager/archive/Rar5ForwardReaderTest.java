package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Session ownership contracts, not a substitute for producer/codec fixtures. */
public class Rar5ForwardReaderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void ownsListAndPasswordCopiesWithoutMutatingCallerState() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(stored("first.bin", new byte[]{7, 8}));
        char[] supplied = {'p', 'w'};
        File directory = temporary.newFolder();
        Rar5ForwardReader reader = new Rar5ForwardReader(
                entries, supplied, directory, RarVolumeChain.index(entries));
        Field passwordField = Rar5ForwardReader.class.getDeclaredField("password");
        passwordField.setAccessible(true);
        char[] owned = (char[]) passwordField.get(reader);
        try {
            entries.clear();
            supplied[0] = 'x';
            assertArrayEquals(new char[]{'p', 'w'}, owned);
            assertEquals("first.bin", reader.nextEntry().path);
            assertEquals(1, reader.read(new byte[1]));
        } finally { reader.close(); }
        assertArrayEquals(new char[]{0, 0}, owned);
        assertArrayEquals(new char[]{'x', 'w'}, supplied);
        assertEquals(0, directory.list().length);
        assertThrows(IOException.class, reader::nextEntry);
    }

    @Test public void advancesAfterPartialReadAndReleasesEachVerifiedSpool() throws Exception {
        List<RarArchiveReader.RarEntry> entries = Arrays.asList(
                stored("first.bin", new byte[]{7, 8, 9}), stored("second.bin", new byte[]{10, 11}));
        File directory = temporary.newFolder();
        try (Rar5ForwardReader reader = new Rar5ForwardReader(
                entries, null, directory, RarVolumeChain.index(entries))) {
            assertEquals("first.bin", reader.nextEntry().path);
            byte[] one = new byte[1];
            assertEquals(1, reader.read(one));
            assertEquals(7, one[0]);
            assertEquals(1, directory.list().length);
            assertEquals("second.bin", reader.nextEntry().path);
            assertEquals(0, directory.list().length);
            byte[] second = new byte[2];
            assertEquals(2, reader.read(second));
            assertArrayEquals(new byte[]{10, 11}, second);
            assertEquals(-1, reader.read(one));
            assertNull(reader.nextEntry());
            assertNull(reader.nextEntry());
            assertEquals(0, directory.list().length);
        }
    }

    @Test public void emptyReadDoesNotDecodeAndDecodeFailureRetiresSession() throws Exception {
        RarArchiveReader.RarEntry entry = stored("damaged.bin", new byte[]{42});
        // Mutate the payload after metadata creation to force the existing CRC guard.
        Files.write(entry.sourceArchive.toPath(), new byte[]{43});
        List<RarArchiveReader.RarEntry> entries = Collections.singletonList(entry);
        File directory = temporary.newFolder();
        try (Rar5ForwardReader reader = new Rar5ForwardReader(
                entries, null, directory, RarVolumeChain.index(entries))) {
            assertEquals(-1, reader.read(new byte[1]));
            assertEquals("damaged.bin", reader.nextEntry().path);
            assertEquals(0, reader.read(new byte[0]));
            assertEquals(0, directory.list().length);
            assertThrows(IOException.class, () -> reader.read(new byte[1]));
            assertEquals(0, directory.list().length);
            assertThrows(IOException.class, reader::nextEntry);
            assertThrows(IOException.class, () -> reader.drainCurrentEntry(Long.MAX_VALUE));
        }
    }

    private RarArchiveReader.RarEntry stored(String name, byte[] bytes) throws Exception {
        File source = temporary.newFile();
        Files.write(source.toPath(), bytes);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(name, false,
                bytes.length, bytes.length, 0, 5, 0, false, false, false, null, crc.getValue(), 0);
        entry.sourceArchive = source;
        return entry;
    }
}
