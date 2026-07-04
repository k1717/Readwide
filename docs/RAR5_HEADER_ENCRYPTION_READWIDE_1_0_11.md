# RAR5 header encryption (-hp) first-party support

Reference notes for the RAR5 header-decryption path added to
`RarArchiveReader.readRar5Entries` in 1.0.11.

## What it closes

RAR5 archives created with `-hp` (header encryption) previously had **no path
at all**: the bundled libarchive 3.7.2 does not decrypt RAR5 headers (it
cannot even list such archives - "Encryption is not supported"), and the
first-party reader raised "Encrypted RAR5 headers are not supported yet". The
1.0.11 revalidation doc recorded this as the one hard unsupported RAR
boundary.

The missing piece turned out to be small. The first-party RAR5 machinery
already covered everything *after* header parsing - stored-entry AES
decryption (`Rar5Crypto`), compressed decrypt+decode
(`Rar5CompressedArchiveExtractor` feeding `Rar5CompressedDecoder`), solid
chains, split payloads, and volume resolution. Only header decryption was
absent, so once headers decrypt, every existing path applies unchanged.

## Format

An `-hp` archive keeps the plain RAR5 signature, then its first block is the
archive encryption header (type 4): encryption version (vint, 0 = AES-256),
flags (vint, bit 0 = password check present), KDF count (u8), 16-byte salt,
and an optional 12-byte check record (8 password-check bytes plus the first 4
bytes of their SHA-256, which guards the check record itself; an inconsistent
record is ignored the way unrar ignores it). Every subsequent header block is
stored as a 16-byte IV followed by the AES-256-CBC ciphertext of the usual
block (CRC32, size vint, header bytes) padded to a 16-byte boundary. File
*data* encryption is unchanged: each file header carries its own encryption
record, exactly as in visible-header `-p` archives.

Key derivation reuses `Rar5Crypto.deriveSecrets` (the single HMAC-SHA256
U-chain validated against a real WinRAR 7.00 archive in the earlier
revalidation pass) with the crypt header's salt and KDF count.

## Error semantics

- No password: `PasswordRequiredException` before any parsing (unchanged).
- Wrong password with a check record: the check-value mismatch re-prompts
  before any header is parsed.
- Wrong password without a usable check record: the first decrypted header
  cannot pass its CRC32, which on a header-encrypted archive means a wrong
  key far more often than corruption, so that too re-prompts rather than
  reporting a corrupt file.

## Validation

Fixtures were created in-session with the RAR 7.00 CLI and every extraction
compared byte-for-byte against UNRAR 7.00 output:

- `-hp` stored (`-m0`), compressed (`-m3`, `-m5`), and solid (`-s`, three
  entries) - list, whole-archive, and single-entry extraction all byte-exact.
- Multi-volume `-hp` (`-v20k`, three parts, entries split across volumes) -
  both files byte-exact; each volume carries its own crypt header and the
  per-volume header reader handles it because the password flows through the
  volume chain.
- An 84 KB mixed text/random payload at `-m5`, and Korean entry names
  (UTF-8 names decode correctly from decrypted headers).
- Missing and wrong passwords re-prompt cleanly with no partial output.
- Visible-header regression: `-p` stored and compressed fixtures behave as
  before.

Two self-made fixtures (stored and `-m3`, 2,910/366 bytes, payload pinned by
SHA-256) are embedded in `Rar5HeaderEncryptedArchiveTest`. No third-party
extractor code was used; UNRAR served as a black-box oracle only.

## Remaining RAR boundaries

Unchanged by this work: RAR4 `-hp` stays on the existing rewriter path; RAR4
stored+encrypted still lacks a real-file fixture (RAR 7.00 cannot create RAR4
archives); compressed RAR3/RAR4 remains libarchive-primary with the scoped
first-party fallbacks.
