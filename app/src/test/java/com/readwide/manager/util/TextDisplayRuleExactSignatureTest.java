package com.readwide.manager.util;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Arrays;

public class TextDisplayRuleExactSignatureTest {
    private TextDisplayRule rule(String find, String replacement) {
        TextDisplayRule rule = new TextDisplayRule();
        rule.findText = find;
        rule.replacementText = replacement;
        return rule;
    }

    @Test public void distinguishesLegacyStringHashCollisionAndTransformOrder() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        TextDisplayRule a = rule("Aa", "x"), b = rule("BB", "y");
        assertNotEquals(TextDisplayRuleManager.getExactSignature(Arrays.asList(a)),
                TextDisplayRuleManager.getExactSignature(Arrays.asList(rule("BB", "x"))));
        assertNotEquals(TextDisplayRuleManager.getExactSignature(Arrays.asList(a, b)),
                TextDisplayRuleManager.getExactSignature(Arrays.asList(b, a)));
    }

    @Test public void copiedRuleHasSameIdentityButRegexAndCaseChangesDoNot() {
        TextDisplayRule original = rule("find", "replace");
        TextDisplayRule copy = original.copy();
        String signature = TextDisplayRuleManager.getExactSignature(Arrays.asList(original));
        assertEquals(signature, TextDisplayRuleManager.getExactSignature(Arrays.asList(copy)));
        copy.useRegex = true;
        assertNotEquals(signature, TextDisplayRuleManager.getExactSignature(Arrays.asList(copy)));
        copy.useRegex = false;
        copy.caseSensitive = true;
        assertNotEquals(signature, TextDisplayRuleManager.getExactSignature(Arrays.asList(copy)));
    }

    @Test public void lengthPrefixesAndUtf16PreserveDistinctText() {
        assertNotEquals(TextDisplayRuleManager.getExactSignature(Arrays.asList(rule("ab", "c"))),
                TextDisplayRuleManager.getExactSignature(Arrays.asList(rule("a", "bc"))));
        assertNotEquals(TextDisplayRuleManager.getExactSignature(Arrays.asList(rule("x", "\uD800"))),
                TextDisplayRuleManager.getExactSignature(Arrays.asList(rule("x", "\uD801"))));
        assertEquals(TextDisplayRuleManager.getExactSignature(Arrays.asList(rule("x", null))),
                TextDisplayRuleManager.getExactSignature(Arrays.asList(rule("x", ""))));
    }
}
