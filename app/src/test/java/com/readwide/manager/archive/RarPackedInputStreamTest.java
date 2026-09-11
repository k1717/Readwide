package com.readwide.manager.archive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.CRC32;
import static org.junit.Assert.*;

public class RarPackedInputStreamTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void readsOnlySegmentBytesAcrossVolumes() throws Exception {
        File first = temp.newFile();
        File second = temp.newFile();
        Files.write(first.toPath(), new byte[] {9, 1, 2, 9});
        Files.write(second.toPath(), new byte[] {9, 3, 4, 9});
        try (RarPackedInputStream input = new RarPackedInputStream(Arrays.asList(
                new RarCryptoStreams.EncryptedSegment(first, 1, 2),
                new RarCryptoStreams.EncryptedSegment(second, 1, 2)), null)) {
            assertEquals(1, input.read()); assertEquals(2, input.read());
            assertEquals(3, input.read()); assertEquals(4, input.read());
            assertEquals(-1, input.read());
            assertEquals(0, input.read(new byte[0]));
        }
    }

    @Test public void cancellationStopsBeforeReadingPayload() throws Exception {
        com.readwide.manager.util.FileOperationProgress progress =
                new com.readwide.manager.util.FileOperationProgress("RAR", null);
        progress.cancel();
        try (RarPackedInputStream input = new RarPackedInputStream(Arrays.asList(
                new RarCryptoStreams.EncryptedSegment(temp.newFile(), 0, 0)), progress)) {
            try { input.read(); fail("Cancelled read must fail"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("cancelled")); }
        }
    }

    @Test public void supportsLongOffsetsAndSizesWithoutAllocatingWholePayload() throws Exception {
        org.junit.Assume.assumeTrue("Sparse-file allocation depends on the test filesystem",
                Boolean.getBoolean("readwide.largeArchiveTests"));
        File sparse = temp.newFile();
        long offset = (long) Integer.MAX_VALUE + 17;
        try (RandomAccessFile file = new RandomAccessFile(sparse, "rw")) {
            file.seek(offset);
            file.write(42);
            file.setLength(offset + 65L * 1024 * 1024);
        }
        try (RarPackedInputStream input = new RarPackedInputStream(Arrays.asList(
                new RarCryptoStreams.EncryptedSegment(sparse, offset, 65L * 1024 * 1024)), null)) {
            assertEquals(42, input.read());
        }
    }

    @Test public void rejectsTruncatedPhysicalSegmentAndReadsAfterClose() throws Exception {
        File file = temp.newFile();
        RarPackedInputStream input = new RarPackedInputStream(Arrays.asList(
                new RarCryptoStreams.EncryptedSegment(file, 0, 1)), null);
        try {
            input.read(); fail("Missing payload must fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("volume"));
        } finally { input.close(); }
        try { input.read(); fail("Closed stream must fail"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("closed")); }
    }

    @Test public void intermediateChecksDoNotTreatFinalFileCrcAsSegmentCrc() throws Exception {
        for (int version : new int[]{4,5}) {
            byte[] a = {1,2,3}, b = {4,5};
            RarArchiveReader.RarEntry first = entry(a,version,false,true,crc(a),null);
            RarArchiveReader.RarEntry last = entry(b,version,true,false,crc(new byte[]{1,2,3,4,5}),null);
            try (RarPackedInputStream in = stream(first,last)) {
                byte[] buffer = new byte[9];
                assertEquals(3,in.read(buffer));
                assertEquals(2,in.read(buffer));
                assertEquals(-1,in.read(buffer));
            }
        }
    }

    @Test public void checksumFailsOnBoundaryReadAndCannotResumeAtNextVolume() throws Exception {
        byte[] a = {1,2,3};
        RarArchiveReader.RarEntry first = entry(a,5,false,true,crc(a)^1,null);
        RarArchiveReader.RarEntry last = entry(new byte[]{4},5,true,false,0,null);
        try (RarPackedInputStream in = stream(first,last)) {
            assertEquals(1,in.read());
            IOException failure;
            try { in.read(new byte[2]); fail("Boundary read must check CRC immediately"); return; }
            catch (IOException expected) { failure = expected; assertTrue(expected.getMessage().contains("packed-volume")); }
            try { in.read(); fail("A failed stream must not resume"); }
            catch (IOException expected) { assertSame(failure,expected); }
        }
    }

    @Test public void blakeIsCheckedEvenWithMatchingPackedCrc() throws Exception {
        byte[] a = RarBlake2spTest.sequence(65);
        byte[] digest = RarBlake2spTest.hex("fff24d3cc729d395daf978b0157306cb495797e6c8dca1731d2f6f81b849baae");
        RarArchiveReader.RarEntry last = entry(new byte[]{8},5,true,false,0,null);
        try (RarPackedInputStream in = stream(entry(a,5,false,true,crc(a),digest),last)) {
            assertEquals(a.length,in.read(new byte[a.length]));
        }
        digest[31]^=1;
        try (RarPackedInputStream in = stream(entry(a,5,false,true,crc(a),digest),last)) {
            try { in.read(new byte[a.length]); fail("Valid CRC must not mask a bad packed BLAKE2sp"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("packed-volume")); }
        }
    }

    @Test public void zeroLengthIntermediateMemberIsChecked() throws Exception {
        RarArchiveReader.RarEntry first = entry(new byte[0],5,false,true,1,null);
        RarArchiveReader.RarEntry last = entry(new byte[]{8},5,true,false,0,null);
        try (RarPackedInputStream in = stream(first,last)) {
            try { in.read(); fail("Empty members still have a checksum"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("packed-volume")); }
        }
    }

    @Test public void partialCloseDoesNotDrainOrPretendToVerifyTheRest() throws Exception {
        RarArchiveReader.RarEntry first = entry(new byte[]{1,2,3},5,false,true,0,null);
        try (RarPackedInputStream in = stream(first,entry(new byte[]{4},5,true,false,0,null))) {
            assertEquals(1,in.read()); // Not at the boundary; cancellation may close early.
        }
    }

    @Test public void storedExtractionRejectsBadIntermediateCrcDespiteGoodFinalCrc() throws Exception {
        RarArchiveReader.RarEntry first = entry(new byte[]{1,2,3},5,false,true,0,null);
        RarArchiveReader.RarEntry last = entry(new byte[]{4,5},5,true,false,crc(new byte[]{1,2,3,4,5}),null);
        File out = new File(temp.getRoot(),"rejected.bin");
        try { RarSplitStoredExtractor.extract(first,out,null,Arrays.asList(first,last),null); fail(); }
        catch (IOException expected) { assertFalse(out.exists()); }
    }

    @Test public void encryptedIntermediateChecksAreNotMistakenForPlainPackedChecks() throws Exception {
        RarArchiveReader.EncryptionInfo encryption = new RarArchiveReader.EncryptionInfo(
                0,2,1,new byte[16],new byte[16],new byte[0]);
        RarArchiveReader.RarEntry encrypted = new RarArchiveReader.RarEntry("data.bin",false,
                5,16,0,5,0,false,false,true,encryption,123,0);
        encrypted.sourceArchive = temp.newFile();
        Files.write(encrypted.sourceArchive.toPath(), new byte[16]);
        assertSame(encrypted, RarVolumeChain.payloadSegments(Arrays.asList(encrypted)).get(0).packedCheck);
        try (RarPackedInputStream in = stream(encrypted)) {
            try { in.read(new byte[16]); fail("Keyed packed checks must not use a plain CRC"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("key is required")); }
        }
    }

    @Test public void legacyAbsentPackedCrcDoesNotDisableRar5Checks() throws Exception {
        RarArchiveReader.RarEntry legacy = entry(new byte[]{1},4,false,true,0xffffffffL,null);
        try (RarPackedInputStream in = stream(legacy)) { assertEquals(1,in.read()); }
        RarArchiveReader.RarEntry modern = entry(new byte[]{1},5,false,true,0xffffffffL,null);
        try (RarPackedInputStream in = stream(modern)) {
            try { in.read(); fail("RAR5 all-one CRC is not an absent-check sentinel"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("packed-volume")); }
        }
    }

    private RarPackedInputStream stream(RarArchiveReader.RarEntry... entries) throws IOException {
        return new RarPackedInputStream(RarVolumeChain.payloadSegments(Arrays.asList(entries)),null);
    }

    private RarArchiveReader.RarEntry entry(byte[] packed,int version,boolean before,boolean after,
            long crc,byte[] hash) throws Exception {
        File file = temp.newFile(); Files.write(file.toPath(),packed);
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry("data.bin",false,
                5,packed.length,0,version,0,false,before,after,null,crc,0,0,hash);
        entry.sourceArchive=file;
        return entry;
    }

    private static long crc(byte[] bytes) { CRC32 crc=new CRC32();crc.update(bytes);return crc.getValue(); }
}
