# ZIP PPMd-I regression fixtures

Source: [libarchive regression data](https://github.com/libarchive/libarchive/tree/27cbc7827172698143e440801fc0ba39ccb4f1f5/libarchive/test),
commit `27cbc7827172698143e440801fc0ba39ccb4f1f5` (libarchive 3.8.9).
The two Base64 files contain exact decoded upstream `.uu` archives; no recompression
or payload decoding was performed here. They are host resources / Android-test
assets only, never production APK assets. Original producer/version is not
established by these records; do not present them as newly generated producer proof.

| Upstream file | Original .uu SHA-256 | Decoded archive bytes / SHA-256 |
| --- | --- | --- |
| test_read_format_zip_ppmd8.zipx.uu | edd1a418baf069f41f79b93ec1977a447217c684041fc030305d18dfc354bfbc | 616 / 3524501a47b1947a162cb2b33ccabc8d2bd8c2311b86c0a6af6f7ba28eedbf6e |
| test_read_format_zip_ppmd8_multi.zipx.uu | dfeda169edd10c1935c9b5bb5df2197100bf3b1377f9dea8c18ae87d8c827799 | 3602 / b860d5caf61e9a62672811a32955e27706428155059e6283fd4a761b31d9d6e2 |

The pinned [test source](https://github.com/libarchive/libarchive/blob/27cbc7827172698143e440801fc0ba39ccb4f1f5/libarchive/test/test_read_format_zip.c)
asserts decoded CRC32 values. Metadata inspected without decompressing:

| Member | Raw payload offset / length in multi ZIP | Output bytes | CRC32 |
| --- | --- | --- | --- |
| smartd.conf | 41 / 2361 | 6699 | 8dd7379e |
| ts.conf | 2439 / 365 | 852 | 7ae59b31 |
| vimrc | 2839 / 472 | 912 | ba8e3baa |

The single ZIP holds vimrc at offset 35, packed length 472. All property words are
7: order 8, memory 1 MiB, restart mode. Test mutation to cut-off does not exhaust the
model and is not proof of restoration behavior. Synthetic AES wrappers test the
app contract, not original encrypted-producer archives. All new methods are unrun.

## Retained upstream test license (BSD-2-Clause)

Copyright (c) 2003-2007 Tim Kientzle
Copyright (c) 2011 Michihiro NAKAJIMA
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:
1. Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE AUTHOR(S) ``AS IS'' AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE AUTHOR(S) BE LIABLE FOR ANY DIRECT, INDIRECT,
INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
