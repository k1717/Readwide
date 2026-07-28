package com.readwide.manager.image;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
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
    static final int ARCHIVE_PREVIEW_DISPLAY_SCALE = 3;
    static final int PREFETCH_DISPLAY_SCALE = 1;
    private static final int DETAIL_DISPLAY_SCALE = 4;
    private static final long PREVIEW_DIRECT_ORIGINAL_PIXELS = 24_000_000L;
    private static final long PREVIEW_MAX_BITMAP_PIXELS = 24_000_000L;
    static final long ARCHIVE_PREVIEW_MAX_BITMAP_PIXELS = 24_000_000L;
    // Prefetch targets the actual display size. The cap is only a backstop for
    // very tall fit-width pages whose proportional height exceeds the viewport.
    static final long PREFETCH_MAX_BITMAP_PIXELS = 8_000_000L;
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
        return decodePreviewProfile(
                context,
                filePath,
                fileUri,
                displayName,
                PREVIEW_DISPLAY_SCALE,
                PREVIEW_DIRECT_ORIGINAL_PIXELS,
                PREVIEW_MAX_BITMAP_PIXELS);
    }

    /**
     * Archive pages use a moderately denser preview than loose images. Comic
     * pages are revisited and paired frequently, so this tier sits between the
     * ordinary preview and the explicit zoom/detail decode.
     */
    @Nullable
    public static LoadedImage decodeArchivePreview(@NonNull Context context,
                                                   @Nullable String filePath,
                                                   @Nullable String fileUri,
                                                   @Nullable String displayName) throws Exception {
        return decodePreviewProfile(
                context,
                filePath,
                fileUri,
                displayName,
                ARCHIVE_PREVIEW_DISPLAY_SCALE,
                ARCHIVE_PREVIEW_MAX_BITMAP_PIXELS,
                ARCHIVE_PREVIEW_MAX_BITMAP_PIXELS);
    }

    @Nullable
    private static LoadedImage decodePreviewProfile(@NonNull Context context,
                                                    @Nullable String filePath,
                                                    @Nullable String fileUri,
                                                    @Nullable String displayName,
                                                    int displayScale,
                                                    long directOriginalPixels,
                                                    long maxBitmapPixels) throws Exception {
        if (shouldInspectAnimatedCandidate(displayName, Build.VERSION.SDK_INT)) {
            Drawable drawable = decodeAnimatedDrawable(
                    context,
                    filePath,
                    fileUri,
                    displayScale,
                    maxBitmapPixels);
            // ImageDecoder also returns an ordinary BitmapDrawable for static
            // GIF/WebP files. Only a genuinely animated drawable belongs on the
            // non-bitmap path; static WebP comic pages must remain cacheable so
            // the archive reader can compose a two-page spread.
            if (drawable instanceof AnimatedImageDrawable) {
                return LoadedImage.forDrawable(drawable);
            }
            LoadedImage staticImage = bitmapFromStaticCandidateDrawable(drawable);
            if (staticImage != null) return staticImage;
        }
        BitmapDecodeResult result = decodeBitmap(
                context,
                filePath,
                fileUri,
                displayScale,
                directOriginalPixels,
                maxBitmapPixels,
                true);
        return result == null || result.bitmap == null ? null : LoadedImage.forBitmap(
                result.bitmap,
                result.sampleSize <= 1,
                result.sourceWidth,
                result.sourceHeight,
                result.sampleSize);
    }

    /**
     * Decodes a cache warm-up bitmap for a neighboring page. This deliberately
     * bypasses animated drawables (the bitmap cache cannot retain them) and uses
     * a lower pixel ceiling than an on-demand preview. The normal detail pass
     * refines the page if the user actually opens it.
     */
    @Nullable
    public static LoadedImage decodePrefetch(@NonNull Context context,
                                             @Nullable String filePath,
                                             @Nullable String fileUri,
                                             @Nullable String displayName) throws Exception {
        if (shouldInspectAnimatedCandidate(displayName, Build.VERSION.SDK_INT)) {
            Drawable drawable = decodeAnimatedDrawable(
                    context,
                    filePath,
                    fileUri,
                    PREFETCH_DISPLAY_SCALE,
                    PREFETCH_MAX_BITMAP_PIXELS);
            if (shouldSkipPrefetchAfterDrawableClassification(
                    drawable instanceof AnimatedImageDrawable)) {
                return null;
            }
            // Filename alone cannot distinguish animation. Reuse the bitmap from
            // a static BitmapDrawable; skipping every .webp candidate made normal
            // WebP comic pages permanently ineligible for spreads.
            LoadedImage staticImage = bitmapFromStaticCandidateDrawable(drawable);
            if (staticImage != null) return staticImage;
        }
        BitmapDecodeResult result = decodeBitmap(
                context,
                filePath,
                fileUri,
                PREFETCH_DISPLAY_SCALE,
                0L,
                PREFETCH_MAX_BITMAP_PIXELS,
                true);
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
        if (shouldInspectAnimatedCandidate(displayName, Build.VERSION.SDK_INT)) {
            Drawable drawable = decodeAnimatedDrawable(
                    context,
                    filePath,
                    fileUri,
                    DETAIL_DISPLAY_SCALE,
                    DETAIL_MAX_BITMAP_PIXELS);
            if (drawable instanceof AnimatedImageDrawable) {
                return LoadedImage.forDrawable(drawable);
            }
            LoadedImage staticImage = bitmapFromStaticCandidateDrawable(drawable);
            if (staticImage != null) return staticImage;
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
                                                   int displayScale,
                                                   long pixelCap) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null;
        ImageDecoder.Source source;
        if (filePath != null && filePath.trim().length() > 0) {
            source = ImageDecoder.createSource(new File(filePath));
        } else if (fileUri != null && fileUri.trim().length() > 0) {
            source = ImageDecoder.createSource(context.getContentResolver(), Uri.parse(fileUri));
        } else {
            return null;
        }
        int safeDisplayScale = Math.max(1, displayScale);
        int reqW = Math.max(
                context.getResources().getDisplayMetrics().widthPixels * safeDisplayScale,
                1);
        int reqH = Math.max(
                context.getResources().getDisplayMetrics().heightPixels * safeDisplayScale,
                1);
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
        return decodeBitmap(
                context,
                filePath,
                fileUri,
                detail ? DETAIL_DISPLAY_SCALE : PREVIEW_DISPLAY_SCALE,
                detail ? DETAIL_DIRECT_ORIGINAL_PIXELS : PREVIEW_DIRECT_ORIGINAL_PIXELS,
                detail ? DETAIL_MAX_BITMAP_PIXELS : PREVIEW_MAX_BITMAP_PIXELS,
                !detail);
    }

    @Nullable
    private static BitmapDecodeResult decodeBitmap(@NonNull Context context,
                                                    @Nullable String filePath,
                                                    @Nullable String fileUri,
                                                    int displayScale,
                                                    long directOriginalPixels,
                                                    long maxBitmapPixels,
                                                    boolean useDisplayTarget) throws Exception {
        int reqW = Math.max(context.getResources().getDisplayMetrics().widthPixels * displayScale, 1);
        int reqH = Math.max(context.getResources().getDisplayMetrics().heightPixels * displayScale, 1);
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
        int sample = chooseBitmapSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                reqW,
                reqH,
                directOriginalPixels,
                maxBitmapPixels,
                useDisplayTarget);
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
                Thread.yield();
            }
        }
        return null;
    }

    static int chooseBitmapSampleSize(int width,
                                      int height,
                                      int reqWidth,
                                      int reqHeight,
                                      long directOriginalPixels,
                                      long maxBitmapPixels,
                                      boolean useDisplayTarget) {
        if (width <= 0 || height <= 0) return 1;
        long pixels = (long) width * (long) height;
        if (directOriginalPixels > 0L && pixels <= directOriginalPixels) return 1;
        int sample = useDisplayTarget
                ? calculateInSampleSize(width, height, reqWidth, reqHeight)
                : 1;
        sample = Math.max(sample, calculateSampleForPixelCap(width, height, maxBitmapPixels));
        return Math.max(1, sample);
    }

    private static boolean isAnimatedImageCandidateName(@Nullable String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".gif") || lower.endsWith(".webp");
    }

    static boolean shouldInspectAnimatedCandidate(@Nullable String name, int sdkInt) {
        return sdkInt >= Build.VERSION_CODES.P && isAnimatedImageCandidateName(name);
    }

    /**
     * Filename is only a prompt to inspect with ImageDecoder. Static WebP/GIF
     * candidates must continue down the bitmap prefetch path.
     */
    static boolean shouldSkipPrefetchAfterDrawableClassification(
            boolean actuallyAnimated) {
        return actuallyAnimated;
    }

    @Nullable
    private static LoadedImage bitmapFromStaticCandidateDrawable(
            @Nullable Drawable drawable) {
        if (!(drawable instanceof BitmapDrawable)) return null;
        Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
        if (bitmap == null || bitmap.isRecycled()) return null;
        // ImageDecoder may have sampled the source, but it does not expose that
        // sample factor here. Mark it non-original so an explicit zoom can still
        // request the normal detail pass.
        return LoadedImage.forBitmap(
                bitmap,
                false,
                bitmap.getWidth(),
                bitmap.getHeight(),
                1);
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
