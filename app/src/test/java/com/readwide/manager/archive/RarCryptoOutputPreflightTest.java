package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RarCryptoOutputPreflightTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void unalignedSinglePayloadPreservesDestination() throws Exception { single(16, 15, 1); }
    @Test public void truncatedSinglePayloadPreservesDestination() throws Exception { single(8, 16, 1); }
    @Test public void impossiblePlaintextLimitPreservesDestination() throws Exception { single(16, 16, 17); }

    @Test public void unalignedSplitTotalPreservesDestination() throws Exception {
        split(Arrays.asList(segment(11, 0), segment(6, 0)), 1);
    }

    @Test public void negativeSplitOffsetAndImpossibleLimitPreserveDestination() throws Exception {
        split(Collections.singletonList(segment(16, -1)), 1);
        split(Collections.singletonList(segment(16, 0)), 17);
    }

    @Test public void overflowingSplitTotalPreservesDestination() throws Exception {
        split(Arrays.asList(segment(Long.MAX_VALUE, 0), segment(1, 0)), 1);
    }

    @Test public void validAesBlockCrossingVolumesStillDecrypts() throws Exception {
        byte[] plaintext = new byte[16];
        for (int i = 0; i < plaintext.length; i++) plaintext[i] = (byte) i;
        byte[] encrypted = cipher(Cipher.ENCRYPT_MODE).doFinal(plaintext);
        File first = file(Arrays.copyOfRange(encrypted, 0, 11));
        File last = file(Arrays.copyOfRange(encrypted, 11, 16));
        File output = file(new byte[]{42});
        RarCryptoStreams.decryptSegmentsToFile(Arrays.asList(
                new RarCryptoStreams.EncryptedSegment(first, 0, 11),
                new RarCryptoStreams.EncryptedSegment(last, 0, 5)),
                9, cipher(Cipher.DECRYPT_MODE), output, "decrypt", null, false);
        assertArrayEquals(Arrays.copyOf(plaintext, 9), Files.readAllBytes(output.toPath()));
    }

    private void single(int physicalSize, long encryptedSize, long plaintextLimit) throws Exception {
        File output = file(new byte[]{42});
        // An uninitialized cipher ensures bad metadata is rejected before cipher processing.
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        try (RandomAccessFile input = new RandomAccessFile(file(new byte[physicalSize]), "r")) {
            assertThrows(IOException.class, () -> RarCryptoStreams.decryptToFile(input,
                    encryptedSize, plaintextLimit, cipher, output, "decrypt", null, false));
            assertEquals(0, input.getFilePointer());
        }
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    private void split(List<RarCryptoStreams.EncryptedSegment> segments, long limit) throws Exception {
        File output = file(new byte[]{42});
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        assertThrows(IOException.class, () -> RarCryptoStreams.decryptSegmentsToFile(
                segments, limit, cipher, output, "decrypt", null, false));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    private RarCryptoStreams.EncryptedSegment segment(long size, long offset) {
        return new RarCryptoStreams.EncryptedSegment(new File(temporary.getRoot(), "absent.rar"), offset, size);
    }

    private File file(byte[] bytes) throws Exception {
        File file = temporary.newFile();
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static Cipher cipher(int mode) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(mode, new SecretKeySpec(new byte[16], "AES"), new IvParameterSpec(new byte[16]));
        return cipher;
    }
}
