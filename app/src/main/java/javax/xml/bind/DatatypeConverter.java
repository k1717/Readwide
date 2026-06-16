package javax.xml.bind;

/**
 * Minimal JAXB DatatypeConverter compatibility shim.
 *
 * hwplib exposes a fromBase64String helper that references the Java 7 JAXB
 * DatatypeConverter class. Android/Java 17 builds do not provide JAXB by
 * default, so this tiny project-local shim supplies only the method that hwplib
 * references. Readwide does not call the Base64 HWP path directly.
 */
public final class DatatypeConverter {
    private static final int[] BASE64 = new int[256];

    static {
        for (int i = 0; i < BASE64.length; i++) BASE64[i] = -1;
        for (int i = 'A'; i <= 'Z'; i++) BASE64[i] = i - 'A';
        for (int i = 'a'; i <= 'z'; i++) BASE64[i] = 26 + i - 'a';
        for (int i = '0'; i <= '9'; i++) BASE64[i] = 52 + i - '0';
        BASE64['+'] = 62;
        BASE64['/'] = 63;
    }

    private DatatypeConverter() {}

    public static byte[] parseBase64Binary(String value) {
        if (value == null || value.isEmpty()) return new byte[0];
        StringBuilder compact = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isWhitespace(ch)) compact.append(ch);
        }
        int len = compact.length();
        if (len == 0) return new byte[0];
        if ((len & 3) != 0) throw new IllegalArgumentException("Invalid Base64 length");
        int padding = 0;
        if (len > 0 && compact.charAt(len - 1) == '=') padding++;
        if (len > 1 && compact.charAt(len - 2) == '=') padding++;
        byte[] out = new byte[(len / 4) * 3 - padding];
        int outPos = 0;
        for (int i = 0; i < len; i += 4) {
            int c0 = decode(compact.charAt(i));
            int c1 = decode(compact.charAt(i + 1));
            int c2 = compact.charAt(i + 2) == '=' ? 0 : decode(compact.charAt(i + 2));
            int c3 = compact.charAt(i + 3) == '=' ? 0 : decode(compact.charAt(i + 3));
            int block = (c0 << 18) | (c1 << 12) | (c2 << 6) | c3;
            if (outPos < out.length) out[outPos++] = (byte) ((block >>> 16) & 0xFF);
            if (outPos < out.length) out[outPos++] = (byte) ((block >>> 8) & 0xFF);
            if (outPos < out.length) out[outPos++] = (byte) (block & 0xFF);
        }
        return out;
    }

    private static int decode(char ch) {
        if (ch < BASE64.length && BASE64[ch] >= 0) return BASE64[ch];
        throw new IllegalArgumentException("Invalid Base64 character");
    }
}
