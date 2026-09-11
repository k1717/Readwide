# Vendored libarchive-android source

Readwide builds its Android libarchive backend from source. No prebuilt AAR or
native library is stored in this directory.

Pinned upstream revisions:

- libarchive-android wrapper: `3a592be028c7be41847f667570bd343c0010bd9d`
- libarchive 3.8.9: `27cbc7827172698143e440801fc0ba39ccb4f1f5`
- bzip2: `6a8690fc8d26c815e798c588f796eabe9d684cf0`
- LZ4: `ebb370ca83af193212df4dcbadcc5d87bc0de2f0`
- Mbed TLS: `068ff080b369adfac81509f9b57b2afabaf82dc5`
- XZ Utils: `4b73f2ec19a99ef465282fbce633e8deb33691b3`
- Zstandard: `f8745da6ff1ad1e7bab384bd1f9d742439278e99`

Readwide advances the wrapper's original native baseline to the official
libarchive v3.8.9 tag above. The wrapper's small `libarchive.patch` JNI API
extension is already applied to those vendored files, and the retained patch
beside the JNI sources records the Android-specific delta for review.
`library/CMakeLists.txt` therefore compiles the sources directly without
requiring a host `patch` executable.

Only files needed by the wrapper's native build, public headers, and license
texts are vendored. Upstream test suites, samples, generated binaries, and Git
metadata are intentionally excluded.
