package com.readwide.manager.util;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Uses a private test preference file, never the app's live singleton/store. */
@RunWith(AndroidJUnit4.class)
public class BackupPreferencesInstrumentedTest {
    private static final String RULES = "txt_display_replacement_rules_json";
    private Context context;
    private String name;
    private SharedPreferences store;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        name = "backup-preferences-test-" + UUID.randomUUID();
        store = context.getSharedPreferences(name, Context.MODE_PRIVATE);
        TextDisplayRuleManager.invalidateCache();
    }

    @After public void tearDown() {
        TextDisplayRuleManager.invalidateCache();
        assertTrue(context.deleteSharedPreferences(name));
    }

    private PrefsManager manager(SharedPreferences preferences) throws Exception {
        Context isolated = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String ignored, int mode) { return preferences; }
        };
        Constructor<PrefsManager> constructor = PrefsManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(isolated);
    }

    private static JSONObject setting(String key, String type, Object value) throws Exception {
        return new JSONObject().put("values", new JSONObject().put(key,
                new JSONObject().put("type", type).put("value", value)));
    }
    private static String rules(String replacement) throws Exception {
        TextDisplayRule rule = new TextDisplayRule();
        rule.findText = "cat"; rule.replacementText = replacement;
        return new org.json.JSONArray().put(rule.toJson()).toString();
    }

    @Test public void replacementPreservesSecurityAndEncodingCacheButRemovesOrdinaryOldValues() throws Exception {
        store.edit().putString("lock_pin", "test-only-pin").putBoolean("lock_enabled", true)
                .putString("auto_text_encoding::book", "UTF-8").putString("old", "remove me").commit();
        JSONObject incoming = setting("count", "int", 9);
        incoming.getJSONObject("values").put("lock_pin", false); // Excluded even if malformed.
        manager(store).importSettingsFromJson(incoming, false);
        assertEquals(9, store.getInt("count", 0));
        assertEquals("test-only-pin", store.getString("lock_pin", null));
        assertTrue(store.getBoolean("lock_enabled", false));
        assertEquals("UTF-8", store.getString("auto_text_encoding::book", null));
        assertFalse(store.contains("old"));
    }

    @Test public void mergeRetainsUnrelatedValuesAndPreservesExplicitLongType() throws Exception {
        store.edit().putString("old", "keep").commit();
        manager(store).importSettingsFromJson(setting("count", "long", 9), true);
        assertEquals("keep", store.getString("old", null));
        assertEquals(9L, store.getLong("count", 0));
    }

    @Test public void changedPreferenceTypeIsRejectedBeforeReplacingOtherValues() throws Exception {
        store.edit().putInt("count", 3).putString("old", "keep").commit();
        Map<String, ?> before = store.getAll();
        try { manager(store).importSettingsFromJson(setting("count", "string", "bad"), false); fail("Expected validation error"); }
        catch (org.json.JSONException expected) { assertEquals(before, store.getAll()); }
    }

    @Test public void failedPreferenceCommitRestoresPreviousMemoryAndRules() throws Exception {
        store.edit().putString(RULES, rules("old")).putString("marker", "keep").commit();
        TextDisplayRuleManager.getRules(store);
        Map<String, ?> before = store.getAll();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(store, args);
                    SharedPreferences.Editor editor = store.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, editMethod, editArgs) -> {
                                Object result = editMethod.invoke(editor, editArgs);
                                if (editMethod.getName().equals("commit") && failOnce.getAndSet(false)) return false;
                                return result instanceof SharedPreferences.Editor ? editorProxy : result;
                            });
                });
        try { manager(failing).importSettingsFromJson(setting(RULES, "string", rules("new")), false); fail("Expected commit error"); }
        catch (IOException expected) { assertEquals(before, store.getAll()); }
        assertEquals("old", TextDisplayRuleManager.apply("cat", TextDisplayRuleManager.captureActive(store, "/book.txt").compiled));
    }

    @Test public void successfulImportInvalidatesAlreadyLoadedRuleCache() throws Exception {
        store.edit().putString(RULES, rules("old")).commit();
        TextDisplayRuleManager.ActiveSnapshot old = TextDisplayRuleManager.captureActive(store, "/book.txt");
        manager(store).importSettingsFromJson(setting(RULES, "string", rules("new")), true);
        TextDisplayRuleManager.ActiveSnapshot updated = TextDisplayRuleManager.captureActive(store, "/book.txt");
        assertNotEquals(old.version, updated.version);
        assertEquals("new", TextDisplayRuleManager.apply("cat", updated.compiled));
        assertEquals("old", TextDisplayRuleManager.apply("cat", old.compiled));
    }

    @Test public void rawPreferenceChangeIsDetectedEvenWithoutExplicitInvalidation() throws Exception {
        store.edit().putString(RULES, rules("old")).commit();
        TextDisplayRuleManager.ActiveSnapshot old = TextDisplayRuleManager.captureActive(store, "/book.txt");
        store.edit().putString(RULES, rules("new")).commit();
        TextDisplayRuleManager.ActiveSnapshot updated = TextDisplayRuleManager.captureActive(store, "/book.txt");
        assertNotEquals(old.version, updated.version);
        assertEquals("new", TextDisplayRuleManager.apply("cat", updated.compiled));
    }

    @Test public void returnedRuleObjectsCannotMutateCachedSnapshot() throws Exception {
        store.edit().putString(RULES, rules("original")).commit();
        List<TextDisplayRule> editor = TextDisplayRuleManager.getRules(store);
        editor.get(0).replacementText = "unsaved";
        editor.get(0).enabled = false;
        editor.clear();
        assertEquals("original", TextDisplayRuleManager.apply("cat", TextDisplayRuleManager.captureActive(store, "/book.txt").compiled));
    }
}
