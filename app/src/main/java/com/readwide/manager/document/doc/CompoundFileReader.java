package com.readwide.manager.document.doc;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal read-only reader for the OLE2 / Compound File Binary Format
 * (CFBF, [MS-CFB]). It exposes the named streams a legacy binary Word
 * document is built from (WordDocument, 0Table/1Table, Data) so a higher
 * level parser can reconstruct text.
 *
 * The reader is deliberately conservative and self-contained: it parses the
 * header, the FAT (via the DIFAT), the mini FAT, and the directory, then
 * returns whole streams by name. It does not modify anything and never
 * touches Android APIs, so the class is plain Java and unit testable.
 *
 * Sector numbers are kept as signed ints: valid sectors are non-negative,
 * and the special terminators (ENDOFCHAIN 0xFFFFFFFE, FREESECT 0xFFFFFFFF,
 * etc.) land on negative values, so a chain walk simply stops once the next
 * sector is negative.
 */
public final class CompoundFileReader {

    private static final int[] SIGNATURE = {0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1};

    private final byte[] data;
    private final int major;
    private final int sectorSize;
    private final int miniSectorSize;
    private final int miniCutoff;
    private final int[] fat;
    private final int[] miniFat;
    private final List<Entry> entries = new ArrayList<>();
    private final byte[] miniStream;

    /** A single directory entry (storage, stream, or root). */
    static final class Entry {
        final String name;
        final int type;        // 1 storage, 2 stream, 5 root
        final int startSector;
        final long size;

        Entry(String name, int type, int startSector, long size) {
            this.name = name;
            this.type = type;
            this.startSector = startSector;
            this.size = size;
        }
    }

    public CompoundFileReader(@NonNull byte[] bytes) throws IOException {
        this.data = bytes;
        if (bytes.length < 512 || !hasSignature()) {
            throw new IOException("Not a compound file (bad CFB signature)");
        }
        this.major = u16(26);
        int sectorShift = u16(30);
        int miniShift = u16(32);
        if (sectorShift < 7 || sectorShift > 20 || miniShift < 4 || miniShift > sectorShift) {
            throw new IOException("Unsupported CFB sector shift");
        }
        this.sectorSize = 1 << sectorShift;
        this.miniSectorSize = 1 << miniShift;
        long cutoff = u32(56);
        this.miniCutoff = (cutoff > 0 && cutoff <= Integer.MAX_VALUE) ? (int) cutoff : 4096;

        int firstDir = i32(48);
        int firstMiniFat = i32(60);
        int firstDifat = i32(68);

        // DIFAT: the first 109 FAT sector locations live in the header, the
        // rest chain through dedicated DIFAT sectors.
        //
        // The header-supplied numDifat is NOT trusted as a loop bound: a crafted
        // header (huge numDifat + a self-referencing DIFAT sector) would grow the
        // list without limit and OutOfMemoryError. Instead the walk is bounded by
        // the file's real capacity - a FAT cannot describe more sectors than the
        // file physically has - and revisited DIFAT sectors break the loop.
        int perSector = sectorSize / 4;
        int maxSectors = Math.max(1, data.length / sectorSize);
        int maxFatSectors = maxSectors / Math.max(1, perSector) + 2;
        List<Integer> difat = new ArrayList<>();
        for (int i = 0; i < 109 && difat.size() < maxFatSectors; i++) {
            int v = i32(76 + i * 4);
            if (v >= 0) difat.add(v);
        }
        java.util.HashSet<Integer> seenDifat = new java.util.HashSet<>();
        int sec = firstDifat;
        while (sec >= 0 && difat.size() < maxFatSectors && seenDifat.add(sec)) {
            int base = sectorOffset(sec);
            requireRange(base, sectorSize);
            for (int i = 0; i < perSector - 1 && difat.size() < maxFatSectors; i++) {
                int v = i32(base + i * 4);
                if (v >= 0) difat.add(v);
            }
            sec = i32(base + (perSector - 1) * 4);
        }

        // FAT: concatenate every FAT sector referenced by the DIFAT.
        this.fat = new int[difat.size() * perSector];
        int fi = 0;
        for (int fatSec : difat) {
            int base = sectorOffset(fatSec);
            requireRange(base, sectorSize);
            for (int i = 0; i < perSector; i++) {
                fat[fi++] = i32(base + i * 4);
            }
        }

        // Mini FAT: its sectors are chained through the main FAT.
        this.miniFat = readIntArrayChain(firstMiniFat);

        // Directory: 128-byte entries, sectors chained through the main FAT.
        for (int dirSec : chain(firstDir)) {
            int base = sectorOffset(dirSec);
            if (base < 0) continue;
            int count = sectorSize / 128;
            for (int i = 0; i < count; i++) {
                Entry e = readDirEntry(base + i * 128);
                if (e != null) entries.add(e);
            }
        }

        Entry root = null;
        for (Entry e : entries) {
            if (e.type == 5) { root = e; break; }
        }
        if (root == null) throw new IOException("CFB has no root entry");
        // The root entry points at the mini stream container (holds all
        // sub-cutoff streams packed into miniSectorSize chunks).
        this.miniStream = readMainChain(root.startSector, root.size);
    }

