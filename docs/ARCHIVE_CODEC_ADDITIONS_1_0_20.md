# Archive codec additions — Readwide 1.0.20

These additions have been reviewed as source only;
no compilation, test execution, Android decoding or performance measurement was run.
They are implemented paths, not a claim of complete format compatibility.

The later [stream corrections](ARCHIVE_STREAM_REPAIR_1_0_20.md) fix LZ5 full-window
retention/input guards and add non-MT metadata skipping, failure/close handling and
RAR bulk forwarding. Those changes and their regression sources are also unrun.

## EGG LEA authentication and decryption

`EggAuthenticatedCrypto` shares the existing PBKDF2-HMAC-SHA1 password derivation,
two-byte verifier and per-file HMAC validation between AES and LEA. Methods 5/6
select LEA-128/256. LEA uses a big-endian counter starting at zero; existing AES
keeps its little-endian counter starting at one. `EggLeaCipher` supplies the block
primitive. Authentication, decoded size, nonzero block CRCs, cancellation and
per-file rollback are retained. The later block-owner implementation also routes
ZipCrypto/AES/LEA encrypted-solid data through authenticated packed spools before
decoding/publication. It retains the existing block grammar, not continuous codec
state across packed blocks. Encrypted filenames remain unsupported. Producer/device
validation of the encrypted-solid path is pending; see the
[current boundary](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md).

The LEA primitive is adapted from the independent MIT-licensed
[alkegi/unegg](https://github.com/alkegi/unegg/tree/9929009ca4d010e8cd88bb796f7a1868162026d4),
not ESTsoft's proprietary UnEGG decoder. `EggLeaCryptoTest` includes block vectors,
selected/bulk extraction and bad-password/authentication/cancellation cases.
Three upstream CC0 producer fixtures are stored as test-only base64 resources;
their provenance, hashes and public test password are in
`app/src/test/resources/archives/egg-lea/README.md`. They are not shipped in the APK.
These tests and existing AES regressions still need execution.

## RAR3 custom VM filters

`Rar3VirtualMachine` adapts the BSD-2-Clause VM logic from
[nwaples/rardecode](https://github.com/nwaples/rardecode/tree/76ac572a52e7550ccbbc75395d2e08e39c069e36).
`Rar3VmFilter.ProgramState` keeps the six existing standard-filter fast paths and
compiles other checked bytecode into reusable programs. Program globals persist
between invocations; each invocation owns its VM memory and registers. It retains
separate execution-count metadata and overridable record registers (including R4/R5).
Immediate operand widths and unsigned/large-shift behavior follow the reference.
The VM has no host I/O. Metadata/address-space bounds, an instruction budget and cancellation
checks reject malformed or nonterminating programs; these are not file-size limits.

The shared LZ/PPMd output pipeline permits intermediate and final resizing in
within-entry chains. Every stage consumes the preceding result; raw dictionary
positions and final output positions remain separate, and size changes require
explicit EOF plus final size/CRC checks. Original raw extents govern overlap checks.
Wholly future descriptors can carry across explicit solid-entry markers. Raw blocks
straddling file endings, late descriptors and unsupported overlap/order remain rejected;
this is not general cross-entry filter support. The newer paths are uncompiled/unrun.
Program-slot resets now preserve already queued transformations and their original
program objects. Queued bytecode has a separate distinct-program metadata budget,
so repeated resets cannot bypass the active-slot ceiling. Complete record validation
precedes metadata updates; biased offsets wrap at 32 bits. RGB accepts partial
blocks and stride three. These follow-up regressions remain uncompiled/unrun;
see [decode boundaries](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md).
This does not establish complete RAR VM or RAR compatibility. Synthetic regression
sources cover instructions, globals, offsets, malformed programs and output routing;
real custom-filter producer files and existing standard-filter regressions remain
necessary validation.

## 7z LZ5 and Lizard

`SevenZCoderRegistry` adds LZ5 (04F71105, five property bytes) and Lizard (04F71106,
three property bytes). `LzFamilyInputStream` and its JNI bridge feed framed data to
the source-built decoder libraries in the existing replaceable LGPL codec module.
They accept standard codec frames and canonical MT wrappers, enforce packed-frame
boundaries and declared decoded length, and propagate checksum failures and
cancellation. The local frame follow-up also verifies optional block checksums
before decoding/output and validates explicit zero content sizes. Java/native transfer
buffers are 64 KiB; the decoders allocate additional frame/history storage, so total
memory is not limited to 64 KiB.
No whole-file payload buffer, worker pool or arbitrary archive payload cap is added.

Selected BSD codec sources come from
[7-Zip-zstd](https://github.com/mcmilk/7-Zip-zstd/tree/b5ac55ff587ec9c9315ffd624c5c93a2899340d2),
not its archive engine or UnRAR code. Lizard's dual-licensed entropy sources use
the BSD option. Pins, imported files, hashes, modifications and attribution are in
`third_party/zipx-codecs-android/LZ_UPSTREAM.md` and `LZ_SOURCES.json`.
The module's existing LGPL distribution and relinking obligations still apply.

Android regression sources cover stored frame boundaries, fragmented input,
concatenation, malformed headers, truncation, MT lengths and cancellation. They
have not been compiled/run. Actual compressed producer frames and AES/split/solid/
encoded-header 7z compositions, ARMv7/ARM64 builds and native memory-safety checks
remain pending. Registry admission alone is not end-to-end interoperability evidence.

## Release checks

The later Java LZ4 frame sequencer adds legacy/mixed-frame handling to standalone,
TAR and supplemental 7z paths without a new codec dependency. Legacy blocks are
buffered and preflighted within format bounds. The modern-frame follow-up handles
empty stored blocks and local descriptor/size/checksum checks around the existing
Commons raw decoder, with circular linked history. Preset dictionaries remain unsupported.
The earlier 27 and 18 additional host methods are uncompiled/unrun. See
[LZ4 scope and limitations](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md).

Version remains 1.0.20 / 10020. Existing dependency pins and toolchain remain;
the native module gains the source subset above. New MIT/BSD notices are APK assets,
and the notices, license report and source SBOM record the adaptations separately
from first-party code. FOSS provenance is not an F-Droid build/acceptance result.
Archive support descriptions in all 22 resource locales now match the new LEA,
scoped VM and LZ5/Lizard paths; the obsolete encrypted/split mixed-LZ/PPMd exclusion
is removed. Resource XML and source SBOM JSON were parsed without building the app.
See [release readiness](RELEASE_READINESS_1_0_20.md) before publication.
