package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Best-effort decoder for legacy archive entry names.
 *
 * Archive formats are inconsistent about filename encodings: modern ZIP/EGG/RAR5
 * normally use UTF-8, while older ZIP/ALZ/EGG files often store names in a local
 * legacy code page without a reliable flag.  This helper keeps ASCII and valid
 * flagged UTF-8 stable, then scores common archive filename encodings across the
 * major language families most likely to appear in old desktop-created archives.
 */
public final class ArchiveFilenameDecoder {
    private ArchiveFilenameDecoder() {}

    /**
     * Candidate order is a tie-breaker only; the script/language score below is
     * the real selector.  The list intentionally covers more than twelve common
     * archive filename language families/code pages:
     * Korean, Simplified Chinese, Traditional Chinese, Japanese, Western Latin,
     * Central/Eastern European Latin, Baltic Latin, Cyrillic/Russian, Greek,
     * Turkish, Hebrew, Arabic, Thai, Vietnamese, DOS Cyrillic, and DOS archives.
     */
    private static final List<String> LEGACY_CANDIDATES = Arrays.asList(
            "MS949",          // Korean CP949 / Windows-949
            "GB18030",       // Simplified Chinese, superset of GBK
            "Big5",          // Traditional Chinese
            "Shift_JIS",     // Japanese
            "windows-1252",  // Western European / English legacy archives
            "windows-1250",  // Central/Eastern European Latin
            "windows-1257",  // Baltic Latin
            "windows-1251",  // Cyrillic / Russian
            "KOI8-R",        // Older Russian archives
            "IBM866",        // DOS Cyrillic archives
            "windows-1253",  // Greek
            "windows-1254",  // Turkish
            "windows-1255",  // Hebrew
            "windows-1256",  // Arabic / Persian / Urdu legacy names
            "windows-874",   // Thai
            "windows-1258",  // Vietnamese
            "ISO-8859-1",    // Latin-1 fallback before DOS box-drawing
            "IBM437"         // Original ZIP/DOS default fallback
    );

    @NonNull
    public static String decodeZipName(@NonNull byte[] raw, boolean utf8Flag) {
        return decode(raw, 0, raw.length, utf8Flag, 0);
    }

    @NonNull
    public static String decodeLegacyName(@NonNull byte[] raw) {
        return decode(raw, 0, raw.length, false, 0);
    }

    @NonNull
    public static String decodeEggName(@NonNull byte[] raw, int offset, int length, int localeCodePage) {
        return decode(raw, offset, length, false, localeCodePage);
    }

    @NonNull
    public static String decodeUtf8OrLegacy(@NonNull byte[] raw) {
        return decode(raw, 0, raw.length, true, 0);
    }

    @NonNull
    private static String decode(@NonNull byte[] raw,
                                 int offset,
                                 int length,
                                 boolean preferUtf8,
                                 int localeCodePage) {
        if (length <= 0) return "";
        if (isAscii(raw, offset, length)) {
            return normalizeName(new String(raw, offset, length, StandardCharsets.US_ASCII));
        }

        String utf8 = tryDecode(raw, offset, length, StandardCharsets.UTF_8);
        if (preferUtf8 && utf8 != null && isUsableDecodedName(utf8)) {
            return normalizeName(utf8);
        }

        ArrayList<String> candidates = new ArrayList<>();
        String localeCharset = charsetForCodePage(localeCodePage);
        if (localeCharset != null) candidates.add(localeCharset);
        if (utf8 != null) candidates.add("UTF-8");
        for (String candidate : LEGACY_CANDIDATES) {
            if (!containsIgnoreCase(candidates, candidate)) candidates.add(candidate);
        }

        ScoredName best = null;
        for (String charsetName : candidates) {
            if (!Charset.isSupported(charsetName)) continue;
            String decoded = tryDecode(raw, offset, length, Charset.forName(charsetName));
            if (decoded == null || !isUsableDecodedName(decoded)) continue;
            double score = scoreDecodedName(decoded, charsetName, localeCharset);
            ScoredName scored = new ScoredName(decoded, score);
            if (best == null || scored.score > best.score) best = scored;
        }

        if (best != null) return normalizeName(best.value);
        if (utf8 != null) return normalizeName(utf8);
        return normalizeName(new String(raw, offset, length, StandardCharsets.ISO_8859_1));
    }

