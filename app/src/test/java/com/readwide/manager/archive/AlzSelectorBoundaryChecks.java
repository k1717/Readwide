package com.readwide.manager.archive;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decoder-level regression checks shared by JUnit and the dependency-free host runner.
 * Complete synthetic DLZ streams exercise real decoding, not a second selector decoder.
 * Existing single/multi-block fixtures provide a separate compatibility check.
 */
final class AlzSelectorBoundaryChecks {
    private static final int MAX_SELECTORS = 18002;
    private static final byte[] A = {'A'};
    private static final String END = fixed(0x444c5a02L, 32);

    @FunctionalInterface interface Check { void run() throws Exception; }

    private static Map<String, Check> checks() {
        Map<String, Check> checks = new LinkedHashMap<>();
        checks.put("existingSingleBlockStillDecodes", AlzSelectorBoundaryChecks::existingSingleBlockStillDecodes);
        checks.put("existingThreeBlocksStillDecode", AlzSelectorBoundaryChecks::existingThreeBlocksStillDecode);
        checks.put("everyActiveSelectorIsAccepted", AlzSelectorBoundaryChecks::everyActiveSelectorIsAccepted);
        checks.put("validUpperSelectorSurvivesShortReads", AlzSelectorBoundaryChecks::validUpperSelectorSurvivesShortReads);
        checks.put("wrappedSelectorsAreRejected", AlzSelectorBoundaryChecks::wrappedSelectorsAreRejected);
        checks.put("realFixtureWrapMutationsAreRejected", AlzSelectorBoundaryChecks::realFixtureWrapMutationsAreRejected);
        checks.put("inactiveSelectorsAreRejected", AlzSelectorBoundaryChecks::inactiveSelectorsAreRejected);
        checks.put("zeroSelectorsAreRejected", AlzSelectorBoundaryChecks::zeroSelectorsAreRejected);
        checks.put("zeroGroupsAreRejected", AlzSelectorBoundaryChecks::zeroGroupsAreRejected);
        checks.put("oneGroupIsRejected", AlzSelectorBoundaryChecks::oneGroupIsRejected);
        checks.put("sevenGroupsAreRejected", AlzSelectorBoundaryChecks::sevenGroupsAreRejected);
        checks.put("validExcessSelectorsRemainCompatible", AlzSelectorBoundaryChecks::validExcessSelectorsRemainCompatible);
        checks.put("invalidDiscardedSelectorsAreRejected", AlzSelectorBoundaryChecks::invalidDiscardedSelectorsAreRejected);
        checks.put("unterminatedUnaryFailsAtActiveBound", AlzSelectorBoundaryChecks::unterminatedUnaryFailsAtActiveBound);
        checks.put("multipleBlocksResetSelectorTables", AlzSelectorBoundaryChecks::multipleBlocksResetSelectorTables);
        checks.put("laterBlockFailureRemainsSticky", AlzSelectorBoundaryChecks::laterBlockFailureRemainsSticky);
        checks.put("laterBlockZeroSelectorsAreRejected", AlzSelectorBoundaryChecks::laterBlockZeroSelectorsAreRejected);
        checks.put("truncatedSelectorListIsRejected", AlzSelectorBoundaryChecks::truncatedSelectorListIsRejected);
        checks.put("emptyStreamRemainsValid", AlzSelectorBoundaryChecks::emptyStreamRemainsValid);
        return checks;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--probe".equals(args[0])) { probe(); return; }
        int passed = 0, failed = 0;
        for (Map.Entry<String, Check> entry : checks().entrySet()) {
            try {
                entry.getValue().run();
                passed++;
                System.out.println("PASS " + entry.getKey());
            } catch (Throwable error) {
                failed++;
                System.out.println("FAIL " + entry.getKey());
                error.printStackTrace(System.out);
            }
        }
        System.out.println("SUMMARY passed=" + passed + " failed=" + failed + " total=" + (passed + failed));
        if (failed != 0) System.exit(1);
    }

    static void existingSingleBlockStillDecodes() throws Exception {
        byte[] expected = "ALZ bzip2 single block payload. ".repeat(40).getBytes(StandardCharsets.US_ASCII);
        equal(expected, decode(original()), "existing single-block fixture");
    }

