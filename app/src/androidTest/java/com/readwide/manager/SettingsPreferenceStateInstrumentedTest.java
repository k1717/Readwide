package com.readwide.manager;

import android.content.Context;
import android.os.Parcelable;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real widget hierarchy checks; uses no live preferences and launches no Activity. */
@RunWith(AndroidJUnit4.class)
public class SettingsPreferenceStateInstrumentedTest {
    private Context context() {
        return new ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                R.style.Theme_TextViewReader);
    }

    @Test public void allPersistedControlsInActualLayoutUseStoredValues() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View root = LayoutInflater.from(context()).inflate(R.layout.activity_settings, null, false);
            SettingsPreferenceViewState.useStoredValues(root);
            assertTrue(root.isSaveFromParentEnabled());
            assertTrue(root.findViewById(R.id.settings_scroll).isSaveFromParentEnabled());
            assertTrue(checkControls(root) > 20);
        });
    }

    private int checkControls(View view) {
        if (view instanceof Switch || view instanceof Spinner || view instanceof SeekBar) {
            assertFalse(view.isSaveFromParentEnabled());
            return 1;
        }
        int count = 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += checkControls(group.getChildAt(i));
        }
        return count;
    }

    private LinearLayout controls(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setId(100);
        root.setOrientation(LinearLayout.VERTICAL);
        Switch toggle = new Switch(context);
        toggle.setId(101);
        root.addView(toggle);
        SeekBar slider = new SeekBar(context);
        slider.setId(102);
        root.addView(slider);
        Spinner spinner = new Spinner(context);
        spinner.setId(103);
        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_item,
                new String[]{"left", "center", "right"}));
        root.addView(spinner);
        EditText draft = new EditText(context);
        draft.setId(104);
        root.addView(draft);
        return root;
    }

    @Test public void oldHierarchyCannotUndoNewChoicesButKeepsTextDraft() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context();
            LinearLayout old = controls(context);
            ((Switch) old.findViewById(101)).setChecked(true);
            ((SeekBar) old.findViewById(102)).setProgress(32);
            ((Spinner) old.findViewById(103)).setSelection(0);
            ((EditText) old.findViewById(104)).setText("#123456");
            SparseArray<Parcelable> state = new SparseArray<>();
            old.saveHierarchyState(state);

            LinearLayout current = controls(context);
            SettingsPreferenceViewState.useStoredValues(current);
            Switch toggle = current.findViewById(101);
            SeekBar slider = current.findViewById(102);
            Spinner spinner = current.findViewById(103);
            toggle.setChecked(false);
            slider.setProgress(18);
            spinner.setSelection(2);
            int[] writes = {0};
            toggle.setOnCheckedChangeListener((button, checked) -> writes[0]++);
            current.restoreHierarchyState(state);

            assertFalse(toggle.isChecked());
            assertEquals(18, slider.getProgress());
            assertEquals(2, spinner.getSelectedItemPosition());
            assertEquals(0, writes[0]);
            assertEquals("#123456", ((EditText) current.findViewById(104)).getText().toString());
        });
    }
}
