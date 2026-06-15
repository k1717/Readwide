package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class DecodedStreamSafetyLimitTest {
    @Test
    public void decodedStreamLimitRejectsOverflow() throws Exception {
        Method method = ArchiveSupport.class.getDeclaredMethod(
                "checkedAddDecodedStreamBytes", long.class, int.class);
        method.setAccessible(true);
        try {
            method.invoke(null, Long.MAX_VALUE, 1);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertTrue(cause instanceof ArchiveSupport.UnsupportedArchiveFeatureException);
            assertTrue(String.valueOf(cause.getMessage()).contains("safety limit"));
            return;
        }
        throw new AssertionError("Expected decoded stream safety-limit failure");
    }

    @Test
    public void decodedStreamLimitAllowsNormalIncrement() throws Exception {
        Method method = ArchiveSupport.class.getDeclaredMethod(
                "checkedAddDecodedStreamBytes", long.class, int.class);
        method.setAccessible(true);
        Object value = method.invoke(null, 100L, 23);
        assertEquals(123L, ((Long) value).longValue());
    }
}
