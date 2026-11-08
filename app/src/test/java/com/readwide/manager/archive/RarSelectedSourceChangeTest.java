package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RarSelectedSourceChangeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] DATA = {1, 2, 3, 4}, OLD = {42};
    private static final String NAME = "page.txt";

    @Test public void unchangedStoredSourcesStillExtract() throws Exception {
        for (int version : new int[]{4, 5}) for (boolean existing : new boolean[]{false, true}) {
            File archive = archive(version), output = output(existing);
            ArchiveSupport.ExtractionResult result = extract(archive, NAME, output, null);
            assertTrue(result.detail, result.success);
            assertArrayEquals(DATA, Files.readAllBytes(output.toPath()));
            cleanParent(output, true);
        }
    }

    @Test public void renamedMemberAfterMetadataScanCannotPublishStaleName() throws Exception {
        for (int version : new int[]{4, 5}) for (boolean existing : new boolean[]{false, true}) {
            File archive = archive(version);
            reject(archive, NAME, null, existing, () -> {
                long modified = archive.lastModified();
                Files.write(archive.toPath(), bytes(version, "gone.txt"));
                assertTrue(archive.setLastModified(modified + 5000));
            });
            assertEquals("gone.txt", RarArchiveReader.readEntries(archive, null).get(0).path);
        }
    }

    @Test public void timestampChangeDuringOutputOpenRestoresPriorOutput() throws Exception {
        for (int version : new int[]{4, 5}) for (boolean existing : new boolean[]{false, true}) {
            File archive = archive(version);
            reject(archive, NAME, null, existing, () -> touch(archive));
        }
    }

    @Test public void changedPreviouslyScannedVolumeCannotPublishSelectedMember() throws Exception {
        for (int version : new int[]{4, 5}) {
            File archive = archive(version), later = new File(archive.getParentFile(), "book.r00");
            Files.write(later.toPath(), new byte[]{9, 8, 7});
            reject(archive, NAME, null, true, () -> touch(later));
        }
    }

    @Test public void removedPreviouslyScannedVolumeCannotPublishSelectedMember() throws Exception {
        for (int version : new int[]{4, 5}) {
            File archive = archive(version), later = new File(archive.getParentFile(), "book.r00");
            Files.write(later.toPath(), new byte[]{9, 8, 7});
            reject(archive, NAME, null, true, () -> Files.delete(later.toPath()));
        }
    }

    @Test public void addedVolumeAfterMetadataScanCannotPublishSelectedMember() throws Exception {
        for (int version : new int[]{4, 5}) {
            File archive = archive(version), later = new File(archive.getParentFile(), "book.r00");
            reject(archive, NAME, null, true, () -> Files.write(later.toPath(), new byte[]{9, 8, 7}));
        }
    }

    @Test public void unchangedCorruptTailStillAllowsCompletePrefixRecovery() throws Exception {
        for (int version : new int[]{4, 5}) {
            File archive = archive(version), output = output(true);
            Files.write(new File(archive.getParentFile(), "book.r00").toPath(), new byte[]{9, 8, 7});
            ArchiveSupport.ExtractionResult result = extract(archive, NAME, output, null);
            assertTrue(result.detail, result.success);
            assertArrayEquals(DATA, Files.readAllBytes(output.toPath()));
            cleanParent(output, true);
        }
    }

    @Test public void encryptedHeaderSourceChangeRestoresOutput() throws Exception {
        for (String fixture : new String[]{Rar5HeaderEncryptedFixtures.STORED_B64,
                Rar5HeaderEncryptedFixtures.COMPRESSED_B64}) {
            for (boolean existing : new boolean[]{false, true}) {
                File archive = new File(temp.newFolder(), "headers.rar");
                Files.write(archive.toPath(), Base64.getDecoder().decode(fixture));
                reject(archive, "hp-sm.txt", "speak".toCharArray(), existing, () -> touch(archive));
            }
        }
    }

    @Test public void unchangedNumericSourcesStillExtract() throws Exception {
        for (int version : new int[]{4, 5}) for (int selected : new int[]{0, 1}) {
            File[] parts = numericParts(version);
            File output = output(true);
            ArchiveSupport.ExtractionResult result = extract(parts[selected], NAME, output, null);
            assertTrue(result.detail, result.success);
            assertArrayEquals(DATA, Files.readAllBytes(output.toPath()));
            cleanParent(output, true);
        }
    }

    @Test public void numericSourceChangeAfterPreparationRestoresOutput() throws Exception {
        for (int version : new int[]{4, 5}) for (int selected : new int[]{0, 1}) {
            File[] parts = numericParts(version);
            reject(parts[selected], NAME, null, true, () -> touch(parts[1]));
        }
    }

    @Test public void changeWhileProtectingOutputStopsBeforeDecoding() throws Exception {
        File archive = archive(4), original = output(true);
        boolean[] protectedOutput = {false}, openedOutput = {false};
        File output = new File(original.getPath()) {
            @Override public boolean renameTo(File target) {
                boolean renamed = super.renameTo(target);
                if (renamed && !protectedOutput[0]) {
                    protectedOutput[0] = true;
                    touch(archive);
                }
                return renamed;
            }
            @Override public File getAbsoluteFile() {
                openedOutput[0] = true;
                return super.getAbsoluteFile();
            }
        };
        ArchiveSupport.ExtractionResult result = extract(archive, NAME, output, null);
        assertTrue(protectedOutput[0]);
        assertFalse(result.detail, result.success);
        assertFalse(openedOutput[0]);
        assertArrayEquals(OLD, Files.readAllBytes(original.toPath()));
        cleanParent(original, true);
    }

    @Test public void missingSelectedMemberKeepsPriorOutput() throws Exception {
        File archive = archive(4), output = output(true);
        ArchiveSupport.ExtractionResult result = extract(archive, "absent.txt", output, null);
        assertFalse(result.success);
        assertEquals(ArchiveSupport.ExtractionFailure.FAILED, result.failure);
        assertArrayEquals(OLD, Files.readAllBytes(output.toPath()));
        cleanParent(output, true);
    }

    private void reject(File archive, String name, char[] password, boolean existing,
                        Change change) throws Exception {
        File original = output(existing);
        boolean[] mutated = {false};
        File output = new File(original.getPath()) {
            @Override public File getAbsoluteFile() {
                if (!mutated[0]) {
                    mutated[0] = true;
                    try { change.run(); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }
                return super.getAbsoluteFile();
            }
        };
        ArchiveSupport.ExtractionResult result = extract(archive, name, output, password);
        assertTrue("Mutation must reach the selected payload output", mutated[0]);
        assertFalse("Changed source accepted: " + result.detail, result.success);
        assertEquals(ArchiveSupport.ExtractionFailure.FAILED, result.failure);
        assertTrue(result.detail, result.detail != null && result.detail.contains("source changed"));
        if (existing) assertArrayEquals(OLD, Files.readAllBytes(original.toPath()));
        cleanParent(original, existing);
    }

    private static ArchiveSupport.ExtractionResult extract(File archive, String name, File output, char[] password) {
        return ArchiveSupport.extractSingleEntryDetailed(archive, name, output, password);
    }

    private File output(boolean existing) throws Exception {
        File file = new File(temp.newFolder(), "output.bin");
        if (existing) Files.write(file.toPath(), OLD);
        return file;
    }

    private static void cleanParent(File file, boolean exists) {
        assertArrayEquals(exists ? new String[]{file.getName()} : new String[0], file.getParentFile().list());
    }

    private static void touch(File file) { assertTrue(file.setLastModified(file.lastModified() + 5000)); }

    private File archive(int version) throws Exception {
        File file = new File(temp.newFolder(), "book.rar");
        Files.write(file.toPath(), bytes(version, NAME));
        return file;
    }

    private File[] numericParts(int version) throws Exception {
        File folder = temp.newFolder();
        File[] parts = {new File(folder, "book.rar.001"), new File(folder, "book.rar.002")};
        byte[] content = bytes(version, NAME);
        int boundary = content.length / 2;
        Files.write(parts[0].toPath(), java.util.Arrays.copyOfRange(content, 0, boundary));
        Files.write(parts[1].toPath(), java.util.Arrays.copyOfRange(content, boundary, content.length));
        return parts;
    }

    private static byte[] bytes(int version, String path) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'R', 'a', 'r', '!', 0x1a, 7, (byte) (version == 4 ? 0 : 1)});
        if (version == 5) out.write(0);
        out.write(version == 4 ? header4(0x73, 0, new byte[6]) : header5(new byte[]{1, 0, 0}));
        byte[] name = path.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (version == 4) {
            Round4Fixtures.le(body, DATA.length, 4); Round4Fixtures.le(body, DATA.length, 4);
            body.write(1); Round4Fixtures.le(body, Round4Fixtures.crc(DATA), 4);
            Round4Fixtures.le(body, 0, 4); body.write(29); body.write(0x30);
            Round4Fixtures.le(body, name.length, 2); Round4Fixtures.le(body, 0, 4); body.write(name);
            out.write(header4(0x74, 0x8000, body.toByteArray()));
        } else {
            body.write(new byte[]{2, 2, (byte) DATA.length, 4, (byte) DATA.length, 0});
            Round4Fixtures.le(body, Round4Fixtures.crc(DATA), 4);
            body.write(0); body.write(1); body.write(name.length); body.write(name);
            out.write(header5(body.toByteArray()));
        }
        out.write(DATA);
        out.write(version == 4 ? header4(0x7b, 0, new byte[0]) : header5(new byte[]{5, 0, 0}));
        return out.toByteArray();
    }

    private static byte[] header4(int type, int flags, byte[] body) throws Exception {
        ByteArrayOutputStream header = new ByteArrayOutputStream(), out = new ByteArrayOutputStream();
        header.write(type); Round4Fixtures.le(header, flags, 2);
        Round4Fixtures.le(header, 7 + body.length, 2); header.write(body);
        Round4Fixtures.le(out, Round4Fixtures.crc(header.toByteArray()), 2);
        out.write(header.toByteArray()); return out.toByteArray();
    }

    private static byte[] header5(byte[] body) throws Exception {
        ByteArrayOutputStream header = new ByteArrayOutputStream(), out = new ByteArrayOutputStream();
        header.write(body.length); header.write(body);
        Round4Fixtures.le(out, Round4Fixtures.crc(header.toByteArray()), 4);
        out.write(header.toByteArray()); return out.toByteArray();
    }

    private interface Change { void run() throws Exception; }
}
