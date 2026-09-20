#!/usr/bin/env python3
"""Compile the full document loader against explicit queued worker/UI collaborators.

Usage: check-document-load-lifecycle.py SOURCE_ROOT OUTPUT_DIR
Requires Python and JDK17+. Fake parsing/Android/JSON/storage isolate callback
ordering; this does not open real documents or run Android lifecycle events.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


def executable(name):
    if os.environ.get('JAVA_HOME'):
        p = Path(os.environ['JAVA_HOME']) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
        if p.is_file():
            return str(p)
    result = shutil.which(name)
    if not result:
        raise SystemExit('Missing JDK executable: ' + name)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source_root', type=Path)
    parser.add_argument('output_dir', type=Path)
    args = parser.parse_args()
    root, out = args.source_root.resolve(strict=True), args.output_dir.resolve()
    here = Path(__file__).resolve().parent
    if any(out == p or p in out.parents for p in (root, here.parent)):
        raise SystemExit('Output must be outside source trees')
    generated, classes = out / 'src', out / 'classes'
    generated.mkdir(parents=True, exist_ok=True)
    classes.mkdir(exist_ok=True)
    stubs = {
        'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}',
        'android/content/Intent.java': '''package android.content; public class Intent {private final java.util.Map<String,Object> extras=new java.util.HashMap<>(); public Intent putExtra(String key,String value){extras.put(key,value);return this;} public String getStringExtra(String key){return (String)extras.get(key);} public int getIntExtra(String key,int fallback){Object v=extras.get(key);return v instanceof Integer?(Integer)v:fallback;}}''',
        'android/net/Uri.java': 'package android.net; public class Uri {public static Uri parse(String value){return new Uri();}}',
        'android/view/View.java': 'package android.view; public class View {public static final int INVISIBLE=4,VISIBLE=0;public int visibility;public void setVisibility(int value){visibility=value;}}',
        'android/widget/Toast.java': 'package android.widget; public class Toast {}',
        'androidx/core/view/ViewCompat.java': 'package androidx.core.view; public class ViewCompat {public static void requestApplyInsets(android.view.View view){}}',
        'org/json/JSONObject.java': '''package org.json; public class JSONObject {public JSONObject(String json){}public int optInt(String key,int fallback){return fallback;}}''',
        'com/readwide/manager/model/ReaderState.java': '''package com.readwide.manager.model; public class ReaderState {public String getContentAnchorJson(){return "";}public String getEncoding(){return "";}public int getCharPosition(){return -1;}public int getPageNumber(){return 0;}public int getScrollY(){return 0;}}''',
        'com/readwide/manager/util/FileUtils.java': '''package com.readwide.manager.util; public class FileUtils {public static boolean isMarkdownFile(String name){return name.endsWith(".md");}public static boolean isHwpFile(String name){return name.endsWith(".hwp");}public static boolean isWordFile(String name){return name.endsWith(".docx");}public static String getFileNameFromUri(Object context,android.net.Uri uri){return "book.epub";}public static java.io.File copyUriToLocal(Object context,android.net.Uri uri,String name){return new java.io.File(name);}}''',
    }
    files = []
    for rel, text in stubs.items():
        p = generated / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text + '\n', encoding='utf-8')
        files.append(p)
    production = root / 'app/src/main/java/com/readwide/manager/DocumentPageLoadController.java'
    files += [production, here / 'tests/DocumentLoadLifecycleHostCheck.java']
    arguments = out / 'javac.args'
    arguments.write_text('\n'.join('"' + str(p).replace('\\', '/') + '"' for p in files), encoding='utf-8')
    commands = [
        ('compile', [executable('javac'), '--release', '17', '-encoding', 'UTF-8', '-d', str(classes), '@' + str(arguments)]),
        ('tests', [executable('java'), '-cp', str(classes), 'com.readwide.manager.DocumentLoadLifecycleHostCheck']),
    ]
    records = []
    for name, command in commands:
        with (out / (name + '.log')).open('w', encoding='utf-8') as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=False)
        records.append({'step': name, 'command': command, 'exit': result.returncode})
        (out / 'commands.json').write_text(json.dumps({'production_sha256': hashlib.sha256(production.read_bytes()).hexdigest(), 'commands': records}, indent=2) + '\n', encoding='utf-8')
        print((out / (name + '.log')).read_text(encoding='utf-8', errors='replace'), end='')
        if result.returncode:
            return result.returncode
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
