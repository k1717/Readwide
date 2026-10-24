package com.readwide.manager.archive;

import static org.junit.Assert.*;
import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Injects two header-matched contexts; does not claim a real PBKDF2 verifier collision fixture. */
public class EggAuthenticatedCandidateTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void secondCandidateAcrossBlocksAndPhysicalCutsKeepsFreshCipherAndMac() throws Exception {
        for (boolean lea : new boolean[]{false, true}) for (int bits : new int[]{128, 256}) {
            Fixture f = new Fixture(bits, lea);
            EggAuthenticatedCrypto wrong = f.context("wrong");
            EggAuthenticatedCrypto right = f.context("right");
            try (SplitVolumeInput input = f.input()) {
                assertSame(right, select(input, f.entry, wrong, right, null));
            }
            byte[] decoded = f.ciphertext.clone();
            right.decryptInPlace(decoded, 0, 37);
            right.decryptInPlace(decoded, 37, decoded.length - 37);
            assertArrayEquals(f.plain, decoded);
            assertTrue(right.verifyFooter(f.footer));
        }
    }

    @Test public void firstCandidateStillWinsWhenOnlyItsFullMacMatches() throws Exception {
        Fixture f = new Fixture(128, false);
        EggAuthenticatedCrypto right = f.context("right");
        try (SplitVolumeInput input = f.input()) {
            assertSame(right, select(input, f.entry, right, f.context("wrong"), null));
        }
    }

    @Test public void missingMacMatchIsRejected() throws Exception {
        Fixture f = new Fixture(256, false);
        try (SplitVolumeInput input = f.input()) {
            assertThrows(IOException.class, () -> select(input, f.entry,
                    f.context("wrong"), f.context("also-wrong"), null));
        }
    }

    @Test public void twoMacMatchesAreRejectedAsAmbiguous() throws Exception {
        Fixture f = new Fixture(128, true);
        try (SplitVolumeInput input = f.input()) {
            assertThrows(IOException.class, () -> select(input, f.entry,
                    f.context("right"), f.context("right"), null));
        }
    }

    @Test public void cancellationDoesNotBecomePasswordRejection() throws Exception {
        Fixture f = new Fixture(128, false);
        FileOperationProgress progress = new FileOperationProgress("EGG", null);
        progress.cancel();
        try (SplitVolumeInput input = f.input()) {
            assertThrows(java.io.InterruptedIOException.class, () -> select(input, f.entry,
                    f.context("right"), f.context("wrong"), progress));
        }
    }

    @Test public void unavailableSourceDoesNotBecomePasswordRejection() throws Exception {
        Fixture f = new Fixture(128, false);
        SplitVolumeInput input = f.input();
        input.close();
        IOException failure = assertThrows(IOException.class, () -> select(input, f.entry,
                f.context("right"), f.context("wrong"), null));
        assertFalse(failure.getMessage().contains("password authentication"));
    }

    @Test public void bytesAfterFirstBlockRemainRequiredForSelection() throws Exception {
        Fixture f = new Fixture(128, false);
        byte[] changed = f.logical.clone(); changed[changed.length - 1] ^= 1;
        Files.write(f.last.toPath(), Arrays.copyOfRange(changed, 7, changed.length));
        try (SplitVolumeInput input = f.input()) {
            assertThrows(IOException.class, () -> select(input, f.entry,
                    f.context("right"), f.context("wrong"), null));
        }
    }

    @Test public void staleCapturedSourceIsRejectedBeforeCandidateReads() throws Exception {
        Fixture f = new Fixture(128, false);
        try (SplitVolumeInput input = f.input()) {
            int[] reads = {0};
            interceptFirstRead(input, f.first, () -> reads[0]++);
            changeStamp(f.first);
            IOException failure = assertThrows(IOException.class, () -> select(input, f.entry,
                    f.context("right"), f.context("wrong"), null));
            assertTrue(failure.getMessage().contains("changed"));
            assertEquals(0, reads[0]);
        }
    }

    @Test public void activeVolumeChangeCannotAcceptOrRejectPasswordCandidates() throws Exception {
        for (boolean lea : new boolean[]{false, true}) for (boolean matches : new boolean[]{false, true}) {
            Fixture f = new Fixture(128, lea);
            File file = temp.newFile(); Files.write(file.toPath(), f.logical);
            try (SplitVolumeInput input = singleInput(file)) {
                interceptFirstRead(input, file, () -> changeStamp(file));
                IOException failure = assertThrows(IOException.class, () -> select(input, f.entry,
                        f.context(matches ? "right" : "also-wrong"), f.context("wrong"), null));
                assertTrue(failure.getMessage().contains("changed"));
            }
        }
    }

    @Test public void alreadyReadSplitVolumeChangeIsTerminalAfterMac() throws Exception {
        Fixture f = new Fixture(256, true);
        try (SplitVolumeInput input = f.input()) {
            // The first physical read changes its stamp; later reads switch to
            // the second volume without altering any authenticated ciphertext.
            interceptFirstRead(input, f.first, () -> changeStamp(f.first));
            IOException failure = assertThrows(IOException.class, () -> select(input, f.entry,
                    f.context("right"), f.context("wrong"), null));
            assertTrue(failure.getMessage().contains("changed"));
        }
    }

    @Test public void physicalFailureRetainsPriorityOverChangedSource() throws Exception {
        Fixture f = new Fixture(128, false);
        IOException injected = new IOException("physical candidate read failed");
        try (SplitVolumeInput input = f.input()) {
            interceptFirstRead(input, f.first, () -> { changeStamp(f.first); throw injected; });
            assertSame(injected, assertThrows(IOException.class, () -> select(input, f.entry,
                    f.context("right"), f.context("wrong"), null)));
        }
    }

    @Test public void cancellationDuringFinalMetadataCheckCannotSelectCandidate() throws Exception {
        checkFinalMetadataAction(true);
    }

    @Test(timeout = 5000) public void pauseDuringFinalMetadataCheckDoesNotDelayAcceptance() throws Exception {
        checkFinalMetadataAction(false);
    }

    private void checkFinalMetadataAction(boolean cancel) throws Exception {
        Fixture f = new Fixture(128, false);
        File file = temp.newFile(); Files.write(file.toPath(), f.logical);
        FileOperationProgress progress = new FileOperationProgress("EGG", null);
        boolean[] read = {false}, observed = {false};
        File hooked = new File(file.getPath()) {
            @Override public File getCanonicalFile() { return this; }
            @Override public long lastModified() {
                long stamp = super.lastModified();
                if (read[0]) {
                    observed[0] = true;
                    if (cancel) progress.cancel(); else progress.setPaused(true);
                }
                return stamp;
            }
        };
        try (SplitVolumeInput input = singleInput(hooked)) {
            interceptFirstRead(input, file, () -> read[0] = true);
            EggAuthenticatedCrypto right = f.context("right"), wrong = f.context("wrong");
            if (cancel) assertThrows(java.io.InterruptedIOException.class,
                    () -> select(input, f.entry, right, wrong, progress));
            else assertSame(right, select(input, f.entry, right, wrong, progress));
            assertTrue(observed[0]);
        } finally { progress.setPaused(false); }
    }

    private interface ReadAction { void run() throws IOException; }

    private static SplitVolumeInput singleInput(File file) throws IOException {
        return new SplitVolumeInput(Collections.singletonList(new SplitVolumeInput.Segment(file, 0, file.length())));
    }

    private static void interceptFirstRead(SplitVolumeInput input, File file, ReadAction action) throws Exception {
        assertTrue(input.read() >= 0); input.seek(0);
        Field active = field(input, "activeFile");
        ((RandomAccessFile) active.get(input)).close();
        active.set(input, new RandomAccessFile(file, "r") {
            boolean fired;
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                int count = super.read(buffer, offset, length);
                if (count > 0 && !fired) { fired = true; action.run(); }
                return count;
            }
        });
    }

    private static void changeStamp(File file) throws IOException {
        long before = file.lastModified();
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(before + 10000));
        assertNotEquals(before, file.lastModified());
    }

    private final class Fixture {
        final int bits;
        final boolean lea;
        final byte[] salt;
        final byte[] plain = new byte[65581];
        final byte[] ciphertext;
        final byte[] footer;
        final byte[] logical;
        final File first, last;
        final Object entry;

        Fixture(int bits, boolean lea) throws Exception {
            this.bits = bits; this.lea = lea; salt = new byte[bits / 16];
            for (int i = 0; i < plain.length; i++) plain[i] = (byte) (i * 17);
            byte[] derived = derive("right");
            int keyLength = bits / 8;
            byte[] key = Arrays.copyOf(derived, keyLength);
            Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            EggLeaCipher leaCipher = lea ? new EggLeaCipher(key) : null;
            ciphertext = plain.clone();
            for (int offset = 0, block = 0; offset < ciphertext.length; offset += 16, block++) {
                byte[] counter = new byte[16], stream = new byte[16];
                int ordinal = lea ? block : block + 1;
                for (int i = 0; i < 4; i++) counter[lea ? 15 - i : i] = (byte) (ordinal >>> (8 * i));
                if (lea) leaCipher.encryptBlock(counter, stream); else stream = aes.doFinal(counter);
                for (int i = 0; i < 16 && offset + i < ciphertext.length; i++) ciphertext[offset + i] ^= stream[i];
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(Arrays.copyOfRange(derived, keyLength, keyLength * 2), "HmacSHA1"));
            footer = Arrays.copyOf(mac.doFinal(ciphertext), 10);
            // Inter-block header bytes are not ciphertext and must not enter the MAC.
            logical = new byte[ciphertext.length + 3];
            System.arraycopy(ciphertext, 0, logical, 0, 37);
            Arrays.fill(logical, 37, 40, (byte) 0x7f);
            System.arraycopy(ciphertext, 37, logical, 40, ciphertext.length - 37);
            first = temp.newFile(); last = temp.newFile();
            Files.write(first.toPath(), Arrays.copyOf(logical, 7));
            Files.write(last.toPath(), Arrays.copyOfRange(logical, 7, logical.length));
            entry = newPrivate("EggEntry");
            field(entry, "aesFooter").set(entry, footer);
            @SuppressWarnings("unchecked") List<Object> blocks = (List<Object>) field(entry, "blocks").get(entry);
            blocks.add(block(0, 37)); blocks.add(block(40, ciphertext.length - 37));
        }

        byte[] derive(String password) throws Exception {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 1000, (bits / 4 + 2) * 8);
            try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded(); }
            finally { spec.clearPassword(); }
        }
        EggAuthenticatedCrypto context(String password) throws Exception {
            byte[] derived = derive(password);
            EggAuthenticatedCrypto context = new EggAuthenticatedCrypto(password.getBytes(StandardCharsets.US_ASCII),
                    salt, Arrays.copyOfRange(derived, bits / 4, bits / 4 + 2), bits, lea);
            assertTrue(context.isPasswordVerified());
            return context;
        }
        SplitVolumeInput input() throws IOException {
            return new SplitVolumeInput(Arrays.asList(new SplitVolumeInput.Segment(first, 0, first.length()),
                    new SplitVolumeInput.Segment(last, 0, last.length())));
        }
    }

    private static Object block(long offset, long size) throws Exception {
        Object block = newPrivate("EggBlock");
        field(block, "dataOffset").setLong(block, offset); field(block, "compSize").setLong(block, size);
        return block;
    }
    private static Object newPrivate(String name) throws Exception {
        Constructor<?> constructor = Class.forName(EggArchiveReader.class.getName() + "$" + name).getDeclaredConstructor();
        constructor.setAccessible(true); return constructor.newInstance();
    }
    private static Field field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static EggAuthenticatedCrypto select(SplitVolumeInput input, Object entry,
            EggAuthenticatedCrypto first, EggAuthenticatedCrypto second, FileOperationProgress progress) throws Exception {
        Method method = EggArchiveReader.class.getDeclaredMethod("selectAuthenticatedDecryptor", SplitVolumeInput.class,
                entry.getClass(), EggAuthenticatedCrypto.class, EggAuthenticatedCrypto.class, FileOperationProgress.class);
        method.setAccessible(true);
        try { return (EggAuthenticatedCrypto) method.invoke(null, input, entry, first, second, progress); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw (Error) failure.getCause();
        }
    }
}
