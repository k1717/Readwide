package com.textview.reader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.archive.ArchiveSupport;
import com.textview.reader.util.FileSortUtils;
import com.textview.reader.util.FileUtils;

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

    @NonNull
    static List<ArchiveSupport.EntryInfo> directChildren(@NonNull List<ArchiveSupport.EntryInfo> allEntries,
                                                         @NonNull String prefix,
                                                         int sortMode) {
        Map<String, ArchiveSupport.EntryInfo> children = new LinkedHashMap<>();
        for (ArchiveSupport.EntryInfo entry : allEntries) {
            if (entry == null) continue;
            String path = entry.path;
            if (path == null || !path.startsWith(prefix)) continue;
            String rest = path.substring(prefix.length());
            if (rest.length() == 0) continue;
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                String dirName = rest.substring(0, slash + 1);
                String childPath = prefix + dirName;
                if (!children.containsKey(childPath)) {
                    children.put(childPath, new ArchiveSupport.EntryInfo(childPath, true, -1L, 0L));
                }
            } else {
                children.put(path, entry);
            }
        }
        List<ArchiveSupport.EntryInfo> result = new ArrayList<>(children.values());
        sort(result, sortMode);
        return result;
    }

    @NonNull
    static List<ArchiveSupport.EntryInfo> filter(@NonNull List<ArchiveSupport.EntryInfo> source,
                                                 @Nullable String queryText,
                                                 int activeFilter,
                                                 int sortMode) {
        String query = queryText == null ? "" : queryText.toLowerCase(Locale.ROOT);
        if (query.length() == 0 && activeFilter == FILTER_ALL) {
            List<ArchiveSupport.EntryInfo> result = new ArrayList<>(source);
            sort(result, sortMode);
            return result;
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
        sort(result, sortMode);
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
                return FileUtils.isWordFile(name);
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
            if (!isArchiveImageSequenceEntry(entry)) continue;
            if (entry.path.startsWith(prefix)) result.add(entry);
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
        if (lower.startsWith("__macosx/") || lower.contains("/__macosx/")) return false;
        String[] parts = path.split("/");
        for (String part : parts) {
            if (part == null || part.length() == 0) continue;
            String lowerPart = part.toLowerCase(Locale.ROOT);
            if (lowerPart.equals(".ds_store") || lowerPart.startsWith("._")) return false;
        }
        return true;
    }

    static void sortImageSequence(@NonNull List<ArchiveSupport.EntryInfo> target) {
        FileSortUtils.sortArchiveImageSequence(target);
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
