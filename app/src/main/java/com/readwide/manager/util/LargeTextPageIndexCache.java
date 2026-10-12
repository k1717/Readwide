package com.readwide.manager.util;

import com.readwide.manager.view.CustomReaderView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

/** Disposable exact anchors, never a substitute for the reader's pagination algorithm. */
public final class LargeTextPageIndexCache {
    private static final int MAGIC = 0x54585031; // TXP1; bump if serialized semantics change.
    // Stream the v1 payload: a large exact index must not silently lose persistence
    // merely because its per-page text context exceeds a whole-buffer size budget.
    private static final int IO_BUFFER_BYTES = 64 * 1024;

    private LargeTextPageIndexCache() {}

    /** Configuration.toString includes a bookkeeping sequence, not a layout input.
     * Keep all other fields (including OEM font fields/assets/window state) intact. */
    public static String configurationKey(String description) {
        if (description == null) return "";
        // Literal parsing avoids Android ICU / desktop regex syntax differences.
        // This helper runs on the UI thread before the indexing worker starts.
        StringBuilder key = null;
        int copiedThrough = 0, searchFrom = 0;
        int start;
        while ((start = description.indexOf(" s.", searchFrom)) >= 0) {
            int end = start + 3;
            if (end < description.length() && description.charAt(end) == '-') end++;
            int digitsStart = end;
            while (end < description.length()
                    && description.charAt(end) >= '0' && description.charAt(end) <= '9') end++;
            searchFrom = end;
            // Strip only a complete sequence token. Preserve unknown OEM fields,
            // malformed values and assetsSeq (" as.") rather than weakening the key.
            if (end > digitsStart && end < description.length()
                    && (description.charAt(end) == ' ' || description.charAt(end) == '}')) {
                if (key == null) key = new StringBuilder(description.length());
                key.append(description, copiedThrough, start);
                copiedThrough = end;
            }
        }
        return key == null ? description : key.append(description, copiedThrough, description.length()).toString();
    }

    public interface Checkpoint { void check() throws IOException; }
    public interface ContentDigest { byte[] read() throws IOException; }

