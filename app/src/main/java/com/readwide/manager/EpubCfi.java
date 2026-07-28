package com.readwide.manager;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Small, defensive EPUB CFI point parser.
 *
 * <p>This deliberately implements the navigation subset exercised by the IDPF
 * {@code georgia-cfi} sample: a package component, one indirection marker, an
 * element/text content path, an optional character offset, and optional ID/text
 * assertions. Range CFIs, temporal/spatial offsets, side-bias parameters, and
 * nested indirections are rejected instead of being interpreted approximately.</p>
 */
final class EpubCfi {
    private static final int MAX_CFI_LENGTH = 8192;
    private static final int MAX_STEPS = 128;
    private static final int MAX_STEP_NUMBER = 1_000_000;
    private static final int MAX_CHARACTER_OFFSET = 50_000_000;

    static final class Step {
        private final int number;
        private final String idAssertion;

        Step(int number, String idAssertion) {
            this.number = number;
            this.idAssertion = idAssertion != null ? idAssertion : "";
        }

        int number() {
            return number;
        }

        String idAssertion() {
            return idAssertion;
        }

        boolean isElementStep() {
            return (number & 1) == 0;
        }

        boolean isTextStep() {
            return !isElementStep();
        }

        /** Zero-based index among element children for an even CFI step. */
        int elementIndex() {
            return isElementStep() ? (number / 2) - 1 : -1;
        }

        /** Number of element siblings preceding the logical text-node group. */
        int textGapIndex() {
            return isTextStep() ? (number - 1) / 2 : -1;
        }
    }

    private static final class ParsedComponent {
        final List<Step> steps;
        final int characterOffset;
        final String textBefore;
        final String textAfter;
        final boolean hasTextAssertion;

        ParsedComponent(List<Step> steps,
                        int characterOffset,
                        String textBefore,
                        String textAfter,
                        boolean hasTextAssertion) {
            this.steps = steps;
            this.characterOffset = characterOffset;
            this.textBefore = textBefore;
            this.textAfter = textAfter;
            this.hasTextAssertion = hasTextAssertion;
        }
    }

    private static final class BracketValue {
        final String value;
        final int nextIndex;
        final int firstUnescapedComma;

        BracketValue(String value, int nextIndex, int firstUnescapedComma) {
            this.value = value;
            this.nextIndex = nextIndex;
            this.firstUnescapedComma = firstUnescapedComma;
        }
    }

    private final String source;
    private final List<Step> packageSteps;
    private final List<Step> contentSteps;
    private final int characterOffset;
    private final String textBefore;
    private final String textAfter;
    private final boolean hasTextAssertion;

    private EpubCfi(String source,
                    ParsedComponent packageComponent,
                    ParsedComponent contentComponent) {
        this.source = source;
        this.packageSteps = Collections.unmodifiableList(
                new ArrayList<>(packageComponent.steps));
        this.contentSteps = Collections.unmodifiableList(
                new ArrayList<>(contentComponent.steps));
        this.characterOffset = contentComponent.characterOffset;
        this.textBefore = contentComponent.textBefore;
        this.textAfter = contentComponent.textAfter;
        this.hasTextAssertion = contentComponent.hasTextAssertion;
    }

    /** Returns {@code null} for malformed or unsupported CFI forms. */
    static EpubCfi parse(String value) {
        if (value == null) return null;
        String raw = value.trim();
        int hash = raw.lastIndexOf('#');
        if (hash >= 0) raw = raw.substring(hash + 1).trim();
        if (raw.startsWith("#")) raw = raw.substring(1).trim();
        if (raw.isEmpty() || raw.length() > MAX_CFI_LENGTH) return null;

        raw = decodePercentEscapes(raw);
        if (raw == null || raw.length() > MAX_CFI_LENGTH
                || !raw.startsWith("epubcfi(") || !raw.endsWith(")")) {
            return null;
        }

        String body = raw.substring("epubcfi(".length(), raw.length() - 1);
        int bang = findSingleTopLevelIndirection(body);
        if (bang <= 0 || bang >= body.length() - 1) return null;

        ParsedComponent packageComponent = parseComponent(
                body.substring(0, bang), false);
        ParsedComponent contentComponent = parseComponent(
                body.substring(bang + 1), true);
        if (packageComponent == null || contentComponent == null) return null;

        // The package component must end at an itemref element step. Its numeric
        // value remains a useful fallback when the optional ID assertion is absent.
        Step packageTarget = packageComponent.steps.get(packageComponent.steps.size() - 1);
        if (!packageTarget.isElementStep()) return null;
        return new EpubCfi(raw, packageComponent, contentComponent);
    }

    String source() {
        return source;
    }

    List<Step> packageSteps() {
        return packageSteps;
    }

    List<Step> contentSteps() {
        return contentSteps;
    }

    /** Itemref XML-ID assertion, or an empty string when the CFI omitted it. */
    String itemRefIdAssertion() {
        return packageSteps.get(packageSteps.size() - 1).idAssertion();
    }

    /** Zero-based itemref position derived from the final package even step. */
    int spineItemIndex() {
        return packageSteps.get(packageSteps.size() - 1).elementIndex();
    }

