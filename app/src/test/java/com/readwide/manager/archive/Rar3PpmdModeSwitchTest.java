package com.readwide.manager.archive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Synthetic range-coded controls and LZ blocks, not producer interoperability evidence. */
public class Rar3PpmdModeSwitchTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final char[] PASSWORD = "mixed-test".toCharArray();
    private static final byte[] SALT = {1,2,3,4,5,6,7,8};

    @Test public void encryptedAndSplitMixedRunsKeepTablesModelAndIndependentResets() throws Exception {
        for (boolean encrypted : new boolean[]{false, true}) for (boolean split : new boolean[]{false, true}) {
            List<RarArchiveReader.RarEntry> entries = new java.util.ArrayList<>();
            append(entries, "primer.bin", switchTo(lz(new int[]{'A','B',256,271}, new int[]{1}, "00011000")),
                    new byte[]{'A','B'}, false, encrypted, split);
            append(entries, "lz.bin", new byte[]{(byte)0xd2,0}, new byte[]{'A','B','A'}, true, encrypted, split);
            append(entries, "ppmd.bin", concat(new byte[]{(byte)0x80,0,0,0,0},
                    lz(new int[]{256,271}, new int[]{0}, "0100001")), new byte[]{'A','A','A'}, true, encrypted, split);
            append(entries, "fresh.bin", switchTo(lz(new int[]{'Z',256}, new int[0], "000101")),
                    new byte[]{'Z'}, false, encrypted, split);
            RarArchiveReader.RarEntry targetEntry = entries.get(split ? 4 : 2);
            File target = temp.newFile();
            assertTrue(Rar3PpmdSolidArchiveExtractor.tryExtractSolidPpmdEntry(
                    targetEntry, entries, target, PASSWORD, null));
            assertArrayEquals(new byte[]{'A','A','A'}, Files.readAllBytes(target.toPath()));
            File bulk = temp.newFolder();
            assertTrue(Rar3PpmdSolidArchiveExtractor.tryExtractArchivePpmdSolid(entries, bulk, PASSWORD, null, null));
            assertArrayEquals(new byte[]{'A','B'}, Files.readAllBytes(new File(bulk,"primer.bin").toPath()));
            assertArrayEquals(new byte[]{'A','B','A'}, Files.readAllBytes(new File(bulk,"lz.bin").toPath()));
            assertArrayEquals(new byte[]{'A','A','A'}, Files.readAllBytes(new File(bulk,"ppmd.bin").toPath()));
            assertArrayEquals(new byte[]{'Z'}, Files.readAllBytes(new File(bulk,"fresh.bin").toPath()));
            File spool = temp.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                    entries, PASSWORD, spool, false)) {
                assertTrue(reader instanceof Rar3PpmdForwardReader);
                reader.nextEntry(); reader.nextEntry(); reader.nextEntry(); // Verify both unread primers.
                assertArrayEquals(new byte[]{'A','A','A'}, read(reader));
                reader.nextEntry(); assertArrayEquals(new byte[]{'Z'}, read(reader));
                assertNull(reader.nextEntry());
            }
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void encryptedSplitVmOutputUsesRawDictionaryForItsSolidSuccessor() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new java.util.ArrayList<>();
        append(entries, "vm.bin", switchTo(Rar3UnpackerTest.lzVmDefinitionForPpmdSwitch()),
                new byte[]{'B',0}, false, true, true);
        append(entries, "raw.bin", lz(new int[]{256,271}, new int[]{1}, "0100001"),
                new byte[]{'A','B','A'}, true, true, true);
        File target = temp.newFile();
        // The second member starts LZ, so the broader logical plan must preserve
        // the same raw dictionary; filtered B,0 must not replace its original AB.
        assertTrue(Rar3LogicalSolidArchiveExtractor.tryExtractEntry(entries.get(2), entries, target, PASSWORD, null));
        assertArrayEquals(new byte[]{'A','B','A'}, Files.readAllBytes(target.toPath()));
    }

    @Test public void encryptedSplitSourceFailureAfterAdmissionDoesNotPublishOrResume() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new java.util.ArrayList<>();
        append(entries, "one.bin", switchTo(lz(new int[]{'A',256}, new int[0], "000101")),
                new byte[]{'A'}, false, true, true);
        File target = temp.newFile(); Files.write(target.toPath(), new byte[]{42});
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                entries, PASSWORD, spool, false)) {
            assertNotNull(reader); reader.nextEntry();
            File tail = entries.get(1).sourceArchive;
            byte[] original = Files.readAllBytes(tail.toPath());
            Files.write(tail.toPath(), Arrays.copyOf(original, original.length - 1));
            byte[] untouched = {42};
            assertThrows(IOException.class, () -> reader.read(untouched));
            assertArrayEquals(new byte[]{42}, untouched);
            assertThrows(IOException.class, () -> Rar3PpmdSolidArchiveExtractor.tryExtractSolidPpmdEntry(
                    entries.get(0), entries, target, PASSWORD, null));
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(target.toPath()));
            Files.write(tail.toPath(), original);
            assertThrows(IOException.class, reader::nextEntry);
        }
        assertEquals(0, spool.list().length);
    }


    @Test public void selectedBulkAndForwardUseInternalLzWithoutReplayingOutput() throws Exception {
        byte[] packed = switchTo(lz(new int[]{'A',256}, new int[0], "000101"));
        RarArchiveReader.RarEntry entry = entry("one.bin", packed, new byte[]{'A'}, false);
        List<RarArchiveReader.RarEntry> entries = Collections.singletonList(entry);
        File target = temp.newFile();
        assertTrue(Rar3PpmdSolidArchiveExtractor.tryExtractSolidPpmdEntry(entry, entries, target, null));
        assertArrayEquals(new byte[]{'A'}, Files.readAllBytes(target.toPath()));
        File bulk = temp.newFolder();
        assertTrue(Rar3PpmdSolidArchiveExtractor.tryExtractArchivePpmdSolid(entries, bulk, null, null, null));
        assertArrayEquals(new byte[]{'A'}, Files.readAllBytes(new File(bulk, "one.bin").toPath()));
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                entries, null, spool, false)) {
            assertTrue(reader instanceof Rar3PpmdForwardReader);
            assertEquals("one.bin", reader.nextEntry().path);
            assertArrayEquals(new byte[]{'A'}, read(reader)); assertNull(reader.nextEntry());
        }
        assertEquals(0, spool.list().length);
    }

    @Test public void promotedSessionRetainsLzTablesAndRawHistoryForSkippedSolidPrimer() throws Exception {
        byte[] first = switchTo(lz(new int[]{'A','B',256,271}, new int[]{1}, "00011000"));
        // Table-less distance-2 match, EOF requiring a new table. The high first
        // bit previously sent this member to the PPMd-only selector as well.
        byte[] second = {(byte)0xd2,0}; // Include padding required by the plain first-byte probe.
        List<RarArchiveReader.RarEntry> entries = Arrays.asList(
                entry("first.bin", first, new byte[]{'A','B'}, false),
                entry("second.bin", second, new byte[]{'A','B','A'}, true),
                entry("fresh.bin", switchTo(lz(new int[]{'Z',256}, new int[0], "000101")), new byte[]{'Z'}, false));
        File target = temp.newFile();
        assertTrue(Rar3PpmdSolidArchiveExtractor.tryExtractSolidPpmdEntry(entries.get(1), entries, target, null));
        assertArrayEquals(new byte[]{'A','B','A'}, Files.readAllBytes(target.toPath()));
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                entries, null, spool, false)) {
            assertTrue(reader instanceof Rar3PpmdForwardReader);
            reader.nextEntry(); reader.nextEntry(); // Decode/verify the unread primer to discard.
            assertArrayEquals(new byte[]{'A','B','A'}, read(reader));
            reader.nextEntry(); assertArrayEquals(new byte[]{'Z'}, read(reader));
            assertNull(reader.nextEntry());
        }
        assertEquals(0, spool.list().length);
    }

    @Test public void promotedSessionCanReturnToRetainedPpmdModel() throws Exception {
        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        byte[] first = switchTo(lz(new int[]{'A',256}, new int[0], "000101"));
        assertArrayEquals(new byte[]{'A'}, decoder.decodeEntry(first, 1).data);
        // Same zero escape and model: range initialization only, then two zero
        // symbols select LZ again. No reset/memory/escape bytes are present.
        byte[] second = concat(new byte[]{(byte)0x80,0,0,0,0},
                lz(new int[]{256,271}, new int[]{0}, "0100001"));
        assertArrayEquals(new byte[]{'A','A','A'}, decoder.decodeEntry(second, 3).data);
        assertEquals(4, decoder.windowSize());
    }

    @Test public void vmQueueSurvivesPromotionAndFilteredBytesDoNotReplaceRawHistory() throws Exception {
        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        Rar3VmFilter.ProgramState programs = (Rar3VmFilter.ProgramState) field(decoder, "vmPrograms");
        Rar3PpmdFilterOutput.DeferredFilters pending =
                (Rar3PpmdFilterOutput.DeferredFilters) field(decoder, "deferredFilters");
        // Seed a symbol-side descriptor; the LZ block reuses its program and
        // length at the same raw start. This is not a range-coded VM record.
        try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(new ByteArrayOutputStream(), 0,
                programs, Rar3PpmdFilterOutput.MAX_FILTER_BYTES, pending)) {
            out.acceptParsed(programs.parse(0xa0, Rar3VirtualMachineTest.slicingRecord(2,2,1),
                    0, Rar3PpmdFilterOutput.MAX_FILTER_BYTES));
            out.finish(true);
        }
        assertArrayEquals(new byte[]{0,0}, decoder.decodeEntry(
                switchTo(Rar3UnpackerTest.lzVmReuseForPpmdSwitch()), 2).data);
        assertArrayEquals(new byte[]{'A','B','A'}, decoder.decodeEntry(
                lz(new int[]{256,271}, new int[]{1}, "0100001"), 3).data);
        decoder.requireCompleteFilterRun();
    }

    @Test public void corruptMixedSuffixPreservesTargetAndNeverPublishesForwardBytes() throws Exception {
        byte[] valid = switchTo(lz(new int[]{'A',256}, new int[0], "000101"));
        for (boolean truncate : new boolean[]{false, true}) {
            byte[] packed = truncate ? Arrays.copyOf(valid, valid.length - 1) : valid;
            RarArchiveReader.RarEntry entry = entry("bad.bin", packed,
                    new byte[]{(byte)(truncate ? 'A' : 'B')}, false);
            List<RarArchiveReader.RarEntry> entries = Collections.singletonList(entry);
            File target = temp.newFile(); Files.write(target.toPath(), new byte[]{42});
            assertThrows(IOException.class, () -> Rar3PpmdSolidArchiveExtractor.tryExtractSolidPpmdEntry(
                    entry, entries, target, null));
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(target.toPath()));
            File spool = temp.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                    entries, null, spool, false)) {
                assertNotNull(reader); reader.nextEntry(); byte[] untouched = {42};
                assertThrows(IOException.class, () -> reader.read(untouched));
                assertArrayEquals(new byte[]{42}, untouched);
                assertThrows(IOException.class, reader::nextEntry);
            }
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void sameFileVmOffsetsKeepAlreadyDecodedPrefix() throws Exception {
        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        decoder.decodeEntry(switchTo(Rar3UnpackerTest.emptyLzFileBoundary()),0);
        Rar3VmFilter.ProgramState programs = (Rar3VmFilter.ProgramState) field(decoder,"vmPrograms");
        Rar3PpmdSolidStreamDecoder.HistoryWindow history =
                (Rar3PpmdSolidStreamDecoder.HistoryWindow) field(decoder,"window");
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        // Directly seed the seam's one-byte prefix; do not claim this is a
        // producer stream containing PPMd literals before its mode switch.
        try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(target,3,programs)) {
            history.literal('X',out);
            java.lang.reflect.Method resume = decoder.getClass().getDeclaredMethod("continueMixed",
                    java.io.InputStream.class,Rar3PpmdFilterOutput.class,int.class);
            resume.setAccessible(true);
            resume.invoke(decoder,new ByteArrayInputStream(Rar3UnpackerTest.lzVmDefinitionForPpmdSwitch()),out,-1);
            out.finish(true);
        }
        assertArrayEquals(new byte[]{'X','B',0},target.toByteArray());
        assertEquals(3,decoder.windowSize());
    }

    @Test public void promotedOutputFailureRetiresStateAndDoesNotCloseCallerStreams() throws Exception {
        byte[] packed = switchTo(lz(new int[]{'A',256}, new int[0], "000101"));
        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        IOException failure = new IOException("output failed");
        ByteArrayInputStream input = new ByteArrayInputStream(packed) {
            @Override public void close() { fail("Caller owns input"); }
        };
        OutputStream output = new OutputStream() {
            @Override public void write(int value) throws IOException { throw failure; }
            @Override public void close() { fail("Caller owns output"); }
        };
        assertSame(failure, assertThrows(IOException.class, () -> decoder.decodeEntry(input,1,output)));
        assertNull(field(decoder, "mixed"));
        assertThrows(IOException.class, () -> decoder.decodeEntry(new ByteArrayInputStream(packed),1,
                new ByteArrayOutputStream()));
    }

    private RarArchiveReader.RarEntry entry(String name, byte[] packed, byte[] raw, boolean solid) throws IOException {
        File payload = temp.newFile(); Files.write(payload.toPath(), packed);
        CRC32 crc = new CRC32(); crc.update(raw);
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(name, false, raw.length,
                packed.length, 0, 4, 0x33, solid, false, false, null, crc.getValue(), 0);
        entry.sourceArchive = payload; return entry;
    }

    private void append(List<RarArchiveReader.RarEntry> entries, String name, byte[] packed,
            byte[] raw, boolean solid, boolean encrypted, boolean split) throws Exception {
        RarArchiveReader.EncryptionInfo encryption = null;
        if (encrypted) {
            Rar3Crypto.Parameters parameters = Rar3Crypto.deriveParameters(PASSWORD, SALT);
            try {
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding");
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(parameters.key,"AES"),
                        new javax.crypto.spec.IvParameterSpec(parameters.iv));
                packed = cipher.doFinal(Arrays.copyOf(packed, (packed.length + 15) / 16 * 16));
            } finally {
                Arrays.fill(parameters.key, (byte)0); Arrays.fill(parameters.iv, (byte)0);
            }
            encryption = RarArchiveReader.EncryptionInfo.rar4Unsupported(SALT.clone());
        }
        CRC32 crc = new CRC32(); crc.update(raw);
        if (split) {
            // Plain: cross the range-coded control/table boundary. AES: split
            // inside a CBC block, not at a cipher or decoder reset boundary.
            int cut = Math.min(encrypted ? 7 : 8, packed.length - 1);
            byte[] first = Arrays.copyOf(packed, cut);
            CRC32 packedCrc = new CRC32(); packedCrc.update(first);
            entries.add(part(name, first, raw.length, solid, false, true, encryption, packedCrc.getValue()));
            entries.add(part(name, Arrays.copyOfRange(packed,cut,packed.length), raw.length,
                    solid, true, false, encryption, crc.getValue()));
        } else entries.add(part(name, packed, raw.length, solid, false, false, encryption, crc.getValue()));
    }

    private RarArchiveReader.RarEntry part(String name, byte[] packed, int unpacked, boolean solid,
            boolean before, boolean after, RarArchiveReader.EncryptionInfo encryption, long crc) throws IOException {
        File source = temp.newFile(); Files.write(source.toPath(), packed);
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(name, false, unpacked,
                packed.length, 0, 4, 0x33, solid, before, after, encryption, crc, 0);
        entry.sourceArchive = source; return entry;
    }

    private static byte[] switchTo(byte[] lz) throws IOException {
        // Order 2 / 1 MiB / escape 0; four zero range bytes and one normalized
        // byte encode two zero symbols (escape + table switch), with no output.
        return concat(new byte[]{(byte)0xe1,0,0,0,0,0,0,0}, lz);
    }
    private static byte[] concat(byte[] a, byte[] b) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(a); out.write(b); return out.toByteArray();
    }
    private static byte[] lz(int[] main, int[] distance, String actions) {
        return Rar3UnpackerTest.lzBlockForPpmdSwitch(main, distance, actions);
    }
    private static Object field(Object object, String name) throws Exception {
        java.lang.reflect.Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static byte[] read(ArchiveSupport.ForwardArchiveReader reader) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[3];
        for (int count; (count = reader.read(buffer)) >= 0;) out.write(buffer,0,count);
        return out.toByteArray();
    }
}
