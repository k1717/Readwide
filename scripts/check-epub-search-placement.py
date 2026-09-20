#!/usr/bin/env python3
"""Run actual Java-generated search/anchor JS with bounded synthetic DOM collaborators.

Usage: check-epub-search-placement.py SOURCE_ROOT OUTPUT_DIR
Requires Python, JDK17+, Node. This is not Android/WebView/device validation.
The legacy comparison extracts the exact old evaluateJavascript argument.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

sys.dont_write_bytecode = True


def executable(name):
    if name in ('java', 'javac') and os.environ.get('JAVA_HOME'):
        candidate = Path(os.environ['JAVA_HOME']) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(name)
    if not found:
        raise SystemExit('Missing executable: ' + name)
    return found


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source_root', type=Path)
    parser.add_argument('output_dir', type=Path)
    args = parser.parse_args()
    root, out = args.source_root.resolve(strict=True), args.output_dir.resolve()
    here = Path(__file__).resolve().parent
    if any(out == p or p in out.parents for p in (root, here.parent)):
        raise SystemExit('Output must be outside both source trees')
    out.mkdir(parents=True, exist_ok=True)
    classes = out / 'classes'
    classes.mkdir(exist_ok=True)
    java_dir = root / 'app/src/main/java/com/readwide/manager'
    controller = java_dir / 'DocumentSearchController.java'
    source = controller.read_text(encoding='utf-8')
    spec = importlib.util.spec_from_file_location('extract_search_members', here / 'test_support/extract_members.py')
    extractor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(extractor)
    reveal = extractor.member(source, 'private void scrollDocumentSearchCurrentIntoView()')
    clear = extractor.member(source, 'void clearDocumentSearchState(')
    integration = [
        {'name': 'controller uses tested reveal builder with native viewport height',
         'passed': 'DocumentSearchJavascript.revealCurrentMatch(safeBottomPx, targetView.getHeight())' in reveal},
        {'name': 'clearing displayed search markup uses preserving reload path',
         'passed': 'reloadCurrentPageToRefreshSearchMarkup();' in clear
                   and 'activity.reloadCurrentDocumentPreservingPosition();' in extractor.member(
                       source, 'private void reloadCurrentPageToRefreshSearchMarkup()')},
    ]
    (out / 'integration.json').write_text(json.dumps(integration, indent=2) + '\n', encoding='utf-8')
    facades = out / 'SearchPlacementFacades.java'
    facades.write_text('''package com.readwide.manager;
import java.util.function.Consumer;
// Explicit host collaborators, not Android implementations.
class TextView {}
class WebSettings { boolean enabled; boolean getJavaScriptEnabled(){return enabled;} void setJavaScriptEnabled(boolean value){enabled=value;} }
class WebView { final WebSettings settings=new WebSettings(); Consumer<String> callback; WebSettings getSettings(){return settings;} void evaluateJavascript(String source,Consumer<String> callback){this.callback=callback;} }
class DocumentPageActivity {
 boolean activityDestroyed,valid=true; int currentPage,documentAnchorPageGeneration,activeDocumentSearchOrdinal=1,pageCount=1;
 String activeDocumentSearchQuery="春"; WebView webView=new WebView(); int reloads,directLoads,reveals;
 boolean hasValidCurrentDocumentPage(){return valid;} void clearDocumentEdgeArm(){}
 void reloadCurrentDocumentPreservingPosition(){reloads++;} void showPage(int page,int direction){directLoads++;}
 void restoreDocumentJavaScriptPolicy(WebView target,int page,boolean temporary){}
}
''', encoding='utf-8')
    search_probe = out / 'DocumentSearchController.java'
    search_probe.write_text('''package com.readwide.manager;
class DocumentSearchController {
 private static final String CURRENT_SEARCH_ID="rw-document-search-current";
 final DocumentPageActivity activity; DocumentSearchController(DocumentPageActivity a){activity=a;}
 void updateDocumentSearchStatus(TextView status){} void scrollDocumentSearchCurrentIntoView(){activity.reveals++;}
 int documentSearchPageCount(){return activity.pageCount;}
 void refresh(){reloadCurrentPageToRefreshSearchMarkup();} void select(){applySamePageDocumentSearchSelectionOrReload(new TextView());}
''' + '\n'.join(extractor.member(source, token) for token in [
        'private void applySamePageDocumentSearchSelectionOrReload(',
        'private void reloadCurrentPageToRefreshSearchMarkup()',
    ]) + '\n}\n', encoding='utf-8')
    font_probe = out / 'DocumentFontDialogController.java'
    font_source = (java_dir / 'DocumentFontDialogController.java').read_text(encoding='utf-8')
    font_probe.write_text('''package com.readwide.manager;
class DocumentFontDialogController {
 final DocumentPageActivity activity; DocumentFontDialogController(DocumentPageActivity a){activity=a;}
 void refresh(){refreshCurrentDocumentFont();}
''' + extractor.member(font_source, 'private void refreshCurrentDocumentFont()') + '\n}\n', encoding='utf-8')
    placement = java_dir / 'DocumentSearchJavascript.java'
    if not placement.is_file():
        method = source.split('private void scrollDocumentSearchCurrentIntoView()', 1)[1]
        expression = method.split('targetView.evaluateJavascript(', 1)[1].split(',\n                value ->', 1)[0]
        placement = out / 'DocumentSearchJavascript.java'
        placement.write_text('package com.readwide.manager; final class DocumentSearchJavascript {\n'
            'static final String CURRENT_SEARCH_ID="rw-document-search-current";\n'
            'static String revealCurrentMatch(int safeBottomPx,int webViewHeightPx){return '
            + expression + ';}}\n', encoding='utf-8')
    anchor = java_dir / 'DocumentContentAnchorJavascript.java'
    exporter = out / 'ExportSearchPlacement.java'
    exporter.write_text('''package com.readwide.manager;
import java.nio.file.*;
public final class ExportSearchPlacement {
 public static void main(String[] args) throws Exception {
  Path out=Path.of(args[0]);
  Files.writeString(out.resolve("plain.js"),DocumentSearchJavascript.revealCurrentMatch(-1,1200));
  Files.writeString(out.resolve("panel.js"),DocumentSearchJavascript.revealCurrentMatch(160,1200));
  Files.writeString(out.resolve("anchor.js"),DocumentContentAnchorJavascript.installScript());
 }
}
''', encoding='utf-8')
    commands = [
        ('compile', [executable('javac'), '--release', '17', '-encoding', 'UTF-8', '-d', str(classes),
            str(placement), str(anchor), str(exporter), str(facades), str(search_probe), str(font_probe),
            str(here / 'tests/EpubSearchControllerHostCheck.java')]),
        ('export', [executable('java'), '-cp', str(classes), 'com.readwide.manager.ExportSearchPlacement', str(out)]),
        ('controller', [executable('java'), '-cp', str(classes), 'com.readwide.manager.EpubSearchControllerHostCheck']),
        ('behavior', [executable('node'), str(here / 'tests/epub-search-placement.cjs'), str(out)]),
    ]
    records = []
    failure = 0 if all(c['passed'] for c in integration) else 1
    for name, command in commands:
        with (out / (name + '.log')).open('w', encoding='utf-8') as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=False)
        records.append({'step': name, 'exit': result.returncode, 'command': command})
        (out / 'commands.json').write_text(json.dumps({
            'source': str(root), 'controller_sha256': hashlib.sha256(controller.read_bytes()).hexdigest(),
            'placement_sha256': hashlib.sha256(placement.read_bytes()).hexdigest(),
            'anchor_sha256': hashlib.sha256(anchor.read_bytes()).hexdigest(), 'commands': records,
        }, indent=2) + '\n', encoding='utf-8')
        print((out / (name + '.log')).read_text(encoding='utf-8', errors='replace'), end='')
        if result.returncode and name in ('compile', 'export'):
            return result.returncode
        failure = failure or result.returncode
    print('STATIC TOTAL: %d passed; %d failed' % (sum(c['passed'] for c in integration), sum(not c['passed'] for c in integration)))
    return failure


if __name__ == '__main__':
    raise SystemExit(main())
