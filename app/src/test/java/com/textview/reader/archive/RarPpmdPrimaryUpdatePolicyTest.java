package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RarPpmdPrimaryUpdatePolicyTest {
    @Test
    public void primaryUpdatePoliciesExposeDiagnosticKnobs() throws Exception {
        RarPpmdPrimaryUpdatePolicy unrar = RarPpmdPrimaryUpdatePolicy.unrarShaped();
        assertEquals("unrar-shaped", unrar.name());
        assertEquals(4, unrar.frequencyDelta());
        assertTrue(unrar.promoteOneStepIfMoreFrequent());
        assertTrue(unrar.rescaleIfNeeded());

        RarPpmdPrimaryUpdatePolicy frozen = RarPpmdPrimaryUpdatePolicy.frozen();
        assertEquals("frozen", frozen.name());
        assertEquals(0, frozen.frequencyDelta());
        assertFalse(frozen.promoteOneStepIfMoreFrequent());
        assertFalse(frozen.rescaleIfNeeded());
    }

    @Test
    public void diagnosticOptionsCarryPrimaryUpdatePolicy() throws Exception {
        assertTrue(RarPpmdDiagnosticOptions.rarPrimaryRoot().diagnostic()
                .contains("primaryUpdatePolicy={name=unrar-shaped"));
        assertTrue(RarPpmdDiagnosticOptions.rarPrimaryRootFrozen().diagnostic()
                .contains("primaryUpdatePolicy={name=frozen"));
    }
}