    /** Returns the whole content of the named stream, or null if absent. */
    @Nullable
    public byte[] getStream(@NonNull String name) throws IOException {
        Entry entry = null;
        for (Entry e : entries) {
            if (e.type == 2 && name.equals(e.name)) { entry = e; break; }
        }
        if (entry == null) return null;
        if (entry.size >= miniCutoff) {
            return readMainChain(entry.startSector, entry.size);
        }
        return readMiniChain(entry.startSector, entry.size);
    }

    // ---- internals ----

    private boolean hasSignature() {
        for (int i = 0; i < SIGNATURE.length; i++) {
            if ((data[i] & 0xFF) != SIGNATURE[i]) return false;
        }
        return true;
    }

    private int sectorOffset(int sector) {
        // Compute in long so a large (crafted) sector number cannot overflow int
        // and wrap into a valid-looking offset. Out-of-range returns -1, which
        // requireRange then rejects with a clean IOException.
        long off = 512L + (long) sector * sectorSize;
        return (sector < 0 || off < 0 || off > Integer.MAX_VALUE) ? -1 : (int) off;
    }

    private int[] chain(int start) throws IOException {
        List<Integer> out = new ArrayList<>();
        int s = start;
        int guard = 0;
        int limit = fat.length + 16;
        while (s >= 0 && guard++ < limit) {
            out.add(s);
            s = (s < fat.length) ? fat[s] : -1;
        }
        if (s >= 0) throw new IOException("CFB sector chain too long (corrupt file)");
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    private int[] readIntArrayChain(int start) throws IOException {
        int[] sectors = chain(start);
        int perSector = sectorSize / 4;
        int[] arr = new int[sectors.length * perSector];
        int idx = 0;
        for (int s : sectors) {
            int base = sectorOffset(s);
            requireRange(base, sectorSize);
            for (int i = 0; i < perSector; i++) arr[idx++] = i32(base + i * 4);
        }
        return arr;
    }

    private byte[] readMainChain(int start, long size) throws IOException {
        int[] sectors = chain(start);
        int total = sectors.length * sectorSize;
        byte[] out = new byte[total];
        int pos = 0;
        for (int s : sectors) {
            int base = sectorOffset(s);
            requireRange(base, sectorSize);
            System.arraycopy(data, base, out, pos, sectorSize);
            pos += sectorSize;
        }
        return trim(out, size);
    }

    private byte[] readMiniChain(int start, long size) throws IOException {
        List<Integer> sectors = new ArrayList<>();
        int s = start;
        int guard = 0;
        int limit = miniFat.length + 16;
        while (s >= 0 && guard++ < limit) {
            sectors.add(s);
            s = (s < miniFat.length) ? miniFat[s] : -1;
        }
        if (s >= 0) throw new IOException("CFB mini chain too long (corrupt file)");
        byte[] out = new byte[sectors.size() * miniSectorSize];
        int pos = 0;
        for (int mini : sectors) {
            long off = (long) mini * miniSectorSize;
            if (mini < 0 || off + miniSectorSize > miniStream.length) {
                throw new IOException("CFB mini sector out of range");
            }
            System.arraycopy(miniStream, (int) off, out, pos, miniSectorSize);
            pos += miniSectorSize;
        }
        return trim(out, size);
    }

    @Nullable
    private Entry readDirEntry(int off) throws IOException {
        requireRange(off, 128);
        int nameLen = u16(off + 64);
        int type = data[off + 66] & 0xFF;
        if (type != 1 && type != 2 && type != 5) return null;
        String name = "";
        if (nameLen >= 2) {
            int chars = Math.min(64, nameLen) - 2;
            if (chars > 0) name = new String(data, off, chars, java.nio.charset.StandardCharsets.UTF_16LE);
        }
        int startSector = i32(off + 116);
        long size = u32(off + 120);
        if (major != 3) {
            size |= u32(off + 124) << 32;
        }
        return new Entry(name, type, startSector, size);
    }

    private static byte[] trim(byte[] src, long size) {
        int n = (size < 0 || size > src.length) ? src.length : (int) size;
        if (n == src.length) return src;
        byte[] out = new byte[n];
        System.arraycopy(src, 0, out, 0, n);
        return out;
    }

    private void requireRange(int off, int len) throws IOException {
        if (off < 0 || (long) off + len > data.length) {
            throw new IOException("CFB read out of range");
        }
    }

    private int u16(int off) {
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
    }

    private int i32(int off) {
        return (data[off] & 0xFF)
                | ((data[off + 1] & 0xFF) << 8)
                | ((data[off + 2] & 0xFF) << 16)
                | ((data[off + 3] & 0xFF) << 24);
    }

    private long u32(int off) {
        return i32(off) & 0xFFFFFFFFL;
    }
}
