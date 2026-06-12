package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class SevenZSplitEncryptedDrainSafetyTest {
    @Test
    public void sevenZDrainUsesSameDecodedStreamSafetyLimit() throws Exception {
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
        throw new AssertionError("Expected the shared decoded stream safety-limit guard to fail");
    }

    @Test
    public void sevenZPasswordVerificationMessagesClassifyAsBadPassword() {
        assertEquals(ArchiveSupport.ExtractionFailure.BAD_PASSWORD,
                ArchiveFailureClassifier.classify(new IOException("Password verification failed")));
        assertEquals(ArchiveSupport.ExtractionFailure.BAD_PASSWORD,
                ArchiveFailureClassifier.classify(new IOException("Wrong passphrase for encrypted 7z entry")));
    }
}
