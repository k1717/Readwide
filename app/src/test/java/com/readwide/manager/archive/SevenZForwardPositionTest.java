package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Cursor ownership checks on synthetic verified folders, not a throughput benchmark. */
public final class SevenZForwardPositionTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void partialReadsReuseCursorAcrossPlainSolidMembers() throws Exception {
        verifySequentialReads(false, false);
    }

    @Test public void partialReadsReuseCursorAcrossEncryptedSplitSolidMembers() throws Exception {
        verifySequentialReads(true, true);
    }

    private void verifySequentialReads(boolean encrypted, boolean split) throws Exception {
        byte[] raw = Round4Fixtures.data(38);
        try (ArchiveSupport.ForwardArchiveReader reader = open(raw, encrypted, split)) {
            assertEquals("1.bin", reader.nextEntry().path);
            byte[] first = new byte[1];
            assertEquals(1, reader.read(first));
            assertEquals(raw[0], first[0]);
            CountingFile cursor = observeCursor(reader);
            assertArrayEquals(Arrays.copyOfRange(raw, 1, 19), readRemaining(reader));
            assertEquals("Partial reads and EOF need no repeated seek", 0, cursor.seeks);
            assertEquals("2.bin", reader.nextEntry().path);
            assertEquals(0, reader.read(new byte[0]));
            assertEquals(0, cursor.seeks);
            assertArrayEquals(Arrays.copyOfRange(raw, 19, 38), readRemaining(reader));
            assertEquals("A new member seeks once", 1, cursor.seeks);
            assertNull(reader.nextEntry());
        }
    }

    @Test public void unreadPredecessorCanBeSkippedBeforeCreatingFolderSpool() throws Exception {
        byte[] raw = Round4Fixtures.data(38);
        try (ArchiveSupport.ForwardArchiveReader reader = open(raw, true, false)) {
            assertEquals("1.bin", reader.nextEntry().path);
            assertEquals("2.bin", reader.nextEntry().path);
            byte[] first = new byte[1];
            assertEquals(1, reader.read(first));
            assertEquals(raw[19], first[0]);
            CountingFile cursor = observeCursor(reader);
            assertArrayEquals(Arrays.copyOfRange(raw, 20, 38), readRemaining(reader));
            assertEquals(0, cursor.seeks);
        }
    }

    @Test public void drainingPartialMemberRepositionsOnlyWhenNextMemberIsRead() throws Exception {
        byte[] raw = Round4Fixtures.data(38);
        try (ArchiveSupport.ForwardArchiveReader reader = open(raw, false, true)) {
            reader.nextEntry();
            assertEquals(1, reader.read(new byte[1]));
            CountingFile cursor = observeCursor(reader);
            assertTrue(reader.drainCurrentEntry(18));
            assertEquals(-1, reader.read(new byte[1]));
            assertEquals(0, cursor.seeks);
            reader.nextEntry();
            assertArrayEquals(Arrays.copyOfRange(raw, 19, 38), readRemaining(reader));
            assertEquals(1, cursor.seeks);
        }
    }

    @Test public void readingDrainedUnpreparedMemberDoesNotMisplaceNextMember() throws Exception {
        byte[] raw = Round4Fixtures.data(38);
        try (ArchiveSupport.ForwardArchiveReader reader = open(raw, true, true)) {
            reader.nextEntry();
            assertTrue(reader.drainCurrentEntry(19));
            assertEquals(-1, reader.read(new byte[1]));
            CountingFile cursor = observeCursor(reader);
            reader.nextEntry();
            assertArrayEquals(Arrays.copyOfRange(raw, 19, 38), readRemaining(reader));
            assertEquals(1, cursor.seeks);
        }
    }

    @Test public void zeroLengthMemberKeepsExistingVerificationAndPositioning() throws Exception {
        byte[] raw = {42}; // Two substreams: a zero-length first member and one byte.
        try (ArchiveSupport.ForwardArchiveReader reader = open(raw, false, false)) {
            reader.nextEntry();
            assertEquals(-1, reader.read(new byte[1]));
            CountingFile cursor = observeCursor(reader);
            assertEquals(-1, reader.read(new byte[1]));
            assertEquals(1, cursor.seeks);
            reader.nextEntry();
            assertArrayEquals(raw, readRemaining(reader));
            assertEquals(2, cursor.seeks);
        }
    }

    @Test public void changedLengthStillFailsBeforeReturningNextChunk() throws Exception {
        try (ArchiveSupport.ForwardArchiveReader reader = open(Round4Fixtures.data(38), false, false)) {
            reader.nextEntry();
            assertEquals(1, reader.read(new byte[1]));
            CountingFile cursor = observeCursor(reader);
            File spool = (File) field(reader, "spool").get(reader);
            try (RandomAccessFile writer = new RandomAccessFile(spool, "rw")) { writer.setLength(37); }
            byte[] untouched = {99};
            assertThrows(IOException.class, () -> reader.read(untouched));
            assertArrayEquals(new byte[]{99}, untouched);
            assertEquals(0, cursor.seeks);
            assertFalse(spool.exists());
            assertThrows(IOException.class, reader::nextEntry);
        }
    }

    private ArchiveSupport.ForwardArchiveReader open(byte[] raw, boolean encrypted, boolean split) throws Exception {
        File archive = Round4Fixtures.sevenZ(temporary.newFolder(), raw, 4, encrypted, false, false, true);
        if (split) {
            byte[] bytes = Files.readAllBytes(archive.toPath());
            File directory = temporary.newFolder();
            Files.write(new File(directory, "book.7z.001").toPath(), Arrays.copyOfRange(bytes, 0, 35));
            archive = new File(directory, "book.7z.002");
            Files.write(archive.toPath(), Arrays.copyOfRange(bytes, 35, bytes.length));
        }
        ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(
                archive, encrypted ? Round4Fixtures.PASSWORD : null, temporary.newFolder());
        assertNotNull(reader);
        return reader;
    }

    private static byte[] readRemaining(ArchiveSupport.ForwardArchiveReader reader) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] bytes = new byte[3];
        int count;
        while ((count = reader.read(bytes)) != -1) {
            assertTrue("No zero-progress read", count > 0);
            output.write(bytes, 0, count);
        }
        assertEquals(-1, reader.read(bytes));
        return output.toByteArray();
    }

    private static CountingFile observeCursor(ArchiveSupport.ForwardArchiveReader reader) throws Exception {
        Field input = field(reader, "input");
        RandomAccessFile previous = (RandomAccessFile) input.get(reader);
        long position = previous.getFilePointer();
        previous.close();
        CountingFile replacement = new CountingFile((File) field(reader, "spool").get(reader), position);
        input.set(reader, replacement);
        return replacement;
    }

    private static Field field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class CountingFile extends RandomAccessFile {
        int seeks;
        CountingFile(File file, long position) throws IOException {
            super(file, "r");
            super.seek(position);
        }
        @Override public void seek(long position) throws IOException {
            seeks++;
            super.seek(position);
        }
    }
}
