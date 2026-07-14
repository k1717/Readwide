# RAR / 7z split + encryption revalidation (Readwide 1.0.11)

This note records an empirical re-audit of the RAR and 7z split and encryption
paths against real archives. No code changes were needed; both readers already
route these cases as intended. It captures what was verified and where the
conservative boundaries sit, so future edits do not silently widen a claim.

> **Historical audit sequence:** Some present-tense paragraphs below describe the pre-close state that motivated later first-party work. RAR5 header encryption and encrypted PPMd/BCJ2 7z support were subsequently added in the scoped paths linked from their respective closure notes; use the 1.0.15 README/release notes for the current boundary.

Verification method: real fixtures were parsed and decoded with an independent
Python mirror and with the bundled backends' reference tools (libarchive
3.7.2 via bsdtar, p7zip). RAR fixtures include the libarchive project's own
test files (BSD-2-Clause test corpus) and, this pass, real RAR5 encrypted
archives created with the WinRAR 7.00 trial CLI (`rar a -ma5 ...`; RAR 7.00 no
longer creates RAR4 archives, so RAR4 encryption stays mirror-verified). No
third-party extractor code was copied into the app.

## RAR

Backend split: libarchive is the primary broad RAR backend; first-party Java
covers stored entries, the scoped RAR3/RAR4 and RAR5 v5.0 decode paths, the
visible-header AES decrypt/rewrite path, and (RAR5) stored-entry AES decryption
via `Rar5Crypto`. At the time of this initial audit, RAR5 header encryption had
no decrypt path in either backend; that gap was later closed by the scoped
first-party path (see the boundary note below). The libarchive fallback is handed
the *entire* volume chain (`RarLibarchiveFallback.archivePaths` ->
`RarArchiveReader.collectVolumeChainForBackend`), so multi-volume RAR is
resolved by discovering all volumes, not by passing a single selected part.

Verified against real files:

- **RAR4 AES-256 KDF.** `Rar3Crypto.deriveParameters` (UTF-16LE password, salt,
  2^18-round SHA-1 with the 3-byte little-endian round counter, IV bytes
  sampled every 2^14 rounds from digest byte 19, and the endian-swapped
  16-byte key) reproduces the exact key/IV of the libarchive
  `test_read_format_rar_encryption_header.rar` fixture: decrypting the
  encrypted header block yields a well-formed RAR4 file header whose stored
  CRC16 matches the recomputed one. A wrong password does not. This confirms
  the KDF that both the payload rewriter and the stored-entry decrypt path
  depend on.
- **RAR4 header-encryption detection.** The detector's flag constants
  (`RAR4_MAIN_PASSWORD = 0x0080`, long-block `0x8000`) match the real
  header-encrypted fixture, so the app raises the password prompt before entry
  parsing rather than misreporting corruption.
- **RAR4 file-encryption layout.** The rewriter's field model (password flag
  `0x0004`, salt flag `0x0400`, salt positioned immediately after the file
  name, method `0x30` = store) matches the real data-encrypted fixture
  (`test_read_format_rar_encryption_data.rar`, method 0x33 = compressed in that
  file). Because the fixture's entries are compressed, they correctly fall
  outside the stored rewrite path; the store-only branch stays scoped.
- **Multi-volume discovery.** Real RAR4 `.partNNNN.rar` and RAR5
  `.partNN.rar` chains were confirmed to require the sibling volumes to read
  past the first part (libarchive lists only the first entry when handed a
  single early part), matching why the fallback forwards the whole chain.

Boundaries left unchanged (still libarchive-dependent or unsupported):
compressed encrypted RAR content on RAR3/RAR4, encrypted split RAR4 payloads,
and RAR4 solid encrypted sets. **RAR5 header-encryption (`-hp`) was a hard
unsupported boundary when this audit was written** - libarchive 3.7.2 does not
decrypt RAR5 headers at all (bsdtar reports "Encryption is not supported" and
cannot even list a real WinRAR 7.00 `-hp` RAR5 archive) and there was no
first-party header decryptor - **and has since been closed first party**: see
`docs/RAR5_HEADER_ENCRYPTION_READWIDE_1_0_11.md`. The paragraphs below record
the pre-close state. `RarHeaderEncryptionDetector` distinguishes RAR4 `-hp` (a
first-party header rewriter exists; a failure there is the real fault) from
RAR5 `-hp` (whose historical unsupported message said no decrypt path existed)
in its unsupported message. No
stored+encrypted RAR4 fixture could be generated in-sandbox (RAR 7.00 dropped
RAR4 archive *creation* - `-ma4` is rejected as an unknown option), so the RAR4
stored-payload rewrite's end-to-end decrypt remains covered by the KDF mirror
plus the existing unit tests rather than a real stored-encrypted RAR4 file.

