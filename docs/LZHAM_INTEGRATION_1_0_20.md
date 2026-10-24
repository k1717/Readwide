# LZHAM 7z integration — Readwide 1.0.20

The local release build completed successfully. Native runtime validation remains
separate.

## Implemented scope

The 26-entry `SevenZCoderRegistry` now includes method `04F71001`, using a
separate `readwide-lzham` native target and `LzhamInputStream`. Existing container
listing, selected/bulk extraction, graph composition and dedicated forward routing
select it. The decoder source is MIT LZHAM, not first-party compression code.

Five-byte properties with version `0x10` or `0x11` are accepted for method
`04F71001`. Dictionary zero selects exponent 26; explicit exponents are 15–29.
Encoder level/flags/reserved metadata does not affect decoding, matching that
plugin. The raw decoder retains default table-update settings, no seed dictionary
and no standalone zlib/LZHAM wrapper. Other property versions need independently
verified profiles; they are not relabeled as this version.

The stream uses 64 KiB I/O chunks and a format-selected dictionary. It has no
fixed decoded-file cap, no full-payload buffer and no new disk spool. Dictionary
allocation can still fail on a device; no output-size-dependent dictionary
shrinking is applied because LZHAM dictionary bits also select coding tables.
Initialization and individual native calls are not asynchronously interruptible;
interruption is checked at Java input/step boundaries, not a measured latency SLA.

## Version 1.0 property compatibility

The pinned upstream README explicitly locks the v1.x bitstream and promises
forward/backward compatibility with v1.0, except critical bug fixes; pre-1.0 alpha
streams are excluded. The author's February 2015 producer listing shows the actual
five-byte property value `1017030000`. The stable pin below reports DLL `0x1010`,
and the bundled pin `0x1011`. Their base coding tables and zero-override/default
table-rate selection agree. This evidence, not similar tables alone, justifies
admitting `0x10` through the already bundled decoder without a second engine.

Readwide deliberately accepts these two version bytes; the original plugin wrapper
checks equality with its own DLL byte. No other version byte is inferred. The
historical experimental method ID `090101` is still not registered: its precise
container profile has not been independently established. The producer property
example does not prove that every early archive used today's `04F71001` ID.
Custom table-update/seed-dictionary profiles and standalone wrappers remain outside
this integration. Native sources, pins, licenses and packaged source hashes do not
change for this Java property-admission change.

Two existing host-property methods now cover both accepted bytes and every rejected
version byte. Five existing app Android methods exercise both versions through
selected/bulk/forward routes and integrity/password/size failures. Their synthetic
raw blocks are not new compressed-producer evidence; all remain uncompiled/unrun.

## Integrity and failure handling

- Enable the decoder's Adler32 check as well as existing 7z packed/folder/member CRCs.
- Require exact decoded size, explicit end/trailer, no prefetched whole bytes
  after the trailer and no remaining packed suffix. Never enable synthetic
  zero-byte EOF padding; input exhaustion before codec completion is an error.
- Fail zero-progress or invalid source reads. Preserve checked/unchecked source
  failures, reject reentrant reads/close and retire failed native state.
- Treat opened LZHAM payload errors as terminal integrity failures, not a reason
  to retry another backend over partially written output.
- Preserve existing free-space reserve, cancellation, source identity, guarded
  destination rollback and checked forward-viewer spooling. No TXT paging changes.

Four marked upstream files handle allocation failures, explicit placement-new
declaration, caught library failure instead of process exit, and strict prefetched
tail rejection. See [source selection and hashes](../third_party/lzham/README.md).
This is not a complete native security audit or fuzzing result.

## Validation and release boundary

The cleanup follow-through retains failed source-close ownership for retry,
without reopening decoding or repeating a successful source close. Cleanup callbacks
cannot recursively close the owner. Two added Android methods and one revised close
expectation are uncompiled/unrun; codec/native behavior and supported profiles are unchanged.

Added twelve native-stream Android methods, five app Android methods and two
host-property methods, with three registry expectations updated. No build,
compilation, test, benchmark or native/device execution was performed.

The synthetic fixtures encode raw LZHAM blocks, explicit EOF and Adler32. They
cover source/ownership/error behavior and 7z/AES routing in regression source,
not compressed Huffman/LZ matches or real-producer interoperability. Pending:

- Both ARM ABIs, minified JNI resolution, runtime/ELF dependencies and APK size.
- Actual LZHAM-plugin compressed archives, table adaptation and long-distance
  matches, including 32-/64-bit decoder differences.
- Solid, split, encoded-header, BCJ/BCJ2 graph compositions and password failures.
- Memory pressure/allocation injection, low disk, cancellation, destination/close
  failures and output restoration. Run the older 7z suites as well.
- Alpha/unknown property versions, experimental method `090101`, alternative table
  profiles and dictionary-dependent inputs remain unimplemented; do not infer
  their semantics from a similar version or method ID.

MIT source and NDK libc++/libc++abi/libunwind notices are included in production
license assets and the source-declared SBOM. Runtime comes from the pinned NDK,
not checked-in binaries. This is not F-Droid approval or a resolved build audit.

## Primary references

- [Pinned v1.x compatibility contract](https://github.com/richgel999/lzham_codec_devel/blob/d09fc6676a0313c3814457fbc76351a68f653092/README.md).
- [Stable v1.0 API/defaults](https://github.com/richgel999/lzham_codec/blob/d379b1f9121e2197881c61cfc4713c78848bdfe7/include/lzham.h).
- [Author's v1.0 producer properties](https://richg42.blogspot.com/2015/02/lzham-10-integrated-into-7zip-command.html).
- [Author's experimental method ID](https://richg42.blogspot.com/2015/02/7zip-938-custom-codec-plugin-for-lzham.html).
- [Original 7z plugin](https://github.com/richgel999/lzham_codec_devel/blob/d09fc6676a0313c3814457fbc76351a68f653092/lzham7zip/LzhamRegister.cpp):
  method ID, property shape, version/dictionary/default profile.
- [Decoder and API](https://github.com/richgel999/lzham_codec_devel/tree/d09fc6676a0313c3814457fbc76351a68f653092/lzhamdecomp):
  raw block/end/checksum grammar and streaming ownership.
- [Source license](https://github.com/richgel999/lzham_codec_devel/blob/d09fc6676a0313c3814457fbc76351a68f653092/LICENSE).
- [NDK C++ runtime guidance](https://developer.android.com/ndk/guides/cpp-support):
  one private C++ JNI boundary, no cross-library C++ allocations or exceptions.
