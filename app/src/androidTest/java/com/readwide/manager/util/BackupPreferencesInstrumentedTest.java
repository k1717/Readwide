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
    @Rule public org.junit.rules.TemporaryFolder folders = new org.junit.rules.TemporaryFolder(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
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

    @Test public void epubAlignmentRoundTripsIndependentlyOfTxtAlignment() throws Exception {
        PrefsManager preferences = manager(store);
        assertEquals(EpubTextAlignment.BOOK_DEFAULT, preferences.getEpubTextAlignment());
        preferences.setTextAlignment(PrefsManager.TEXT_ALIGN_CENTER);
        for (int mode : new int[]{EpubTextAlignment.START, EpubTextAlignment.JUSTIFY}) {
            preferences.setEpubTextAlignment(mode);
            JSONObject exported = preferences.exportSettingsToJson();
            preferences.setEpubTextAlignment(EpubTextAlignment.BOOK_DEFAULT);
            preferences.importSettingsFromJson(exported, false);
            assertEquals(mode, preferences.getEpubTextAlignment());
            assertEquals(PrefsManager.TEXT_ALIGN_CENTER, preferences.getTextAlignment());
        }
    }

    @Test public void epubAlignmentResetAndOldReplacementBackupUseBookDefault() throws Exception {
        PrefsManager preferences = manager(store);
        preferences.setEpubTextAlignment(EpubTextAlignment.JUSTIFY);
        preferences.setEpubFontSize(28f);
        preferences.setEpubLineHeight(4);
        int nightMode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode();
        androidx.core.os.LocaleListCompat locales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try { preferences.resetReaderAndAppSettings(); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });
        } finally {
            // Reset also applies these process-wide settings; do not leak them to other tests.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode);
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales);
            });
        }
        assertEquals(EpubTextAlignment.BOOK_DEFAULT, preferences.getEpubTextAlignment());
        assertEquals(EpubTypography.DEFAULT_FONT_SIZE, preferences.getEpubFontSize(), 0f);
        assertEquals(0, preferences.getEpubLineHeight());
        preferences.setEpubTextAlignment(EpubTextAlignment.START);
        preferences.importSettingsFromJson(setting("marker", "string", "old backup"), true);
        assertEquals(EpubTextAlignment.START, preferences.getEpubTextAlignment());
        preferences.importSettingsFromJson(setting("marker", "string", "old backup"), false);
        assertEquals(EpubTextAlignment.BOOK_DEFAULT, preferences.getEpubTextAlignment());
    }

    @Test public void malformedEpubAlignmentTypeIsRejectedBeforePreferenceChanges() throws Exception {
        store.edit().putString("marker", "keep").commit();
        Map<String, ?> before = store.getAll();
        try {
            manager(store).importSettingsFromJson(setting("epub_text_alignment", "string", "justify"), false);
            fail("Expected validation error");
        } catch (org.json.JSONException expected) { assertEquals(before, store.getAll()); }
    }

    @Test public void failedResetCommitRestoresValuesAndDoesNotApplyLocaleOrTheme() throws Exception {
        store.edit().putFloat("font_size", 29f).putFloat("line_spacing", 1.7f)
                .putBoolean("show_status_bar", true).putInt("dark_mode", PrefsManager.DARK_MODE_DARK_NAVY)
                .putInt("language_mode", PrefsManager.LANGUAGE_KOREAN).putString("font_family", "serif")
                .putString("folder_shortcuts", "/Books").putString("lock_pin", "test-only-pin")
                .putBoolean("lock_enabled", true).putString("auto_text_encoding::book", "UTF-8")
                .commit();
        Map<String, ?> before = store.getAll();
        PrefsManager preferences = manager(resetCommitFailures(1, null));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            int nightMode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode();
            androidx.core.os.LocaleListCompat locales =
                    androidx.appcompat.app.AppCompatDelegate.getApplicationLocales();
            try { preferences.resetReaderAndAppSettings(); fail("Expected reset commit error"); }
            catch (IOException expected) { assertEquals(before, store.getAll()); }
            assertEquals(nightMode, androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode());
            assertEquals(locales, androidx.appcompat.app.AppCompatDelegate.getApplicationLocales());
        });
        assertFalse("absent reset values stay absent", store.contains("epub_line_height"));
    }

    @Test public void resetRollbackLeavesUnrelatedNewValuesAlone() throws Exception {
        store.edit().putFloat("font_size", 25f).putString("folder_shortcuts", "/Before")
                .putString("marker", "old").commit();
        PrefsManager preferences = manager(resetCommitFailures(1, () ->
                store.edit().putString("folder_shortcuts", "/Updated")
                        .putString("marker", "new").putString("auto_text_encoding::book", "UTF-16LE").commit()));
        try { preferences.resetReaderAndAppSettings(); fail("Expected reset commit error"); }
        catch (IOException expected) { assertEquals(25f, preferences.getFontSize(), 0f); }
        assertEquals("/Updated", store.getString("folder_shortcuts", null));
        assertEquals("new", store.getString("marker", null));
        assertEquals("UTF-16LE", store.getString("auto_text_encoding::book", null));
    }

    @Test public void resetAlsoReportsRollbackCommitFailure() throws Exception {
        store.edit().putFloat("font_size", 25f).commit();
        PrefsManager preferences = manager(resetCommitFailures(2, null));
        try { preferences.resetReaderAndAppSettings(); fail("Expected rollback error"); }
        catch (IOException expected) {
            assertTrue(expected.getMessage().contains("restoring previous settings also failed"));
            assertNotNull(expected.getCause());
            assertEquals(1, expected.getCause().getSuppressed().length);
        }
    }

    private SharedPreferences resetCommitFailures(int failures, Runnable afterFirstCommit) {
        java.util.concurrent.atomic.AtomicInteger commits = new java.util.concurrent.atomic.AtomicInteger();
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(store, args);
                    SharedPreferences.Editor editor = store.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, editMethod, editArgs) -> {
                                Object result = editMethod.invoke(editor, editArgs);
                                if (editMethod.getName().equals("commit")) {
                                    int attempt = commits.incrementAndGet();
                                    if (attempt == 1 && afterFirstCommit != null) afterFirstCommit.run();
                                    // Simulate the important failure mode: memory was
                                    // already changed even though commit reports false.
                                    if (attempt <= failures) return false;
                                }
                                return result instanceof SharedPreferences.Editor ? editorProxy : result;
                            });
                });
    }

    @Test public void unknownEpubAlignmentCannotBecomeCssOrAnInvalidSpinnerPosition() throws Exception {
        PrefsManager preferences = manager(store);
        preferences.setEpubTextAlignment(-1);
        assertEquals(EpubTextAlignment.BOOK_DEFAULT, preferences.getEpubTextAlignment());
        preferences.importSettingsFromJson(setting("epub_text_alignment", "int", Integer.MAX_VALUE), false);
        assertEquals(EpubTextAlignment.BOOK_DEFAULT, preferences.getEpubTextAlignment());
    }
    @Test public void epubSizeMigratesBeforeTxtChangesAndThenStaysIndependent() throws Exception {
        store.edit().putFloat("font_size", 28f).commit();
        PrefsManager preferences = manager(store);
        preferences.setFontSize(20f); // TXT is changed before EPUB is opened.
        assertEquals(28f, preferences.getEpubFontSize(), 0f);
        preferences.setEpubFontSize(22f);
        assertEquals(20f, preferences.getFontSize(), 0f);
        assertEquals(22f, manager(store).getEpubFontSize(), 0f);
    }

    @Test public void epubTypographyRoundTripsWithoutChangingTxtSpacing() throws Exception {
        PrefsManager preferences = manager(store);
        preferences.setFontSize(20f);
        preferences.setLineSpacing(1.7f);
        preferences.setEpubFontSize(24f);
        preferences.setEpubLineHeight(4);
        JSONObject backup = preferences.exportSettingsToJson();
        preferences.setEpubFontSize(16f);
        preferences.setEpubLineHeight(0);
        preferences.importSettingsFromJson(backup, false);
        assertEquals(24f, preferences.getEpubFontSize(), 0f);
        assertEquals(4, preferences.getEpubLineHeight());
        assertEquals(20f, preferences.getFontSize(), 0f);
        assertEquals(1.7f, preferences.getLineSpacing(), 0f);
    }

    @Test public void oldBackupReplacementMigratesItsSizeAndMergeRetainsExistingEpubSize() throws Exception {
        store.edit().putFloat("font_size", 28f).commit();
        PrefsManager preferences = manager(store);
        JSONObject legacy = setting("font_size", "float", 20f);
        preferences.importSettingsFromJson(legacy, true);
        assertEquals(28f, preferences.getEpubFontSize(), 0f);
        preferences.setEpubLineHeight(5);
        preferences.importSettingsFromJson(legacy, false);
        assertEquals(20f, preferences.getEpubFontSize(), 0f);
        assertEquals(0, preferences.getEpubLineHeight());
        preferences.importSettingsFromJson(setting("marker", "string", "old defaults"), false);
        assertEquals(EpubTypography.DEFAULT_FONT_SIZE, preferences.getEpubFontSize(), 0f);
    }

    @Test public void exportingBeforeFirstEpubOpenCapturesLegacySize() throws Exception {
        store.edit().putFloat("font_size", 26f).commit();
        JSONObject backup = manager(store).exportSettingsToJson();
        assertEquals(26.0, backup.getJSONObject("values").getJSONObject("epub_font_size").getDouble("value"), 0.0);
    }

    @Test public void invalidEpubTypographyBackupIsRejectedWithoutMutation() throws Exception {
        store.edit().putFloat("epub_font_size", 24f).putInt("epub_line_height", 3).commit();
        Map<String, ?> before = store.getAll();
        JSONObject[] invalid = {
                setting("epub_font_size", "int", 20), setting("epub_font_size", "float", 0f),
                setting("epub_font_size", "float", 49f), setting("epub_font_size", "float", Double.MAX_VALUE),
                setting("epub_line_height", "string", "1.5"), setting("epub_line_height", "int", -1),
                setting("epub_line_height", "int", 6)
        };
        for (JSONObject incoming : invalid) {
            try { manager(store).importSettingsFromJson(incoming, false); fail("Expected validation error"); }
            catch (org.json.JSONException expected) { assertEquals(before, store.getAll()); }
        }
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

    @Test public void missingTtsSignatureStillRequiresStringType() throws Exception {
        store.edit().putString("marker", "keep").commit();
        Map<String, ?> before = store.getAll();
        try { manager(store).importSettingsFromJson(setting("tts_last_content_signature", "int", 7), false); fail(); }
        catch (org.json.JSONException expected) { assertEquals(before, store.getAll()); }
    }

    @Test public void rejectsUnsafeRenderingNumbersWithoutChangingPreferences() throws Exception {
        store.edit().putString("marker", "keep").commit();
        Map<String, ?> before = store.getAll();
        for (String key : new String[]{"font_size", "line_spacing", "brightness_value"}) {
            for (float value : new float[]{-1f, Float.MAX_VALUE}) {
                try { manager(store).importSettingsFromJson(setting(key, "float", value), false); fail(); }
                catch (org.json.JSONException expected) { assertEquals(before, store.getAll()); }
            }
        }
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

    @Test public void foreignMissingFoldersKeepValidLocalFallbacksDuringReplacement() throws Exception {
        String local = folders.newFolder("local").getAbsolutePath();
        store.edit().putString("last_directory", local).putString("recent_folders", local)
                .putString("folder_shortcuts", local).commit();
        String missing = new java.io.File(folders.getRoot(), "not-transferred").getAbsolutePath();
        JSONObject incoming = setting("last_directory", "string", "content://unrestored-permission/tree");
        incoming.getJSONObject("values").put("recent_folders", setting("recent_folders", "string", missing)
                .getJSONObject("values").get("recent_folders"));
        incoming.getJSONObject("values").put("folder_shortcuts", setting("folder_shortcuts", "string", missing)
                .getJSONObject("values").get("folder_shortcuts"));
        manager(store).importSettingsFromJson(incoming, false);
        for (String key : new String[]{"last_directory", "recent_folders", "folder_shortcuts"})
            assertEquals(local, store.getString(key, null));
    }

    @Test public void availableFolderListsFilterMissingAndDuplicatePathsWithoutCreatingThem() throws Exception {
        String available = folders.newFolder("available").getAbsolutePath();
        java.io.File missing = new java.io.File(folders.getRoot(), "not-created");
        manager(store).importSettingsFromJson(setting("recent_folders", "string",
                missing.getAbsolutePath() + "\n" + available + "\n" + available), false);
        assertEquals(available, store.getString("recent_folders", null));
        assertFalse(missing.exists());
    }

    @Test public void missingFontReferenceRemainsPortableAndResolvesToDefaultWithoutImportingFiles() throws Exception {
        java.io.File missing = new java.io.File(folders.getRoot(), "not-transferred.ttf");
        PrefsManager preferences = manager(store);
        preferences.importSettingsFromJson(setting("font_family", "string", missing.getAbsolutePath()), false);
        assertEquals(missing.getAbsolutePath(), preferences.getFontFamily());
        assertEquals(android.graphics.Typeface.DEFAULT, FontManager.getInstance().getTypeface(preferences.getFontFamily()));
        assertNull(FontManager.getInstance().getFontPathForName(preferences.getFontFamily()));
        assertFalse(missing.exists());
    }
}
