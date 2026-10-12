package com.readwide.codecs;

import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Source only until an Android run is explicitly authorized. */
@RunWith(AndroidJUnit4.class)
public class CabNativeDecoderInstrumentedTest {
    @Test public void storedFivePartSetExtractsExactBytesInForwardAndBackwardOrder() throws Exception {
        MemorySource source = multipart();
        try (CabNativeDecoder decoder = open(source,12345)) {
            assertEquals(3,decoder.entries().size());
            String[] expected = {line(1)+line(2),line(3),line(4)+line(5)};
            for (int i : new int[]{0,1,2,2,0,1}) {
                CabNativeDecoder.Entry entry = decoder.entries().get(i);
                assertEquals("test"+(i+1)+".txt",new String(entry.nameBytes(),StandardCharsets.US_ASCII));
                assertEquals(expected[i].length(),entry.length);
                assertEquals(0,entry.folderIndex);
                assertEquals(expected[i],new String(extract(decoder,i),StandardCharsets.US_ASCII));
            }
        }
    }

    @Test public void pinnedMszipLzxAndQuantumPayloadsMatchUpstreamDigests() throws Exception {
        MemorySource source = codecs();
        String[] names = {"mszip.txt","lzx.txt","qtm.txt"};
        String[] hashes = {"940cba86658fbceb582faecd2b5975d1","703474293b614e7110b3eb8ac2762b53",
                "98fcfa4962a0f169a3c7fdbcb445cf17"};
        try (CabNativeDecoder decoder = open(source,0)) {
            assertEquals(3,decoder.entries().size());
            for (int i : new int[]{2,0,1,0,2,1}) {
                assertEquals(names[i],new String(decoder.entries().get(i).nameBytes(),StandardCharsets.US_ASCII));
                // MD5 is only the upstream regression oracle, not a security check.
                assertEquals(hashes[i],hex(MessageDigest.getInstance("MD5").digest(extract(decoder,i))));
            }
        }
    }

    @Test public void partialPositiveSourceReadsAreFilledWithoutTreatingThemAsEof() throws Exception {
        MemorySource source = multipart(); source.maximumRead = 3;
        try (CabNativeDecoder decoder = open(source,12345)) {
            assertEquals(line(4)+line(5),new String(extract(decoder,2),StandardCharsets.US_ASCII));
        }
    }

    @Test public void missingReorderedAndMismatchedPartsCannotOpen() throws Exception {
        MemorySource source = multipart();
        assertThrows(IOException.class,()->open(new MemorySource(Arrays.copyOf(source.parts,4)),12345));
        byte[] swap=source.parts[1]; source.parts[1]=source.parts[2]; source.parts[2]=swap;
        assertThrows(IOException.class,()->open(source,12345));
        MemorySource wrong=multipart(); wrong.parts[2][32]^=1;
        assertThrows(IOException.class,()->open(wrong,12345));
    }

    @Test public void atypicalFileTableCannotSelectDifferentMetadata() throws Exception {
        MemorySource source=codecs(); source.parts[0][16]++;
        IOException failure=assertThrows(IOException.class,()->open(source,0));
        assertTrue(failure.getMessage().contains("file-table offset"));
    }

    @Test public void partlyMatchingContinuationTablesMustNotMerge() throws Exception {
        for (boolean rename : new boolean[]{false,true}) {
            MemorySource source=multipart();
            int first=u32(source.parts[1],16);
            if(rename)source.parts[1][first+16]='x';
            else source.parts[1][first]^=1;
            // Other entries still match; upstream's permissive merge accepted that.
            assertThrows(IOException.class,()->open(source,12345));
        }
    }

    @Test public void changedContinuationNameEncodingIsNotSilentlyReinterpreted() throws Exception {
        MemorySource source=multipart();
        source.parts[1][u32(source.parts[1],16)+14]^=(byte)0x80;
        assertThrows(IOException.class,()->open(source,12345));
    }

