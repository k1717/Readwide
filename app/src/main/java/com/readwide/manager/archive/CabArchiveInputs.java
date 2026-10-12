package com.readwide.manager.archive;

import com.readwide.codecs.CabNativeDecoder;
import com.readwide.manager.util.FileOperationProgress;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.Set;

/** Approved CAB volume handles, one open file and one reusable read-ahead buffer. */
final class CabArchiveInputs implements CabNativeDecoder.Source, Closeable {
    final CabVolumeResolver.VolumeSet volumes;
    private final FileOperationProgress progress;
    private final Set<File> sourceFiles;
    private final byte[] cache = new byte[64 * 1024];
    private RandomAccessFile input;
    private int volume = -1, cached;
    private long cacheOffset;
    private boolean closed;

    CabArchiveInputs(CabVolumeResolver.VolumeSet volumes, FileOperationProgress progress) throws IOException {
        this.volumes = volumes;
        this.progress = progress;
        sourceFiles = new HashSet<>(volumes.parts);
        validate();
    }

    @Override public void checkpoint() throws IOException {
        ArchiveCancellation.checkpoint(progress);
        if (closed) throw new IOException("CAB inputs are closed");
    }

    void validate() throws IOException {
        checkpoint();
        volumes.requireUnchanged();
        if (input != null && input.length() != volumes.headers.get(volume).physicalBytes) {
            throw new IOException("Opened CAB volume changed length");
        }
    }

    void requireDistinctOutput(File output) throws IOException {
        if (sourceFiles.contains(output.getCanonicalFile())) {
            throw new IOException("Extraction output is a CAB source volume");
        }
    }

    @Override public int read(int part, long offset, byte[] target, int count) throws IOException {
        checkpoint();
        if (part < 0 || part >= volumes.headers.size() || offset < 0 || count < 0 || count > target.length) {
            throw new IOException("Invalid CAB positional read");
        }
        CabVolumeResolver.Header header = volumes.headers.get(part);
        if (offset > header.cabinetBytes) throw new IOException("CAB read outside declared extent");
        if (count == 0) return 0;
        if (offset == header.cabinetBytes) return -1;
        if (part != volume) {
            closeCurrent();
            header.requireUnchanged();
            RandomAccessFile opened = new RandomAccessFile(header.file,"r");
            try {
                if (opened.length() != header.physicalBytes) throw new IOException("CAB source changed while opening");
                header.requireUnchanged();
                input = opened; volume = part;
            } catch (IOException | RuntimeException | Error failure) {
                try { opened.close(); } catch (IOException | RuntimeException | Error cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
        int total = 0;
        int wanted = (int)Math.min(count,header.cabinetBytes-offset);
        while (total < wanted) {
            checkpoint();
            long position = offset+total;
            if (cached == 0 || position < cacheOffset || position-cacheOffset >= cached) {
                header.requireUnchanged();
                if (input.length() != header.physicalBytes) throw new IOException("Opened CAB volume changed length");
                cacheOffset = position;
                cached = 0;
                int length = (int)Math.min(cache.length,header.cabinetBytes-position);
                input.seek(position);
                input.readFully(cache,0,length);
                cached = length;
            }
            int from = (int)(position-cacheOffset);
            int length = Math.min(wanted-total,cached-from);
            System.arraycopy(cache,from,target,total,length);
            total += length;
        }
        return total;
    }

    private void closeCurrent() throws IOException {
        cached = 0;
        // Keep the failed handle owned so session cleanup can retry it.
        if (input != null) input.close();
        input = null; volume = -1;
    }

    @Override public void close() throws IOException {
        closed = true;
        closeCurrent(); // cleanup must still run after cancellation
    }
}
