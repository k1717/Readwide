package com.textview.reader.image;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;

/** Heavy image decoding code kept outside ImageReaderActivity. */
public final class ImageDecodeHelper {
    private static final float TALL_IMAGE_ASPECT_RATIO = 2.5f;
    private static final int PREVIEW_DISPLAY_SCALE = 2;
    private static final int DETAIL_DISPLAY_SCALE = 4;
    private static final long PREVIEW_DIRECT_ORIGINAL_PIXELS = 12_000_000L;
    private static final long PREVIEW_MAX_BITMAP_PIXELS = 12_000_000L;
    private static final long DETAIL_DIRECT_ORIGINAL_PIXELS = 48_000_000L;
    private static final long DETAIL_MAX_BITMAP_PIXELS = 48_000_000L;

    private ImageDecodeHelper() {}

    @Nullable
    public static LoadedImage decode(@NonNull Context context,
                                     @Nullable String filePath,
                                     @Nullable String fileUri,
                                     @Nullable String displayName) throws Exception {
        return decodePreview(context, filePath, fileUri, displayName);
    }

    @Nullable
    public static LoadedImage decodePreview(@NonNull Context context,
                                            @Nullable String filePath,
                                            @Nullable String fileUri,
                                            @Nullable String displayName) throws Exception {
        if (isAnimatedImageCandidateName(displayName) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Drawable drawable = decodeAnimatedDrawable(context, filePath, fileUri, false);
            if (drawable != null) return LoadedImage.forDrawable(drawable);
        }
        BitmapDecodeResult result = decodeBitmap(context, filePath, fileUri, false);
        return result == null || result.bitmap == null ? null : LoadedImage.forBitmap(
                result.bitmap,
                result.sampleSize <= 1,
                result.sourceWidth,
                result.sourceHeight,
                result.sampleSize);
    }

    @Nullable
    public static LoadedImage decodeDetail(@NonNull Context context,
                                           @Nullable String filePath,
                                           @Nullable String fileUri,
                                           @Nullable String displayName) throws Exception {
        if (isAnimatedImageCandidateName(displayName) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Drawable drawable = decodeAnimatedDrawable(context, filePath, fileUri, true);
            if (drawable != null) return LoadedImage.forDrawable(drawable);
        }
        BitmapDecodeResult result = decodeBitmap(context, filePath, fileUri, true);
        return result == null || result.bitmap == null ? null : LoadedImage.forBitmap(
                result.bitmap,
                result.sampleSize <= 1,
                result.sourceWidth,
                result.sourceHeight,
                result.sampleSize);
    }

