#!/usr/bin/env python3
"""Check actual EPUB bookmark JavaScript using bounded synthetic DOM collaborators.

Usage: check-epub-bookmark-preview.py SOURCE_ROOT OUTPUT_DIR
Requires Python 3, JDK 17+ and Node.js; no downloads or third-party modules.
This is not Android/WebView rendering, EPUB integration, or device validation.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


def executable(name):
    if name in ('java', 'javac') and os.environ.get('JAVA_HOME'):
        candidate = Path(os.environ['JAVA_HOME']) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(name)
    if not found:
        raise SystemExit(f'Required executable unavailable: {name} (use PATH or JAVA_HOME for the JDK)')
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source_root', type=Path)
    parser.add_argument('output_dir', type=Path)
    args = parser.parse_args()
    root = args.source_root.resolve(strict=True)
    out = args.output_dir.resolve()
    runner_root = Path(__file__).resolve().parent.parent
    if any(out == source_tree or source_tree in out.parents for source_tree in (root, runner_root)):
        raise SystemExit('Output directory must be outside both the input and harness source trees')
    java_source = root / 'app/src/main/java/com/readwide/manager/DocumentContentAnchorJavascript.java'
    if not java_source.is_file():
        raise SystemExit(f'Anchor source not found: {java_source}')
    harness = Path(__file__).resolve().parent / 'tests/epub-bookmark-preview.cjs'
    if not harness.is_file():
        raise SystemExit(f'Behavioral harness not found: {harness}')
    javac, java, node = executable('javac'), executable('java'), executable('node')
    out.mkdir(parents=True, exist_ok=True)
    classes = out / 'classes'
    classes.mkdir(exist_ok=True)
    exporter = out / 'ExportBookmarkPreview.java'
    exporter.write_text('''package com.readwide.manager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
public final class ExportBookmarkPreview {
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(args[0]), DocumentContentAnchorJavascript.installScript(), StandardCharsets.UTF_8);
    }
}
''', encoding='utf-8')
    script = out / 'install.js'
    commands = [
        ('compile', [javac, '--release', '17', '-encoding', 'UTF-8', '-d', str(classes),
                     str(java_source), str(exporter)]),
        ('export', [java, '-cp', str(classes), 'com.readwide.manager.ExportBookmarkPreview', str(script)]),
        ('behavior', [node, str(harness), str(script), str(out / 'results.json')]),
    ]
    records = []
    for name, command in commands:
        with (out / (name + '.log')).open('w', encoding='utf-8') as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=False)
        records.append({'step': name, 'command': command, 'exit': result.returncode})
        (out / 'commands.json').write_text(json.dumps({
            'source': str(java_source),
            'source_sha256': hashlib.sha256(java_source.read_bytes()).hexdigest(),
            'commands': records,
        }, indent=2) + '\n', encoding='utf-8')
        output = (out / (name + '.log')).read_text(encoding='utf-8', errors='replace')
        if output:
            print(output, end='')
        if result.returncode:
            return result.returncode
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
