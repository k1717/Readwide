# ZIP PPMd-I decoder source

Eight public-domain C/header files from [7-Zip](https://github.com/ip7z/7zip/tree/9128b80e3a471108678e2b3ec3c985861c8d7b0f/C),
commit `9128b80e3a471108678e2b3ec3c985861c8d7b0f` (2026-10-06).
`UPSTREAM_SHA256SUMS` records exact upstream bytes; `SHA256SUMS` records current
bytes. Six files remain unchanged. `Ppmd8.c` and `Ppmd8.h` have the small, marked
Readwide freeze-profile change described below. Per-file declarations are retained:
Igor Pavlov's Ppmd8 codec/common platform helpers, based on Dmitry Shkarin's PPMd
and Dmitry Subbotin's carryless range coder, all explicitly public domain.
No encoder, ZIP parser, UnRAR code, prebuilt binary or executable is included.

Only `Ppmd8.c` and `Ppmd8Dec.c` compile. Six headers satisfy their local includes.
The Readwide Apache-2.0 JNI adapter is in the existing native-codec module, but
CMake declares an independent `libreadwide-ppmd.so` target: it does not link the
LGPL JPEG/CAB targets or the libarchive container implementation. Build/runtime
downloads are not used. Keep the bundled public-domain attribution asset.

[ZIP framing reference](https://github.com/ip7z/7zip/blob/9128b80e3a471108678e2b3ec3c985861c8d7b0f/CPP/7zip/Compress/PpmdZip.cpp):
two little-endian property bytes encode order 2–16, memory 1–256 MiB and restoration.
Restart/cut-off use the decoder's ordinary modes. Upstream disables freeze because
incompatible PPMd-I revisions exist. Readwide's core/JNI targets now enable the
upstream freeze branch consistently and provide an explicit raw-stream revision
parameter. Raw default opening rejects freeze; the app now uses checked container
selection instead of guessing a revision.
These are codec properties, not a total decoded-file limit.

## Local freeze-profile change — 2026-10-10

`Ppmd8.h` adds `ReadwideFreezeRevision1` inside the freeze compile guard;
`Ppmd8_Construct` initializes it false. `Refresh` omits the upstream-documented
revision-2 high-sum correction only when that flag is true and restoration is
freeze/frozen. Every other mode keeps the existing correction. Upstream allocator
fixes, maximum-order bounds and range safety corrections remain; the change does
not recreate historical undefined behavior. Core/JNI share the PUBLIC compile
definition so the private model structure has the same layout in both targets.
The marked Readwide additions and its Java/JNI adapter are Apache-2.0; original
public-domain declarations remain. No code was imported from GPL mirrors.

The app container now checks both profiles sequentially with exact completion,
CRC/authentication and ambiguity handling, rejecting differing valid results.
The raw API still requires an explicit profile. Five earlier Android
methods are uncompiled/unrun; small mutated-property payloads do not exercise
memory exhaustion, freeze transitions or the divergent high-sum case.
The standalone [Refresh regression source](tests/freeze_refresh_test.c) directly
checks the high-sum boundary for both flags and all restoration states, without
claiming encoded-stream interoperability. It is not linked into the app and has
not been compiled or run.

See [integration boundaries](../../docs/ZIP_PPMDI_INTEGRATION_1_0_20.md).
Sources and Android regression methods are uncompiled/unrun. This is an independent
container/stream path using upstream decoder code, not a new first-party PPMd model.