    @Nullable
    private static Drawable decodeAnimatedDrawable(@NonNull Context context,
                                                   @Nullable String filePath,
                                                   @Nullable String fileUri,
                                                   boolean detail) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null;
        ImageDecoder.Source source;
        if (filePath != null && filePath.trim().length() > 0) {
            source = ImageDecoder.createSource(new File(filePath));
        } else if (fileUri != null && fileUri.trim().length() > 0) {
            source = ImageDecoder.createSource(context.getContentResolver(), Uri.parse(fileUri));
        } else {
            return null;
        }
        int reqW = Math.max(context.getResources().getDisplayMetrics().widthPixels * (detail ? DETAIL_DISPLAY_SCALE : PREVIEW_DISPLAY_SCALE), 1);
        int reqH = Math.max(context.getResources().getDisplayMetrics().heightPixels * (detail ? DETAIL_DISPLAY_SCALE : PREVIEW_DISPLAY_SCALE), 1);
        long pixelCap = detail ? DETAIL_MAX_BITMAP_PIXELS : PREVIEW_MAX_BITMAP_PIXELS;
        Drawable drawable = ImageDecoder.decodeDrawable(source, (decoder, info, imageSource) -> {
            int sample = calculateInSampleSize(info.getSize().getWidth(), info.getSize().getHeight(), reqW, reqH);
            sample = Math.max(sample, calculateSampleForPixelCap(info.getSize().getWidth(), info.getSize().getHeight(), pixelCap));
            if (sample > 1) decoder.setTargetSampleSize(sample);
        });
        if (drawable instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) drawable).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
        }
        return drawable;
    }

    @Nullable
    private static BitmapDecodeResult decodeBitmap(@NonNull Context context,
                                                   @Nullable String filePath,
                                                   @Nullable String fileUri,
                                                   boolean detail) throws Exception {
        int reqW = Math.max(context.getResources().getDisplayMetrics().widthPixels * (detail ? DETAIL_DISPLAY_SCALE : PREVIEW_DISPLAY_SCALE), 1);
        int reqH = Math.max(context.getResources().getDisplayMetrics().heightPixels * (detail ? DETAIL_DISPLAY_SCALE : PREVIEW_DISPLAY_SCALE), 1);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        if (filePath != null && filePath.trim().length() > 0) {
            BitmapFactory.decodeFile(filePath, bounds);
        } else {
            if (fileUri == null || fileUri.trim().isEmpty()) return null;
            Uri uri = Uri.parse(fileUri);
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, bounds);
            }
        }
        int sample = chooseBitmapSampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH, detail);
        return decodeBitmapWithFallback(context, filePath, fileUri, bounds.outWidth, bounds.outHeight, sample);
    }

    @Nullable
    private static BitmapDecodeResult decodeBitmapWithFallback(@NonNull Context context,
                                                               @Nullable String filePath,
                                                               @Nullable String fileUri,
                                                               int sourceWidth,
                                                               int sourceHeight,
                                                               int initialSample) throws Exception {
        int sample = Math.max(1, initialSample);
        while (sample <= 128) {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bitmap;
                if (filePath != null && filePath.trim().length() > 0) {
                    bitmap = BitmapFactory.decodeFile(filePath, opts);
                } else {
                    if (fileUri == null || fileUri.trim().isEmpty()) return null;
                    Uri uri = Uri.parse(fileUri);
                    try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                        bitmap = BitmapFactory.decodeStream(is, null, opts);
                    }
                }
                return bitmap == null ? null : new BitmapDecodeResult(bitmap, sourceWidth, sourceHeight, sample);
            } catch (OutOfMemoryError oom) {
                sample *= 2;
                System.gc();
            }
        }
        return null;
    }

    private static int chooseBitmapSampleSize(int width, int height, int reqWidth, int reqHeight, boolean detail) {
        if (width <= 0 || height <= 0) return 1;
        long pixels = (long) width * (long) height;
        if (!detail && pixels <= PREVIEW_DIRECT_ORIGINAL_PIXELS) return 1;
        if (detail && pixels <= DETAIL_DIRECT_ORIGINAL_PIXELS) return 1;
        long cap = detail ? DETAIL_MAX_BITMAP_PIXELS : PREVIEW_MAX_BITMAP_PIXELS;
        int sample = detail ? 1 : calculateInSampleSize(width, height, reqWidth, reqHeight);
        sample = Math.max(sample, calculateSampleForPixelCap(width, height, cap));
        return Math.max(1, sample);
    }

    private static boolean isAnimatedImageCandidateName(@Nullable String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".gif") || lower.endsWith(".webp");
    }

    private static int calculateInSampleSize(int width, int height, int reqWidth, int reqHeight) {
        if (width <= 0 || height <= 0) return 1;
        int inSampleSize = 1;
        if (height >= width * TALL_IMAGE_ASPECT_RATIO) {
            while ((width / inSampleSize) > reqWidth) inSampleSize *= 2;
            return Math.max(1, inSampleSize);
        }
        while ((height / inSampleSize) > reqHeight || (width / inSampleSize) > reqWidth) inSampleSize *= 2;
        return Math.max(1, inSampleSize);
    }

    private static int calculateSampleForPixelCap(int width, int height, long maxPixels) {
        if (width <= 0 || height <= 0 || maxPixels <= 0L) return 1;
        int sample = 1;
        while (((long) (width / sample) * (long) (height / sample)) > maxPixels) sample *= 2;
        return Math.max(1, sample);
    }

    private static final class BitmapDecodeResult {
        final Bitmap bitmap;
        final int sourceWidth;
        final int sourceHeight;
        final int sampleSize;

        BitmapDecodeResult(@NonNull Bitmap bitmap, int sourceWidth, int sourceHeight, int sampleSize) {
            this.bitmap = bitmap;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.sampleSize = Math.max(1, sampleSize);
        }
    }
}
