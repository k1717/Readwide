package com.readwide.manager.util;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.lang.reflect.Constructor;
import java.util.UUID;
import static org.junit.Assert.*;

/** Isolated preference storage; never obtains or modifies the live PrefsManager singleton. */
@RunWith(AndroidJUnit4.class)
public class NavigationPreferencesInstrumentedTest {
    private Context context;
    private SharedPreferences store;
    private PrefsManager prefs;
    private String name;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        name = "navigation-preferences-test-" + UUID.randomUUID();
        store = context.getSharedPreferences(name, Context.MODE_PRIVATE);
        Context isolated = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String ignored, int mode) { return store; }
        };
        Constructor<PrefsManager> constructor = PrefsManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        prefs = constructor.newInstance(isolated);
    }

    @After public void tearDown() { context.deleteSharedPreferences(name); }

    @Test public void rebindsAllNavigationCollectionsWithoutReorderingOrDuplicating() {
        store.edit().putString("folder_shortcuts", "/Old/Part\n/Other\n/New/Part")
                .putString("recent_folders", "/Old\n/Oldest\n/Old/Part")
                .putString("hidden_recent_folders", "/Old/Hidden\n/Elsewhere")
                .putString("last_directory", "/Old/Part").commit();
        prefs.moveFolderReferences("/Old", "/New");
        assertEquals("/New/Part\n/Other", store.getString("folder_shortcuts", null));
        assertEquals("/New\n/Oldest\n/New/Part", store.getString("recent_folders", null));
        assertEquals("/New/Hidden\n/Elsewhere", store.getString("hidden_recent_folders", null));
        assertEquals("/New/Part", prefs.getLastDirectory());
    }

    @Test public void rebindingDoesNotTruncateImportedShortcutLists() {
        StringBuilder paths = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            if (i > 0) paths.append('\n');
            paths.append("/Old/").append(i);
        }
        store.edit().putString("folder_shortcuts", paths.toString()).commit();
        prefs.moveFolderReferences("/Old", "/New");
        assertEquals(80, prefs.getFolderShortcuts(0).size());
        assertEquals("/New/79", prefs.getFolderShortcuts(0).get(79));
    }

    @Test public void resetRestoresListDisplayAndScopeWhileKeepingPinsAndPosition() {
        store.edit().putInt("file_display_mode", PrefsManager.FILE_DISPLAY_TILES)
                .putBoolean("file_search_all_folders", true)
                .putString("folder_shortcuts", "/Books")
                .putString("last_directory", "/Books").commit();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            int night = AppCompatDelegate.getDefaultNightMode();
            LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
            try { prefs.resetReaderAndAppSettings(); }
            catch (java.io.IOException failure) { throw new AssertionError(failure); }
            finally {
                AppCompatDelegate.setDefaultNightMode(night);
                AppCompatDelegate.setApplicationLocales(locales);
            }
        });
        assertEquals(PrefsManager.FILE_DISPLAY_LIST, prefs.getFileDisplayMode());
        assertFalse(prefs.getFileSearchAllFolders());
        assertEquals("/Books", prefs.getFolderShortcuts(0).get(0));
        assertEquals("/Books", prefs.getLastDirectory());
    }
}
