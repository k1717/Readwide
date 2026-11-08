package com.readwide.manager;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SafStorageAccessInstrumentedTest {
    private static final int READ = Intent.FLAG_GRANT_READ_URI_PERMISSION;
    private static final int WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    @Test public void addingTreeKeepsPreviousRegistrationAndGrant() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Internal");
            f.resolver.onRelease = () -> assertEquals(f.second,
                    SafStorageAccess.getRememberedTree(f.context));
            assertTrue(f.select(f.second, "USB"));
            assertEquals("USB", SafStorageAccess.getRememberedLabel(f.context));
            assertTrue(f.resolver.released.isEmpty());
            assertTrue(f.resolver.grants.containsKey(f.first));
            assertEquals(Integer.valueOf(READ | WRITE), f.resolver.grants.get(f.second));
            assertEquals(2, SafStorageAccess.getRegisteredTrees(f.context).size());
            assertEquals("Internal", SafStorageAccess.getRegisteredTrees(f.context).get(0).label);
        }
    }

    @Test public void lastPinReleasedBeforeSelectionKeepsRegisteredGrant() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Internal");
            SafStorageAccess.pinTree(f.first);
            SafStorageAccess.unpinTree(f.context, f.first);
            assertTrue(f.resolver.released.isEmpty());
            assertTrue(f.select(f.second, "External"));
            assertTrue(f.resolver.released.isEmpty());
        }
    }

    @Test public void lastOutgoingPinDuringSelectionKeepsBothRegisteredGrants() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Internal");
            SafStorageAccess.pinTree(f.first);
            raceLastUnpinWithSelection(f, f.first, f.second);
            assertEquals(f.second, SafStorageAccess.getRememberedTree(f.context));
            assertTrue(f.resolver.released.isEmpty());
        }
    }

    @Test public void lastIncomingPinCannotRevokeTheGrantBeingSelected() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remember(f.second, "USB");
            f.resolver.grants.put(f.first, READ | WRITE);
            SafStorageAccess.pinTree(f.first);
            raceLastUnpinWithSelection(f, f.first, f.first);
            assertEquals(f.first, SafStorageAccess.getRememberedTree(f.context));
            assertTrue(f.resolver.released.isEmpty());
            assertEquals(Integer.valueOf(READ | WRITE), f.resolver.grants.get(f.first));
        }
    }

    @Test public void browserAndClipboardPinsEachKeepTheirSourceGrant() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Internal");
            SafStorageAccess.pinTree(f.first);
            SafStorageAccess.pinTree(f.first);
            assertTrue(f.select(f.second, "External"));
            assertEquals(SafStorageAccess.Removal.BUSY, SafStorageAccess.removeRegisteredTree(f.context, f.first));
            SafStorageAccess.unpinTree(f.context, f.first);
            assertTrue(f.resolver.released.isEmpty());
            assertEquals(SafStorageAccess.Removal.BUSY, SafStorageAccess.removeRegisteredTree(f.context, f.first));
            SafStorageAccess.unpinTree(f.context, f.first);
            assertTrue(f.resolver.released.isEmpty());
            assertEquals(SafStorageAccess.Removal.REMOVED, SafStorageAccess.removeRegisteredTree(f.context, f.first));
            assertEquals(Collections.singletonList(f.first), f.resolver.released);
        }
    }

    @Test public void reselectingCurrentTreeKeepsGrantAfterLastPin() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Internal");
            SafStorageAccess.pinTree(f.first);
            assertTrue(f.select(f.first, "Renamed label"));
            SafStorageAccess.unpinTree(f.context, f.first);
            assertTrue(f.resolver.released.isEmpty());
            assertEquals("Renamed label", SafStorageAccess.getRememberedLabel(f.context));
            assertEquals(1, SafStorageAccess.getRegisteredTrees(f.context).size());
        }
    }

    @Test public void rejectedReplacementPreservesPreviousSelectionAndGrant() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Internal");
            f.resolver.denyRead = true;
            assertFalse(f.select(f.second, "Rejected"));
            assertEquals(f.first, SafStorageAccess.getRememberedTree(f.context));
            assertEquals("Internal", SafStorageAccess.getRememberedLabel(f.context));
            assertTrue(f.resolver.released.isEmpty());
            assertEquals(Integer.valueOf(READ | WRITE), f.resolver.grants.get(f.first));
        }
    }

    @Test public void readOnlyRegistrationsRemainUntilExplicitRemoval() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Read-only internal");
            f.resolver.grants.put(f.first, READ);
            f.resolver.denyWrite = true;
            f.resolver.rejectUnheldReleaseFlags = true;
            assertTrue(f.select(f.second, "Read-only external"));
            assertEquals(Integer.valueOf(READ), f.resolver.grants.get(f.second));
            assertTrue(f.resolver.released.isEmpty());
            assertEquals(f.second, SafStorageAccess.getUsableTree(f.context));
            assertFalse(SafStorageAccess.hasPersistedWritePermission(f.context, f.second));
            assertEquals(SafStorageAccess.Removal.REMOVED, SafStorageAccess.removeRegisteredTree(f.context, f.first));
            assertEquals(Collections.singletonList(f.first), f.resolver.released);
        }
    }

    @Test public void unbalancedUnpinDoesNotReleaseAnUnrelatedGrant() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Internal");
            f.resolver.grants.put(f.second, READ | WRITE);
            SafStorageAccess.unpinTree(f.context, f.second);
            SafStorageAccess.unpinTree(f.context, null);
            assertTrue(f.resolver.released.isEmpty());
            assertEquals(Integer.valueOf(READ | WRITE), f.resolver.grants.get(f.second));
        }
    }

    @Test public void staleMissingPermissionCannotForgetDifferentNewSelection() throws Exception {
        stalePermissionCheckKeepsSelection(false);
    }

    @Test public void staleMissingPermissionCannotForgetRetakenSameSelection() throws Exception {
        stalePermissionCheckKeepsSelection(true);
    }

    @Test public void missingPermissionKeepsRegistrationForReauthorization() {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Disconnected USB");
            f.resolver.grants.remove(f.first);
            f.resolver.grants.put(f.second, READ);
            assertNull(SafStorageAccess.getUsableTree(f.context));
            assertEquals(f.first, SafStorageAccess.getRememberedTree(f.context));
            assertEquals("Disconnected USB", SafStorageAccess.getRememberedLabel(f.context));
            assertEquals(1, SafStorageAccess.getRegisteredTrees(f.context).size());
            assertTrue(f.resolver.released.isEmpty());
            assertEquals(Integer.valueOf(READ), f.resolver.grants.get(f.second));
        }
    }

    @Test public void legacyFolderIsListedBeforeAnyRegistryWrite() {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Existing books");
            List<SafStorageAccess.RegisteredTree> trees = SafStorageAccess.getRegisteredTrees(f.context);
            assertEquals(1, trees.size());
            assertEquals(f.first, trees.get(0).uri);
            assertEquals("Existing books", trees.get(0).label);
            assertFalse(f.prefs.contains("registered_trees"));
        }
    }

    @Test public void openingRegisteredFolderChangesOnlyLastSelection() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Internal");
            assertTrue(f.select(f.second, "USB"));
            assertTrue(SafStorageAccess.useRegisteredTree(f.context, f.first));
            assertEquals(f.first, SafStorageAccess.getRememberedTree(f.context));
            assertEquals("Internal", SafStorageAccess.getRememberedLabel(f.context));
            assertEquals(2, SafStorageAccess.getRegisteredTrees(f.context).size());
            assertTrue(f.resolver.released.isEmpty());
        }
    }

    @Test public void unregisteredGrantCannotBecomeSelectedThroughRegistry() {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Internal");
            f.resolver.grants.put(f.second, READ);
            assertFalse(SafStorageAccess.useRegisteredTree(f.context, f.second));
            assertEquals(SafStorageAccess.Removal.NOT_REGISTERED,
                    SafStorageAccess.removeRegisteredTree(f.context, f.second));
            assertTrue(f.resolver.released.isEmpty());
            assertEquals(f.first, SafStorageAccess.getRememberedTree(f.context));
        }
    }

    @Test public void removeSelectedTreeKeepsOtherRegistrations() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Internal");
            assertTrue(f.select(f.second, "USB"));
            assertEquals(SafStorageAccess.Removal.REMOVED, SafStorageAccess.removeRegisteredTree(f.context, f.second));
            assertNull(SafStorageAccess.getRememberedTree(f.context));
            assertEquals(f.first, SafStorageAccess.getRegisteredTrees(f.context).get(0).uri);
            assertEquals(Integer.valueOf(READ | WRITE), f.resolver.grants.get(f.first));
            assertTrue(SafStorageAccess.useRegisteredTree(f.context, f.first));
        }
    }

    @Test public void failedRevocationKeepsFolderInRegistry() {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Internal");
            f.resolver.denyRelease = true;
            assertEquals(SafStorageAccess.Removal.FAILED, SafStorageAccess.removeRegisteredTree(f.context, f.first));
            assertEquals(1, SafStorageAccess.getRegisteredTrees(f.context).size());
            assertEquals(f.first, SafStorageAccess.getRememberedTree(f.context));
        }
    }

    @Test public void alreadyRevokedFolderCanBeRemovedWithoutTouchingOtherGrants() {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Internal");
            f.resolver.grants.remove(f.first);
            f.resolver.grants.put(f.second, READ);
            f.resolver.rejectUnheldReleaseFlags = true;
            assertEquals(SafStorageAccess.Removal.REMOVED, SafStorageAccess.removeRegisteredTree(f.context, f.first));
            assertTrue(SafStorageAccess.getRegisteredTrees(f.context).isEmpty());
            assertEquals(Integer.valueOf(READ), f.resolver.grants.get(f.second));
            assertTrue(f.resolver.released.isEmpty());
        }
    }

    @Test public void unreadablePermissionListCannotConfirmRevocation() {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Internal");
            f.resolver.denyRelease = true;
            f.resolver.onPermissions = () -> { throw new SecurityException("fixture"); };
            assertEquals(SafStorageAccess.Removal.FAILED, SafStorageAccess.removeRegisteredTree(f.context, f.first));
            assertEquals(1, SafStorageAccess.getRegisteredTrees(f.context).size());
        }
    }

    @Test public void corruptRegistryNeverReleasesPinnedGrantOrOverwritesRegistration() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Internal");
            f.prefs.edit().putString("registered_trees", "invalid").apply();
            SafStorageAccess.pinTree(f.first);
            SafStorageAccess.unpinTree(f.context, f.first);
            assertTrue(f.resolver.released.isEmpty());
            try {
                SafStorageAccess.removeRegisteredTree(f.context, f.first);
                fail("Malformed registry must not be treated as empty");
            } catch (ClassCastException expected) { }
            assertEquals("invalid", f.prefs.getString("registered_trees", null));
            assertEquals(Integer.valueOf(READ | WRITE), f.resolver.grants.get(f.first));
        }
    }

    @Test public void removedFolderCanBeAddedAgain() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Old");
            assertEquals(SafStorageAccess.Removal.REMOVED, SafStorageAccess.removeRegisteredTree(f.context, f.first));
            assertTrue(f.select(f.first, "Again"));
            assertEquals(1, SafStorageAccess.getRegisteredTrees(f.context).size());
            assertEquals("Again", SafStorageAccess.getRegisteredTrees(f.context).get(0).label);
            assertTrue(SafStorageAccess.hasPersistedReadPermission(f.context, f.first));
        }
    }

    @Test public void invalidTreeCannotAcquireOrReplaceGrant() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Internal");
            assertFalse(f.select(Uri.parse("file:///books"), "Invalid"));
            assertEquals(1, f.resolver.grants.size());
            assertEquals(f.first, SafStorageAccess.getRememberedTree(f.context));
        }
    }

    @Test public void unregisteredTemporaryGrantStillReleasesAfterLastPin() {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Internal");
            f.resolver.grants.put(f.second, READ);
            SafStorageAccess.pinTree(f.second);
            SafStorageAccess.unpinTree(f.context, f.second);
            assertEquals(Collections.singletonList(f.second), f.resolver.released);
            assertEquals(Integer.valueOf(READ | WRITE), f.resolver.grants.get(f.first));
        }
    }

    @Test public void returnedRegistryListCannotMutateSavedFolders() {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Internal");
            SafStorageAccess.getRegisteredTrees(f.context).clear();
            assertEquals(1, SafStorageAccess.getRegisteredTrees(f.context).size());
            assertEquals(f.first, SafStorageAccess.getRememberedTree(f.context));
        }
    }

    private static void raceLastUnpinWithSelection(Fixture f, Uri pinned, Uri incoming)
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        FutureTask<Void> lastUnpin = new FutureTask<>(() -> {
            entered.countDown();
            try { SafStorageAccess.unpinTree(f.context, pinned); }
            finally { finished.countDown(); }
            return null;
        });
        Thread worker = new Thread(lastUnpin, "saf-last-pin-fixture");
        f.resolver.onTake = () -> {
            f.resolver.onTake = null;
            worker.start();
            await(entered);
            try {
                assertFalse("Last unpin crossed the selection transaction",
                        finished.await(100, TimeUnit.MILLISECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        try { assertTrue(f.select(incoming, "Selected")); }
        finally {
            if (worker.getState() != Thread.State.NEW) lastUnpin.get(5, TimeUnit.SECONDS);
        }
    }

    private static void stalePermissionCheckKeepsSelection(boolean sameTree) throws Exception {
        try (Fixture f = new Fixture()) {
            f.remember(f.first, "Old label");
            CountDownLatch queried = new CountDownLatch(1);
            CountDownLatch releaseQuery = new CountDownLatch(1);
            f.resolver.onPermissions = () -> {
                queried.countDown();
                await(releaseQuery);
            };
            FutureTask<Uri> check = new FutureTask<>(() -> SafStorageAccess.getUsableTree(f.context));
            new Thread(check, "saf-permission-query-fixture").start();
            Uri replacement = sameTree ? f.first : f.second;
            try {
                assertTrue(queried.await(5, TimeUnit.SECONDS));
                assertTrue(f.select(replacement, "New label"));
            } finally { releaseQuery.countDown(); }
            assertNull(check.get(5, TimeUnit.SECONDS));
            assertEquals(replacement, SafStorageAccess.getRememberedTree(f.context));
            assertEquals("New label", SafStorageAccess.getRememberedLabel(f.context));
            f.resolver.onPermissions = null;
            assertEquals(replacement, SafStorageAccess.getUsableTree(f.context));
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String prefsName = "saf-grant-fixture-" + UUID.randomUUID();
        final SharedPreferences prefs = target.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        final Uri first = Uri.parse("content://readwide.grant.fixture/tree/primary-" + UUID.randomUUID());
        final Uri second = Uri.parse("content://readwide.grant.fixture/tree/usb-" + UUID.randomUUID());
        final RecordingResolver resolver = new RecordingResolver(target);
        final Context context = new ContextWrapper(target) {
            @Override public ContentResolver getContentResolver() { return resolver; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
        };

        void remember(Uri tree, String label) {
            prefs.edit().putString("tree_uri", tree.toString()).putString("tree_label", label).apply();
            resolver.grants.put(tree, READ | WRITE);
        }

        boolean select(Uri tree, String label) throws Exception {
            // Register without a provider metadata query or real system grants.
            Method method = SafStorageAccess.class.getDeclaredMethod(
                    "registerTreeGrant", Context.class, Uri.class, String.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(null, context, tree, label);
        }

        @SuppressWarnings("unchecked")
        @Override public void close() {
            try {
                Field field = SafStorageAccess.class.getDeclaredField("pinnedTrees");
                field.setAccessible(true);
                synchronized (SafStorageAccess.class) {
                    Map<Uri, Integer> pins = (Map<Uri, Integer>) field.get(null);
                    pins.remove(first);
                    pins.remove(second);
                }
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            target.deleteSharedPreferences(prefsName);
        }
    }

    private static final class RecordingResolver extends ContentResolver {
        final Map<Uri, Integer> grants = new ConcurrentHashMap<>();
        final List<Uri> released = Collections.synchronizedList(new ArrayList<>());
        volatile Runnable onTake;
        volatile Runnable onRelease;
        volatile Runnable onPermissions;
        boolean denyRead;
        boolean denyWrite;
        boolean rejectUnheldReleaseFlags;
        boolean denyRelease;

        RecordingResolver(Context context) { super(context); }

        @Override public void takePersistableUriPermission(Uri uri, int flags) {
            if (denyRead || (denyWrite && (flags & WRITE) != 0)) throw new SecurityException();
            grants.put(uri, flags);
            Runnable action = onTake;
            if (action != null) action.run();
        }

        @Override public void releasePersistableUriPermission(Uri uri, int flags) {
            if (denyRelease) throw new SecurityException("fixture");
            int held = grants.getOrDefault(uri, 0);
            if (rejectUnheldReleaseFlags && (flags & held) != flags) throw new SecurityException();
            Runnable action = onRelease;
            if (action != null) action.run();
            released.add(uri);
            int remaining = held & ~flags;
            if (remaining == 0) grants.remove(uri);
            else grants.put(uri, remaining);
        }

        @Override public List<UriPermission> getPersistedUriPermissions() {
            Runnable action = onPermissions;
            if (action != null) {
                action.run();
                return Collections.emptyList();
            }
            List<UriPermission> permissions = new ArrayList<>();
            for (Map.Entry<Uri, Integer> grant : grants.entrySet()) {
                Parcel parcel = Parcel.obtain();
                try {
                    parcel.writeParcelable(grant.getKey(), 0);
                    parcel.writeInt(grant.getValue());
                    parcel.writeLong(1L);
                    parcel.setDataPosition(0);
                    permissions.add(UriPermission.CREATOR.createFromParcel(parcel));
                } finally { parcel.recycle(); }
            }
            return permissions;
        }
    }
}
