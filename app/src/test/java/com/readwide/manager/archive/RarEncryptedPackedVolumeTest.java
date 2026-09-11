package com.readwide.manager.archive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

/** Synthetic framing around JCE ciphertext; no new codec/real-volume certification. */
public class RarEncryptedPackedVolumeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final char[] password = "split-integrity".toCharArray();

    @Test public void storedAesChecksCiphertextAcrossUnalignedVolumeBoundaries() throws Exception {
        for (int version : new int[]{4,5}) {
            for (int corruption : new int[]{0,1,2}) {
                if (version == 4 && corruption == 2) continue;
                List<RarArchiveReader.RarEntry> chain = storedChain(version, corruption);
                File out = new File(temp.getRoot(), "out-" + version + "-" + corruption);
                try {
                    RarSplitStoredExtractor.extract(chain.get(0),out,password,chain,null);
                    if (corruption != 0) fail("Intermediate corruption must fail despite a valid final check");
                    assertArrayEquals(new byte[]{7,8,9}, Files.readAllBytes(out.toPath()));
                } catch (IOException expected) {
                    if (corruption == 0) throw expected;
                    assertTrue(expected.getMessage().contains("packed-volume"));
                    assertFalse(out.exists());
                }
            }
        }
    }

    @Test public void cancellationWithoutProgressStopsEncryptedSegmentCopy() throws Exception {
        List<RarArchiveReader.RarEntry> chain = storedChain(4,0);
        File out = new File(temp.getRoot(),"cancelled");
        Thread.currentThread().interrupt();
        try {
            RarSplitStoredExtractor.extract(chain.get(0),out,password,chain,null);
            fail("Interrupted work must not finish extraction");
        } catch (IOException expected) { assertFalse(out.exists()); }
        finally { Thread.interrupted(); }
    }

    private List<RarArchiveReader.RarEntry> storedChain(int version, int corruption) throws Exception {
        byte[] plain = {7,8,9};
        byte[] salt = new byte[version == 4 ? 8 : 16];
        Rar5Crypto.Secrets secrets = version == 5 ? Rar5Crypto.deriveSecrets(password,1,salt) : null;
        Rar3Crypto.Parameters legacy = version == 4 ? Rar3Crypto.deriveParameters(password,salt) : null;
        byte[] iv = legacy == null ? new byte[16] : legacy.iv;
        Cipher encrypt = Cipher.getInstance("AES/CBC/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(legacy == null ? secrets.key : legacy.key,"AES"),
                new IvParameterSpec(iv));
        // Two full padding blocks let an intermediate checksum fail after the output limit.
        byte[] packed = encrypt.doFinal(Arrays.copyOf(plain,48));
        int[] boundaries = {0,17,33,48};
        List<RarArchiveReader.RarEntry> chain = new ArrayList<>();
        for (int i=0;i<3;i++) {
            byte[] part = Arrays.copyOfRange(packed,boundaries[i],boundaries[i+1]);
            boolean last = i == 2;
            // The middle member alone uses keyed checks; flags are per-volume metadata.
            boolean keyed = version == 5 && i == 1;
            RarArchiveReader.EncryptionInfo enc = version == 4
                    ? RarArchiveReader.EncryptionInfo.rar4Unsupported(salt)
                    : new RarArchiveReader.EncryptionInfo(0,keyed ? 2 : 0,1,salt,iv,new byte[0]);
            byte[] checked = last ? plain : part;
            CRC32 crc = new CRC32(); crc.update(checked);
            long expectedCrc = keyed ? Rar5Crypto.tweakCrc32(crc.getValue(),secrets) : crc.getValue();
            byte[] hash = null;
            if (version == 5) {
                RarBlake2sp blake = new RarBlake2sp(); blake.update(checked,0,checked.length);
                hash = blake.digest();
                if (keyed) hash = Rar5Crypto.tweakBlake2sp(hash,secrets);
            }
            if (i == 1 && corruption == 1) expectedCrc ^= 1;
            if (i == 1 && corruption == 2) hash[31] ^= 1;
            File file = temp.newFile(); Files.write(file.toPath(),part);
            RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry("data.bin",false,
                    plain.length,part.length,0,version,version == 4 ? 0x30 : 0,false,
                    i != 0,!last,enc,expectedCrc,0,0,hash);
            entry.sourceArchive = file;
            chain.add(entry);
        }
        return chain;
    }
}
