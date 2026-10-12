package com.readwide.manager;

import java.io.Closeable;
import java.io.IOException;
import java.util.zip.ZipFile;

/** Retire immediately; the last active resource request closes the owned ZIP. */
final class DocumentZipSession implements Closeable {
    private final ZipFile zip;
    private int readers;
    private boolean retired;

    DocumentZipSession(ZipFile zip) { this.zip = java.util.Objects.requireNonNull(zip); }

    synchronized Lease acquire() {
        if (retired) return null;
        readers++;
        return new Lease();
    }

    @Override public void close() {
        boolean closeNow;
        synchronized (this) {
            if (retired) return;
            retired = true;
            closeNow = readers == 0;
        }
        if (closeNow) closeZip();
    }

    private void closeZip() {
        try { zip.close(); } catch (IOException ignored) { }
    }

    final class Lease implements Closeable {
        private boolean released;
        ZipFile zip() { return zip; }

        @Override public void close() {
            boolean closeNow;
            synchronized (DocumentZipSession.this) {
                if (released) return;
                released = true;
                closeNow = --readers == 0 && retired;
            }
            if (closeNow) closeZip();
        }
    }
}
