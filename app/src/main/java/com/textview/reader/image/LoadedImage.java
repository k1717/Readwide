package com.textview.reader.image;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Result holder for ImageReaderActivity decoding paths. */
public final class LoadedImage {
    public final Bitmap bitmap;
    public final Drawable drawable;
    public final boolean originalQuality;
    public final int sourceWidth;
    public final int sourceHeight;
    public final int sampleSize;

    private LoadedImage(@Nullable Bitmap bitmap,
                        @Nullable Drawable drawable,
                        boolean originalQuality,
                        int sourceWidth,
                        int sourceHeight,
                        int sampleSize) {
        this.bitmap = bitmap;
        this.drawable = drawable;
        this.originalQuality = originalQuality;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.sampleSize = Math.max(1, sampleSize);
    }

    @NonNull
    public static LoadedImage forBitmap(@NonNull Bitmap bitmap) {
        return forBitmap(bitmap, true, bitmap.getWidth(), bitmap.getHeight(), 1);
    }

    @NonNull
    public static LoadedImage forBitmap(@NonNull Bitmap bitmap,
                                        boolean originalQuality,
                                        int sourceWidth,
                                        int sourceHeight,
                                        int sampleSize) {
        return new LoadedImage(bitmap, null, originalQuality, sourceWidth, sourceHeight, sampleSize);
    }

    @NonNull
    public static LoadedImage forDrawable(@NonNull Drawable drawable) {
        return new LoadedImage(null, drawable, true,
                Math.max(1, drawable.getIntrinsicWidth()),
                Math.max(1, drawable.getIntrinsicHeight()),
                1);
    }
}
