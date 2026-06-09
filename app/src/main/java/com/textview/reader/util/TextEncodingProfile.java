package com.textview.reader.util;

import java.util.Locale;

/**
 * Character-level profile of a candidate charset decode.
 */
    final class TextEncodingProfile {
        final String charsetName;
        final String text;
        final int replacementCount;
        final int badControlCount;
        final int nulCount;
        final int cjkCount;
        final int kanaCount;
        final int hangulCount;
        final int alphabeticScriptCount;
        final int usefulPunctuationCount;
        final int asciiCount;
        final int latinExtendedCount;
        final int greekCount;
        final int cyrillicCount;
        final int hebrewCount;
        final int arabicCount;
        final int thaiCount;
        final int cyrillicNaturalnessScore;
        final int cyrillicOddCount;
        final double score;

        TextEncodingProfile(String charsetName, String text) {
            this.charsetName = charsetName;
            this.text = text;

            int replacements = 0;
            int badControls = 0;
            int nuls = 0;
            int cjk = 0;
            int kana = 0;
            int hangul = 0;
            int alphabeticScripts = 0;
            int latinExtended = 0;
            int greek = 0;
            int cyrillic = 0;
            int hebrew = 0;
            int arabic = 0;
            int thai = 0;
            int usefulPunctuation = 0;
            int ascii = 0;

            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);

                if (ch == '\uFFFD') replacements++;
                if (ch == '\u0000') nuls++;
                if (ch <= 0x7F) ascii++;

                if (isBadControl(ch)) badControls++;
                if (isCjk(ch)) cjk++;
                if (isKana(ch)) kana++;
                if (isHangul(ch)) hangul++;
                if (isBroadAlphabeticScript(ch)) alphabeticScripts++;
                if (isLatinExtended(ch)) latinExtended++;
                if (isGreek(ch)) greek++;
                if (isCyrillic(ch)) cyrillic++;
                if (isHebrew(ch)) hebrew++;
                if (isArabic(ch)) arabic++;
                if (isThai(ch)) thai++;
                if (isUsefulTextPunctuation(ch)) usefulPunctuation++;
            }

            this.replacementCount = replacements;
            this.badControlCount = badControls;
            this.nulCount = nuls;
            this.cjkCount = cjk;
            this.kanaCount = kana;
            this.hangulCount = hangul;
            this.alphabeticScriptCount = alphabeticScripts;
            this.usefulPunctuationCount = usefulPunctuation;
            this.asciiCount = ascii;
            this.latinExtendedCount = latinExtended;
            this.greekCount = greek;
            this.cyrillicCount = cyrillic;
            this.hebrewCount = hebrew;
            this.arabicCount = arabic;
            this.thaiCount = thai;
            this.cyrillicNaturalnessScore = cyrillicNaturalnessScore(text);
            this.cyrillicOddCount = countOddCyrillicForRussianProse(text);

            // Lower is better. Replacement/control/NUL are strong evidence of wrong encoding.
            // Script-specific letters and common book punctuation are positive evidence.
            // This makes detection less CJK-only and helps Latin/Greek/Cyrillic/etc. legacy TXT.
            this.score =
                    replacements * 1000.0
                            + badControls * 350.0
                            + nuls * 1200.0
                            - cjk * 0.8
                            - kana * 1.5
                            - hangul * 1.2
                            - alphabeticScripts * 0.45
                            - usefulPunctuation * 0.35
                            - Math.min(ascii, 2000) * 0.02
                            - TextEncodingDetector.charsetScriptMatchBonus(charsetName, latinExtended, greek, cyrillic, hebrew, arabic, thai)
                            - cyrillicNaturalnessScore * 2.8
                            + cyrillicOddCount * 5.5
                            + TextEncodingDetector.charsetTieBreakerPenalty(charsetName);
        }

    private static boolean isBadControl(char ch) {
        if (ch == '\n' || ch == '\r' || ch == '\t') return false;
        return ch < 0x20 || (ch >= 0x7F && ch <= 0x9F);
    }

    private static boolean isCjk(char ch) {
        return (ch >= 0x3400 && ch <= 0x4DBF)    // CJK Ext A
                || (ch >= 0x4E00 && ch <= 0x9FFF) // CJK Unified
                || (ch >= 0xF900 && ch <= 0xFAFF); // Compatibility Ideographs
    }

    private static boolean isKana(char ch) {
        return (ch >= 0x3040 && ch <= 0x309F) // Hiragana
                || (ch >= 0x30A0 && ch <= 0x30FF) // Katakana
                || (ch >= 0x31F0 && ch <= 0x31FF); // Katakana Phonetic Extensions
    }

    private static boolean isHangul(char ch) {
        return (ch >= 0xAC00 && ch <= 0xD7AF)
                || (ch >= 0x1100 && ch <= 0x11FF)
                || (ch >= 0x3130 && ch <= 0x318F);
    }

    private static boolean isBroadAlphabeticScript(char ch) {
        if (ch <= 0x7F) return false;
        if (isCjk(ch) || isKana(ch) || isHangul(ch)) return false;
        return Character.isLetterOrDigit(ch);
    }

    private static boolean isLatinExtended(char ch) {
        return (ch >= 0x00C0 && ch <= 0x024F)
                || (ch >= 0x1E00 && ch <= 0x1EFF);
    }

    private static boolean isGreek(char ch) {
        return (ch >= 0x0370 && ch <= 0x03FF)
                || (ch >= 0x1F00 && ch <= 0x1FFF);
    }

    private static boolean isCyrillic(char ch) {
        return (ch >= 0x0400 && ch <= 0x052F)
                || (ch >= 0x2DE0 && ch <= 0x2DFF)
                || (ch >= 0xA640 && ch <= 0xA69F);
    }

    private static boolean isHebrew(char ch) {
        return ch >= 0x0590 && ch <= 0x05FF;
    }

    private static boolean isArabic(char ch) {
        return (ch >= 0x0600 && ch <= 0x06FF)
                || (ch >= 0x0750 && ch <= 0x077F)
                || (ch >= 0x08A0 && ch <= 0x08FF)
                || (ch >= 0xFB50 && ch <= 0xFDFF)
                || (ch >= 0xFE70 && ch <= 0xFEFF);
    }

    private static boolean isThai(char ch) {
        return ch >= 0x0E00 && ch <= 0x0E7F;
    }

    private static boolean isUsefulTextPunctuation(char ch) {
        switch (ch) {
            case '‘': // ‘
            case '’': // ’
            case '‚': // ‚
            case '“': // “
            case '”': // ”
            case '„': // „
            case '‹': // ‹
            case '›': // ›
            case '–': // –
            case '—': // —
            case '…': // …
            case '•': // •
            case ' ': // non-breaking space
            case '«': // «
            case '»': // »
            case '¡': // ¡
            case '¿': // ¿
            case '£': // £
            case '€': // €
                return true;
            default:
                return false;
        }
    }

    private static int cyrillicNaturalnessScore(String text) {
        if (text == null || text.isEmpty()) return 0;
        String lower = text.toLowerCase(Locale.ROOT);
        String[] common = new String[]{
                "ст", "но", "то", "на", "ен", "ов", "ни", "ра", "ко", "ро",
                "не", "по", "го", "ре", "ер", "пр", "ли", "ла", "ел", "ос",
                "ть", "те", "ал", "ка", "ес", "ом", "ет", "ит", "ия", "ых",
                "ого", "его", "что", "как", "для", "это", "его", "она", "они",
                "или", "при", "ост", "ени", "ого", "его", "ова", "ени"
        };

        int score = 0;
        for (String item : common) {
            score += countOccurrences(lower, item);
        }
        return score;
    }

    private static int countOccurrences(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int from = 0;
        while (from < text.length()) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) break;
            count++;
            from = idx + needle.length();
        }
        return count;
    }

    private static int countOddCyrillicForRussianProse(String text) {
        if (text == null || text.isEmpty()) return 0;
        int odd = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                // These letters are valid Unicode Cyrillic and valid for some
                // languages, but in Russian-prose TXT they often appear when
                // ISO-8859-5 bytes were incorrectly decoded as Windows-1251.
                case 'ѓ': case 'Ѓ':
                case 'є': case 'Є':
                case 'ѕ': case 'Ѕ':
                case 'і': case 'І':
                case 'ї': case 'Ї':
                case 'ј': case 'Ј':
                case 'љ': case 'Љ':
                case 'њ': case 'Њ':
                case 'ћ': case 'Ћ':
                case 'ќ': case 'Ќ':
                case 'ў': case 'Ў':
                case 'џ': case 'Џ':
                    odd++;
                    break;
                default:
                    break;
            }
        }
        return odd;
    }

    static int countVietnameseToneMarks(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case 'Ă': case 'ă': case 'Â': case 'â': case 'Ê': case 'ê':
                case 'Ô': case 'ô': case 'Ơ': case 'ơ': case 'Ư': case 'ư':
                case 'Đ': case 'đ':
                case 'À': case 'Á': case 'Ả': case 'Ã': case 'Ạ':
                case 'à': case 'á': case 'ả': case 'ã': case 'ạ':
                case 'Ằ': case 'Ắ': case 'Ẳ': case 'Ẵ': case 'Ặ':
                case 'ằ': case 'ắ': case 'ẳ': case 'ẵ': case 'ặ':
                case 'Ầ': case 'Ấ': case 'Ẩ': case 'Ẫ': case 'Ậ':
                case 'ầ': case 'ấ': case 'ẩ': case 'ẫ': case 'ậ':
                case 'È': case 'É': case 'Ẻ': case 'Ẽ': case 'Ẹ':
                case 'è': case 'é': case 'ẻ': case 'ẽ': case 'ẹ':
                case 'Ề': case 'Ế': case 'Ể': case 'Ễ': case 'Ệ':
                case 'ề': case 'ế': case 'ể': case 'ễ': case 'ệ':
                case 'Ì': case 'Í': case 'Ỉ': case 'Ĩ': case 'Ị':
                case 'ì': case 'í': case 'ỉ': case 'ĩ': case 'ị':
                case 'Ò': case 'Ó': case 'Ỏ': case 'Õ': case 'Ọ':
                case 'ò': case 'ó': case 'ỏ': case 'õ': case 'ọ':
                case 'Ồ': case 'Ố': case 'Ổ': case 'Ỗ': case 'Ộ':
                case 'ồ': case 'ố': case 'ổ': case 'ỗ': case 'ộ':
                case 'Ờ': case 'Ớ': case 'Ở': case 'Ỡ': case 'Ợ':
                case 'ờ': case 'ớ': case 'ở': case 'ỡ': case 'ợ':
                case 'Ù': case 'Ú': case 'Ủ': case 'Ũ': case 'Ụ':
                case 'ù': case 'ú': case 'ủ': case 'ũ': case 'ụ':
                case 'Ừ': case 'Ứ': case 'Ử': case 'Ữ': case 'Ự':
                case 'ừ': case 'ứ': case 'ử': case 'ữ': case 'ự':
                case 'Ỳ': case 'Ý': case 'Ỷ': case 'Ỹ': case 'Ỵ':
                case 'ỳ': case 'ý': case 'ỷ': case 'ỹ': case 'ỵ':
                    count++;
                    break;
                default:
                    break;
            }
        }
        return count;
    }
}
