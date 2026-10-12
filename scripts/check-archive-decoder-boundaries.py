#!/usr/bin/env python3
"""Host checks for ALZ tables/containers and shared Deflate input contracts.

Compiles all three changed production classes. Android progress, EGG, and Commons
BZip2 have explicit unavailable host seams; successful checks must not call them.
ArchiveSupport contributes exact extracted API/output-budget members, not the
complete Android dispatcher. No file-descriptor or heap cap is set.
"""
from pathlib import Path
import argparse
import bz2
import ctypes
import hashlib
import importlib.util
import json
import os
import random
import re
import shutil
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True

PKG = Path('com/readwide/manager/archive')
CLASSES = ['AlzSelectorBoundaryChecks', 'AlzHuffmanBoundaryChecks', 'ArchiveDecoderBoundaryChecks']


def write(root, name, content):
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf-8')
    return path


def generated_sources(root, destination):
    spec = importlib.util.spec_from_file_location('extract_members', root / 'scripts/test_support/extract_members.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    text = (root / 'app/src/main/java' / PKG / 'ArchiveSupport.java').read_text(encoding='utf-8')
    tokens = ['public static final class EntryInfo', 'private static String normalizeDisplayPath(',
              'public static final class PasswordRequiredException', 'public static class UnsupportedArchiveFeatureException',
              'public enum ExtractionFailure', 'public static OutputStream openExtractionOutputStream(',
              'static long runtimeExtractionBudgetBytes(', 'static long runtimeExtractionBudgetForUsableSpace(']
    margin = re.search(r'private static final long MIN_EXTRACTION_FREE_MARGIN_BYTES\s*=\s*([^;]+);', text)
    if not margin:
        raise RuntimeError('Cannot find unchanged free-space margin')
    header = ('package com.readwide.manager.archive; import java.io.*; import androidx.annotation.*; '
              'public final class ArchiveSupport { static final long MIN_EXTRACTION_FREE_MARGIN_BYTES=' + margin[1] + ';\n')
    write(destination, PKG / 'ArchiveSupport.java', header + '\n'.join(module.member(text, t) for t in tokens) + '\n}\n')
    for annotation in ['NonNull', 'Nullable']:
        write(destination, f'androidx/annotation/{annotation}.java', f'package androidx.annotation; public @interface {annotation} {{}}\n')
    write(destination, 'com/readwide/manager/util/FileOperationProgress.java', '''package com.readwide.manager.util;
public class FileOperationProgress {
 public boolean checkpoint(){throw new AssertionError("HOST progress unavailable");}
 public void setTotalBytes(long n){throw new AssertionError("HOST progress unavailable");}
 public void setDetail(String n){throw new AssertionError("HOST progress unavailable");}
 public void addDoneBytes(long n){throw new AssertionError("HOST progress unavailable");}
}''')
    write(destination, PKG/'ArchiveExtractionProgressTracker.java', '''package com.readwide.manager.archive;
final class ArchiveExtractionProgressTracker {
 void onDirectory(String n){throw new AssertionError("HOST progress unavailable");}
 void onFile(String n){throw new AssertionError("HOST progress unavailable");}
}''')
    write(destination, PKG/'EggArchiveReader.java', '''package com.readwide.manager.archive;
import java.io.*; import java.util.*;import com.readwide.manager.util.FileOperationProgress;
final class EggArchiveReader {
 static int calls;
 private static IOException unavailable(){calls++;return new IOException("HOST EGG unavailable");}
 static List<ArchiveSupport.EntryInfo> listEntries(File f,char[] p)throws IOException{throw unavailable();}
 static boolean extractSingleEntry(File f,String e,File o,char[] p)throws IOException{throw unavailable();}
 static boolean extractArchiveIntoDirectory(File f,File o,char[] p,FileOperationProgress x,ArchiveExtractionProgressTracker t)throws IOException{throw unavailable();}
 static boolean requiresPasswordForExtraction(File f){calls++;throw new AssertionError("HOST EGG unavailable");}
}''')
    write(destination, 'org/apache/commons/compress/compressors/bzip2/BZip2CompressorInputStream.java', '''package org.apache.commons.compress.compressors.bzip2;
import java.io.*;public final class BZip2CompressorInputStream extends InputStream {
 public static int calls;
 public BZip2CompressorInputStream(InputStream in)throws IOException{calls++;throw new IOException("HOST Commons BZip2 unavailable");}
 public int read(){throw new AssertionError("HOST Commons BZip2 unavailable");}
}''')
    write(destination, PKG/'Rar5CompressedArchiveExtractor.java', '''package com.readwide.manager.archive;
final class Rar5CompressedArchiveExtractor {static final class ChecksumException extends java.io.IOException {}}
''')
    # Execute each suite in-process so the no-external-codec assertion observes all calls.
    write(destination, PKG/'ArchiveBoundaryHostLauncher.java', '''package com.readwide.manager.archive;
public final class ArchiveBoundaryHostLauncher {
 public static void main(String[] args)throws Exception {
  switch(args[0]) {
   case "AlzSelectorBoundaryChecks":AlzSelectorBoundaryChecks.main(new String[0]);break;
   case "AlzHuffmanBoundaryChecks":AlzHuffmanBoundaryChecks.main(new String[0]);break;
   case "ArchiveDecoderBoundaryChecks":ArchiveDecoderBoundaryChecks.main(new String[0]);break;
   default:throw new IllegalArgumentException(args[0]);
  }
  int calls=EggArchiveReader.calls+org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream.calls;
  System.out.println("UNAVAILABLE_CODEC_CALLS="+calls);
  if(calls!=0)throw new AssertionError("An unavailable external backend was called");
 }
}''')


def bit_string(data):
    return ''.join(f'{x:08b}' for x in data)


def bit_bytes(bits):
    bits += '0' * (-len(bits) % 8)
    return bytes(int(bits[i:i+8], 2) for i in range(0, len(bits), 8))


def trimmed_bzip(packed):
    """Test-fixture framing conversion only; not a general archive parser.

    The fixture corpus is generated here. Require unique ordered 48-bit markers,
    zero randomization flags and a complete standard round-trip before conversion.
    """
    s = bit_string(packed)
    assert packed[:3] == b'BZh'
    block = f'{0x314159265359:048b}'
    end = f'{0x177245385090:048b}'
    positions = sorted([(m.start(), 'block') for m in re.finditer(block, s)] +
                       [(m.start(), 'end') for m in re.finditer(end, s)])
    assert positions and positions[0][0] == 32 and positions[-1][1] == 'end'
    assert sum(k == 'end' for _, k in positions) == 1
    out = []
    for i, (pos, kind) in enumerate(positions):
        if kind == 'end':
            assert len(s) - (pos+80) < 8
            out.append(f'{0x444c5a02:032b}')
        else:
            assert s[pos+80] == '0', 'randomized test block not supported by converter'
            assert i+1 < len(positions)
            out.append(f'{0x444c5a01:032b}' + s[pos+81:positions[i+1][0]])
    return bit_bytes(''.join(out)), len(positions)-1


def reference_fixtures(destination):
    destination.mkdir(parents=True, exist_ok=True)
    rand = random.Random(10032026)
    cases = [
        ('empty', b'', 9), ('one-byte', b'A', 9), ('two-bytes', b'AB', 1),
        ('repeat-200000', b'A'*200000, 9), ('byte-range', bytes(range(256))*37, 9),
        ('unicode', ('ALZ archive\n안녕하세요 日本語 café 🚀\n'*3000).encode(), 1),
        ('random-31', rand.randbytes(31), 1), ('random-65537', rand.randbytes(65537), 9),
        ('random-260000-three-blocks', rand.randbytes(260000), 1),
        ('random-1100000-shrinking-blocks', rand.randbytes(1100000), 9),
        ('mixed-runs', b''.join(bytes([i])*n for i in range(256) for n in [1,2,3,4,5,255,256,259]), 1),
        ('repeat-3MiB', (b'X'*10000+b'Y'*20000+b'ABCD'*4000)*70, 1),
    ]
    manifest, metadata = [], []
    for name, plain, level in cases:
        packed = bz2.compress(plain, compresslevel=level)
        assert bz2.decompress(packed) == plain
        trimmed, blocks = trimmed_bzip(packed)
        (destination/f'{name}.dlz').write_bytes(trimmed)
        # Keep standard compressor output for independent reproduction as evidence.
        (destination/f'{name}.bz2').write_bytes(packed)
        digest = hashlib.sha256(plain).hexdigest()
        manifest.append(f'{name}\t{len(plain)}\t{digest}')
        metadata.append(dict(name=name, output_bytes=len(plain), bzip_blocks=blocks, sha256=digest))
    write(destination, 'manifest.tsv', '\n'.join(manifest)+'\n')
    write(destination, 'provenance.json', json.dumps(dict(encoder='Python bz2 / system libbzip2',
          note='Generated standard streams, independently round-tripped; framing conversion only, not vendor ALZip fixtures.',
          fixtures=metadata), indent=2)+'\n')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1])
    parser.add_argument('--production-root',type=Path,help='Optional baseline production tree; always use current regression sources')
    parser.add_argument('--work-dir',type=Path,help='Retain compiled classes, logs, stubs and reference fixtures here')
    args=parser.parse_args()
    root=args.root.resolve(); production=(args.production_root or root).resolve()
    java,javac=shutil.which('java'),shutil.which('javac')
    if not java or not javac:parser.error('JDK 17 or newer must be on PATH')
    temp=None
    if args.work_dir:
        work=args.work_dir.resolve();work.mkdir(parents=True,exist_ok=True)
    else:
        temp=tempfile.TemporaryDirectory(prefix='readwide-archive-boundaries-');work=Path(temp.name)
    try:
        generated=work/'generated';classes=work/'classes'
        if generated.exists():shutil.rmtree(generated)
        if classes.exists():shutil.rmtree(classes)
        classes.mkdir(parents=True)
        generated_sources(production,generated)
        names=['AlzBzip2InputStream','OwnedDeflateInputStream','AlzipArchiveReader','AlzVolumeResolver',
               'SplitVolumeInput','RarOutputFileGuard','ArchiveFilenameDecoder','AlzipZipCrypto',
               'ArchiveFailureClassifier','ArchiveExtractionByteBudget']
        sources=[production/'app/src/main/java'/PKG/(n+'.java') for n in names]
        sources += [root/'app/src/test/java'/PKG/(n+'.java') for n in CLASSES+['Round4Fixtures','Round6Fixtures']]
        sources += sorted(generated.rglob('*.java'))
        command=[javac,'--release','17','-encoding','UTF-8','-d',str(classes),*map(str,sources)]
        compilation=subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=90)
        write(work,'compile.log',compilation.stdout)
        print('PRODUCTION_ROOT='+str(production),flush=True)
        if compilation.returncode:
            print(compilation.stdout);raise SystemExit(compilation.returncode)
        write(work,'compiled-production-sha256.json',json.dumps({n+'.java':hashlib.sha256((production/'app/src/main/java'/PKG/(n+'.java')).read_bytes()).hexdigest() for n in names},indent=2)+'\n')
        results=[]
        for name in CLASSES:
            run=subprocess.run([java,'-cp',str(classes),'com.readwide.manager.archive.ArchiveBoundaryHostLauncher',name],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=120)
            write(work,name+'.log',run.stdout);print(run.stdout,flush=True)
            match=re.search(r'SUMMARY passed=(\d+) failed=(\d+) total=(\d+)',run.stdout)
            result=dict(suite=name,returncode=run.returncode)
            if match:result.update(zip(['passed','failed','total'],map(int,match.groups())))
            results.append(result)
        reference_fixtures(work/'reference-fixtures')
        run=subprocess.run([java,'-cp',str(classes),'com.readwide.manager.archive.AlzHuffmanBoundaryChecks','--fixtures',str(work/'reference-fixtures')],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=180)
        write(work,'reference-fixtures.log',run.stdout);print(run.stdout,flush=True)
        summary=dict(suites=results,reference_returncode=run.returncode,passed=sum(x.get('passed',0) for x in results),
                     failed=sum(x.get('failed',0) for x in results),reference_fixtures=12,
                     no_heap_or_descriptor_caps=True,full_android_build=False)
        write(work,'summary.json',json.dumps(summary,indent=2)+'\n')
        if run.returncode or any(x['returncode'] for x in results):raise SystemExit(1)
    finally:
        if temp:temp.cleanup()

if __name__=='__main__':main()
