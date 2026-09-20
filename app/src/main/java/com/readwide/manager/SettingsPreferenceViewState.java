package com.readwide.manager;

import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;

/** These controls save each choice immediately; preferences own their current values. */
final class SettingsPreferenceViewState {
    private SettingsPreferenceViewState() {}

    static void useStoredValues(View view) {
        if (view == null) return;
        if (view instanceof Switch || view instanceof SeekBar || view instanceof Spinner) {
            // A pre-reset/import hierarchy snapshot must not overwrite newer preferences.
            view.setSaveFromParentEnabled(false);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                useStoredValues(group.getChildAt(i));
            }
        }
    }
}
