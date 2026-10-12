package com.readwide.manager.archive;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** First-party stream composition tests, not a broad RAR encoder interoperability claim. */
public class Rar3IndependentPayloadExtractorTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] RAW = {'A','B'};
    private static final byte[] OLD = {42,43};
    private static final char[] PASSWORD = {'t','e','s','t'};
    private static final byte[] SALT = {1,2,3,4,5,6,7,8};

    @Test public void independentLzDecodesAesSplitAndAesSplitWithoutNativeRewrite() throws Exception {
        for (boolean encrypted : new boolean[]{false,true}) {
            for (boolean split : new boolean[]{false,true}) {
                if (!encrypted && !split) continue;
                List<RarArchiveReader.RarEntry> entries = fixture(encrypted, split);
                File out = temp.newFile();
                char[] password = PASSWORD.clone();
                assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractSingleEntryLimitedFallback(
                        entries.get(0), entries, out, password, null));
                assertArrayEquals(RAW, Files.readAllBytes(out.toPath()));
                assertArrayEquals(PASSWORD, password);
            }
        }
    }

    @Test public void bulkSpecialCaseRoutingReachesFirstPartySplitDecoder() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(false, true);
        File directory = temp.newFolder();
        assertTrue(Rar3Or4SpecialCaseExtractor.tryExtractArchiveWithSpecialCases(
                entries, directory, null, null, null));
        assertArrayEquals(RAW, Files.readAllBytes(new File(directory,"image.bin").toPath()));
    }

    @Test public void missingPasswordAndContinuationCannotModifyDestination() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(true, true);
        File out = original();
        assertThrows(ArchiveSupport.PasswordRequiredException.class, () -> extract(entries, out, null));
        assertArrayEquals(OLD, Files.readAllBytes(out.toPath()));
        assertFalse(Rar3IndependentPayloadExtractor.tryExtract(entries.get(1), entries, out, PASSWORD, null));
        assertArrayEquals(OLD, Files.readAllBytes(out.toPath()));
    }

    @Test public void wrongPasswordDoesNotCommitOutput() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(true, false);
        File out = original();
        try { assertFalse(extract(entries, out, new char[]{'x'})); }
        catch (IOException expected) { }
        assertArrayEquals(OLD, Files.readAllBytes(out.toPath()));
    }

    @Test public void badFinalCrcRestoresExistingOutput() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(false, true);
        RarArchiveReader.RarEntry last = entries.get(1);
        entries.set(1, copy(last, last.dataCrc ^ 1, last.packedSize, false, last.encryption));
        File out = original();
        assertThrows(IOException.class, () -> extract(entries, out, null));
        assertArrayEquals(OLD, Files.readAllBytes(out.toPath()));
    }

    @Test public void packedTailIsCheckedAfterDecoderReachesItsOutputSize() throws Exception {
        byte[] firstBytes = Arrays.copyOf(Rar3FirstPartyArchiveExtractorTest.independentLzFixture(), 70000);
        for (boolean badMiddleCrc : new boolean[]{false,true}) {
            List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
            entries.add(part(firstBytes, false, true, crc(firstBytes), null));
            if (badMiddleCrc) {
                byte[] middle = new byte[1500];
                entries.add(part(middle, true, true, crc(middle) ^ 1, null));
                entries.add(part(new byte[4], true, false, crc(RAW), null));
            } else {
                RarArchiveReader.RarEntry last = part(new byte[1], true, false, crc(RAW), null);
                entries.add(copy(last, last.dataCrc, 100, false, null)); // Missing physical tail.
            }
            File out = original();
            assertThrows(IOException.class, () -> extract(entries, out, null));
            assertArrayEquals(OLD, Files.readAllBytes(out.toPath()));
        }
    }

    @Test public void incompleteAndInconsistentChainsFailBeforePublication() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(true, true);
        File out = original();
        assertThrows(IOException.class, () -> Rar3IndependentPayloadExtractor.tryExtract(
                entries.get(0), Collections.singletonList(entries.get(0)), out, PASSWORD, null));
        RarArchiveReader.RarEntry last = entries.get(1);
        entries.set(1, copy(last, last.dataCrc, last.packedSize, false,
                RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[8])));
        assertThrows(IOException.class, () -> extract(entries, out, PASSWORD));
        assertArrayEquals(OLD, Files.readAllBytes(out.toPath()));
    }

    @Test public void solidAndUnsupportedEncryptionRemainExcluded() throws Exception {
        RarArchiveReader.RarEntry first = fixture(true, false).get(0);
        for (RarArchiveReader.RarEntry candidate : new RarArchiveReader.RarEntry[]{
                copy(first, first.dataCrc, first.packedSize, true, first.encryption),
                copy(first, first.dataCrc, first.packedSize, false,
                        RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[0]))}) {
            File out = original();
            assertFalse(Rar3IndependentPayloadExtractor.tryExtract(candidate,
                    Collections.singletonList(candidate), out, PASSWORD, null));
            assertArrayEquals(OLD, Files.readAllBytes(out.toPath()));
        }
    }

    @Test public void cancellationDoesNotOpenOutputOrClearInterrupt() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(false, true);
        File out = original();
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> extract(entries, out, null));
            assertTrue(Thread.currentThread().isInterrupted());
            assertArrayEquals(OLD, Files.readAllBytes(out.toPath()));
        } finally { Thread.interrupted(); }
    }

    private List<RarArchiveReader.RarEntry> fixture(boolean encrypted, boolean split) throws Exception {
        byte[] packed = Rar3FirstPartyArchiveExtractorTest.independentLzFixture();
        RarArchiveReader.EncryptionInfo encryption = null;
        if (encrypted) {
            Rar3Crypto.Parameters parameters = Rar3Crypto.deriveParameters(PASSWORD, SALT);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(parameters.key, "AES"), new IvParameterSpec(parameters.iv));
            packed = cipher.doFinal(Arrays.copyOf(packed, (packed.length + 15) / 16 * 16));
            encryption = RarArchiveReader.EncryptionInfo.rar4Unsupported(SALT.clone());
        }
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        if (split) {
            // Split within a CBC block, not just at a block boundary.
            byte[] first = Arrays.copyOf(packed, 7);
            entries.add(part(first, false, true, crc(first), encryption));
            entries.add(part(Arrays.copyOfRange(packed, 7, packed.length), true, false, crc(RAW), encryption));
        } else entries.add(part(packed, false, false, crc(RAW), encryption));
        return entries;
    }

    private RarArchiveReader.RarEntry part(byte[] bytes, boolean before, boolean after,
            long crc, RarArchiveReader.EncryptionInfo encryption) throws IOException {
        File source = temp.newFile(); Files.write(source.toPath(), bytes);
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry("image.bin", false,
                RAW.length, bytes.length, 0, 4, 0x33, false, before, after, encryption, crc, 0);
        entry.sourceArchive = source;
        return entry;
    }
    private static RarArchiveReader.RarEntry copy(RarArchiveReader.RarEntry entry, long crc,
            long size, boolean solid, RarArchiveReader.EncryptionInfo encryption) {
        RarArchiveReader.RarEntry copy = new RarArchiveReader.RarEntry(entry.path, false,
                entry.unpackedSize, size, entry.dataOffset, 4, entry.method, solid,
                entry.splitBefore, entry.splitAfter, encryption, crc, 0);
        copy.sourceArchive = entry.sourceArchive; return copy;
    }
    private boolean extract(List<RarArchiveReader.RarEntry> entries, File out, char[] password) throws IOException {
        return Rar3IndependentPayloadExtractor.tryExtract(entries.get(0), entries, out, password, null);
    }
    private File original() throws IOException {
        File out = temp.newFile(); Files.write(out.toPath(), OLD); return out;
    }
    private static long crc(byte[] bytes) { CRC32 crc = new CRC32(); crc.update(bytes); return crc.getValue(); }
}
