# RAR status for Readwide 1.0.1

This is the consolidated RAR status note for Readwide 1.0.1. It replaces internal RAR decoder development notes with a release-facing compatibility boundary.

## Final support position

Readwide 1.0.1 is **not** a complete RAR implementation.

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

## Not guaranteed

Do not advertise guaranteed support for:

- split/multi-volume RAR;
- encrypted RAR;
- RAR3/RAR4 compressed solid archives;
- PPMd first-party decoding;
- RAR VM-filtered first-party decoding;
- compressed split chains;
- broad SFX executable wrappers;
- RAR5 compressed, solid, or encrypted-header extraction;
- damaged/recovery-record edge cases;
- RAR creation/compression.

Some of these files may succeed if the bundled backend handles the exact variant, but that is backend-dependent and not a first-party compatibility claim.

## Public wording

Use:

> RAR/CBR support is limited. Stored RAR entries have covered first-party handling, and common compressed RAR3/RAR4 entries are attempted through bundled libarchive-android. Split, encrypted, compressed-solid, broad SFX, PPMd/VM-filtered, and RAR5 compressed/solid/encrypted-header cases are not guaranteed.

Do not use:

> Complete RAR support.

> RAR5 compressed support.

> Encrypted/split/solid RAR support.
