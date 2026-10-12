package com.readwide.manager.archive;

import com.readwide.codecs.CabNativeDecoder;
import com.readwide.manager.util.FileOperationProgress;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Linked CAB extraction with app-owned inputs, guarded writes and exact entry identity. */
final class CabArchiveReader {
    private CabArchiveReader() {}

    /** Keep normal single-CAB/libarchive behavior; never fall back after a linked-set error. */
    static Session openIfNeeded(File archive, FileOperationProgress progress) throws IOException {
        ArchiveCancellation.checkpoint(progress);
        if (!CabVolumeResolver.hasSignature(archive)) return null;
        boolean linked = CabVolumeResolver.hasVolumeLinks(archive);
        if (!linked && LibarchiveNativeBridge.isAvailable()) return null;
        CabVolumeResolver.VolumeSet volumes = CabVolumeResolver.resolve(archive);
        if (!CabNativeDecoder.isAvailable()) {
            if (linked) throw new ArchiveSupport.UnsupportedArchiveFeatureException("Multi-volume CAB decoder unavailable");
            return null;
        }
        return new Session(volumes,progress,NativeDecoder::new);
    }

    interface Decoder extends Closeable {
        List<RawEntry> entries();
        void extract(int index, OutputStream output) throws IOException;
    }
    interface Factory { Decoder open(CabArchiveInputs inputs) throws IOException; }

    static final class RawEntry {
        final byte[] name;
        final long size;
        final int attributes;
        RawEntry(byte[] name, long size, int attributes) {
            this.name = name.clone(); this.size = size; this.attributes = attributes;
        }
    }

    static final class Entry {
        final int index;
        final String path;
        final long size;
        final boolean directory;
        Entry(int index, String path, long size) {
            this.index = index; this.path = path; this.size = size;
            directory = path.endsWith("/");
        }
    }

