package com.readwide.manager.archive;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;
public class ArchiveFixtureContractRound8Test {
 @Rule public TemporaryFolder temp = new TemporaryFolder();
 private RarArchiveReader.RarEntry entry(File f, long size) {
  RarArchiveReader.RarEntry e = new RarArchiveReader.RarEntry("test",false,32,size,0,4,0x33,false,false,false,null,0,0);
  e.sourceArchive=f; return e;
 }
 @Test public void shortMetadataProbeMustRemainUnknown() throws Exception {
  File f=temp.newFile();Files.write(f.toPath(),new byte[4]);
  assertFalse(Rar3PpmdBlockProbe.probe(entry(f,32)).isClassicLz());
 }
 @Test public void completeMetadataProbeCanIdentifyClassicLz() throws Exception {
  File f=temp.newFile();Files.write(f.toPath(),new byte[32]);
  assertTrue(Rar3PpmdBlockProbe.probe(entry(f,32)).isClassicLz());
 }
 @Test public void exactMaximumByteCounterIsNotArbitraryHeapLimit() throws Exception {
  assertEquals(Long.MAX_VALUE,ArchiveSupport.checkedAddDecodedStreamBytes(Long.MAX_VALUE-1,1));
 }
 @Test public void overflowFailsWithUnsupportedCategory() throws Exception {
  try { ArchiveSupport.checkedAddDecodedStreamBytes(Long.MAX_VALUE,1);fail(); }
  catch(ArchiveSupport.UnsupportedArchiveFeatureException e) {
   assertEquals(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE,ArchiveFailureClassifier.classify(e));
  }
 }
}
