package com.readwide.manager.archive;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Metadata-only CAB set discovery. Payloads must use a real cabinet-set decoder,
 * never byte concatenation. Public linked-CAB routes use this complete set.
 */
final class CabVolumeResolver {
    private CabVolumeResolver() {}

    /** Small probe only; full metadata validation belongs to resolve/readHeader. */
    static boolean hasSignature(File file) throws IOException {
        ArchiveCancellation.checkpoint(null);
        try (RandomAccessFile input = new RandomAccessFile(file,"r")) {
            return input.length() >= 4 && input.readInt() == 0x4d534346;
        }
    }

    /** A split CAB is linked by CFHEADER, not by an inferred numeric filename. */
    static boolean hasVolumeLinks(File file) throws IOException {
        ArchiveCancellation.checkpoint(null);
        try (RandomAccessFile input = new RandomAccessFile(file,"r")) {
            if (input.length() < 36 || input.readInt() != 0x4d534346) return false;
            input.seek(30);
            int flags = input.readUnsignedByte() | (input.readUnsignedByte() << 8);
            input.seek(34);
            int index = input.readUnsignedByte() | (input.readUnsignedByte() << 8);
            return (flags & 3) != 0 || index != 0;
        }
    }

    // Link fields have no code-page tag. Accept only an unambiguous existing sibling
    // under strict round-trip decoding; no replacement characters or guessed path.
    private static final String[] LINK_ENCODINGS = {
            "UTF-8", "windows-1252", "IBM437", "MS949", "Shift_JIS", "GB18030", "Big5"
    };

    static final class Header {
        final File file;
        final long cabinetBytes, physicalBytes, modified, fileTableOffset;
        final int setId, index, folders, files, folderReserve, dataReserve;
        private final byte[] previousName, nextName;

        Header(File file, long cabinetBytes, long physicalBytes, long modified,
                long fileTableOffset, int setId, int index, int folders, int files,
                int folderReserve, int dataReserve, byte[] previousName, byte[] nextName) {
            this.file = file; this.cabinetBytes = cabinetBytes; this.physicalBytes = physicalBytes;
            this.modified = modified; this.fileTableOffset = fileTableOffset;
            this.setId = setId; this.index = index; this.folders = folders; this.files = files;
            this.folderReserve = folderReserve; this.dataReserve = dataReserve;
            this.previousName = previousName; this.nextName = nextName;
        }

        void requireUnchanged() throws IOException {
            ArchiveCancellation.checkpoint(null);
            if (!file.isFile() || !file.canRead() || !file.equals(file.getCanonicalFile())
                    || file.length() != physicalBytes || file.lastModified() != modified) {
                throw new IOException("CAB volume changed or became unavailable: " + file.getName());
            }
        }
    }

    static final class VolumeSet {
        final List<Header> headers;
        final List<File> parts;

        VolumeSet(List<Header> headers) {
            this.headers = Collections.unmodifiableList(new ArrayList<>(headers));
            List<File> files = new ArrayList<>(headers.size());
            for (Header header : headers) files.add(header.file);
            parts = Collections.unmodifiableList(files);
        }

        void requireUnchanged() throws IOException {
            for (Header header : headers) header.requireUnchanged();
        }
    }