    @Test public void extraContinuationRecordsCannotDisappearDuringMerge() throws Exception {
        MemorySource source=multipart(); byte[] old=source.parts[1];
        int first=u32(old,16), folder=first-8, data=u32(old,folder);
        int record=16; while(old[first+record++]!=0) { }
        byte[] extended=new byte[old.length+record];
        System.arraycopy(old,0,extended,0,data);
        System.arraycopy(old,first,extended,data,record);
        System.arraycopy(old,data,extended,data+record,old.length-data);
        put32(extended,8,extended.length); put32(extended,folder,data+record);
        extended[28]++; source.parts[1]=extended;
        assertThrows(IOException.class,()->open(source,12345));
    }

    @Test public void invalidVersionAndReservedFieldsAreRejected() throws Exception {
        for(int offset:new int[]{4,12,20,24,25,30}) {
            MemorySource source=codecs(); source.parts[0][offset]=(byte)255;
            assertThrows(IOException.class,()->open(source,0));
        }
    }

    @Test public void unsignedEntryLengthIsListedWithoutAllocatingThePayload() throws Exception {
        MemorySource source=codecs(); int fileOffset=u32(source.parts[0],16);
        source.parts[0][fileOffset]=0; source.parts[0][fileOffset+1]=0;
        source.parts[0][fileOffset+2]=0; source.parts[0][fileOffset+3]=(byte)128;
        try(CabNativeDecoder decoder=open(source,0)) {assertEquals(0x80000000L,decoder.entries().get(0).length);}
    }

    @Test public void nonzeroDataChecksumFailureRetiresTheSession() throws Exception {
        MemorySource source=codecs(); source.parts[0][u32(source.parts[0],36)]^=1;
        try(CabNativeDecoder decoder=open(source,0)) {
            IOException failure=assertThrows(IOException.class,()->extract(decoder,0));
            assertTrue(failure.getMessage().contains("checksum"));
            assertThrows(IOException.class,()->extract(decoder,1));
        }
    }

    @Test public void sourceExceptionIdentitySurvivesNativeCleanup() throws Exception {
        MemorySource source=multipart(); IOException expected=new IOException("source unavailable");
        try(CabNativeDecoder decoder=open(source,12345)) {
            source.readFailure=expected;
            assertSame(expected,assertThrows(IOException.class,()->extract(decoder,0)));
            source.readFailure=null;
            assertThrows(IOException.class,()->extract(decoder,0));
        }
    }

    @Test public void zeroAndPrematureEofReadsFailWithoutLooping() throws Exception {
        for(int result:new int[]{0,-1}) {
            MemorySource source=multipart();
            try(CabNativeDecoder decoder=open(source,12345)) {
                source.forcedRead=result;
                assertThrows(IOException.class,()->extract(decoder,0));
            }
        }
    }

    @Test public void cancellationInsideNativeReadPreservesItsType() throws Exception {
        MemorySource source=multipart();
        InterruptedIOException expected=new InterruptedIOException("cancelled");
        try(CabNativeDecoder decoder=open(source,12345)) {
            source.readFailure=expected;
            assertSame(expected,assertThrows(InterruptedIOException.class,()->extract(decoder,0)));
        }
    }

    @Test public void failingGuardedOutputIsNotSwallowedOrClosed() throws Exception {
        MemorySource source=multipart(); IOException expected=new IOException("free-space reserve");
        boolean[] closed={false};
        OutputStream output=new OutputStream() {
            @Override public void write(int value) throws IOException {throw expected;}
            @Override public void write(byte[] b,int offset,int count) throws IOException {throw expected;}
            @Override public void close() {closed[0]=true;}
        };
        try(CabNativeDecoder decoder=open(source,12345)) {
            assertSame(expected,assertThrows(IOException.class,()->decoder.extract(0,output)));
            assertFalse(closed[0]);
            assertThrows(IOException.class,()->extract(decoder,0));
        }
    }

