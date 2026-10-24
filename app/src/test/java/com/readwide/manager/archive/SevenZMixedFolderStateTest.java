package com.readwide.manager.archive;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Synthetic container/state regressions, not encoder interoperability evidence. */
public class SevenZMixedFolderStateTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void solidPpmdSlicesAndIndependentLzma2PpmdFoldersStaySeparate() throws Exception {
        for (boolean skipFirst : new boolean[]{false, true}) {
            File spool = temporary.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(
                    fixture(false), null, spool)) {
                assertNotNull(reader);
                assertEquals("first.bin", reader.nextEntry().path);
                if (!skipFirst) assertArrayEquals(new byte[]{0}, read(reader));
                assertEquals("second.bin", reader.nextEntry().path);
                assertArrayEquals(new byte[]{0}, read(reader));
                assertEquals(1, spool.list().length);
                assertEquals(skipFirst ? 1 : 2, spool.listFiles()[0].length());
                assertEquals("lzma2.bin", reader.nextEntry().path);
                assertArrayEquals(new byte[]{42}, read(reader));
                assertEquals(1, spool.list().length);
                assertEquals("reset-ppmd.bin", reader.nextEntry().path);
                assertArrayEquals(new byte[]{0}, read(reader));
                assertNull(reader.nextEntry()); assertNull(reader.nextEntry());
                assertEquals(-1, reader.read(new byte[1]));
            }
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void badSolidSuffixRetiresBeforePublishingPrefixOrReusingNextFolder() throws Exception {
        File spool = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(
                fixture(true), null, spool)) {
            assertNotNull(reader); reader.nextEntry();
            byte[] untouched = {99};
            assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class, () -> reader.read(untouched));
            assertArrayEquals(new byte[]{99}, untouched);
            assertThrows(IOException.class, reader::nextEntry);
            assertEquals(0, spool.list().length);
        }
        // A fresh reader in the same spool directory cannot inherit the failed model.
        try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(
                fixture(false), null, spool)) {
            reader.nextEntry(); assertArrayEquals(new byte[]{0}, read(reader));
            reader.nextEntry(); reader.nextEntry(); assertArrayEquals(new byte[]{42}, read(reader));
        }
        assertEquals(0, spool.list().length);
    }

    private File fixture(boolean badSecondCrc) throws IOException {
        // Six zero bytes initialize a valid PPMd range stream producing zero-valued symbols.
        // LZMA2 uses a dictionary-reset uncompressed chunk containing one byte.
        byte[][] packed = {new byte[6], {1,0,0,42,0}, new byte[6]};
        byte[][] raw = {{0,0}, {42}, {0}};
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(new byte[]{1,4,6,0,3,9}); // Header/MainStreams/PackInfo; position 0, three streams.
        for (byte[] bytes : packed) { Round4Fixtures.number(header, bytes.length); body.write(bytes); }
        header.write(new byte[]{10,1}); // All pack CRCs defined.
        for (byte[] bytes : packed) Round4Fixtures.le(header, Round4Fixtures.crc(bytes), 4);
        header.write(new byte[]{0,7,11,3,0}); // End PackInfo, UnpackInfo, three inline folders.
        header.write(new byte[]{1,0x23,3,4,1,5,6,0,0,1,0}); // PPMd order 6, 64 KiB.
        header.write(new byte[]{1,0x21,0x21,1,0}); // LZMA2, 4 KiB dictionary.
        header.write(new byte[]{1,0x23,3,4,1,5,6,0,0,1,0}); // Independent PPMd model.
        header.write(new byte[]{12,2,1,1,10,1}); // Folder output lengths and CRCs.
        for (byte[] bytes : raw) Round4Fixtures.le(header, Round4Fixtures.crc(bytes), 4);
        header.write(new byte[]{0,8,13,2,1,1,9,1,10,1}); // Substreams: 2/1/1, first slice length 1.
        long zeroCrc = Round4Fixtures.crc(new byte[]{0});
        Round4Fixtures.le(header, zeroCrc, 4);
        Round4Fixtures.le(header, badSecondCrc ? zeroCrc ^ 1L : zeroCrc, 4);
        header.write(new byte[]{0,0,5,4,17}); // End substreams/streams; four files, inline names.
        byte[] names = "first.bin\0second.bin\0lzma2.bin\0reset-ppmd.bin\0".getBytes(StandardCharsets.UTF_16LE);
        Round4Fixtures.number(header, names.length + 1); header.write(0); header.write(names);
        header.write(new byte[]{0,0});
        return Round5Fixtures.wrap(temporary.newFolder(), body.toByteArray(), header.toByteArray());
    }

    private static byte[] read(ArchiveSupport.ForwardArchiveReader reader) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[1];
        for (int count; (count = reader.read(buffer)) >= 0;) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
