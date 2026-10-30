#!/usr/bin/env python3
"""Negative tests for PackageInspector: builds altered copies of a package ZIP
(only rewriting ZIP entries, nothing is executed) and checks the parser's verdict."""
import os, subprocess, sys, zipfile

src, cls, work = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(work, exist_ok=True)
tmp = os.path.join(work, 'variant.zip')

def variant(fn):
    with zipfile.ZipFile(src) as zi, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_STORED) as zo:
        fn(zi, zo)

def copy(zi, zo, rename=lambda n: n, skip=(), patch=None):
    for info in zi.infolist():
        if info.filename in skip:
            continue
        data = zi.read(info)
        if patch and info.filename in patch:
            data = patch[info.filename](data)
        zo.writestr(rename(info.filename), data)

import hashlib, re
def relist(patch):
    """Patch files and rewrite their SHA256SUMS lines so only the profile rules can reject the variant."""
    def sums(d):
        out = d.decode()
        for name, fn in patch.items():
            rel = name[len('files/'):] if name.startswith('files/') else name
            def fix(m):
                return hashlib.sha256(fn(ORIG[name])).hexdigest() + m.group(2)
            out = re.sub(r'^([0-9a-f]{64})(\s+\*?(?:\./)?(?:files/)?' + re.escape(rel) + r')$', fix, out, flags=re.M)
        return out.encode()
    full = dict(patch); full['files/SHA256SUMS'] = sums
    return full
with zipfile.ZipFile(src) as _z:
    ORIG = {n: _z.read(n) for n in ('files/device-profile.json', 'files/tf-install.env')}
def sub(a, b):
    return lambda d: d.replace(a, b)
PROFILE_CASES = [
    ('unknown device profile', lambda zi, zo: copy(zi, zo, patch=relist({
        'files/device-profile.json': sub(b'"k1"', b'"zz9"'),
        'files/tf-install.env': sub(b'PROFILE=k1', b'PROFILE=zz9')})), '不支持的机型 profile'),
    ('K1 package with a second model', lambda zi, zo: copy(zi, zo, patch=relist({
        'files/device-profile.json': sub(b'["angelcare K1"]', b'["angelcare K1", "Other"]')})), 'K1 安装包的机型标识不正确'),
    ('profile mismatch between manifests', lambda zi, zo: copy(zi, zo, patch=relist({
        'files/tf-install.env': sub(b'PROFILE=k1', b'PROFILE=m8')})), '机型不一致'),
] if b'"k1"' in ORIG['files/device-profile.json'] else []
if b'"yuanhang41s"' in ORIG['files/device-profile.json']:
    PROFILE_CASES.append(('41S profile/model mismatch with hashes recomputed', lambda zi, zo: copy(zi, zo, patch=relist({
        'files/device-profile.json': sub(b'ZTE 7546N', b'ZTE 9999'),
        'files/tf-install.env': sub(b'ZTE 7546N', b'ZTE 9999')})), '与机型规则要求的'))
if b'"changxing60"' in ORIG['files/device-profile.json']:
    PROFILE_CASES.append(('Changxing60 profile/model mismatch with hashes recomputed', lambda zi, zo: copy(zi, zo, patch=relist({
        'files/device-profile.json': sub(b'ZTE 7552N', b'ZTE 9999'),
        'files/tf-install.env': sub(b'ZTE 7552N', b'ZTE 9999')})), '与机型规则要求的'))
cases = PROFILE_CASES + [
    ('original-compatible prefix folder', lambda zi, zo: copy(zi, zo, rename=lambda n: 'e5-pkg/' + n), 'OK prefix=\'e5-pkg/\''),
    ('tampered installer script', lambda zi, zo: copy(zi, zo, patch={'files/android-tf.sh': lambda d: d + b'\n# x\n'}), '文件校验失败'),
    ('tampered firmware image', lambda zi, zo: copy(zi, zo, patch={'files/boot.img': lambda d: d[:-1] + b'\x01'}), '文件校验失败'),
    ('missing listed file', lambda zi, zo: copy(zi, zo, skip={'files/busybox'}), '安装包不完整'),
    ('unlisted extra installer file', lambda zi, zo: (copy(zi, zo), zo.writestr('files/evil.sh', b'echo hi\n')), '未被 SHA256SUMS 登记'),
    ('path traversal entry', lambda zi, zo: (copy(zi, zo), zo.writestr('../evil.sh', b'x')), '不安全'),
    ('two packages in one zip', lambda zi, zo: (copy(zi, zo, rename=lambda n: 'a/' + n), zo.writestr('b/files/SHA256SUMS', b'')), '多个安装包目录'),
    ('not an installer zip', lambda zi, zo: zo.writestr('readme.txt', b'hello'), '找不到 files/SHA256SUMS'),
]
ok = 0
for name, fn, want in cases:
    variant(fn)
    r = subprocess.run(['java', '-cp', cls, 'DesktopInspect', tmp, os.path.join(work, 'w')],
                       capture_output=True, text=True)
    last = [l for l in r.stdout.splitlines() if l.startswith(('OK', 'REFUSED'))]
    verdict = last[-1] if last else (r.stdout + r.stderr)[-300:]
    good = want in verdict
    ok += good
    print(('PASS' if good else 'FAIL') + f': {name}: {verdict[:160]}')
os.remove(tmp)
print(f'negative tests: {ok}/{len(cases)} passed')
sys.exit(0 if ok == len(cases) else 1)
