package com.readwide.manager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.archive.ArchiveSupport;
import com.readwide.manager.util.FileSortUtils;
import com.readwide.manager.util.FileUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure archive-entry list shaping used by ArchiveBrowserActivity.
 *
 * Keeps folder tree expansion, search/filter matching, sorting, and image-sequence
 * collection out of the Activity so UI code is not mixed with archive list policy.
 */
final class ArchiveEntryListController {
    static final int FILTER_ALL = 0;
    static final int FILTER_GENERAL = 1;
    static final int FILTER_TXT = 2;
    static final int FILTER_ARCHIVE = 3;
    static final int FILTER_PDF = 4;
    static final int FILTER_EPUB = 5;
    static final int FILTER_WORD = 6;
    static final int FILTER_IMAGE = 7;

    private ArchiveEntryListController() {
    }

    /** UI-thread cache for one folder of an immutable, replace-on-reload listing. */
    static final class FolderView {
        private List<ArchiveSupport.EntryInfo> source;
        private String prefix;
        private int sortMode;
        private List<ArchiveSupport.EntryInfo> sortedChildren;

        @NonNull
        List<ArchiveSupport.EntryInfo> visibleChildren(@NonNull List<ArchiveSupport.EntryInfo> allEntries,
                @NonNull String currentPrefix, @Nullable String query, int filter, int currentSortMode) {
            if (source != allEntries || !currentPrefix.equals(prefix) || sortMode != currentSortMode) {
                // Rebuild from source order on sort changes too: stable ties must
                // not inherit the order produced by the previous sort mode.
                List<ArchiveSupport.EntryInfo> children = collectDirectChildren(allEntries, currentPrefix);
                sort(children, currentSortMode);
                sortedChildren = children;
                source = allEntries;
                prefix = currentPrefix;
                sortMode = currentSortMode;
            }
            // Return a new list; adapters/callers cannot mutate the cached order.
            return filterPresorted(sortedChildren, query, filter);
        }
    }

    @NonNull
    static List<ArchiveSupport.EntryInfo> directChildren(@NonNull List<ArchiveSupport.EntryInfo> allEntries,
                                                         @NonNull String prefix,
                                                         int sortMode) {
        List<ArchiveSupport.EntryInfo> result = collectDirectChildren(allEntries, prefix);
        sort(result, sortMode);
        return result;
    }

    @NonNull
    static List<ArchiveSupport.EntryInfo> visibleChildren(@NonNull List<ArchiveSupport.EntryInfo> allEntries,
                                                          @NonNull String prefix,
                                                          @Nullable String queryText,
                                                          int activeFilter,
                                                          int sortMode) {
        // Filtering preserves relative order, so only the survivors need sorting.
        return filter(collectDirectChildren(allEntries, prefix), queryText, activeFilter, sortMode);
    }

    @NonNull
    private static List<ArchiveSupport.EntryInfo> collectDirectChildren(
            @NonNull List<ArchiveSupport.EntryInfo> allEntries, @NonNull String prefix) {
        Map<String, ArchiveSupport.EntryInfo> children = new LinkedHashMap<>();
        for (ArchiveSupport.EntryInfo entry : allEntries) {
            if (entry == null) continue;
            String path = entry.path;
            if (path == null || !path.startsWith(prefix)) continue;
            if (path.length() == prefix.length()) continue;
            int slash = path.indexOf('/', prefix.length());
            if (slash >= 0) {
                String childPath = path.substring(0, slash + 1);
                if (!children.containsKey(childPath)) {
                    children.put(childPath, new ArchiveSupport.EntryInfo(childPath, true, -1L, 0L));
                }
            } else {
                children.put(path, entry);
            }
        }
        return new ArrayList<>(children.values());
    }

    @NonNull
    static List<ArchiveSupport.EntryInfo> filter(@NonNull List<ArchiveSupport.EntryInfo> source,
                                                 @Nullable String queryText,
                                                 int activeFilter,
                                                 int sortMode) {
        List<ArchiveSupport.EntryInfo> result = filterPresorted(source, queryText, activeFilter);
        sort(result, sortMode);
        return result;
    }

    /** Filtering preserves source order, so an already sorted view needs no re-sort. */
    @NonNull
    private static List<ArchiveSupport.EntryInfo> filterPresorted(
            @NonNull List<ArchiveSupport.EntryInfo> source, @Nullable String queryText, int activeFilter) {
        String query = queryText == null ? "" : queryText.toLowerCase(Locale.ROOT);
        if (query.length() == 0 && activeFilter == FILTER_ALL) {
            return new ArrayList<>(source);
        }
        List<ArchiveSupport.EntryInfo> result = new ArrayList<>();
        for (ArchiveSupport.EntryInfo entry : source) {
            if (entry == null) continue;
            String name = entry.name();
            boolean nameMatch = query.length() == 0
                    || name.toLowerCase(Locale.ROOT).contains(query);
            if (!nameMatch) continue;
            if (entry.directory || matchesFilter(name, activeFilter)) {
                result.add(entry);
            }
        }
        return result;
    }

