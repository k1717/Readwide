package com.readwide.manager.archive;

import com.readwide.codecs.PpmdZipInputStream;
import com.readwide.codecs.PpmdZipInputStream.FreezeRevision;
import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.util.Arrays;
import java.util.zip.CRC32;

/** ZIP method 98 revision selection, with one model and one guarded output file. */
final class ZipPpmdEntryExtractor {
    private ZipPpmdEntryExtractor() {}
    interface Check { void run() throws IOException; }
    interface Opener { Payload open() throws IOException; }
    interface Decoder {
        InputStream open(InputStream packed, long size, FreezeRevision revision) throws IOException;
    }
    static final class Payload implements AutoCloseable {
        final InputStream input;
        final Check finish;
        Payload(InputStream input, Check finish) { this.input=input; this.finish=finish; }
        @Override public void close() throws IOException { input.close(); }
    }
    /** A covered entry must not be retried by a less strict container backend. */
    static final class ExtractionException extends IOException {
        ExtractionException(Throwable cause) { super("ZIP PPMd-I extraction failed",cause); }
    }

    static boolean extract(Opener opener, Check unchanged, File output, long size,
                           long crc, FileOperationProgress progress) throws IOException {
        return extract(opener,unchanged,output,size,crc,progress,(packed,expected,revision) ->
                revision==null ? PpmdZipInputStream.open(packed,expected)
                        : PpmdZipInputStream.openFreeze(packed,expected,revision));
    }

    // Decoder seam permits source-only policy regressions without loading JNI.
    static boolean extract(Opener opener, Check unchanged, File output, long size,
                           long crc, FileOperationProgress progress, Decoder decoder) throws IOException {
        try {
            ArchiveCancellation.checkpoint(progress);
            if(size<0 || crc < -1 || crc>0xffffffffL)throw new IOException("Invalid PPMd-I entry integrity metadata");
            unchanged.run();
            try(RarOutputFileGuard guard=RarOutputFileGuard.forTarget(output);
                ArchiveExtractionByteBudget.Scope ignored=ArchiveExtractionByteBudget.begin(
                        ArchiveSupport.runtimeExtractionBudgetBytes(output.getAbsoluteFile().getParentFile()))) {
                // Reuse the operation budget across both attempts. A standalone
                // retry must not count its soon-to-be-replaced candidate twice.
                Result first=attempt(opener,unchanged,output,size,crc,progress,decoder,false,false,-1);
                if(first.freeze) {
                    // Always check the other revision, even if CRC matches. AE-2 has
                    // no decoded CRC; CRC collisions must not authorize ambiguity either.
                    Result second=attempt(opener,unchanged,output,size,crc,progress,decoder,true,first.valid,first.properties);
                    if(!first.valid && !second.valid) {
                        IOException failed=new IOException("Neither ZIP PPMd-I freeze revision is valid",first.failure);
                        if(second.failure!=null && second.failure!=first.failure)failed.addSuppressed(second.failure);
                        throw failed;
                    }
                    if(first.valid && second.valid && !second.same)
                        throw new IOException("Ambiguous ZIP PPMd-I freeze revisions produce different output");
                } else if(!first.valid)throw first.failure;
                ArchiveCancellation.checkpoint(progress);
                unchanged.run();
                if(progress!=null)progress.addDoneBytes(size); // count accepted bytes only once
                ArchiveCancellation.checkpoint(progress);
                guard.commit();
                return true;
            }
        } catch(IOException | SecurityException failure) {
            ArchiveCancellation.rethrowIfCancelled(failure,progress);
            throw new ExtractionException(failure);
        }
    }

    private static Result attempt(Opener opener, Check unchanged, File output, long size,
                                  long expectedCrc, FileOperationProgress progress, Decoder decoder,
                                  boolean legacy, boolean compare, int expectedProperties) throws IOException {
        ArchiveCancellation.checkpoint(progress);
        unchanged.run();
        Result result;
        try(Payload payload=opener.open()) {
            BorrowedInput packed=new BorrowedInput(payload.input,progress);
            int low=packed.read(),high=packed.read();
            if(low<0 || high<0)throw new IOException("Truncated ZIP PPMd-I properties");
            int properties=low|(high<<8);
            packed.input.unread(new byte[]{(byte)low,(byte)high});
            boolean freeze=(high>>>4)==2;
            if(legacy && properties!=expectedProperties)throw new IOException("ZIP PPMd-I properties changed between revisions");
            FreezeRevision revision=freeze ? (legacy ? FreezeRevision.REVISION_1 : FreezeRevision.REVISION_2) : null;
            CRC32 crc=new CRC32();
            long count=0;
            boolean valid=true,same=true;
            IOException failure=null;
            // Cleanup lies OUTSIDE the decoder-rejection catch. A close failure
            // always aborts; it never grants permission to try another revision.
            try(DecoderOwner owned=new DecoderOwner()) {
                try { owned.input=decoder.open(packed,size,revision); }
                catch(PpmdZipInputStream.DataException rejected) {
                    requireDecoderRejection(packed,rejected,progress);valid=false;failure=rejected;
                }
                if(valid) {
                    try(OutputStream destination=compare ? new ComparisonOutput(output,progress)
                            : ArchiveSupport.openExtractionOutputStream(output)) {
                        byte[] buffer=new byte[65536];
                        try {
                            while(true) {
                                ArchiveCancellation.checkpoint(progress);
                                int n;
                                try { n=owned.input.read(buffer); }
                                catch(PpmdZipInputStream.DataException rejected) {
                                    requireDecoderRejection(packed,rejected,progress);valid=false;failure=rejected;break;
                                }
                                ArchiveCancellation.checkpoint(progress);
                                if(n == -1)break;
                                if(n<=0 || n>buffer.length)throw new IOException("Invalid PPMd-I decoder progress");
                                if(n>size-count) {
                                    valid=false;failure=new IOException("Corrupt ZIP PPMd-I decoded size mismatch");break;
                                }
                                destination.write(buffer,0,n); // output faults are never decoder rejection
                                crc.update(buffer,0,n);count+=n;
                            }
                            if(valid && count!=size) {
                                valid=false;failure=new IOException("Corrupt ZIP PPMd-I decoded size mismatch");
                            }
                            if(valid && expectedCrc>=0 && crc.getValue()!=expectedCrc) {
                                valid=false;failure=new IOException("ZIPX CRC mismatch");
                            }
                            if(compare && valid)same=((ComparisonOutput)destination).finish();
                        } finally { Arrays.fill(buffer,(byte)0); }
                    }
                }
            }
            // Authenticate even a rejected candidate before allowing a retry.
            // The borrowed decoder input does not close the owning decryptor.
            payload.finish.run();
            ArchiveCancellation.checkpoint(progress);
            result=new Result(properties,freeze,valid,same,failure);
        }
        unchanged.run(); // includes successful payload/decoder cleanup before retry/acceptance
        return result;
    }

