package com.readwide.manager.archive;

import static org.junit.Assert.*;

import com.readwide.manager.util.FileOperationProgress;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Collections;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RarCryptoStreamsCancellationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void preInterruptedStreamDoesNotReadOrWriteWithoutProgressToken() throws Exception {
        File source = file(new byte[16]);
        Cipher cipher = cipher(Cipher.DECRYPT_MODE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> RarCryptoStreams.decryptToStream(
                        input, 16, cipher, output, -1, "decrypt failed", null, false));
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(0, input.getFilePointer());
                assertEquals(0, output.size());
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void interruptionOnFinalWriteIsNotReportedAsSuccess() throws Exception {
        byte[] encrypted = cipher(Cipher.ENCRYPT_MODE).doFinal(new byte[16]);
        Cipher cipher = cipher(Cipher.DECRYPT_MODE);
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                super.write(bytes, offset, length);
                Thread.currentThread().interrupt();
            }
        };
        try (RandomAccessFile input = new RandomAccessFile(file(encrypted), "r")) {
            try {
                assertThrows(InterruptedIOException.class, () -> RarCryptoStreams.decryptToStream(
                        input, encrypted.length, cipher, output, 16, "decrypt failed", null, false));
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(16, output.size());
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void preCancelledZeroLengthFileDoesNotTruncateExistingDestination() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("decrypt", null);
        progress.cancel();
        File output = file(new byte[]{42});
        Cipher cipher = cipher(Cipher.DECRYPT_MODE);
        try (RandomAccessFile input = new RandomAccessFile(file(new byte[0]), "r")) {
            assertThrows(InterruptedIOException.class, () -> RarCryptoStreams.decryptToFile(
                    input, 0, 0, cipher, output, "decrypt failed", progress, false));
        }
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void preInterruptedSplitFileDoesNotTruncateExistingDestination() throws Exception {
        File output = file(new byte[]{42});
        Cipher cipher = cipher(Cipher.DECRYPT_MODE);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> RarCryptoStreams.decryptSegmentsToFile(
                    Collections.emptyList(), 0, cipher, output, "decrypt failed", null, false));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void interruptedEmptyStreamStopsBeforeCipherFinalization() throws Exception {
        Cipher cipher = cipher(Cipher.DECRYPT_MODE);
        try (RandomAccessFile input = new RandomAccessFile(file(new byte[0]), "r")) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> RarCryptoStreams.decryptToStream(
                        input, 0, cipher, new ByteArrayOutputStream(), -1, "decrypt failed", null, false));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void uninterruptedDecryptRetainsSizeLimitAndConsumesPadding() throws Exception {
        byte[] plaintext = new byte[32];
        for (int i = 0; i < plaintext.length; i++) plaintext[i] = (byte) i;
        byte[] encrypted = cipher(Cipher.ENCRYPT_MODE).doFinal(plaintext);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (RandomAccessFile input = new RandomAccessFile(file(encrypted), "r")) {
            assertEquals(0, RarCryptoStreams.decryptToStream(input, encrypted.length,
                    cipher(Cipher.DECRYPT_MODE), output, 17, "decrypt failed", null, false));
            assertEquals(encrypted.length, input.getFilePointer());
        }
        assertArrayEquals(java.util.Arrays.copyOf(plaintext, 17), output.toByteArray());
    }

    private File file(byte[] bytes) throws Exception {
        File file = temp.newFile();
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static Cipher cipher(int mode) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(mode, new SecretKeySpec(new byte[16], "AES"), new IvParameterSpec(new byte[16]));
        return cipher;
    }
}
