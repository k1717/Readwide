package com.readwide.manager;

import android.widget.RadioButton;
import android.widget.RadioGroup;

import com.readwide.manager.util.PrefsManager;

/** Persist explicit selections; restoring checked view state must never change the theme. */
final class SettingsThemeSelectionController {
    private final SettingsActivity activity;

    SettingsThemeSelectionController(SettingsActivity activity) {
        this.activity = activity;
    }

    void bind() {
        RadioGroup group = activity.findViewById(R.id.dark_mode_group);
        if (group == null) return;
        // Preferences own this state. Skip the entire subtree during hierarchy save/restore,
        // including old bundles written before this fix.
        group.setSaveFromParentEnabled(false);
        group.setOnCheckedChangeListener(null);
        int[][] options = {
                {R.id.radio_system, PrefsManager.DARK_MODE_FOLLOW_SYSTEM},
                {R.id.radio_light, PrefsManager.DARK_MODE_OFF},
                {R.id.radio_dark, PrefsManager.DARK_MODE_ON},
                {R.id.radio_dark_navy, PrefsManager.DARK_MODE_DARK_NAVY},
                {R.id.radio_custom_main, PrefsManager.DARK_MODE_CUSTOM}
        };
        int current = activity.prefs.getDarkMode();
        int selectedId = R.id.radio_system;
        for (int[] option : options) {
            int mode = option[1];
            if (mode == current) selectedId = option[0];
            RadioButton button = activity.findViewById(option[0]);
            button.setOnClickListener(v -> {
                if (activity.prefs.getDarkMode() == mode) return;
                activity.prefs.setDarkMode(mode);
                // AppCompat owns recreation when the night configuration changes.
                // Same-night palette switches (dark/navy/custom) refresh in place.
                activity.refreshMainThemeAppearance();
            });
        }
        group.check(selectedId);
    }
}
