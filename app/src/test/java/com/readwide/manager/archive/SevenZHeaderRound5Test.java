package com.readwide.manager.archive;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class SevenZHeaderRound5Test {
    @Test public void metadataArrayValidationHasNo512MiBQuota() throws Exception {
        assertEquals(513 * 1024 * 1024,
                SevenZBcj2ArchiveReader.checkedMetadataArraySize(513L * 1024 * 1024));
        assertEquals(Integer.MAX_VALUE,
                SevenZBcj2ArchiveReader.checkedMetadataArraySize(Integer.MAX_VALUE));
    }
    @Test public void metadataArrayValidationRejectsOverflowWithoutAllocation() {
        for (long size : new long[] {-1, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE}) {
            assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.checkedMetadataArraySize(size));
        }
    }
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private File file(long count,byte[] props)throws Exception{return Round5Fixtures.emptySevenZ(temp.newFolder(),count,props);}
    private void rejects(long count,byte[] props)throws Exception{File f=file(count,props);assertThrows(IOException.class,()->SevenZBcj2ArchiveReader.listEntries(f,null));}
    private byte[] named(int declared,byte[] payload)throws Exception{
        ByteArrayOutputStream p=new ByteArrayOutputStream();p.write(Round5Fixtures.emptyProperties(1,false));p.write(17);Round4Fixtures.number(p,declared);p.write(payload);return p.toByteArray();
    }
    @Test public void supports4097NamedEmptyFilesWithout64FileCap()throws Exception{
        assertEquals(4097,SevenZBcj2ArchiveReader.listEntries(file(4097,Round5Fixtures.emptyProperties(4097,true)),null).size());
    }
    @Test public void supports4097UnnamedEmptyFiles()throws Exception{
        assertEquals(4097,SevenZBcj2ArchiveReader.listEntries(file(4097,Round5Fixtures.emptyProperties(4097,false)),null).size());
    }
    @Test public void emptyArchiveWithEmptyFilesInfoIsValid()throws Exception{
        assertEquals(0,SevenZBcj2ArchiveReader.listEntries(file(0,new byte[0]),null).size());
    }
    @Test public void signedFileCountOverflowFailsAsIoBeforeAllocation()throws Exception{rejects(1L<<31,new byte[0]);}
    @Test public void wrappedFileCountCannotBecomeZeroFiles()throws Exception{rejects(1L<<32,new byte[0]);}
    @Test public void impossibleFileCountFailsBeforeArrayAllocation()throws Exception{rejects(1_000_000,new byte[0]);}
    @Test public void negativeUnsignedFileCountFailsAsIo()throws Exception{rejects(-1,new byte[0]);}
    @Test public void nameMustStayInsideItsDeclaredProperty()throws Exception{rejects(1,named(1,new byte[]{0,0,0}));}
    @Test public void oddLengthUtf16NameIsRejected()throws Exception{rejects(1,named(2,new byte[]{0,65}));}
    @Test public void unterminatedUtf16NameIsRejected()throws Exception{rejects(1,named(3,new byte[]{0,65,0}));}
    @Test public void trailingNameBytesCannotBeSilentlyIgnored()throws Exception{rejects(1,named(5,new byte[]{0,0,0,65,0}));}
    @Test public void duplicatedNamesAreRejected()throws Exception{
        byte[] one=named(3,new byte[]{0,0,0});ByteArrayOutputStream p=new ByteArrayOutputStream();p.write(one);p.write(17);Round4Fixtures.number(p,3);p.write(new byte[]{0,0,0});rejects(1,p.toByteArray());
    }
    @Test public void duplicateEmptyStreamPropertyIsRejected()throws Exception{
        ByteArrayOutputStream p=new ByteArrayOutputStream();p.write(Round5Fixtures.emptyProperties(1,true));p.write(14);Round4Fixtures.number(p,1);p.write(128);rejects(1,p.toByteArray());
    }
    @Test public void undersizedEmptyVectorCannotBorrowNextProperty()throws Exception{
        ByteArrayOutputStream p=new ByteArrayOutputStream();p.write(14);Round4Fixtures.number(p,0);p.write(128);rejects(1,p.toByteArray());
    }
    @Test public void oversizedEmptyVectorIsRejected()throws Exception{
        ByteArrayOutputStream p=new ByteArrayOutputStream();p.write(14);Round4Fixtures.number(p,2);p.write(128);p.write(0);rejects(1,p.toByteArray());
    }
    @Test public void unknownLengthDelimitedPropertyIsSkipped()throws Exception{
        ByteArrayOutputStream p=new ByteArrayOutputStream();p.write(100);Round4Fixtures.number(p,4);p.write(new byte[]{17,0,0,0});p.write(Round5Fixtures.emptyProperties(2,true));
        assertEquals(2,SevenZBcj2ArchiveReader.listEntries(file(2,p.toByteArray()),null).size());
    }
    @Test public void oversizedUnknownPropertyIsRejected()throws Exception{
        ByteArrayOutputStream p=new ByteArrayOutputStream();p.write(100);Round4Fixtures.number(p,1L<<32);rejects(1,p.toByteArray());
    }
    @Test public void missingOuterHeaderTerminatorIsRejected()throws Exception{
        ByteArrayOutputStream h=new ByteArrayOutputStream();h.write(1);h.write(5);Round4Fixtures.number(h,0);h.write(0);
        File f=Round5Fixtures.wrap(temp.newFolder(),new byte[0],h.toByteArray());assertThrows(IOException.class,()->SevenZBcj2ArchiveReader.listEntries(f,null));
    }
    @Test public void overflowingPackCountFailsAsIo()throws Exception{
        ByteArrayOutputStream h=new ByteArrayOutputStream();h.write(1);h.write(4);h.write(6);Round4Fixtures.number(h,0);Round4Fixtures.number(h,1L<<31);h.write(9);h.write(0);h.write(0);h.write(0);
        File f=Round5Fixtures.wrap(temp.newFolder(),new byte[0],h.toByteArray());assertThrows(IOException.class,()->SevenZBcj2ArchiveReader.listEntries(f,null));
    }
    @Test public void overflowingFolderCountFailsAsIo()throws Exception{
        ByteArrayOutputStream h=new ByteArrayOutputStream();h.write(1);h.write(4);h.write(7);h.write(11);Round4Fixtures.number(h,1L<<31);h.write(0);h.write(0);h.write(0);
        File f=Round5Fixtures.wrap(temp.newFolder(),new byte[0],h.toByteArray());assertThrows(IOException.class,()->SevenZBcj2ArchiveReader.listEntries(f,null));
    }
    @Test public void fuzzedShortHeadersNeverEscapeAsUncheckedParserErrors()throws Exception{
        Random random=new Random(519007);File dir=temp.newFolder();
        for(int i=0;i<500;i++){
            byte[] bytes=new byte[1+random.nextInt(95)];random.nextBytes(bytes);bytes[0]=1;
            File f=Round5Fixtures.wrap(dir,new byte[0],bytes);
            try{SevenZBcj2ArchiveReader.listEntries(f,null);}catch(IOException expected){}
        }
    }

    @Test public void directoryOnlyArchiveReportsSuccessfulExtraction()throws Exception{
        ByteArrayOutputStream props=new ByteArrayOutputStream();props.write(14);Round4Fixtures.number(props,1);props.write(128);
        byte[] names="directory/\0".getBytes(StandardCharsets.UTF_16LE);props.write(17);Round4Fixtures.number(props,names.length+1);props.write(0);props.write(names);
        File f=file(1,props.toByteArray()),out=temp.newFolder();assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(f,out,null,null,null));assertTrue(new File(out,"directory").isDirectory());
    }
    @Test public void unknownPackPropertyBeforeSizesIsSkipped()throws Exception{
        ByteArrayOutputStream h=new ByteArrayOutputStream();h.write(1);h.write(4);h.write(6);Round4Fixtures.number(h,0);Round4Fixtures.number(h,0);
        h.write(100);Round4Fixtures.number(h,3);h.write(new byte[]{0,0,0});h.write(9);h.write(0);h.write(0);h.write(5);Round4Fixtures.number(h,0);h.write(0);h.write(0);
        File f=Round5Fixtures.wrap(temp.newFolder(),new byte[0],h.toByteArray());assertEquals(0,SevenZBcj2ArchiveReader.listEntries(f,null).size());
    }
    @Test public void missingPackSizesIsRejected()throws Exception{
        ByteArrayOutputStream h=new ByteArrayOutputStream();h.write(1);h.write(4);h.write(6);Round4Fixtures.number(h,0);Round4Fixtures.number(h,0);h.write(0);h.write(0);h.write(5);Round4Fixtures.number(h,0);h.write(0);h.write(0);
        File f=Round5Fixtures.wrap(temp.newFolder(),new byte[0],h.toByteArray());assertThrows(IOException.class,()->SevenZBcj2ArchiveReader.listEntries(f,null));
    }

    @Test public void antiFileCannotReplaceExistingFileWithEmptyOutput() throws Exception {
        File archive=file(1,antiProperties(1,new byte[]{(byte)128})),out=temp.newFolder();
        File existing=new File(out,"file0");java.nio.file.Files.write(existing.toPath(),new byte[]{42});
        assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive,out,null,null,null));
        assertArrayEquals(new byte[]{42},java.nio.file.Files.readAllBytes(existing.toPath()));
    }
    @Test public void allFalseAntiBitmapIsAccepted() throws Exception {
        assertEquals(1,SevenZBcj2ArchiveReader.listEntries(file(1,antiProperties(1,new byte[]{0})),null).size());
    }
    @Test public void truncatedAntiBitmapIsRejected() throws Exception { rejects(1,antiProperties(0,new byte[0])); }
    @Test public void oversizedAntiBitmapIsRejected() throws Exception { rejects(1,antiProperties(2,new byte[]{0,0})); }
    @Test public void duplicateAntiBitmapIsRejected() throws Exception {
        ByteArrayOutputStream p=new ByteArrayOutputStream();p.write(antiProperties(1,new byte[]{0}));p.write(new byte[]{16,1,0});rejects(1,p.toByteArray());
    }
    private byte[] antiProperties(int size,byte[] bits)throws Exception{
        ByteArrayOutputStream p=new ByteArrayOutputStream();p.write(Round5Fixtures.emptyProperties(1,true));
        p.write(16);Round4Fixtures.number(p,size);p.write(bits);return p.toByteArray();
    }
    @Test public void unclaimedSolidSubstreamIsRejected() throws Exception {
        File archive=unclaimedStream(true);assertThrows(IOException.class,()->SevenZBcj2ArchiveReader.listEntries(archive,null));
    }
    @Test public void streamedArchiveWithoutFilesInfoIsRejected() throws Exception {
        File archive=unclaimedStream(false);assertThrows(IOException.class,()->SevenZBcj2ArchiveReader.listEntries(archive,null));
    }
    private File unclaimedStream(boolean filesInfo)throws Exception{
        byte[] raw={1,2};Round4Fixtures.Graph graph=Round5Fixtures.deflateGraph(raw,0,false);
        ByteArrayOutputStream h=new ByteArrayOutputStream();h.write(1);h.write(4);Round4Fixtures.streams(h,graph,0,true,false);
        if(filesInfo){h.write(5);Round4Fixtures.number(h,1);byte[] names="only.bin\0".getBytes(StandardCharsets.UTF_16LE);
            h.write(17);Round4Fixtures.number(h,names.length+1);h.write(0);h.write(names);h.write(0);}
        h.write(0);return Round5Fixtures.wrap(temp.newFolder(),graph.packed,h.toByteArray());
    }

    @Test public void canonicalHeaderlessEmptyArchiveListsNoEntries() throws Exception {
        File archive=Round5Fixtures.wrap(temp.newFolder(),new byte[0],new byte[0]);
        assertEquals(32,archive.length());assertEquals(0,SevenZBcj2ArchiveReader.listEntries(archive,null).size());
    }
    @Test public void headerlessEmptyArchiveStillChecksStartCrc() throws Exception {
        File archive=Round5Fixtures.wrap(temp.newFolder(),new byte[0],new byte[0]);
        byte[] bytes=java.nio.file.Files.readAllBytes(archive.toPath());bytes[8]^=1;java.nio.file.Files.write(archive.toPath(),bytes);
        assertThrows(IOException.class,()->SevenZBcj2ArchiveReader.listEntries(archive,null));
    }
    @Test public void headerlessEmptyArchiveRejectsNonzeroOffset() throws Exception {
        File archive=emptyWithNextFields(1,0);assertThrows(IOException.class,()->SevenZBcj2ArchiveReader.listEntries(archive,null));
    }
    @Test public void headerlessEmptyArchiveRejectsNonzeroNextCrc() throws Exception {
        File archive=emptyWithNextFields(0,1);assertThrows(IOException.class,()->SevenZBcj2ArchiveReader.listEntries(archive,null));
    }
    @Test public void unsupportedSevenZMajorVersionIsRejected() throws Exception {
        File archive=Round5Fixtures.wrap(temp.newFolder(),new byte[0],new byte[0]);
        byte[] bytes=java.nio.file.Files.readAllBytes(archive.toPath());bytes[6]=1;java.nio.file.Files.write(archive.toPath(),bytes);
        assertThrows(IOException.class,()->SevenZBcj2ArchiveReader.listEntries(archive,null));
    }
    private File emptyWithNextFields(long offset,long crc)throws Exception{
        ByteArrayOutputStream start=new ByteArrayOutputStream();Round4Fixtures.le(start,offset,8);Round4Fixtures.le(start,0,8);Round4Fixtures.le(start,crc,4);
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{'7','z',(byte)0xbc,(byte)0xaf,0x27,0x1c,0,4});
        Round4Fixtures.le(out,Round4Fixtures.crc(start.toByteArray()),4);out.write(start.toByteArray());out.write(0);
        return Round4Fixtures.write(temp.newFolder(),"empty.7z",out.toByteArray());
    }

    @Test public void extracts257NamedEmptyFilesWithout64FileCap()throws Exception{
        File f=file(257,Round5Fixtures.emptyProperties(257,true)),out=temp.newFolder();
        assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(f,out,null,null,null));assertEquals(257,out.list().length);
        for(int i=0;i<257;i++){File target=new File(out,"file"+i);assertTrue(target.isFile());assertEquals(0,target.length());}
    }
}
