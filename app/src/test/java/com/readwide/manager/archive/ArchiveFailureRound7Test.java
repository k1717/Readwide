package com.readwide.manager.archive;
import static org.junit.Assert.*;
import org.junit.*;import org.junit.rules.TemporaryFolder;
import java.io.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.zip.*;
import com.readwide.manager.util.FileOperationProgress;
public class ArchiveFailureRound7Test {
 private void classification(ArchiveSupport.ExtractionFailure expected,Exception error){assertEquals(expected,ArchiveFailureClassifier.classify(error));}
 @Test public void typedEofWithoutMessageIsCorrupt(){classification(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,new EOFException());}
 @Test public void wrappedTypedEofIsCorrupt(){classification(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,new IOException("Read failed",new EOFException()));}
 @Test public void typedEofBeatsMisleadingOuterFilename(){classification(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,new IOException("path bad password.zip",new EOFException()));}
 @Test public void typedRequiredPasswordBeatsUnrelatedMessage(){classification(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED,new IOException("read failed",new ArchiveSupport.PasswordRequiredException()));}
 @Test public void typedUnsupportedBeatsBadPasswordWords(){classification(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE,new ArchiveSupport.UnsupportedArchiveFeatureException("bad password algorithm"));}
 @Test public void nestedUnsupportedWithoutMessageDetected(){classification(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE,new IOException("outer",new ArchiveSupport.UnsupportedArchiveFeatureException("opaque")));}
 @Test public void wrappedMessageFallbackFindsChecksum(){classification(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,new IOException("outer",new IOException("checksum mismatch")));}
 @Test public void wrappedMessageFallbackFindsPassword(){classification(ArchiveSupport.ExtractionFailure.BAD_PASSWORD,new IOException("outer",new IOException("wrong password")));}
 @Test public void cancellationIsNotPasswordFailure(){classification(ArchiveSupport.ExtractionFailure.FAILED,new InterruptedIOException("wrong password.zip interrupted"));}
 @Test public void unknownFailureRemainsUnknown(){classification(ArchiveSupport.ExtractionFailure.FAILED,new IOException("permission denied"));}
 @Test public void cyclicCausesDoNotHang(){IOException a=new IOException("a"),b=new IOException("b");a.initCause(b);b.initCause(a);classification(ArchiveSupport.ExtractionFailure.FAILED,a);}
 @Test public void unexpectedEofMessageIsCorrupt(){classification(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,new IOException("Unexpected EGG EOF"));}
 @Test public void invalidEggVolumeSignatureIsCorrupt(){classification(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,new IOException("Invalid EGG volume signature: sample.egg"));}
 @Test public void invalidAlzVolumeSignatureIsCorrupt(){classification(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,new IOException("Invalid ALZ volume signature: sample.alz"));}
}
