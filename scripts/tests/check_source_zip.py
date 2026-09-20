#!/usr/bin/env python3
"""Compare both source packagers on retained source and excluded local-file fixtures.
Usage: check_source_zip.py SOURCE_ROOT OUTPUT_DIR
Requires Python and PowerShell (powershell or pwsh). Outputs stay outside source.
"""
from pathlib import Path
import hashlib, json, shutil, subprocess, sys, zipfile

root, out = (Path(p).resolve() for p in sys.argv[1:3])
if out == root or root in out.parents:
    raise SystemExit('Output must be outside source tree')
out.mkdir(parents=True, exist_ok=True)
fixture = out/'fixture'
fixture.mkdir()  # A new output directory prevents stale-fixture passes.
keep = {
    '.gitignore': b'build/\n', '.gitattributes': b'*.sh text eol=lf\n',
    'LICENSE': b'License fixture\n', 'gradlew': b'#!/bin/sh\nexit 0\n',
    'scripts/check.sh': b'#!/bin/sh\nexit 0\n',
    'app/src/main/Main.java': b'class Main {}\n',
    'gradle/wrapper/gradle-wrapper.jar': b'wrapper fixture',
    'app/src/test/resources/archive.zip': b'archive fixture',
    'third_party/libarchive-android/library/src/main/jni/external/libarchive/build/cmake/config.h.in': b'cmake fixture',
}
excluded = ['__pycache__/check.cpython-314.pyc', 'scripts/cache.pyo', 'project.iml',
    '.pytest_cache/state', '.ruff_cache/state', '.venv/bin/python', '.vscode/settings.json',
    '.captures/profile', '.gradle/state', '.git/config', '.idea/workspace.xml',
    'app/build/out', 'third_party/module/build/out', '.cxx/state', '.externalNativeBuild/state',
    '.kotlin/state', 'docs/review-round8/REVIEW.md', 'docs/review-20260918/report.md',
    'docs/archive-review-round7.md', 'scripts/review-jvm-round8/run.py', 'captures/screenshot.png', 'app.apk', 'release.aab', 'private.jks',
    'private.keystore', 'private.p12', 'private.pfx', 'secrets.properties', 'keystore.properties',
    'local.properties', '.env', '.env.local', 'readwide_backup_example.json',
    'TextView_backup_example.json', 'desktop.ini', 'build.log', 'scratch.tmp']
for name, data in keep.items():
    p=fixture/name; p.parent.mkdir(parents=True,exist_ok=True); p.write_bytes(data)
for name in excluded:
    p=fixture/name; p.parent.mkdir(parents=True,exist_ok=True); p.write_text('Excluded test fixture\n')
for name in ('create_source_zip.py','create_source_zip.ps1'):
    relative='scripts/'+name
    keep[relative]=(root/relative).read_bytes()
    (fixture/relative).write_bytes(keep[relative])
shell=shutil.which('powershell') or shutil.which('pwsh')
if not shell: raise SystemExit('PowerShell required for packager parity check')
commands=[('python',[sys.executable,str(fixture/'scripts/create_source_zip.py'),str(out/'python.zip')]),
          ('powershell',[shell,'-NoProfile','-ExecutionPolicy','Bypass','-File',str(fixture/'scripts/create_source_zip.ps1'),'-Output',str(out/'powershell.zip')])]
results=[]
for label,cmd in commands:
    result=subprocess.run(cmd,capture_output=True,text=True)
    (out/(label+'.log')).write_text(result.stdout+result.stderr,encoding='utf8')
    if result.returncode: raise RuntimeError(label+' failed; see '+str(out/(label+'.log')))
    with zipfile.ZipFile(out/(label+'.zip')) as archive:
        assert archive.testzip() is None
        assert len(archive.namelist())==len(set(archive.namelist()))
        assert set(archive.namelist())==set(keep), (label,set(archive.namelist())^set(keep))
        for info in archive.infolist():
            assert archive.read(info)==keep[info.filename],info.filename
            mode=0o755 if info.filename=='gradlew' or info.filename.endswith('.sh') else 0o644
            assert info.create_system==3 and (info.external_attr>>16)&0o777==mode,info.filename
    original=hashlib.sha256((out/(label+'.zip')).read_bytes()).hexdigest()
    repeated=subprocess.run(cmd,capture_output=True,text=True)
    assert repeated.returncode!=0, 'Existing output was overwritten'
    assert hashlib.sha256((out/(label+'.zip')).read_bytes()).hexdigest()==original
    results.append({'packager':label,'retained_files':len(keep),'excluded_files':len(excluded),
        'crc':True,'content_hashes':True,'portable_modes':True,'overwrite_refused':True})
(out/'results.json').write_text(json.dumps(results,indent=2),encoding='utf8')
print(f'PASS both packagers: {len(keep)} retained files, {len(excluded)} exclusions, exact contents, executable modes, CRC and overwrite protection.')
