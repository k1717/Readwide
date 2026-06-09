package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RarBackendRouterTest {
    @Test
    public void routesNonSolidClassicLzToLimitedFirstPartyFallback() {
        RarArchiveReader.RarEntry entry = entry(0x33, false, false, false, false, 4, null);

        RarBackendDecision decision = RarBackendRouter.decideEntry(entry);

        assertEquals(RarBackendRoute.Kind.TRY_FIRST_PARTY_CLASSIC_LZ_NON_SOLID, decision.route);
        assertTrue(decision.firstPartyAllowed);
        assertFalse(decision.libarchiveOwned);
    }

    @Test
    public void keepsSolidClassicLzDiagnosticOnly() {
        RarArchiveReader.RarEntry entry = entry(0x33, true, false, false, false, 4, null);

        RarBackendDecision decision = RarBackendRouter.decideEntry(entry);

        assertEquals(RarBackendRoute.Kind.CLEAN_UNSUPPORTED_SOLID, decision.route);
        assertFalse(decision.firstPartyAllowed);
    }

    @Test
    public void routesPlainCompressedSplitThroughRewriteHelper() {
        RarArchiveReader.RarEntry entry = entry(0x33, false, false, false, true, 4, null);

        RarBackendDecision decision = RarBackendRouter.decideEntry(entry);

        assertEquals(RarBackendRoute.Kind.TRY_FIRST_PARTY_RAR4_COMPRESSED_SPLIT_REWRITE, decision.route);
        assertTrue(decision.firstPartyAllowed);
    }

    @Test
    public void routesStoredSplitToStoredSplitPath() {
        RarArchiveReader.RarEntry entry = entry(0x30, false, false, false, true, 4, null);

        RarBackendDecision decision = RarBackendRouter.decideEntry(entry);

        assertEquals(RarBackendRoute.Kind.TRY_FIRST_PARTY_STORED_SPLIT, decision.route);
        assertTrue(decision.firstPartyAllowed);
    }

    @Test
    public void keepsRar5CompressedLibarchivePrimaryWithoutFirstPartyClaim() {
        RarArchiveReader.RarEntry entry = entry(0x33, false, false, false, false, 5, null);

        RarBackendDecision decision = RarBackendRouter.decideEntry(entry);

        assertEquals(RarBackendRoute.Kind.TRY_LIBARCHIVE, decision.route);
        assertFalse(decision.firstPartyAllowed);
        assertTrue(decision.libarchiveOwned);
    }


    @Test
    public void routesEncryptedCompressedSplitThroughDecryptRewriteHelper() {
        RarArchiveReader.EncryptionInfo encryption = RarArchiveReader.EncryptionInfo.rar4Unsupported(
                new byte[] {1,2,3,4,5,6,7,8});
        RarArchiveReader.RarEntry entry = entry(0x33, false, false, false, true, 4, encryption);

        RarBackendDecision decision = RarBackendRouter.decideEntry(entry);

        assertEquals(RarBackendRoute.Kind.TRY_FIRST_PARTY_RAR4_ENCRYPTED_COMPRESSED_SPLIT_REWRITE, decision.route);
        assertTrue(decision.firstPartyAllowed);
    }

    @Test
    public void routesVisibleHeaderEncryptedRewriteCandidateSeparately() {
        RarArchiveReader.EncryptionInfo encryption = RarArchiveReader.EncryptionInfo.rar4Unsupported(
                new byte[] {1,2,3,4,5,6,7,8});
        RarArchiveReader.RarEntry entry = entry(0x33, false, false, false, false, 4, encryption);

        RarBackendDecision decision = RarBackendRouter.decideEntry(entry);

        assertEquals(RarBackendRoute.Kind.TRY_FIRST_PARTY_RAR4_REWRITE, decision.route);
        assertTrue(decision.firstPartyAllowed);
    }

    private static RarArchiveReader.RarEntry entry(int method,
                                                   boolean solid,
                                                   boolean directory,
                                                   boolean splitBefore,
                                                   boolean splitAfter,
                                                   int rarVersion,
                                                   RarArchiveReader.EncryptionInfo encryption) {
        return new RarArchiveReader.RarEntry(
                directory ? "dir" : "file.bin",
                directory,
                directory ? 0 : 4,
                directory ? 0 : 4,
                0,
                rarVersion,
                method,
                solid,
                splitBefore,
                splitAfter,
                encryption,
                0,
                0);
    }
}