    private static final class NativeDecoder implements Decoder {
        private final CabNativeDecoder nativeDecoder;
        private final List<RawEntry> entries;
        NativeDecoder(CabArchiveInputs inputs) throws IOException {
            long[] sizes = new long[inputs.volumes.headers.size()];
            for (int i=0;i<sizes.length;i++) sizes[i]=inputs.volumes.headers.get(i).cabinetBytes;
            nativeDecoder = new CabNativeDecoder(inputs,inputs.volumes.headers.get(0).setId,sizes);
            try {
                List<RawEntry> parsed = new ArrayList<>();
                for (CabNativeDecoder.Entry entry : nativeDecoder.entries()) {
                    inputs.checkpoint();
                    parsed.add(new RawEntry(entry.nameBytes(),entry.length,entry.attributes));
                }
                entries = Collections.unmodifiableList(parsed);
            } catch (IOException | RuntimeException | Error failure) {
                try { nativeDecoder.close(); } catch (IOException | RuntimeException | Error cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
        @Override public List<RawEntry> entries() { return entries; }
        @Override public void extract(int index,OutputStream output) throws IOException { nativeDecoder.extract(index,output); }
        @Override public void close() throws IOException { nativeDecoder.close(); }
    }

    static final class Session implements Closeable {
        final CabArchiveInputs inputs;
        final List<Entry> entries;
        private final Decoder decoder;
        private final FileOperationProgress progress;
        private boolean closed, decoderClosed, closing;

        Session(CabVolumeResolver.VolumeSet volumes, FileOperationProgress progress, Factory factory) throws IOException {
            this.progress = progress;
            inputs = new CabArchiveInputs(volumes,progress);
            Decoder opened = null;
            try {
                opened = factory.open(inputs);
                entries = index(opened.entries(), progress);
                inputs.validate();
                decoder = opened;
            } catch (IOException | RuntimeException | Error failure) {
                if (opened != null) try { opened.close(); }
                catch (IOException | RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                try { inputs.close(); }
                catch (IOException | RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        void validate() throws IOException {
            if (closed) throw new IOException("CAB extraction session is closed");
            inputs.validate();
        }

        List<ArchiveSupport.EntryInfo> listEntries() throws IOException {
            validate();
            List<ArchiveSupport.EntryInfo> result = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                inputs.checkpoint();
                result.add(new ArchiveSupport.EntryInfo(entry.path,entry.directory,entry.size,0));
            }
            // index() already retained the first normalized identity exactly once.
            return ArchiveSupport.withSyntheticDirectories(result);
        }

        boolean extractSingle(String selected, File output) throws IOException {
            validate();
            String path = ArchiveSupport.sanitizeEntryPathForList(selected);
            if (path == null || path.endsWith("/")) return false;
            for (Entry entry : entries) {
                inputs.checkpoint();
                if (!entry.directory && entry.path.equals(path)) {
                    inputs.requireDistinctOutput(output);
                    try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(output)) {
                        writeFile(entry,output);
                        close(); // source/decoder cleanup must succeed before committing the target
                        guard.commit();
                    }
                    return true;
                }
            }
            return false;
        }

        boolean extractAll(File directory, ArchiveExtractionProgressTracker tracker) throws IOException {
            validate();
            ArchiveOutputPreflight preflight = new ArchiveOutputPreflight(directory);
            List<File> destinations = new ArrayList<>(entries.size());
            Set<File> identities = new HashSet<>();
            long total = 0;
            for (Entry entry : entries) {
                inputs.checkpoint();
                File output = preflight.add(entry.path,entry.directory);
                inputs.requireDistinctOutput(output);
                if (!identities.add(output)) {
                    throw new IOException("CAB entry paths alias the same output");
                }
                destinations.add(output);
                if (entry.size > Long.MAX_VALUE-total) throw new IOException("CAB extraction size overflow");
                total += entry.size;
            }
            if (progress != null) progress.setTotalBytes(total);
            for (int i=0;i<entries.size();i++) {
                Entry entry = entries.get(i); File output = destinations.get(i);
                validate();
                if (entry.directory) {
                    RarOutputFileGuard.ensureDirectory(output);
                    if (tracker != null) tracker.onDirectory(entry.path);
                } else {
                    if (tracker != null) tracker.onFile(entry.path);
                    else if (progress != null) progress.setDetail(entry.path);
                    try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(output)) {
                        writeFile(entry,output);
                        guard.commit();
                    }
                }
            }
            validate();
            return true;
        }

        void writeFile(Entry entry, File output) throws IOException {
            try {
                validate();
                inputs.requireDistinctOutput(output);
                RarOutputFileGuard.ensureParentDirectory(output);
                if (entry.size > ArchiveSupport.runtimeExtractionBudgetBytes(output.getAbsoluteFile().getParentFile())) {
                    throw new IOException("Not enough free space for CAB extraction");
                }
                try (OutputStream stream = ArchiveSupport.openExtractionOutputStream(output)) {
                    write(entry,stream);
                }
                if (output.length() != entry.size) throw new IOException("CAB output file length mismatch");
                validate();
            } catch (IOException | RuntimeException | Error failure) {
                retire(failure); throw failure;
            }
        }

        void write(Entry entry, OutputStream output) throws IOException {
            validate();
            final long[] written = {0};
            OutputStream counted = new OutputStream() {
                @Override public void write(int value) throws IOException {
                    inputs.checkpoint();
                    if (written[0] >= entry.size) throw new IOException("CAB output exceeds declared entry size");
                    output.write(value); written[0]++;
                    if (progress!=null) progress.addDoneBytes(1);
                }
                @Override public void write(byte[] bytes,int offset,int length) throws IOException {
                    inputs.checkpoint();
                    if ((offset|length)<0 || length>bytes.length-offset) throw new IndexOutOfBoundsException();
                    if (length>entry.size-written[0]) throw new IOException("CAB output exceeds declared entry size");
                    output.write(bytes,offset,length); written[0]+=length;
                    if (progress!=null) progress.addDoneBytes(length);
                }
            };
            try {
                decoder.extract(entry.index,counted);
                if (written[0]!=entry.size) throw new IOException("CAB decoded length mismatch");
                validate();
            } catch (IOException | RuntimeException | Error failure) {
                retire(failure); throw failure;
            }
        }

        void retire(Throwable failure) {
            try { close(); } catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
        }

        @Override public void close() throws IOException {
            if (closing) throw new IOException("Reentrant CAB session close");
            closed = true;
            closing = true;
            try {
                Throwable failure = null;
                if (!decoderClosed) {
                    try { decoder.close(); decoderClosed = true; }
                    catch (IOException | RuntimeException | Error cleanup) { failure=cleanup; }
                }
                try { inputs.close(); } catch (IOException | RuntimeException | Error cleanup) {
                    if (failure==null) failure=cleanup; else if (cleanup!=failure) failure.addSuppressed(cleanup);
                }
                rethrow(failure);
            } finally { closing = false; }
        }
    }

    static List<Entry> index(List<RawEntry> raw) throws IOException {
        return index(raw, null);
    }

    static List<Entry> index(List<RawEntry> raw, FileOperationProgress progress) throws IOException {
        ArchiveFilenameDecoder.NameCorpus corpus = new ArchiveFilenameDecoder.NameCorpus();
        for (RawEntry entry : raw) {
            ArchiveCancellation.checkpoint(progress);
            if ((entry.attributes & 0x80)==0) corpus.observe(entry.name);
        }
        Map<String,Entry> entries = new LinkedHashMap<>();
        for (int i=0;i<raw.size();i++) {
            ArchiveCancellation.checkpoint(progress);
            RawEntry entry = raw.get(i);
            if (entry.size<0) throw new IOException("Invalid CAB entry size");
            String decoded = (entry.attributes & 0x80)!=0
                    ? StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(entry.name)).toString()
                    : ArchiveFilenameDecoder.decodeLegacyName(entry.name,corpus);
            String path = ArchiveSupport.sanitizeEntryPathForList(decoded);
            if (path==null) throw new IOException("Unsafe CAB entry path");
            if (path.endsWith("/") && entry.size!=0) throw new IOException("CAB directory entry has payload data");
            // All listing, selected, bulk and forward routes expose the same first identity.
            entries.putIfAbsent(path,new Entry(i,path,entry.size));
        }
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    static void rethrow(Throwable failure) throws IOException {
        if (failure instanceof IOException) throw (IOException)failure;
        if (failure instanceof RuntimeException) throw (RuntimeException)failure;
        if (failure instanceof Error) throw (Error)failure;
    }
}