    int characterOffset() {
        return characterOffset;
    }

    boolean hasCharacterOffset() {
        return characterOffset >= 0;
    }

    boolean hasTextAssertion() {
        return hasTextAssertion;
    }

    String textBefore() {
        return textBefore;
    }

    String textAfter() {
        return textAfter;
    }

    private static ParsedComponent parseComponent(String component,
                                                   boolean contentComponent) {
        if (component == null || component.isEmpty() || component.charAt(0) != '/') {
            return null;
        }
        ArrayList<Step> steps = new ArrayList<>();
        int offset = -1;
        String textBefore = "";
        String textAfter = "";
        boolean hasTextAssertion = false;
        int i = 0;
        while (i < component.length()) {
            if (component.charAt(i) != '/' || steps.size() >= MAX_STEPS) return null;
            i++;
            int numberStart = i;
            while (i < component.length() && isAsciiDigit(component.charAt(i))) i++;
            if (numberStart == i) return null;
            int number = parseBoundedNumber(component, numberStart, i, MAX_STEP_NUMBER);
            if (number <= 0) return null;

            String idAssertion = "";
            if (i < component.length() && component.charAt(i) == '[') {
                BracketValue assertion = readBracket(component, i);
                if (assertion == null) return null;
                idAssertion = assertion.value;
                i = assertion.nextIndex;
            }

            Step step = new Step(number, idAssertion);
            steps.add(step);

            if (i < component.length() && component.charAt(i) == ':') {
                if (!contentComponent || !step.isTextStep() || offset >= 0) return null;
                i++;
                int offsetStart = i;
                while (i < component.length() && isAsciiDigit(component.charAt(i))) i++;
                if (offsetStart == i) return null;
                offset = parseBoundedNumber(
                        component, offsetStart, i, MAX_CHARACTER_OFFSET);
                if (offset < 0) return null;
                if (i < component.length() && component.charAt(i) == '[') {
                    BracketValue assertion = readBracket(component, i);
                    if (assertion == null) return null;
                    String[] pair = splitTextAssertion(
                            assertion.value, assertion.firstUnescapedComma);
                    textBefore = pair[0];
                    textAfter = pair[1];
                    hasTextAssertion = true;
                    i = assertion.nextIndex;
                }
            }

            if (i < component.length() && component.charAt(i) != '/') return null;
            if (offset >= 0 && i < component.length()) return null;
        }

        if (steps.isEmpty()) return null;
        if (!contentComponent && offset >= 0) return null;
        // This scoped resolver supports a logical text group only as the target,
        // never as an intermediate path node.
        for (int s = 0; s < steps.size() - 1; s++) {
            if (steps.get(s).isTextStep()) return null;
        }
        return new ParsedComponent(
                steps, offset, textBefore, textAfter, hasTextAssertion);
    }

    private static int findSingleTopLevelIndirection(String body) {
        int bracketDepth = 0;
        int bang = -1;
        boolean escaped = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '^') {
                escaped = true;
                continue;
            }
            if (c == '[') {
                bracketDepth++;
            } else if (c == ']') {
                if (--bracketDepth < 0) return -1;
            } else if (bracketDepth == 0) {
                if (c == ',') return -1; // range CFI
                if (c == '!') {
                    if (bang >= 0) return -1; // nested indirection
                    bang = i;
                }
            }
        }
        return bracketDepth == 0 && !escaped ? bang : -1;
    }

    private static BracketValue readBracket(String text, int open) {
        if (open < 0 || open >= text.length() || text.charAt(open) != '[') return null;
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        int firstUnescapedComma = -1;
        for (int i = open + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                out.append(c);
                escaped = false;
            } else if (c == '^') {
                escaped = true;
            } else if (c == ']') {
                return new BracketValue(
                        out.toString(), i + 1, firstUnescapedComma);
            } else if (c == '[') {
                return null;
            } else {
                if (c == ',' && firstUnescapedComma < 0) {
                    firstUnescapedComma = out.length();
                }
                out.append(c);
            }
        }
        return null;
    }

    private static String[] splitTextAssertion(String value, int comma) {
        String raw = value != null ? value : "";
        if (comma < 0) return new String[]{raw, ""};
        return new String[]{raw.substring(0, comma), raw.substring(comma + 1)};
    }

    private static int parseBoundedNumber(String value,
                                          int start,
                                          int end,
                                          int maximum) {
        long parsed = 0L;
        for (int i = start; i < end; i++) {
            parsed = parsed * 10L + (value.charAt(i) - '0');
            if (parsed > maximum) return -1;
        }
        return (int) parsed;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** URI percent decoding that intentionally keeps a literal '+' unchanged. */
    private static String decodePercentEscapes(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length();) {
            char c = value.charAt(i);
            if (c != '%') {
                out.append(c);
                i++;
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (i < value.length() && value.charAt(i) == '%') {
                if (i + 2 >= value.length()) return null;
                int high = hex(value.charAt(i + 1));
                int low = hex(value.charAt(i + 2));
                if (high < 0 || low < 0) return null;
                bytes.write((high << 4) | low);
                i += 3;
            }
            out.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
}
