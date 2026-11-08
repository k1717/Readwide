# EGG encrypted regression fixtures

These base64 files encode the unmodified EGG samples in
https://github.com/alkegi/unegg/tree/9929009ca4d010e8cd88bb796f7a1868162026d4/tests/data.
Upstream dedicates these test data to the public domain under CC0 1.0:
https://creativecommons.org/publicdomain/zero/1.0/ . The upstream dedication is at
https://github.com/alkegi/unegg/blob/9929009ca4d010e8cd88bb796f7a1868162026d4/tests/data/README.md.
Password: `test1234` (public fixture password, not an application credential).

The focused host tests compile and decode these samples with Readwide 1.0.22.
Selected/bulk extraction checks decoded sizes and CRCs; wrong-password and
changed-authentication-footer tests reach the crypto checks and preserve output.
These are test-only resources and are not packaged into the production APK.

| Fixture | Archive bytes | Observed encryption |
| --- | ---: | --- |
| lea128.egg | 32231 | LEA-128, method 5 |
| lea256.egg | 32303 | LEA-256, method 6 |
| enc_lea_bzip2.egg | 266 | AES-256, method 2, with BZip2 |
| enc_zip20.egg | 867 | ZipCrypto, method 0, BZip2/Deflate members |
| oversized_encrypt_zip20.egg | 164 | ZipCrypto, method 0, Deflate member |

The `enc_lea_bzip2.egg` filename is retained from upstream; its encryption method is AES, not
LEA. Its ENCRYPT field declares 36 bytes but contains the complete 29-byte
method/salt/verifier/footer payload followed by END. The declared count includes
the seven-byte extra-field prefix. The two ZipCrypto samples similarly declare
24 bytes for a complete 17-byte method/check-data/CRC payload. Both now match
Bandizip's extracted filenames and bytes through Readwide's selected and bulk paths.
All five samples have unencrypted filename fields, so these
fixtures do not exercise encrypted filenames or encrypted split-solid production.
