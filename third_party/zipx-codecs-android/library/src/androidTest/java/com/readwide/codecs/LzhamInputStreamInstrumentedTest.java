package com.readwide.codecs;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.zip.Adler32;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Synthetic raw LZHAM grammar, not compressed-LZ/Huffman producer evidence. */
@RunWith(AndroidJUnit4.class)
public class LzhamInputStreamInstrumentedTest {
    @Test public void rawBlocksCrossInputDictionaryAndCallerBuffers() throws Exception {
        byte[] raw = new byte[200003];
        for (int i=0;i<raw.length;i++) raw[i]=(byte)(i*31);
        for (int chunk : new int[]{1,7,65536}) {
            try (InputStream in=LzhamInputStream.open(new ByteArrayInputStream(frame(raw)),15,raw.length)) {
                assertArrayEquals(raw,readAll(in,chunk));
            }
        }
    }

    @Test public void emptyAndShortPackedReadsFinishExplicitly() throws Exception {
        for (byte[] raw : new byte[][]{new byte[0],{1,2,3,4,5}}) {
            Tracked source=new Tracked(frame(raw)) {
                @Override public synchronized int read(byte[] b,int o,int n) { return super.read(b,o,Math.min(1,n)); }
            };
            try(InputStream in=LzhamInputStream.open(source,15,raw.length)) { assertArrayEquals(raw,readAll(in,3)); }
            assertEquals(1,source.closes);
        }
    }

    @Test public void everyTruncatedTrailerFailsIncludingZeroAdlerBytes() throws Exception {
        byte[] raw=new byte[65520]; Arrays.fill(raw,(byte)1);
        byte[] packed=frame(raw);
        assertEquals(0,packed[packed.length-1]); assertEquals(0,packed[packed.length-2]);
        for (int length : new int[]{0,1,4,packed.length-1,packed.length-2,packed.length-5}) {
            byte[] truncated=Arrays.copyOf(packed,length);
            assertThrows(IOException.class,()->decode(truncated,raw.length));
        }
    }

    @Test public void wrongLengthAndAdlerRemainErrors() throws Exception {
        byte[] packed=frame(new byte[]{1,2,3});
        for(int length:new int[]{0,2,4}) assertThrows(IOException.class,()->decode(packed,length));
        packed[packed.length-1]^=1;
        assertThrows(IOException.class,()->decode(packed,3));
    }

    @Test public void rawLengthCheckAndTrailingBytesAreNotIgnored() throws Exception {
        byte[] packed=frame(new byte[]{1,2,3});
        byte[] badLength=packed.clone(); badLength[3]^=1;
        assertThrows(IOException.class,()->decode(badLength,3));
        assertThrows(IOException.class,()->decode(Arrays.copyOf(packed,packed.length+1),3));
    }

    @Test public void invalidParametersCloseOwnedInput() {
        for(int bits:new int[]{14,30}) {
            Tracked source=new Tracked(new byte[0]);
            assertThrows(IOException.class,()->LzhamInputStream.open(source,bits,0));
            assertEquals(1,source.closes);
        }
        Tracked source=new Tracked(new byte[0]);
        assertThrows(IOException.class,()->LzhamInputStream.open(source,15,-1));
        assertEquals(1,source.closes);
    }

    @Test public void stalledInputFailsOnceAndCannotRevive() throws Exception {
        Tracked source=new Tracked(frame(new byte[0])) {
            @Override public synchronized int read(byte[] b,int o,int n) { return 0; }
        };
        try(InputStream in=LzhamInputStream.open(source,15,0)) {
            IOException error=assertThrows(IOException.class,in::read);
            assertSame(error,assertThrows(IOException.class,in::read));
        }
        assertEquals(1,source.closes);
    }

    @Test public void originalCheckedAndUncheckedInputErrorsStayTerminal() throws Exception {
        IOException checked=new IOException("source I/O");
        RuntimeException unchecked=new IllegalStateException("source runtime");
        for(boolean runtime:new boolean[]{false,true}) {
            InputStream source=new InputStream() {
                @Override public int read() throws IOException { if(runtime)throw unchecked;throw checked; }
            };
            try(InputStream in=LzhamInputStream.open(source,15,0)) {
                if(runtime) {
                    assertSame(unchecked,assertThrows(RuntimeException.class,in::read));
                    assertSame(unchecked,assertThrows(RuntimeException.class,in::read));
                } else {
                    assertSame(checked,assertThrows(IOException.class,in::read));
                    assertSame(checked,assertThrows(IOException.class,in::read));
                }
            }
        }
    }