    @Test public void uncheckedOutputFailureAlsoRetiresTheSession() throws Exception {
        MemorySource source=multipart(); AssertionError expected=new AssertionError("output failed");
        try(CabNativeDecoder decoder=open(source,12345)) {
            OutputStream output=new OutputStream() {@Override public void write(int value){throw expected;}};
            assertSame(expected,assertThrows(AssertionError.class,()->decoder.extract(0,output)));
            assertThrows(IOException.class,()->extract(decoder,0));
        }
    }

    @Test public void reentrantCloseCannotFreeAnActiveNativeSession() throws Exception {
        MemorySource source=multipart();
        try(CabNativeDecoder decoder=open(source,12345)) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            OutputStream output=new OutputStream() {
                @Override public void write(int value) throws IOException {
                    assertThrows(IOException.class,decoder::close); bytes.write(value);
                }
            };
            decoder.extract(0,output);
            assertEquals(line(1)+line(2),bytes.toString("US-ASCII"));
            assertEquals(line(3),new String(extract(decoder,1),StandardCharsets.US_ASCII));
        }
    }

    @Test public void entryMetadataCannotBeMutatedByTheCaller() throws Exception {
        try(CabNativeDecoder decoder=open(multipart(),12345)) {
            assertThrows(UnsupportedOperationException.class,()->decoder.entries().clear());
            byte[] name=decoder.entries().get(0).nameBytes(); name[0]='x';
            assertEquals("test1.txt",new String(decoder.entries().get(0).nameBytes(),StandardCharsets.US_ASCII));
        }
    }

    @Test public void repeatedCloseIsSafeAndExtractionAfterCloseFails() throws Exception {
        CabNativeDecoder decoder=open(multipart(),12345);
        decoder.close(); decoder.close();
        assertThrows(IOException.class,()->extract(decoder,0));
    }

    private static CabNativeDecoder open(MemorySource source,int set) throws IOException {
        long[] lengths=new long[source.parts.length];
        for(int i=0;i<lengths.length;i++) lengths[i]=source.parts[i].length;
        return new CabNativeDecoder(source,set,lengths);
    }
    private static byte[] extract(CabNativeDecoder decoder,int index) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); decoder.extract(index,bytes); return bytes.toByteArray();
    }
    private static String line(int part){return "This is the data from cabinet part "+part+".\n";}
    private static int u32(byte[] bytes,int offset) {
        return (bytes[offset]&255)|((bytes[offset+1]&255)<<8)|((bytes[offset+2]&255)<<16)|((bytes[offset+3]&255)<<24);
    }
    private static void put32(byte[] bytes,int offset,int value) {
        for(int i=0;i<4;i++)bytes[offset+i]=(byte)(value>>>(8*i));
    }
    private static String hex(byte[] bytes) {
        StringBuilder text=new StringBuilder(); for(byte value:bytes)text.append(String.format(java.util.Locale.ROOT,"%02x",value&255)); return text.toString();
    }
    private static MemorySource multipart() throws IOException {
        byte[][] parts=new byte[5][]; for(int i=0;i<5;i++)parts[i]=fixture("multi_basic_pt"+(i+1)+".cab"); return new MemorySource(parts);
    }
    private static MemorySource codecs() throws IOException {return new MemorySource(new byte[][]{fixture("mszip_lzx_qtm.cab")});}
    private static byte[] fixture(String name) throws IOException {
        try(InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name+".base64")) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[1024];int count;
            while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);
            return Base64.decode(bytes.toByteArray(),Base64.DEFAULT);
        }
    }
    private static final class MemorySource implements CabNativeDecoder.Source {
        final byte[][] parts; int maximumRead=Integer.MAX_VALUE; Integer forcedRead; IOException readFailure;
        MemorySource(byte[][] parts){this.parts=parts;}
        @Override public int read(int volume,long offset,byte[] target,int count) throws IOException {
            if(readFailure!=null)throw readFailure;
            if(forcedRead!=null)return forcedRead;
            if(offset>=parts[volume].length)return -1;
            int length=Math.min(Math.min(count,maximumRead),parts[volume].length-(int)offset);
            System.arraycopy(parts[volume],(int)offset,target,0,length);return length;
        }
        @Override public void checkpoint() throws IOException {
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("cancelled");
        }
    }
}
