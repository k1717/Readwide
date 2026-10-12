package com.readwide.manager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Resource regression source; launcher theming still requires a manual device check. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 33)
public class ThemedLauncherIconInstrumentedTest {
    @Test public void normalAndRoundIconsExposeTransparentBookMasks() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (int resource : new int[]{R.mipmap.ic_launcher, R.mipmap.ic_launcher_round}) {
            Drawable drawable = context.getDrawable(resource);
            assertTrue(drawable instanceof AdaptiveIconDrawable);
            AdaptiveIconDrawable icon = (AdaptiveIconDrawable) drawable;
            assertNotNull(icon.getBackground());
            assertNotNull(icon.getForeground());
            Drawable mask = icon.getMonochrome();
            assertNotNull(mask);
            Bitmap bitmap = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888);
            try {
                mask.setBounds(0, 0, 108, 108);
                mask.draw(new Canvas(bitmap));
                int visible = 0;
                for (int y = 0; y < 108; y++) {
                    for (int x = 0; x < 108; x++) {
                        if (Color.alpha(bitmap.getPixel(x, y)) > 0) visible++;
                    }
                }
                assertTrue("Book mask is empty", visible > 0);
                assertTrue("Mask must not be an opaque icon tile", visible < 108 * 108 / 2);
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)));
                assertEquals(0, Color.alpha(bitmap.getPixel(107, 107)));
            } finally { bitmap.recycle(); }
        }
    }
}
