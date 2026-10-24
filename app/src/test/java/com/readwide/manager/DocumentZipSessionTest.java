package com.readwide.manager;

import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DocumentZipSessionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private ZipFile openZip() throws Exception {
        File file = temporary.newFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            out.putNextEntry(new ZipEntry("chapter"));
            out.write(42);
            out.closeEntry();
        }
        return new ZipFile(file);
    }

    @Test public void retirementWaitsForLastLeaseButRejectsNewRequests() throws Exception {
        ZipFile zip = openZip();
        try (DocumentZipSession session = new DocumentZipSession(zip);
             DocumentZipSession.Lease first = session.acquire();
             DocumentZipSession.Lease second = session.acquire()) {
            session.close();
            session.close();
            assertNull(session.acquire());
            first.close();
            first.close();
            try (java.io.InputStream input = second.zip().getInputStream(zip.getEntry("chapter"))) {
                assertEquals(42, input.read());
            }
            second.close();
            try { zip.getEntry("chapter"); fail("Final lease must close the ZIP"); }
            catch (IllegalStateException expected) { }
        }
    }

    @Test public void retiringOldSessionDoesNotCloseReplacement() throws Exception {
        try (DocumentZipSession old = new DocumentZipSession(openZip());
             DocumentZipSession next = new DocumentZipSession(openZip())) {
            old.close();
            try (DocumentZipSession.Lease lease = next.acquire()) {
                assertNotNull(lease.zip().getEntry("chapter"));
            }
        }
    }

    @Test public void workerResourceFailureStillReleasesRetiredLease() throws Exception {
        for (Error failure : new Error[] {
                new OutOfMemoryError("injected"), new StackOverflowError("injected")}) {
            ZipFile zip = openZip();
            try (DocumentZipSession session = new DocumentZipSession(zip)) {
                try (DocumentZipSession.Lease lease = session.acquire()) {
                    assertSame(zip, lease.zip());
                    session.close();
                    throw failure;
                } catch (OutOfMemoryError | StackOverflowError caught) {
                    assertSame(failure, caught);
                }
                assertNull(session.acquire());
                try { zip.getEntry("chapter"); fail("Failure must not retain the retired ZIP"); }
                catch (IllegalStateException expected) { }
            }
        }
    }

    @Test public void failedWorkDoesNotRetireTheCurrentSession() throws Exception {
        try (DocumentZipSession session = new DocumentZipSession(openZip())) {
            try (DocumentZipSession.Lease lease = session.acquire()) {
                assertNotNull(lease.zip().getEntry("chapter"));
                throw new IllegalStateException("injected worker failure");
            } catch (IllegalStateException expected) { }
            try (DocumentZipSession.Lease retry = session.acquire()) {
                assertNotNull(retry);
                assertNotNull(retry.zip().getEntry("chapter"));
            }
        }
    }
}
