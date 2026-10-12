package com.readwide.manager.util;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import static org.junit.Assert.*;

/** Isolated preferences: never writes the user's rule store. */
@RunWith(AndroidJUnit4.class)
public class TextDisplayRuleSignatureCacheInstrumentedTest {
    private Context context;
    private String preferenceName;
    private SharedPreferences preferences;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferenceName = "rule-signature-test-" + UUID.randomUUID();
        preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE);
        TextDisplayRuleManager.invalidateCache();
    }

    @After public void tearDown() {
        TextDisplayRuleManager.invalidateCache();
        assertTrue(context.deleteSharedPreferences(preferenceName));
    }

    private TextDisplayRule rule(String find, String replacement) {
        TextDisplayRule rule = new TextDisplayRule();
        rule.findText = find;
        rule.replacementText = replacement;
        return rule;
    }

    private void save(TextDisplayRule... rules) throws Exception {
        JSONArray array = new JSONArray();
        for (TextDisplayRule rule : rules) array.put(rule.toJson());
        // Bypass the manager, as a preference import/external edit would.
        assertTrue(preferences.edit().putString("txt_display_replacement_rules_json", array.toString()).commit());
    }

    @Test public void cacheHitReusesHashButExternalChangeAndInvalidationRecompute() throws Exception {
        TextDisplayRule rule = rule("cat", "dog");
        save(rule);
        String first = TextDisplayRuleManager.getExactSignature(preferences, "book.txt");
        assertEquals(TextDisplayRuleManager.getExactSignature(Arrays.asList(rule)), first);
        assertSame(first, TextDisplayRuleManager.getExactSignature(preferences, "book.txt"));
        rule.replacementText = "bird";
        save(rule);
        String changed = TextDisplayRuleManager.getExactSignature(preferences, "book.txt");
        assertNotEquals(first, changed);
        assertEquals(TextDisplayRuleManager.getExactSignature(Arrays.asList(rule)), changed);
        TextDisplayRuleManager.invalidateCache();
        String rebuilt = TextDisplayRuleManager.getExactSignature(preferences, "book.txt");
        assertEquals(changed, rebuilt);
        assertNotSame(changed, rebuilt);
    }

    @Test public void pathScopeEnablementAndOrderMatchUncachedActiveRules() throws Exception {
        TextDisplayRule all = rule("cat", "dog"), scoped = rule("dog", "bird"), disabled = rule("bird", "fish");
        scoped.scope = TextDisplayRule.SCOPE_FILE;
        scoped.filePath = "one.txt";
        disabled.enabled = false;
        save(all, scoped, disabled);
        assertEquals(TextDisplayRuleManager.getExactSignature(Arrays.asList(all, scoped)),
                TextDisplayRuleManager.getExactSignature(preferences, "one.txt"));
        assertEquals(TextDisplayRuleManager.getExactSignature(Arrays.asList(all)),
                TextDisplayRuleManager.getExactSignature(preferences, "two.txt"));
        save(scoped, all, disabled);
        assertEquals(TextDisplayRuleManager.getExactSignature(Arrays.asList(scoped, all)),
                TextDisplayRuleManager.getExactSignature(preferences, "one.txt"));
        save();
        assertEquals(TextDisplayRuleManager.getExactSignature(Collections.emptyList()),
                TextDisplayRuleManager.getExactSignature(preferences, "one.txt"));
    }

    @Test public void recentPathCacheIsBoundedWithoutChangingIdentity() throws Exception {
        save(rule("cat", "dog"));
        String first = TextDisplayRuleManager.getExactSignature(preferences, "first.txt");
        for (int i = 0; i < 16; i++) TextDisplayRuleManager.getExactSignature(preferences, "book-" + i);
        String afterEviction = TextDisplayRuleManager.getExactSignature(preferences, "first.txt");
        assertEquals(first, afterEviction);
        assertNotSame(first, afterEviction);
        assertSame(afterEviction, TextDisplayRuleManager.getExactSignature(preferences, "first.txt"));
    }
}
