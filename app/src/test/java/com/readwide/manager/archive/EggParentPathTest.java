package com.readwide.manager.archive;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Synthetic specification-derived fixtures, not producer interoperability evidence. */
public class EggParentPathTest {
    private static final int END = 0x08e28222, FILE = 0x0a8590e3, NAME = 0x0a8591ac;
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void forwardNestedIdsKeepFullUnsignedIdentityAndEntryOrder() throws Exception {
        byte[] data = {1,2,3};
        File archive = archive(false, entry(2, -1, "page.jpg", data, 0),
                directory(-1, 0, "chapter"), directory(0, null, "comic"));
        List<ArchiveSupport.EntryInfo> entries = EggArchiveReader.listEntries(archive, null);
        assertEquals(3, entries.size());
        assertEquals("comic/chapter/page.jpg", entries.get(0).path);
        assertEquals("comic/chapter", entries.get(1).path);
        assertTrue(entries.get(1).directory);
        extracted(archive, "comic/chapter/page.jpg", data);
    }

    @Test public void referencedDeflateFileUsesResolvedSelectedAndBulkPaths() throws Exception {
        byte[] data = "a page in a folder".getBytes(StandardCharsets.UTF_8);
        extracted(archive(false, directory(10, null, "comic"),
                entry(20, 10, "page.txt", data, 1)), "comic/page.txt", data);
    }

    @Test public void standardLocalePrecedesParentId() throws Exception { locale(0x18); }
    @Test public void legacyLocalePrecedesParentId() throws Exception { locale(0x30); }

    @Test public void wideExtraLengthIncludesParentId() throws Exception {
        File archive = archive(false, header(2, 0x11, concat(le(1,4), utf8("page")), 0, false),
                directory(1, null, "folder"));
        extracted(archive, "folder/page", new byte[0]);
    }

    @Test public void slashDirectoryAndBackslashChildAreNormalized() throws Exception {
        File archive = archive(false, header(1, 0, utf8("folder/"), 0, false),
                entry(2, 1, ".\\sub\\page", new byte[]{7}, 0));
        extracted(archive, "folder/sub/page", new byte[]{7});
    }

