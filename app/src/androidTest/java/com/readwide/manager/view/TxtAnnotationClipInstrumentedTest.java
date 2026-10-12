package com.readwide.manager.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.StaticLayout;
import android.text.TextPaint;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertArrayEquals;

/** Pixel oracle for highlight clipping only; exact page-count/layout code is not changed. */
@RunWith(AndroidJUnit4.class)
public class TxtAnnotationClipInstrumentedTest {
    @Test public void clippedHighlightPixelsMatchOriginalFullRangePaths() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                StringBuilder body = new StringBuilder();
                for (int i = 0; i < 180; i++) body.append("한글 中文 😀 e\u0301 English العربية אבג\n");
                body.append("\n\n");
                String text = body.toString();
                TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                textPaint.setTextSize(23.25f);
                StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, 241)
                        .setLineSpacing(0, 1.15f).setIncludePad(true).build();
                CustomReaderView view = new CustomReaderView(
                        InstrumentationRegistry.getInstrumentation().getTargetContext());
                // Inject the same immutable layout/text into the production drawing path and oracle.
                set(view, "text", text);
                set(view, "layout", layout);
                List<CustomReaderView.AnnotationHighlightRange> ranges = Arrays.asList(
                        new CustomReaderView.AnnotationHighlightRange(0, text.length()),
                        new CustomReaderView.AnnotationHighlightRange(2, 21),
                        new CustomReaderView.AnnotationHighlightRange(135, text.length() - 17),
                        new CustomReaderView.AnnotationHighlightRange(text.length() - 40, text.length() + 100),
                        new CustomReaderView.AnnotationHighlightRange(500, 501));
                view.setAnnotationHighlights(ranges);
                Field paintField = CustomReaderView.class.getDeclaredField("annotationHighlightPaint");
                paintField.setAccessible(true);
                Paint paint = (Paint) paintField.get(view);
                Method draw = CustomReaderView.class.getDeclaredMethod("drawAnnotationHighlights", Canvas.class);
                draw.setAccessible(true);
                for (float top : new float[]{-350, -7.25f, 0, 13.5f, 250.75f,
                        layout.getLineTop(70) - 0.5f, layout.getHeight() - 100.5f, layout.getHeight() + 3}) {
                    Bitmap expected = Bitmap.createBitmap(280, 320, Bitmap.Config.ARGB_8888);
                    Bitmap actual = Bitmap.createBitmap(280, 320, Bitmap.Config.ARGB_8888);
                    try {
                        Canvas original = canvas(expected, top), optimized = canvas(actual, top);
                        // Frozen pre-change behavior: construct the entire range, then let Canvas clip.
                        Path path = new Path();
                        for (CustomReaderView.AnnotationHighlightRange range : ranges) {
                            int start = Math.max(0, Math.min(text.length(), range.start));
                            int end = Math.max(0, Math.min(text.length(), range.end));
                            if (end <= start) continue;
                            path.reset();
                            layout.getSelectionPath(start, end, path);
                            original.drawPath(path, paint);
                        }
                        draw.invoke(view, optimized);
                        int[] before = new int[280 * 320], after = new int[before.length];
                        expected.getPixels(before, 0, 280, 0, 0, 280, 320);
                        actual.getPixels(after, 0, 280, 0, 0, 280, 320);
                        assertArrayEquals("Viewport top=" + top, before, after);
                    } finally { expected.recycle(); actual.recycle(); }
                }
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
    }

    private static Canvas canvas(Bitmap bitmap, float top) {
        Canvas canvas = new Canvas(bitmap);
        canvas.clipRect(8, 9, 268, 309);
        canvas.translate(11.5f, 9 - top);
        return canvas;
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = CustomReaderView.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
