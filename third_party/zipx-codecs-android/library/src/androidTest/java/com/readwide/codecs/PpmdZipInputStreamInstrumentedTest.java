package com.readwide.codecs;

import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.zip.CRC32;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Independent native stream contract; no libarchive API. Uncompiled/unrun. */
@RunWith(AndroidJUnit4.class)
public class PpmdZipInputStreamInstrumentedTest {
    // These offsets, lengths and CRCs refer to the pinned, hashed upstream ZIP.
    private static final int[] OFFSETS={41,2439,2839}, PACKED={2361,365,472}, SIZES={6699,852,912};
    private static final long[] CRCS={0x8dd7379eL,0x7ae59b31L,0xba8e3baaL};
    @Before public void requireNative(){assertTrue(PpmdZipInputStream.isAvailable());}

    @Test public void upstreamMembersMatchLengthsAndCrcsWithDifferentReadSizes() throws Exception {
        for(int entry=0;entry<3;entry++)for(int chunk:new int[]{1,7,65536}) {
            try(InputStream input=PpmdZipInputStream.open(new ByteArrayInputStream(payload(entry)),SIZES[entry])) {
                byte[] bytes=readAll(input,chunk);assertEquals(SIZES[entry],bytes.length);
                CRC32 crc=new CRC32();crc.update(bytes);assertEquals(CRCS[entry],crc.getValue());
                assertEquals(-1,input.read());assertEquals(0,input.read(new byte[0]));
            }
        }
    }

    @Test public void positiveShortPackedReadsAreFilled() throws Exception {
        byte[] bytes=payload(2);
        try(InputStream input=PpmdZipInputStream.open(new FilterInputStream(new ByteArrayInputStream(bytes)) {
            @Override public int read(byte[] out,int offset,int count)throws IOException{return super.read(out,offset,Math.min(3,count));}
        },912)) {assertEquals(912,readAll(input,13).length);}
    }

    @Test public void cutoffHeaderWithNoModelExhaustionRetainsTheSamePayload() throws Exception {
        byte[] bytes=payload(2);bytes[1]|=0x10;
        try(InputStream input=PpmdZipInputStream.open(new ByteArrayInputStream(bytes),912)) {
            CRC32 crc=new CRC32();crc.update(readAll(input,97));assertEquals(CRCS[2],crc.getValue());
        }
        // Synthetic property mutation only: not a producer cutoff/restoration fixture.
    }

    @Test public void invalidOrderFreezeAndReservedRestorationFailAndCloseSource() throws Exception {
        for(int properties:new int[]{0,0x2007,0x3007,0xf007}) {
            byte[] bytes=payload(2);bytes[0]=(byte)properties;bytes[1]=(byte)(properties>>>8);
            Tracked source=new Tracked(bytes);
            assertThrows(IOException.class,()->PpmdZipInputStream.open(source,912));assertEquals(1,source.closes);
        }
    }

    @Test public void explicitFreezeProfilesShareSmallPayloadBeforeModelExhaustion() throws Exception {
        for(PpmdZipInputStream.FreezeRevision revision:PpmdZipInputStream.FreezeRevision.values()) {
            for(int entry=0;entry<3;entry++) {
                byte[] bytes=payload(entry);bytes[1]|=0x20;
                try(InputStream input=PpmdZipInputStream.openFreeze(new ByteArrayInputStream(bytes),SIZES[entry],revision)) {
                    byte[] decoded=readAll(input,17);assertEquals(SIZES[entry],decoded.length);
                    CRC32 crc=new CRC32();crc.update(decoded);assertEquals(CRCS[entry],crc.getValue());
                    assertEquals(-1,input.read());
                }
            }
        }
        // Property mutation only, not proof of exhaustion or revision divergence.
    }