    public static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Full bytes, not a size/mtime/sample shortcut; executed only on a background worker. */
    public static byte[] fingerprint(File source, Checkpoint checkpoint) throws IOException {
        long length = source.length(), modified = source.lastModified();
        MessageDigest digest = newDigest();
        try (FileInputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkpoint.check();
                digest.update(buffer, 0, count);
            }
        }
        checkpoint.check();
        if (source.length() != length || source.lastModified() != modified) {
            throw new IOException("TXT changed while checking cached pages");
        }
        return digest.digest();
    }

    /** Null is a cache miss, including malformed/truncated/old data. Cancellation propagates. */
    public static ArrayList<CustomReaderView.PageTextAnchor> load(
            File file, String signature, byte[] contentDigest, int totalChars,
            Checkpoint checkpoint) throws IOException {
        return load(file, signature, totalChars, checkpoint, () -> contentDigest);
    }

    /** Do not re-read source bytes for an absent/corrupt/different-layout cache. */
    public static ArrayList<CustomReaderView.PageTextAnchor> load(
            File file, String signature, int totalChars, Checkpoint checkpoint,
            ContentDigest contentDigest) throws IOException {
        checkpoint.check();
        if (file == null || !file.isFile()) return null;
        long cacheLength = file.length();
        if (cacheLength < 48 || totalChars < 0) return null;
        try (FileInputStream source = new FileInputStream(file)) {
            // Validate before allocating anchors or hashing the TXT. Rewind the
            // same open file so a concurrent atomic replacement cannot switch
            // between different cache files during checksum validation/parsing.
            long checksum = verifyChecksum(source, cacheLength, checkpoint);
            if (checksum < 0) return null;
            source.getChannel().position(0L);
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(source, IO_BUFFER_BYTES))) {
                if (input.readInt() != MAGIC || !signature.equals(input.readUTF())) return null;
                byte[] savedDigest = new byte[32];
                input.readFully(savedDigest);
                if (input.readInt() != totalChars) return null;
                int count = input.readInt();
                // Strictly increasing positions in [0, totalChars] and at least
                // eight serialized bytes per record bound an untrusted count.
                if (count <= 0 || count > (long) totalChars + 1L
                        || count > (cacheLength - 48L) / 8L) return null;
                if (!MessageDigest.isEqual(contentDigest.read(), savedDigest)) return null;
                ArrayList<CustomReaderView.PageTextAnchor> anchors = new ArrayList<>(Math.min(count, 4096));
                int previous = -1;
                for (int i = 0; i < count; i++) {
                    checkpoint.check();
                    int position = input.readInt();
                    String before = input.readUTF(), after = input.readUTF();
                    if (position <= previous || position > totalChars || (i == 0 && position != 0)
                            || before.length() > 81 || after.length() > 121) return null;
                    anchors.add(new CustomReaderView.PageTextAnchor(position, before, after));
                    previous = position;
                }
                return input.readLong() == checksum && input.read() == -1 ? anchors : null;
            }
        } catch (InterruptedIOException cancelled) {
            throw cancelled;
        } catch (IOException | RuntimeException invalidCache) {
            checkpoint.check();
            return null;
        }
    }

    /** A checksum mismatch is a cache miss; cancellation must still propagate. */
    private static long verifyChecksum(FileInputStream source, long length,
            Checkpoint checkpoint) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[IO_BUFFER_BYTES];
        long remaining = length - 8L;
        while (remaining > 0L) {
            checkpoint.check();
            int count = source.read(buffer, 0, (int) Math.min(remaining, buffer.length));
            if (count < 0) return -1L;
            crc.update(buffer, 0, count);
            remaining -= count;
        }
        checkpoint.check();
        long saved = new DataInputStream(source).readLong();
        return saved == crc.getValue() && source.read() == -1 ? saved : -1L;
    }

    public static synchronized void save(File file, String signature, byte[] contentDigest,
            int totalChars, ArrayList<CustomReaderView.PageTextAnchor> anchors,
            Checkpoint checkpoint) throws IOException {
        checkpoint.check();
        if (file == null || anchors.isEmpty() || contentDigest.length != 32 || totalChars < 0) return;
        File parent = file.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return;
        File temporary = File.createTempFile("pages-", ".tmp", parent);
        try {
            CRC32 crc = new CRC32();
            try (DataOutputStream output = new DataOutputStream(new CheckedOutputStream(
                    new BufferedOutputStream(new FileOutputStream(temporary), IO_BUFFER_BYTES), crc))) {
                output.writeInt(MAGIC);
                output.writeUTF(signature);
                output.write(contentDigest);
                output.writeInt(totalChars);
                output.writeInt(anchors.size());
                int previous = -1;
                for (CustomReaderView.PageTextAnchor anchor : anchors) {
                    checkpoint.check();
                    if (anchor.charPosition <= previous || anchor.charPosition > totalChars
                            || (previous < 0 && anchor.charPosition != 0)
                            || anchor.anchorTextBefore.length() > 81 || anchor.anchorTextAfter.length() > 121) return;
                    output.writeInt(anchor.charPosition);
                    output.writeUTF(anchor.anchorTextBefore);
                    output.writeUTF(anchor.anchorTextAfter);
                    previous = anchor.charPosition;
                }
                // Snapshot before appending the footer; it is not part of its own CRC.
                long checksum = crc.getValue();
                output.writeLong(checksum);
            }
            checkpoint.check();
            // Publish only a complete file. A concurrent read/crash between replacement
            // steps is a cache miss; readers validate their private copy before use.
            if (file.exists() && !file.delete()) return;
            if (!temporary.renameTo(file)) throw new IOException("Cannot publish TXT page cache");
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }
}
