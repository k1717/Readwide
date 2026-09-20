#!/usr/bin/env python3
"""Replay PDF paging requests with an explicit host-only clock and Android facades.

Usage: check-pdf-tap-paging.py SOURCE_ROOT OUTPUT_DIR
Requires Python 3 and JDK 17+ (PATH or JAVA_HOME); no downloads.
Compiles the complete production PdfPageTurnController and exact selected Activity
members. This does not run Android GestureDetector, rendering, or the full Activity.
"""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys

sys.dont_write_bytecode = True


def executable(name):
    if os.environ.get('JAVA_HOME'):
        p = Path(os.environ['JAVA_HOME']) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
        if p.is_file():
            return str(p)
    found = shutil.which(name)
    if not found:
        raise SystemExit('JDK executable unavailable: ' + name)
    return found


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
    classes.mkdir(exist_ok=True)
    spec = importlib.util.spec_from_file_location('extract_pdf_members', here / 'test_support/extract_members.py')
    extractor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(extractor)
    java_root = root / 'app/src/main/java/com/readwide/manager'
    activity = (java_root / 'PdfReaderActivity.java').read_text(encoding='utf8')
    controller = java_root / 'PdfPageTurnController.java'
    tokens = ['private PdfPageTurnController pageTurns(', 'private boolean handleFastPdfTapPaging(',
              'private int getPdfTapPagingAction(', 'private boolean handlePdfTapPaging(',
              'void turnPdfDisplayPageBy(', 'boolean onMatrixPageSwipe(', 'void onMatrixTap(']
    members = [extractor.member(activity, token) for token in tokens]
    buttons = []
    controls = extractor.member(activity, 'void setupControls(')
    for name in ('prevButton', 'nextButton'):
        matches = re.findall(r'^\s*' + name + r'\.setOnClickListener\([^\n]+', controls, re.M)
        if len(matches) != 1:
            raise SystemExit('Expected one button binding: ' + name)
        buttons.append(matches[0])

    def write(rel, text):
        p = generated / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding='utf8')

    # All Android collaborators below are deterministic facades, not SDK classes.
    write('android/os/SystemClock.java', 'package android.os; public final class SystemClock { public static long now; public static long uptimeMillis(){return now;} }')
    write('androidx/annotation/NonNull.java', 'package androidx.annotation; public @interface NonNull {}')
    keys = sorted(set(re.findall(r'KeyEvent\.(KEYCODE_\w+)', controller.read_text(encoding='utf8'))))
    write('android/view/KeyEvent.java', '''package android.view;
public class KeyEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1;
 %s
 private final int action,key,repeat;
 public KeyEvent(int action,int key,int repeat){this.action=action;this.key=key;this.repeat=repeat;}
 public int getAction(){return action;} public int getKeyCode(){return key;} public int getRepeatCount(){return repeat;}
}''' % '\n'.join('public static final int %s=%d;' % (key, i + 10) for i, key in enumerate(keys)))
    write('android/view/MotionEvent.java', '''package android.view;
public class MotionEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;
 private final int action; private final float x,y;
 public MotionEvent(int action,float x,float y){this.action=action;this.x=x;this.y=y;}
 public int getActionMasked(){return action;} public float getRawX(){return x;} public float getRawY(){return y;}
}''')
    write('android/view/View.java', '''package android.view;
public class View {
 public interface OnClickListener{void onClick(View v);} private OnClickListener listener;
 public void setOnClickListener(OnClickListener listener){this.listener=listener;}
 public void performClick(){listener.onClick(this);}
 public boolean zoomed;
 public boolean isZoomedIn(){return zoomed;}
 public int getWidth(){return 1000;} public int getHeight(){return 1000;}
 public void getLocationOnScreen(int[] location){location[0]=0;location[1]=0;}
 public int getPaddingLeft(){return 0;} public int getPaddingRight(){return 0;}
 public int getPaddingTop(){return 0;} public int getPaddingBottom(){return 0;}
}''')
    write('com/readwide/manager/util/PrefsManager.java', '''package com.readwide.manager.util;
public class PrefsManager {
 public static final int TAP_ZONE_HORIZONTAL=0,TAP_ZONE_VERTICAL=1;
 public boolean enabled=true,keys=true; public int mode=TAP_ZONE_HORIZONTAL;
 public boolean getPdfTapPagingEnabled(){return enabled;} public boolean getVolumeKeyScroll(){return keys;}
 public int getTapZoneMode(){return mode;} public int getTapLeadingZonePercent(){return 35;}
 public int getTapTrailingZonePercent(){return 35;}
}''')
    write('com/readwide/manager/PdfReaderActivity.java', '''package com.readwide.manager;
import android.view.*; import androidx.annotation.NonNull; import com.readwide.manager.util.*;
// Only the selected paging members below are production code. State and navigation are host facades.
class PdfReaderActivity {
 PrefsManager prefs=new PrefsManager(); int pageCount=30,currentPage=5,turns,chromeToggles;
 boolean verticalPageSlideMode,spread,pdfTapPagingSequence,gestureSawMultiTouch,viewportPanConsumed;
 float gestureStartRawX,gestureStartRawY; int touchSlop=8;
 View pdfViewport=new View(),pdfPageMatrixView=new View(),prevButton=new View(),nextButton=new View();
 private PdfPageTurnController pageTurnController;
 boolean isPdfTwoPageSpreadMode(){return spread && !verticalPageSlideMode;}
 void goToPage(int page,int direction){currentPage=page;turns++;}
 void togglePdfChrome(){chromeToggles++;}
 boolean confirmed(float x,float y){return handlePdfTapPaging(new MotionEvent(MotionEvent.ACTION_UP,x,y));}
 boolean fast(float x,float y){gestureStartRawX=x;gestureStartRawY=y;
  handleFastPdfTapPaging(new MotionEvent(MotionEvent.ACTION_DOWN,x,y),true);
  return handleFastPdfTapPaging(new MotionEvent(MotionEvent.ACTION_UP,x,y),true);}
 boolean cancelTap(){handleFastPdfTapPaging(new MotionEvent(MotionEvent.ACTION_DOWN,900,500),true);
  handleFastPdfTapPaging(new MotionEvent(MotionEvent.ACTION_CANCEL,900,500),true);
  return handleFastPdfTapPaging(new MotionEvent(MotionEvent.ACTION_UP,900,500),true);}
 boolean key(KeyEvent e){return pageTurns().handlePageTurnKey(e);}
 void installButtons(){%s}
 %s
}''' % ('\n'.join(buttons), '\n'.join(members)))
    sources = sorted(generated.rglob('*.java')) + [controller, java_root / 'util/TapZoneMath.java',
               java_root / 'util/SpreadMath.java', here / 'tests/PdfTapPagingHostCheck.java']
    coverage = {'boundary': __doc__, 'members': tokens, 'source_hashes': {
        str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in (controller, java_root / 'PdfReaderActivity.java', java_root / 'util/TapZoneMath.java', java_root / 'util/SpreadMath.java')}}
    (out / 'coverage.json').write_text(json.dumps(coverage, indent=2), encoding='utf8')
    commands = [('compile', [executable('javac'), '--release', '17', '-encoding', 'UTF-8', '-d', str(classes)] + list(map(str, sources))),
                ('behavior', [executable('java'), '-cp', str(classes), 'com.readwide.manager.PdfTapPagingHostCheck'])]
    for name, command in commands:
        with (out / (name + '.log')).open('w', encoding='utf8') as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=False)
        output = (out / (name + '.log')).read_text(encoding='utf8', errors='replace')
        if output:
            print(output, end='')
        if result.returncode:
            return result.returncode
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
