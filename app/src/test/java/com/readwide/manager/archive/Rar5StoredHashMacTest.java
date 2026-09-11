package com.readwide.manager.archive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

/** Synthetic AES stored/split entries; real WinRAR checksum vectors live in the compressed fixture tests. */
public class Rar5StoredHashMacTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final char[] password = "hashmac-test".toCharArray();

    @Test public void storedAndSplitHashMacWithoutPasswordCheckVerifyBeforeCommit() throws Exception {
        byte[] plain = {7, 8, 9};
        Rar5Crypto.Secrets secrets = Rar5Crypto.deriveSecrets(password, 1, new byte[16]);
        RarArchiveReader.EncryptionInfo enc = new RarArchiveReader.EncryptionInfo(
                0, 2, 1, new byte[16], new byte[16], new byte[0]);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secrets.key, "AES"), new IvParameterSpec(enc.iv));
        byte[] packed = cipher.doFinal(Arrays.copyOf(plain, 16));
        CRC32 crc = new CRC32();
        crc.update(plain);
        long expected = Rar5Crypto.tweakCrc32(crc.getValue(), secrets);
        File whole = temp.newFile("whole.rar");
        Files.write(whole.toPath(), packed);
        RarArchiveReader.RarEntry single = entry(whole, 16, false, false, enc, expected);
        File singleOut = new File(temp.getRoot(), "single.bin");
        RarArchiveReader.extractStoredEntry(single, singleOut, password, Collections.singletonList(single), null);
        assertArrayEquals(plain, Files.readAllBytes(singleOut.toPath()));

        File part1 = temp.newFile("sample.part1.rar"), part2 = temp.newFile("sample.part2.rar");
        Files.write(part1.toPath(), Arrays.copyOfRange(packed, 0, 1));
        Files.write(part2.toPath(), Arrays.copyOfRange(packed, 1, 16));
        // Checksum flags may change on the last volume without changing AES key/IV.
        RarArchiveReader.EncryptionInfo firstEnc = new RarArchiveReader.EncryptionInfo(
                0, 0, 1, new byte[16], new byte[16], new byte[0]);
        CRC32 packedCrc = new CRC32(); packedCrc.update(packed, 0, 1);
        RarArchiveReader.RarEntry first = entry(part1, 1, false, true, firstEnc, packedCrc.getValue());
        RarArchiveReader.RarEntry last = entry(part2, 15, true, false, enc, expected);
        File splitOut = new File(temp.getRoot(), "split.bin");
        RarSplitStoredExtractor.extract(first, splitOut, password, Arrays.asList(first, last), null);
        assertArrayEquals(plain, Files.readAllBytes(splitOut.toPath()));

        RarArchiveReader.RarEntry bad = entry(whole, 16, false, false, enc, expected ^ 1);
        File rejected = new File(temp.getRoot(), "bad.bin");
        try {
            RarArchiveReader.extractStoredEntry(bad, rejected, password, Collections.singletonList(bad), null);
            fail("Corrupt stored CRC must fail");
        } catch (IOException failure) { assertFalse(rejected.exists()); }
        RarArchiveReader.RarEntry badLast = entry(part2, 15, true, false, enc, expected ^ 1);
        File rejectedSplit = new File(temp.getRoot(), "bad-split.bin");
        try {
            RarSplitStoredExtractor.extract(first, rejectedSplit, password, Arrays.asList(first, badLast), null);
            fail("Corrupt final split CRC must fail");
        } catch (IOException failure) { assertFalse(rejectedSplit.exists()); }
    }

    private static RarArchiveReader.RarEntry entry(File source, long packedSize,
            boolean before, boolean after, RarArchiveReader.EncryptionInfo encryption, long crc) {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry("data.bin", false,
                3, packedSize, 0, 5, 0, false, before, after, encryption, crc, 0);
        entry.sourceArchive = source;
        return entry;
    }
}
