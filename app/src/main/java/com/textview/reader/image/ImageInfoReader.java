package com.textview.reader.image;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.R;
import com.textview.reader.util.FileSortUtils;
import com.textview.reader.util.FileUtils;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Reads and formats image metadata outside the activity class. */
public final class ImageInfoReader {
    private ImageInfoReader() {}

    @NonNull
    public static ImageInfo read(@NonNull Context context,
                                 @Nullable String filePath,
                                 @Nullable String fileUri,
                                 @Nullable String displayName,
                                 @NonNull String sourceLabel,
                                 @Nullable String pathOrUriText,
                                 boolean allowFileOps,
                                 @Nullable Bitmap currentBitmap) {
        ImageInfo info = new ImageInfo();
        info.name = displayName;
        info.source = sourceLabel;
        info.pathOrUri = pathOrUriText;
        info.type = FileUtils.getReadableFileType(info.name);
        info.extension = extensionLabel(context, info.name);

        if (filePath != null && filePath.trim().length() > 0) {
            File file = new File(filePath);
            if (file.exists()) {
                info.size = exactFileSize(context, file.length());
                info.modified = formatDate(file.lastModified());
                long downloaded = FileSortUtils.fileDownloadedTimeMillis(context, file);
                long created = downloaded > 0L ? downloaded : FileSortUtils.fileCreationTimeMillis(file);
                if (created > 0L) info.created = formatDate(created);
                info.readable = String.valueOf(file.canRead());
                info.writable = String.valueOf(allowFileOps && file.canWrite());
            }
        } else if (fileUri != null && fileUri.trim().length() > 0) {
            Uri uri = Uri.parse(fileUri);
            long size = uriSize(context, uri);
            if (size >= 0) info.size = exactFileSize(context, size);
        }

        BitmapFactory.Options opts = readImageBounds(context, filePath, fileUri);
        if (opts != null) {
            if (!TextUtils.isEmpty(opts.outMimeType)) info.mime = opts.outMimeType;
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                applyDimensions(context, info, opts.outWidth, opts.outHeight);
            }
        }
        if (TextUtils.isEmpty(info.mime)) info.mime = mimeTypeForName(info.name);
        if (TextUtils.isEmpty(info.dimensions) && currentBitmap != null && !currentBitmap.isRecycled()) {
            applyDimensions(context, info, currentBitmap.getWidth(), currentBitmap.getHeight());
        }

        applyExifInfo(context, info, filePath, fileUri);
        return info;
    }

    @NonNull
    public static String unavailable(@NonNull Context context) {
        return context.getString(R.string.image_info_unavailable);
    }

    @NonNull
    public static String mimeTypeForName(@Nullable String name) {
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mime != null && mime.trim().length() > 0) return mime;
            }
        }
        return "image/*";
    }

    @NonNull
    private static String exactFileSize(@NonNull Context context, long size) {
        if (size < 0) return unavailable(context);
        return FileUtils.formatFileSize(size) + " (" + String.format(Locale.getDefault(), "%,d", size) + " bytes)";
    }

    @Nullable
    private static BitmapFactory.Options readImageBounds(@NonNull Context context,
                                                         @Nullable String filePath,
                                                         @Nullable String fileUri) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try {
            if (filePath != null && filePath.trim().length() > 0) {
                BitmapFactory.decodeFile(filePath, opts);
            } else if (fileUri != null && fileUri.trim().length() > 0) {
                try (InputStream is = context.getContentResolver().openInputStream(Uri.parse(fileUri))) {
                    BitmapFactory.decodeStream(is, null, opts);
                }
            }
            return opts;
        } catch (Exception ignored) {
            return null;
        }
    }

    @NonNull
    private static String extensionLabel(@NonNull Context context, @Nullable String name) {
        if (name == null) return unavailable(context);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) return unavailable(context);
        return name.substring(dot + 1).toUpperCase(Locale.ROOT);
    }

    private static void applyDimensions(@NonNull Context context, @NonNull ImageInfo info, int width, int height) {
        info.dimensions = String.format(Locale.getDefault(), "%d × %d px", width, height);
        info.aspectRatio = aspectRatio(context, width, height);
        info.megapixels = String.format(Locale.getDefault(), "%.2f MP", (width * (double) height) / 1_000_000.0);
    }

    @NonNull
    private static String aspectRatio(@NonNull Context context, int width, int height) {
        if (width <= 0 || height <= 0) return unavailable(context);
        int gcd = gcd(width, height);
        String reduced = (width / gcd) + ":" + (height / gcd);
        double decimal = width / (double) height;
        return String.format(Locale.getDefault(), "%s (%.3f)", reduced, decimal);
    }

    private static int gcd(int a, int b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return Math.max(1, a);
    }

    private static long uriSize(@NonNull Context context, @NonNull Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        } catch (Exception ignored) {}
        try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (afd != null) return afd.getLength();
        } catch (Exception ignored) {}
        return -1L;
    }

    private static void applyExifInfo(@NonNull Context context,
                                      @NonNull ImageInfo info,
                                      @Nullable String filePath,
                                      @Nullable String fileUri) {
        ExifInterface exif = null;
        try {
            if (filePath != null && filePath.trim().length() > 0) {
                exif = new ExifInterface(filePath);
            } else if (fileUri != null && fileUri.trim().length() > 0) {
                try (InputStream is = context.getContentResolver().openInputStream(Uri.parse(fileUri))) {
                    if (is != null) exif = new ExifInterface(is);
                }
            }
        } catch (Exception ignored) {
            exif = null;
        }
        if (exif == null) return;

        String make = cleanExif(exif.getAttribute(ExifInterface.TAG_MAKE));
        String model = cleanExif(exif.getAttribute(ExifInterface.TAG_MODEL));
        if (!TextUtils.isEmpty(make) || !TextUtils.isEmpty(model)) {
            info.camera = (TextUtils.isEmpty(make) ? "" : make)
                    + (!TextUtils.isEmpty(make) && !TextUtils.isEmpty(model) ? " " : "")
                    + (TextUtils.isEmpty(model) ? "" : model);
        }
        String date = formatExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
        if (TextUtils.isEmpty(date)) date = formatExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME));
        info.taken = date;
        info.software = cleanExif(exif.getAttribute(ExifInterface.TAG_SOFTWARE));
    }

    @Nullable
    private static String formatExifDate(@Nullable String value) {
        String cleaned = cleanExif(value);
        if (TextUtils.isEmpty(cleaned)) return null;
        try {
            SimpleDateFormat parser = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
            parser.setLenient(false);
            Date parsed = parser.parse(cleaned);
            if (parsed != null && parsed.getTime() > 0L) return formatDate(parsed.getTime());
        } catch (Exception ignored) {}
        if (cleaned.length() >= 10 && cleaned.charAt(4) == ':' && cleaned.charAt(7) == ':') {
            return cleaned.substring(0, 4) + "-" + cleaned.substring(5, 7)
                    + "-" + cleaned.substring(8);
        }
        return cleaned;
    }

    @Nullable
    private static String cleanExif(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @NonNull
    private static String formatDate(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(millis));
    }
}
