package com.readwide.manager.archive;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import com.readwide.manager.util.FileOperationProgress;

/** Host contract tests use a fake payload decoder, never claim native codec coverage. */
public class CabArchiveReaderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void forwardInputCloseFailuresKeepHandleForRetry() throws Exception {
        for (Throwable failure : new Throwable[]{new IOException("close"), new IllegalStateException("close"), new AssertionError("close")}) {
            FakeDecoder decoder = fake(new String[]{"page"}, new byte[][]{{1}});
            CabForwardReader reader = new CabForwardReader(session(decoder), temporary.newFolder());
            java.lang.reflect.Field input = CabForwardReader.class.getDeclaredField("input"); input.setAccessible(true);
            int[] closes = {0};
            input.set(reader, new InputStream() {
                @Override public int read() { return -1; }
                @Override public void close() throws IOException {
                    if (++closes[0] != 1) return;
                    if (failure instanceof IOException) throw (IOException) failure;
                    if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                    throw (Error) failure;
                }
            });
            try {
                assertSame(failure, assertThrows(Throwable.class, reader::close));
                assertNotNull(input.get(reader));
                reader.close(); reader.close();
                assertNull(input.get(reader)); assertEquals(2, closes[0]);
                assertEquals(1, decoder.closes);
                assertThrows(IOException.class, reader::nextEntry);
            } finally { reader.close(); }
        }
    }

    @Test public void failedInputCloseRetainsOwnershipAndRejectsReads() throws Exception {
        for(Throwable failure:new Throwable[]{new IOException("close"),new IllegalStateException("close"),new AssertionError("close")}) {
            File[] files=parts();
            CabArchiveInputs input=new CabArchiveInputs(CabVolumeResolver.resolve(files[0]),null);
            CloseOnceFile handle=new CloseOnceFile(files[0],failure);
            installHandle(input,handle);
            try {
                assertSame(failure,assertThrows(Throwable.class,input::close));
                assertThrows(IOException.class,()->input.read(0,0,new byte[1],1));
                input.close();input.close();assertEquals(2,handle.closes);
            } finally {input.close();}
        }
    }

    @Test public void failedVolumeSwitchKeepsOldHandleForCleanup() throws Exception {
        File[] files=parts();IOException failure=new IOException("switch close");
        CabArchiveInputs input=new CabArchiveInputs(CabVolumeResolver.resolve(files[0]),null);
        CloseOnceFile handle=new CloseOnceFile(files[0],failure);
        installHandle(input,handle);
        try {
            assertSame(failure,assertThrows(IOException.class,()->input.read(1,0,new byte[1],1)));
            input.close();assertEquals(2,handle.closes);
        } finally {input.close();}
    }

    @Test public void failedSessionDecoderCloseCanRetryWithoutReopeningSession() throws Exception {
        FakeDecoder decoder=fake(new String[]{"page"},new byte[][]{{1}});
        decoder.closeFailure=new IOException("decoder cleanup");
        CabArchiveReader.Session session=session(decoder);
        try {
            assertSame(decoder.closeFailure,assertThrows(IOException.class,session::close));
            assertThrows(IOException.class,session::validate);
            assertThrows(IOException.class,session.inputs::checkpoint);
            session.close();session.close();assertEquals(2,decoder.closes);
        } finally {session.close();}
    }

    @Test public void sessionRetriesInputWithoutRepeatingSuccessfulDecoderClose() throws Exception {
        File[] files=parts();IOException failure=new IOException("input cleanup");
        FakeDecoder decoder=fake(new String[]{"page"},new byte[][]{{1}});
        CabArchiveReader.Session session=new CabArchiveReader.Session(CabVolumeResolver.resolve(files[0]),null,inputs->decoder);
        CloseOnceFile handle=new CloseOnceFile(files[0],failure);
        installHandle(session.inputs,handle);
        try {
            assertSame(failure,assertThrows(IOException.class,session::close));
            assertThrows(IOException.class,session::validate);
            session.close();session.close();assertEquals(2,handle.closes);assertEquals(1,decoder.closes);
        } finally {session.close();}
    }

    @Test public void progressCancellationStopsNameIndexWithoutThreadInterrupt() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("index", null);
        progress.cancel();
        assertThrows(InterruptedIOException.class,
                () -> CabArchiveReader.index(Arrays.asList(raw("page", 1)), progress));
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test public void cancellationDuringNameEnumerationStopsBeforeTheRest() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("index", null);
        int[] reads = {0};
        List<CabArchiveReader.RawEntry> rows = new AbstractList<CabArchiveReader.RawEntry>() {
            @Override public int size() { return 100; }
            @Override public CabArchiveReader.RawEntry get(int index) {
                reads[0]++; progress.cancel(); return raw("page" + index, 1);
            }
        };
        assertThrows(InterruptedIOException.class, () -> CabArchiveReader.index(rows, progress));
        assertEquals(1, reads[0]);
    }

    @Test public void singleByteAndBlockCallbacksProduceIdenticalPayloads() throws Exception {
        byte[] payload = {0, 127, (byte)128, (byte)255};
        for (boolean single : new boolean[]{false, true}) {
            FakeDecoder decoder = fake(new String[]{"page"}, new byte[][]{payload});
            decoder.singleByteWrites = single;
            File output = temporary.newFile();
            try (CabArchiveReader.Session session = session(decoder)) {
                assertTrue(session.extractSingle("page", output));
            }
            assertArrayEquals(payload, Files.readAllBytes(output.toPath()));
        }
    }

    @Test public void excessSingleByteCallbackRollsBackAndRetiresDecoder() throws Exception {
        File output = temporary.newFile(); Files.write(output.toPath(), new byte[]{9});
        FakeDecoder decoder = fake(new String[]{"page"}, new byte[][]{{1, 2, 3}});
        decoder.singleByteWrites = true; decoder.rows.set(0, raw("page", 2));
        try (CabArchiveReader.Session session = session(decoder)) {
            assertThrows(IOException.class, () -> session.extractSingle("page", output));
            assertTrue(decoder.closed);
        }
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(output.toPath()));
    }

    @Test public void progressCancellationBetweenByteCallbacksRestoresOutput() throws Exception {
        File output = temporary.newFile(); Files.write(output.toPath(), new byte[]{9});
        FakeDecoder decoder = fake(new String[]{"page"}, new byte[][]{{1, 2, 3}});
        decoder.singleByteWrites = true;
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        progress.setListener(snapshot -> {
            if (snapshot.doneBytes > 0 && !snapshot.cancelled) progress.cancel();
        });
        try (CabArchiveReader.Session session = new CabArchiveReader.Session(
                CabVolumeResolver.resolve(parts()[0]), progress, inputs -> decoder)) {
            assertThrows(InterruptedIOException.class, () -> session.extractSingle("page", output));
            assertTrue(decoder.closed);
        }
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(output.toPath()));
    }

    @Test public void approvedInputsSupportPositionalReadsAcrossVolumesAndDeclaredEnd() throws Exception {
        File[] files=parts();
        try(CabArchiveInputs input=new CabArchiveInputs(CabVolumeResolver.resolve(files[3]),null)) {
            byte[] buffer=new byte[100];
            for(int part:new int[]{4,0,2,0}) {
                byte[] original=Files.readAllBytes(files[part].toPath());
                assertEquals(100,input.read(part,10,buffer,100));
                assertArrayEquals(Arrays.copyOfRange(original,10,110),buffer);
                assertEquals(3,input.read(part,original.length-3,buffer,100));
                assertArrayEquals(Arrays.copyOfRange(original,original.length-3,original.length),Arrays.copyOf(buffer,3));
                assertEquals(-1,input.read(part,original.length,buffer,1));
            }
        }
    }

    @Test public void invalidInputReadsAndReadsAfterCloseFail() throws Exception {
        CabArchiveInputs input=new CabArchiveInputs(CabVolumeResolver.resolve(parts()[0]),null);
        byte[] bytes=new byte[4];
        assertThrows(IOException.class,()->input.read(-1,0,bytes,1));
        assertThrows(IOException.class,()->input.read(0,-1,bytes,1));
        assertThrows(IOException.class,()->input.read(0,Long.MAX_VALUE,bytes,1));
        assertThrows(IOException.class,()->input.read(0,0,bytes,5));
        assertEquals(0,input.read(0,0,bytes,0));
        input.close();input.close();
        assertThrows(IOException.class,()->input.read(0,0,bytes,1));
    }

    @Test public void changedSiblingInvalidatesAlreadyCachedSession() throws Exception {
        File[] files=parts();
        try(CabArchiveInputs input=new CabArchiveInputs(CabVolumeResolver.resolve(files[0]),null)) {
            input.read(0,0,new byte[4],4);
            Files.write(files[4].toPath(),new byte[500]);
            assertThrows(IOException.class,input::validate);
        }
    }

    @Test public void sourceSizeSnapshotIncludesEveryPartAndAnySelectionSharesNamespace() throws Exception {
        File[] files=parts();
        ArchiveSourceSnapshot before=ArchiveSourceSnapshot.capture(files[0]);
        for(File file:files) {
            assertEquals(before.cacheFingerprint(),ArchiveSourceSnapshot.capture(file).cacheFingerprint());
            assertTrue(before.containsFile(file));
            assertEquals(files[0].getCanonicalFile(),ArchiveSupport.normalizeExtractionQueueArchive(file));
        }
        byte[] original=Files.readAllBytes(files[4].toPath());
        Files.write(files[4].toPath(),Arrays.copyOf(original,original.length+1));
        assertFalse(before.matches(files[0]));
    }

    @Test public void explicitCabLinksTakePrecedenceOverNumericSplitNames() throws Exception {
        File[] files=parts();String oldName=files[0].getName();
        String newName=oldName.substring(0,oldName.length()-8)+".cab.001";
        byte[] second=Files.readAllBytes(files[1].toPath());
        byte[] oldBytes=oldName.getBytes(StandardCharsets.US_ASCII),newBytes=newName.getBytes(StandardCharsets.US_ASCII);
        assertEquals(oldBytes.length,newBytes.length);
        for(int i=0;i<=second.length-oldBytes.length;i++) {
            if(Arrays.equals(oldBytes,Arrays.copyOfRange(second,i,i+oldBytes.length))) {
                System.arraycopy(newBytes,0,second,i,newBytes.length);break;
            }
        }
        Files.write(files[1].toPath(),second);
        File renamed=new File(files[0].getParentFile(),newName);Files.move(files[0].toPath(),renamed.toPath());
        assertTrue(ArchiveSupport.isNumericSplitArchive(renamed));
        assertTrue(CabVolumeResolver.hasVolumeLinks(renamed));
        assertEquals(ArchiveSourceSnapshot.capture(files[4]).cacheFingerprint(),ArchiveSourceSnapshot.capture(renamed).cacheFingerprint());
        assertEquals(renamed.getCanonicalFile(),ArchiveSupport.normalizeExtractionQueueArchive(files[4]));
    }

    @Test public void firstNormalizedIdentityIsSharedByListingAndExtraction() throws Exception {
        FakeDecoder decoder=fake(new String[]{"./dir\\page.jpg","dir/page.jpg"},new byte[][]{{1,2},{9}});
        try(CabArchiveReader.Session session=session(decoder)) {
            assertEquals(1,session.entries.size());
            List<ArchiveSupport.EntryInfo> listed=session.listEntries();
            assertEquals("dir/",listed.get(0).path);
            assertEquals("dir/page.jpg",listed.get(1).path);
            assertEquals(2,listed.get(1).size);
            File target=new File(temporary.getRoot(),"selected.jpg");
            assertTrue(session.extractSingle("dir/page.jpg",target));
            assertArrayEquals(new byte[]{1,2},Files.readAllBytes(target.toPath()));
            assertEquals(Arrays.asList(0),decoder.calls);assertTrue(decoder.closed);
        }
    }

    @Test public void flaggedUtf8AndUnsafeNamesFailBeforeOutput() throws Exception {
        assertThrows(IOException.class,()->CabArchiveReader.index(Arrays.asList(new CabArchiveReader.RawEntry(new byte[]{(byte)0xff},0,0x80))));
        for(String name:new String[]{"../outside","/absolute","C:\\drive","folder/../../outside","bad\0name"}) {
            assertThrows(IOException.class,()->CabArchiveReader.index(Arrays.asList(raw(name,0))));
        }
        assertThrows(IOException.class,()->CabArchiveReader.index(Arrays.asList(raw("folder/",1))));
    }

    @Test public void missingSelectedEntryDoesNotTouchExistingTarget() throws Exception {
        File target=temporary.newFile("selected");Files.write(target.toPath(),new byte[]{7});
        try(CabArchiveReader.Session session=session(fake(new String[]{"page"},new byte[][]{{1}}))) {
            assertFalse(session.extractSingle("absent",target));
            assertArrayEquals(new byte[]{7},Files.readAllBytes(target.toPath()));
        }
    }

    @Test public void decodeFailureRestoresExistingTargetAndRetiresDecoder() throws Exception {
        File target=temporary.newFile("selected");Files.write(target.toPath(),new byte[]{7,8});
        FakeDecoder decoder=fake(new String[]{"page"},new byte[][]{{1,2,3}});
        decoder.extractFailure=new IOException("checksum mismatch");
        try(CabArchiveReader.Session session=session(decoder)) {
            assertSame(decoder.extractFailure,assertThrows(IOException.class,()->session.extractSingle("page",target)));
            assertArrayEquals(new byte[]{7,8},Files.readAllBytes(target.toPath()));
            assertTrue(decoder.closed);assertThrows(IOException.class,session::validate);
        }
    }

    @Test public void decoderCloseFailureOccursBeforeSelectedTargetCommit() throws Exception {
        File target=temporary.newFile("selected");Files.write(target.toPath(),new byte[]{7});
        FakeDecoder decoder=fake(new String[]{"page"},new byte[][]{{1,2,3}});
        decoder.closeFailure=new IOException("close failed");
        try(CabArchiveReader.Session session=session(decoder)) {
            assertSame(decoder.closeFailure,assertThrows(IOException.class,()->session.extractSingle("page",target)));
            assertArrayEquals(new byte[]{7},Files.readAllBytes(target.toPath()));
        }
    }

    @Test public void originalDecodeErrorKeepsCleanupFailureSuppressed() throws Exception {
        FakeDecoder decoder=fake(new String[]{"page"},new byte[][]{{1}});
        decoder.extractFailure=new IOException("primary");decoder.closeFailure=new IOException("cleanup");
        try(CabArchiveReader.Session session=session(decoder)) {
            IOException failure=assertThrows(IOException.class,()->session.extractSingle("page",new File(temporary.getRoot(),"selected")));
            assertSame(decoder.extractFailure,failure);
            assertTrue(Arrays.asList(failure.getSuppressed()).contains(decoder.closeFailure));
        }
    }

    @Test public void sharedOutputBudgetFailureRollsBackEvenWhenBufferFlushFails() throws Exception {
        File target=temporary.newFile("selected");Files.write(target.toPath(),new byte[]{7});
        FakeDecoder decoder=fake(new String[]{"page"},new byte[][]{{1,2,3}});
        try(CabArchiveReader.Session session=session(decoder);ArchiveExtractionByteBudget.Scope budget=ArchiveExtractionByteBudget.begin(2)) {
            assertThrows(IOException.class,()->session.extractSingle("page",target));
            assertArrayEquals(new byte[]{7},Files.readAllBytes(target.toPath()));assertTrue(decoder.closed);
        }
    }

    @Test public void shortAndExcessDecodedPayloadsCannotCommit() throws Exception {
        for(long size:new long[]{1,3}) {
            FakeDecoder decoder=fake(new String[]{"page"},new byte[][]{{1,2}});
            decoder.rows.set(0,raw("page",size));
            File target=new File(temporary.getRoot(),"selected"+size);Files.write(target.toPath(),new byte[]{7});
            try(CabArchiveReader.Session session=session(decoder)) {
                assertThrows(IOException.class,()->session.extractSingle("page",target));
                assertArrayEquals(new byte[]{7},Files.readAllBytes(target.toPath()));assertTrue(decoder.closed);
            }
        }
    }

    @Test public void bulkFileDirectoryCollisionFailsBeforeAnyDecode() throws Exception {
        FakeDecoder decoder=fake(new String[]{"part","part/page"},new byte[][]{{1},{2}});
        File directory=new File(temporary.getRoot(),"out");
        try(CabArchiveReader.Session session=session(decoder)) {
            assertThrows(IOException.class,()->session.extractAll(directory,null));
            assertTrue(decoder.calls.isEmpty());assertFalse(directory.exists());
        }
    }

    @Test public void canonicalOutputAliasesAreRejectedBeforeWriting() throws Exception {
        FakeDecoder decoder=fake(new String[]{"dir/page","dir/./page"},new byte[][]{{1},{2}});
        File directory=new File(temporary.getRoot(),"out");
        try(CabArchiveReader.Session session=session(decoder)) {
            assertThrows(IOException.class,()->session.extractAll(directory,null));
            assertTrue(decoder.calls.isEmpty());assertFalse(directory.exists());
        }
    }

    @Test public void bulkAndSingleCannotOverwriteAnyCabSourceVolume() throws Exception {
        File[] files=parts();byte[] original=Files.readAllBytes(files[4].toPath());
        FakeDecoder decoder=fake(new String[]{"harmless",files[4].getName()},new byte[][]{{1},{2}});
        try(CabArchiveReader.Session session=new CabArchiveReader.Session(CabVolumeResolver.resolve(files[0]),null,inputs->decoder)) {
            assertThrows(IOException.class,()->session.extractAll(files[0].getParentFile(),null));
            assertThrows(IOException.class,()->session.extractSingle("harmless",files[4]));
            assertTrue(decoder.calls.isEmpty());assertArrayEquals(original,Files.readAllBytes(files[4].toPath()));
        }
    }

    @Test public void cancellationBeforeOutputKeepsExistingFile() throws Exception {
        File target=temporary.newFile("selected");Files.write(target.toPath(),new byte[]{7});
        try(CabArchiveReader.Session session=session(fake(new String[]{"page"},new byte[][]{{1}}))) {
            Thread.currentThread().interrupt();
            try {assertThrows(InterruptedIOException.class,()->session.extractSingle("page",target));}
            finally{Thread.interrupted();}
            assertArrayEquals(new byte[]{7},Files.readAllBytes(target.toPath()));
        }
    }

    @Test public void factoryFailureClosesTheApprovedInputs() throws Exception {
        CabArchiveInputs[] captured={null};IOException expected=new IOException("open failed");
        File[] files=parts();
        assertSame(expected,assertThrows(IOException.class,()->new CabArchiveReader.Session(CabVolumeResolver.resolve(files[0]),null,inputs->{captured[0]=inputs;throw expected;})));
        assertThrows(IOException.class,captured[0]::checkpoint);
    }

    @Test public void viewerSkipsUnrequestedHeadersAndExtractsOnlyRequestedIndex() throws Exception {
        FakeDecoder decoder=fake(new String[]{"first.jpg","second.jpg"},new byte[][]{{1},{2,3,4}});
        File spool=temporary.newFolder("spools");
        try(CabForwardReader reader=new CabForwardReader(session(decoder),spool)) {
            assertTrue(reader.skipsUnreadEntryOnAdvance());
            assertEquals("first.jpg",reader.nextEntry().path);
            assertEquals("second.jpg",reader.nextEntry().path);
            assertTrue(decoder.calls.isEmpty());
            byte[] bytes=new byte[10];assertEquals(3,reader.read(bytes));assertEquals(-1,reader.read(bytes));
            assertArrayEquals(new byte[]{2,3,4},Arrays.copyOf(bytes,3));assertEquals(Arrays.asList(1),decoder.calls);
            assertNull(reader.nextEntry());assertEquals(0,spool.list().length);
        }
        assertTrue(decoder.closed);
    }

    @Test public void viewerFailureDoesNotPublishPartialBytesAndDeletesSpool() throws Exception {
        FakeDecoder decoder=fake(new String[]{"page.jpg"},new byte[][]{{1,2,3}});decoder.extractFailure=new IOException("bad block checksum");
        File spool=temporary.newFolder("spools");
        try(CabForwardReader reader=new CabForwardReader(session(decoder),spool)) {
            reader.nextEntry();byte[] bytes={9,9,9};
            assertThrows(IOException.class,()->reader.read(bytes));
            assertArrayEquals(new byte[]{9,9,9},bytes);assertEquals(0,spool.list().length);
            assertThrows(IOException.class,reader::nextEntry);
        }
    }

    @Test public void viewerDrainUsesRequestedRemainingBoundAndClosesOnFailure() throws Exception {
        FakeDecoder decoder=fake(new String[]{"page.jpg"},new byte[][]{{1,2,3,4}});
        File spool=temporary.newFolder("spools");
        try(CabForwardReader reader=new CabForwardReader(session(decoder),spool)) {
            reader.nextEntry();assertEquals(2,reader.read(new byte[2]));
            assertThrows(IOException.class,()->reader.drainCurrentEntry(1));
            assertEquals(0,spool.list().length);assertTrue(decoder.closed);
        }
    }

    @Test public void viewerDiscardVerifiesWithoutCreatingPayloadSpool() throws Exception {
        FakeDecoder decoder=fake(new String[]{"page.jpg"},new byte[][]{{1,2,3}});
        File spool=new File(temporary.getRoot(),"no-spools");
        try(CabForwardReader reader=new CabForwardReader(session(decoder),spool)) {
            reader.nextEntry();assertTrue(reader.drainCurrentEntry(3));
            assertEquals(Arrays.asList(0),decoder.calls);assertFalse(spool.exists());
            assertEquals(-1,reader.read(new byte[1]));
        }
    }

    private static void installHandle(CabArchiveInputs inputs,RandomAccessFile handle) throws Exception {
        java.lang.reflect.Field input=CabArchiveInputs.class.getDeclaredField("input");
        input.setAccessible(true);input.set(inputs,handle);
        java.lang.reflect.Field volume=CabArchiveInputs.class.getDeclaredField("volume");
        volume.setAccessible(true);volume.setInt(inputs,0);
    }
    private static final class CloseOnceFile extends RandomAccessFile {
        final Throwable failure;int closes;
        CloseOnceFile(File file,Throwable failure)throws IOException{super(file,"r");this.failure=failure;}
        @Override public void close()throws IOException {
            if(++closes==1) {
                if(failure instanceof IOException)throw (IOException)failure;
                if(failure instanceof RuntimeException)throw (RuntimeException)failure;
                throw (Error)failure;
            }
            super.close();
        }
    }
    private CabArchiveReader.Session session(FakeDecoder decoder) throws IOException {
        return new CabArchiveReader.Session(CabVolumeResolver.resolve(parts()[0]),null,inputs->decoder);
    }
    private File[] parts() throws IOException {
        File directory=temporary.newFolder();File[] files=new File[5];
        for(int i=0;i<files.length;i++) {
            String name="multi_basic_pt"+(i+1)+".cab";
            try(InputStream input=getClass().getResourceAsStream("/archives/cab-multipart/"+name+".base64")) {
                assertNotNull(input);ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[1024];int count;
                while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);
                files[i]=new File(directory,"cabd_"+name);Files.write(files[i].toPath(),Base64.getMimeDecoder().decode(bytes.toByteArray()));
            }
        }
        return files;
    }
    private static CabArchiveReader.RawEntry raw(String name,long size) {
        return new CabArchiveReader.RawEntry(name.getBytes(StandardCharsets.UTF_8),size,0x80);
    }
    private static FakeDecoder fake(String[] names,byte[][] bytes) {
        FakeDecoder result=new FakeDecoder(bytes);for(int i=0;i<names.length;i++)result.rows.add(raw(names[i],bytes[i].length));return result;
    }
    private static final class FakeDecoder implements CabArchiveReader.Decoder {
        final List<CabArchiveReader.RawEntry> rows=new ArrayList<>();final List<Integer> calls=new ArrayList<>();final byte[][] payloads;
        IOException extractFailure,closeFailure;boolean closed,singleByteWrites;int closes;
        FakeDecoder(byte[][] payloads){this.payloads=payloads;}
        @Override public List<CabArchiveReader.RawEntry> entries(){return rows;}
        @Override public void extract(int index,OutputStream output) throws IOException {
            if(closed)throw new IOException("decoder closed");calls.add(index);
            if(singleByteWrites) { for(byte value:payloads[index])output.write(value); }
            else output.write(payloads[index]);
            if(extractFailure!=null)throw extractFailure;
        }
        @Override public void close() throws IOException {closes++;if(closed)return;closed=true;if(closeFailure!=null)throw closeFailure;}
    }
}
