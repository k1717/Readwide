package com.readwide.manager.util;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class TextDisplayRuleCompiledTest {
    private static TextDisplayRule rule(String find, String replacement, boolean regex) {
        TextDisplayRule rule = new TextDisplayRule();
        rule.findText = find; rule.replacementText = replacement;
        rule.useRegex = regex; rule.caseSensitive = true;
        return rule;
    }
    private static String apply(String input, TextDisplayRule... rules) {
        return TextDisplayRuleManager.apply(input, TextDisplayRuleManager.compile(Arrays.asList(rules)));
    }

    @Test public void malformedReplacementsLeaveTextReadable() {
        for (String replacement : new String[]{"$2", "${missing}", "$", "\\", "${1x}", "${a_b}", "$x", "${name"}) {
            TextDisplayRuleManager.CompiledRules compiled = TextDisplayRuleManager.compile(
                    Collections.singletonList(rule("(cat)", replacement, true)));
            assertEquals("cat", TextDisplayRuleManager.apply("cat", compiled));
            assertEquals("cat cat", TextDisplayRuleManager.apply("cat cat", compiled));
        }
    }

    @Test public void invalidRuleDoesNotStopOtherRules() {
        assertEquals("cat fox", apply("cat dog", rule("(cat)", "${missing}", true), rule("dog", "fox", false)));
    }

    @Test public void numericAndNamedCapturesStillWork() {
        assertEquals("atc", apply("cat", rule("(c)(at)", "$2$1", true)));
        assertEquals("cats", apply("cat", rule("(?<animal>cat)", "${animal}s", true)));
        assertEquals("cat", apply("cat", rule("cat", "$0", true)));
        assertEquals("cat2", apply("cat", rule("(cat)", "$12", true)));
        assertEquals("cat1", apply("cat", rule("cat", "$01", true)));
    }

    @Test public void escapedReplacementTextIsNotTreatedAsGroupReference() {
        assertEquals("$1", apply("cat", rule("cat", "\\$1", true)));
        assertEquals("\\", apply("cat", rule("cat", "\\\\", true)));
        TextDisplayRule literal = rule("[a]", "$1\\", false);
        literal.caseSensitive = false;
        assertEquals("$1\\", apply("[A]", literal));
    }

    @Test public void compiledSnapshotIsIndependentOfLaterEditorChanges() {
        TextDisplayRule source = rule("cat", "dog", true);
        TextDisplayRuleManager.CompiledRules compiled = TextDisplayRuleManager.compile(Collections.singletonList(source));
        source.findText = "dog"; source.replacementText = "$"; source.enabled = false;
        assertEquals("dog dog", TextDisplayRuleManager.apply("cat cat", compiled));
    }

    @Test public void missingNamedGroupIsSafeBeforeAndAfterFirstMatch() {
        TextDisplayRuleManager.CompiledRules compiled = TextDisplayRuleManager.compile(
                Collections.singletonList(rule("cat", "${missing}", true)));
        assertEquals("dog", TextDisplayRuleManager.apply("dog", compiled));
        assertEquals("cat", TextDisplayRuleManager.apply("cat", compiled));
        assertEquals("cat dog", TextDisplayRuleManager.apply("cat dog", compiled));
    }

    @Test public void malformedPatternAndMultilineRuleAreSkipped() {
        assertEquals("cat", apply("cat", rule("[", "x", true), rule("cat", "x\ny", true)));
    }

    @Test public void caseInsensitiveLiteralKeepsOriginalUnicodeOffsets() {
        TextDisplayRule source = rule("a", "X", false);
        source.caseSensitive = false;
        assertEquals("İX", apply("İa", source));
        assertEquals("İXXİ", apply("İaAİ", source));
    }

    @Test public void ruleCopyIncludesScopeAndDoesNotShareEditableFields() {
        TextDisplayRule original = rule("cat", "dog", true);
        original.id = "stable"; original.scope = TextDisplayRule.SCOPE_FILE;
        original.filePath = "/book.txt"; original.sourceFilePath = "/source.txt";
        TextDisplayRule copy = original.copy();
        assertNotSame(original, copy);
        assertEquals(original.id, copy.id);
        assertEquals(original.sourceFilePath, copy.sourceFilePath);
        assertTrue(copy.appliesTo("/book.txt"));
        assertFalse(copy.appliesTo("/other.txt"));
        copy.filePath = "/other.txt"; copy.replacementText = "changed"; copy.enabled = false;
        assertEquals("dog", original.replacementText);
        assertTrue(original.enabled);
        assertTrue(original.appliesTo("/book.txt"));
    }
}
