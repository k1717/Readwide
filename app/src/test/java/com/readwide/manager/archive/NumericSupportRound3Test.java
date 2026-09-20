package com.readwide.manager.archive;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class NumericSupportRound3Test {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 private List<File> split(String name,byte[] bytes,int step)throws Exception {
  List<File> files=new ArrayList<>();int n=1;
  for(int p=0;p<bytes.length;p+=step){File f=temp.newFile(String.format(Locale.ROOT,"%s.%03d",name,n++));Files.write(f.toPath(),Arrays.copyOfRange(bytes,p,Math.min(bytes.length,p+step)));files.add(f);}
  return files;
 }
 private byte[] content(){byte[] b=new byte[16000];new Random(319).nextBytes(b);return b;}
 private File merge(List<File> files)throws Exception{return NumericSplitArchiveMerger.merge(files,temp.getRoot(),d->Long.MAX_VALUE);}
 @Test public void laterPartsRecognizeEveryExistingNumericArchiveFamily(){
  String[] names={"x.zip","x.cbz","x.rar","x.7z","x.cb7","x.tar","x.tar.gz","x.tar.bz2","x.tar.xz","x.tar.zst","x.tar.lz4","x.gz","x.bz2","x.xz","x.egg","x.alz","x.cab"};
  for(String n:names){assertNotNull(ArchiveTypeDetector.fromFileName(n));assertEquals(ArchiveTypeDetector.fromFileName(n),ArchiveTypeDetector.fromFileName(n+".002"));assertEquals(ArchiveTypeDetector.fromFileName(n),ArchiveTypeDetector.fromFileName(n+".1000"));}
 }
 @Test public void unknownAndMalformedSuffixesStayUnsupported(){
  for(String n:new String[]{"notes.txt.002","x.zip.000","x.zip.0002","x.zip.2147483648","x.zip.001.002"})assertNull(n,ArchiveTypeDetector.fromFileName(n));
 }
 @Test public void outputNamesStripLaterAndWideNumericParts(){
  assertEquals("x",ArchiveTypeDetector.outputBaseName(new File("x.tar.gz.003"),"f"));
  assertEquals("x",ArchiveTypeDetector.outputBaseName(new File("x.zip.1000"),"f"));
 }
 @Test public void nestedNumericSuffixesDoNotRecurse(){assertNull(ArchiveTypeDetector.fromFileName("x.zip"+".001".repeat(10000)));}
 @Test public void realZipReassemblesFromLaterSelectedVolume()throws Exception {
  byte[] payload=content();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  try(ZipOutputStream zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry("folder/page.bin"));zip.write(payload);zip.closeEntry();}
  List<File> parts=split("comic.zip",bytes.toByteArray(),137);
  SevenZSplitVolumeResolver.VolumeSet set=SevenZSplitVolumeResolver.resolveNumeric(parts.get(7));
  File joined=merge(set.parts);
  try(ZipInputStream zip=new ZipInputStream(new FileInputStream(joined))){assertEquals("folder/page.bin",zip.getNextEntry().getName());assertArrayEquals(payload,zip.readAllBytes());assertNull(zip.getNextEntry());}
  finally{joined.delete();}
 }
 @Test public void realConcatenatedGzipMembersReassembleExactly()throws Exception {
  byte[] a=content(),b="second member".getBytes("UTF-8");ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  try(GZIPOutputStream gz=new GZIPOutputStream(bytes)){gz.write(a);}
  try(GZIPOutputStream gz=new GZIPOutputStream(bytes)){gz.write(b);}
  List<File> parts=split("book.gz",bytes.toByteArray(),97);File joined=merge(SevenZSplitVolumeResolver.resolveNumeric(parts.get(3)).parts);
  try(GZIPInputStream gz=new GZIPInputStream(new FileInputStream(joined))){ByteArrayOutputStream expected=new ByteArrayOutputStream();expected.write(a);expected.write(b);assertArrayEquals(expected.toByteArray(),gz.readAllBytes());}
  finally{joined.delete();}
 }
 @Test public void corruptedStoredZipStillFailsCrcAfterReassembly()throws Exception {
  byte[] payload=content();ByteArrayOutputStream bytes=new ByteArrayOutputStream();CRC32 crc=new CRC32();crc.update(payload);
  try(ZipOutputStream zip=new ZipOutputStream(bytes)){ZipEntry entry=new ZipEntry("p");entry.setMethod(ZipEntry.STORED);entry.setSize(payload.length);entry.setCrc(crc.getValue());zip.putNextEntry(entry);zip.write(payload);zip.closeEntry();}
  byte[] corrupt=bytes.toByteArray();corrupt[31+50]^=1;File joined=merge(split("bad.zip",corrupt,255));
  try(ZipInputStream zip=new ZipInputStream(new FileInputStream(joined))){zip.getNextEntry();zip.readAllBytes();fail("CRC mismatch accepted");}
  catch(ZipException expected){assertTrue(expected.getMessage().toLowerCase(Locale.ROOT).contains("crc"));}
  finally{joined.delete();}
 }
 @Test public void missingIntermediateNumericPartIsRejected()throws Exception {
  List<File> files=split("book.zip",content(),1000);assertTrue(files.get(3).delete());
  try{SevenZSplitVolumeResolver.resolveNumeric(files.get(5));fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("Missing"));}
 }
 @Test public void mergeRejectsInsufficientSpaceAndDeletesTemporaryFile()throws Exception {
  List<File> files=split("book.zip",content(),1024);int before=temp.getRoot().list().length;
  try{NumericSplitArchiveMerger.merge(files,temp.getRoot(),d->1);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("free space"));}
  assertEquals(before,temp.getRoot().list().length);
 }
 @Test public void mergeRechecksSpaceDuringLongCopy()throws Exception {
  List<File> files=split("book.zip",new byte[3*1024*1024],512*1024);int before=temp.getRoot().list().length;int[] calls={0};
  try{NumericSplitArchiveMerger.merge(files,temp.getRoot(),d->++calls[0]==1?Long.MAX_VALUE:0);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("free space"));}
  assertTrue(calls[0]>=2);assertEquals(before,temp.getRoot().list().length);
 }
 @Test public void mergeRejectsMutationAfterInitialSnapshot()throws Exception {
  List<File> files=split("book.zip",content(),1024);int before=temp.getRoot().list().length;
  try{NumericSplitArchiveMerger.merge(files,temp.getRoot(),d->{files.get(0).setLastModified(1234000L);return Long.MAX_VALUE;});fail();}
  catch(IOException expected){assertTrue(expected.getMessage().contains("changed"));}
  assertEquals(before,temp.getRoot().list().length);
 }
 @Test public void mergeCancellationCleansUpAndRetainsInterrupt()throws Exception {
  List<File> files=split("book.zip",content(),1024);int before=temp.getRoot().list().length;
  try{NumericSplitArchiveMerger.merge(files,temp.getRoot(),d->{Thread.currentThread().interrupt();return Long.MAX_VALUE;});fail();}
  catch(InterruptedIOException expected){assertTrue(Thread.currentThread().isInterrupted());}
  finally{Thread.interrupted();}
  assertEquals(before,temp.getRoot().list().length);
 }
 @Test public void mergeSupportsEmptyIntermediateVolumes()throws Exception {
  File a=temp.newFile("a"),b=temp.newFile("b"),c=temp.newFile("c");Files.write(a.toPath(),new byte[]{1});Files.write(c.toPath(),new byte[]{2,3});
  File joined=merge(Arrays.asList(a,b,c));try{assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(joined.toPath()));}finally{joined.delete();}
 }
 @Test public void mergeRejectsSizeOverflowBeforeCreatingTemporaryOutput()throws Exception {
  File real=temp.newFile("a");File huge=new File(real.toString()){@Override public long length(){return Long.MAX_VALUE;}};
  File one=new File(real.toString()){@Override public long length(){return 1;}};int before=temp.getRoot().list().length;
  try{merge(Arrays.asList(huge,one));fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("too large"));}assertEquals(before,temp.getRoot().list().length);
 }
}
