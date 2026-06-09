package com.textview.reader.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;

public class RarHeaderEncryptionDetectorTest {
    private static final byte[] RAR4_SIGNATURE = new byte[] {
            0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00
    };
    private static final byte[] RAR5_SIGNATURE = new byte[] {
            0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00
    };
    private static final int RAR4_HEADER_MAIN = 0x73;
    private static final int RAR4_MAIN_PASSWORD = 0x0080;

    @Test
    public void rar4MainPasswordFlag_isDetectedAsHeaderEncrypted() throws Exception {
        File rar = File.createTempFile("textview-rar4-hp-", ".rar");
        try {
            try (FileOutputStream out = new FileOutputStream(rar)) {
                out.write(RAR4_SIGNATURE);
                writeRar4Header(out, RAR4_HEADER_MAIN, RAR4_MAIN_PASSWORD, new byte[6]);
            }
            assertTrue(RarHeaderEncryptionDetector.hasEncryptedHeaders(rar));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            rar.delete();
        }
    }

    @Test
    public void rar4NormalMainHeader_isNotHeaderEncrypted() throws Exception {
        File rar = File.createTempFile("textview-rar4-normal-", ".rar");
        try {
            try (FileOutputStream out = new FileOutputStream(rar)) {
                out.write(RAR4_SIGNATURE);
                writeRar4Header(out, RAR4_HEADER_MAIN, 0, new byte[6]);
            }
            assertFalse(RarHeaderEncryptionDetector.hasEncryptedHeaders(rar));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            rar.delete();
        }
    }

    @Test
    public void rar5EncryptionHeader_isDetectedAsHeaderEncrypted() throws Exception {
        File rar = File.createTempFile("textview-rar5-hp-", ".rar");
        try {
            try (FileOutputStream out = new FileOutputStream(rar)) {
                out.write(RAR5_SIGNATURE);
                writeUInt32LE(out, 0); // CRC is not needed for detector-only parsing.
                out.write(2); // header data length: type vint + flags vint.
                out.write(4); // HEADER_ENCRYPTION.
                out.write(0); // flags.
            }
            assertTrue(RarHeaderEncryptionDetector.hasEncryptedHeaders(rar));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            rar.delete();
        }
    }

    private static void writeRar4Header(FileOutputStream out,
                                        int type,
                                        int flags,
                                        byte[] data) throws Exception {
        writeUInt16LE(out, 0); // CRC is advisory for detector-only tests.
        out.write(type & 0xff);
        writeUInt16LE(out, flags);
        writeUInt16LE(out, 7 + data.length);
        out.write(data);
    }

    private static void writeUInt16LE(FileOutputStream out, int value) throws Exception {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeUInt32LE(FileOutputStream out, long value) throws Exception {
        out.write((int) (value & 0xff));
        out.write((int) ((value >>> 8) & 0xff));
        out.write((int) ((value >>> 16) & 0xff));
        out.write((int) ((value >>> 24) & 0xff));
    }
}
