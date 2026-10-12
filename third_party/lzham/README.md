# LZHAM decoder source for Readwide

- Upstream: [richgel999/lzham_codec_devel](https://github.com/richgel999/lzham_codec_devel).
- Pin: `d09fc6676a0313c3814457fbc76351a68f653092`.
- License: MIT, Richard Geldreich, Jr.; full [LICENSE](LICENSE) retained.
- Selection: `include/lzham.h`, all 29 C++/header files in `lzhamdecomp`,
  and the license (31 upstream files). No compressor translation units,
  CLI, 7-Zip plugin/engine, binaries or generated payloads are imported.
  Shared model helpers include upstream encoding functions, discarded when unused.
- Original file hashes: [SHA256SUMS.upstream](SHA256SUMS.upstream).
  Packaged source hashes: [SHA256SUMS](SHA256SUMS).

## Local changes

Four files have behavioral/declaration changes. `lzham_mem.cpp` checks context allocation before
placement construction and arena-growth allocation before copying. The decoder's
`lzham_lzdecomp.cpp` checks the returned context before use. `lzham_assert.cpp`
uses a caught C++ exception instead of process exit under `READWIDE_LZHAM_NO_EXIT`.
The decoder also rejects prefetched bytes after the byte-aligned Adler32 under
`READWIDE_LZHAM_STRICT_END`; consumed-input counts alone cannot detect them.
`lzham_core.h` explicitly includes `<new>` for placement new. These changes do
not change codec tables; end acceptance is tightened. They are not a complete native audit.

The no-exit branch now omits its unused exit flag and explicitly marks the unused
file/line arguments. `READWIDE_LZHAM_DECODE_ONLY` identifies the decoder-only target
so the portable-platform compressor-threading warning remains enabled for other
builds but is not emitted for this intentional decoder configuration. No threading,
exception message, decoding algorithm or failure behavior changes.

`lzham_config.h` and `lzham_timer.cpp` only gain a final newline during import.
Thus 25 files are byte-identical, two differ only by that newline, and four have
the marked code changes above; both hash manifests preserve this distinction.

The native module builds eleven decoder/common translation units with the ANSI
portable path, exceptions, hidden symbols and upstream-required no-strict-aliasing.
Its separate `readwide-lzham` JNI target links the NDK static C++ runtime privately;
no C++ types, allocations or exceptions cross JNI. Existing C-only codec targets
remain C-only. Verify final ELF/runtime dependencies when building.

## Container profile

The original [LzhamRegister.cpp](https://github.com/richgel999/lzham_codec_devel/blob/d09fc6676a0313c3814457fbc76351a68f653092/lzham7zip/LzhamRegister.cpp)
provides method `04F71001` and five-byte properties: low DLL-version byte,
dictionary exponent, encoder level/flags, reserved byte. This pin uses `0x11`.
Readwide also admits `0x10` using upstream's pinned v1.x bitstream compatibility
contract, the author's v1.0 property example and matching default table settings;
see the [evidence and exact scope](../../docs/LZHAM_INTEGRATION_1_0_20.md#version-10-property-compatibility).
Dictionary zero means 26, otherwise 15–29. The decoder does not use the final
three descriptive bytes. Unknown/alpha versions, experimental method `090101`,
custom tables/seed dictionaries and standalone wrappers remain outside this
integration. Dictionary exponents are codec parameters, not output caps.

The Apache-2.0 Readwide adapter streams with bounded I/O buffers, checks Adler32
in addition to the container CRC, and never enables the upstream zero-padding EOF
mode. Explicit end, exact output and packed exhaustion are required. Failed state
cannot resume; source ownership, cancellation and reentrant-close protection remain.

The maintainer's 2026-10-11 log successfully built Release and the app Android-test
APK and compiled local tests, including ARMv7/ARM64 native dependencies. It predates
the warning cleanup above. No tests, fuzzing or device decoding were run by this
audit; module-only test sources are not certified by the app test-APK build.
Raw-block synthetic fixtures do not exercise compressed Huffman/
LZ matches or prove interoperability. Producer/compressed/solid/split/BCJ2 and both
Android ABIs remain validation work. See [integration status](../../docs/LZHAM_INTEGRATION_1_0_20.md).
