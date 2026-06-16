# RAR status for Readwide 1.0.2

This is the consolidated RAR status note for Readwide 1.0.2. It replaces internal RAR decoder development notes with a release-facing compatibility boundary.

## Final support position

Readwide 1.0.2 is **not** a complete RAR implementation.

The default FOSS-friendly build is:

- Junrar-free;
- UnRAR-license-fallback-free;
- read/extract only;
- first-party Java for covered metadata/stored paths;
- bundled libarchive-android for common compressed RAR attempts;
- conservative in public support claims.

## Implemented / retained first-party paths

- RAR metadata parsing used for listing/routing/safety checks.
- Path traversal and unsafe-entry rejection boundaries.
- RAR4 Unicode filename handling where covered.
- Stored RAR3/RAR4 method-0/0x30 extraction.
- Covered stored split path validation/routing.
- Partial-output cleanup and pre-existing output restoration for guarded paths.
- Covered RAR5 stored-entry handling.
- **RAR5 compressed decoding:** first-party decoder
  (`Rar5CompressedDecoder` + `Rar5CompressedArchiveExtractor`) for covered RAR5
  entries using compression algorithm version 5.0 (methods 1-5, dictionaries up
  to 64 MB), including solid runs with window carryover and predecessor priming,
  and the DELTA / x86 / ARM filters. Unencrypted single-volume entries are
  CRC32-verified. Covered visible-header AES entries use the RAR5 password-check
  value when present and can assemble tested multi-volume compressed/solid split
  payloads before decoding. Non-5.0 algorithm versions (RAR7-era streams),
  damaged/gapped chains, header-encrypted archives, and untested encrypted/split
  variants fail cleanly or remain backend-dependent. This path runs only after
  the libarchive-primary attempt has failed or is unavailable.
- **RAR3/RAR4 PPMd decoding:** first-party PPMd variant H engine
  (`RarPpmdVarHDecoder` + `Rar3PpmdSolidStreamDecoder` +
  `Rar3PpmdSolidArchiveExtractor`) for unencrypted, single-volume RAR3/RAR4
  entries whose compressed members are PPMd blocks, including solid sets.
  Solid model and window state persist across entries, predecessors are decoded
  and CRC-checked before a later target, and the per-entry end-of-data marker is
  required so solid continuation never desynchronizes silently. Every entry is
  CRC32-verified; any mismatch or unsupported construct (VM filters, mid-entry
  table switches, classic-LZ solid members) fails cleanly instead of producing
  fallback output. This path runs only after the libarchive-primary attempt has
  failed or is unavailable.
- Diagnostic probes/classifiers for unsupported compressed/solid/PPMd/VM cases.

## Bundled backend path

Common RAR3/RAR4 compressed extraction attempts use:

```text
ArchiveSupport
  -> RarArchiveReader
  -> RarLibarchiveFallback
  -> LibarchiveNativeBridge
  -> me.zhanghai.android.libarchive
```

The bundled backend comes from `me.zhanghai.android.libarchive:library:1.1.6`. No manual local `libarchive.so`, NDK/CMake flag, or Junrar dependency is required for the default build.

For single-entry RAR/CBR image extraction, the libarchive path drains earlier regular entries to temporary throwaway files before extracting a later entry. This preserves the sequential decoder state needed by some solid RAR3/RAR4 comic archives where skipping earlier members can make later images fail. The temporary primer files are app-private cache/output-adjacent files and are deleted immediately after use.

## Not guaranteed

Do not advertise guaranteed support for:

- broad split/multi-volume RAR outside the tested RAR5 visible-header AES v5.0 chains;
- broad encrypted RAR outside the tested visible-header RAR5 AES path and scoped stored-entry paths;
- RAR3/RAR4 compressed solid archives using classic-LZ members (first-party PPMd-member solid archives are covered above);
- RAR VM-filtered first-party decoding;
- compressed split chains;
- broad SFX executable wrappers;
- RAR5 encrypted-header extraction, RAR5 algorithm versions other than 5.0 (RAR7-era streams), and RAR5 dictionaries above 64 MB;
- damaged/recovery-record edge cases;
- RAR creation/compression.

Some of these files may succeed if the bundled backend handles the exact variant, but that is backend-dependent and not a first-party compatibility claim.

## Public wording

Use:

> RAR/CBR support is limited. Stored RAR entries, scoped RAR3/RAR4 PPMd entries, covered RAR5 v5.0 compressed entries, and fixture-tested RAR5 AES visible-header multi-volume chains have first-party handling with CRC/password-check safeguards; other common compressed RAR entries are attempted through bundled libarchive-android. Broad split/encrypted RAR, classic-LZ compressed-solid RAR3/RAR4, broad SFX, VM-filtered, RAR7-era algorithm versions, and encrypted-header cases are not guaranteed.

Do not use:

> Complete RAR support.

> RAR5 compressed support.

> Complete encrypted/split/solid RAR support.
