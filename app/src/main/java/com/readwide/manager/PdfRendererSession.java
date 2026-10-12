package com.readwide.manager;

import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/** Owns both native renderers and their descriptors. Open/close on workers only. */
final class PdfRendererSession {
    final PdfRenderer primary;
    final PdfRenderer prefetch;
    final int pageCount;
    private final Handle primaryHandle;
    private final Handle prefetchHandle;

    private PdfRendererSession(Handle primary, Handle prefetch, int pageCount) {
        this.primaryHandle = primary;
        this.prefetchHandle = prefetch;
        this.primary = primary.renderer;
        this.prefetch = prefetch == null ? null : prefetch.renderer;
        this.pageCount = pageCount;
    }

    static PdfRendererSession open(File file) throws IOException {
        Handle primary = Handle.open(file);
        Handle prefetch = null;
        try {
            int count = primary.renderer.getPageCount();
            if (count <= 0) throw new IOException("PDF has no pages");
            try { prefetch = Handle.open(file); }
            catch (IOException | RuntimeException | OutOfMemoryError optional) {
                // Visible rendering remains available if a second renderer cannot open.
            }
            return new PdfRendererSession(primary, prefetch, count);
        } catch (IOException | RuntimeException | Error failure) {
            primary.close();
            if (prefetch != null) prefetch.close();
            throw failure;
        }
    }

    void closePrimary() { primaryHandle.close(); }
    void closePrefetch() { if (prefetchHandle != null) prefetchHandle.close(); }
    void closeUnpublished() { closePrimary(); closePrefetch(); }

    private static final class Handle {
        final PdfRenderer renderer;
        final ParcelFileDescriptor descriptor;
        private boolean closed;
        Handle(PdfRenderer renderer, ParcelFileDescriptor descriptor) {
            this.renderer = renderer;
            this.descriptor = descriptor;
        }
        static Handle open(File file) throws IOException {
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            PdfRenderer renderer = null;
            try {
                renderer = new PdfRenderer(descriptor);
                return new Handle(renderer, descriptor);
            }
            catch (IOException | RuntimeException | Error failure) {
                if (renderer != null) {
                    try { renderer.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                }
                try { descriptor.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        void close() {
            if (closed) return;
            closed = true;
            try { renderer.close(); }
            catch (RuntimeException failure) { Log.w("ReadwidePdf", "Cannot close PDF renderer", failure); }
            finally {
                try { descriptor.close(); }
                catch (IOException failure) { Log.w("ReadwidePdf", "Cannot close PDF descriptor", failure); }
            }
        }
    }
}
