package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RarVolumeIntegrityTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final char[] PASSWORD = "speak".toCharArray();
    private static final String NAME = "hp-sm.txt";

    @Test public void missingFirstVolumeIsCorruptionEvenWithMisleadingFilename() throws Exception {
        File selected = temp.newFile("wrong password unsupported.part2.rar");
        corrupt(assertThrows(IOException.class,
                () -> RarArchiveLocator.collectReadableVolumes(selected)));
    }

    @Test public void missingMiddleVolumeIsCorruptionFromEitherSideOfGap() throws Exception {
        File first = temp.newFile("sample.part1.rar"), last = temp.newFile("sample.part3.rar");
        for (File selected : Arrays.asList(first, last)) {
            corrupt(assertThrows(IOException.class,
                    () -> RarArchiveLocator.collectReadableVolumes(selected)));
        }
    }

    @Test public void missingLogicalContinuationIsCorruptionForSelectedAndIndexedRoutes() {
        for (int version : new int[]{4, 5}) {
            RarArchiveReader.RarEntry head = head(version);
            corrupt(assertThrows(IOException.class,
                    () -> RarVolumeChain.build(head, Collections.singletonList(head))));
            corrupt(assertThrows(IOException.class,
                    () -> RarVolumeChain.index(Collections.singletonList(head))));
            corrupt(assertThrows(IOException.class, () -> RarVolumeChain.indexSelected(
                    Collections.singletonList(head), Collections.singletonList(head))));
        }
    }

    @Test public void truncatedEncryptedHeaderArchiveKeepsItsPhysicalFailureWhenListing() throws Exception {
        File archive = truncated();
        corrupt(assertThrows(IOException.class, () -> ArchiveSupport.listEntries(archive, PASSWORD)));
    }

    @Test public void truncatedEncryptedHeaderArchiveKeepsSelectedOutput() throws Exception {
        File archive = truncated(), output = temp.newFile();
        Files.write(output.toPath(), new byte[]{42});
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(archive, NAME, output, PASSWORD);
        assertFalse(result.success);
        assertEquals(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE, result.failure);
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void truncatedEncryptedHeaderArchiveDoesNotCreateBulkOutput() throws Exception {
        File archive = truncated(), output = new File(temp.getRoot(), "output");
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(archive, output, true, PASSWORD, null);
        assertFalse(result.success);
        assertEquals(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE, result.failure);
        assertFalse(output.exists());
    }

    @Test public void truncatedEncryptedHeaderArchiveDoesNotCreateForwardSpool() throws Exception {
        File archive = truncated(), spool = temp.newFolder();
        corrupt(assertThrows(IOException.class, () -> {
            try (ArchiveSupport.ForwardArchiveReader ignored = ArchiveSupport.openForwardReader(archive, PASSWORD, spool)) {
                fail("Truncated physical payload accepted");
            }
        }));
        assertEquals(0, spool.list().length);
    }

    @Test public void missingAndWrongPasswordsStillPromptBeforeEncryptedHeadersAreRead() throws Exception {
        File archive = truncated();
        for (char[] password : new char[][]{null, "wrong".toCharArray()}) {
            IOException failure = assertThrows(ArchiveSupport.PasswordRequiredException.class,
                    () -> ArchiveSupport.listEntries(archive, password));
            assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED, ArchiveFailureClassifier.classify(failure));
        }
    }

    @Test public void cancellationStillWinsOverAMissingVolume() throws Exception {
        File selected = temp.newFile("sample.part2.rar");
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> RarArchiveLocator.collectReadableVolumes(selected));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void wrappedMissingVolumeStillYieldsToSuppressedCancellation() throws Exception {
        File selected = temp.newFile("sample.part2.rar");
        IOException missing = assertThrows(IOException.class, () -> RarArchiveLocator.collectReadableVolumes(selected));
        IOException wrapper = new IOException("wrong password", missing);
        corrupt(wrapper);
        wrapper.addSuppressed(new InterruptedIOException("cancelled"));
        assertEquals(ArchiveSupport.ExtractionFailure.FAILED, ArchiveFailureClassifier.classify(wrapper));
    }

    private File truncated() throws Exception {
        byte[] bytes = Base64.getDecoder().decode(Rar5HeaderEncryptedFixtures.STORED_B64);
        File archive = temp.newFile("headers.rar");
        Files.write(archive.toPath(), bytes);
        RarArchiveReader.RarEntry entry = RarArchiveReader.readEntries(archive, PASSWORD).get(0);
        Files.write(archive.toPath(), Arrays.copyOf(bytes, (int) (entry.dataOffset + entry.packedSize / 2)));
        return archive;
    }

    private static RarArchiveReader.RarEntry head(int version) {
        return new RarArchiveReader.RarEntry("page", false, 128, 64, 0,
                version, version == 4 ? 0x30 : 0, false, false, true, null, 0, 0);
    }

    private static void corrupt(IOException failure) {
        assertEquals(failure.toString(), ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,
                ArchiveFailureClassifier.classify(failure));
    }
}
