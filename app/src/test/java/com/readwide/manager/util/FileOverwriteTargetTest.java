package com.readwide.manager.util;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileOverwriteTargetTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void absentTargetCannotBeApprovedForOverwrite() {
        assertThrows(IOException.class, () -> FileSystemOps.captureOverwriteTarget(
                new File(temp.getRoot(), "missing")));
    }

    @Test public void changedChildAfterApprovalIsRejected() throws Exception {
        File target = temp.newFolder("target");
        assumeIdentity(target);
        FileSystemOps.OverwriteTarget approval = FileSystemOps.captureOverwriteTarget(target);
        assertTrue(new File(target, "arrived").mkdir());
        assertThrows(IOException.class, () -> approval.recheck(target));
        assertTrue(new File(target, "arrived").isDirectory());
    }

    @Test public void approvalCannotBeReusedForAnotherDestination() throws Exception {
        File target = temp.newFolder("target");
        assumeIdentity(target);
        FileSystemOps.OverwriteTarget approval = FileSystemOps.captureOverwriteTarget(target);
        approval.recheck(target);
        assertThrows(IOException.class, () -> approval.recheck(new File(temp.getRoot(), "another")));
    }

    private static void assumeIdentity(File directory) throws IOException {
        FileTreeWalk.walk(directory, () -> true, entry -> {
            Assume.assumeNotNull(entry.identity);
            return false;
        });
    }
}