    @Test public void solidForwardReferencesDoNotReorderDecodedBytes() throws Exception {
        File archive = archive(true, header(2, 16, concat(le(1,4),utf8("first")),1,false),
                directory(1,null,"folder"),
                header(3,16,concat(le(1,4),utf8("second")),2,false), block(new byte[]{1,2,3},0));
        extracted(archive,"folder/second",new byte[]{2,3});
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = EggArchiveReader.openSolidForwardReader(archive,spool)) {
            assertEquals("folder/first",reader.nextEntry().path);
            assertEquals("folder",reader.nextEntry().path); // Drains first file, not its parent.
            assertEquals("folder/second",reader.nextEntry().path);
            byte[] data = new byte[2];
            assertEquals(2,reader.read(data));
            assertArrayEquals(new byte[]{2,3},data);
            assertNull(reader.nextEntry());
        }
        assertEquals(0,spool.list().length);
    }

    @Test public void unrelatedDuplicateIdsDoNotInvalidateUnambiguousReferences() throws Exception {
        File archive = archive(false, directory(1,null,"folder"),
                entry(2,1,"page",new byte[]{1},0), entry(7,null,"a",new byte[]{2},0),
                entry(7,null,"b",new byte[]{3},0));
        assertEquals(4,EggArchiveReader.listEntries(archive,null).size());
        extracted(archive,"folder/page",new byte[]{1});
    }

    @Test public void duplicateResolvedFilenameKeepsFirstSelectedIdentity() throws Exception {
        File archive = archive(false, directory(1,null,"folder"),
                entry(2,1,"page",new byte[]{1},0), entry(3,null,"folder/page",new byte[]{2,3},0));
        List<ArchiveSupport.EntryInfo> entries = EggArchiveReader.listEntries(archive,null);
        assertEquals(2,entries.size());
        assertEquals(1,entries.get(1).size);
        File output = temp.newFile();
        assertTrue(EggArchiveReader.extractSingleEntry(archive,"folder/page",output,null));
        assertArrayEquals(new byte[]{1},Files.readAllBytes(output.toPath()));
    }

    @Test public void missingParentFailsBeforeAnyOutputOrReplacement() throws Exception {
        File archive = archive(false,entry(1,null,"existing",new byte[]{1},0),
                entry(2,99,"page",new byte[]{2},0));
        rejects(archive);
        File output = temp.newFile(); Files.write(output.toPath(),new byte[]{42});
        assertThrows(IOException.class,() -> EggArchiveReader.extractSingleEntry(archive,"existing",output,null));
        assertArrayEquals(new byte[]{42},Files.readAllBytes(output.toPath()));
        File root = temp.newFolder(), existing = new File(root,"existing");
        Files.write(existing.toPath(),new byte[]{42});
        assertThrows(IOException.class,() -> EggArchiveReader.extractArchiveIntoDirectory(archive,root,null));
        assertArrayEquals(new byte[]{42},Files.readAllBytes(existing.toPath()));
        assertEquals(1,root.list().length);
    }

    @Test public void ambiguousParentIdIsRejected() throws Exception {
        rejects(archive(false,directory(1,null,"a"),directory(1,null,"b"),entry(2,1,"page",new byte[0],0)));
    }

    @Test public void selfReferencingDirectoryIsRejected() throws Exception {
        rejects(archive(false,directory(1,1,"loop")));
    }

    @Test public void directoryCycleIsRejected() throws Exception {
        rejects(archive(false,directory(1,2,"a"),directory(2,1,"b")));
    }

    @Test public void regularFileCannotBeAParentDirectory() throws Exception {
        rejects(archive(false,entry(1,null,"file",new byte[0],0),entry(2,1,"page",new byte[0],0)));
    }

    @Test public void incompleteParentMetadataAndMissingNameAreRejected() throws Exception {
        for (int size=0;size<=4;size++) {
            rejects(archive(false,header(1,16,new byte[size],0,false)));
        }
        for (int size=2;size<=6;size++) {
            rejects(archive(false,header(1,24,new byte[size],0,false)));
        }
    }

    @Test public void prefixingADirectoryCannotLaunderUnsafeChildPaths() throws Exception {
        for (String path : new String[]{"../outside","/absolute","C:/drive","sub/../../escape","bad\u0000name"}) {
            rejects(archive(false,directory(1,null,"folder"),entry(2,1,path,new byte[0],0)));
        }
    }

    @Test public void unsafeParentPathIsRejected() throws Exception {
        rejects(archive(false,directory(1,null,"../outside"),entry(2,1,"page",new byte[0],0)));
    }

    @Test public void deepForwardChainIsResolvedWithoutRecursiveCalls() throws Exception {
        int depth = 1500;
        byte[][] records = new byte[depth+1][];
        records[0] = entry(depth+1,depth,"page",new byte[0],0);
        for (int id=depth;id>=1;id--) {
            records[depth-id+1] = directory(id,id==1 ? null : Integer.valueOf(id-1),"d");
        }
        StringBuilder expected = new StringBuilder();
        for (int i=0;i<depth;i++) expected.append("d/");
        expected.append("page");
        assertEquals(expected.toString(),EggArchiveReader.listEntries(archive(false,records),null).get(0).path);
    }

    @Test public void expandedNamesStayWithinTheMetadataRange() throws Exception {
        char[] chars = new char[33000]; java.util.Arrays.fill(chars,'a');
        String component = new String(chars);
        rejects(archive(false,directory(1,null,component),directory(2,1,component)));
    }

    private void locale(int flags) throws Exception {
        byte[] payload = concat(le(949,2),le(0x80000001,4),"한글.txt".getBytes(Charset.forName("MS949")));
        File archive = archive(false,header(2,flags,payload,0,false),directory(0x80000001,null,"folder"));
        extracted(archive,"folder/한글.txt",new byte[0]);
    }

    private void extracted(File archive,String name,byte[] data) throws Exception {
        File output = temp.newFile();
        assertTrue(EggArchiveReader.extractSingleEntry(archive,name,output,null));
        assertArrayEquals(data,Files.readAllBytes(output.toPath()));
        File root = temp.newFolder();
        assertTrue(EggArchiveReader.extractArchiveIntoDirectory(archive,root,null));
        assertArrayEquals(data,Files.readAllBytes(new File(root,name).toPath()));
    }

    private void rejects(File archive) {
        assertThrows(IOException.class,() -> EggArchiveReader.listEntries(archive,null));
    }

    private File archive(boolean solid,byte[]... records) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(concat(le(0x41474745,4),le(0x0100,2),le(1,4),le(0,4)));
        if (solid) bytes.write(extra(0x24e5a060,0,new byte[0]));
        bytes.write(le(END,4));
        for (byte[] record : records) bytes.write(record);
        bytes.write(le(END,4));
        File file = new File(temp.newFolder(),"parent-path.egg");
        Files.write(file.toPath(),bytes.toByteArray());
        return file;
    }

    private static byte[] directory(int id,Integer parent,String name) throws IOException {
        return namedHeader(id,parent,name,0,true);
    }

    private static byte[] entry(int id,Integer parent,String name,byte[] data,int method) throws IOException {
        return concat(namedHeader(id,parent,name,data.length,false),block(data,method));
    }

    private static byte[] namedHeader(int id,Integer parent,String name,long size,boolean directory) throws IOException {
        return header(id,parent==null ? 0 : 16,parent==null ? utf8(name) : concat(le(parent,4),utf8(name)),size,directory);
    }

    private static byte[] header(int id,int flags,byte[] name,long size,boolean directory) throws IOException {
        byte[] metadata = directory ? extra(0x2c86950b,0,concat(new byte[8],new byte[]{(byte)128})) : new byte[0];
        return concat(le(FILE,4),le(id,4),le(size,8),extra(NAME,flags,name),metadata,le(END,4));
    }

    private static byte[] block(byte[] data,int method) throws IOException {
        byte[] packed = data;
        if (method==1) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION,true);
            try (DeflaterOutputStream output = new DeflaterOutputStream(bytes,deflater)) { output.write(data); }
            finally { deflater.end(); }
            packed = bytes.toByteArray();
        }
        CRC32 crc = new CRC32(); crc.update(data);
        return concat(le(0x02b50c13,4),new byte[]{(byte)method,0},le(data.length,4),le(packed.length,4),
                le(crc.getValue(),4),le(END,4),packed);
    }

    private static byte[] extra(int signature,int flags,byte[] data) throws IOException {
        return concat(le(signature,4),new byte[]{(byte)flags},le(data.length,(flags & 1)!=0 ? 4 : 2),data);
    }

    private static byte[] utf8(String name) { return name.getBytes(StandardCharsets.UTF_8); }
    private static byte[] le(long value,int count) {
        byte[] bytes = new byte[count];
        for (int i=0;i<count;i++) bytes[i]=(byte)(value >>> (8*i));
        return bytes;
    }
    private static byte[] concat(byte[]... pieces) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] piece : pieces) bytes.write(piece);
        return bytes.toByteArray();
    }
}
