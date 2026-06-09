package com.textview.reader.util;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.textview.reader.model.TextChunk;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Encoding detection and text-file decoding extracted from FileUtils.
 * FileUtils keeps delegation wrappers so existing callers do not change.
 */
final class TextEncodingDetector {
    private static final String TAG = "TextEncodingDetector";

    private static final byte[] BOM_UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] BOM_UTF16_LE = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] BOM_UTF16_BE = {(byte) 0xFE, (byte) 0xFF};
    private static final byte[] BOM_UTF32_LE = {(byte) 0xFF, (byte) 0xFE, 0x00, 0x00};
    private static final byte[] BOM_UTF32_BE = {0x00, 0x00, (byte) 0xFE, (byte) 0xFF};

    // Fast path first: most TXT files can be classified from a modest sample.
    // If the primary sample is ambiguous, detection escalates to the extended
    // sample.  This keeps normal file opening responsive while preserving the
    // 256 KiB accuracy path for genuinely uncertain legacy encodings.
    private static final int PRIMARY_SAMPLE_LIMIT = 192 * 1024;
    private static final int EXTENDED_SAMPLE_LIMIT = 256 * 1024;
    private static final int SAMPLE_LIMIT = PRIMARY_SAMPLE_LIMIT;

    // Encoding scorer weights.  These names intentionally preserve the existing
    // numeric behavior while making future detector tuning reviewable in diffs.
    private static final double DETECTOR_ICU_EXACT_BONUS = 45.0;
    private static final double DETECTOR_MOZILLA_EXACT_BONUS = 70.0;
    private static final double DETECTOR_ICU_FAMILY_BONUS = 18.0;
    private static final double DETECTOR_MOZILLA_FAMILY_BONUS = 32.0;
    private static final double DETECTOR_AGREEING_FAMILY_BONUS = 45.0;
    private static final double SCORE_REPLACEMENT_PENALTY = 900.0;
    private static final double SCORE_BAD_CONTROL_PENALTY = 500.0;
    private static final double SCORE_NUL_PENALTY = 1500.0;
    private static final double KOREAN_HANGUL_BONUS = 6.7;
    private static final double KOREAN_CJK_BONUS = 1.45;
    private static final double KOREAN_SUSTAINED_SIGNAL_BONUS = 550.0;
    private static final double KOREAN_STRONG_SIGNAL_BONUS = 3200.0;
    private static final double KOREAN_WEAK_SIGNAL_PENALTY = 900.0;
    private static final double KOREAN_SUPERSET_BONUS = 35.0;
    private static final double KOREAN_EUC_KR_TIE_PENALTY = 10.0;
    // When the same bytes decode to substantially more Han characters under a
    // Chinese charset than they produce Hangul under CP949, the "Korean" decode
    // is almost certainly Chinese mojibake. In that case the big sustained/strong
    // Korean bonuses are suppressed and a penalty is applied so the correct
    // Chinese family can win. The ratio guards against penalizing genuine Korean
    // text, which is Hangul-dominant rather than Han-dominant.
    private static final double KOREAN_HAN_DOMINANCE_RATIO = 1.30;
    private static final double KOREAN_FAKE_HANGUL_PENALTY = 1500.0;
    private static final double CYRILLIC_CHAR_BONUS = 1.8;
    private static final double CYRILLIC_NATURALNESS_BONUS = 4.2;
    private static final double CYRILLIC_ODD_CHAR_PENALTY = 45.0;
    private static final double CYRILLIC_ODD_PATTERN_BASE_PENALTY = 1800.0;
    private static final double CYRILLIC_ODD_PATTERN_EXTRA_PENALTY = 18.0;
    private static final double CYRILLIC_SUSTAINED_SIGNAL_BONUS = 260.0;
    private static final double CYRILLIC_WEAK_SIGNAL_PENALTY = 850.0;
    private static final double CYRILLIC_BYTE_SHAPE_WEIGHT = 0.75;
    private static final double KOI8_BOX_DRAWING_PENALTY = 8.0;
    private static final double JAPANESE_KANA_BONUS = 3.5;
    private static final double JAPANESE_CJK_BONUS = 0.75;
    private static final double JAPANESE_SUSTAINED_SIGNAL_BONUS = 380.0;
    private static final double JAPANESE_WEAK_SIGNAL_PENALTY = 700.0;
    private static final double CHINESE_CJK_BONUS = 1.35;
    private static final double CHINESE_SUSTAINED_SIGNAL_BONUS = 360.0;
    private static final double CHINESE_WEAK_SIGNAL_PENALTY = 550.0;
    private static final double SINGLE_SCRIPT_CHAR_BONUS = 2.1;
    private static final double SINGLE_SCRIPT_SUSTAINED_SIGNAL_BONUS = 240.0;
    private static final double SINGLE_SCRIPT_WEAK_SIGNAL_PENALTY = 450.0;
    // Short high-byte samples (titles, first lines, memos) carry weak
    // statistical signal, so single-byte code pages can spuriously beat the
    // correct multibyte CJK encoding by treating every byte as a valid
    // character. When the sample is short, contains high bytes, and a
    // multibyte CJK charset decodes the same bytes with almost no replacement
    // characters, penalize single-byte alphabetic families so the multibyte
    // interpretation is preferred.
    private static final int SHORT_SAMPLE_BYTE_LIMIT = 256;
    private static final int SHORT_SAMPLE_DETECTOR_TRUST_MIN_BYTES = 24;
    private static final double SHORT_SAMPLE_SINGLE_BYTE_PENALTY = 600.0;
    // When a statistical detector (ICU or Mozilla) reports a multibyte CJK
    // encoding, a single-byte alphabetic candidate is almost certainly that CJK
    // text misread one byte at a time. Single-byte code pages accept nearly any
    // byte as a valid letter, so a Chinese/Japanese/Korean file can score well
    // as "clean" Cyrillic / Thai / Greek text. Penalize that mismatch so the
    // detector-confirmed CJK family wins regardless of sample length.
    private static final double DETECTOR_CJK_VS_SINGLE_BYTE_PENALTY = 6000.0;
    // Per-character component so the mismatch penalty outweighs single-byte
    // character bonuses plus their sustained/naturalness bonuses on long files.
    // Set above the largest per-character bonus in the scorer (6.7) so a
    // detector-confirmed CJK family reliably beats any single-byte misdecode.
    private static final double DETECTOR_CJK_VS_SINGLE_BYTE_PER_CHAR_PENALTY = 8.0;
    // Detector-confirmed single-byte vs a DIFFERENT single-byte family. Smaller
    // than the CJK penalty because the two scripts are closer in plausibility,
    // but still length-scaled so it overcomes per-character bonuses on long
    // files. Per-char value sits just above the single-byte char bonuses (<=2.4).
    private static final double DETECTOR_SINGLE_BYTE_CROSS_FAMILY_PENALTY = 400.0;
    private static final double DETECTOR_SINGLE_BYTE_CROSS_FAMILY_PER_CHAR_PENALTY = 3.0;
    private static final double VIETNAMESE_SIGNAL_BONUS = 2.4;
    private static final double VIETNAMESE_SUSTAINED_SIGNAL_BONUS = 260.0;
    private static final double VIETNAMESE_WEAK_SIGNAL_PENALTY = 220.0;
    private static final double WESTERN_ASCII_LIKE_BONUS = 150.0;
    private static final double WESTERN_SCRIPT_MISMATCH_PENALTY = 400.0;
    private static final double STATEFUL_SIGNATURE_MISSING_PENALTY = 100000.0;

    private static final String[] TEXT_ENCODING_CANDIDATES = new String[]{
            "UTF-8",

            // Broad alphabetic single-byte encodings. These cover most Latin, Greek,
            // Cyrillic, Turkish, Baltic, Hebrew, Arabic, and Thai TXT files that are
            // not Unicode but should not be forced through CJK-only detection.
            "windows-1252", // Western European smart quotes / punctuation
            "ISO-8859-1",
            "ISO-8859-15",
            "windows-1250", // Central/Eastern European Latin
            "ISO-8859-2",
            "windows-1251", // Cyrillic
            "ISO-8859-5",
            "KOI8-R",
            "KOI8-U",
            "windows-1253", // Greek
            "ISO-8859-7",
            "windows-1254", // Turkish
            "ISO-8859-9",
            "windows-1257", // Baltic
            "ISO-8859-13",
            "windows-1258", // Vietnamese
            "windows-1255", // Hebrew
            "ISO-8859-8",
            "windows-1256", // Arabic
            "ISO-8859-6",
            "windows-874", // Thai
            "ISO-8859-11",
            "IBM866", // DOS Cyrillic
            "x-MacRoman",
            "x-MacCyrillic",
            "x-MacGreek",
            "x-MacTurkish",
            "x-MacHebrew",
            "x-MacArabic",

            // CJK and stateful East Asian legacy encodings.
            "windows-949",
            "MS949",
            "EUC-KR",
            "ISO-2022-KR",
            "Shift_JIS",
            "windows-31j",
            "EUC-JP",
            "ISO-2022-JP",
            "GB18030",
            "GBK",
            "GB2312",
            "HZ-GB-2312",
            "Big5",
            "Big5-HKSCS",
            "EUC-TW",

            // Optional legacy families: only active when Android/Java supports them.
            "VISCII",
            "x-ISCII91"
    };




    /**
     * Detect text file encoding.
     */
    public static String detectEncoding(File file) {
        return detectEncodingDetailed(file).charsetName;
    }

    public static FileUtils.EncodingResult detectEncodingDetailed(File file) {
        try {
            byte[] primary = readSample(file, PRIMARY_SAMPLE_LIMIT);
            FileUtils.EncodingResult result = detectEncodingResultFromBytes(primary);

            if (shouldExtendEncodingSample(result, primary.length, file.length())) {
                byte[] extended = readSample(file, EXTENDED_SAMPLE_LIMIT);
                FileUtils.EncodingResult extendedResult = detectEncodingResultFromBytes(extended);
                if (extendedResult.confidence >= result.confidence
                        || result.confidence < FileUtils.EncodingResult.HIGH_CONFIDENCE) {
                    return extendedResult;
                }
            }

            return result;
        } catch (Exception e) {
            Log.e(TAG, "Encoding detection failed", e);
            return new FileUtils.EncodingResult("UTF-8", FileUtils.EncodingResult.LOW_CONFIDENCE, "fallback", "unicode");
        }
    }

    /**
     * Detect encoding from an InputStream. The stream must support mark/reset if caller needs to re-read it.
     */
    public static String detectEncoding(InputStream inputStream) {
        try {
            BufferedInputStream bis = inputStream instanceof BufferedInputStream
                    ? (BufferedInputStream) inputStream
                    : new BufferedInputStream(inputStream);

            bis.mark(PRIMARY_SAMPLE_LIMIT + 16);
            byte[] data = readSample(bis, PRIMARY_SAMPLE_LIMIT);
            bis.reset();

            return detectEncodingResultFromBytes(data).charsetName;
        } catch (Exception e) {
            Log.e(TAG, "Encoding detection from stream failed", e);
            return "UTF-8";
        }
    }

    private static FileUtils.EncodingResult detectEncodingResultFromBytes(byte[] data) {
        if (data == null || data.length == 0) {
            return new FileUtils.EncodingResult("UTF-8", FileUtils.EncodingResult.HIGH_CONFIDENCE, "empty", "unicode");
        }

        String bom = detectBom(data);
        if (bom != null) {
            return new FileUtils.EncodingResult(bom, FileUtils.EncodingResult.HIGH_CONFIDENCE, "bom", "unicode");
        }

        // Stateful East Asian encodings have visible escape sequences and are
        // cheap to identify before statistical legacy scoring.
        if (looksLikeIso2022Jp(data) && Charset.isSupported("ISO-2022-JP")) {
            return new FileUtils.EncodingResult("ISO-2022-JP", FileUtils.EncodingResult.HIGH_CONFIDENCE, "escape", "japanese");
        }
        if (looksLikeIso2022Kr(data) && Charset.isSupported("ISO-2022-KR")) {
            return new FileUtils.EncodingResult("ISO-2022-KR", FileUtils.EncodingResult.HIGH_CONFIDENCE, "escape", "korean");
        }
        if (looksLikeHzGb2312(data) && Charset.isSupported("HZ-GB-2312")) {
            return new FileUtils.EncodingResult("HZ-GB-2312", FileUtils.EncodingResult.HIGH_CONFIDENCE, "escape", "chinese");
        }

        // BOM-less UTF-16 text can look like valid UTF-8 when it is mostly ASCII
        // because NUL bytes are legal control characters in a byte stream. Detect
        // strong UTF-16 byte patterns before accepting strict UTF-8.
        String bomlessUtf16 = detectBomlessUtf16(data);
        if (bomlessUtf16 != null) {
            return new FileUtils.EncodingResult(bomlessUtf16, FileUtils.EncodingResult.HIGH_CONFIDENCE, "utf16-heuristic", "unicode");
        }

        // Strict valid UTF-8 is strong explicit evidence. Legacy encodings are only
        // scored after UTF-8 fails strict validation.
        if (isStrictValidUtf8(data)) {
            return new FileUtils.EncodingResult("UTF-8", FileUtils.EncodingResult.HIGH_CONFIDENCE, "strict-utf8", "unicode");
        }

        // Large fixed-size samples can end in the middle of a multibyte UTF-8
        // character. Without this boundary-aware rescue, valid UTF-8 files can
        // fall through to legacy scoring and be mislabeled as IBM866 or another
        // single-byte code page.
        if (isUtf8WithOnlyTrailingSampleBoundaryIssue(data)) {
            return new FileUtils.EncodingResult("UTF-8", FileUtils.EncodingResult.HIGH_CONFIDENCE, "utf8-sample-boundary", "unicode");
        }

        // Accuracy-first legacy detection:
        // Do not return immediately from a Korean/Cyrillic pre-pass.  CP949,
        // ISO-8859-5, Windows-1251, KOI8, and many Western encodings can all
        // produce "valid" Unicode from the same bytes.  Decode all candidate
        // families, score the decoded text, then choose the strongest family.
        String icuDetected = detectWithAndroidIcu(data);
        String mozillaDetected = detectWithMozillaUniversalChardet(data);
        return detectLegacyEncodingByAccuracy(data, icuDetected, mozillaDetected);
    }

    private static FileUtils.EncodingResult detectLegacyEncodingByAccuracy(byte[] data, String icuDetected, String mozillaDetected) {
        TextEncodingProfile best = null;
        TextEncodingProfile second = null;
        TextEncodingProfile bestKorean = null;
        double bestScore = Double.MAX_VALUE;
        double secondScore = Double.MAX_VALUE;
        double bestKoreanScore = Double.MAX_VALUE;

        for (String candidate : TEXT_ENCODING_CANDIDATES) {
            if (!Charset.isSupported(candidate)) continue;
            if (isStatefulEncodingName(candidate) && !hasRequiredStatefulSignature(candidate, data)) continue;
            TextEncodingProfile result = decodeCandidate(data, candidate);
            double adjusted = accuracyAdjustedScore(result, data, icuDetected, mozillaDetected);

            if ("korean".equals(guessEncodingFamily(result.charsetName)) && adjusted < bestKoreanScore) {
                bestKorean = result;
                bestKoreanScore = adjusted;
            }

            if (best == null || adjusted < bestScore) {
                second = best;
                secondScore = bestScore;
                best = result;
                bestScore = adjusted;
            } else if (adjusted < secondScore) {
                second = result;
                secondScore = adjusted;
            }
        }

        if (best == null) {
            return new FileUtils.EncodingResult("UTF-8", FileUtils.EncodingResult.LOW_CONFIDENCE, "fallback", "unicode");
        }

        boolean koreanSignalOverride = false;
        if (bestKorean != null && hasOverwhelmingKoreanSignal(bestKorean)
                && !"korean".equals(guessEncodingFamily(best.charsetName))) {
            // Do not let the Korean override fire when the same bytes are
            // Han-dominant under a Chinese charset, or a detector reports a
            // Chinese encoding — that means bestKorean is Chinese mojibake whose
            // incidental Hangul tripped the signal test.
            int chineseHan = maxChineseHanCount(data);
            boolean detectorSaysChinese =
                    (icuDetected != null && "chinese".equals(guessEncodingFamily(icuDetected)))
                    || (mozillaDetected != null && "chinese".equals(guessEncodingFamily(mozillaDetected)));
            boolean chineseDominates = bestKorean.hangulCount > 0
                    && (chineseHan >= bestKorean.hangulCount * KOREAN_HAN_DOMINANCE_RATIO
                        || detectorSaysChinese);
            if (!chineseDominates) {
                second = best;
                secondScore = bestScore;
                best = bestKorean;
                bestScore = bestKoreanScore;
                koreanSignalOverride = true;
            }
        }

        int confidence = accuracyConfidence(best, bestScore, second, secondScore);
        String source = koreanSignalOverride
                ? "korean-signal+accuracy-score"
                : detectorMergeSource(best.charsetName, icuDetected, mozillaDetected);
        String chosenCharset = normalizeAutoDetectedKoreanSuperset(best.charsetName);
        return new FileUtils.EncodingResult(chosenCharset, confidence, source, guessEncodingFamily(chosenCharset));
    }

    private static String normalizeAutoDetectedKoreanSuperset(String charsetName) {
        if (charsetName == null) return "UTF-8";
        String upper = charsetName.toUpperCase(Locale.ROOT);
        if (upper.equals("WINDOWS-949") || upper.equals("MS949") || upper.equals("CP949") || upper.equals("EUC-KR")) {
            // CP949/windows-949 is a practical superset of EUC-KR for Korean TXT.
            // Auto-detection should prefer the superset so EUC-KR-compatible files
            // still open correctly while CP949-only extension characters are not
            // lost. Manual EUC-KR selection remains available when the user wants it.
            return "windows-949";
        }
        return charsetName;
    }

    private static double accuracyAdjustedScore(TextEncodingProfile result, byte[] data, String icuDetected, String mozillaDetected) {
        double score = result.score;
        int length = Math.max(1, result.text.length());
        String family = guessEncodingFamily(result.charsetName);

        // Stateful 7-bit East Asian encodings are accepted only with their
        // concrete shift/designation signatures. Detector hints alone are not
        // enough because random legacy text can contain short ESC/~ sequences.
        if (isStatefulEncodingName(result.charsetName) && !hasRequiredStatefulSignature(result.charsetName, data)) {
            score += STATEFUL_SIGNATURE_MISSING_PENALTY;
        }

        if (icuDetected != null && result.charsetName.equalsIgnoreCase(icuDetected)) {
            score -= DETECTOR_ICU_EXACT_BONUS;
        }
        if (mozillaDetected != null && result.charsetName.equalsIgnoreCase(mozillaDetected)) {
            score -= DETECTOR_MOZILLA_EXACT_BONUS;
        }

        String resultFamily = guessEncodingFamily(result.charsetName);
        if (icuDetected != null && resultFamily.equals(guessEncodingFamily(icuDetected))) {
            score -= DETECTOR_ICU_FAMILY_BONUS;
        }
        if (mozillaDetected != null && resultFamily.equals(guessEncodingFamily(mozillaDetected))) {
            score -= DETECTOR_MOZILLA_FAMILY_BONUS;
        }
        if (icuDetected != null && mozillaDetected != null
                && guessEncodingFamily(icuDetected).equals(guessEncodingFamily(mozillaDetected))
                && resultFamily.equals(guessEncodingFamily(icuDetected))) {
            score -= DETECTOR_AGREEING_FAMILY_BONUS;
        }

        // Detector-confirmed CJK vs single-byte alphabetic mismatch. If ICU or
        // Mozilla reports a multibyte CJK family but this candidate is a
        // single-byte alphabetic code page, the candidate is almost certainly
        // the CJK text misread byte-by-byte. Single-byte pages accept nearly
        // every byte as a valid letter, so such a decode looks "clean" and can
        // otherwise win on raw character-count bonuses — which grow with length.
        // The penalty therefore scales with text length so it overcomes those
        // bonuses on long files as well as short ones.
        if (isSingleByteAlphabeticFamily(resultFamily)) {
            boolean icuSaysCjk = icuDetected != null && isCjkFamily(guessEncodingFamily(icuDetected));
            boolean mozillaSaysCjk = mozillaDetected != null && isCjkFamily(guessEncodingFamily(mozillaDetected));
            boolean detectorSupportsThisSingleByte = detectorConfidentlySupportsFamily(icuDetected, mozillaDetected, resultFamily, data);
            if ((icuSaysCjk || mozillaSaysCjk) && !detectorSupportsThisSingleByte) {
                score += DETECTOR_CJK_VS_SINGLE_BYTE_PENALTY
                        + length * DETECTOR_CJK_VS_SINGLE_BYTE_PER_CHAR_PENALTY;
            }
        }

        // Detector-confirmed single-byte family vs a different single-byte
        // family. Single-byte code pages accept almost any 0x80-0xFF byte as a
        // valid letter, so e.g. Greek text decodes just as "cleanly" as Cyrillic
        // and can win on raw character-count bonuses even though a detector
        // correctly identified the real script. When ICU/Mozilla gives a
        // confident single-byte family and there is no detector disagreement,
        // respect that family directly instead of applying the regional
        // preference order. CJK-vs-single-byte conflicts are still handled by
        // the CJK guard above.
        if (isSingleByteAlphabeticFamily(resultFamily)) {
            String detectorSingleByteFamily = detectorSingleByteFamily(icuDetected, mozillaDetected, data);
            if (detectorSingleByteFamily != null
                    && !detectorSingleByteFamily.equals(resultFamily)) {
                score += DETECTOR_SINGLE_BYTE_CROSS_FAMILY_PENALTY
                        + length * DETECTOR_SINGLE_BYTE_CROSS_FAMILY_PER_CHAR_PENALTY;
            }
        }

        // Heavy penalties for decoded text that is technically valid Unicode but
        // structurally implausible.
        score += Math.max(0, result.replacementCount - Math.max(2, length / 500)) * SCORE_REPLACEMENT_PENALTY;
        score += Math.max(0, result.badControlCount - Math.max(2, length / 350)) * SCORE_BAD_CONTROL_PENALTY;
        score += result.nulCount * SCORE_NUL_PENALTY;

        switch (family) {
            case "korean": {
                int signal = result.hangulCount + result.cjkCount;
                double density = signal / (double) length;

                // Guard against Chinese mojibake. If the same bytes decode to
                // substantially more Han characters under a Chinese charset than
                // they yield Hangul here, OR a statistical detector reports a
                // Chinese encoding, this "Korean" decode is incidental mojibake
                // from a Chinese (GBK/Big5/GB18030) file. Suppress the large
                // Korean signal bonuses and apply a penalty so the real Chinese
                // family can win. Genuine Korean text is Hangul-dominant and is
                // not flagged by the char-count ratio, and detectors report it
                // as Korean rather than Chinese.
                int chineseHan = maxChineseHanCount(data);
                boolean detectorSaysChinese =
                        (icuDetected != null && "chinese".equals(guessEncodingFamily(icuDetected)))
                        || (mozillaDetected != null && "chinese".equals(guessEncodingFamily(mozillaDetected)));
                boolean chineseDominates = result.hangulCount > 0
                        && (chineseHan >= result.hangulCount * KOREAN_HAN_DOMINANCE_RATIO
                            || detectorSaysChinese);

                if (chineseDominates) {
                    // Incidental Hangul from Chinese mojibake: do not award the
                    // per-character Hangul/CJK bonuses (they would otherwise let
                    // a Chinese file win as Korean), and apply a penalty.
                    score -= result.cjkCount * KOREAN_CJK_BONUS;
                    score += KOREAN_FAKE_HANGUL_PENALTY;
                } else {
                    // Hangul is a very strong signal because random legacy
                    // mojibake rarely forms sustained valid Korean syllables.
                    score -= result.hangulCount * KOREAN_HANGUL_BONUS;
                    score -= result.cjkCount * KOREAN_CJK_BONUS;
                    if (result.hangulCount >= 8 && density >= 0.04) score -= KOREAN_SUSTAINED_SIGNAL_BONUS;
                    if (result.hangulCount >= 80 && density >= 0.18) score -= KOREAN_STRONG_SIGNAL_BONUS;
                }
                if (result.hangulCount == 0 && result.cjkCount < 4) score += KOREAN_WEAK_SIGNAL_PENALTY;

                // CP949/MS949 is the practical superset for old Korean TXT. When
                // EUC-KR and CP949 are near-tied, prefer the superset.
                if (result.charsetName.equals("windows-949") || result.charsetName.equals("MS949")) score -= KOREAN_SUPERSET_BONUS;
                if (result.charsetName.equals("EUC-KR")) score += KOREAN_EUC_KR_TIE_PENALTY;
                break;
            }

            case "cyrillic": {
                double density = result.cyrillicCount / (double) length;
                score -= result.cyrillicCount * CYRILLIC_CHAR_BONUS;
                score -= result.cyrillicNaturalnessScore * CYRILLIC_NATURALNESS_BONUS;

                // Many non-Cyrillic legacy files, especially CP949 Korean text,
                // can decode into "valid" Cyrillic under ISO-8859-5/CP1251.
                // Those false positives tend to contain a high ratio of Serbian/
                // Ukrainian/Macedonian-only letters in otherwise Russian-looking
                // prose.  Penalize that pattern strongly so real Hangul/Hanja
                // evidence can beat Cyrillic mojibake automatically.
                score += result.cyrillicOddCount * CYRILLIC_ODD_CHAR_PENALTY;
                if (result.cyrillicCount > 0
                        && result.cyrillicOddCount > Math.max(8, result.cyrillicCount / 50)) {
                    score += CYRILLIC_ODD_PATTERN_BASE_PENALTY + result.cyrillicOddCount * CYRILLIC_ODD_PATTERN_EXTRA_PENALTY;
                }

                if (result.cyrillicCount >= 10 && density >= 0.04) score -= CYRILLIC_SUSTAINED_SIGNAL_BONUS;
                if (result.cyrillicCount < 8) score += CYRILLIC_WEAK_SIGNAL_PENALTY;

                if (result.charsetName.equals("ISO-8859-5")) {
                    score -= iso88595ByteShapeBonus(data) * CYRILLIC_BYTE_SHAPE_WEIGHT;
                } else if (result.charsetName.equals("windows-1251")) {
                    score -= windows1251ByteShapeBonus(data) * CYRILLIC_BYTE_SHAPE_WEIGHT;
                } else if (result.charsetName.startsWith("KOI8")) {
                    score += koi8BoxDrawingPenalty(result.text) * KOI8_BOX_DRAWING_PENALTY;
                }
                break;
            }

            case "japanese": {
                int signal = result.kanaCount + result.cjkCount;
                double density = signal / (double) length;
                score -= result.kanaCount * JAPANESE_KANA_BONUS;
                score -= result.cjkCount * JAPANESE_CJK_BONUS;
                if (result.kanaCount >= 6 && density >= 0.03) score -= JAPANESE_SUSTAINED_SIGNAL_BONUS;
                if (result.kanaCount == 0 && result.cjkCount < 8) score += JAPANESE_WEAK_SIGNAL_PENALTY;
                break;
            }

            case "chinese": {
                double density = result.cjkCount / (double) length;
                score -= result.cjkCount * CHINESE_CJK_BONUS;
                if (result.cjkCount >= 16 && density >= 0.06) score -= CHINESE_SUSTAINED_SIGNAL_BONUS;
                if (result.cjkCount < 8) score += CHINESE_WEAK_SIGNAL_PENALTY;
                break;
            }

            case "greek": {
                double density = result.greekCount / (double) length;
                score -= result.greekCount * SINGLE_SCRIPT_CHAR_BONUS;
                if (result.greekCount >= 8 && density >= 0.04) score -= SINGLE_SCRIPT_SUSTAINED_SIGNAL_BONUS;
                if (result.greekCount < 6) score += SINGLE_SCRIPT_WEAK_SIGNAL_PENALTY;
                break;
            }

            case "hebrew": {
                double density = result.hebrewCount / (double) length;
                score -= result.hebrewCount * SINGLE_SCRIPT_CHAR_BONUS;
                if (result.hebrewCount >= 8 && density >= 0.04) score -= SINGLE_SCRIPT_SUSTAINED_SIGNAL_BONUS;
                if (result.hebrewCount < 6) score += SINGLE_SCRIPT_WEAK_SIGNAL_PENALTY;
                break;
            }

            case "arabic": {
                double density = result.arabicCount / (double) length;
                score -= result.arabicCount * SINGLE_SCRIPT_CHAR_BONUS;
                if (result.arabicCount >= 8 && density >= 0.04) score -= SINGLE_SCRIPT_SUSTAINED_SIGNAL_BONUS;
                if (result.arabicCount < 6) score += SINGLE_SCRIPT_WEAK_SIGNAL_PENALTY;
                break;
            }

            case "thai": {
                double density = result.thaiCount / (double) length;
                score -= result.thaiCount * SINGLE_SCRIPT_CHAR_BONUS;
                if (result.thaiCount >= 8 && density >= 0.04) score -= SINGLE_SCRIPT_SUSTAINED_SIGNAL_BONUS;
                if (result.thaiCount < 6) score += SINGLE_SCRIPT_WEAK_SIGNAL_PENALTY;
                break;
            }

            case "vietnamese": {
                int signal = result.latinExtendedCount + TextEncodingProfile.countVietnameseToneMarks(result.text);
                double density = signal / (double) length;
                score -= signal * VIETNAMESE_SIGNAL_BONUS;
                if (signal >= 8 && density >= 0.025) score -= VIETNAMESE_SUSTAINED_SIGNAL_BONUS;
                if (signal < 4) score += VIETNAMESE_WEAK_SIGNAL_PENALTY;
                break;
            }

            case "western": {
                int nonAscii = Math.max(0, length - result.asciiCount);
                if (nonAscii < Math.max(4, length / 200) && result.badControlCount == 0) {
                    score -= WESTERN_ASCII_LIKE_BONUS;
                }
                if (result.cjkCount + result.hangulCount + result.kanaCount
                        + result.cyrillicCount + result.greekCount
                        + result.hebrewCount + result.arabicCount + result.thaiCount > length / 8) {
                    score += WESTERN_SCRIPT_MISMATCH_PENALTY;
                }
                break;
            }

            default:
                break;
        }

        // Short high-byte samples: damp single-byte alphabetic families when the
        // same bytes decode cleanly as multibyte CJK. This corrects short Korean
        // CP949 titles being scattered across Thai/Greek/Cyrillic/Western, etc.
        if (isSingleByteAlphabeticFamily(family)
                && data != null && data.length <= SHORT_SAMPLE_BYTE_LIMIT
                && shortSampleLooksMultibyteCjk(data)) {
            boolean detectorSupportsThisSingleByte = data.length >= SHORT_SAMPLE_DETECTOR_TRUST_MIN_BYTES
                    && detectorConfidentlySupportsFamily(icuDetected, mozillaDetected, family, data);
            // Do not let the short-sample CJK guard override a direct confident
            // detector match for a real single-byte text once the sample is long
            // enough for that detector hint to be meaningful. Very tiny samples
            // still keep the CJK guard because detector guesses can scatter short
            // CP949 Korean titles into Thai/Western/etc.
            if (!detectorSupportsThisSingleByte) {
                score += SHORT_SAMPLE_SINGLE_BYTE_PENALTY;
            }
        }

        return score;
    }

    /**
     * For short samples, returns true if the bytes contain high bytes AND at
     * least one multibyte CJK charset decodes them with almost no replacement
     * characters. Such a sample is far more likely to be CJK than a single-byte
     * code page that merely tolerates every byte. Used to damp single-byte
     * families on short inputs (see SHORT_SAMPLE_SINGLE_BYTE_PENALTY).
     */
    private static boolean shortSampleLooksMultibyteCjk(byte[] data) {
        if (data == null || data.length == 0 || data.length > SHORT_SAMPLE_BYTE_LIMIT) return false;

        boolean hasHighByte = false;
        for (byte b : data) {
            if ((b & 0xFF) >= 0x80) { hasHighByte = true; break; }
        }
        if (!hasHighByte) return false;

        for (String cjk : new String[]{"windows-949", "GB18030", "Shift_JIS", "Big5", "EUC-JP"}) {
            if (!Charset.isSupported(cjk)) continue;
            try {
                TextEncodingProfile r = decodeCandidate(data, cjk);
                int len = Math.max(1, r.text.length());
                boolean clean = r.replacementCount <= Math.max(1, len / 40)
                        && r.nulCount == 0;
                int cjkSignal = r.cjkCount + r.hangulCount + r.kanaCount;
                if (clean && cjkSignal >= 2) return true;
            } catch (Exception ignored) {
                // Charset present but decode failed — try the next candidate.
            }
        }
        return false;
    }

    private static boolean isCjkFamily(String family) {
        return "korean".equals(family) || "japanese".equals(family) || "chinese".equals(family);
    }

    private static boolean detectorReportsCjkFamily(String icuDetected, String mozillaDetected) {
        return (icuDetected != null && isCjkFamily(guessEncodingFamily(icuDetected)))
                || (mozillaDetected != null && isCjkFamily(guessEncodingFamily(mozillaDetected)));
    }

    private static boolean detectorReportsFamily(String icuDetected, String mozillaDetected, String family) {
        if (family == null || family.isEmpty()) return false;
        return (icuDetected != null && family.equals(guessEncodingFamily(icuDetected)))
                || (mozillaDetected != null && family.equals(guessEncodingFamily(mozillaDetected)));
    }

    private static boolean detectorConfidentlySupportsFamily(String icuDetected, String mozillaDetected, String family, byte[] data) {
        if (family == null || family.isEmpty()) return false;
        if (isSingleByteAlphabeticFamily(family)) {
            return family.equals(detectorConfidentSingleByteFamily(icuDetected, data))
                    || family.equals(detectorConfidentSingleByteFamily(mozillaDetected, data));
        }
        return detectorReportsFamily(icuDetected, mozillaDetected, family);
    }

    /**
     * Returns a confident single-byte alphabetic detector family, or null if the
     * detector path is absent, weak, non-single-byte, or internally conflicting.
     * Regional preference is not used here: if ICU and Mozilla name different
     * confident single-byte families, scoring is left to the normal accuracy
     * path instead of forcing a higher-priority region over a lower-priority one.
     */
    private static String detectorSingleByteFamily(String icuDetected, String mozillaDetected, byte[] data) {
        String icuFam = detectorConfidentSingleByteFamily(icuDetected, data);
        String mozFam = detectorConfidentSingleByteFamily(mozillaDetected, data);

        if (icuFam != null && mozFam != null) {
            return icuFam.equals(mozFam) ? icuFam : null;
        }
        if (icuFam != null) return icuFam;
        return mozFam;
    }

    /**
     * Maps a detector result to a confident single-byte family, or null.
     * Western is accepted only when the detector names an explicit Western
     * legacy charset such as Windows-1252 / ISO-8859-1 / ISO-8859-15 / MacRoman.
     * US-ASCII and generic/unknown fallbacks are still ignored. This intentionally
     * lets clearly detected Western Latin text win over Southeast Asian Latin or
     * Thai-looking single-byte candidates, while avoiding the weakest ASCII
     * fallback signal.
     */
    private static String detectorConfidentSingleByteFamily(String detected) {
        if (detected == null) return null;
        String fam = guessEncodingFamily(detected);
        if (!isSingleByteAlphabeticFamily(fam)) return null;
        if ("western".equals(fam) && !isExplicitWesternDetectorName(detected)) return null;
        return fam;
    }

    /**
     * Byte-aware call site kept for consistency with the CJK and short-sample
     * guards. At the family-confidence level, however, explicit Western detector
     * names are trusted instead of being overridden by a Vietnamese heuristic.
     * This matches the selected policy: if ICU/Mozilla confidently reports a
     * concrete single-byte family, that detector family should be respected;
     * weak US-ASCII/generic Western fallbacks are still ignored by
     * detectorConfidentSingleByteFamily(String).
     */
    private static String detectorConfidentSingleByteFamily(String detected, byte[] data) {
        return detectorConfidentSingleByteFamily(detected);
    }

    private static boolean isExplicitWesternDetectorName(String detected) {
        if (detected == null) return false;
        String n = detected.trim().toUpperCase(Locale.ROOT)
                .replace('_', '-')
                .replace(' ', '-');
        if (n.equals("US-ASCII") || n.equals("ASCII") || n.equals("ANSI-X3.4-1968")) return false;
        return n.equals("WINDOWS-1252")
                || n.equals("CP1252")
                || n.equals("MS-ANSI")
                || n.equals("ISO-8859-1")
                || n.equals("ISO-8859-15")
                || n.equals("MACROMAN")
                || n.equals("X-MACROMAN")
                || n.equals("IBM819")
                || n.equals("CP819")
                || n.equals("LATIN1")
                || n.equals("LATIN-1")
                || n.equals("L1");
    }

    private static boolean isSingleByteAlphabeticFamily(String family) {
        switch (family) {
            case "cyrillic":
            case "greek":
            case "hebrew":
            case "arabic":
            case "thai":
            case "western":
            case "vietnamese":
                return true;
            default:
                return false;
        }
    }

    private static boolean hasOverwhelmingKoreanSignal(TextEncodingProfile result) {
        if (result == null || result.text == null) return false;
        int length = Math.max(1, result.text.length());
        int signal = result.hangulCount + result.cjkCount;

        if (result.replacementCount > Math.max(3, length / 250)) return false;
        if (result.nulCount > 0) return false;
        if (result.badControlCount > Math.max(4, length / 160)) return false;

        // This deliberately requires strong sustained Hangul evidence. It prevents
        // PC/browser detector hints from stealing Korean CP949/MS949 novels as Thai,
        // Arabic, or other single-byte legacy encodings, while avoiding false
        // positives for real Thai/Cyrillic/Western files.
        return result.hangulCount >= 48
                && signal >= 80
                && (signal / (double) length) >= 0.08;
    }

    private static int accuracyConfidence(TextEncodingProfile best, double bestScore, TextEncodingProfile second, double secondScore) {
        if (best == null) return FileUtils.EncodingResult.LOW_CONFIDENCE;
        int length = Math.max(1, best.text.length());
        double margin = (second == null || equivalentEncodingForConfidence(best.charsetName, second.charsetName))
                ? 9999.0
                : secondScore - bestScore;

        if (best.replacementCount > Math.max(3, length / 250)
                || best.nulCount > 0
                || best.badControlCount > Math.max(4, length / 160)) {
            return FileUtils.EncodingResult.LOW_CONFIDENCE;
        }

        String family = guessEncodingFamily(best.charsetName);
        boolean strongFamilySignal = false;
        switch (family) {
            case "korean":
                strongFamilySignal = best.hangulCount >= 8 && (best.hangulCount + best.cjkCount) >= 12;
                break;
            case "cyrillic":
                strongFamilySignal = best.cyrillicCount >= 10 && best.cyrillicOddCount <= Math.max(3, best.cyrillicCount / 12);
                break;
            case "japanese":
                strongFamilySignal = best.kanaCount >= 6 || best.cjkCount >= 20;
                break;
            case "chinese":
                strongFamilySignal = best.cjkCount >= 20;
                break;
            case "greek":
                strongFamilySignal = best.greekCount >= 8;
                break;
            case "hebrew":
                strongFamilySignal = best.hebrewCount >= 8;
                break;
            case "arabic":
                strongFamilySignal = best.arabicCount >= 8;
                break;
            case "thai":
                strongFamilySignal = best.thaiCount >= 8;
                break;
            case "vietnamese":
                strongFamilySignal = best.latinExtendedCount + TextEncodingProfile.countVietnameseToneMarks(best.text) >= 8;
                break;
            case "western":
                strongFamilySignal = best.asciiCount > length * 0.65;
                break;
            default:
                strongFamilySignal = best.alphabeticScriptCount >= 12 || best.asciiCount > length * 0.70;
                break;
        }

        if (strongFamilySignal && margin >= 80.0) return FileUtils.EncodingResult.HIGH_CONFIDENCE;
        if (strongFamilySignal || margin >= 40.0) return FileUtils.EncodingResult.MEDIUM_CONFIDENCE;
        return FileUtils.EncodingResult.LOW_CONFIDENCE;
    }

    static String detectEncodingFromBytes(byte[] data) {
        return detectEncodingResultFromBytes(data).charsetName;
    }

    private static FileUtils.EncodingResult detectEncodingResultFromDataAdaptive(byte[] data) {
        if (data == null || data.length == 0) {
            return new FileUtils.EncodingResult("UTF-8", FileUtils.EncodingResult.HIGH_CONFIDENCE, "empty", "unicode");
        }

        byte[] primary = sampleBytes(data, PRIMARY_SAMPLE_LIMIT);
        FileUtils.EncodingResult result = detectEncodingResultFromBytes(primary);

        if (shouldExtendEncodingSample(result, primary.length, data.length)) {
            byte[] extended = sampleBytes(data, EXTENDED_SAMPLE_LIMIT);
            FileUtils.EncodingResult extendedResult = detectEncodingResultFromBytes(extended);
            if (extendedResult.confidence >= result.confidence
                    || result.confidence < FileUtils.EncodingResult.HIGH_CONFIDENCE) {
                return extendedResult;
            }
        }

        return result;
    }

    private static boolean shouldExtendEncodingSample(FileUtils.EncodingResult result, int sampleLength, long fullLength) {
        if (result == null) return true;
        if (fullLength <= sampleLength) return false;
        if (sampleLength >= EXTENDED_SAMPLE_LIMIT) return false;

        String source = result.source != null ? result.source : "";
        if ("bom".equals(source)
                || "strict-utf8".equals(source)
                || "utf8-sample-boundary".equals(source)
                || "utf16-heuristic".equals(source)
                || "escape".equals(source)) {
            return false;
        }

        // Legacy detector work is expensive. Only pay for the 512 KiB pass when
        // the first-pass decision is genuinely weak or detector-family consensus
        // did not produce a high-confidence result.
        return result.confidence < FileUtils.EncodingResult.HIGH_CONFIDENCE;
    }


    public static String[] getManualTextEncodingOptions() {
        return new String[]{
                "Auto",
                "UTF-8",
                "UTF-16LE",
                "UTF-16BE",
                "windows-949",
                "MS949",
                "EUC-KR",
                "windows-31j",
                "Shift_JIS",
                "EUC-JP",
                "ISO-2022-JP",
                "GB18030",
                "GBK",
                "Big5",
                "windows-1251",
                "ISO-8859-5",
                "KOI8-R",
                "KOI8-U",
                "windows-1252",
                "ISO-8859-1",
                "ISO-8859-15",
                "windows-1250",
                "ISO-8859-2",
                "windows-1253",
                "ISO-8859-7",
                "windows-1254",
                "ISO-8859-9",
                "windows-1257",
                "ISO-8859-13",
                "windows-1258",
                "windows-1255",
                "ISO-8859-8",
                "windows-1256",
                "ISO-8859-6",
                "windows-874",
                "ISO-8859-11",
                "IBM866",
                "x-MacRoman",
                "x-MacCyrillic",
                "x-MacGreek",
                "x-MacTurkish",
                "x-MacHebrew",
                "x-MacArabic",
                "GB2312",
                "HZ-GB-2312",
                "Big5-HKSCS",
                "EUC-TW",
                "ISO-2022-KR",
                "VISCII",
                "x-ISCII91"
        };
    }

    public static String normalizeManualEncodingName(String name) {
        String normalized = normalizeDetectedCharset(name);
        return normalized != null && Charset.isSupported(normalized) ? normalized : null;
    }

    private static boolean equivalentEncodingForConfidence(String a, String b) {
        if (a == null || b == null) return false;
        String na = a.toUpperCase(Locale.ROOT);
        String nb = b.toUpperCase(Locale.ROOT);
        if ((na.equals("WINDOWS-949") || na.equals("MS949"))
                && (nb.equals("WINDOWS-949") || nb.equals("MS949"))) return true;
        if ((na.equals("SHIFT_JIS") || na.equals("WINDOWS-31J"))
                && (nb.equals("SHIFT_JIS") || nb.equals("WINDOWS-31J"))) return true;
        return na.equals(nb);
    }

    private static String guessEncodingFamily(String charsetName) {
        if (charsetName == null) return "unknown";
        String n = charsetName.toUpperCase(Locale.ROOT);
        if (n.contains("949") || n.equals("MS949") || n.equals("EUC-KR") || n.equals("ISO-2022-KR")) return "korean";
        if (n.contains("SHIFT") || n.contains("31J") || n.equals("EUC-JP") || n.equals("ISO-2022-JP")) return "japanese";
        if (n.contains("GB") || n.contains("BIG5") || n.contains("HZ") || n.equals("EUC-TW")) return "chinese";
        if (n.equals("ISO-8859-5") || n.equals("WINDOWS-1251") || n.startsWith("KOI8")
                || n.equals("IBM866") || n.contains("MACCYRILLIC")) return "cyrillic";
        if (n.equals("UTF-8") || n.startsWith("UTF-16") || n.startsWith("UTF-32")) return "unicode";
        if (n.equals("WINDOWS-1253") || n.equals("ISO-8859-7") || n.contains("MACGREEK")) return "greek";
        if (n.equals("WINDOWS-1258") || n.equals("VISCII")) return "vietnamese";
        if (n.equals("WINDOWS-1255") || n.equals("ISO-8859-8") || n.contains("MACHEBREW")) return "hebrew";
        if (n.equals("WINDOWS-1256") || n.equals("ISO-8859-6") || n.contains("MACARABIC")) return "arabic";
        if (n.equals("WINDOWS-874") || n.equals("ISO-8859-11")
                || n.contains("TIS620") || n.contains("TIS-620") || n.contains("X-WINDOWS-874")) return "thai";
        return "western";
    }




    private static double iso88595ByteShapeBonus(byte[] data) {
        int b0bf = 0;
        int d0ef = 0;
        int c0cf = 0;
        int high = 0;

        for (byte raw : data) {
            int b = raw & 0xFF;
            if (b >= 0x80) high++;
            if (b >= 0xB0 && b <= 0xBF) b0bf++;
            if (b >= 0xD0 && b <= 0xEF) d0ef++;
            if (b >= 0xC0 && b <= 0xCF) c0cf++;
        }

        if (high == 0) return 0.0;
        double bonus = 0.0;
        bonus += Math.min(b0bf, 80) * 0.9;
        bonus += Math.min(d0ef, 160) * 0.28;
        bonus += Math.min(c0cf, 80) * 0.15;
        return bonus;
    }

    private static double windows1251ByteShapeBonus(byte[] data) {
        int c0ff = 0;
        int a8b8 = 0;
        int c1Controls = 0;

        for (byte raw : data) {
            int b = raw & 0xFF;
            if (b >= 0xC0 && b <= 0xFF) c0ff++;
            if (b == 0xA8 || b == 0xB8) a8b8++;
            if (b >= 0x80 && b <= 0x9F) c1Controls++;
        }

        double bonus = 0.0;
        bonus += Math.min(c0ff, 200) * 0.22;
        bonus += a8b8 * 2.0;
        bonus += Math.min(c1Controls, 40) * 0.35; // real cp1251 punctuation area, not ISO text controls
        return bonus;
    }

    private static int koi8BoxDrawingPenalty(String text) {
        if (text == null || text.isEmpty()) return 0;
        int penalty = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 0x2500 && ch <= 0x257F) penalty++;
        }
        return penalty;
    }

    private static String detectWithAndroidIcu(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            Class<?> detectorClass = Class.forName("android.icu.text.CharsetDetector");
            Object detector = detectorClass.getConstructor().newInstance();
            detectorClass.getMethod("setText", byte[].class).invoke(detector, (Object) data);
            Object match = detectorClass.getMethod("detect").invoke(detector);
            if (match == null) return null;

            Class<?> matchClass = match.getClass();
            Object nameObj = matchClass.getMethod("getName").invoke(match);
            Object confidenceObj = matchClass.getMethod("getConfidence").invoke(match);
            if (!(nameObj instanceof String) || !(confidenceObj instanceof Integer)) return null;

            String name = normalizeDetectedCharset((String) nameObj);
            int confidence = (Integer) confidenceObj;
            if (name == null || confidence < 40 || !Charset.isSupported(name)) return null;
            if (isStatefulEncodingName(name) && !hasRequiredStatefulSignature(name, data)) return null;

            TextEncodingProfile result = decodeCandidate(data, name);
            int length = Math.max(1, result.text.length());
            if (result.replacementCount > Math.max(2, length / 100)) return null;
            if (result.nulCount > Math.max(1, length / 200)) return null;
            // Control bytes (e.g. literal ESC 0x1B) present in the source survive
            // any decode, so badControlCount has little discriminating power for a
            // charset that ICU already reports with high confidence. Only apply
            // the badControl rejection to lower-confidence ICU hints; a strong ICU
            // result (>= 90) is trusted even when the text carries stray controls.
            // Stateful ISO-2022 encodings are excluded from this leniency because
            // their detection is handled separately and is escape-sensitive.
            boolean strongIcuHint = confidence >= 90 && !isStatefulEncodingName(name);
            if (!strongIcuHint && result.badControlCount > Math.max(2, length / 80)) return null;
            return name;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static String detectorMergeSource(String bestCharset, String icuDetected, String mozillaDetected) {
        boolean icuExact = icuDetected != null && bestCharset != null && bestCharset.equalsIgnoreCase(icuDetected);
        boolean mozExact = mozillaDetected != null && bestCharset != null && bestCharset.equalsIgnoreCase(mozillaDetected);
        boolean icuFamily = icuDetected != null && bestCharset != null
                && guessEncodingFamily(bestCharset).equals(guessEncodingFamily(icuDetected));
        boolean mozFamily = mozillaDetected != null && bestCharset != null
                && guessEncodingFamily(bestCharset).equals(guessEncodingFamily(mozillaDetected));

        if (icuExact && mozExact) return "icu+mozilla+accuracy-score";
        if (mozExact && icuFamily) return "mozilla+icu-family+accuracy-score";
        if (icuExact && mozFamily) return "icu+mozilla-family+accuracy-score";
        if (mozExact) return "mozilla+accuracy-score";
        if (icuExact) return "icu+accuracy-score";
        if (icuFamily && mozFamily) return "icu-family+mozilla-family+accuracy-score";
        if (mozFamily) return "mozilla-family+accuracy-score";
        if (icuFamily) return "icu-family+accuracy-score";
        return "accuracy-score";
    }

    private static String detectWithMozillaUniversalChardet(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            Class<?> detectorClass = Class.forName("org.mozilla.universalchardet.UniversalDetector");
            Object detector = null;

            for (java.lang.reflect.Constructor<?> ctor : detectorClass.getConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 0) {
                    detector = ctor.newInstance();
                    break;
                }
                if (params.length == 1) {
                    detector = ctor.newInstance(new Object[]{null});
                    break;
                }
            }
            if (detector == null) return null;

            detectorClass.getMethod("handleData", byte[].class, int.class, int.class)
                    .invoke(detector, data, 0, data.length);
            detectorClass.getMethod("dataEnd").invoke(detector);
            Object detected = detectorClass.getMethod("getDetectedCharset").invoke(detector);

            try {
                detectorClass.getMethod("reset").invoke(detector);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Some versions do not require explicit reset for a one-shot instance.
            }

            if (!(detected instanceof String)) return null;
            String name = normalizeDetectedCharset((String) detected);
            if (name == null || !Charset.isSupported(name)) return null;
            if (isStatefulEncodingName(name) && !hasRequiredStatefulSignature(name, data)) return null;

            TextEncodingProfile result = decodeCandidate(data, name);
            int length = Math.max(1, result.text.length());
            if (result.replacementCount > Math.max(2, length / 120)) return null;
            if (result.nulCount > Math.max(1, length / 240)) return null;
            if (result.badControlCount > Math.max(2, length / 90)) return null;
            return name;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static String normalizeDetectedCharset(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return null;
        String upper = trimmed.toUpperCase(Locale.ROOT).replace('_', '-');
        if (upper.equals("ISO-8859-8-I")) return "ISO-8859-8";
        if (upper.equals("UTF32") || upper.equals("UTF-32")) return "UTF-32";
        if (upper.equals("UTF32LE") || upper.equals("UTF-32LE")) return "UTF-32LE";
        if (upper.equals("UTF32BE") || upper.equals("UTF-32BE")) return "UTF-32BE";
        if (upper.equals("MS949") || upper.equals("CP949") || upper.equals("X-WINDOWS-949")
                || upper.equals("KS-C-5601") || upper.equals("KS-C-5601-1987")) return "windows-949";
        if (upper.equals("ISO2022KR") || upper.equals("ISO-2022-KR")) return "ISO-2022-KR";
        if (upper.equals("ISO2022JP") || upper.equals("ISO-2022-JP")) return "ISO-2022-JP";
        if (upper.equals("WINDOWS-31J") || upper.equals("MS932") || upper.equals("CP932")) return "windows-31j";
        if (upper.equals("SHIFT-JIS") || upper.equals("SHIFT_JIS") || upper.equals("SJIS")) return "Shift_JIS";
        if (upper.equals("CP1250")) return "windows-1250";
        if (upper.equals("CP1251")) return "windows-1251";
        if (upper.equals("CP1252")) return "windows-1252";
        if (upper.equals("CP1253")) return "windows-1253";
        if (upper.equals("CP1254")) return "windows-1254";
        if (upper.equals("CP1255")) return "windows-1255";
        if (upper.equals("CP1256")) return "windows-1256";
        if (upper.equals("CP1257")) return "windows-1257";
        if (upper.equals("CP1258")) return "windows-1258";
        if (upper.equals("CP874")) return "windows-874";
        if (upper.equals("CP866") || upper.equals("IBM-866") || upper.equals("866")) return "IBM866";
        if (upper.equals("GB2312") || upper.equals("GB-2312") || upper.equals("EUC-CN")) return "GB2312";
        if (upper.equals("HZ") || upper.equals("HZGB2312") || upper.equals("HZ-GB2312")) return "HZ-GB-2312";
        if (upper.equals("BIG5HKSCS") || upper.equals("BIG5-HKSCS")) return "Big5-HKSCS";
        if (upper.equals("EUCHT") || upper.equals("EUC-TW")) return "EUC-TW";
        if (upper.equals("MACROMAN") || upper.equals("X-MAC-ROMAN")) return "x-MacRoman";
        if (upper.equals("MACCYRILLIC") || upper.equals("X-MAC-CYRILLIC")) return "x-MacCyrillic";
        if (upper.equals("MACGREEK") || upper.equals("X-MAC-GREEK")) return "x-MacGreek";
        if (upper.equals("MACTURKISH") || upper.equals("X-MAC-TURKISH")) return "x-MacTurkish";
        if (upper.equals("MACHEBREW") || upper.equals("X-MAC-HEBREW")) return "x-MacHebrew";
        if (upper.equals("MACARABIC") || upper.equals("X-MAC-ARABIC")) return "x-MacArabic";
        if (upper.equals("ISO8859-5") || upper.equals("ISO-8859-5") || upper.equals("ISO-IR-144")
                || upper.equals("CYRILLIC") || upper.equals("CSISOLATINCYRILLIC")) return "ISO-8859-5";
        return trimmed;
    }

    /**
     * Read entire text file with detected encoding and safe replacement.
     */
    public static String readTextFile(File file) throws IOException {
        byte[] data = readAllBytes(file);
        String encoding = detectEncodingResultFromDataAdaptive(data).charsetName;
        return decodeBestEffort(data, encoding);
    }

    /**
     * Read text file with specified encoding and safe replacement.
     */
    public static String readTextFile(File file, String encoding) throws IOException {
        byte[] data = readAllBytes(file);
        return decodeBestEffort(data, encoding);
    }

    /**
     * Read a text file into decoded chunks.
     * For correctness with variable-width encodings, decode once and split by Java chars.
     */
    public static List<TextChunk> readTextFileAsChunks(File file, int targetChunkChars) throws IOException {
        String text = readTextFile(file);
        int chunkSize = Math.max(2000, targetChunkChars);
        ArrayList<TextChunk> chunks = new ArrayList<>();

        if (text.isEmpty()) {
            chunks.add(new TextChunk(0, 0, ""));
            return chunks;
        }

        int chunkIndex = 0;
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkSize);
            end = FileUtils.clampToSurrogateSafeEnd(text, end);
            if (end <= start) {
                end = Math.min(text.length(), start + chunkSize);
            }
            chunks.add(new TextChunk(chunkIndex++, start, text.substring(start, end)));
            start = end;
        }
        return chunks;
    }

    /**
     * Read text from a content URI.
     */
    public static String readTextFromUri(Context context, Uri uri) throws IOException {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) throw new IOException("Cannot open URI: " + uri);
            byte[] data = readAllBytes(is);
            String encoding = detectEncodingResultFromDataAdaptive(data).charsetName;
            return decodeBestEffort(data, encoding);
        }
    }

    static String decodeBestEffort(byte[] data, String encoding) {
        String bom = detectBom(data);
        int offset = bomOffset(data);
        byte[] body = offset > 0 ? Arrays.copyOfRange(data, offset, data.length) : data;

        // A BOM is explicit metadata from the file. Do not let broad legacy
        // candidate scoring override it, or UTF-8-BOM Korean/Unicode text can be
        // misread as a single-byte/CJK legacy encoding when mojibake happens to
        // receive a lower score.
        if (bom != null && Charset.isSupported(bom)) {
            return TextStringUtils.sanitizeDecodedText(decodeCandidate(body, bom).text);
        }

        // detectEncodingFromBytes() already performs BOM, UTF-16 heuristics,
        // strict UTF-8 validation, ICU detection, and legacy fallback scoring.
        // Once it selects an encoding, trust that selection here instead of
        // rescoring every candidate on the full file and accidentally changing
        // the result for later chunks or longer files.
        String selected = normalizeDetectedCharset(encoding);
        if (selected != null && Charset.isSupported(selected)) {
            return TextStringUtils.sanitizeDecodedText(decodeCandidate(body, selected).text);
        }

        TextEncodingProfile best = null;
        for (String candidate : TEXT_ENCODING_CANDIDATES) {
            if (!Charset.isSupported(candidate)) continue;
            if (isStatefulEncodingName(candidate) && !hasRequiredStatefulSignature(candidate, body)) continue;
            TextEncodingProfile result = decodeCandidate(body, candidate);
            if (best == null || result.score < best.score) {
                best = result;
            }
        }

        if (best == null) {
            best = decodeCandidate(body, "UTF-8");
        }

        return TextStringUtils.sanitizeDecodedText(best.text);
    }

    /**
     * Decodes the same bytes through the strongest available Chinese charsets
     * and returns the largest Han (CJK ideograph) count produced. Used to
     * detect "Korean" decodes that are actually Chinese mojibake: genuine
     * Korean text is Hangul-dominant, whereas Chinese text misread as CP949
     * yields incidental Hangul but a much larger true Han count under GB18030 /
     * GBK / Big5. Returns 0 if no Chinese charset is available.
     */
    private static int maxChineseHanCount(byte[] data) {
        int best = 0;
        for (String cn : new String[]{"GB18030", "GBK", "Big5"}) {
            if (!Charset.isSupported(cn)) continue;
            try {
                TextEncodingProfile cnResult = decodeCandidate(data, cn);
                // Skip decodes that are mostly replacement characters — those
                // are not a credible Chinese interpretation.
                int len = Math.max(1, cnResult.text.length());
                if (cnResult.replacementCount > Math.max(2, len / 20)) continue;
                if (cnResult.cjkCount > best) best = cnResult.cjkCount;
            } catch (Exception ignored) {
                // Charset present but decode failed — ignore this candidate.
            }
        }
        return best;
    }

    private static TextEncodingProfile decodeCandidate(byte[] data, String charsetName) {
        try {
            Charset charset = Charset.forName(charsetName);
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .replaceWith("\uFFFD");

            CharBuffer chars = decoder.decode(ByteBuffer.wrap(data));
            return new TextEncodingProfile(charsetName, chars.toString());
        } catch (Exception e) {
            return new TextEncodingProfile(charsetName, "");
        }
    }

    private static byte[] readSample(File file) throws IOException {
        return readSample(file, SAMPLE_LIMIT);
    }

    private static byte[] readSample(File file, int limit) throws IOException {
        try (InputStream is = new FileInputStream(file)) {
            byte[] sample = readSample(is, limit);
            return file.length() > sample.length ? trimUtf8SampleBoundary(sample) : sample;
        }
    }

    private static byte[] readSample(InputStream is, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int n;

        while (total < limit && (n = is.read(buffer, 0, Math.min(buffer.length, limit - total))) != -1) {
            out.write(buffer, 0, n);
            total += n;
        }

        byte[] sample = out.toByteArray();
        return total >= limit ? trimUtf8SampleBoundary(sample) : sample;
    }

    private static byte[] readAllBytes(File file) throws IOException {
        try (InputStream is = new FileInputStream(file)) {
            return readAllBytes(is);
        }
    }

    static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        int n;

        while ((n = is.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }

        return out.toByteArray();
    }

    static byte[] sampleBytes(byte[] data) {
        return sampleBytes(data, SAMPLE_LIMIT);
    }

    private static byte[] sampleBytes(byte[] data, int limit) {
        if (data == null || data.length <= limit) return data;
        return trimUtf8SampleBoundary(Arrays.copyOf(data, limit));
    }

    private static byte[] trimUtf8SampleBoundary(byte[] sample) {
        if (sample == null || sample.length == 0) return sample;

        int safeEnd = utf8SafeBoundary(sample, sample.length);
        if (safeEnd <= 0 || safeEnd >= sample.length) return sample;
        return Arrays.copyOf(sample, safeEnd);
    }

    private static int utf8SafeBoundary(byte[] data, int limit) {
        if (data == null || data.length == 0) return 0;

        int end = Math.min(Math.max(0, limit), data.length);
        if (end <= 0 || end >= data.length) {
            // Still useful for fixed-size readSample(): end may equal sample.length
            // even when the original file continues beyond the sample.
            end = Math.min(Math.max(0, limit), data.length);
        }
        if (end <= 0) return 0;

        int last = data[end - 1] & 0xFF;
        if (last < 0x80) return end;

        int lead = end - 1;
        int continuationCount = 0;
        while (lead >= 0 && ((data[lead] & 0xC0) == 0x80) && continuationCount < 4) {
            lead--;
            continuationCount++;
        }

        if (lead < 0) return end;

        int leadByte = data[lead] & 0xFF;
        int expected;
        if ((leadByte & 0x80) == 0) {
            return end;
        } else if ((leadByte & 0xE0) == 0xC0) {
            expected = 2;
        } else if ((leadByte & 0xF0) == 0xE0) {
            expected = 3;
        } else if ((leadByte & 0xF8) == 0xF0) {
            expected = 4;
        } else {
            return end;
        }

        int available = end - lead;
        if (available < expected) {
            return lead;
        }
        return end;
    }

    private static String detectBom(byte[] data) {
        // Check UTF-32 before UTF-16 because UTF-32LE starts with FF FE.
        if (data.length >= 4
                && data[0] == BOM_UTF32_LE[0]
                && data[1] == BOM_UTF32_LE[1]
                && data[2] == BOM_UTF32_LE[2]
                && data[3] == BOM_UTF32_LE[3]
                && Charset.isSupported("UTF-32LE")) {
            return "UTF-32LE";
        }

        if (data.length >= 4
                && data[0] == BOM_UTF32_BE[0]
                && data[1] == BOM_UTF32_BE[1]
                && data[2] == BOM_UTF32_BE[2]
                && data[3] == BOM_UTF32_BE[3]
                && Charset.isSupported("UTF-32BE")) {
            return "UTF-32BE";
        }

        if (data.length >= 3
                && data[0] == BOM_UTF8[0]
                && data[1] == BOM_UTF8[1]
                && data[2] == BOM_UTF8[2]) {
            return "UTF-8";
        }

        if (data.length >= 2
                && data[0] == BOM_UTF16_LE[0]
                && data[1] == BOM_UTF16_LE[1]) {
            return "UTF-16LE";
        }

        if (data.length >= 2
                && data[0] == BOM_UTF16_BE[0]
                && data[1] == BOM_UTF16_BE[1]) {
            return "UTF-16BE";
        }

        return null;
    }

    private static int bomOffset(byte[] data) {
        if (data.length >= 4
                && ((data[0] == BOM_UTF32_LE[0] && data[1] == BOM_UTF32_LE[1]
                        && data[2] == BOM_UTF32_LE[2] && data[3] == BOM_UTF32_LE[3])
                || (data[0] == BOM_UTF32_BE[0] && data[1] == BOM_UTF32_BE[1]
                        && data[2] == BOM_UTF32_BE[2] && data[3] == BOM_UTF32_BE[3]))) {
            return 4;
        }

        if (data.length >= 3
                && data[0] == BOM_UTF8[0]
                && data[1] == BOM_UTF8[1]
                && data[2] == BOM_UTF8[2]) {
            return 3;
        }

        if (data.length >= 2
                && ((data[0] == BOM_UTF16_LE[0] && data[1] == BOM_UTF16_LE[1])
                || (data[0] == BOM_UTF16_BE[0] && data[1] == BOM_UTF16_BE[1]))) {
            return 2;
        }

        return 0;
    }

    private static boolean isStrictValidUtf8(byte[] data) {
        return validateUtf8(data, false).valid;
    }

    private static boolean isUtf8WithOnlyTrailingSampleBoundaryIssue(byte[] data) {
        Utf8Validation validation = validateUtf8(data, true);
        return validation.valid
                && validation.trailingBoundaryIssue
                && data != null
                && data.length >= SAMPLE_LIMIT
                && validation.validByteCount >= Math.max(0, data.length - 4);
    }

    private static class Utf8Validation {
        final boolean valid;
        final boolean trailingBoundaryIssue;
        final int multibyteCount;
        final int validByteCount;

        Utf8Validation(boolean valid, boolean trailingBoundaryIssue, int multibyteCount, int validByteCount) {
            this.valid = valid;
            this.trailingBoundaryIssue = trailingBoundaryIssue;
            this.multibyteCount = multibyteCount;
            this.validByteCount = validByteCount;
        }
    }

    private static Utf8Validation validateUtf8(byte[] data, boolean allowTrailingIncomplete) {
        if (data == null) return new Utf8Validation(false, false, 0, 0);

        int i = 0;
        int multibyte = 0;

        while (i < data.length) {
            int b = data[i] & 0xFF;
            int seqLen;

            if (b <= 0x7F) {
                i++;
                continue;
            } else if (b >= 0xC2 && b <= 0xDF) {
                seqLen = 2;
            } else if (b >= 0xE0 && b <= 0xEF) {
                seqLen = 3;
            } else if (b >= 0xF0 && b <= 0xF4) {
                seqLen = 4;
            } else {
                return new Utf8Validation(false, false, multibyte, i);
            }

            if (i + seqLen > data.length) {
                return new Utf8Validation(allowTrailingIncomplete, allowTrailingIncomplete, multibyte, i);
            }

            for (int j = 1; j < seqLen; j++) {
                if ((data[i + j] & 0xC0) != 0x80) {
                    return new Utf8Validation(false, false, multibyte, i);
                }
            }

            // Reject overlong sequences, UTF-16 surrogate halves, and code points
            // above U+10FFFF. This avoids treating arbitrary legacy bytes as UTF-8.
            if (seqLen == 3) {
                int b1 = data[i + 1] & 0xFF;
                if (b == 0xE0 && b1 < 0xA0) return new Utf8Validation(false, false, multibyte, i);
                if (b == 0xED && b1 > 0x9F) return new Utf8Validation(false, false, multibyte, i);
            } else if (seqLen == 4) {
                int b1 = data[i + 1] & 0xFF;
                if (b == 0xF0 && b1 < 0x90) return new Utf8Validation(false, false, multibyte, i);
                if (b == 0xF4 && b1 > 0x8F) return new Utf8Validation(false, false, multibyte, i);
            }

            multibyte++;
            i += seqLen;
        }

        // ASCII-only files are also valid UTF-8.
        return new Utf8Validation(true, false, multibyte, i);
    }

    private static boolean looksLikeIso2022Jp(byte[] data) {
        if (data == null || data.length < 8 || containsRawHighBytes(data)) return false;

        // ISO-2022-JP is stateful and 7-bit. A single ESC $ B byte trio is not
        // enough evidence because unrelated legacy/binary snippets may contain
        // that ASCII sequence. Require a real transition into JIS state, valid
        // 7-bit text while shifted, and a transition back out.
        int state = 0; // 0 = ASCII/Roman, 1 = JIS X 0208 two-byte, 2 = JIS kana
        boolean sawJisShift = false;
        boolean sawAsciiReset = false;
        int jisPairs = 0;
        int kanaBytes = 0;
        int invalidEscape = 0;

        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 0xFF;
            if (b == 0x1B) {
                if (i + 2 >= data.length) return false;
                int b1 = data[++i] & 0xFF;
                int b2 = data[++i] & 0xFF;
                if (b1 == 0x24 && (b2 == 0x40 || b2 == 0x42)) {
                    state = 1;
                    sawJisShift = true;
                    continue;
                }
                if (b1 == 0x28 && (b2 == 0x42 || b2 == 0x4A)) {
                    if (state != 0) sawAsciiReset = true;
                    state = 0;
                    continue;
                }
                if (b1 == 0x28 && b2 == 0x49) {
                    state = 2;
                    sawJisShift = true;
                    continue;
                }
                invalidEscape++;
                continue;
            }

            if (state == 1) {
                if (b == '\n' || b == '\r' || b == '\t' || b == ' ') continue;
                if (b < 0x21 || b > 0x7E || i + 1 >= data.length) return false;
                int b2 = data[++i] & 0xFF;
                if (b2 < 0x21 || b2 > 0x7E) return false;
                jisPairs++;
            } else if (state == 2) {
                if (b == '\n' || b == '\r' || b == '\t' || b == ' ') continue;
                if (b < 0x21 || b > 0x5F) return false;
                kanaBytes++;
            }
        }

        return sawJisShift && sawAsciiReset && state == 0
                && (jisPairs >= 2 || kanaBytes >= 4)
                && invalidEscape == 0;
    }

    private static boolean looksLikeIso2022Kr(byte[] data) {
        if (data == null || data.length < 10 || containsRawHighBytes(data)) return false;

        // ISO-2022-KR requires the ESC $ ) C designation and SO/SI shifted
        // KSC 5601 byte pairs. Treat the designation alone as insufficient.
        boolean sawDesignation = false;
        boolean inKorean = false;
        boolean sawShiftIn = false;
        boolean sawShiftOut = false;
        int kscPairs = 0;

        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 0xFF;
            if (b == 0x1B) {
                if (i + 3 >= data.length) return false;
                int b1 = data[++i] & 0xFF;
                int b2 = data[++i] & 0xFF;
                int b3 = data[++i] & 0xFF;
                if (b1 == 0x24 && b2 == 0x29 && b3 == 0x43) {
                    sawDesignation = true;
                    continue;
                }
                return false;
            }

            if (b == 0x0E) { // SO: shift to Korean
                if (!sawDesignation) return false;
                inKorean = true;
                sawShiftIn = true;
                continue;
            }
            if (b == 0x0F) { // SI: shift back to ASCII
                if (!inKorean) return false;
                inKorean = false;
                sawShiftOut = true;
                continue;
            }

            if (inKorean) {
                if (b == '\n' || b == '\r' || b == '\t' || b == ' ') continue;
                if (b < 0x21 || b > 0x7E || i + 1 >= data.length) return false;
                int b2 = data[++i] & 0xFF;
                if (b2 < 0x21 || b2 > 0x7E) return false;
                kscPairs++;
            }
        }

        return sawDesignation && sawShiftIn && sawShiftOut && !inKorean && kscPairs >= 2;
    }

    private static boolean containsRawHighBytes(byte[] data) {
        if (data == null) return false;
        for (byte raw : data) {
            if ((raw & 0xFF) >= 0x80) return true;
        }
        return false;
    }

    private static boolean isIso2022JpName(String charsetName) {
        if (charsetName == null) return false;
        String n = charsetName.toUpperCase(Locale.ROOT).replace("_", "-");
        return n.equals("ISO2022JP") || n.equals("ISO-2022-JP") || n.equals("CSISO2022JP");
    }

    private static boolean isIso2022KrName(String charsetName) {
        if (charsetName == null) return false;
        String n = charsetName.toUpperCase(Locale.ROOT).replace("_", "-");
        return n.equals("ISO2022KR") || n.equals("ISO-2022-KR") || n.equals("CSISO2022KR");
    }

    private static boolean isStatefulEncodingName(String charsetName) {
        return isHzGb2312Name(charsetName) || isIso2022JpName(charsetName) || isIso2022KrName(charsetName);
    }

    private static boolean hasRequiredStatefulSignature(String charsetName, byte[] data) {
        if (isHzGb2312Name(charsetName)) return looksLikeHzGb2312(data);
        if (isIso2022JpName(charsetName)) return looksLikeIso2022Jp(data);
        if (isIso2022KrName(charsetName)) return looksLikeIso2022Kr(data);
        return true;
    }

    private static boolean isHzGb2312Name(String charsetName) {
        if (charsetName == null) return false;
        String n = charsetName.toUpperCase(Locale.ROOT).replace("_", "-");
        return n.equals("HZ") || n.equals("HZGB2312") || n.equals("HZ-GB2312") || n.equals("HZ-GB-2312");
    }

    private static boolean looksLikeHzGb2312(byte[] data) {
        if (data == null || data.length < 6) return false;

        // HZ-GB-2312 is 7-bit. If the sample contains raw high bytes, it is
        // much more likely to be CP949/GBK/Big5/etc. than HZ. This is the main
        // guard against Korean Windows-949 being stolen as HZ-GB-2312.
        for (byte raw : data) {
            if ((raw & 0xFF) >= 0x80) return false;
        }

        boolean inGb = false;
        boolean sawShiftIn = false;
        boolean sawShiftOut = false;
        int gbBytePairs = 0;
        int invalidEscape = 0;

        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 0xFF;
            if (b == '~') {
                if (i + 1 >= data.length) return false;
                int next = data[++i] & 0xFF;
                if (next == '{') {
                    inGb = true;
                    sawShiftIn = true;
                    continue;
                }
                if (next == '}') {
                    if (!inGb) invalidEscape++;
                    inGb = false;
                    sawShiftOut = true;
                    continue;
                }
                if (next == '~' || next == '\n' || next == '\r') {
                    continue;
                }
                invalidEscape++;
                continue;
            }

            if (inGb) {
                if (b == '\n' || b == '\r' || b == '\t' || b == ' ') {
                    continue;
                }
                if (b < 0x21 || b > 0x7E || i + 1 >= data.length) {
                    return false;
                }
                int b2 = data[++i] & 0xFF;
                if (b2 < 0x21 || b2 > 0x7E) {
                    return false;
                }
                gbBytePairs++;
            }
        }

        return sawShiftIn && sawShiftOut && !inGb && gbBytePairs >= 2 && invalidEscape == 0;
    }


    private static String detectBomlessUtf16(byte[] data) {
        if (data == null || data.length < 8) return null;

        int limit = Math.min(data.length, SAMPLE_LIMIT);
        int pairs = limit / 2;
        if (pairs < 4) return null;

        int zeroEven = 0;
        int zeroOdd = 0;
        int leAsciiPairs = 0;
        int beAsciiPairs = 0;
        int leNewlinePairs = 0;
        int beNewlinePairs = 0;

        for (int i = 0; i + 1 < limit; i += 2) {
            int b0 = data[i] & 0xFF;
            int b1 = data[i + 1] & 0xFF;

            if (b0 == 0) zeroEven++;
            if (b1 == 0) zeroOdd++;

            if (b1 == 0 && isLikelySingleByteText(b0)) leAsciiPairs++;
            if (b0 == 0 && isLikelySingleByteText(b1)) beAsciiPairs++;

            if (b1 == 0 && (b0 == '\n' || b0 == '\r')) leNewlinePairs++;
            if (b0 == 0 && (b1 == '\n' || b1 == '\r')) beNewlinePairs++;
        }

        double zeroEvenRatio = zeroEven / (double) pairs;
        double zeroOddRatio = zeroOdd / (double) pairs;
        double leAsciiRatio = leAsciiPairs / (double) pairs;
        double beAsciiRatio = beAsciiPairs / (double) pairs;

        // Strong ASCII/newline evidence. This covers the common no-BOM UTF-16
        // files that previously looked like strict-valid UTF-8 with embedded NULs.
        if ((zeroOddRatio >= 0.30 && leAsciiRatio >= 0.25 && leAsciiPairs >= beAsciiPairs * 2)
                || (zeroOddRatio >= 0.20 && leAsciiRatio >= 0.15
                && leNewlinePairs >= 2 && leNewlinePairs > beNewlinePairs)) {
            return "UTF-16LE";
        }
        if ((zeroEvenRatio >= 0.30 && beAsciiRatio >= 0.25 && beAsciiPairs >= leAsciiPairs * 2)
                || (zeroEvenRatio >= 0.20 && beAsciiRatio >= 0.15
                && beNewlinePairs >= 2 && beNewlinePairs > leNewlinePairs)) {
            return "UTF-16BE";
        }

        // Non-ASCII no-BOM UTF-16 is deliberately conservative. Pure CJK UTF-16
        // without ASCII/NUL lane structure is hard to distinguish from arbitrary
        // legacy bytes, so do not force a Unicode result unless the byte lanes
        // still show a strong UTF-16 signature and one endian clearly decodes
        // better than the other.
        if (Math.max(zeroEvenRatio, zeroOddRatio) < 0.24
                || Math.abs(zeroEven - zeroOdd) < Math.max(4, pairs / 12)) {
            return null;
        }

        TextEncodingProfile le = decodeCandidate(data, "UTF-16LE");
        TextEncodingProfile be = decodeCandidate(data, "UTF-16BE");
        boolean lePlausible = isPlausibleUtf16Text(le);
        boolean bePlausible = isPlausibleUtf16Text(be);

        if (!lePlausible && !bePlausible) return null;

        int leSignal = utf16TextSignal(le);
        int beSignal = utf16TextSignal(be);
        int signalMargin = Math.max(8, pairs / 12);

        if (lePlausible && !bePlausible && zeroOddRatio >= zeroEvenRatio * 1.60) return "UTF-16LE";
        if (bePlausible && !lePlausible && zeroEvenRatio >= zeroOddRatio * 1.60) return "UTF-16BE";
        if (lePlausible && zeroOddRatio >= zeroEvenRatio * 1.60 && leSignal >= beSignal + signalMargin) return "UTF-16LE";
        if (bePlausible && zeroEvenRatio >= zeroOddRatio * 1.60 && beSignal >= leSignal + signalMargin) return "UTF-16BE";

        // Ambiguous no-BOM UTF-16 should not be guessed just because both
        // decoders produced some plausible Unicode. Let legacy scoring handle it
        // rather than forcing a loose Unicode decision.
        return null;
    }

    private static boolean isPlausibleUtf16Text(TextEncodingProfile result) {
        if (result == null || result.text == null || result.text.isEmpty()) return false;
        int length = Math.max(1, result.text.length());
        if (result.replacementCount > Math.max(2, length / 120)) return false;
        if (result.nulCount > Math.max(1, length / 300)) return false;
        if (result.badControlCount > Math.max(2, length / 120)) return false;
        return utf16TextSignal(result) >= Math.max(4, length / 5);
    }

    private static int utf16TextSignal(TextEncodingProfile result) {
        if (result == null) return 0;
        return result.asciiCount
                + result.cjkCount
                + result.kanaCount
                + result.hangulCount
                + result.alphabeticScriptCount
                + result.usefulPunctuationCount;
    }

    private static boolean isLikelySingleByteText(int b) {
        return b == '\t' || b == '\n' || b == '\r' || (b >= 0x20 && b <= 0x7E);
    }



    static double charsetScriptMatchBonus(String charsetName,
                                                    int latinExtended,
                                                    int greek,
                                                    int cyrillic,
                                                    int hebrew,
                                                    int arabic,
                                                    int thai) {
        if (charsetName == null) return 0.0;
        String n = charsetName.toUpperCase(Locale.ROOT);

        // Non-Latin legacy encodings are highly ambiguous at the byte level, so
        // only grant a strong script bonus when enough characters decoded into
        // the script. This avoids stealing short Western European accent text.
        if ((n.equals("WINDOWS-1251") || n.equals("ISO-8859-5") || n.startsWith("KOI8")) && cyrillic >= 6) {
            return cyrillic * 4.0;
        }
        if ((n.equals("WINDOWS-1253") || n.equals("ISO-8859-7")) && greek >= 6) {
            return greek * 4.0;
        }
        if ((n.equals("WINDOWS-1255") || n.equals("ISO-8859-8")) && hebrew >= 5) {
            return hebrew * 4.0;
        }
        if ((n.equals("WINDOWS-1256") || n.equals("ISO-8859-6")) && arabic >= 5) {
            return arabic * 4.0;
        }
        if ((n.equals("WINDOWS-874") || n.equals("ISO-8859-11")) && thai >= 5) {
            return thai * 4.0;
        }
        if ((n.equals("WINDOWS-1258") || n.equals("VISCII")) && latinExtended > 0) {
            return latinExtended * 2.0;
        }
        if ((n.equals("WINDOWS-1252") || n.equals("ISO-8859-1") || n.equals("ISO-8859-15")
                || n.equals("WINDOWS-1250") || n.equals("ISO-8859-2")
                || n.equals("WINDOWS-1254") || n.equals("ISO-8859-9")
                || n.equals("WINDOWS-1257") || n.equals("ISO-8859-13")
                || n.contains("MAC")) && latinExtended > 0) {
            return latinExtended * 1.4;
        }
        return 0.0;
    }

    static double charsetTieBreakerPenalty(String charsetName) {
        if (charsetName == null) return 50.0;
        String n = charsetName.toUpperCase(Locale.ROOT);

        // Prefer common encodings if scores are otherwise close. Windows code pages
        // are slightly preferred over ISO siblings because they preserve smart quotes
        // and other book punctuation in many old TXT files.
        if (n.equals("UTF-8")) return 0.0;
        if (n.equals("WINDOWS-1252")) return 3.0;
        if (n.equals("ISO-8859-1") || n.equals("ISO-8859-15")) return 7.0;
        if (n.equals("WINDOWS-1250") || n.equals("WINDOWS-1251") || n.equals("WINDOWS-1253")
                || n.equals("WINDOWS-1254") || n.equals("WINDOWS-1255") || n.equals("WINDOWS-1256")
                || n.equals("WINDOWS-1257") || n.equals("WINDOWS-1258") || n.equals("WINDOWS-874")
                || n.equals("IBM866")) return 8.0;
        if (n.startsWith("ISO-8859-") || n.startsWith("KOI8")) return 11.0;
        if (n.contains("949") || n.equals("MS949") || n.equals("EUC-KR")) return 12.0;
        if (n.contains("SHIFT") || n.contains("31J")) return 14.0;
        if (n.contains("EUC-JP")) return 16.0;
        if (n.contains("GB") || n.contains("HZ")) return 18.0;
        if (n.contains("BIG5") || n.contains("EUC-TW")) return 20.0;
        if (n.contains("MAC")) return 22.0;

        return 25.0;
    }


}
