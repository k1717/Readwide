package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RarSplitStoredLengthTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void checksumFreePlainSplitCannotBeShorterOrLongerThanDeclared() throws Exception {
        for (long size : new long[]{4, 6}) {
            IOException failure = assertThrows(IOException.class,
                    () -> RarSplitStoredPlan.fromChain(chain(2, 3, size, false)));
            assertEquals("Stored RAR split size mismatch", failure.getMessage());
        }
    }

    @Test public void invalidLengthFailsBeforeMissingSourcesAndOutputPreparation() throws Exception {
        List<RarArchiveReader.RarEntry> chain = chain(2, 3, 4, false);
        File output = new File(temporary.getRoot(), "not-created/out.bin");
        IOException failure = assertThrows(IOException.class,
                () -> RarSplitStoredExtractor.extract(chain.get(0), output, null, chain, null));
        assertTrue(failure.getMessage().contains("size mismatch"));
        assertFalse(output.getParentFile().exists());
    }

    @Test public void invalidLengthPreservesExistingOutput() throws Exception {
        List<RarArchiveReader.RarEntry> chain = chain(2, 3, 6, false);
        File output = temporary.newFile();
        Files.write(output.toPath(), new byte[]{42});
        assertThrows(IOException.class,
                () -> RarSplitStoredExtractor.extract(chain.get(0), output, null, chain, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void encryptedLengthFailsBeforePasswordPrompt() throws Exception {
        for (long[] sizes : new long[][]{{11, 6, 12}, {11, 5, 17}}) {
            List<RarArchiveReader.RarEntry> chain = chain(sizes[0], sizes[1], sizes[2], true);
            IOException failure = assertThrows(IOException.class,
                    () -> RarSplitStoredExtractor.extract(chain.get(0),
                            new File(temporary.getRoot(), "unused/out.bin"), null, chain, null));
            assertTrue(failure.getMessage().contains("stored size or AES alignment"));
            assertFalse(failure instanceof ArchiveSupport.PasswordRequiredException);
        }
    }

    @Test public void alignedEncryptedTotalAllowsUnalignedVolumeBoundaryAndPadding() throws Exception {
        RarSplitStoredPlan plan = RarSplitStoredPlan.fromChain(chain(11, 5, 12, true));
        assertEquals(16, plan.totalPackedSize());
        assertEquals(12, plan.unpackedSize());
    }

    @Test public void matchingPlainAndEmptyLengthsRemainEligible() throws Exception {
        assertEquals(5, RarSplitStoredPlan.fromChain(chain(2, 3, 5, false)).unpackedSize());
        assertEquals(0, RarSplitStoredPlan.fromChain(chain(0, 0, 0, false)).unpackedSize());
    }

    private List<RarArchiveReader.RarEntry> chain(long firstSize, long lastSize, long unpacked, boolean encrypted) {
        RarArchiveReader.EncryptionInfo encryption = encrypted
                ? new RarArchiveReader.EncryptionInfo(0, 0, 1, new byte[16], new byte[16], new byte[0]) : null;
        RarArchiveReader.RarEntry first = entry(false, true, firstSize, unpacked, encryption);
        RarArchiveReader.RarEntry last = entry(true, false, lastSize, unpacked, encryption);
        return Arrays.asList(first, last);
    }

    private RarArchiveReader.RarEntry entry(boolean before, boolean after, long packed, long unpacked,
            RarArchiveReader.EncryptionInfo encryption) {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry("entry.bin", false,
                unpacked, packed, 0, 5, 0, false, before, after, encryption, -1, 0, 0);
        entry.sourceArchive = new File(temporary.getRoot(), "absent-" + before + ".rar");
        return entry;
    }
}
