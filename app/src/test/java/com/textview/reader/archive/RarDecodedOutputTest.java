package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class RarDecodedOutputTest {
    @Test
    public void outputStreamAdapterIsByteIdentical() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarDecodedOutput decoded = new RarOutputStreamDecodedOutput(out);

        decoded.writeDecodedByte('R');
        decoded.writeDecodedBytes("AR".getBytes(StandardCharsets.UTF_8), 0, 2);

        assertArrayEquals("RAR".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }

    @Test
    public void lzWindowCanWriteThroughDecodedOutputAbstraction() throws Exception {
        RecordingDecodedOutput out = new RecordingDecodedOutput();
        RarLzWindow window = new RarLzWindow(16, out);

        window.writeLiteral('a');
        window.writeLiteral('b');
        window.copyMatch(2, 4);

        assertArrayEquals("ababab".getBytes(StandardCharsets.UTF_8), out.bytes());
        assertEquals(6, out.calls);
        assertEquals(6L, window.written());
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void decodedBytesRejectsInvalidRange() throws Exception {
        new RarOutputStreamDecodedOutput(new ByteArrayOutputStream())
                .writeDecodedBytes(new byte[] {1, 2}, 1, 2);
    }

    private static final class RecordingDecodedOutput implements RarDecodedOutput {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int calls;

        @Override
        public void writeDecodedByte(int value) throws IOException {
            out.write(value & 0xff);
            calls++;
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }
}