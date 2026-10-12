package com.readwide.manager.archive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Mutations of the existing oracle-validated ALZ bitstream, not a second decoder. */
public final class AlzSelectorValidationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static byte[] original() { return Base64.getDecoder().decode(Round4Fixtures.ALZ_BZ_SINGLE_BLOCK_B64); }
    private static byte[] plain() { return "ALZ bzip2 single block payload. ".repeat(40).getBytes(StandardCharsets.US_ASCII); }
    @Test public void canonicalSelectorStillDecodesOriginalBytes() throws Exception {
        try (InputStream input = new AlzBzip2InputStream(new ByteArrayInputStream(original()))) {
            assertArrayEquals(plain(), input.readAllBytes());
        }
    }
    @Test public void selectorUnary256DoesNotWrapToValidByte() throws Exception { rejectsSelector(256); }
    @Test public void selectorUnary512DoesNotWrapToValidByte() throws Exception { rejectsSelector(512); }
    @Test public void selectorMustNameAnActiveGroup() throws Exception {
        String bits = bits(original()); int position = tableOffset(bits);
        int groups = Integer.parseInt(bits.substring(position, position + 3), 2);
        rejectsSelector(groups);
    }
    @Test public void publicAlzSelectedRejectsWrappedSelectorAndRestoresTarget() throws Exception {
        File archive = new File(temporary.newFolder(), "bad.alz"), target = temporary.newFile();
        Files.write(archive.toPath(), Round4Fixtures.alzStored(plain(), 1, changedSelector(256), false));
        Files.write(target.toPath(), new byte[]{42});
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(archive, "data.bin", target, null);
        assertFalse("Malformed selector was accepted with a valid output CRC", result.success);
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(target.toPath()));
    }
    @Test public void publicAlzBulkRejectsWrappedSelectorAndRestoresTarget() throws Exception {
        File archive = new File(temporary.newFolder(), "bad.alz"), directory = temporary.newFolder();
        File target = new File(directory, "data.bin"); Files.write(target.toPath(), new byte[]{42});
        Files.write(archive.toPath(), Round4Fixtures.alzStored(plain(), 1, changedSelector(512), false));
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(archive, directory, true, null, null);
        assertFalse("Malformed selector was accepted with a valid output CRC", result.success);
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(target.toPath()));
    }
    @Test public void zeroDeclaredSelectorsAreRejectedBeforePayload() throws Exception {
        String bits = bits(original()); int position = tableOffset(bits) + 3;
        int count = Integer.parseInt(bits.substring(position, position + 15), 2), end = position + 15;
        for (int i = 0; i < count; i++) { while (bits.charAt(end++) == '1') {} }
        byte[] malformed = bytes(bits.substring(0, position) + "0".repeat(15) + bits.substring(end));
        IOException failure = assertThrows(IOException.class, () -> decode(malformed));
        assertTrue(failure.getMessage(), failure.getMessage().toLowerCase().contains("selector"));
    }
    private void rejectsSelector(int extra) throws Exception {
        IOException failure = assertThrows(IOException.class, () -> decode(changedSelector(extra)));
        assertTrue(failure.getMessage(), failure.getMessage().toLowerCase().contains("selector"));
    }
    private static void decode(byte[] packed) throws IOException {
        try (InputStream input = new AlzBzip2InputStream(new ByteArrayInputStream(packed))) { input.readAllBytes(); }
    }
    private static byte[] changedSelector(int extra) {
        String bits = bits(original()); int position = tableOffset(bits) + 3 + 15;
        return bytes(bits.substring(0, position) + "1".repeat(extra) + bits.substring(position));
    }
    private static int tableOffset(String bits) {
        int position = 32 + 24; // DLZ block magic and BWT origin, before the in-use bitmap.
        String groups = bits.substring(position, position + 16); position += 16;
        for (int i = 0; i < 16; i++) if (groups.charAt(i) == '1') position += 16;
        return position;
    }
    private static String bits(byte[] data) {
        StringBuilder bits = new StringBuilder(data.length * 8);
        for (byte value : data) for (int i = 7; i >= 0; i--) bits.append((value >>> i) & 1);
        return bits.toString();
    }
    private static byte[] bytes(String bits) {
        byte[] data = new byte[(bits.length() + 7) / 8];
        for (int i = 0; i < bits.length(); i++) if (bits.charAt(i) == '1') data[i / 8] |= 1 << (7 - i % 8);
        return data;
    }
}