    private static void requireDecoderRejection(BorrowedInput packed,
            PpmdZipInputStream.DataException rejected,FileOperationProgress progress) throws IOException {
        packed.rethrowFailure();
        ArchiveCancellation.rethrowIfCancelled(rejected,progress);
        if(rejected.getCause()!=null || rejected.getSuppressed().length!=0)throw rejected;
    }

    private static final class Result {
        final int properties;
        final boolean freeze,valid,same;
        final IOException failure;
        Result(int properties,boolean freeze,boolean valid,boolean same,IOException failure) {
            this.properties=properties;this.freeze=freeze;this.valid=valid;this.same=same;
            this.failure=failure;
        }
    }
    private static final class DecoderOwner implements AutoCloseable {
        InputStream input;
        @Override public void close() throws IOException { if(input!=null)input.close(); }
    }
    /** Tracks real input faults even if a source happens to throw DataException. */
    private static final class BorrowedInput extends InputStream {
        final PushbackInputStream input;
        final FileOperationProgress progress;
        Throwable failure;
        BorrowedInput(InputStream input,FileOperationProgress progress) {
            this.input=new PushbackInputStream(input,2);this.progress=progress;
        }
        @Override public int read() throws IOException {
            try {
                ArchiveCancellation.checkpoint(progress);int n=input.read();
                ArchiveCancellation.checkpoint(progress);
                if(n < -1 || n>255)throw new IOException("Invalid PPMd-I property read");
                return n;
            } catch(IOException | RuntimeException | Error error) { failure=error;throw error; }
        }
        @Override public int read(byte[] bytes,int offset,int length) throws IOException {
            try {
                ArchiveCancellation.checkpoint(progress);int n=input.read(bytes,offset,length);
                ArchiveCancellation.checkpoint(progress);
                if(n < -1 || n>length || (n==0 && length!=0))throw new IOException("Invalid PPMd-I packed read");
                return n;
            } catch(IOException | RuntimeException | Error error) { failure=error;throw error; }
        }
        void rethrowFailure() throws IOException {
            if(failure instanceof IOException)throw (IOException)failure;
            if(failure instanceof RuntimeException)throw (RuntimeException)failure;
            if(failure instanceof Error)throw (Error)failure;
        }
        @Override public void close() {} // Payload owns the real input and authentication lifetime
    }
    /** Exact comparison, not a digest shortcut; keep decoding after a difference. */
    private static final class ComparisonOutput extends OutputStream {
        final InputStream original;
        final FileOperationProgress progress;
        final byte[] buffer=new byte[65536];
        boolean same=true;
        ComparisonOutput(File file,FileOperationProgress progress) throws IOException {
            original=new FileInputStream(file);this.progress=progress;
        }
        @Override public void write(int value) throws IOException { write(new byte[]{(byte)value},0,1); }
        @Override public void write(byte[] bytes,int offset,int length) throws IOException {
            int done=0;
            while(done<length) {
                ArchiveCancellation.checkpoint(progress);
                int n=original.read(buffer,0,Math.min(buffer.length,length-done));
                ArchiveCancellation.checkpoint(progress);
                if(n<=0 || n>Math.min(buffer.length,length-done))throw new IOException("PPMd-I comparison file changed or unreadable");
                for(int i=0;i<n;i++)if(bytes[offset+done+i]!=buffer[i])same=false;
                done+=n;
            }
        }
        boolean finish() throws IOException {
            ArchiveCancellation.checkpoint(progress);int n=original.read();
            ArchiveCancellation.checkpoint(progress);
            if(n!=-1)throw new IOException("PPMd-I comparison file grew during validation");
            return same;
        }
        @Override public void close() throws IOException {
            try { original.close(); } finally { Arrays.fill(buffer,(byte)0); }
        }
    }
}
