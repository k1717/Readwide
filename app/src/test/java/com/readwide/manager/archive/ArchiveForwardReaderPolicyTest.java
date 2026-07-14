package com.readwide.manager.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ArchiveForwardReaderPolicyTest {
    @Test
    public void plainSpecialSevenZUsesLibarchiveForwardWhenAvailable() {
        assertTrue(ArchiveSupport.shouldUseLibarchiveForwardForSevenZ(true, false, true));
    }

    @Test
    public void normalOrEncryptedSevenZKeepsDedicatedPath() {
        assertFalse(ArchiveSupport.shouldUseLibarchiveForwardForSevenZ(true, false, false));
        assertFalse(ArchiveSupport.shouldUseLibarchiveForwardForSevenZ(true, true, true));
        assertFalse(ArchiveSupport.shouldUseLibarchiveForwardForSevenZ(false, false, true));
    }

    @Test
    public void plainSplitSevenZUsesVolumeAwareLibarchiveForwardReader() {
        assertTrue(ArchiveSupport.shouldUseLibarchiveForwardForSevenZ(
                true, false, false, true));
        assertFalse(ArchiveSupport.shouldUseLibarchiveForwardForSevenZ(
                true, true, false, true));
        assertFalse(ArchiveSupport.shouldUseLibarchiveForwardForSevenZ(
                false, false, false, true));
    }

    @Test
    public void libarchiveForwardReaderDoesNotHeaderSkipSolidState() {
        assertFalse(LibarchiveForwardReader.canSkipUnreadEntryWithoutDecode());
    }
}
