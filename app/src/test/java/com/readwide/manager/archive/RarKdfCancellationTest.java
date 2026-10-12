package com.readwide.manager.archive;

import static org.junit.Assert.*;
import com.readwide.manager.util.FileOperationProgress;
import java.io.InterruptedIOException;
import org.junit.Test;

public class RarKdfCancellationTest {
    @Test public void cancelledTokenStopsBothDeriversWithoutMutatingCallerPassword() throws Exception {
        FileOperationProgress progress = cancelled();
        char[] password = "unchanged".toCharArray();
        char[] before = password.clone();
        assertThrows(InterruptedIOException.class,
                () -> Rar3Crypto.deriveParameters(password, new byte[8], progress));
        assertThrows(InterruptedIOException.class,
                () -> Rar5Crypto.deriveSecrets(password, 24, new byte[16], progress));
        assertArrayEquals(before, password);
    }

    @Test public void cancelledRar3CipherFactoryDoesNotStartDerivation() throws Exception {
        assertThrows(InterruptedIOException.class,
                () -> Rar3Crypto.createAesCbcDecryptCipher("pw".toCharArray(), new byte[8], cancelled()));
    }

    @Test public void legacyOverloadsStillHonorThreadInterruption() throws Exception {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> Rar3Crypto.deriveParameters("pw".toCharArray(), new byte[8]));
            assertThrows(InterruptedIOException.class,
                    () -> Rar5Crypto.deriveSecrets("pw".toCharArray(), 24, new byte[16]));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void progressAwareRar3DerivationRetainsKnownVector() throws Exception {
        Rar3Crypto.Parameters parameters = Rar3Crypto.deriveParameters("hello".toCharArray(),
                hex("379475b06e303955"), new FileOperationProgress("derive", null));
        assertArrayEquals(hex("a002f7af8fc3b153436abb226f298747"), parameters.key);
        assertArrayEquals(hex("e3dfe7498ad0faf3325f9ee9283a396c"), parameters.iv);
    }

    @Test public void progressAwareRar5DerivationRetainsLegacyResults() throws Exception {
        char[] password = "pw".toCharArray();
        byte[] salt = hex("00112233445566778899aabbccddeeff");
        Rar5Crypto.Secrets legacy = Rar5Crypto.deriveSecrets(password, 1, salt);
        Rar5Crypto.Secrets progressAware = Rar5Crypto.deriveSecrets(password, 1, salt,
                new FileOperationProgress("derive", null));
        assertArrayEquals(legacy.key, progressAware.key);
        assertArrayEquals(legacy.hashKey, progressAware.hashKey);
        assertArrayEquals(legacy.pswCheck, progressAware.pswCheck);
    }

    private static FileOperationProgress cancelled() {
        FileOperationProgress progress = new FileOperationProgress("derive", null);
        progress.cancel();
        return progress;
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
