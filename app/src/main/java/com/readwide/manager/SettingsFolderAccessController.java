package com.readwide.manager;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.app.Dialog;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

final class SettingsFolderAccessController {
    private final AppCompatActivity activity;
    private final ActivityResultLauncher<Uri> picker;
    private boolean pickerPending;
    private Dialog activeDialog;
    private View activePanel;

    SettingsFolderAccessController(AppCompatActivity activity) {
        this.activity = activity;
        picker = activity.registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
                this::onTreeSelected);
        activity.getLifecycle().addObserver(new androidx.lifecycle.DefaultLifecycleObserver() {
            @Override public void onPause(androidx.lifecycle.LifecycleOwner owner) { dismissDialog(); }
            @Override public void onDestroy(androidx.lifecycle.LifecycleOwner owner) { dismissDialog(); }
        });
    }

    void bind(View entry) {
        if (entry == null) return;
        entry.setOnClickListener(ignored -> showRegisteredFolders());
        entry.setOnLongClickListener(ignored -> {
            open(true);
            return true;
        });
    }

    void open(boolean chooseAgain) {
        if (!canLaunch() || pickerPending) return;
        try {
            Uri usable = chooseAgain ? null : SafStorageAccess.getUsableTree(activity);
            if (usable != null) {
                openBrowser(usable);
                return;
            }
            launchPicker(SafStorageAccess.getRememberedTree(activity));
        } catch (RuntimeException unavailable) {
            pickerPending = false;
            showUnavailable();
        }
    }

    private void launchPicker(Uri initial) {
        if (!canLaunch() || pickerPending) return;
        dismissDialog();
        try {
            pickerPending = true;
            picker.launch(initial);
        } catch (RuntimeException unavailable) {
            pickerPending = false;
            showUnavailable();
        }
    }

    void showRegisteredFolders() {
        if (!canLaunch() || pickerPending) return;
        if (!(activity instanceof SettingsActivity)) { open(false); return; }
        SettingsActivity settings = (SettingsActivity) activity;
        try {
            Dialog dialog = dialogForPage(settings);
            LinearLayout panel = createRegistryPanel(settings, dialog);
            showDialog(settings, dialog, panel);
        } catch (RuntimeException unavailable) { showUnavailable(); }
    }

    LinearLayout createRegistryPanel(SettingsActivity settings, Dialog dialog) {
        LinearLayout panel = settings.createRoundedSettingsDialogPanel();
        panel.addView(settings.makeSettingsDialogTitle(activity.getString(R.string.saf_registered_folders),
                settings.dialogTextColor()));
        java.util.List<SafStorageAccess.RegisteredTree> folders = SafStorageAccess.getRegisteredTrees(activity);
        if (folders.isEmpty()) {
            panel.addView(settings.makeSettingsDialogMessage(activity.getString(R.string.saf_no_registered_folders),
                    settings.dialogSubTextColor()));
        }
        for (SafStorageAccess.RegisteredTree tree : folders) {
            MaterialButton entry = addButton(settings, dialog, panel, describeTree(tree), () -> showFolderActions(tree));
            entry.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            entry.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            entry.setTextSize(14f);
            entry.setMaxLines(2);
            entry.setEllipsize(TextUtils.TruncateAt.END);
            float density = activity.getResources().getDisplayMetrics().density;
            entry.setMinHeight(Math.round(44 * density));
            entry.setMinimumHeight(Math.round(44 * density));
            int verticalPadding = Math.round(6 * density);
            entry.setPaddingRelative(entry.getPaddingStart(), verticalPadding,
                    entry.getPaddingEnd(), verticalPadding);
        }
        addButton(settings, dialog, panel, activity.getString(R.string.saf_add_folder),
                () -> open(true));
        addButton(settings, dialog, panel, activity.getString(R.string.close), () -> {});
        return panel;
    }

    static String describeTree(SafStorageAccess.RegisteredTree tree) {
        // Provider + tree ID distinguish equal folder names on different volumes.
        return tree.label + "\n" + tree.uri.getAuthority() + " / "
                + android.provider.DocumentsContract.getTreeDocumentId(tree.uri);
    }

    private void showFolderActions(SafStorageAccess.RegisteredTree tree) {
        if (!canLaunch() || !(activity instanceof SettingsActivity)) return;
        SettingsActivity settings = (SettingsActivity) activity;
        Dialog dialog = dialogForPage(settings);
        LinearLayout panel = settings.createRoundedSettingsDialogPanel();
        panel.addView(settings.makeSettingsDialogTitle(tree.label, settings.dialogTextColor()));
        panel.addView(settings.makeSettingsDialogMessage(describeTree(tree), settings.dialogSubTextColor()));
        addButton(settings, dialog, panel, activity.getString(R.string.saf_open_folder), () -> {
            try {
                if (SafStorageAccess.useRegisteredTree(activity, tree.uri)) openBrowser(tree.uri);
                else launchPicker(tree.uri);
            } catch (RuntimeException unavailable) { showUnavailable(); }
        });
        addButton(settings, dialog, panel, activity.getString(R.string.saf_revoke_access),
                () -> confirmRemoval(tree));
        addButton(settings, dialog, panel, activity.getString(R.string.cancel), this::showRegisteredFolders);
        showDialog(settings, dialog, panel);
    }

    private void confirmRemoval(SafStorageAccess.RegisteredTree tree) {
        if (!canLaunch() || !(activity instanceof SettingsActivity)) return;
        SettingsActivity settings = (SettingsActivity) activity;
        Dialog dialog = dialogForPage(settings);
        LinearLayout panel = settings.createRoundedSettingsDialogPanel();
        panel.addView(settings.makeSettingsDialogTitle(activity.getString(R.string.saf_revoke_access),
                settings.dialogTextColor()));
        panel.addView(settings.makeSettingsDialogMessage(
                activity.getString(R.string.saf_revoke_confirm, tree.label), settings.dialogSubTextColor()));
        addButton(settings, dialog, panel, activity.getString(R.string.saf_revoke_access), () -> {
            try {
                SafStorageAccess.Removal result = SafStorageAccess.removeRegisteredTree(activity, tree.uri);
                if (result == SafStorageAccess.Removal.BUSY) ShortToast.show(activity, R.string.saf_folder_in_use);
                else if (result == SafStorageAccess.Removal.FAILED) ShortToast.show(activity, R.string.saf_revoke_failed);
                showRegisteredFolders();
            } catch (RuntimeException unavailable) { ShortToast.show(activity, R.string.saf_revoke_failed); }
        });
        addButton(settings, dialog, panel, activity.getString(R.string.cancel), () -> showFolderActions(tree));
        showDialog(settings, dialog, panel);
    }

    private MaterialButton addButton(SettingsActivity settings, Dialog dialog, LinearLayout panel,
                           String text, Runnable action) {
        MaterialButton button = settings.makeSettingsDialogButtonNoShade(text,
                settings.dialogTextColor(), settings.dialogOutlineColor());
        button.setSingleLine(false);
        button.setOnClickListener(ignored -> runDialogAction(dialog, panel, action));
        panel.addView(button);
        return button;
    }

    void runDialogAction(Dialog dialog, View panel, Runnable action) {
        if (!canLaunch() || pickerPending || dialog == null || activeDialog != dialog
                || panel == null || activePanel != panel || !dialog.isShowing()) return;
        // A reused window must not accept callbacks from its previous page.
        activePanel = null;
        try { action.run(); }
        catch (RuntimeException unavailable) { showUnavailable(); }
        finally {
            if (activeDialog == dialog && activePanel == null) dismissDialog();
        }
    }

    private Dialog dialogForPage(SettingsActivity settings) {
        return activeDialog != null && activeDialog.isShowing()
                ? activeDialog : settings.createRoundedSettingsDialog();
    }

    private void showDialog(SettingsActivity settings, Dialog dialog, LinearLayout panel) {
        if (activeDialog == dialog && dialog.isShowing()) {
            activePanel = panel;
            try { settings.replaceRoundedSettingsDialogContent(dialog, panel); }
            catch (RuntimeException unavailable) { dismissDialog(); showUnavailable(); }
            return;
        }
        dismissDialog();
        activeDialog = dialog;
        activePanel = panel;
        dialog.setOnDismissListener(ignored -> {
            if (activeDialog == dialog) {
                activeDialog = null;
                activePanel = null;
            }
        });
        try { settings.showRoundedSettingsDialog(dialog, panel); }
        catch (RuntimeException unavailable) { dismissDialog(); showUnavailable(); }
    }

    void dismissDialog() {
        Dialog dialog = activeDialog;
        activeDialog = null;
        activePanel = null;
        if (dialog != null) dialog.dismiss();
    }

    private void onTreeSelected(Uri tree) {
        pickerPending = false;
        if (tree == null || !canLaunch()) return;
        try {
            if (!SafStorageAccess.selectTree(activity, tree)) {
                showUnavailable();
                return;
            }
            openBrowser(tree);
        } catch (RuntimeException unavailable) {
            showUnavailable();
        }
    }

    private void openBrowser(Uri tree) {
        if (!canLaunch()) return;
        dismissDialog();
        try {
            Intent intent = new Intent(activity, SafBrowserActivity.class);
            intent.putExtra(SafBrowserActivity.EXTRA_TREE_URI, tree.toString());
            activity.startActivity(intent);
        } catch (RuntimeException unavailable) {
            showUnavailable();
        }
    }

    private boolean canLaunch() { return !activity.isFinishing() && !activity.isDestroyed(); }

    private void showUnavailable() {
        if (canLaunch()) ShortToast.show(activity, R.string.containing_folder_unavailable);
    }
}
