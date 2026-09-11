package com.readwide.codecs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class ZipxNativeCodecsInstrumentedTest {
    @Test
    public void decodesOfficialWavPackPcmRegressionStreamToWave() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        long count;
        try (InputStream input = context.getAssets().open("wavpack-pcm16.wv")) {
            count = ZipxNativeCodecs.decode(
                    ZipxNativeCodecs.METHOD_WAVPACK, input, decoded, 16L * 1024L * 1024L);
        }

        byte[] wave = decoded.toByteArray();
        assertEquals(wave.length, count);
        assertTrue(wave.length > 44);
        assertEquals("RIFF", new String(wave, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wave, 8, 4, StandardCharsets.US_ASCII));
        assertEquals(wave.length - 8L, littleEndianUInt32(wave, 4));
    }

    private static long littleEndianUInt32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
                | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16)
                | ((bytes[offset + 3] & 0xffL) << 24);
    }
}
