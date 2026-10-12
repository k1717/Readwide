package com.readwide.manager.archive;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Metadata discovery only. Synthetic headers are not decompression fixtures. */
public class CabVolumeResolverTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void anySelectedPartResolvesTheSameOrderedSet() throws Exception {
        File first = cab("first.cab",7,0,null,"middle.data");
        File middle = cab("middle.data",7,1,"first.cab","last.bin");
        File last = cab("last.bin",7,2,"middle.data",null);
        for (File selected : new File[]{first,middle,last}) {
            CabVolumeResolver.VolumeSet set = CabVolumeResolver.resolve(selected);
            assertEquals(Arrays.asList(first,middle,last),set.parts);
            for (int i=0;i<3;i++) assertEquals(i,set.headers.get(i).index);
        }
    }

    @Test public void standaloneCabDoesNotCollectUnrelatedNumberedFiles() throws Exception {
        File first = cab("book.cab",5,0,null,null);
        Files.write(new File(temporary.getRoot(),"book2.cab").toPath(),new byte[]{1,2});
        assertEquals(Arrays.asList(first),CabVolumeResolver.resolve(first).parts);
    }

    @Test public void uniqueCaseInsensitiveSiblingNamesAreAccepted() throws Exception {
        File first = cab("first.cab",5,0,null,"SECOND.CAB");
        File second = cab("second.cab",5,1,"FIRST.CAB",null);
        assertEquals(Arrays.asList(first,second),CabVolumeResolver.resolve(second).parts);
    }

    @Test public void missingPreviousOrNextPartFailsInsteadOfReturningPartialSet() throws Exception {
        File first = cab("first.cab",5,0,null,"absent.cab");
        File last = cab("last.cab",5,1,"absent.cab",null);
        assertThrows(IOException.class,()->CabVolumeResolver.resolve(first));
        assertThrows(IOException.class,()->CabVolumeResolver.resolve(last));
    }

    @Test public void mismatchedSetOrNonconsecutiveIndexIsRejected() throws Exception {
        for (int mode=0;mode<2;mode++) {
            File first = cab("first.cab",5,0,null,"second.cab");
            cab("second.cab",mode==0 ? 6 : 5,mode==0 ? 1 : 2,"first.cab",null);
            IOException failure = assertThrows(IOException.class,()->CabVolumeResolver.resolve(first));
            assertTrue(failure.getMessage().contains("set IDs or nonconsecutive"));
        }
    }

    @Test public void nonreciprocalLinksAreRejected() throws Exception {
        File first = cab("first.cab",5,0,null,"second.cab");
        cab("second.cab",5,1,"other.cab",null); cab("other.cab",5,0,null,null);
        assertThrows(IOException.class,()->CabVolumeResolver.resolve(first));
    }

    @Test public void linksCannotEscapeDirectoryOrUseWindowsPathAliases() throws Exception {
        for (String link : new String[]{"../outside.cab","C:\\outside.cab","part/a.cab",
                "part\\a.cab","file:stream",".","..","name.","name ","bad\nname"}) {
            File first = cab("first.cab",5,0,null,link);
            assertThrows(IOException.class,()->CabVolumeResolver.resolve(first));
        }
    }

    @Test public void circularAndImpossibleEndpointIndicesFail() throws Exception {
        File first = cab("first.cab",5,0,null,"first.cab");
        assertThrows(IOException.class,()->CabVolumeResolver.resolve(first));
        File wrongFirst = cab("bad-first.cab",5,0,"first.cab",null);
        assertThrows(IOException.class,()->CabVolumeResolver.resolve(wrongFirst));
        File wrongLast = cab("bad-last.cab",5,65535,null,"first.cab");
        assertThrows(IOException.class,()->CabVolumeResolver.resolve(wrongLast));
        File missingHead = cab("missing-head.cab",5,1,null,null);
        assertThrows(IOException.class,()->CabVolumeResolver.resolve(missingHead));
    }

    @Test public void utf8AndLegacyLinksMustMatchExactlyOneRealSibling() throws Exception {
        for (String encoding : new String[]{"UTF-8","MS949"}) {
            if (!Charset.isSupported(encoding)) continue;
            String next = "다음.cab";
            File first = cab("first.cab",5,0,null,next.getBytes(Charset.forName(encoding)),0);
            File second = cab(next,5,1,"first.cab",null);
            assertEquals(Arrays.asList(first,second),CabVolumeResolver.resolve(first).parts);
        }
    }

    @Test public void conflictingLosslessNameInterpretationsAreNotGuessed() throws Exception {
        File first = cab("first.cab",5,0,null,"é.cab".getBytes(StandardCharsets.UTF_8),0);
        cab("é.cab",5,1,"first.cab",null); cab("Ã©.cab",5,1,"first.cab",null);
        IOException failure = assertThrows(IOException.class,()->CabVolumeResolver.resolve(first));
        assertTrue(failure.getMessage().contains("Ambiguous"));
    }

    @Test public void reserveFieldsAreSkippedWithinDeclaredMetadataExtent() throws Exception {
        File first = cab("reserved.cab",19,0,null,null,37);
        CabVolumeResolver.Header header = CabVolumeResolver.resolve(first).headers.get(0);
        assertEquals(2,header.folderReserve); assertEquals(3,header.dataReserve);
        byte[] bytes = Files.readAllBytes(first.toPath()); bytes[36]=(byte)255; bytes[37]=(byte)255;
        Files.write(first.toPath(),bytes);
        assertThrows(IOException.class,()->CabVolumeResolver.resolve(first));
    }

    @Test public void unterminatedLinkCannotReadIntoFolderOrFileTables() throws Exception {
        File first = cab("first.cab",5,0,null,"second.cab");
        byte[] bytes = Files.readAllBytes(first.toPath());
        int folderStart = (bytes[16]&255)-8;
        Arrays.fill(bytes,36,folderStart,(byte)'A'); Files.write(first.toPath(),bytes);
        assertThrows(IOException.class,()->CabVolumeResolver.resolve(first));
    }

    @Test public void emptyAndOverlongLinkFieldsFailBeforeDiscovery() throws Exception {
        for (String name : new String[]{"",new String(new char[256]).replace('\0','x')}) {
            File first = cab("first.cab",5,0,null,name);
            assertThrows(IOException.class,()->CabVolumeResolver.resolve(first));
        }
    }

    @Test public void malformedFixedHeaderAndTableExtentsAreRejected() throws Exception {
        for (int offset : new int[]{0,4,12,20,24,25,30,8,16,26,28}) {
            File first = cab("first.cab",5,0,null,null);
            byte[] bytes = Files.readAllBytes(first.toPath()); bytes[offset]=(byte)255;
            Files.write(first.toPath(),bytes);
            assertThrows(IOException.class,()->CabVolumeResolver.resolve(first));
        }
        File shortFile = temporary.newFile("short.cab"); Files.write(shortFile.toPath(),new byte[35]);
        assertThrows(IOException.class,()->CabVolumeResolver.resolve(shortFile));
    }

    @Test public void changedOrRemovedPartInvalidatesMetadataSnapshot() throws Exception {
        File first = cab("first.cab",5,0,null,"second.cab");
        File second = cab("second.cab",5,1,"first.cab",null);
        CabVolumeResolver.VolumeSet set = CabVolumeResolver.resolve(first);
        byte[] original = Files.readAllBytes(second.toPath());
        Files.write(second.toPath(),Arrays.copyOf(original,original.length+1));
        assertThrows(IOException.class,set::requireUnchanged);
        Files.delete(second.toPath()); assertThrows(IOException.class,set::requireUnchanged);
    }

    @Test public void resultListsCannotBeReorderedOrReplacedByCaller() throws Exception {
        File first = cab("first.cab",5,0,null,null);
        CabVolumeResolver.VolumeSet set = CabVolumeResolver.resolve(first);
        assertThrows(UnsupportedOperationException.class,()->set.parts.add(first));
        assertThrows(UnsupportedOperationException.class,()->set.headers.clear());
    }

    @Test public void cancellationIsTerminalDuringMetadataDiscovery() throws Exception {
        File first = cab("first.cab",5,0,null,null);
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedIOException.class,()->CabVolumeResolver.resolve(first)); }
        finally { Thread.interrupted(); }
    }

    @Test public void symlinkToOutsideSiblingDirectoryIsRejectedWhenSupported() throws Exception {
        File first = cab("first.cab",5,0,null,"linked.cab");
        File outsideDirectory = temporary.newFolder("outside");
        File outside = new File(outsideDirectory,"second.cab"); Files.write(outside.toPath(),new byte[36]);
        File link = new File(temporary.getRoot(),"linked.cab");
        try { Files.createSymbolicLink(link.toPath(),outside.toPath()); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            org.junit.Assume.assumeNoException(unavailable);
        }
        assertThrows(IOException.class,()->CabVolumeResolver.resolve(first));
        assertThrows(IOException.class,()->CabVolumeResolver.resolve(link));
    }

    @Test public void pinnedUpstreamFivePartHeadersResolveFromEverySelection() throws Exception {
        File[] files = new File[5];
        for (int i=0;i<files.length;i++) {
            String name = "multi_basic_pt"+(i+1)+".cab";
            byte[] encoded;
            try (InputStream input = getClass().getResourceAsStream("/archives/cab-multipart/"+name+".base64")) {
                assertNotNull(input); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024]; int count;
                while ((count=input.read(buffer))!=-1) bytes.write(buffer,0,count);
                encoded = bytes.toByteArray();
            }
            // Upstream stores shorter resource names; embedded links include cabd_.
            files[i] = new File(temporary.getRoot(),"cabd_"+name).getCanonicalFile();
            Files.write(files[i].toPath(),Base64.getMimeDecoder().decode(encoded));
        }
        for (File file : files) {
            CabVolumeResolver.VolumeSet set = CabVolumeResolver.resolve(file);
            assertEquals(Arrays.asList(files),set.parts);
            for (int i=0;i<5;i++) { assertEquals(12345,set.headers.get(i).setId); assertEquals(i,set.headers.get(i).index); }
        }
    }

    private File cab(String name,int set,int index,String previous,String next) throws IOException {
        return cab(name,set,index,previous==null?null:previous.getBytes(StandardCharsets.UTF_8),
                next==null?null:next.getBytes(StandardCharsets.UTF_8),0);
    }

    private File cab(String name,int set,int index,byte[] previous,byte[] next,int reserve) throws IOException {
        ByteArrayOutputStream variable = new ByteArrayOutputStream();
        int flags = (previous==null?0:1) | (next==null?0:2) | (reserve==0?0:4);
        if (reserve>0) {
            variable.write(reserve&255); variable.write(reserve>>>8); variable.write(2); variable.write(3);
            for (int i=0;i<reserve;i++) variable.write(0);
        }
        if (previous!=null) { variable.write(previous); variable.write(0); variable.write(0); }
        if (next!=null) { variable.write(next); variable.write(0); variable.write(0); }
        int fileOffset = 36+variable.size()+8+(reserve==0?0:2);
        byte[] bytes = new byte[fileOffset+17];
        put(bytes,0,0x4643534d,4); put(bytes,8,bytes.length,4); put(bytes,16,fileOffset,4);
        bytes[24]=3; bytes[25]=1; put(bytes,26,1,2); put(bytes,28,1,2);
        put(bytes,30,flags,2); put(bytes,32,set,2); put(bytes,34,index,2);
        System.arraycopy(variable.toByteArray(),0,bytes,36,variable.size());
        File file = new File(temporary.getRoot(),name).getCanonicalFile(); Files.write(file.toPath(),bytes); return file;
    }

    private static void put(byte[] bytes,int offset,int value,int count) {
        for (int i=0;i<count;i++) bytes[offset+i]=(byte)(value>>>(i*8));
    }
}
