package com.readwide.manager.archive;

import static org.junit.Assert.*;

import com.readwide.manager.util.FileOperationProgress;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ArchiveBulkSourceChangeTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final byte[] DATA = Round4Fixtures.data(70003);
    private static final byte[] ORIGINAL = {42};

    @Test public void unchangedEggAndAlzStoredArchivesStillExtract() throws Exception {
        for (boolean egg : new boolean[]{true, false}) {
            for (boolean overwrite : new boolean[]{false, true}) {
                accept(eggOrAlz(egg, false), overwrite);
            }
        }
    }

    @Test public void unchangedEncryptedSplitEggAndAlzStillExtract() throws Exception {
        for (boolean egg : new boolean[]{true, false}) {
            for (boolean overwrite : new boolean[]{false, true}) {
                accept(eggOrAlz(egg, true), overwrite);
            }
        }
    }

    @Test public void unchangedStoredRarStillExtracts() throws Exception {
        for (boolean overwrite : new boolean[]{false, true}) accept(rar(), overwrite);
    }

    @Test public void eggAndAlzChangesAfterListingCannotPublishNewDirectory() throws Exception {
        for (boolean egg : new boolean[]{true, false}) rejectAfterListing(eggOrAlz(egg, false), false, 0);
    }

    @Test public void eggAndAlzChangesAfterListingKeepExistingDestinationTree() throws Exception {
        for (boolean egg : new boolean[]{true, false}) rejectAfterListing(eggOrAlz(egg, false), true, 0);
    }

    @Test public void encryptedSplitEggAndAlzChangesAfterListingCannotPublishOutput() throws Exception {
        for (boolean egg : new boolean[]{true, false}) {
            for (boolean overwrite : new boolean[]{false, true}) {
                for (int changedPart : new int[]{0, 2}) {
                    rejectAfterListing(eggOrAlz(egg, true), overwrite, changedPart);
                }
            }
        }
    }

    @Test public void storedRarChangeAfterListingCannotPublishNewDirectory() throws Exception {
        rejectAfterListing(rar(), false, 0);
    }

    @Test public void storedRarChangeAfterListingKeepsExistingDestinationTree() throws Exception {
        rejectAfterListing(rar(), true, 0);
    }

    private void accept(Fixture fixture, boolean overwrite) throws Exception {
        File directory = destination(overwrite);
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                fixture.selected, directory, overwrite, fixture.password, null);
        assertTrue(result.detail, result.success);
        assertArrayEquals(DATA, Files.readAllBytes(new File(directory, "data.bin").toPath()));
        assertArrayEquals(new String[]{"data.bin"}, directory.list());
        assertArrayEquals(new String[]{"output"}, directory.getParentFile().list());
    }

    private void rejectAfterListing(Fixture fixture, boolean overwrite, int changedPart) throws Exception {
        File directory = destination(overwrite);
        File source = fixture.parts.get(changedPart);
        boolean[] mutated = {false};
        FileOperationProgress progress = new FileOperationProgress("archive", snapshot -> {
            if (!mutated[0] && snapshot.totalBytes == DATA.length) {
                assertEquals(0L, snapshot.doneBytes);
                mutated[0] = true;
                assertTrue(source.setLastModified(source.lastModified() + 5000));
            }
        });
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                fixture.selected, directory, overwrite, fixture.password, progress);
        assertTrue("The fixture must reach the post-listing callback", mutated[0]);
        assertFalse(result.success);
        assertNotNull(result.detail);
        assertTrue(result.detail, result.detail.contains("source") && result.detail.contains("changed"));
        if (overwrite) {
            assertArrayEquals(ORIGINAL, Files.readAllBytes(new File(directory, "keep").toPath()));
            assertArrayEquals(new String[]{"keep"}, directory.list());
        } else {
            assertFalse(directory.exists());
        }
        assertArrayEquals(overwrite ? new String[]{"output"} : new String[0], directory.getParentFile().list());
    }

    private File destination(boolean overwrite) throws IOException {
        File directory = new File(temporary.newFolder(), "output");
        if (overwrite) {
            assertTrue(directory.mkdir());
            Files.write(new File(directory, "keep").toPath(), ORIGINAL);
        }
        return directory;
    }

    private Fixture eggOrAlz(boolean egg, boolean encryptedSplit) throws Exception {
        File directory = temporary.newFolder();
        byte[] bytes = egg
                ? Round4Fixtures.egg(DATA, encryptedSplit ? 1 : 0, encryptedSplit)
                : Round4Fixtures.alz(DATA, encryptedSplit ? 2 : 0, encryptedSplit);
        List<File> parts;
        if (encryptedSplit) {
            parts = egg ? Round4Fixtures.splitEgg(directory, "book", bytes, 3)
                    : Round4Fixtures.splitAlz(directory, "book", bytes, 3);
        } else {
            parts = Collections.singletonList(Round4Fixtures.write(directory,
                    egg ? "book.egg" : "book.alz", bytes));
        }
        return new Fixture(parts, encryptedSplit ? Round4Fixtures.PASSWORD.clone() : null);
    }

    private Fixture rar() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0});
        header(out, 0x73, 0, new byte[6]);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        Round4Fixtures.le(body, DATA.length, 4);
        Round4Fixtures.le(body, DATA.length, 4);
        body.write(1);
        Round4Fixtures.le(body, Round4Fixtures.crc(DATA), 4);
        Round4Fixtures.le(body, 0, 4);
        body.write(29);
        body.write(0x30);
        byte[] name = "data.bin".getBytes(StandardCharsets.UTF_8);
        Round4Fixtures.le(body, name.length, 2);
        Round4Fixtures.le(body, 0, 4);
        body.write(name);
        header(out, 0x74, 0x8000, body.toByteArray());
        out.write(DATA);
        header(out, 0x7b, 0, new byte[0]);
        File file = Round4Fixtures.write(temporary.newFolder(), "book.rar", out.toByteArray());
        return new Fixture(Collections.singletonList(file), null);
    }

    private static void header(ByteArrayOutputStream out, int type, int flags, byte[] body)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(type);
        Round4Fixtures.le(bytes, flags, 2);
        Round4Fixtures.le(bytes, 7 + body.length, 2);
        bytes.write(body);
        byte[] data = bytes.toByteArray();
        Round4Fixtures.le(out, Round4Fixtures.crc(data) & 0xffff, 2);
        out.write(data);
    }

    private static final class Fixture {
        final File selected;
        final List<File> parts;
        final char[] password;

        Fixture(List<File> parts, char[] password) {
            this.parts = parts;
            this.selected = parts.get(0);
            this.password = password;
        }
    }
}
