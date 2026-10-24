#!/usr/bin/env python3
"""Negative tests for the MU300 layout in PackageInspector: rewrites ZIP entries of a
real MU300 package (nothing is executed) and checks OK / REFUSED verdicts."""
import json, os, subprocess, sys, zipfile
src, cls, work = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(work, exist_ok=True)
tmp = os.path.join(work, 'variant.zip')
def copy(zi, zo, rename=lambda n: n, skip=(), patch=None):
    for info in zi.infolist():
        if info.filename in skip: continue
        d = zi.read(info)
        if patch and info.filename in patch: d = patch[info.filename](d)
        zo.writestr(rename(info.filename), d)
def man_patch(fn):
    def p(d):
        m = json.loads(d); fn(m); return json.dumps(m, indent=2).encode()
    return {'desktop/manifest.json': p}
def first_hash_change(m):
    sec = m['magisk']
    files = sec.get('files', sec) if isinstance(sec, dict) else sec
    k = next(k for k in files if k.endswith('switch.sh')); files[k] = '0' * 64
cases = [
    ('unmodified', lambda zi, zo: copy(zi, zo), True),
    ('prefix folder', lambda zi, zo: copy(zi, zo, rename=lambda n: 'mu/' + n), True),
    ('tampered worker script', lambda zi, zo: copy(zi, zo, patch={'payload/tf-install-worker.sh': lambda d: d + b'\n#x\n'}), False),
    ('tampered boot image', lambda zi, zo: copy(zi, zo, patch={'payload/boot-linux.img': lambda d: d[:-1] + b'\x01'}), False),
    ('manifest hash changed', lambda zi, zo: copy(zi, zo, patch=man_patch(first_hash_change)), False),
    ('missing payload file', lambda zi, zo: copy(zi, zo, skip={'payload/switch.sh'}), False),
    ('unlisted extra payload file', lambda zi, zo: (copy(zi, zo), zo.writestr('payload/evil.sh', b'echo hi\n')), False),
    ('path traversal entry', lambda zi, zo: (copy(zi, zo), zo.writestr('../evil.sh', b'x')), False),
]
bad = 0
for name, fn, want_ok in cases:
    with zipfile.ZipFile(src) as zi, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_STORED) as zo:
        fn(zi, zo)
    out = subprocess.run(['java', '-cp', cls, 'DesktopInspect', tmp, os.path.join(work, 'out')],
                         capture_output=True, text=True).stdout
    verdict = next((l for l in out.splitlines() if l.startswith(('OK', 'REFUSED'))), 'NONE')
    good = verdict.startswith('OK') == want_ok
    bad += not good
    print(('PASS' if good else 'FAIL'), name, '->', verdict[:150])
os.remove(tmp)
print('mu300 negative: all passed' if bad == 0 else f'mu300 negative: {bad} failed')
sys.exit(1 if bad else 0)
