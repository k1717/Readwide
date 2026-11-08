package com.readwide.manager.util;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A confirmed tree inventory; deletion never expands it with a fresh recursive walk. */
public final class FileDeletionPlan {
    public enum Result { DELETED, CHANGED, FAILED, CANCELLED }

    private final List<Node> nodes = new ArrayList<>();
    private final List<Node> removalOrder = new ArrayList<>();
    private long bytes;

    private FileDeletionPlan() { }

    public static FileDeletionPlan captureSingleFile(File file) throws IOException {
        FileDeletionPlan plan = new FileDeletionPlan();
        FileTreeWalk.walk(file, () -> !Thread.currentThread().isInterrupted(), entry -> {
            if (entry.kind != FileTreeWalk.Kind.FILE || entry.identity == null) {
                throw new IOException("Cannot identify regular deletion target: " + file);
            }
            Node node = new Node(entry);
            plan.nodes.add(node);
            plan.removalOrder.add(node);
            plan.bytes = entry.bytes;
            return false;
        });
        if (plan.nodes.isEmpty()) throw new IOException("Deletion capture interrupted");
        plan.nodes.get(0).verifyUnchanged();
        return plan;
    }

    public static FileDeletionPlan capture(File root) throws IOException {
        FileDeletionPlan plan = new FileDeletionPlan();
        java.util.Map<String, Node> directories = new java.util.HashMap<>();
        boolean complete = FileTreeWalk.walk(root, () -> !Thread.currentThread().isInterrupted(),
                new FileTreeWalk.Visitor() {
                    @Override public boolean enter(FileTreeWalk.Entry entry) throws IOException {
                        if (entry.kind == FileTreeWalk.Kind.MISSING || entry.kind == FileTreeWalk.Kind.OTHER
                                || entry.identity == null) {
                            throw new IOException("Cannot identify deletion target: " + entry.file);
                        }
                        Node node = new Node(entry);
                        plan.nodes.add(node);
                        plan.bytes = entry.bytes > Long.MAX_VALUE - plan.bytes
                                ? Long.MAX_VALUE : plan.bytes + entry.bytes;
                        if (entry.kind == FileTreeWalk.Kind.DIRECTORY) directories.put(entry.relative, node);
                        else plan.removalOrder.add(node);
                        return true;
                    }

                    @Override public boolean leave(FileTreeWalk.Entry directory) {
                        plan.removalOrder.add(directories.remove(directory.relative));
                        return true;
                    }
                });
        if (!complete || plan.nodes.isEmpty()) throw new IOException("Deletion inventory interrupted");
        for (Node node : plan.nodes) node.verifyUnchanged();
        return plan;
    }

    public long totalBytes() { return bytes; }

    public boolean isDirectory() {
        return !nodes.isEmpty() && nodes.get(0).entry.kind == FileTreeWalk.Kind.DIRECTORY;
    }

    public Result delete(FileOperationProgress progress, FileTreeProgressTracker tracker) {
        try {
            for (Node node : nodes) {
                if (!checkpoint(progress)) return Result.CANCELLED;
                node.verifyUnchanged();
            }
        } catch (IOException changed) {
            return Result.CHANGED;
        } catch (SecurityException denied) {
            return Result.FAILED;
        }
        try {
            for (Node node : removalOrder) {
                if (!checkpoint(progress)) return Result.CANCELLED;
                if (tracker != null) {
                    if (node.entry.kind == FileTreeWalk.Kind.DIRECTORY) tracker.onDirectory(node.entry.file);
                    else tracker.onFile(node.entry.file);
                } else if (progress != null) {
                    progress.setDetail(node.entry.file.getName());
                }
                if (!checkpoint(progress)) return Result.CANCELLED;
                node.entry.recheck();
                if (node.regularFile != null) node.regularFile.recheck();
                // File.delete only removes an empty directory. New children stay untouched.
                if (!node.entry.file.delete()) return Result.FAILED;
                if (progress != null) {
                    try {
                        progress.addDoneBytes(node.entry.bytes);
                    } catch (RuntimeException reportingFailure) {
                        com.readwide.manager.archive.ArchiveCancellation.restoreIfCancelled(reportingFailure, progress);
                        if (node != removalOrder.get(removalOrder.size() - 1)) {
                            return progress.isCancelled() ? Result.CANCELLED : Result.FAILED;
                        }
                    }
                }
            }
            return Result.DELETED;
        } catch (IOException changed) {
            return Result.CHANGED;
        } catch (SecurityException denied) {
            return Result.FAILED;
        }
    }

    private static boolean checkpoint(FileOperationProgress progress) {
        return (progress == null || progress.checkpoint()) && !Thread.currentThread().isInterrupted();
    }

    private static final class Node {
        final FileTreeWalk.Entry entry;
        final FileMutationGuard regularFile;
        final String[] children;

        Node(FileTreeWalk.Entry entry) throws IOException {
            this.entry = entry;
            regularFile = entry.kind == FileTreeWalk.Kind.FILE ? FileMutationGuard.capture(entry.file) : null;
            children = entry.kind == FileTreeWalk.Kind.DIRECTORY ? childNames(entry.file) : null;
            entry.recheck();
        }

        void verifyUnchanged() throws IOException {
            entry.recheck();
            if (regularFile != null) regularFile.recheck();
            if (children != null && !Arrays.equals(children, childNames(entry.file))) {
                throw new IOException("Directory contents changed: " + entry.file);
            }
        }

        private static String[] childNames(File directory) throws IOException {
            String[] names = directory.list();
            if (names == null) throw new IOException("Cannot list deletion target: " + directory);
            Arrays.sort(names);
            return names;
        }
    }
}
