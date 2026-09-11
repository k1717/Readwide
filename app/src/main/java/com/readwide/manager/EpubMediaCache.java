package com.readwide.manager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Worker-thread audio cache. Paths passed here are decoded ZIP names, not URI strings. */
final class EpubMediaCache {
    private EpubMediaCache() { }

    static synchronized File extract(ZipFile zip, String path, File directory,
                                      String publicationKey, long maxBytes) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null || entry.isDirectory()) throw new IOException("EPUB overlay audio is missing");
        if (maxBytes < 0 || entry.getSize() > maxBytes) throw new IOException("EPUB overlay audio exceeds size limit");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Unable to create EPUB media cache");
        String key = cacheKey(publicationKey, path, entry.getSize(), entry.getCrc());
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String extension = dot >= 0 ? lower.substring(dot) : "";
        if (!extension.matches("\\.[a-z0-9]{1,5}")) extension = ".bin";
        File output = new File(directory, key + extension);
        if (isValid(output, entry.getSize(), entry.getCrc(), maxBytes)) return output;

        File temporary = File.createTempFile(key + "-", ".partial", directory);
        boolean committed = false;
        try {
            CRC32 crc = new CRC32();
            long total = 0;
            try (InputStream input = zip.getInputStream(entry);
                 FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("EPUB audio extraction cancelled");
                    if (read > maxBytes - total) throw new IOException("EPUB overlay audio exceeds size limit");
                    total += read;
                    if (entry.getSize() >= 0 && total > entry.getSize()) throw new IOException("EPUB audio size mismatch");
                    out.write(buffer, 0, read);
                    crc.update(buffer, 0, read);
                }
            }
            if ((entry.getSize() >= 0 && total != entry.getSize())
                    || (entry.getCrc() >= 0 && crc.getValue() != entry.getCrc())) {
                throw new IOException("EPUB audio checksum or size mismatch");
            }
            if (output.exists() && !output.delete()) throw new IOException("Unable to replace EPUB media cache");
            if (!temporary.renameTo(output)) throw new IOException("Unable to finalize EPUB media cache");
            committed = true;
            return output;
        } finally {
            if (!committed) temporary.delete();
        }
    }

    static String cacheKey(String publication, String path, long size, long crc) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Length framing prevents ambiguous publication/path concatenation.
            for (String value : new String[] {publication, path, Long.toString(size), Long.toString(crc)}) {
                byte[] data = value.getBytes(StandardCharsets.UTF_8);
                for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (data.length >>> shift));
                digest.update(data);
            }
            StringBuilder key = new StringBuilder(64);
            for (byte value : digest.digest()) {
                key.append(Character.forDigit((value >>> 4) & 15, 16));
                key.append(Character.forDigit(value & 15, 16));
            }
            return key.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 unavailable", failure);
        }
    }

    private static boolean isValid(File file, long size, long expectedCrc, long maxBytes) throws IOException {
        if (size < 0 || expectedCrc < 0 || !file.isFile() || file.length() != size || size > maxBytes) return false;
        CRC32 crc = new CRC32();
        long total = 0;
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("EPUB audio verification cancelled");
                if (read > size - total) return false;
                total += read;
                crc.update(buffer, 0, read);
            }
        }
        return total == size && crc.getValue() == expectedCrc;
    }
}