    @Test public void explicitFreezeCannotOverrideAnotherRestorationProfile() throws Exception {
        for(PpmdZipInputStream.FreezeRevision revision:PpmdZipInputStream.FreezeRevision.values()) {
            for(int restoration:new int[]{0,1,3,15}) {
                byte[] bytes=payload(2);bytes[1]=(byte)((bytes[1]&15)|(restoration<<4));
                Tracked source=new Tracked(bytes);
                assertThrows(IOException.class,()->PpmdZipInputStream.openFreeze(source,912,revision));
                assertEquals(1,source.closes);
            }
        }
        Tracked source=new Tracked(payload(2));
        assertThrows(IOException.class,()->PpmdZipInputStream.openFreeze(source,912,null));
        assertEquals(1,source.closes);
    }

    @Test public void explicitFreezeStillRequiresExactSizeRangeAndPackedEnd() throws Exception {
        for(PpmdZipInputStream.FreezeRevision revision:PpmdZipInputStream.FreezeRevision.values()) {
            for(int fault=0;fault<4;fault++) {
                byte[] valid=payload(2);valid[1]|=0x20;
                byte[] bytes=Arrays.copyOf(valid,valid.length+(fault==0 ? 1 : fault==1 ? -1 : 0));
                if(fault==2)Arrays.fill(bytes,2,6,(byte)0xff);
                long expected=fault==3 ? 911 : 912;
                Tracked source=new Tracked(bytes);
                try(InputStream input=PpmdZipInputStream.openFreeze(source,expected,revision)) {
                    byte[] output=new byte[65536];Arrays.fill(output,(byte)42);
                    IOException failure=assertThrows(PpmdZipInputStream.DataException.class,()->input.read(output));
                    for(byte value:output)assertEquals(42,value);
                    assertSame(failure,assertThrows(IOException.class,input::read));
                }
                assertEquals(1,source.closes);
            }
        }
    }

    @Test public void explicitFreezeKeepsSourceFailuresDistinctFromDecoderRejection() throws Exception {
        for(PpmdZipInputStream.FreezeRevision revision:PpmdZipInputStream.FreezeRevision.values()) {
            byte[] bytes=payload(2);bytes[1]|=0x20;
            IOException failure=new IOException("Synthetic source read failure");
            Tracked source=new Tracked(bytes) {
                @Override public int read(byte[] b,int off,int len) { throw new IllegalStateException(failure); }
            };
            try(InputStream input=PpmdZipInputStream.openFreeze(source,912,revision)) {
                IllegalStateException error=assertThrows(IllegalStateException.class,input::read);
                assertSame(failure,error.getCause());
                assertSame(error,assertThrows(IllegalStateException.class,input::read));
            }
            assertEquals(1,source.closes);
            InputStream checkedSource=new FilterInputStream(new ByteArrayInputStream(bytes)) {
                @Override public int read(byte[] b,int off,int len)throws IOException { throw failure; }
            };
            try(InputStream input=PpmdZipInputStream.openFreeze(checkedSource,912,revision)) {
                assertSame(failure,assertThrows(IOException.class,input::read));
            }
        }
    }

