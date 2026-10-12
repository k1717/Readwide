package com.readwide.manager.archive;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** One retained CAB folder decoder, exposing only fully decoded/checked entry spools. */
final class CabForwardReader implements ArchiveSupport.ForwardArchiveReader {
    private final CabArchiveReader.Session session;
    private final File directory;
    private int next;
    private CabArchiveReader.Entry current;
    private File spool;
    private InputStream input;
    private long remaining;
    private boolean decoded, closed;

    CabForwardReader(CabArchiveReader.Session session, File directory) {
        this.session=session; this.directory=directory;
    }

    private void validate() throws IOException {
        if (closed) throw new IOException("CAB forward reader is closed");
        session.validate();
    }

    @Override public ArchiveSupport.ForwardEntry nextEntry() throws IOException {
        try {
            validate(); clearSpool();
            current=next<session.entries.size() ? session.entries.get(next++) : null;
            decoded=false;
            return current==null ? null : new ArchiveSupport.ForwardEntry(current.path,current.directory,!current.directory);
        } catch (IOException | RuntimeException | Error failure) { retire(failure); throw failure; }
    }

    // The CAB decoder seeks/advances folder history itself when extract(index) runs.
    @Override public boolean skipsUnreadEntryOnAdvance() { return true; }

    private void decode(boolean retain) throws IOException {
        if (current==null || decoded) return;
        if (!current.directory) {
            if (retain) {
                RarOutputFileGuard.ensureDirectory(directory);
                spool=File.createTempFile("cab_verified_",".spool",directory);
                session.writeFile(current,spool);
                remaining=current.size;
                input=new BufferedInputStream(new FileInputStream(spool));
            } else {
                session.write(current,new OutputStream() {
                    @Override public void write(int value) { }
                    @Override public void write(byte[] bytes,int offset,int count) { }
                });
            }
        }
        decoded=true;
    }

    @Override public int read(byte[] buffer) throws IOException {
        try {
            validate();
            if (buffer.length==0) return 0;
            decode(true);
            if (input==null) return -1;
            if (spool.length()!=current.size) throw new IOException("Verified CAB spool changed length");
            if (remaining==0) return -1;
            int count=input.read(buffer,0,(int)Math.min(buffer.length,remaining));
            if (count<=0) throw new IOException("Truncated verified CAB spool");
            remaining-=count;
            return count;
        } catch (IOException | RuntimeException | Error failure) { retire(failure); throw failure; }
    }

    @Override public boolean drainCurrentEntry(long maximum) throws IOException {
        try {
            validate();
            long needed=current==null || current.directory ? 0 : (decoded ? remaining : current.size);
            if (maximum<0 || needed>maximum) throw new IOException("CAB entry exceeds requested drain bound");
            decode(false); clearSpool();
            return true;
        } catch (IOException | RuntimeException | Error failure) { retire(failure); throw failure; }
    }

    private void clearSpool() throws IOException {
        Throwable failure=null;
        try { if(input!=null) { input.close(); input=null; } }
        catch(IOException | RuntimeException | Error cleanup){failure=cleanup;}
        finally{remaining=0;}
        if(spool!=null) {
            try { RarOutputFileGuard.deletePartial(spool); spool=null; }
            catch(IOException | RuntimeException | Error cleanup) {
                if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup);
            }
        }
        CabArchiveReader.rethrow(failure);
    }

    private void retire(Throwable failure) {
        try { close(); } catch(IOException | RuntimeException | Error cleanup) {
            if(cleanup!=failure)failure.addSuppressed(cleanup);
        }
    }

    @Override public void close() throws IOException {
        closed=true;
        Throwable failure=null;
        try { session.close(); } catch(IOException | RuntimeException | Error cleanup){failure=cleanup;}
        try { clearSpool(); } catch(IOException | RuntimeException | Error cleanup) {
            if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup);
        }
        CabArchiveReader.rethrow(failure);
    }
}
