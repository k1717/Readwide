package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;

public class Rar4SplitHeaderScanTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void bothScannersSkipNonFileLongBlockPayload() throws Exception {
        Fixture fixture = fixture(0x8000, new byte[]{12, 0, 0, 0}, new byte[12]);
        assertFoundByBoth(fixture);
    }

    @Test public void bothScannersAcceptEmptyLongBlock() throws Exception {
        assertFoundByBoth(fixture(0x8000, new byte[4], new byte[0]));
    }

    @Test public void ordinaryBlockDoesNotInterpretHeaderBytesAsPayloadSize() throws Exception {
        assertFoundByBoth(fixture(0, new byte[]{127, 0, 0, 0}, new byte[0]));
    }

    @Test public void missingLongBlockSizeIsRejected() throws Exception {
        assertRejectedByBoth(fixture(0x8000, new byte[3], new byte[0]),
                "Invalid RAR4 long block");
    }

    @Test public void longBlockPayloadPastVolumeIsRejected() throws Exception {
        assertRejectedByBoth(fixture(0x8000, new byte[]{-1, -1, -1, -1}, new byte[0]),
                "Invalid RAR4 data size");
    }

    private void assertFoundByBoth(Fixture fixture) throws Exception {
        for (Class<?> owner : owners()) {
            Object found = scan(owner, fixture);
            Field header = found.getClass().getDeclaredField("headerData");
            header.setAccessible(true);
            assertArrayEquals(fixture.fileHeader, (byte[]) header.get(found));
        }
    }

    private void assertRejectedByBoth(Fixture fixture, String expected) throws Exception {
        for (Class<?> owner : owners()) {
            try {
                scan(owner, fixture);
                fail(owner.getSimpleName() + " accepted invalid long block");
            } catch (IOException failure) {
                assertTrue(failure.getMessage(), failure.getMessage().contains(expected));
            }
        }
    }

    private static Class<?>[] owners() {
        return new Class<?>[]{Rar4SplitPayloadRewriter.class,
                Rar4EncryptedSplitPayloadRewriter.class};
    }

    private static Object scan(Class<?> owner, Fixture fixture) throws Exception {
        Method method = owner.getDeclaredMethod("findFileHeaderForDataOffset", File.class, long.class, com.readwide.manager.util.FileOperationProgress.class);
        method.setAccessible(true);
        try {
            return method.invoke(null, fixture.archive, fixture.dataOffset, null);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw wrapped;
        }
    }

    private Fixture fixture(int flags, byte[] blockHeader, byte[] payload) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[]{0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0});
        header(bytes, 0x73, 0, new byte[6]);
        header(bytes, 0x78, flags, blockHeader);
        bytes.write(payload);
        byte[] fileHeader = new byte[26];
        fileHeader[0] = 3;
        fileHeader[4] = 9;
        fileHeader[17] = 29;
        fileHeader[18] = 0x33;
        fileHeader[19] = 1;
        fileHeader[25] = 'a';
        header(bytes, 0x74, 2, fileHeader);
        long dataOffset = bytes.size();
        bytes.write(new byte[]{1, 2, 3});
        File archive = temporary.newFile();
        Files.write(archive.toPath(), bytes.toByteArray());
        return new Fixture(archive, dataOffset, fileHeader);
    }

    private static void header(ByteArrayOutputStream out, int type, int flags, byte[] data)
            throws IOException {
        // Source CRC is advisory in these scanners; rewritten CRCs are tested elsewhere.
        out.write(0);
        out.write(0);
        out.write(type);
        out.write(flags & 255);
        out.write(flags >>> 8);
        int size = data.length + 7;
        out.write(size & 255);
        out.write(size >>> 8);
        out.write(data);
    }

    private static final class Fixture {
        final File archive;
        final long dataOffset;
        final byte[] fileHeader;

        Fixture(File archive, long dataOffset, byte[] fileHeader) {
            this.archive = archive;
            this.dataOffset = dataOffset;
            this.fileHeader = fileHeader;
        }
    }
}
