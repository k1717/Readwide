package com.readwide.manager;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.system.Os;
import android.view.ContextThemeWrapper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ImageFileAccessRecoveryInstrumentedTest {
    @Test public void recoveryOpensPickerOnceWithoutRepeatingOriginalAction() {
        onMain(f -> {
            FakeDialog original = f.dialog();
            ImageFileAccessRecoveryController.Scope scope = f.own(original);
            FakeDialog offer = f.offer(scope);
            assertFalse(original.showing);
            assertFalse(f.controller.ownsVisible(scope, original));
            assertTrue(f.controller.accept(scope, f.pickerCalls::incrementAndGet));
            assertFalse(f.controller.accept(scope, f.pickerCalls::incrementAndGet));
            assertFalse(offer.showing);
            assertEquals(1, f.pickerCalls.get());
        });
    }

    @Test public void dismissedOriginalCannotOpenRecovery() {
        onMain(f -> {
            FakeDialog original = f.dialog();
            ImageFileAccessRecoveryController.Scope scope = f.own(original);
            original.dismiss();
            assertFalse(f.controller.replaceWithOffer(scope, f.dialog()));
            assertFalse(f.controller.accept(scope, f.pickerCalls::incrementAndGet));
        });
    }

    @Test public void changedSourceCannotOpenRecovery() {
        onMain(f -> {
            ImageFileAccessRecoveryController.Scope scope = f.own(f.dialog());
            f.unchanged = false;
            assertFalse(f.controller.replaceWithOffer(scope, f.dialog()));
            assertEquals(0, f.pickerCalls.get());
        });
    }

    @Test public void imageChangeInvalidatesVisibleOffer() {
        onMain(f -> {
            ImageFileAccessRecoveryController.Scope scope = f.own(f.dialog());
            f.offer(scope);
            f.currentSource = false;
            assertFalse(f.controller.accept(scope, f.pickerCalls::incrementAndGet));
            assertEquals(0, f.pickerCalls.get());
        });
    }

    @Test public void sourceReplacedAfterOfferCannotOpenPicker() {
        onMain(f -> {
            ImageFileAccessRecoveryController.Scope scope = f.own(f.dialog());
            f.offer(scope);
            f.unchanged = false;
            assertFalse(f.controller.accept(scope, f.pickerCalls::incrementAndGet));
            assertEquals(0, f.pickerCalls.get());
        });
    }

    @Test public void lateOriginalDismissDoesNotDiscardItsOffer() {
        onMain(f -> {
            FakeDialog original = f.dialog();
            ImageFileAccessRecoveryController.Scope scope = f.own(original);
            FakeDialog offer = f.offer(scope);
            original.deliverDismiss();
            assertFalse(f.controller.ownsVisible(scope, original));
            assertTrue(f.controller.ownsVisible(scope, offer));
            assertTrue(f.controller.accept(scope, f.pickerCalls::incrementAndGet));
        });
    }

    @Test public void oldScopeCannotDismissOrAcceptReplacement() {
        onMain(f -> {
            FakeDialog original = f.dialog();
            ImageFileAccessRecoveryController.Scope old = f.own(original);
            FakeDialog replacement = f.dialog();
            ImageFileAccessRecoveryController.Scope next = f.own(replacement);
            original.deliverDismiss();
            assertFalse(f.controller.accept(old, f.pickerCalls::incrementAndGet));
            assertTrue(f.controller.ownsVisible(next, replacement));
        });
    }

    @Test public void leavingActivityInvalidatesOfferBeforeDismissCallback() {
        onMain(f -> {
            ImageFileAccessRecoveryController.Scope scope = f.own(f.dialog());
            FakeDialog offer = f.offer(scope);
            f.controller.dismiss();
            assertFalse(f.controller.accept(scope, f.pickerCalls::incrementAndGet));
            assertFalse(offer.showing);
            assertEquals(0, f.pickerCalls.get());
        });
    }

    @Test public void unavailableIdentityCheckFailsClosed() {
        onMain(f -> {
            FakeDialog original = f.dialog();
            ImageFileAccessRecoveryController.Scope scope = f.controller.own(
                    original, () -> true, () -> { throw new SecurityException("unavailable"); });
            assertFalse(f.controller.isCurrent(scope));
            assertFalse(f.controller.replaceWithOffer(scope, f.dialog()));
        });
    }

    @Test public void offerMustBeShowingBeforeItCanLaunchPicker() {
        onMain(f -> {
            ImageFileAccessRecoveryController.Scope scope = f.own(f.dialog());
            FakeDialog offer = f.dialog();
            offer.showing = false;
            assertTrue(f.controller.replaceWithOffer(scope, offer));
            assertFalse(f.controller.accept(scope, f.pickerCalls::incrementAndGet));
            assertEquals(0, f.pickerCalls.get());
        });
    }

    @Test public void recoverySupportsInternalSdAndUsbRawPaths() {
        for (String path : new String[]{"/storage/emulated/0/Pictures/a.png",
                "/storage/1234-5678/a.png", "/storage/usbdrive/a.png"}) {
            assertTrue(ImageFileAccessRecoveryController.isRawSource(true, path, null, null, new File(path)));
        }
    }

    @Test public void recoveryRejectsArchiveCacheContentAndDifferentImage() {
        File selected = new File("/storage/emulated/0/a.png");
        String path = selected.getAbsolutePath();
        assertFalse(ImageFileAccessRecoveryController.isRawSource(false, path, null, null, selected));
        assertFalse(ImageFileAccessRecoveryController.isRawSource(true, path, "content://fixture/image", null, selected));
        assertFalse(ImageFileAccessRecoveryController.isRawSource(true, path, null, "/archive.zip", selected));
        assertFalse(ImageFileAccessRecoveryController.isRawSource(true, path, null, null, new File("/other.png")));
        assertFalse(ImageFileAccessRecoveryController.isRawSource(true, null, null, null, selected));
    }

    @Test public void invalidRenameNamesNeverOfferFolderAccess() {
        File source = new File("/fixture/original.png");
        for (String name : new String[]{"", ".", "..", "../other.png", "sub/file.png", "sub\\file.png",
                "bad\0.png", "original.png"}) {
            assertFalse(name, ImageFileAccessRecoveryController.canOfferRename(source, name));
        }
    }

    @Test public void occupiedRenameNamesIncludingDanglingLinksNeverOfferAccess() throws Exception {
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                "image-access-" + UUID.randomUUID());
        assertTrue(directory.mkdir());
        File source = new File(directory, "source.png");
        File conflict = new File(directory, "occupied.png");
        File subdirectory = new File(directory, "folder");
        File link = new File(directory, "link.png");
        try {
            assertTrue(source.createNewFile());
            assertTrue(conflict.createNewFile());
            assertTrue(subdirectory.mkdir());
            Os.symlink(new File(directory, "missing.png").getAbsolutePath(), link.getAbsolutePath());
            assertFalse(ImageFileAccessRecoveryController.canOfferRename(source, conflict.getName()));
            assertFalse(ImageFileAccessRecoveryController.canOfferRename(source, subdirectory.getName()));
            assertFalse(ImageFileAccessRecoveryController.canOfferRename(source, link.getName()));
            assertTrue(ImageFileAccessRecoveryController.canOfferRename(source, "available.png"));
        } finally {
            link.delete();
            conflict.delete();
            subdirectory.delete();
            source.delete();
            directory.delete();
        }
    }

    private interface Check { void run(Fixture fixture); }

    private static void onMain(Check check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = new Fixture();
            try { check.run(fixture); }
            finally { fixture.controller.dismiss(); }
        });
    }

    private static final class Fixture {
        final ImageFileAccessRecoveryController controller = new ImageFileAccessRecoveryController();
        final AtomicInteger pickerCalls = new AtomicInteger();
        final Context context = new ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                androidx.appcompat.R.style.Theme_AppCompat);
        boolean currentSource = true, unchanged = true;

        FakeDialog dialog() { return new FakeDialog(context); }
        ImageFileAccessRecoveryController.Scope own(FakeDialog dialog) {
            return controller.own(dialog, () -> currentSource, () -> unchanged);
        }
        FakeDialog offer(ImageFileAccessRecoveryController.Scope scope) {
            FakeDialog offer = dialog();
            assertTrue(controller.replaceWithOffer(scope, offer));
            return offer;
        }
    }

    private static final class FakeDialog extends Dialog {
        boolean showing = true;
        DialogInterface.OnDismissListener listener;
        FakeDialog(Context context) { super(context); }
        @Override public boolean isShowing() { return showing; }
        @Override public void dismiss() { showing = false; }
        @Override public void setOnDismissListener(DialogInterface.OnDismissListener listener) {
            this.listener = listener;
        }
        void deliverDismiss() { if (listener != null) listener.onDismiss(this); }
    }
}