    @Test public void explicitFreezeKeepsCancellationAndCleanupTerminal() throws Exception {
        for(PpmdZipInputStream.FreezeRevision revision:PpmdZipInputStream.FreezeRevision.values()) {
            byte[] bytes=payload(2);bytes[1]|=0x20;
            Tracked source=new Tracked(bytes) {
                @Override public synchronized int read(byte[] b,int off,int len) {
                    int count=super.read(b,off,len);Thread.currentThread().interrupt();return count;
                }
            };
            try(InputStream input=PpmdZipInputStream.openFreeze(source,912,revision)) {
                assertThrows(InterruptedIOException.class,input::read);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {Thread.interrupted();}
            assertEquals(1,source.closes);
            Tracked closeFailure=new Tracked(bytes) {
                @Override public void close()throws IOException {
                    super.close();if(closes==1)throw new IOException("Synthetic close failure");
                }
            };
            InputStream input=PpmdZipInputStream.openFreeze(closeFailure,912,revision);
            assertThrows(IOException.class,input::close);
            assertThrows(IOException.class,input::read);
            input.close();input.close();assertEquals(2,closeFailure.closes);
        }
    }

    @Test public void truncatedPropertiesAndPayloadFailWithoutZeroPadding() throws Exception {
        byte[] bytes=payload(2);
        for(int size:new int[]{0,1,2,3,5,bytes.length-1}) {
            Tracked source=new Tracked(Arrays.copyOf(bytes,size));
            assertThrows(IOException.class,()->{try(InputStream input=PpmdZipInputStream.open(source,912)){readAll(input,65536);}});
            assertEquals(1,source.closes);
        }
    }

    @Test public void wrongDeclaredSizeCannotReachSuccessfulEof() throws Exception {
        for(long size:new long[]{0,911,913}) {
            assertThrows(IOException.class,()->{try(InputStream input=PpmdZipInputStream.open(new ByteArrayInputStream(payload(2)),size)){readAll(input,65536);}});
        }
        Tracked source=new Tracked(payload(2));
        assertThrows(IOException.class,()->PpmdZipInputStream.open(source,-1));assertEquals(1,source.closes);
    }

    @Test public void trailingPackedBytesFailBeforePublishingTheCurrentNativeChunk() throws Exception {
        byte[] valid=payload(2),bytes=Arrays.copyOf(valid,valid.length+1);
        try(InputStream input=PpmdZipInputStream.open(new ByteArrayInputStream(bytes),912)) {
            byte[] output=new byte[65536];Arrays.fill(output,(byte)9);
            IOException failure=assertThrows(IOException.class,()->input.read(output));
            for(byte value:output)assertEquals(9,value);
            assertSame(failure,assertThrows(IOException.class,()->input.read(output)));
        }
    }

    @Test public void invalidInitialRangeCannotDecode() throws Exception {
        byte[] bytes=payload(2);Arrays.fill(bytes,2,6,(byte)0xff);
        try(InputStream input=PpmdZipInputStream.open(new ByteArrayInputStream(bytes),912)) {
            assertThrows(IOException.class,input::read);
        }
    }

    @Test public void zeroProgressInputIsTerminal() throws Exception {
        Tracked source=new Tracked(payload(2)) {
            @Override public int read(byte[] bytes,int offset,int count){return 0;}
        };
        try(InputStream input=PpmdZipInputStream.open(source,912)) {
            IOException failure=assertThrows(IOException.class,input::read);
            assertSame(failure,assertThrows(IOException.class,input::read));
        }
        assertEquals(1,source.closes);
    }

    @Test public void checkedSourceFailurePreservesIdentity() throws Exception {
        IOException expected=new IOException("source failed");
        InputStream source=new FilterInputStream(new ByteArrayInputStream(payload(2))) {
            @Override public int read(byte[] bytes,int offset,int count)throws IOException{throw expected;}
        };
        try(InputStream input=PpmdZipInputStream.open(source,912)) {
            assertSame(expected,assertThrows(IOException.class,input::read));
            assertSame(expected,assertThrows(IOException.class,input::read));
        }
    }

    @Test public void uncheckedSourceFailureIsNotAFormatFallback() throws Exception {
        IllegalStateException expected=new IllegalStateException("source failed");
        InputStream source=new FilterInputStream(new ByteArrayInputStream(payload(2))) {
            @Override public int read(byte[] bytes,int offset,int count){throw expected;}
        };
        try(InputStream input=PpmdZipInputStream.open(source,912)) {
            assertSame(expected,assertThrows(IllegalStateException.class,input::read));
            assertSame(expected,assertThrows(IllegalStateException.class,input::read));
        }
    }

    @Test public void cancellationBeforeOpenClosesInputAndPreservesInterrupt() throws Exception {
        Tracked source=new Tracked(payload(2));Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,()->PpmdZipInputStream.open(source,912));
            assertTrue(Thread.currentThread().isInterrupted());assertEquals(1,source.closes);
        } finally {Thread.interrupted();}
    }

    @Test public void reentrantCloseFromSourceCallbackIsRejectedAndRetiresRead() throws Exception {
        InputStream[] owner={null};
        InputStream source=new FilterInputStream(new ByteArrayInputStream(payload(2))) {
            @Override public int read(byte[] bytes,int offset,int count)throws IOException{owner[0].close();return super.read(bytes,offset,count);}
        };
        try(InputStream input=PpmdZipInputStream.open(source,912)) {
            owner[0]=input;IOException failure=assertThrows(IOException.class,input::read);
            assertSame(failure,assertThrows(IOException.class,input::read));
        }
    }

    @Test public void failedSourceCloseCanRetryWithoutReopeningDecoder() throws Exception {
        Tracked source=new Tracked(payload(2)) {
            @Override public void close()throws IOException{super.close();if(closes==1)throw new IOException("close failed");}
        };
        InputStream input=PpmdZipInputStream.open(source,912);
        assertThrows(IOException.class,input::close);
        assertThrows(IOException.class,input::read);
        input.close();input.close();assertEquals(2,source.closes);
        assertThrows(IOException.class,input::read);
    }

    @Test public void invalidScalarPropertyBytesCannotBecomeValidProperties() throws Exception {
        for(int invalid:new int[]{-2,256,65536})for(int position:new int[]{0,1}) {
            Tracked source=new Tracked(payload(2)) {
                int reads;
                @Override public synchronized int read(){return reads++==position ? invalid : super.read();}
            };
            IOException failure=assertThrows(IOException.class,()->PpmdZipInputStream.open(source,912));
            assertTrue(failure.getMessage().contains("property byte"));assertEquals(1,source.closes);
        }
    }

    @Test public void uncheckedSourceCloseFailureCanAlsoRetry() throws Exception {
        for(Throwable failure:new Throwable[]{new IllegalStateException("close"),new AssertionError("close")}) {
            Tracked source=new Tracked(payload(2)) {
                @Override public void close()throws IOException {
                    super.close();if(closes!=1)return;
                    if(failure instanceof RuntimeException)throw (RuntimeException)failure;
                    throw (Error)failure;
                }
            };
            InputStream input=PpmdZipInputStream.open(source,912);
            assertSame(failure,assertThrows(Throwable.class,input::close));
            assertThrows(IOException.class,input::read);
            input.close();input.close();assertEquals(2,source.closes);
        }
    }

    @Test public void sourceCleanupCannotRecursivelyCloseTheOwner() throws Exception {
        InputStream[] owner={null};
        Tracked source=new Tracked(payload(2)) {
            @Override public void close()throws IOException{super.close();if(closes==1)owner[0].close();}
        };
        owner[0]=PpmdZipInputStream.open(source,912);
        IOException failure=assertThrows(IOException.class,owner[0]::close);
        assertTrue(failure.getMessage().contains("callback"));
        owner[0].close();owner[0].close();assertEquals(2,source.closes);
    }

    private static byte[] payload(int entry)throws IOException {
        try(InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                .open("test_read_format_zip_ppmd8_multi.zipx.base64")) {
            byte[] archive=Base64.decode(readAll(input,1024),Base64.DEFAULT);
            return Arrays.copyOfRange(archive,OFFSETS[entry],OFFSETS[entry]+PACKED[entry]);
        }
    }
    private static byte[] readAll(InputStream input,int chunk)throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[chunk];int count;
        while((count=input.read(buffer))!=-1){assertTrue(count>0);output.write(buffer,0,count);}
        return output.toByteArray();
    }
    private static class Tracked extends ByteArrayInputStream {
        int closes;
        Tracked(byte[] bytes){super(bytes);}
        @Override public void close()throws IOException{closes++;super.close();}
    }
}
