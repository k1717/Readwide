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
The same retained patch also records Readwide's local RAR5 redirection correction
(2026-10-09): classify junctions as directory symlinks, and reject unresolved
file-copy/unknown redirection types instead of exposing empty regular files.
Readwide's bridge excludes links; eligible ordinary entries remain available through
the existing Java fallback. This does not implement file-copy restoration. Native
compilation and Android execution for this correction remain pending.
The local patch also bounds native redirection fields to their own extra record,
counts the actual target-length vint width, rejects duplicate/unknown flags and
checks 64-bit lengths before conversion to `size_t`. Extra-record size/type fields
are checked before subtraction; unknown extra record types remain skippable.
Four new synthetic Android boundary tests are uncompiled/unrun. This does not
claim a full audit of every native extra-field decoder or producer interoperability.
The retained patch also admits LHA `-lh4-` (2026-10-10) through the existing static
Huffman decoder with a 4 KiB history. Position-table bounds and initialization
derive from that method window; `-lh5-`/`-lh6-`/`-lh7-` are unchanged. No new codec
source or dependency is imported. Five synthetic Android regression methods remain
uncompiled/unrun; this does not implement LHA volume continuation or other methods.
See [source reasoning and references](../../docs/LHA_LH4_INTEGRATION_1_0_20.md).
The retained patch additionally admits PMarc stored `-pm0-` level-0 members through
the existing LHA stored-data path. Packed/original sizes must match and the CRC
field must be present; normal header checksum and output CRC validation remain.
PMarc comment bytes are excluded from Unix mode/ownership interpretation. PM1/PM2
and other PMarc header levels are not implemented. No reference implementation or
new dependency is imported. Synthetic Android regression sources remain unrun;
see [scope and references](../../docs/DEV_CHANGES_1_0_21.md#archive-compatibility).
The native 7z reader also requires one encoded-header folder instead of decoding
only the first of several. This does not restrict ordinary multi-folder file
data. The same check is retained in `libarchive.patch`; synthetic Android
list/selected/forward and split-route regressions remain uncompiled/unrun.
`library/CMakeLists.txt` therefore compiles the sources directly without
requiring a host `patch` executable.

Only files needed by the wrapper's native build, public headers, and license
texts are vendored. Upstream test suites, samples, generated binaries, and Git
metadata are intentionally excluded.

Readwide's Gradle wrapper configuration uses Java 17 source/target, matching the
app without introducing new Java APIs or changing minSdk. External native staging
uses `cxx/archive` under the configured build root. The successful 1.0.20 build
on 2026-10-11 compiled both native ABIs before these build-setting edits;
runtime validation and module-specific tests remain separate.
