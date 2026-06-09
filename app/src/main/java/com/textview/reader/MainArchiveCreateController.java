package com.textview.reader;

import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.archive.ArchiveSupport;
import com.textview.reader.util.FileOperationProgress;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class MainArchiveCreateController {
    private final MainActivity activity;

    MainArchiveCreateController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    void startArchiveCreation(@NonNull File source) {
        ArrayList<File> sources = new ArrayList<>();
        sources.add(source);
        startArchiveCreation(sources);
    }

    void startArchiveCreation(@NonNull List<File> sources) {
        ArrayList<File> ready = MainArchiveCreationPlanner.collectReadySources(sources);
        if (ready.isEmpty()) {
            ShortToast.show(activity, R.string.archive_create_failed);
            return;
        }
        File sourceParent = ready.get(0).getParentFile();
        if (sourceParent == null || !sourceParent.exists() || !sourceParent.isDirectory()) {
            ShortToast.show(activity, R.string.archive_create_failed);
            return;
        }
        File placeholderDestination = MainArchiveCreationPlanner.buildArchiveDestination(
                sourceParent,
                ready,
                activity.getString(R.string.archive_create_default_name));
        if (placeholderDestination == null) {
            ShortToast.show(activity, R.string.archive_create_failed);
            return;
        }
        addPendingArchiveCreation(new MainPendingArchiveCreation(ready, placeholderDestination, sourceParent));
        activity.archiveCreateInProgress = false;
        activity.updateMainOverflowButtonVisibility();
        Toast.makeText(activity, R.string.archive_create_queued, Toast.LENGTH_LONG).show();

        if (sourceParent.exists() && sourceParent.isDirectory() && sourceParent.canRead()) {
            activity.resetMainBrowseFiltersAndShow(sourceParent, null);
        }
    }

    boolean setActivePendingArchiveCreation(@Nullable MainPendingArchiveCreation task) {
        if (task == null) return false;
        for (MainPendingArchiveCreation pending : activity.pendingArchiveCreations) {
            if (pending == task) {
                activity.pendingArchiveCreation = pending;
                return true;
            }
        }
        return false;
    }

    void cancelPendingArchiveCreation(@Nullable MainPendingArchiveCreation task) {
        removePendingArchiveCreation(task);
        activity.archiveCreateInProgress = false;
        activity.updateMainOverflowButtonVisibility();
        ShortToast.show(activity, R.string.file_operation_cancelled);
    }

    void confirmPendingArchiveCreation() {
        MainPendingArchiveCreation task = activity.pendingArchiveCreation;
        if (task == null) return;
        ArrayList<File> ready = MainArchiveCreationPlanner.collectReadySources(task.sources);
        if (ready.isEmpty()) {
            removePendingArchiveCreation(task);
            activity.updateMainOverflowButtonVisibility();
            ShortToast.show(activity, R.string.archive_create_failed);
            return;
        }
        File destinationDir = activity.currentDirectory;
        if (!MainArchiveCreationPlanner.isWritableParent(destinationDir)) {
            ShortToast.show(activity, R.string.file_move_destination_unavailable);
            return;
        }
        File preferredDestination = new File(destinationDir, task.destination.getName());
        File destination = MainArchiveCreationPlanner.freshDestinationFor(
                destinationDir,
                ready,
                preferredDestination,
                activity.getString(R.string.archive_create_default_name));
        if (destination == null) {
            ShortToast.show(activity, R.string.archive_create_failed);
            return;
        }
        activity.showSimpleConfirmDialog(
                activity.getString(R.string.archive_create_title),
                activity.getString(R.string.archive_create_message,
                        ready.size(),
                        destination.getName(),
                        destinationDir.getName().length() > 0 ? destinationDir.getName() : destinationDir.getAbsolutePath()),
                activity.getString(R.string.archive_create),
                () -> createArchive(task, ready, destination, destinationDir));
    }

    void confirmAllPendingArchiveCreations() {
        if (activity.archiveCreateInProgress) return;
        ArrayList<MainPendingArchiveCreation> tasks = new ArrayList<>(activity.pendingArchiveCreations);
        if (tasks.isEmpty()) return;
        File destinationRoot = activity.currentDirectory;
        if (!MainArchiveCreationPlanner.isWritableParent(destinationRoot)) {
            ShortToast.show(activity, R.string.file_move_destination_unavailable);
            return;
        }
        activity.showSimpleConfirmDialog(
                activity.getString(R.string.archive_create_all_title),
                activity.getString(R.string.archive_create_all_message,
                        tasks.size(),
                        destinationRoot.getName().length() > 0 ? destinationRoot.getName() : destinationRoot.getAbsolutePath()),
                activity.getString(R.string.archive_create_all),
                () -> continueAllArchiveCreations(tasks, destinationRoot));
    }

    private void addPendingArchiveCreation(@NonNull MainPendingArchiveCreation task) {
        removePendingArchiveCreationWithSameDestination(task.destination);
        activity.pendingArchiveCreations.add(0, task);
        activity.pendingArchiveCreation = task;
    }

    private void removePendingArchiveCreation(@Nullable MainPendingArchiveCreation task) {
        if (task == null) return;
        for (int i = activity.pendingArchiveCreations.size() - 1; i >= 0; i--) {
            if (activity.pendingArchiveCreations.get(i) == task) activity.pendingArchiveCreations.remove(i);
        }
        if (activity.pendingArchiveCreation == task) {
            activity.pendingArchiveCreation = activity.pendingArchiveCreations.isEmpty()
                    ? null
                    : activity.pendingArchiveCreations.get(0);
        }
    }

    private void removePendingArchiveCreationWithSameDestination(@NonNull File destination) {
        String path = destination.getAbsolutePath();
        for (int i = activity.pendingArchiveCreations.size() - 1; i >= 0; i--) {
            MainPendingArchiveCreation pending = activity.pendingArchiveCreations.get(i);
            if (pending.destination.getAbsolutePath().equals(path)) activity.pendingArchiveCreations.remove(i);
        }
        if (activity.pendingArchiveCreation != null
                && activity.pendingArchiveCreation.destination.getAbsolutePath().equals(path)) {
            activity.pendingArchiveCreation = activity.pendingArchiveCreations.isEmpty()
                    ? null
                    : activity.pendingArchiveCreations.get(0);
        }
    }

    private void createArchive(@NonNull MainPendingArchiveCreation task,
                               @NonNull ArrayList<File> sources,
                               @NonNull File destination,
                               @NonNull File destinationDir) {
        if (activity.archiveCreateInProgress) return;
        activity.archiveCreateInProgress = true;
        activity.updateMainOverflowButtonVisibility();
        FileOperationProgress progress = activity.showFileOperationProgress(
                activity.getString(R.string.archive_creating),
                destination.getName());
        progress.setFolder(destinationDir.getName().length() > 0 ? destinationDir.getName() : destinationDir.getAbsolutePath());
        activity.executeFolderBackgroundTask(() -> {
            boolean done = ArchiveSupport.createZipArchive(sources, destination, progress);
            activity.fileSearchHandler.post(() -> {
                activity.finishFileOperationProgress(progress);
                activity.archiveCreateInProgress = false;
                if (activity.activityDestroyed) return;
                if (progress.isCancelled()) {
                    ShortToast.show(activity, R.string.file_operation_cancelled);
                    activity.updateMainOverflowButtonVisibility();
                    return;
                }
                if (!done) {
                    ShortToast.show(activity, R.string.archive_create_failed);
                    activity.updateMainOverflowButtonVisibility();
                    return;
                }
                removePendingArchiveCreation(task);
                if (activity.prefs != null) activity.prefs.addRecentFolder(destinationDir.getAbsolutePath());
                activity.resetMainBrowseFiltersAndShow(destinationDir, destination.getAbsolutePath());
                activity.rebuildDrawerStorageEntries();
                activity.updateMainOverflowButtonVisibility();
                ShortToast.show(activity, R.string.archive_created);
            });
        });
    }

    private void continueAllArchiveCreations(@NonNull ArrayList<MainPendingArchiveCreation> tasks,
                                             @NonNull File destinationRoot) {
        if (activity.archiveCreateInProgress) return;
        activity.archiveCreateInProgress = true;
        activity.updateMainOverflowButtonVisibility();
        FileOperationProgress progress = activity.showFileOperationProgress(
                activity.getString(R.string.archive_creating),
                activity.getString(R.string.archive_create_all));
        progress.setFolder(destinationRoot.getName().length() > 0 ? destinationRoot.getName() : destinationRoot.getAbsolutePath());
        activity.executeFolderBackgroundTask(() -> {
            int doneCount = 0;
            File lastParent = null;
            File lastDestination = null;
            ArrayList<MainPendingArchiveCreation> succeededTasks = new ArrayList<>();
            for (MainPendingArchiveCreation task : tasks) {
                if (progress.isCancelled()) break;
                ArrayList<File> ready = MainArchiveCreationPlanner.collectReadySources(task.sources);
                if (ready.isEmpty()) continue;
                File preferredDestination = new File(destinationRoot, task.destination.getName());
                File destination = MainArchiveCreationPlanner.freshDestinationFor(destinationRoot, ready, preferredDestination, activity.getString(R.string.archive_create_default_name));
                if (destination == null) continue;
                progress.setDetail(destination.getName());
                boolean done = ArchiveSupport.createZipArchive(ready, destination, progress);
                if (done) {
                    doneCount++;
                    succeededTasks.add(task);
                    lastParent = destinationRoot;
                    lastDestination = destination;
                }
            }
            final int finalDoneCount = doneCount;
            final File finalLastParent = lastParent;
            final File finalLastDestination = lastDestination;
            final ArrayList<MainPendingArchiveCreation> finalSucceededTasks = succeededTasks;
            activity.fileSearchHandler.post(() -> {
                activity.finishFileOperationProgress(progress);
                activity.archiveCreateInProgress = false;
                if (activity.activityDestroyed) return;
                for (MainPendingArchiveCreation task : finalSucceededTasks) removePendingArchiveCreation(task);
                if (progress.isCancelled()) {
                    ShortToast.show(activity, R.string.file_operation_cancelled);
                } else if (finalDoneCount == tasks.size()) {
                    ShortToast.show(activity, activity.getString(R.string.archive_create_all_done, finalDoneCount, tasks.size()));
                } else if (finalDoneCount > 0) {
                    ShortToast.show(activity, activity.getString(R.string.archive_create_all_partial, finalDoneCount, tasks.size()));
                } else {
                    ShortToast.show(activity, R.string.archive_create_failed);
                }
                if (finalLastParent != null && finalLastDestination != null) {
                    if (activity.prefs != null) activity.prefs.addRecentFolder(finalLastParent.getAbsolutePath());
                    activity.resetMainBrowseFiltersAndShow(finalLastParent, finalLastDestination.getAbsolutePath());
                    activity.rebuildDrawerStorageEntries();
                } else {
                    activity.updateMainOverflowButtonVisibility();
                }
            });
        });
    }


}