    @NonNull
    private static String normalizeName(@NonNull String value) {
        return Normalizer.normalize(value.replace('\\', '/'), Normalizer.Form.NFC);
    }

    @Nullable
    private static String tryDecode(@NonNull byte[] raw, int offset, int length, @NonNull Charset charset) {
        try {
            CharBuffer out = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw, offset, length));
            return out.toString();
        } catch (CharacterCodingException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isAscii(@NonNull byte[] raw, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            if ((raw[i] & 0x80) != 0) return false;
        }
        return true;
    }

    private static boolean isUsableDecodedName(@NonNull String value) {
        if (value.length() == 0) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\u0000' || ch == '\ufffd') return false;
            if (Character.isISOControl(ch) && ch != '\t') return false;
        }
        return true;
    }

    private static double scoreDecodedName(@NonNull String value,
                                           @NonNull String charsetName,
                                           @Nullable String localeCharset) {
        ScriptStats stats = collectScriptStats(value);
        double score = 0.0;
        score += stats.hangul * 14.0;
        score += stats.kana * 12.0;
        score += stats.cjk * 6.0;
        score += stats.commonCjk * 5.0;
        score += stats.cyrillic * 9.0;
        score += stats.greek * 9.0;
        score += stats.greekTonos * 10.0;
        score += stats.hebrew * 10.0;
        score += stats.arabic * 10.0;
        score += stats.thai * 11.0;
        score += stats.vietnamese * 7.0;
        score += stats.turkish * 7.0;
        score += stats.centralEuropeanLatin * 5.0;
        score += stats.balticLatin * 5.0;
        score += stats.latinExtended * 3.0;
        score += stats.latinAscii * 2.0;
        score += stats.digits * 1.5;
        score += stats.separators * 1.0;
        score += stats.commonPunctuation * 1.0;
        score -= stats.boxDrawing * 7.0;
        score -= stats.currencyOrMathSymbols * 4.0;
        score -= stats.suspicious * 8.0;

        String normalized = charsetName.toUpperCase(Locale.ROOT);
        String normalizedLocale = localeCharset == null ? null : localeCharset.toUpperCase(Locale.ROOT);
        if (normalizedLocale != null && normalized.equals(normalizedLocale)) score += 1000.0;

        if (isCharset(normalized, "MS949", "WINDOWS-949", "CP949") && stats.hangul >= 2
                && stats.hangul >= stats.cjk && stats.suspicious == 0 && stats.boxDrawing == 0) score += 200.0;
        if (isCharset(normalized, "SHIFT_JIS", "WINDOWS-31J") && (stats.kana - stats.halfwidthKana) > 0) score += 60.0;
        else if (isCharset(normalized, "SHIFT_JIS", "WINDOWS-31J") && stats.halfwidthKana > 0 && stats.commonCjk > 0) score += 20.0;
        if (isCharset(normalized, "SHIFT_JIS", "WINDOWS-31J") && stats.commonCjk > 0) score += 54.0;
        if (isCharset(normalized, "GB18030", "GBK", "GB2312") && stats.cjk >= 2 && stats.hangul == 0 && stats.kana == 0) score += 10.0;
        if (isCharset(normalized, "GB18030", "GBK", "GB2312") && stats.commonCjk > 0 && stats.hangul == 0 && stats.kana == 0) score += 180.0;
        if (isCharset(normalized, "BIG5") && stats.cjk >= 2 && stats.hangul == 0 && stats.kana == 0) score += 8.0;
        if (isCharset(normalized, "BIG5") && stats.commonCjk > 0 && stats.hangul == 0 && stats.kana == 0) score += 175.0;
        if (isCharset(normalized, "WINDOWS-1251", "KOI8-R", "IBM866", "CP866") && stats.cyrillic > 0) score += 22.0;
        if (isCharset(normalized, "WINDOWS-1253", "ISO-8859-7") && stats.greek > 0) score += 22.0;
        if (isCharset(normalized, "WINDOWS-1253", "ISO-8859-7") && stats.greekTonos > 0) score += 120.0;
        if (isCharset(normalized, "WINDOWS-1254", "ISO-8859-9") && stats.turkish >= 3 && stats.latinExtended <= stats.turkish) score += 220.0;
        else if (isCharset(normalized, "WINDOWS-1254", "ISO-8859-9") && stats.turkish > 0) score += 12.0;
        if (isCharset(normalized, "WINDOWS-1255", "ISO-8859-8") && stats.hebrew > 0) score += 24.0;
        if (isCharset(normalized, "WINDOWS-1256", "ISO-8859-6") && stats.arabic > 0) score += 24.0;
        if (isCharset(normalized, "WINDOWS-874", "TIS-620") && stats.thai > 0) score += 60.0;
        if (isCharset(normalized, "WINDOWS-1258") && stats.vietnamese >= 2) score += 110.0;
        else if (isCharset(normalized, "WINDOWS-1258") && stats.vietnamese > 0) score += 24.0;
        if (isCharset(normalized, "WINDOWS-1250", "ISO-8859-2") && stats.centralEuropeanLatin >= 5) score += 95.0;
        else if (isCharset(normalized, "WINDOWS-1250", "ISO-8859-2") && stats.centralEuropeanLatin > 0) score += 16.0;
        if (isCharset(normalized, "WINDOWS-1257", "ISO-8859-13") && stats.balticLatin >= 4) score += 95.0;
        else if (isCharset(normalized, "WINDOWS-1257", "ISO-8859-13") && stats.balticLatin > 0) score += 16.0;
        if (isCharset(normalized, "WINDOWS-1252", "ISO-8859-1") && stats.latinExtended > 0
                && stats.cyrillic == 0 && stats.greek == 0 && stats.hebrew == 0 && stats.arabic == 0 && stats.thai == 0) {
            score += 8.0;
        }
        if (normalized.equals("UTF-8")) score += 4.0;
        if (isCharset(normalized, "IBM437") && hasOnlyAsciiLike(stats)) score += 5.0;

        return score;
    }

    private static ScriptStats collectScriptStats(@NonNull String value) {
        ScriptStats stats = new ScriptStats();
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '/' || cp == '\\') stats.separators++;
            else if (cp >= '0' && cp <= '9') stats.digits++;
            else if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z')) stats.latinAscii++;
            else if (cp >= 0xac00 && cp <= 0xd7a3) stats.hangul++;
            else if ((cp >= 0x3040 && cp <= 0x30ff) || (cp >= 0xff66 && cp <= 0xff9f)) {
                stats.kana++;
                if (cp >= 0xff66 && cp <= 0xff9f) stats.halfwidthKana++;
            }
            else if ((cp >= 0x4e00 && cp <= 0x9fff) || (cp >= 0x3400 && cp <= 0x4dbf)) {
                stats.cjk++;
                if (isCommonCjkFilenameChar(cp)) stats.commonCjk++;
            }
            else if (cp >= 0x0400 && cp <= 0x052f) {
                stats.cyrillic++;
                if (isRussianMarkerCyrillic(cp)) stats.cyrillicRussianMarkers++;
            }
            else if (cp >= 0x0370 && cp <= 0x03ff) {
                stats.greek++;
                if (isGreekTonosLetter(cp)) stats.greekTonos++;
            }
            else if (cp >= 0x0590 && cp <= 0x05ff) {
                if (cp >= 0x05d0 && cp <= 0x05ea) stats.hebrew++;
                else stats.suspicious += 1;
            }
            else if (cp >= 0x0600 && cp <= 0x06ff) stats.arabic++;
            else if (cp >= 0x0e00 && cp <= 0x0e7f) {
                if ((cp >= 0x0e01 && cp <= 0x0e2e) || (cp >= 0x0e40 && cp <= 0x0e44)) stats.thai++;
                else stats.suspicious += 1;
            }
            else if (isVietnameseLatin(cp)) stats.vietnamese++;
            else if (isTurkishLatin(cp)) stats.turkish++;
            else if (isCentralEuropeanLatin(cp)) stats.centralEuropeanLatin++;
            else if (isBalticLatin(cp)) stats.balticLatin++;
            else if ((cp >= 0x00c0 && cp <= 0x024f) || (cp >= 0x1e00 && cp <= 0x1eff)) stats.latinExtended++;
            else if (isCommonFilenamePunctuation(cp)) stats.commonPunctuation++;
            else if ((cp >= 0x2500 && cp <= 0x257f) || (cp >= 0x2580 && cp <= 0x259f)) stats.boxDrawing++;
            else if (isCurrencyOrMathSymbol(cp)) stats.currencyOrMathSymbols++;
            else if (Character.isLetterOrDigit(cp)) stats.suspicious += 1;
            else stats.suspicious += 2;
        }
        return stats;
    }

    private static boolean isRussianMarkerCyrillic(int cp) {
        return cp == 0x0401 || cp == 0x0451 || cp == 0x0419 || cp == 0x0439
                || cp == 0x042B || cp == 0x044B || cp == 0x042D || cp == 0x044D
                || cp == 0x042E || cp == 0x044E || cp == 0x042F || cp == 0x044F
                || cp == 0x0429 || cp == 0x0449;
    }

    private static boolean isGreekTonosLetter(int cp) {
        return cp == 0x0386 || cp == 0x0388 || cp == 0x0389 || cp == 0x038A
                || cp == 0x038C || cp == 0x038E || cp == 0x038F
                || cp == 0x03AC || cp == 0x03AD || cp == 0x03AE || cp == 0x03AF
                || cp == 0x03CC || cp == 0x03CD || cp == 0x03CE;
    }

    private static boolean isCommonCjkFilenameChar(int cp) {
        switch (cp) {
            case '一': case '二': case '三': case '四': case '五': case '六': case '七': case '八': case '九': case '十':
            case '上': case '下': case '中': case '大': case '小': case '新': case '舊': case '旧': case '全': case '本':
            case '日': case '月': case '年': case '時': case '时': case '分': case '話': case '话': case '回': case '章':
            case '文': case '字': case '語': case '语': case '書': case '书': case '頁': case '页': case '巻': case '卷':
            case '圖': case '图': case '畫': case '画': case '像': case '版': case '名': case '目': case '次': case '第':
            case '简': case '簡': case '体': case '體': case '繁': case '国': case '國': case '汉': case '漢': case '韩':
            case '韓': case '英': case '和': case '外': case '部': case '前': case '後': case '后': case '左': case '右':
                return true;
            default:
                return false;
        }
    }

    private static boolean isVietnameseLatin(int cp) {
        return cp == 0x0102 || cp == 0x0103 || cp == 0x0110 || cp == 0x0111
                || cp == 0x0128 || cp == 0x0129 || cp == 0x0168 || cp == 0x0169
                || cp == 0x01A0 || cp == 0x01A1 || cp == 0x01AF || cp == 0x01B0
                || cp == 0x1EA0 || cp == 0x1EA1 || cp == 0x1EA2 || cp == 0x1EA3
                || cp == 0x1EA4 || cp == 0x1EA5 || cp == 0x1EA6 || cp == 0x1EA7
                || cp == 0x1EA8 || cp == 0x1EA9 || cp == 0x1EAA || cp == 0x1EAB
                || cp == 0x1EAC || cp == 0x1EAD || cp == 0x1EAE || cp == 0x1EAF
                || cp == 0x1EB0 || cp == 0x1EB1 || cp == 0x1EB2 || cp == 0x1EB3
                || cp == 0x1EB4 || cp == 0x1EB5 || cp == 0x1EB6 || cp == 0x1EB7
                || cp == 0x1EB8 || cp == 0x1EB9 || cp == 0x1EBA || cp == 0x1EBB
                || cp == 0x1EBC || cp == 0x1EBD || cp == 0x1EBE || cp == 0x1EBF
                || cp == 0x1EC0 || cp == 0x1EC1 || cp == 0x1EC2 || cp == 0x1EC3
                || cp == 0x1EC4 || cp == 0x1EC5 || cp == 0x1EC6 || cp == 0x1EC7
                || cp == 0x1EC8 || cp == 0x1EC9 || cp == 0x1ECA || cp == 0x1ECB
                || cp == 0x1ECC || cp == 0x1ECD || cp == 0x1ECE || cp == 0x1ECF
                || cp == 0x1ED0 || cp == 0x1ED1 || cp == 0x1ED2 || cp == 0x1ED3
                || cp == 0x1ED4 || cp == 0x1ED5 || cp == 0x1ED6 || cp == 0x1ED7
                || cp == 0x1ED8 || cp == 0x1ED9 || cp == 0x1EDA || cp == 0x1EDB
                || cp == 0x1EDC || cp == 0x1EDD || cp == 0x1EDE || cp == 0x1EDF
                || cp == 0x1EE0 || cp == 0x1EE1 || cp == 0x1EE2 || cp == 0x1EE3
                || cp == 0x1EE4 || cp == 0x1EE5 || cp == 0x1EE6 || cp == 0x1EE7
                || cp == 0x1EE8 || cp == 0x1EE9 || cp == 0x1EEA || cp == 0x1EEB
                || cp == 0x1EEC || cp == 0x1EED || cp == 0x1EEE || cp == 0x1EEF
                || cp == 0x1EF0 || cp == 0x1EF1 || cp == 0x1EF2 || cp == 0x1EF3
                || cp == 0x1EF4 || cp == 0x1EF5 || cp == 0x1EF6 || cp == 0x1EF7
                || cp == 0x1EF8 || cp == 0x1EF9;
    }

    private static boolean isTurkishLatin(int cp) {
        return cp == 0x00C7 || cp == 0x00E7 || cp == 0x00D6 || cp == 0x00F6
                || cp == 0x00DC || cp == 0x00FC || cp == 0x011E || cp == 0x011F
                || cp == 0x0130 || cp == 0x0131 || cp == 0x015E || cp == 0x015F;
    }

    private static boolean isCentralEuropeanLatin(int cp) {
        return cp == 0x0104 || cp == 0x0105 || cp == 0x0106 || cp == 0x0107
                || cp == 0x010C || cp == 0x010D || cp == 0x010E || cp == 0x010F
                || cp == 0x0118 || cp == 0x0119 || cp == 0x011A || cp == 0x011B
                || cp == 0x0139 || cp == 0x013A || cp == 0x013D || cp == 0x013E
                || cp == 0x0141 || cp == 0x0142 || cp == 0x0143 || cp == 0x0144
                || cp == 0x0147 || cp == 0x0148 || cp == 0x0150 || cp == 0x0151
                || cp == 0x0154 || cp == 0x0155 || cp == 0x0158 || cp == 0x0159
                || cp == 0x015A || cp == 0x015B || cp == 0x0160 || cp == 0x0161
                || cp == 0x0164 || cp == 0x0165 || cp == 0x016E || cp == 0x016F
                || cp == 0x0170 || cp == 0x0171 || cp == 0x0179 || cp == 0x017A
                || cp == 0x017B || cp == 0x017C || cp == 0x017D || cp == 0x017E;
    }

    private static boolean isBalticLatin(int cp) {
        return cp == 0x0100 || cp == 0x0101 || cp == 0x010C || cp == 0x010D
                || cp == 0x0112 || cp == 0x0113 || cp == 0x0116 || cp == 0x0117
                || cp == 0x0122 || cp == 0x0123 || cp == 0x012A || cp == 0x012B
                || cp == 0x012E || cp == 0x012F || cp == 0x0136 || cp == 0x0137
                || cp == 0x013B || cp == 0x013C || cp == 0x0145 || cp == 0x0146
                || cp == 0x0160 || cp == 0x0161 || cp == 0x016A || cp == 0x016B
                || cp == 0x0172 || cp == 0x0173 || cp == 0x017D || cp == 0x017E;
    }

    private static boolean hasOnlyAsciiLike(@NonNull ScriptStats stats) {
        return stats.hangul == 0 && stats.kana == 0 && stats.cjk == 0 && stats.cyrillic == 0
                && stats.greek == 0 && stats.hebrew == 0 && stats.arabic == 0 && stats.thai == 0
                && stats.vietnamese == 0 && stats.turkish == 0 && stats.centralEuropeanLatin == 0
                && stats.balticLatin == 0 && stats.boxDrawing == 0 && stats.currencyOrMathSymbols == 0;
    }

    private static boolean isCharset(@NonNull String value, @NonNull String... names) {
        for (String name : names) {
            if (value.equals(name)) return true;
        }
        return false;
    }

    private static boolean isCommonFilenamePunctuation(int cp) {
        return cp == ' ' || cp == '.' || cp == '_' || cp == '-' || cp == '(' || cp == ')' || cp == '[' || cp == ']'
                || cp == '{' || cp == '}' || cp == '+' || cp == '&' || cp == '#' || cp == '@' || cp == '!'
                || cp == ',' || cp == ';' || cp == ':' || cp == '\u3000' || cp == '\u30fb'
                || cp == '\u2010' || cp == '\u2011' || cp == '\u2012' || cp == '\u2013' || cp == '\u2014'
                || cp == '\u2018' || cp == '\u2019' || cp == '\u201c' || cp == '\u201d';
    }

    private static boolean isCurrencyOrMathSymbol(int cp) {
        return (cp >= 0x20a0 && cp <= 0x20cf) || (cp >= 0x2200 && cp <= 0x22ff)
                || cp == '\u00a4' || cp == '\u00a6' || cp == '\u00a7' || cp == '\u00b1' || cp == '\u00b6';
    }

    @Nullable
    private static String charsetForCodePage(int codePage) {
        switch (codePage) {
            case 65001:
                return "UTF-8";
            case 949:
            case 51949:
                return "MS949";
            case 936:
            case 54936:
                return "GB18030";
            case 950:
                return "Big5";
            case 932:
                return "Shift_JIS";
            case 437:
                return "IBM437";
            case 866:
                return "IBM866";
            case 874:
                return "windows-874";
            case 1250:
                return "windows-1250";
            case 1251:
                return "windows-1251";
            case 1252:
                return "windows-1252";
            case 1253:
                return "windows-1253";
            case 1254:
                return "windows-1254";
            case 1255:
                return "windows-1255";
            case 1256:
                return "windows-1256";
            case 1257:
                return "windows-1257";
            case 1258:
                return "windows-1258";
            default:
                return null;
        }
    }

    private static boolean containsIgnoreCase(@NonNull List<String> values, @NonNull String needle) {
        for (String value : values) {
            if (value.equalsIgnoreCase(needle)) return true;
        }
        return false;
    }

    private static final class ScriptStats {
        int hangul;
        int kana;
        int halfwidthKana;
        int cjk;
        int commonCjk;
        int cyrillic;
        int cyrillicRussianMarkers;
        int greek;
        int greekTonos;
        int hebrew;
        int arabic;
        int thai;
        int vietnamese;
        int turkish;
        int centralEuropeanLatin;
        int balticLatin;
        int latinExtended;
        int latinAscii;
        int digits;
        int separators;
        int commonPunctuation;
        int boxDrawing;
        int currencyOrMathSymbols;
        int suspicious;
    }

    private static final class ScoredName {
        final String value;
        final double score;

        ScoredName(@NonNull String value, double score) {
            this.value = value;
            this.score = score;
        }
    }
}