RAR5 encryption, by contrast, was verified against real WinRAR 7.00 fixtures
this pass:

- **RAR5 stored + password (`-p -m0`) is first-party and now real-file
  verified.** `Rar5Crypto.deriveSecrets` (UTF-8 password, single HMAC-SHA256
  U-chain, key at `1<<kdfCount`, and the 8-byte password check folded from the
  32-byte accumulator *itself* - not a second hash of it) reproduces the exact
  password-check bytes (`101e0105f14dd8c2`) of a real WinRAR 7.00
  `rar a -ma5 -m0 -pspeak` archive, and AES-256-CBC decryption of the stored
  payload yields plaintext byte-identical to bsdtar's output (CRC-clean). This
  is the path the app uses for stored encrypted RAR5 entries with no libarchive
  involvement, and it is now backed by a genuine WinRAR file rather than only
  the RAR4 KDF mirror.
- **RAR5 compressed + password (`-p -m3`)** stays libarchive-primary; the
  first-party path does not cover compressed-encrypted RAR5 payloads.

Device testing with a real WinRAR stored+password *RAR4* archive is the
remaining gap.

## 7z

Backend split: Commons Compress is the primary 7z backend; libarchive is the
fallback for method chains Commons Compress cannot decode (PPMd, BCJ2). Split
volumes are resolved by `SevenZSplitVolumeResolver` and opened as one
`MultiReadOnlySeekableByteChannel`; the password is forwarded to `SevenZFile`.

Pre-close findings verified against real 7z fixtures (built with p7zip):

- **Coder coverage (Commons Compress 1.28.0).** The registered decoder table
  is COPY, LZMA, LZMA2, DEFLATE, DEFLATE64, BZIP2, AES256SHA256, the BCJ
  branch filters, and DELTA. PPMd and BCJ2 are absent - confirming the
  libarchive fallback is what makes PPMd/BCJ2 work, and only when those
  streams are **not** also AES-encrypted (see below).
- **AES-256 password classification.** `AES256SHA256Decoder` throws
  `PasswordRequiredException` ("Cannot read encrypted content ... without a
  password") when no password is set. The app's `isSevenZPasswordRequired`
  matches both the class name and that message, so both data-encrypted and
  header-encrypted 7z map to `PASSWORD_REQUIRED`. Wrong AES passwords surface
  as corrupt data and are promoted to `BAD_PASSWORD` via the existing
  `sevenZArchiveHasAesContext` guard.
- **Split resolution.** Real `.7z.001..004` chains open through the
  concatenated channel; a first part alone does not (libarchive errors),
  confirming the resolver must gather the contiguous set. Gapped chains are
  classified corrupt, not password-required.
- **Encrypted + PPMd/BCJ2 is the real boundary.** When PPMd or BCJ2 is
  combined with AES encryption, *neither* backend produces output, verified
  against real p7zip fixtures (`7z a -m0=PPMd -ppass ...`, both `-mhe=on` and
  `-mhe=off`):
  - Commons Compress lacks the PPMd/BCJ2 coder regardless of encryption.
  - libarchive 3.7.2 does not decrypt 7z at all. For a header-encrypted set
    (`-mhe=on`) it cannot even list ("The archive header is encrypted, but
    currently not supported"); for a data-only-encrypted set (`-mhe=off`) it
    lists the visible header but fails extraction at the encryption layer
    ("The file content is encrypted, but currently not supported"), never
    reaching the PPMd/BCJ2 stage. (Earlier notes said libarchive decrypts and
    then reports PPMd as unsupported; the real failure is at the AES layer, so
    the PPMd/BCJ2 coder gap is not even what surfaces.)
  So the app maps these to PASSWORD_REQUIRED (header-encrypted, no password) or
  a clean unsupported/BAD_PASSWORD (data-encrypted), never partial output.
  Unencrypted PPMd/BCJ2 remains covered by the libarchive fallback (verified in
  the 1.0.11 7z coverage pass); the complementary-backend guarantee applies to
  unencrypted PPMd/BCJ2 only.

Boundaries left unchanged: creation of 7z and unsupported method chains. The
encrypted-PPMd/BCJ2 combination documented above has since been closed by the
first-party `SevenZBcj2ArchiveReader`/`SevenZPpmd7Decoder` path (see
`docs/SEVENZ_BCJ2_READER_READWIDE_1_0_11.md` and
`docs/SEVENZ_PPMD_READER_READWIDE_1_0_11.md`); the paragraphs above record the
pre-close state that motivated it.
