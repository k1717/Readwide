package com.readwide.manager.archive;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.*;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** Real bundled JNI is required. Synthetic TAR/RAR headers, not producer interoperability evidence. */
@RunWith(AndroidJUnit4.class)
public class NativeArchiveBoundaryInstrumentedTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
    @Before public void requireNative() { assertTrue(LibarchiveNativeBridge.backendStatus(), LibarchiveNativeBridge.isAvailable()); }

    private File tar(String[] names, byte[] types) throws Exception {
        File file = temporary.newFile();
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < names.length; i++) {
                TarArchiveEntry entry = new TarArchiveEntry(names[i], types[i]);
                if (types[i] == TarConstants.LF_LINK || types[i] == TarConstants.LF_SYMLINK) entry.setLinkName("page");
                if (types[i] == TarConstants.LF_NORMAL) entry.setSize(3);
                out.putArchiveEntry(entry);
                if (types[i] == TarConstants.LF_NORMAL) out.write(new byte[]{1,2,3});
                out.closeArchiveEntry();
            }
        }
        return file;
    }
    private boolean extract(File archive, File root) throws Exception {
        return LibarchiveNativeBridge.extractArchive(archive.getPath(), root.getPath(), null, null, null);
    }
    @Test public void relativeRootMarkerAndRepeatedSeparatorsRemainReadable() throws Exception {
        File archive = tar(new String[]{"./", "./chapter//page"}, new byte[]{TarConstants.LF_DIR, TarConstants.LF_NORMAL});
        File root = temporary.newFolder(); assertTrue(extract(archive, root));
        File output = new File(root, "selected");
        assertTrue(LibarchiveNativeBridge.extractEntry(archive.getPath(), "chapter/page", output.getPath(), null, null));
        assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(output.toPath()));
    }
    @Test public void unsafeMemberFailsBulkInsteadOfReportingPartialSuccess() throws Exception {
        File archive = tar(new String[]{"../outside"}, new byte[]{TarConstants.LF_NORMAL});
        File root = temporary.newFolder();
        assertThrows(IOException.class, () -> extract(archive, root));
        assertEquals(0, root.list().length);
    }
    @Test public void regularFileCannotSatisfyNativeDirectoryEntry() throws Exception {
        File archive = tar(new String[]{"chapter/"}, new byte[]{TarConstants.LF_DIR}), root = temporary.newFolder();
        File keep = new File(root, "chapter"); Files.write(keep.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> extract(archive, root));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(keep.toPath()));
    }
    @Test public void linksAndDevicesAreNotListedOrMaterialized() throws Exception {
        File archive = tar(new String[]{"page", "hard", "symbolic", "fifo"},
                new byte[]{TarConstants.LF_NORMAL, TarConstants.LF_LINK, TarConstants.LF_SYMLINK, TarConstants.LF_FIFO});
        Set<String> names = new HashSet<>();
        for (ArchiveSupport.EntryInfo entry : RarLibarchiveFallback.parseListingRows(
                LibarchiveNativeBridge.listEntries(archive.getPath(), null))) names.add(entry.path);
        assertEquals(new HashSet<>(Arrays.asList("page")), names);
        File root = temporary.newFolder(); assertTrue(extract(archive, root));
        assertArrayEquals(new String[]{"page"}, root.list());
    }
    @Test public void specialMemberDoesNotShadowLaterSelectedFile() throws Exception {
        File archive = tar(new String[]{"page", "page"}, new byte[]{TarConstants.LF_LINK, TarConstants.LF_NORMAL});
        File output = temporary.newFile(); Files.write(output.toPath(), new byte[]{42});
        assertTrue(LibarchiveNativeBridge.extractEntry(archive.getPath(), "page", output.getPath(), null, null));
        assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(output.toPath()));
    }
    @Test public void truncatedPayloadRestoresSelectedOutput() throws Exception {
        File archive = tar(new String[]{"page"}, new byte[]{TarConstants.LF_NORMAL});
        try (RandomAccessFile raw = new RandomAccessFile(archive, "rw")) { raw.setLength(513); }
        File output = temporary.newFile(); Files.write(output.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> LibarchiveNativeBridge.extractEntry(
                archive.getPath(), "page", output.getPath(), null, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }
    @Test public void forwardReaderDoesNotPublishSpecialMemberIdentity() throws Exception {
        File archive = tar(new String[]{"hard", "page"}, new byte[]{TarConstants.LF_LINK, TarConstants.LF_NORMAL});
        LibarchiveNativeBridge.ForwardStream reader = LibarchiveNativeBridge.openForwardStream(new String[]{archive.getPath()}, null);
        try {
            LibarchiveNativeBridge.ForwardStreamEntry entry = reader.nextEntry();
            assertNull(entry.path); assertFalse(entry.regularFile);
            assertEquals("page", reader.nextEntry().path);
        } finally { reader.close(); }
    }
    @Test public void callbackSeekRejectsOverflowAndStaysFailed() throws Exception {
        File archive = temporary.newFile(); Files.write(archive.toPath(), new byte[]{1,2,3});
        Class<?> type = Class.forName(LibarchiveNativeBridge.class.getName() + "$VolumeInput");
        Constructor<?> constructor = type.getDeclaredConstructor(String[].class); constructor.setAccessible(true);
        Object input = constructor.newInstance((Object) new String[]{archive.getPath()});
        Method seek = type.getDeclaredMethod("seek", long.class, int.class); seek.setAccessible(true);
        Method close = type.getDeclaredMethod("close"); close.setAccessible(true);
        try {
            assertEquals(1L, seek.invoke(input, 1L, android.system.OsConstants.SEEK_SET));
            InvocationTargetException error = assertThrows(InvocationTargetException.class,
                    () -> seek.invoke(input, Long.MAX_VALUE, android.system.OsConstants.SEEK_CUR));
            assertTrue(error.getCause() instanceof IOException);
            assertSame(error.getCause(), assertThrows(InvocationTargetException.class,
                    () -> seek.invoke(input, 0L, android.system.OsConstants.SEEK_SET)).getCause());
        } finally { close.invoke(input); }
    }

    @Test public void rarLongRedirectionTargetsDoNotConsumeTheNextRecord() throws Exception {
        byte[] target = new byte[130]; Arrays.fill(target, (byte)'a');
        for (int type : new int[]{1, 2, 3, 4}) {
            byte[] redirection = record(concat(vint(5), vint(type), vint(0), vint(target.length), target));
            // The target-length vint uses two bytes; the following record stays separate.
            File archive = rar(concat(redirection, record(new byte[]{99, 0, 0})));
            File output = temporary.newFolder();
            assertTrue(extract(archive, output));
            assertArrayEquals(new String[]{"page"}, output.list());
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(new File(output, "page").toPath()));
        }
    }

    @Test public void rarShortRedirectionCannotBorrowNextRecordBytes() throws Exception {
        byte[][] bad = {
                new byte[]{5},
                new byte[]{5, 1, 0},
                new byte[]{5, 1, 0, 4, 'a'},
                concat(new byte[]{5, 1, 0}, vint(0x100000001L), new byte[]{'a'}),
                new byte[]{5, 1, 2, 1, 'a'},
                new byte[]{5, 1, 0, 0}
        };
        for (byte[] malformed : bad) {
            File archive = rar(concat(record(malformed), record(new byte[]{99, 'b', 'c', 'd', 0})));
            assertNativeSelectedFailurePreservesOutput(archive);
        }
    }

    @Test public void rarExtraRecordSizeAndDuplicateRedirectionsAreRejected() throws Exception {
        byte[] valid = record(new byte[]{5, 1, 0, 1, 'a'});
        byte[][] bad = {
                new byte[]{0}, // No room for a record type.
                new byte[]{1, (byte)0x80, 0}, // Type vint crosses its record boundary.
                concat(vint(Long.MAX_VALUE), new byte[]{99}),
                concat(valid, valid),
                record(new byte[]{5, 5, 0, 1, 'a'}), // File-copy resolution remains unsupported.
                record(new byte[]{5, 6, 0, 1, 'a'})
        };
        for (byte[] malformed : bad) assertNativeSelectedFailurePreservesOutput(rar(malformed));
    }

    @Test public void rarRedirectionExtensionsAreSkippedWithinTheirOwnRecord() throws Exception {
        File archive = rar(concat(record(new byte[]{5, 3, 0, 1, 'a', 0, 0}), record(new byte[]{99, 0})));
        File output = temporary.newFolder();
        assertTrue(extract(archive, output));
        assertArrayEquals(new String[]{"page"}, output.list());
    }

    private void assertNativeSelectedFailurePreservesOutput(File archive) throws Exception {
        File output = temporary.newFile(); Files.write(output.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> LibarchiveNativeBridge.extractEntry(
                archive.getPath(), "page", output.getPath(), null, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    private File rar(byte[] extra) throws Exception {
        File archive = temporary.newFile();
        try (OutputStream output = new FileOutputStream(archive)) {
            output.write(new byte[]{0x52, 0x61, 0x72, 0x21, 0x1a, 7, 1, 0});
            output.write(rarHeader(new byte[]{1, 0, 0}));
            rarStored(output, "redirected", new byte[0], extra);
            rarStored(output, "page", new byte[]{1, 2, 3}, new byte[0]);
            output.write(rarHeader(new byte[]{5, 0, 0}));
            output.write(new byte[32]); // Lookahead padding after the end header.
        }
        return archive;
    }

    private static void rarStored(OutputStream output, String name, byte[] data, byte[] extra) throws Exception {
        byte[] path = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.write(rarHeader(concat(vint(2), vint(3), vint(extra.length), vint(data.length),
                vint(4), vint(data.length), vint(33188), crc(data), vint(0), vint(1), vint(path.length), path, extra)));
        output.write(data);
    }

    private static byte[] rarHeader(byte[] fields) throws Exception {
        byte[] covered = concat(vint(fields.length), fields);
        return concat(crc(covered), covered);
    }

    private static byte[] crc(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(data);
        long value = crc.getValue();
        return new byte[]{(byte)value, (byte)(value >> 8), (byte)(value >> 16), (byte)(value >> 24)};
    }

    private static byte[] record(byte[] fields) throws Exception { return concat(vint(fields.length), fields); }

    private static byte[] vint(long value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        do {
            int bits = (int)(value & 0x7f); value >>>= 7;
            output.write(bits | (value == 0 ? 0 : 0x80));
        } while (value != 0);
        return output.toByteArray();
    }

    private static byte[] concat(byte[]... parts) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) output.write(part);
        return output.toByteArray();
    }
}
