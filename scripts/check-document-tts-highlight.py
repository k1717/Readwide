#!/usr/bin/env python3
"""Compile the full TTS source/controller/math and replay actual JS with synthetic DOM.

Usage: check-document-tts-highlight.py SOURCE_ROOT OUTPUT_DIR
Requires Python, JDK17+, Node. Android WebView and Html.fromHtml are explicit
facades; fixtures supply the plain text after HTML conversion. No device test.
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
        p = Path(os.environ['JAVA_HOME']) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
        if p.is_file():
            return str(p)
    result = shutil.which(name)
    if not result:
        raise SystemExit('Executable missing: ' + name)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source_root', type=Path)
    parser.add_argument('output_dir', type=Path)
    args = parser.parse_args()
    root, out = args.source_root.resolve(strict=True), args.output_dir.resolve()
    here = Path(__file__).resolve().parent
    if any(out == p or p in out.parents for p in (root, here.parent)):
        raise SystemExit('Output must be outside both source trees')
    out.mkdir(parents=True, exist_ok=True)
    generated, classes = out / 'src', out / 'classes'
    generated.mkdir(exist_ok=True)
    classes.mkdir(exist_ok=True)
    stubs = {
        'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}',
        'androidx/annotation/Nullable.java': 'package androidx.annotation; public @interface Nullable {}',
        'android/webkit/ValueCallback.java': 'package android.webkit; public interface ValueCallback<T>{void onReceiveValue(T value);}',
        'android/webkit/WebSettings.java': '''package android.webkit; public class WebSettings {boolean enabled; public boolean getJavaScriptEnabled(){return enabled;} public void setJavaScriptEnabled(boolean v){enabled=v;}}''',
        'android/webkit/WebView.java': '''package android.webkit; public class WebView {public final java.util.List<String> scripts=new java.util.ArrayList<>(); final WebSettings settings=new WebSettings(); public WebSettings getSettings(){return settings;} public void evaluateJavascript(String js,ValueCallback<String> cb){scripts.add(js);if(cb!=null)cb.onReceiveValue("true");}}''',
        'com/readwide/manager/util/FileUtils.java': '''package com.readwide.manager.util; public class FileUtils {public static String htmlToPlainText(String fixture){return fixture;}}''',
        'com/readwide/manager/DocumentPageActivity.java': '''package com.readwide.manager;
class DocumentPageActivity {
 int currentPage,markdownTtsAnchorCharPosition=-1,pagedTtsResumeAnchorCharPosition=-1,lastMarkdownSourceOffset;
 String markdownSourceText="",lastMarkdownAnchorText=""; boolean markdown;
 android.webkit.WebView webView=new android.webkit.WebView();
 final DocumentTtsHighlightController highlighter=new DocumentTtsHighlightController(this);
 static class Page {String html;Page(String text){html=text;}}
 boolean isMarkdownDocument(){return markdown;}
 void onDocumentTtsSegmentSpoken(int start,int end){}
 DocumentTtsHighlightController documentTtsHighlight(){return highlighter;}
 void restoreDocumentJavaScriptPolicy(android.webkit.WebView view,int page,boolean temporary){}
}
''',
    }
    sources = []
    for rel, text in stubs.items():
        p = generated / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text + '\n', encoding='utf-8')
        sources.append(p)
    java_root = root / 'app/src/main/java/com/readwide/manager'
    production = [java_root / name for name in (
        'DocumentTtsHighlightController.java', 'DocumentTtsHighlightMath.java',
        'DocumentTtsTextSource.java', 'TtsTextSource.java', 'util/TtsAnchorTextMath.java')]
    sources += production + [here / 'tests/DocumentTtsHighlightHostCheck.java']
    arguments = out / 'javac.args'
    arguments.write_text('\n'.join('"' + str(p).replace('\\', '/') + '"' for p in sources), encoding='utf-8')
    commands = [
        ('compile', [executable('javac'), '--release', '17', '-encoding', 'UTF-8', '-d', str(classes), '@' + str(arguments)]),
        ('host', [executable('java'), '-cp', str(classes), 'com.readwide.manager.DocumentTtsHighlightHostCheck', str(out)]),
        ('javascript', [executable('node'), str(here / 'tests/document-tts-highlight.cjs'), str(out)]),
    ]
    records, failure = [], 0
    for name, command in commands:
        with (out / (name + '.log')).open('w', encoding='utf-8') as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=False)
        records.append({'name': name, 'exit': result.returncode, 'command': command})
        (out / 'commands.json').write_text(json.dumps({'production_sha256': {
            str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in production},
            'commands': records}, indent=2) + '\n', encoding='utf-8')
        print((out / (name + '.log')).read_text(encoding='utf-8', errors='replace'), end='')
        if result.returncode and name == 'compile':
            return result.returncode
        failure = failure or result.returncode
    return failure


if __name__ == '__main__':
    raise SystemExit(main())
