package com.readwide.manager.archive;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Source-level regressions; no native backend is required by this reader. */
public final class SevenZForwardSuffixSpoolTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void skippedPrefixIsCheckedButNotSpooled() throws Exception {
        checkSuffix(false, false);
    }

    @Test public void skippedPrefixFolderCrcStillRejectsPublication() throws Exception {
        checkSuffix(true, false);
    }

    @Test public void startingAtFirstEntryKeepsBothSlices() throws Exception {
        checkSuffix(false, true);
    }

    private void checkSuffix(boolean badCrc, boolean readFirst) throws Exception {
        byte[] raw = Round4Fixtures.data(20006);
        File archive = Round4Fixtures.sevenZ(temporary.newFolder(), raw, 4, false, false, badCrc, true);
        File spool = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader =
                     SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, spool)) {
            assertNotNull(reader);
            assertEquals("1.bin", reader.nextEntry().path);
            if (readFirst) assertArrayEquals(Arrays.copyOfRange(raw, 0, raw.length / 2), bytes(reader));
            else assertEquals(0, spool.list().length);
            assertEquals("2.bin", reader.nextEntry().path);
            if (badCrc) {
                byte[] unchanged = {42};
                assertThrows(IOException.class, () -> reader.read(unchanged));
                assertArrayEquals(new byte[]{42}, unchanged);
                assertThrows(IOException.class, reader::nextEntry);
            } else {
                assertArrayEquals(Arrays.copyOfRange(raw, raw.length / 2, raw.length), bytes(reader));
                assertEquals(1, spool.list().length);
                assertEquals(readFirst ? raw.length : raw.length - raw.length / 2, spool.listFiles()[0].length());
                assertNull(reader.nextEntry());
            }
        }
        assertEquals(0, spool.list().length);
    }

    @Test public void reservedAndObsoleteCoderFlagsFailBeforeSpooling() throws Exception {
        for (int flag : new int[]{0x40, 0x80}) {
            File archive = Round4Fixtures.sevenZ(temporary.newFolder(), new byte[4], 4, false, false, false, false);
            byte[] raw = Files.readAllBytes(archive.toPath());
            ByteBuffer fields = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int start = Math.toIntExact(32 + fields.getLong(12));
            int coder = -1;
            for (int i = start; i + 4 < raw.length; i++) {
                if (raw[i] == 0x23 && raw[i + 1] == 2 && raw[i + 2] == 3 && raw[i + 3] == 4) { coder = i; break; }
            }
            assertTrue(coder >= start);
            raw[coder] |= flag;
            fields.putInt(28, (int) Round4Fixtures.crc(Arrays.copyOfRange(raw, start, raw.length)));
            fields.putInt(8, (int) Round4Fixtures.crc(Arrays.copyOfRange(raw, 12, 32)));
            Files.write(archive.toPath(), raw);
            File spool = temporary.newFolder();
            assertThrows(IOException.class,
                    () -> SevenZBcj2ArchiveReader.listEntries(archive, null));
            assertNull(SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, spool));
            assertEquals(0, spool.list().length);
        }
    }

    private static byte[] bytes(ArchiveSupport.ForwardArchiveReader reader) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[37];
        for (int count; (count = reader.read(buffer)) != -1;) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
