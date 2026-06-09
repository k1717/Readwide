package com.textview.reader.archive;

import java.io.IOException;

/**
 * Supplies already decoded RAR3/RAR4 PPMd symbols to the PPM control layer.
 *
 * <p>This is deliberately separated from the PPMd statistical model/range coder. Pass 30
 * implements the RAR-specific control semantics that sit <em>after</em> PPMd symbol decoding,
 * while the actual PPMd model core remains a first-party gap.</p>
 */
interface Rar3PpmdSymbolSource {
    /**
     * Returns the next decoded byte value in {@code 0..255}, or {@code -1} if the PPMd model
     * reports corrupt/end-of-stream data.
     */
    int decodeSymbol() throws IOException;
}