    static void existingThreeBlocksStillDecode() throws Exception {
        byte[] expected = new byte[260000];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) (33 + i % 87);
        byte[] packed = Base64.getDecoder().decode(Round6Fixtures.MULTI_BZIP);
        equal(expected, decode(packed), "existing 260000-byte, three-block fixture");
    }

    static void everyActiveSelectorIsAccepted() throws Exception {
        for (int groups = 2; groups <= 6; groups++) {
            for (int selector = 0; selector < groups; selector++) {
                equal(A, decode(stream(groups, 1, 0, selector)), "groups=" + groups + " selector=" + selector);
            }
        }
    }

    static void validUpperSelectorSurvivesShortReads() throws Exception {
        byte[] packed = stream(6, 1, 0, 5);
        for (int limit = 1; limit <= 19; limit++) {
            final int cap = limit;
            try (InputStream source = new ByteArrayInputStream(packed) {
                @Override public synchronized int read(byte[] b, int off, int len) {
                    return super.read(b, off, Math.min(cap, len));
                }
            }; InputStream decoded = new AlzBzip2InputStream(source)) {
                equal(A, decoded.readAllBytes(), "compressed chunk limit=" + cap);
            }
        }
    }

    static void wrappedSelectorsAreRejected() throws Exception {
        for (int groups = 2; groups <= 6; groups++) {
            for (int selector : new int[]{255, 256, 257, 511, 512, 513, 768, 1024}) {
                final byte[] packed = stream(groups, 1, 0, selector);
                rejected("selectorMtf", () -> decode(packed));
            }
        }
    }

    static void realFixtureWrapMutationsAreRejected() throws Exception {
        for (int extra : new int[]{256, 512, 768, 1024}) {
            byte[] packed = realMutation(extra);
            rejected("selectorMtf", () -> decode(packed));
        }
    }

    static void inactiveSelectorsAreRejected() throws Exception {
        for (int groups = 2; groups <= 6; groups++) {
            for (int selector = groups; selector <= 6; selector++) {
                byte[] packed = stream(groups, 1, 0, selector);
                rejected("selectorMtf", () -> decode(packed));
            }
        }
    }

    static void zeroSelectorsAreRejected() throws Exception {
        for (int groups = 2; groups <= 6; groups++) {
            byte[] packed = stream(groups, 0, -1, 0);
            rejected("selector count", () -> decode(packed));
        }
    }

    static void zeroGroupsAreRejected() throws Exception {
        rejected("group count", () -> decode(stream(0, 1, 0, 0)));
    }
    static void oneGroupIsRejected() throws Exception {
        rejected("group count", () -> decode(stream(1, 1, 0, 0)));
    }
    static void sevenGroupsAreRejected() throws Exception {
        rejected("group count", () -> decode(stream(7, 1, 0, 0)));
    }

    static void validExcessSelectorsRemainCompatible() throws Exception {
        for (int groups : new int[]{2, 6}) {
            for (int count : new int[]{MAX_SELECTORS, MAX_SELECTORS + 1, 32767}) {
                equal(A, decode(stream(groups, count, count - 1, groups - 1)),
                        "valid excess selectors: groups=" + groups + " count=" + count);
            }
        }
    }

    static void invalidDiscardedSelectorsAreRejected() throws Exception {
        for (int badIndex : new int[]{MAX_SELECTORS - 1, MAX_SELECTORS, 32766}) {
            for (int invalid : new int[]{2, 256, 512}) {
                byte[] packed = stream(2, badIndex + 1, badIndex, invalid);
                rejected("selectorMtf", () -> decode(packed));
            }
        }
    }

    static void unterminatedUnaryFailsAtActiveBound() throws Exception {
        for (int groups = 2; groups <= 6; groups++) {
            String prefix = header(groups, 1);
            // There is NO zero terminator. A one-byte-at-a-time source makes the
            // consumption bound observable despite the decoder's input buffer.
            byte[] offered = bytes(prefix + "1".repeat(groups + 16), '1');
            int allowedBytes = (prefix.length() + groups + 7) / 8;
            final int[] consumed = {0};
            try (InputStream source = new InputStream() {
                @Override public int read() throws IOException {
                    if (consumed[0] >= allowedBytes) throw new IOException("read beyond active selector bound");
                    return offered[consumed[0]++] & 255;
                }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (len == 0) return 0;
                    b[off] = (byte) read();
                    return 1;
                }
            }) {
                rejected("selectorMtf", () -> {
                    try (InputStream decoded = new AlzBzip2InputStream(source)) { decoded.readAllBytes(); }
                });
                require(consumed[0] == allowedBytes, "expected immediate failure at group bound");
            }
        }
    }

    static void multipleBlocksResetSelectorTables() throws Exception {
        StringBuilder bits = new StringBuilder();
        byte[] expected = new byte[129];
        Arrays.fill(expected, (byte) 'A');
        for (int i = 0; i < expected.length; i++) {
            int groups = 2 + i % 5;
            bits.append(block(groups, 1, 0, groups - 1));
        }
        equal(expected, decode(bytes(bits + END)), "129 consecutive blocks");
    }

    static void laterBlockFailureRemainsSticky() throws Exception {
        byte[] packed = bytes(block(2, 1, 0, 0) + block(2, 1, 0, 256) + END);
        try (InputStream decoded = new AlzBzip2InputStream(new ByteArrayInputStream(packed))) {
            require(decoded.read() == 'A', "first valid block must decode");
            IOException first = rejected("selectorMtf", decoded::read);
            IOException second = rejected("selectorMtf", decoded::read);
            IOException third = rejected("selectorMtf", () -> decoded.read(new byte[8]));
            require(first == second && second == third, "first selector failure must remain terminal");
        }
    }

    static void laterBlockZeroSelectorsAreRejected() throws Exception {
        byte[] packed = bytes(block(2, 1, 0, 0) + block(2, 0, -1, 0) + END);
        try (InputStream decoded = new AlzBzip2InputStream(new ByteArrayInputStream(packed))) {
            require(decoded.read() == 'A', "first valid block must decode");
            rejected("selector count", decoded::read);
        }
    }

    static void truncatedSelectorListIsRejected() throws Exception {
        rejected(null, () -> decode(bytes(header(2, 32767) + "0")));
    }
    static void emptyStreamRemainsValid() throws Exception {
        equal(new byte[0], decode(bytes(END)), "empty stream has no selector table");
    }

    private static void probe() throws Exception {
        existingSingleBlockStillDecodes();
        System.out.println("Existing single-block output: exact");
        for (int extra : new int[]{256, 512, 768}) {
            try {
                byte[] result = decode(realMutation(extra));
                byte[] expected = "ALZ bzip2 single block payload. ".repeat(40).getBytes(StandardCharsets.US_ASCII);
                System.out.println("Original fixture +" + extra + " unary bits: ACCEPTED, exact=" + Arrays.equals(result, expected));
            } catch (IOException error) {
                System.out.println("Original fixture +" + extra + " unary bits: REJECTED " + error.getMessage());
            }
        }
        for (int[] choice : new int[][]{{2, 1, 2}, {2, 0, 0}, {1, 1, 0}}) {
            try {
                byte[] result = decode(stream(choice[0], choice[1], 0, choice[2]));
                System.out.println("groups=" + choice[0] + " selectors=" + choice[1] + " unary=" + choice[2] + ": ACCEPTED, exact=" + Arrays.equals(A, result));
            } catch (IOException error) {
                System.out.println("groups=" + choice[0] + " selectors=" + choice[1] + " unary=" + choice[2] + ": REJECTED " + error.getMessage());
            }
        }
    }

    private static String header(int groups, int selectors) {
        // One in-use symbol: byte 65 ('A'), group 4 and bit 1 in that group.
        return fixed(0x444c5a01L, 32) + fixed(0, 24) + fixed(0x0800, 16)
                + fixed(0x4000, 16) + fixed(groups, 3) + fixed(selectors, 15);
    }
    private static String block(int groups, int count, int specialIndex, int unary) {
        StringBuilder bits = new StringBuilder(header(groups, count));
        for (int i = 0; i < count; i++) {
            if (i == specialIndex) bits.append("1".repeat(unary));
            bits.append('0');
        }
        // All active tables have lengths [1,2,2]: RUNA=0, RUNB=10, EOB=11.
        // Identical tables allow any valid MTF selector to decode the same 'A'.
        for (int i = 0; i < groups; i++) bits.append("00001").append("01000");
        bits.append("011"); // RUNA followed by EOB: one 'A'.
        return bits.toString();
    }
    private static byte[] stream(int groups, int count, int specialIndex, int unary) {
        return bytes(block(groups, count, specialIndex, unary) + END);
    }
    private static byte[] original() {
        return Base64.getDecoder().decode(Round4Fixtures.ALZ_BZ_SINGLE_BLOCK_B64);
    }
    private static byte[] realMutation(int additionalOnes) {
        String bits = bits(original());
        int position = 32 + 24;
        String usedGroups = bits.substring(position, position + 16);
        position += 16;
        for (int i = 0; i < 16; i++) if (usedGroups.charAt(i) == '1') position += 16;
        position += 3 + 15;
        return bytes(bits.substring(0, position) + "1".repeat(additionalOnes) + bits.substring(position));
    }
    private static byte[] decode(byte[] packed) throws IOException {
        try (InputStream decoded = new AlzBzip2InputStream(new ByteArrayInputStream(packed))) {
            return decoded.readAllBytes();
        }
    }
    private static IOException rejected(String messageFragment, Check action) throws Exception {
        try { action.run(); }
        catch (IOException error) {
            if (messageFragment != null) require(error.getMessage() != null
                    && error.getMessage().contains(messageFragment), "wrong error: " + error);
            return error;
        }
        throw new AssertionError("Malformed stream accepted; expected IOException containing " + messageFragment);
    }
    private static void equal(byte[] expected, byte[] actual, String message) {
        require(Arrays.equals(expected, actual), message + ": output bytes differ");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static String fixed(long value, int width) {
        String bits = Long.toBinaryString(value);
        if (bits.length() > width) throw new IllegalArgumentException("value exceeds bit width");
        return "0".repeat(width - bits.length()) + bits;
    }
    private static String bits(byte[] packed) {
        StringBuilder bits = new StringBuilder(packed.length * 8);
        for (byte b : packed) bits.append(fixed(b & 255, 8));
        return bits.toString();
    }
    private static byte[] bytes(String bits) { return bytes(bits, '0'); }
    private static byte[] bytes(String bits, char padding) {
        int length = (bits.length() + 7) / 8;
        String padded = bits + String.valueOf(padding).repeat(length * 8 - bits.length());
        byte[] bytes = new byte[length];
        for (int i = 0; i < padded.length(); i++) if (padded.charAt(i) == '1') bytes[i / 8] |= (byte) (1 << (7 - i % 8));
        return bytes;
    }
}