    /** Follow explicit links from any selected part; do not infer numbered names. */
    static VolumeSet resolve(File selected) throws IOException {
        ArchiveCancellation.checkpoint(null);
        File absolute = selected.getAbsoluteFile();
        File parent = absolute.getParentFile();
        if (parent == null) throw new IOException("CAB volume has no parent directory");
        parent = parent.getCanonicalFile();
        Catalogue catalogue = new Catalogue(parent);
        File canonical = requireSibling(parent, absolute);
        Map<File, Header> headers = new HashMap<>();
        Header selectedHeader = load(canonical, headers);
        Set<File> visited = new HashSet<>(); visited.add(canonical);
        List<Header> ordered = new ArrayList<>(); ordered.add(selectedHeader);
        Header current = selectedHeader;
        while (current.previousName != null) {
            ArchiveCancellation.checkpoint(null);
            Header previous = load(catalogue.resolve(current.previousName), headers);
            if (!visited.add(previous.file)) throw new IOException("Cyclic CAB volume links");
            requireAdjacent(previous, current, catalogue);
            ordered.add(previous); current = previous;
        }
        if (current.index != 0) throw new IOException("Missing first CAB volume");
        Collections.reverse(ordered);
        current = selectedHeader;
        while (current.nextName != null) {
            ArchiveCancellation.checkpoint(null);
            Header next = load(catalogue.resolve(current.nextName), headers);
            if (!visited.add(next.file)) throw new IOException("Cyclic CAB volume links");
            requireAdjacent(current, next, catalogue);
            ordered.add(next); current = next;
        }
        VolumeSet result = new VolumeSet(ordered);
        result.requireUnchanged();
        return result;
    }

    private static Header load(File file, Map<File, Header> cache) throws IOException {
        Header result = cache.get(file);
        if (result == null) { result = readHeader(file); cache.put(file, result); }
        return result;
    }

    private static void requireAdjacent(Header left, Header right, Catalogue catalogue) throws IOException {
        if (left.setId != right.setId || (long) left.index + 1 != right.index) {
            throw new IOException("CAB volumes have different set IDs or nonconsecutive indices");
        }
        if (left.nextName == null || right.previousName == null
                || !catalogue.resolve(left.nextName).equals(right.file)
                || !catalogue.resolve(right.previousName).equals(left.file)) {
            throw new IOException("CAB volume links are not reciprocal");
        }
    }

    private static File requireSibling(File parent, File candidate) throws IOException {
        ArchiveCancellation.checkpoint(null);
        File canonical = candidate.getCanonicalFile();
        if (!parent.equals(canonical.getParentFile())) throw new IOException("CAB volume leaves selected directory");
        if (!canonical.isFile() || !canonical.canRead()) {
            throw new IOException("CAB volume is unavailable: " + candidate.getName());
        }
        return canonical;
    }

    /** One directory enumeration, with duplicate/case/encoding ambiguity kept visible. */
    private static final class Catalogue {
        final File parent;
        final Map<String, List<File>> names = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        Catalogue(File parent) throws IOException {
            this.parent = parent;
            File[] children = parent.listFiles();
            if (children == null) throw new IOException("CAB volume directory is unavailable");
            for (File child : children) {
                ArchiveCancellation.checkpoint(null);
                List<File> matches = names.get(child.getName());
                if (matches == null) { matches = new ArrayList<>(); names.put(child.getName(), matches); }
                matches.add(child);
            }
        }

