# Google Brotli Java decoder

Readwide builds the pure-Java decoder from Google Brotli **v1.2.0**, pinned to
commit `028fb5a23661f123017c060daa546b55cf4bde29`. This replaces the older
`org.brotli:dec:0.1.2` Maven dependency; it is not a republished Maven artifact.

- Upstream: [pinned source tree](https://github.com/google/brotli/tree/028fb5a23661f123017c060daa546b55cf4bde29/java/org/brotli/dec).
- License: [MIT](LICENSE); original copyright headers are retained.
- Selection: the 12 runtime Java files selected by upstream's
  [BUILD.bazel](https://github.com/google/brotli/blob/028fb5a23661f123017c060daa546b55cf4bde29/java/org/brotli/dec/BUILD.bazel),
  excluding the CLI and test sources. DictionaryData.java is upstream's generated
  built-in dictionary source, included as-is; no generator runs during the build.
- Integration: local Gradle module `:brotliJava`; no native code, downloaded
  executable, prebuilt JAR or runtime fetch is added. Upstream runtime Java and
  `proguard.pgcfg` are unmodified; `build.gradle` and this README are Readwide integration.
- The app includes `proguard.pgcfg` in release shrinking. DictionaryData is
  loaded by reflection; its class and Dictionary's name must survive R8.
- The adapter enables large-window decoding before the first payload read for
  both raw streams and each Brotli-MT frame. This decoder accepts extension window
  exponents 10–30. History grows according to the stream and available Java memory;
  this is not a cap on total extracted file size. Exact output lengths, CRCs,
  cancellation and free-space checks remain enforced by Readwide.
- External custom dictionaries are not supplied or guessed. This is distinct from
  the built-in Brotli dictionary, which remains included.
- Regression source `SevenZBrotliInputStreamTest.upstreamCompressedDictionaryVectorStillDecodes`
  adapts the MIT-licensed `SynthTest.testIntactDistanceRingBuffer1` vector at the
  same pinned commit (Copyright 2016 Google Inc. All Rights Reserved.).
- New regression sources cover extension headers, mixed frames, malformed input,
  built-in dictionary decoding and synthetic AES/solid/encoded-header containers.
  They are uncompiled/unrun. Real-producer long-distance large-window payloads,
  Android execution and minified-release dictionary loading remain pending.

## Source identity

Git blob SHA-1 values below identify exact upstream file content (including the
`blob <length>` followed by a NUL byte). They are provenance checks, not claims about runtime
validation. The root LICENSE is from the same pinned commit.

| Upstream file under java/org/brotli/dec | Git blob SHA-1 |
| --- | --- |
| BitReader.java | `4aace5019ec7c9929506997a67a66b80ca838410` |
| BrotliError.java | `223bbac300cc352f1a6b8043faa97147991003ac` |
| BrotliInputStream.java | `24935db5af96e276bc4450bd1648fb19da1a28aa` |
| BrotliRuntimeException.java | `1844907220d30ee356a39e576fc29e8f3688e73b` |
| Context.java | `e65cfb6d5ac540c08663cb10706134648f7bac1d` |
| Decode.java | `c4dde3fd85c51197f5996751339ec2f2fea1cb65` |
| Dictionary.java | `8dfbc452363f4af828b9409cdf7b6c10fe4e630e` |
| DictionaryData.java | `6267375951c2d4884ba3bfd071ece06f3e08ed10` |
| Huffman.java | `173075d0a3f73247bdbae3b9d6a6bed75ad6d835` |
| State.java | `3b654b5be71ad8ff9e817191f6917d05d2c4598a` |
| Transform.java | `05d0c0fa9c0cbca85aceda0517dfdd75fe31ea93` |
| Utils.java | `6ae45f1942d3ab6461aa1d77f949e62f6124ba53` |
| proguard.pgcfg | `4c545dce5aa4b107dc9fc8acc375f6816e2928ab` |
