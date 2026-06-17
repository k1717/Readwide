package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RarPpmdPrimaryUpdatePolicyTest {
    @Test
    public void primaryUpdatePoliciesExposeDiagnosticKnobs() throws Exception {
        RarPpmdPrimaryUpdatePolicy reference = RarPpmdPrimaryUpdatePolicy.referenceShaped();
        assertEquals("reference-shaped", reference.name());
        assertEquals(4, reference.frequencyDelta());
        assertTrue(reference.promoteOneStepIfMoreFrequent());
        assertTrue(reference.rescaleIfNeeded());

        RarPpmdPrimaryUpdatePolicy frozen = RarPpmdPrimaryUpdatePolicy.frozen();
        assertEquals("frozen", frozen.name());
        assertEquals(0, frozen.frequencyDelta());
        assertFalse(frozen.promoteOneStepIfMoreFrequent());
        assertFalse(frozen.rescaleIfNeeded());
    }

    @Test
    public void diagnosticOptionsCarryPrimaryUpdatePolicy() throws Exception {
        assertTrue(RarPpmdDiagnosticOptions.rarPrimaryRoot().diagnostic()
                .contains("primaryUpdatePolicy={name=reference-shaped"));
        assertTrue(RarPpmdDiagnosticOptions.rarPrimaryRootFrozen().diagnostic()
                .contains("primaryUpdatePolicy={name=frozen"));
    }
}
