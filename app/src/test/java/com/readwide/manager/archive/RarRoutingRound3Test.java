package com.readwide.manager.archive;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class RarRoutingRound3Test {
 @Rule public TemporaryFolder temp = new TemporaryFolder();
 private File part(String name)throws Exception{return temp.newFile(name);}
 private void rejected(File selected,String detail)throws Exception {
  try {RarArchiveLocator.collectReadableVolumes(selected);fail("accepted invalid chain");}
  catch(IOException expected){assertTrue(expected.getMessage(),expected.getMessage().toLowerCase(Locale.ROOT).contains(detail));}
 }
 @Test public void gapAfterTwoReadablePartsIsRejected()throws Exception {
  File first=part("b.part1.rar");part("b.part2.rar");part("b.part4.rar");rejected(first,"incomplete");
 }
 @Test public void zeroPaddedAliasesAreRejected()throws Exception {
  File first=part("b.part1.rar");part("b.part01.rar");part("b.part2.rar");rejected(first,"ambiguous");
 }
 @Test public void caseCollisionsAreRejected()throws Exception {
  File first=part("b.part1.rar");part("B.PART1.RAR");rejected(first,"ambiguous");
 }
 @Test public void mixedCaseAndWidePaddingResolveToSameSet()throws Exception {
  File first=part("Book.PART0000001.RAR"),second=part("book.part0000002.rar");
  assertEquals(Arrays.asList(first,second),RarArchiveLocator.collectReadableVolumes(second));
 }
 @Test public void oldStyleAliasesAreRejected()throws Exception {
  File first=part("b.rar");part("b.r00");part("b.r000");rejected(first,"ambiguous");
 }
 @Test public void oldStyleBaseCaseCollisionWithContinuationsIsRejected()throws Exception {
  File first=part("b.rar");part("B.RAR");part("b.r00");rejected(first,"ambiguous");
 }
 @Test public void baseRarDoesNotHideMissingR00()throws Exception {
  File first=part("b.rar");part("b.r01");rejected(first,"incomplete");
 }
 @Test public void existingBaseNeverRedirectsToSeparatePartSet()throws Exception {
  File base=part("b.rar");part("b.part1.rar");part("b.part2.rar");
  assertEquals(Collections.singletonList(base),RarArchiveLocator.collectReadableVolumes(base));
 }
 @Test public void missingBaseIsNotAnAliasForSeparatePartSet()throws Exception {
  part("b.part1.rar");part("b.part2.rar");rejected(new File(temp.getRoot(),"b.rar"),"incomplete");
 }
 @Test public void relativeLaterPartResolvesFirstPart()throws Exception {
  String name="readwide-relative-"+UUID.randomUUID();File first=new File(name+".part1.rar"),second=new File(name+".part2.rar");
  try {assertTrue(first.createNewFile());assertTrue(second.createNewFile());
   List<File> files=RarArchiveLocator.collectReadableVolumes(second);assertEquals(2,files.size());
   assertEquals(first.getCanonicalFile(),files.get(0).getCanonicalFile());
  }finally{first.delete();second.delete();}
 }
 @Test public void directoryPosingAsVolumeIsRejected()throws Exception {
  File first=part("b.part1.rar");temp.newFolder("b.part2.rar");rejected(first,"unavailable");
 }
 @Test public void zeroAndOverflowOrdinalsAreRejected()throws Exception {
  rejected(part("zero.part0.rar"),"invalid");rejected(part("big.part2147483648.rar"),"invalid");
 }
 @Test public void hugeGapDoesNotRequireNumericProbing()throws Exception {
  File first=part("b.part1.rar");part("b.part2147483647.rar");rejected(first,"incomplete");
 }
 @Test public void cancellationPreservesInterruptFlag()throws Exception {
  File first=part("b.part1.rar");Thread.currentThread().interrupt();
  try {RarArchiveLocator.collectReadableVolumes(first);fail();}
  catch(InterruptedIOException expected){assertTrue(Thread.currentThread().isInterrupted());}
  finally{Thread.interrupted();}
 }
 @Test public void unavailableDirectoryIsNotSingletonSuccess()throws Exception {
  File parent=new File(temp.getRoot(),"unavailable"){@Override public File[] listFiles(){return null;}};
  File selected=new File(parent,"b.rar"){@Override public File getParentFile(){return parent;} @Override public boolean isFile(){return true;} @Override public boolean canRead(){return true;}};
  rejected(selected,"unavailable");
 }
 @Test public void moreThan9999VolumesAreNotTruncated()throws Exception {
  final File[] files=new File[10005];
  File parent=new File(temp.getRoot(),"virtual-catalog"){@Override public File[] listFiles(){return files;}};
  for(int i=0;i<files.length;i++){final int n=i+1;files[i]=new File(parent,"book.part"+n+".rar"){
   @Override public File getParentFile(){return parent;} @Override public boolean isFile(){return true;} @Override public boolean canRead(){return true;}};}
  List<File> result=RarArchiveLocator.collectReadableVolumes(files[0]);
  assertEquals(files.length,result.size());assertEquals(files[10004],result.get(10004));
 }
 @Test public void threeDigitLegacyOrdinalKeepsFullPrefix()throws Exception {
  File first=part("legacy.rar"),selected=null;
  for(int i=0;i<=100;i++)selected=part(String.format(Locale.ROOT,"legacy.r%02d",i));
  List<File> files=RarArchiveLocator.collectReadableVolumes(selected);
  assertEquals(102,files.size());assertEquals(first,files.get(0));assertEquals(selected,files.get(101));
 }
}
