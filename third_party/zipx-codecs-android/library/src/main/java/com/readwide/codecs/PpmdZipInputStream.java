/* Readwide adapter. SPDX-License-Identifier: Apache-2.0 */
package com.readwide.codecs;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Objects;

/** ZIP method 98 (PPMd-I), independent of libarchive's ZIP container reader.
 * Owns its bounded packed input. The container still owns CRC/MAC/output rollback.
 * Model memory follows the format's 1..256 MiB field, not a decoded payload quota.
 */
public final class PpmdZipInputStream extends InputStream {
    private static final int BUFFER_SIZE=65536;
    private final InputStream source;
    private final byte[] packed=new byte[BUFFER_SIZE], one=new byte[1];
    private long handle;
    private boolean busy, ended, closed, sourceClosed;
    private Throwable failure;

    private PpmdZipInputStream(InputStream source) { this.source=source; }

    /** Explicit raw-codec profile, not evidence of which revision a ZIP used. */
    public enum FreezeRevision {
        REVISION_1(1), REVISION_2(2);
        private final int nativeValue;
        FreezeRevision(int nativeValue) { this.nativeValue=nativeValue; }
    }

    /** Decoder rejection only; source I/O, cancellation and cleanup keep their own errors. */
    public static final class DataException extends IOException {
        public DataException(String message) { super(message); }
    }

    /** Takes ownership on entry, including property/allocation/loading failure. */
    public static InputStream open(InputStream source,long expectedSize) throws IOException {
        return openProfile(source,expectedSize,0);
    }

    /**
     * Decodes only a freeze payload using the caller's explicit revision.
     * The caller must verify the container and resolve revision ambiguity before
     * publishing output. Normal open deliberately does not guess a freeze revision.
     * Takes source ownership even for a null/invalid profile or opening failure.
     */
    public static InputStream openFreeze(InputStream source,long expectedSize,FreezeRevision revision)
            throws IOException {
        return openProfile(source,expectedSize,revision==null ? -1 : revision.nativeValue);
    }

    private static InputStream openProfile(InputStream source,long expectedSize,int freezeRevision) throws IOException {
        Objects.requireNonNull(source,"source");
        PpmdZipInputStream result=null;
        try {
            checkpoint();
            if(freezeRevision<0 || freezeRevision>2)throw new IOException("Invalid ZIP PPMd-I freeze revision");
            if(expectedSize<0)throw new IOException("ZIP PPMd-I requires a declared output size");
            ensureLoaded();
            result=new PpmdZipInputStream(source);
            int low=readPropertyByte(source);
            int high=readPropertyByte(source);
            checkpoint();
            result.handle=nativeOpen(result,low|(high<<8),expectedSize,freezeRevision);
            if(result.handle==0)throw new IOException("Cannot open ZIP PPMd-I decoder");
            checkpoint();
            return result;
        } catch(IOException | RuntimeException | Error error) {
            try { if(result==null)source.close();else result.close(); }
            catch(IOException | RuntimeException | Error cleanup){if(cleanup!=error)error.addSuppressed(cleanup);}
            if(error instanceof LinkageError)throw new IOException("ZIP PPMd-I decoder unavailable",error);
            throw error;
        }
    }

    private static int readPropertyByte(InputStream source) throws IOException {
        int value=source.read();
        if(value==-1)throw new EOFException("Truncated ZIP PPMd-I properties");
        if(value<0 || value>255)throw new IOException("Invalid ZIP PPMd-I property byte");
        return value;
    }

    @Override public synchronized int read() throws IOException {
        return read(one,0,1)<0 ? -1 : one[0]&255;
    }

    @Override public synchronized int read(byte[] data,int offset,int length) throws IOException {
        Objects.requireNonNull(data);
        if((offset|length)<0 || length>data.length-offset)throw new IndexOutOfBoundsException();
        if(closed)throw new IOException("ZIP PPMd-I stream is closed");
        if(busy)throw new IOException("Reentrant ZIP PPMd-I read");
        if(failure instanceof IOException)throw (IOException)failure;
        if(failure instanceof RuntimeException)throw (RuntimeException)failure;
        if(failure instanceof Error)throw (Error)failure;
        if(length==0)return 0;
        busy=true;
        try {
            checkpoint();
            if(ended)return -1;
            int count=nativeRead(handle,this,packed,data,offset,Math.min(length,BUFFER_SIZE));
            if(count==0 || count < -1 || count>Math.min(length,BUFFER_SIZE))throw new IOException("Invalid PPMd-I decoder progress");
            if(count<0){ended=true;releaseNative();}
            return count;
        } catch(IOException | RuntimeException | Error error) {
            failure=error;
            try {releaseNative();}catch(RuntimeException | Error cleanup){if(cleanup!=error)error.addSuppressed(cleanup);}
            throw error;
        } finally {busy=false;}
    }

    // Called only during a synchronized nativeRead. Never turn short/zero/error reads into padding.
    private int readPacked(byte[] buffer) throws IOException {
        checkpoint();
        int count=source.read(buffer,0,buffer.length);
        checkpoint();
        if(count==0 || count < -1 || count>buffer.length)throw new IOException("Invalid ZIP PPMd-I packed read");
        return count;
    }

    private static void checkpoint() throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("ZIP PPMd-I extraction cancelled");
    }

    private void checkCancelled() throws InterruptedIOException { checkpoint(); }

    private void releaseNative() {
        long owned=handle;handle=0;
        if(owned!=0)nativeFree(owned);
    }

    @Override public synchronized void close() throws IOException {
        if(busy)throw new IOException("Cannot close ZIP PPMd-I stream from an input callback");
        closed=true;
        busy=true;
        try {
            try {releaseNative();}
            catch(RuntimeException | Error error) {
                try {closeSource();}
                catch(IOException | RuntimeException | Error cleanup){if(cleanup!=error)error.addSuppressed(cleanup);}
                throw error;
            }
            closeSource();
        } finally {busy=false;}
    }

    private void closeSource() throws IOException {
        // Failed cleanup retains ownership, but never reopens the decoder.
        if(!sourceClosed){source.close();sourceClosed=true;}
    }

    private static boolean loaded;
    private static synchronized void ensureLoaded() {
        if(!loaded){System.loadLibrary("readwide-ppmd");loaded=true;}
    }
    public static boolean isAvailable() {
        try {ensureLoaded();return true;}
        catch(LinkageError | SecurityException unavailable){return false;}
    }
    private static native long nativeOpen(PpmdZipInputStream owner,int properties,long expectedSize,
                                         int freezeRevision) throws IOException;
    private static native int nativeRead(long handle,PpmdZipInputStream owner,byte[] packed,
            byte[] output,int offset,int length) throws IOException;
    private static native void nativeFree(long handle);
}
