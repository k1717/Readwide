package com.readwide.manager.util;

import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public class BackupImportTransactionTest {
    @Test public void successfulCommitDoesNotRunRollback() throws Exception {
        List<String> calls = new ArrayList<>();
        BackupImportTransaction.run(Arrays.asList(
                BackupImportTransaction.step(() -> calls.add("a"), () -> calls.add("undo-a")),
                BackupImportTransaction.step(() -> calls.add("b"), () -> calls.add("undo-b"))));
        assertEquals(Arrays.asList("a", "b"), calls);
    }

    @Test public void failingStepIsRolledBackFirstAndLaterStepsDoNotRun() {
        List<String> calls = new ArrayList<>();
        IOException expected = new IOException("disk full");
        try {
            BackupImportTransaction.run(Arrays.asList(
                    BackupImportTransaction.step(() -> calls.add("a"), () -> calls.add("undo-a")),
                    BackupImportTransaction.step(() -> { calls.add("partial-b"); throw expected; }, () -> calls.add("undo-b")),
                    BackupImportTransaction.step(() -> calls.add("c"), () -> calls.add("undo-c"))));
            fail("Expected commit failure");
        } catch (Exception failure) { assertSame(expected, failure); }
        assertEquals(Arrays.asList("a", "partial-b", "undo-b", "undo-a"), calls);
    }

    @Test public void failureInFirstStepStillRestoresThatStep() {
        int[] value = {3};
        try {
            BackupImportTransaction.run(Collections.singletonList(BackupImportTransaction.step(
                    () -> { value[0] = 9; throw new IllegalArgumentException("failed"); }, () -> value[0] = 3)));
            fail("Expected failure");
        } catch (Exception failure) { assertTrue(failure instanceof IllegalArgumentException); }
        assertEquals(3, value[0]);
    }

    @Test public void rollbackFailureIsReportedWithoutSkippingOtherUndos() {
        List<String> calls = new ArrayList<>();
        IOException expected = new IOException("commit");
        IOException undo = new IOException("rollback");
        try {
            BackupImportTransaction.run(Arrays.asList(
                    BackupImportTransaction.step(() -> {}, () -> calls.add("restored-a")),
                    BackupImportTransaction.step(() -> { throw expected; }, () -> { throw undo; })));
            fail("Expected failure");
        } catch (Exception failure) {
            assertSame(expected, failure.getCause());
            assertArrayEquals(new Throwable[]{undo}, expected.getSuppressed());
            assertTrue(failure.getMessage().contains("restoring previous data also failed"));
        }
        assertEquals(Collections.singletonList("restored-a"), calls);
    }

    @Test public void existingSuppressedCommitErrorIsNotMisreportedAsRollbackFailure() {
        IOException expected = new IOException("write");
        expected.addSuppressed(new IOException("close"));
        try {
            BackupImportTransaction.run(Collections.singletonList(
                    BackupImportTransaction.step(() -> { throw expected; }, () -> {})));
            fail("Expected failure");
        } catch (Exception failure) { assertSame(expected, failure); }
    }

    @Test public void emptyTransactionIsAllowed() throws Exception {
        BackupImportTransaction.run(Collections.emptyList());
    }
}
