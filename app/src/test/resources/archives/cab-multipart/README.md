# CAB multipart regression resources

These are byte-exact Base64 representations of the five `multi_basic_pt*.cab`
resources from [libmspack](https://github.com/kyz/libmspack/tree/55d501976171397ccd5d5a7a1ca7da065b1d9a06/libmspack/test/test_files/cabd),
commit `55d501976171397ccd5d5a7a1ca7da065b1d9a06`. Upstream's `cabd_merge_test_02`
uses them as a five-part, single-folder regression set. They are upstream test
fixtures, not claimed to be newly producer-generated interoperability evidence.
They contain short synthetic text and use stored blocks, including split CFDATA.
The additional `mszip_lzx_qtm.cab` contains three compressed files; the unrun Android
regression uses the exact MD5 output oracles from upstream `cabd_extract_test_03`.
Those MD5 values are regression expectations, not security verification.

Source attribution: libmspack, Stuart Caie; distributed with the upstream LGPL-2.1
source. The full license is in
[COPYING.LIB](../../../../../../third_party/libmspack/COPYING.LIB).
Do not put these resources in production assets. The metadata test materializes
them as `cabd_multi_basic_ptN.cab`, matching their embedded links; upstream repository
resource basenames omit the `cabd_` prefix. The native module's Android-test assets
source set references this directory; production APK assets do not include it.
No fixture extraction or regression test has run for this integration.

| Upstream filename | Decoded bytes | SHA-256 of decoded bytes |
| --- | ---: | --- |
| multi_basic_pt1.cab | 221 | `3658aaf68b0d538a5b321919f1de3eb1bcc258cba44e97b07a1938230995c632` |
| multi_basic_pt2.cab | 274 | `ce5fac4470fb850ae593de70ab03d3493069b77f8f81376216768bbf74e52321` |
| multi_basic_pt3.cab | 274 | `e58b57f63ac0858effa75ceb6f242b447fbce6a910d3840c37228fdbc9e7bc95` |
| multi_basic_pt4.cab | 274 | `fbcd45e503c3cffd5f5946ccc45bbc46b9884fe47abef7d09247154c7f8148fb` |
| multi_basic_pt5.cab | 221 | `16ebbb274c8dc09f98415795212683b9ea8b2d6450080a79ab63cd5cdbac7cc4` |
| mszip_lzx_qtm.cab | 379 | `0ce0b55fe705b744d41bb361170c0467db30da0c7f9bdd386d5dade71a78e171` |