        File resolve(byte[] encoded) throws IOException {
            Set<String> spellings = new LinkedHashSet<>();
            for (String encoding : LINK_ENCODINGS) {
                ArchiveCancellation.checkpoint(null);
                if (!Charset.isSupported(encoding)) continue;
                Charset charset = Charset.forName(encoding);
                try {
                    String decoded = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(encoded)).toString();
                    ByteBuffer roundTrip = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(decoded));
                    byte[] bytes = new byte[roundTrip.remaining()]; roundTrip.get(bytes);
                    if (Arrays.equals(bytes, encoded) && safeLeaf(decoded)) spellings.add(decoded);
                } catch (CharacterCodingException invalidEncoding) {
                    // Try another lossless representation, never substitute unknown bytes.
                }
            }
            Set<File> matches = new LinkedHashSet<>();
            for (String spelling : spellings) {
                List<File> candidates = names.get(spelling);
                if (candidates == null) continue;
                // Reject case aliases even if this filesystem canonicalizes to one spelling.
                if (candidates.size() != 1) throw new IOException("Ambiguous CAB volume name");
                matches.add(candidates.get(0));
            }
            if (matches.size() != 1) {
                throw new IOException(matches.isEmpty()
                        ? "Missing CAB volume or unsafe/unknown link encoding" : "Ambiguous CAB volume name encoding");
            }
            return requireSibling(parent, matches.iterator().next());
        }
    }

    private static boolean safeLeaf(String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || name.endsWith(".") || name.endsWith(" ")) return false;
        for (int i=0; i<name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || c == 127 || c == '/' || c == '\\' || c == ':') return false;
        }
        return true;
    }

    static Header readHeader(File source) throws IOException {
        ArchiveCancellation.checkpoint(null);
        File file = source.getCanonicalFile();
        long modified = file.lastModified();
        try (RandomAccessFile input = new RandomAccessFile(file,"r")) {
            long physical = input.length();
            byte[] fixed = new byte[36]; input.readFully(fixed);
            if (u32(fixed,0) != 0x4643534dL) throw new IOException("Invalid CAB signature");
            if (u32(fixed,4) != 0 || u32(fixed,12) != 0 || u32(fixed,20) != 0) {
                throw new IOException("Invalid CAB reserved header fields");
            }
            if ((fixed[24] & 255) != 3 || (fixed[25] & 255) != 1) throw new IOException("Unsupported CAB version");
            long cabinet = u32(fixed,8), fileOffset = u32(fixed,16);
            int folders = u16(fixed,26), files = u16(fixed,28), flags = u16(fixed,30);
            int set = u16(fixed,32), index = u16(fixed,34);
            if (cabinet < 36 || cabinet > physical || fileOffset < 36 || fileOffset > cabinet
                    || (flags & ~7) != 0) throw new IOException("Invalid or truncated CAB header extent");
            int folderReserve = 0, dataReserve = 0;
            if ((flags & 4) != 0) {
                if (fileOffset - input.getFilePointer() < 4) throw new EOFException("Truncated CAB reserve header");
                int headerReserve = input.readUnsignedByte() | (input.readUnsignedByte() << 8);
                folderReserve = input.readUnsignedByte(); dataReserve = input.readUnsignedByte();
                if (headerReserve > fileOffset - input.getFilePointer()) throw new EOFException("Truncated CAB reserved data");
                input.seek(input.getFilePointer() + headerReserve);
            }
            long linksEnd = fileOffset - (long) folders * (8 + folderReserve);
            if (linksEnd < input.getFilePointer() || (long) files * 17 > cabinet - fileOffset) {
                throw new IOException("CAB metadata tables exceed cabinet extent");
            }
            byte[] previous = null, next = null;
            if ((flags & 1) != 0) {
                previous = readString(input,linksEnd,false); readString(input,linksEnd,true);
            }
            if ((flags & 2) != 0) {
                next = readString(input,linksEnd,false); readString(input,linksEnd,true);
            }
            if ((index == 0 && previous != null) || (index == 65535 && next != null)) {
                throw new IOException("CAB volume index cannot continue in the declared direction");
            }
            Header header = new Header(file,cabinet,physical,modified,fileOffset,set,index,folders,files,
                    folderReserve,dataReserve,previous,next);
            header.requireUnchanged();
            return header;
        }
    }

    private static byte[] readString(RandomAccessFile input,long boundary,boolean allowEmpty) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        // libmspack's CAB string API admits a terminator within 256 bytes. This is
        // header metadata compatibility, not a file/extraction payload-size quota.
        for (int i=0; i<256; i++) {
            ArchiveCancellation.checkpoint(null);
            if (input.getFilePointer() >= boundary) throw new EOFException("Truncated CAB link field");
            int value = input.readUnsignedByte();
            if (value == 0) {
                if (bytes.size() == 0 && !allowEmpty) throw new IOException("Empty CAB volume link");
                return bytes.toByteArray();
            }
            bytes.write(value);
        }
        throw new IOException("CAB link field exceeds decoder metadata range");
    }

    private static int u16(byte[] bytes,int offset) { return (bytes[offset]&255) | ((bytes[offset+1]&255)<<8); }
    private static long u32(byte[] bytes,int offset) { return (u16(bytes,offset) | ((long)u16(bytes,offset+2)<<16)); }
}