    static boolean matchesFilter(@NonNull String name, int filter) {
        switch (filter) {
            case FILTER_GENERAL:
                return FileUtils.isGeneralTextFile(name);
            case FILTER_TXT:
                return FileUtils.isTxtFile(name);
            case FILTER_ARCHIVE:
                return ArchiveSupport.isSupportedArchiveFileName(name);
            case FILTER_PDF:
                return FileUtils.isPdfFile(name);
            case FILTER_EPUB:
                return FileUtils.isEpubFile(name);
            case FILTER_WORD:
                return FileUtils.isWordOrHwpFile(name);
            case FILTER_IMAGE:
                return FileUtils.isImageFile(name);
            case FILTER_ALL:
            default:
                return true;
        }
    }

    @NonNull
    static List<ArchiveSupport.EntryInfo> collectImageSequence(@NonNull List<ArchiveSupport.EntryInfo> allEntries,
                                                               @NonNull String currentPrefix,
                                                               @Nullable ArchiveSupport.EntryInfo selectedEntry,
                                                               int sortMode) {
        String prefix = currentPrefix;
        if (selectedEntry != null && !selectedEntry.path.startsWith(prefix)) {
            prefix = parentPrefixOf(selectedEntry.path);
        }
        List<ArchiveSupport.EntryInfo> result = new ArrayList<>();
        for (ArchiveSupport.EntryInfo entry : allEntries) {
            // Most entries can be outside a selected chapter. Check the cheap
            // scope first, before classifying extensions and metadata components.
            if (entry == null || !entry.path.startsWith(prefix)) continue;
            if (!isArchiveImageSequenceEntry(entry)) continue;
            result.add(entry);
        }
        if (result.isEmpty() && isArchiveImageSequenceEntry(selectedEntry)) result.add(selectedEntry);
        sortImageSequence(result);
        return result;
    }

    @NonNull
    static List<ArchiveSupport.EntryInfo> collectPreviewOrderedImageSequence(
            @NonNull List<ArchiveSupport.EntryInfo> visibleEntries,
            @Nullable ArchiveSupport.EntryInfo selectedEntry) {
        List<ArchiveSupport.EntryInfo> result = new ArrayList<>();
        boolean selectedIncluded = false;
        for (ArchiveSupport.EntryInfo entry : visibleEntries) {
            if (!isArchiveImageSequenceEntry(entry)) continue;
            result.add(entry);
            if (selectedEntry != null && selectedEntry.path.equals(entry.path)) {
                selectedIncluded = true;
            }
        }
        if (!selectedIncluded && isArchiveImageSequenceEntry(selectedEntry)) result.add(selectedEntry);
        return result;
    }

    static boolean isArchiveImageSequenceEntry(@Nullable ArchiveSupport.EntryInfo entry) {
        if (entry == null || entry.directory || !FileUtils.isImageFile(entry.name())) return false;
        String path = entry.path == null ? "" : entry.path.replace('\\', '/');
        if (path.length() == 0) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        // Inspect components by offset: no split array, per-component substring
        // or repeated lowercase copy while classifying large image lists.
        for (int start = 0; start < lower.length();) {
            int slash = lower.indexOf('/', start);
            int end = slash < 0 ? lower.length() : slash;
            int length = end - start;
            if ((length == 8 && lower.startsWith("__macosx", start))
                    || (length == 9 && lower.startsWith(".ds_store", start))
                    || (length >= 2 && lower.startsWith("._", start))) return false;
            if (slash < 0) break;
            start = slash + 1;
        }
        return true;
    }

    static void sortImageSequence(@NonNull List<ArchiveSupport.EntryInfo> target) {
        FileSortUtils.sortArchiveImageSequence(target);
    }

    /** Small intent recipe restores browser scope/order without a large Binder list. */
    static List<ArchiveSupport.EntryInfo> restoreImageSequence(
            List<ArchiveSupport.EntryInfo> entries, String prefix, String query,
            boolean previewOrder, int sortMode, String selectedPath) {
        ArchiveSupport.EntryInfo selected = null;
        for (ArchiveSupport.EntryInfo entry : entries) {
            if (entry != null && entry.path.equals(selectedPath)) { selected = entry; break; }
        }
        String safePrefix = prefix != null ? prefix : "";
        if (previewOrder) {
            return collectPreviewOrderedImageSequence(visibleChildren(entries, safePrefix,
                    query, FILTER_ALL, sortMode), selected);
        }
        return collectImageSequence(entries, safePrefix, selected, sortMode);
    }

    @NonNull
    static String parentPrefixOf(@NonNull String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(0, slash + 1) : "";
    }

    static void sort(@NonNull List<ArchiveSupport.EntryInfo> target, int sortMode) {
        FileSortUtils.sortArchiveEntries(target, sortMode);
    }
}
