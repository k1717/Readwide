package com.readwide.manager.archive;

import java.io.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeflateFailureStateTest {
    @Test public void uncheckedInputFailuresCannotResumeOrReportAvailable() throws Exception {
        for (Throwable error : new Throwable[]{new IllegalStateException("input"),new AssertionError("input")}) {
            int[] reads={0}, closes={0};
            InputStream source=new InputStream() {
                @Override public int read() { fail(); return -1; }
                @Override public int read(byte[] b,int o,int n) {
                    reads[0]++;
                    if (error instanceof Error) throw (Error)error;
                    throw (RuntimeException)error;
                }
                @Override public void close() { closes[0]++; }
            };
            OwnedDeflateInputStream stream=new OwnedDeflateInputStream(source);
            assertSame(error,assertThrows(Throwable.class,stream::read));
            assertSame(error,assertThrows(Throwable.class,stream::read));
            assertSame(error,assertThrows(Throwable.class,stream::available));
            assertEquals(1,reads[0]);stream.close();stream.close();assertEquals(1,closes[0]);
        }
    }

    @Test public void invalidCallerRangeDoesNotRetireAHealthyDeflateStream() throws Exception {
        try(InputStream stream=new OwnedDeflateInputStream(new ByteArrayInputStream(Round4Fixtures.deflate(new byte[]{42})))) {
            assertThrows(IndexOutOfBoundsException.class,()->stream.read(new byte[1],Integer.MAX_VALUE,1));
            assertEquals(42,stream.read());assertEquals(-1,stream.read());
        }
    }
}
