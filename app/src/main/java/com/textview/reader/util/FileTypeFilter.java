package com.textview.reader.util;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.textview.reader.R;
import com.textview.reader.archive.ArchiveSupport;

/** Central definition of the main quick file-type filters. */
public final class FileTypeFilter {
    public static final int ALL = 0;
    public static final int GENERAL = 1;
    public static final int TXT = 2;
    public static final int ARCHIVE = 3;
    public static final int PDF = 4;
    public static final int EPUB = 5;
    public static final int WORD = 6;
    public static final int IMAGE = 7;

    private FileTypeFilter() {}

    public static boolean matches(@NonNull String name, int filter) {
        switch (filter) {
            case GENERAL:
                return FileUtils.isGeneralTextFile(name);
            case TXT:
                return FileUtils.isTxtFile(name);
            case ARCHIVE:
                return ArchiveSupport.isSupportedArchiveFileName(name);
            case PDF:
                return FileUtils.isPdfFile(name);
            case EPUB:
                return FileUtils.isEpubFile(name);
            case WORD:
                return FileUtils.isWordFile(name);
            case IMAGE:
                return FileUtils.isImageFile(name);
            case ALL:
            default:
                return FileUtils.isVisibleInAllFilesFilter(name);
        }
    }

    @StringRes
    public static int labelResId(int filter) {
        switch (filter) {
            case GENERAL: return R.string.file_filter_general;
            case TXT: return R.string.file_filter_txt;
            case ARCHIVE: return R.string.file_filter_archive;
            case PDF: return R.string.file_filter_pdf;
            case EPUB: return R.string.file_filter_epub;
            case WORD: return R.string.file_filter_word;
            case IMAGE: return R.string.file_filter_img;
            case ALL:
            default: return R.string.file_filter_all;
        }
    }
}
