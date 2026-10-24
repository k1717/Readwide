# libmspack CAB decoder integration

Upstream: [kyz/libmspack](https://github.com/kyz/libmspack), commit
`55d501976171397ccd5d5a7a1ca7da065b1d9a06` (2026-07-21).
The upstream README identifies 0.11alpha; this commit contains later fixes.
License: LGPL-2.1; keep [COPYING.LIB](COPYING.LIB), per-file copyright headers and
[upstream README](README.upstream), including its additional incorporation terms.

The 14 C/header files are the `libmscabd_la_SOURCES` subset in upstream
`libmspack/Makefile.am`: CAB parsing, stored/MSZIP/LZX/Quantum decoding, public API
and shared I/O/bit/Huffman helpers. No compressor or cabextract executable is included.
Thirteen C/header files are unchanged. `cabd.c` has a marked LGPL-2.1 Readwide
modification: with `READWIDE_CAB_STRICT_SET`, continuation entries must match in
order, offset, length, raw filename and UTF-name flag; the permissive partial-match
merge fallback is disabled. Without that build definition upstream behavior stays
unchanged. [UPSTREAM_SHA256SUMS](UPSTREAM_SHA256SUMS) records the original download
hashes; [SHA256SUMS](SHA256SUMS) records current source/license hashes. The original
`cabd.c` SHA-256 is `3fec20d659b18cfe73f8e18a54e4cb856098f1aa3aa076ff936a79b0b831d7eb`.

The native module now declares a separate `libreadwide-cab.so` target and JNI
adapter using only the public API. Default filesystem I/O is compiled out; input
tokens and Java output callbacks are mandatory. Nothing downloads at build/runtime.
The source build target and guarded app extraction/viewer routes are connected;
their recorded integration checks do not establish native execution or current
post-change verification, and a module-specific APK-size measurement is not recorded.

The library/adapter retain LGPL-2.1-only; the app packages the full license and
binary/relinking notice in `assets/open_source_licenses/libmspack_cab_lgpl_2_1.txt`.
Keep complete corresponding sources and rebuild scripts. See the
[CAB integration status](../../docs/CAB_MULTIVOLUME_INTEGRATION_1_0_20.md). Header
discovery alone does not enable multipart extraction. Never enable repair/salvage
or ignored checksums to make an incomplete/corrupt set appear successful.