    @Test public void cancellationBeforeOpenAndAfterReadRetiresState() throws Exception {
        Tracked before=new Tracked(new byte[0]);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,()->LzhamInputStream.open(before,15,0));
            assertTrue(Thread.currentThread().isInterrupted()); assertEquals(1,before.closes);
        } finally {Thread.interrupted();}
        InputStream in=LzhamInputStream.open(new ByteArrayInputStream(frame(new byte[]{1,2,3})),15,3);
        try {
            assertEquals(1,in.read()); Thread.currentThread().interrupt();
            IOException error=assertThrows(InterruptedIOException.class,in::read);
            Thread.interrupted(); assertSame(error,assertThrows(IOException.class,in::read));
        } finally {Thread.interrupted();in.close();}
    }

    @Test public void inputCallbackCannotCloseActiveDecoder() throws Exception {
        InputStream[] owner=new InputStream[1];
        InputStream source=new InputStream() {
            @Override public int read() throws IOException {owner[0].close();return 0;}
        };
        owner[0]=LzhamInputStream.open(source,15,0);
        try {
            IOException error=assertThrows(IOException.class,owner[0]::read);
            assertTrue(error.getMessage().contains("input callback"));
            assertSame(error,assertThrows(IOException.class,owner[0]::read));
        } finally {owner[0].close();}
    }

    @Test public void failedSourceCloseCanRetryWithoutReopeningDecoder() throws Exception {
        int[] closes={0}; IOException failure=new IOException("close");
        InputStream source=new ByteArrayInputStream(frame(new byte[0])) {
            @Override public void close() throws IOException {if(++closes[0]==1)throw failure;}
        };
        InputStream in=LzhamInputStream.open(source,15,0);
        assertSame(failure,assertThrows(IOException.class,in::close));
        assertThrows(IOException.class,in::read);
        in.close();in.close();assertEquals(2,closes[0]);assertThrows(IOException.class,in::read);
    }

    @Test public void uncheckedSourceCloseFailureCanAlsoRetry() throws Exception {
        for(Throwable failure:new Throwable[]{new IllegalStateException("close"),new AssertionError("close")}) {
            int[] closes={0};
            InputStream source=new ByteArrayInputStream(frame(new byte[0])) {
                @Override public void close() {
                    if(++closes[0]!=1)return;
                    if(failure instanceof RuntimeException)throw (RuntimeException)failure;
                    throw (Error)failure;
                }
            };
            InputStream input=LzhamInputStream.open(source,15,0);
            assertSame(failure,assertThrows(Throwable.class,input::close));
            assertThrows(IOException.class,input::read);
            input.close();input.close();assertEquals(2,closes[0]);
        }
    }

    @Test public void sourceCleanupCannotRecursivelyCloseTheOwner() throws Exception {
        InputStream[] owner={null};int[] closes={0};
        InputStream source=new ByteArrayInputStream(frame(new byte[0])) {
            @Override public void close()throws IOException{if(++closes[0]==1)owner[0].close();}
        };
        owner[0]=LzhamInputStream.open(source,15,0);
        IOException failure=assertThrows(IOException.class,owner[0]::close);
        assertTrue(failure.getMessage().contains("callback"));
        owner[0].close();owner[0].close();assertEquals(2,closes[0]);
    }

    @Test public void invalidCallerRangesDoNotConsumeTheStream() throws Exception {
        try(InputStream in=LzhamInputStream.open(new ByteArrayInputStream(frame(new byte[]{9})),15,1)) {
            assertThrows(IndexOutOfBoundsException.class,()->in.read(new byte[1],1,1));
            assertEquals(0,in.read(new byte[0]));assertEquals(9,in.read());assertEquals(-1,in.read());
        }
    }

    private static byte[] decode(byte[] packed,int length) throws IOException {
        try(InputStream in=LzhamInputStream.open(new ByteArrayInputStream(packed),15,length)){return readAll(in,997);}
    }
    private static byte[] readAll(InputStream in,int chunk) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[chunk];int n;
        while((n=in.read(buffer))!=-1){assertTrue(n>0);out.write(buffer,0,n);}return out.toByteArray();
    }
    // From the pinned decoder's cRawBlock/cEOFBlock grammar: MSB-first fields,
    // raw length minus one, XOR byte, byte alignment, final big-endian Adler32.
    private static byte[] frame(byte[] raw) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int offset=0;offset<raw.length;) {
            int count=Math.min(100003,raw.length-offset),n=count-1;
            long bits=(2L<<32)|((long)n<<8)|((n^(n>>>8)^(n>>>16))&255);
            bits<<=6;for(int shift=32;shift>=0;shift-=8)out.write((int)(bits>>>shift)&255);
            out.write(raw,offset,count);offset+=count;
        }
        out.write(0xc0);Adler32 adler=new Adler32();adler.update(raw);
        for(int shift=24;shift>=0;shift-=8)out.write((int)(adler.getValue()>>>shift)&255);
        return out.toByteArray();
    }
    private static class Tracked extends ByteArrayInputStream {
        int closes;Tracked(byte[] bytes){super(bytes);}
        @Override public void close(){closes++;}
    }
}
