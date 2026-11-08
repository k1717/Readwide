package com.readwide.manager;

import android.app.Dialog;

import com.readwide.manager.util.FileSystemOps;
import com.readwide.manager.util.FileTreeWalk;

import java.io.File;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Keeps a folder-access offer tied to its original image and visible dialog. */
final class ImageFileAccessRecoveryController {
    static final class Scope {
        private Dialog dialog;
        private final BooleanSupplier currentSource;
        private final BooleanSupplier unchangedSource;

        private Scope(Dialog dialog, BooleanSupplier currentSource, BooleanSupplier unchangedSource) {
            this.dialog = dialog;
            this.currentSource = currentSource;
            this.unchangedSource = unchangedSource;
        }
    }

    private Scope current;

    Scope own(Dialog dialog, BooleanSupplier currentSource, BooleanSupplier unchangedSource) {
        dismiss();
        Scope scope = new Scope(dialog, currentSource, unchangedSource);
        current = scope;
        watchDismiss(scope, dialog);
        return scope;
    }

    boolean isCurrent(Scope scope) {
        try {
            return scope != null && ownsVisible(scope, scope.dialog)
                    && scope.unchangedSource.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    boolean ownsVisible(Scope scope, Dialog expected) {
        try {
            return scope != null && current == scope && scope.dialog == expected
                    && expected.isShowing() && scope.currentSource.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    boolean replaceWithOffer(Scope scope, Dialog offer) {
        if (!isCurrent(scope)) return false;
        Dialog original = scope.dialog;
        scope.dialog = offer;
        watchDismiss(scope, offer);
        original.dismiss();
        return true;
    }

    boolean accept(Scope scope, Runnable openFolderAccess) {
        if (!isCurrent(scope)) return false;
        dismiss();
        openFolderAccess.run();
        return true;
    }

    void dismiss() {
        Scope previous = current;
        current = null;
        if (previous != null) previous.dialog.dismiss();
    }

    private void watchDismiss(Scope scope, Dialog dialog) {
        dialog.setOnDismissListener(ignored -> {
            if (current == scope && scope.dialog == dialog) current = null;
        });
    }

    static boolean isRawSource(boolean allowFileOps, String path, String contentUri,
                               String archivePath, File selected) {
        return allowFileOps && path != null && !path.trim().isEmpty()
                && (contentUri == null || contentUri.isEmpty())
                && (archivePath == null || archivePath.isEmpty())
                && selected != null && selected.getAbsolutePath().equals(new File(path).getAbsolutePath());
    }

    static boolean canOfferRename(File source, String name) {
        if (!FileSystemOps.isSingleEntryName(name) || source.getName().equals(name)) return false;
        File parent = source.getParentFile();
        if (parent == null) return false;
        try {
            // A case alias or an occupied name must not be presented as a permission failure.
            return FileTreeWalk.kind(new File(parent, name)) == FileTreeWalk.Kind.MISSING;
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
    }
}
