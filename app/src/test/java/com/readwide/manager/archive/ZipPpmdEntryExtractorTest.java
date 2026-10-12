package com.readwide.manager.archive;

import com.readwide.codecs.PpmdZipInputStream;
import com.readwide.codecs.PpmdZipInputStream.FreezeRevision;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Selection/ownership policy only: injected decoded bytes are not PPMd fixtures. Unrun. */
public class ZipPpmdEntryExtractorTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final byte[] OLD={9,8,7}, DATA={1,2,3,4};

    @Test public void identicalFreezeResultsNeedOneModelAndOneMaterializedFile() throws Exception {
        byte[] bytes=new byte[131075];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)i;
        Harness h=new Harness(bytes,bytes);File out=output();
        try(ArchiveExtractionByteBudget.Scope budget=ArchiveExtractionByteBudget.begin(Long.MAX_VALUE)) {
            assertTrue(h.extract(out,bytes.length,crc(bytes)));
            assertEquals(bytes.length,budget.budgetForTest().totalBytesForTest());
        }
        assertArrayEquals(bytes,Files.readAllBytes(out.toPath()));
        assertEquals(Arrays.asList(FreezeRevision.REVISION_2,FreezeRevision.REVISION_1),h.profiles);
        assertEquals(1,h.maxLive);assertEquals(0,h.live);assertEquals(2,h.finishes);assertEquals(2,h.closes);
    }

    @Test public void restartAndCutoffDoNotProbeFreezeRevisions() throws Exception {
        for(int high:new int[]{0,0x10}) {
            Harness h=new Harness(DATA,DATA);h.high=high;
            assertTrue(h.extract(output(),4,crc(DATA)));assertEquals(1,h.opens);
            assertNull(h.profiles.get(0));assertEquals(1,h.finishes);
        }
    }

    @Test public void crcRejectsOneRevisionWithoutCountingItsOutputTwice() throws Exception {
        for(boolean firstMatches:new boolean[]{true,false}) {
            Harness h=new Harness(firstMatches?DATA:new byte[]{4,3,2,1},firstMatches?new byte[]{4,3,2,1}:DATA);
            File out=output();
            try(ArchiveExtractionByteBudget.Scope budget=ArchiveExtractionByteBudget.begin(Long.MAX_VALUE)) {
                assertTrue(h.extract(out,4,crc(DATA)));assertEquals(4,budget.budgetForTest().totalBytesForTest());
            }
            assertArrayEquals(DATA,Files.readAllBytes(out.toPath()));assertEquals(2,h.finishes);
        }
    }

    @Test public void pureDecoderRejectionCanSelectEitherRevision() throws Exception {
        for(int rejected:new int[]{0,1}) {
            Harness h=new Harness(DATA,DATA);h.decodeFailure[rejected]=new PpmdZipInputStream.DataException("range");
            File out=output();assertTrue(h.extract(out,4,-1));assertArrayEquals(DATA,Files.readAllBytes(out.toPath()));
            assertEquals(2,h.opens);assertEquals(2,h.finishes);assertEquals(2,h.closes);
        }
    }

    @Test public void bothInvalidRevisionsRestoreOriginalAndRemoveNewTarget() throws Exception {
        for(boolean existing:new boolean[]{true,false}) {
            Harness h=new Harness(DATA,DATA);
            h.decodeFailure[0]=new PpmdZipInputStream.DataException("first");
            h.decodeFailure[1]=new PpmdZipInputStream.DataException("second");
            File out=existing?output():new File(temporary.newFolder(),"new");
            assertThrows(ZipPpmdEntryExtractor.ExtractionException.class,()->h.extract(out,4,-1));
            if(existing)assertArrayEquals(OLD,Files.readAllBytes(out.toPath()));else assertFalse(out.exists());
            assertEquals(2,h.finishes);assertEquals(2,h.closes);
        }
    }

    @Test public void conflictingValidAe2OutputsAreNotChosenByRevisionPreference() throws Exception {
        Harness h=new Harness(DATA,new byte[]{4,3,2,1});File out=output();
        IOException error=assertThrows(ZipPpmdEntryExtractor.ExtractionException.class,()->h.extract(out,4,-1));
        assertTrue(error.getCause().getMessage().contains("Ambiguous"));
        assertArrayEquals(OLD,Files.readAllBytes(out.toPath()));assertEquals(2,h.finishes);
    }

    @Test public void sourceDataExceptionIsNotMistakenForDecoderRejection() throws Exception {
        Harness h=new Harness(DATA,DATA);PpmdZipInputStream.DataException fault=new PpmdZipInputStream.DataException("source");
        h.sourceFailure=fault;File out=output();
        IOException error=assertThrows(ZipPpmdEntryExtractor.ExtractionException.class,()->h.extract(out,4,-1));
        assertSame(fault,error.getCause());assertEquals(1,h.opens);assertEquals(1,h.closes);
        assertArrayEquals(OLD,Files.readAllBytes(out.toPath()));
    }

    @Test public void wrappedOrSuppressedFaultOnDecoderRejectionIsTerminal() throws Exception {
        for(boolean suppressed:new boolean[]{true,false}) {
            Harness h=new Harness(DATA,DATA);PpmdZipInputStream.DataException error=new PpmdZipInputStream.DataException("decoder");
            if(suppressed)error.addSuppressed(new IOException("cleanup"));else error.initCause(new IOException("source"));
            h.decodeFailure[0]=error;File out=output();
            assertSame(error,assertThrows(ZipPpmdEntryExtractor.ExtractionException.class,()->h.extract(out,4,-1)).getCause());
            assertEquals(1,h.opens);assertArrayEquals(OLD,Files.readAllBytes(out.toPath()));
        }
    }

    @Test public void authenticationMustSucceedEvenWhenTheDecoderRejects() throws Exception {
        Harness h=new Harness(DATA,DATA);h.decodeFailure[0]=new PpmdZipInputStream.DataException("range");
        h.finishFailure=new IOException("bad MAC");File out=output();
        assertSame(h.finishFailure,assertThrows(ZipPpmdEntryExtractor.ExtractionException.class,()->h.extract(out,4,-1)).getCause());
        assertEquals(1,h.opens);assertEquals(1,h.finishes);assertEquals(1,h.closes);
        assertArrayEquals(OLD,Files.readAllBytes(out.toPath()));
    }

    @Test public void decoderAndPayloadCloseFaultsDoNotAllowRetryOrCommit() throws Exception {
        for(boolean decoderClose:new boolean[]{true,false})for(boolean rejected:new boolean[]{true,false}) {
            Harness h=new Harness(DATA,DATA);IOException close=new IOException("close");
            if(decoderClose)h.decoderCloseFailure=close;else h.payloadCloseFailure=close;
            if(rejected)h.decodeFailure[0]=new PpmdZipInputStream.DataException("range");
            File out=output();assertSame(close,assertThrows(ZipPpmdEntryExtractor.ExtractionException.class,()->h.extract(out,4,-1)).getCause());
            assertEquals(1,h.opens);assertEquals(1,h.closes);assertArrayEquals(OLD,Files.readAllBytes(out.toPath()));
        }
    }

    @Test public void sourceChangeBeforeSecondRevisionRestoresOriginal() throws Exception {
        Harness h=new Harness(DATA,DATA);h.changeAt=4;File out=output();
        assertThrows(ZipPpmdEntryExtractor.ExtractionException.class,()->h.extract(out,4,-1));
        assertEquals(1,h.opens);assertArrayEquals(OLD,Files.readAllBytes(out.toPath()));
    }

    @Test public void secondRevisionAuthenticationFailureCannotAcceptTheFirstResult() throws Exception {
        Harness h=new Harness(DATA,DATA);h.finishFailure=new IOException("second MAC");h.finishFailureAt=2;
        File out=output();
        assertSame(h.finishFailure,assertThrows(ZipPpmdEntryExtractor.ExtractionException.class,()->h.extract(out,4,-1)).getCause());
        assertEquals(2,h.opens);assertEquals(2,h.finishes);assertArrayEquals(OLD,Files.readAllBytes(out.toPath()));
    }

    @Test public void propertiesCannotChangeBetweenRevisionAttempts() throws Exception {
        for(int high:new int[]{0,0x21}) {
            Harness h=new Harness(DATA,DATA);h.secondHigh=high;File out=output();
            assertThrows(ZipPpmdEntryExtractor.ExtractionException.class,()->h.extract(out,4,-1));
            assertEquals(2,h.opens);assertEquals(1,h.profiles.size());assertEquals(2,h.closes);
            assertArrayEquals(OLD,Files.readAllBytes(out.toPath()));
        }
    }

    @Test public void cancellationAfterDecodeReadStopsBeforeWritingOrRetry() throws Exception {
        Harness h=new Harness(DATA,DATA);h.interruptRead=true;File out=output();
        try {
            assertThrows(InterruptedIOException.class,()->h.extract(out,4,-1));assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals(1,h.opens);assertArrayEquals(OLD,Files.readAllBytes(out.toPath()));
    }

    @Test public void declaredSizeAndOutputBudgetRemainMandatory() throws Exception {
        for(long size:new long[]{3,5}) {
            Harness h=new Harness(DATA,DATA);File out=output();
            assertThrows(ZipPpmdEntryExtractor.ExtractionException.class,()->h.extract(out,size,-1));
            assertArrayEquals(OLD,Files.readAllBytes(out.toPath()));
        }
        Harness h=new Harness(DATA,DATA);File out=output();
        try(ArchiveExtractionByteBudget.Scope ignored=ArchiveExtractionByteBudget.begin(2)) {
            assertThrows(ZipPpmdEntryExtractor.ExtractionException.class,()->h.extract(out,4,-1));
        }
        assertEquals(1,h.opens);assertArrayEquals(OLD,Files.readAllBytes(out.toPath()));
    }

    private File output() throws Exception { File out=temporary.newFile();Files.write(out.toPath(),OLD);return out; }
    private static long crc(byte[] bytes) { CRC32 crc=new CRC32();crc.update(bytes);return crc.getValue(); }
    private static final class Harness {
        final byte[][] bytes;
        final IOException[] decodeFailure=new IOException[2];
        final List<FreezeRevision> profiles=new ArrayList<>();
        IOException sourceFailure,finishFailure,decoderCloseFailure,payloadCloseFailure;
        int high=0x20,secondHigh=-1,finishFailureAt=1,opens,finishes,closes,checks,changeAt,live,maxLive;
        boolean interruptRead;
        Harness(byte[] first,byte[] second) { bytes=new byte[][]{first,second}; }
        boolean extract(File out,long size,long crc) throws IOException {
            return ZipPpmdEntryExtractor.extract(() -> {
                opens++;
                int property=opens==2 && secondHigh>=0 ? secondHigh : high;
                InputStream packed=new InputStream() {
                    int offset;
                    @Override public int read() throws IOException {
                        if(offset++==0)return 7;
                        if(offset==2)return property;
                        if(sourceFailure!=null)throw sourceFailure;
                        return -1;
                    }
                    @Override public void close() throws IOException { closes++;if(payloadCloseFailure!=null)throw payloadCloseFailure; }
                };
                return new ZipPpmdEntryExtractor.Payload(packed,()->{finishes++;if(finishFailure!=null && finishes==finishFailureAt)throw finishFailure;});
            },()->{if(++checks==changeAt)throw new IOException("source changed");},out,size,crc,null,(packed,expected,revision)->{
                int index=profiles.size();profiles.add(revision);
                assertEquals(7,packed.read());assertEquals(high,packed.read());
                live++;maxLive=Math.max(maxLive,live);
                return new InputStream() {
                    final ByteArrayInputStream decoded=new ByteArrayInputStream(bytes[index]);
                    @Override public int read() throws IOException { byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255; }
                    @Override public int read(byte[] b,int off,int len) throws IOException {
                        if(sourceFailure!=null)packed.read();
                        if(decodeFailure[index]!=null)throw decodeFailure[index];
                        int n=decoded.read(b,off,Math.min(len,index==0?len:7));
                        if(interruptRead)Thread.currentThread().interrupt();return n;
                    }
                    @Override public void close() throws IOException { live--;packed.close();if(decoderCloseFailure!=null)throw decoderCloseFailure; }
                };
            });
        }
    }
}
