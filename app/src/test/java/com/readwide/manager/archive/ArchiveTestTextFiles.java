package com.readwide.manager.archive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** UTF-8 fixture I/O using APIs exposed to Android local-test compilation. */
final class ArchiveTestTextFiles {
    private ArchiveTestTextFiles() {}

    static String readString(Path path) throws IOException {
        // Match the original strict UTF-8 read, including malformed-input errors.
        return StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
    }

    static Path writeString(Path path, CharSequence text) throws IOException {
        // Encode before opening the destination: invalid UTF-16 must not truncate
        // an existing fixture. Files.write retains create/truncate/write defaults.
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return Files.write(path, bytes);
    }
}
