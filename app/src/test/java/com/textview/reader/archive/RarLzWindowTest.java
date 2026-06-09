package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class RarLzWindowTest {
    @Test
    public void copyMatch_repeatsPreviousBytes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarLzWindow window = new RarLzWindow(16, out);

        window.writeLiteral('a');
        window.writeLiteral('b');
        window.copyMatch(2, 4);

        assertArrayEquals("ababab".getBytes(StandardCharsets.UTF_8), out.toByteArray());
        assertEquals(6L, window.written());
    }


    @Test
    public void sharedWindowConstructorContinuesDictionaryAtPosition() throws Exception {
        byte[] shared = new byte[16];
        ByteArrayOutputStream firstOut = new ByteArrayOutputStream();
        RarLzWindow first = new RarLzWindow(shared, 0, firstOut);
        first.writeLiteral('x');
        first.writeLiteral('y');
        first.writeLiteral('z');

        ByteArrayOutputStream secondOut = new ByteArrayOutputStream();
        RarLzWindow second = new RarLzWindow(shared, first.position(), secondOut);
        second.copyMatch(3, 3);

        assertArrayEquals("xyz".getBytes(StandardCharsets.UTF_8), secondOut.toByteArray());
        assertEquals(3L, second.written());
    }

    @Test(expected = IOException.class)
    public void copyMatch_rejectsInvalidDistance() throws Exception {
        new RarLzWindow(16, new ByteArrayOutputStream()).copyMatch(0, 1);
    }
}
