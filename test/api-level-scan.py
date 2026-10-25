#!/usr/bin/env python3
"""Static Android-compatibility check of the compiled app.
1. Every framework/JDK member it references must exist at the app's minSdk (api-versions.xml).
2. Pattern.compile flags must be ones Android's regex engine accepts (it throws
   IllegalArgumentException("Unsupported flags") for UNICODE_CHARACTER_CLASS / CANON_EQ,
   which a desktop JVM accepts - the v1.4.0 crash).
Usage: api-level-scan.py <classes-dir> <api-versions.xml> <minSdk>"""
import re, subprocess, sys, xml.etree.ElementTree as ET, pathlib
cls_dir, api_xml, min_api = sys.argv[1], sys.argv[2], int(sys.argv[3])
root = ET.parse(api_xml).getroot()
classes = {}
for c in root.findall('class'):
    name = c.get('name'); since = int(c.get('since', '1'))
    members = {}
    for m in c:
        if m.tag in ('method', 'field'):
            members[m.get('name')] = int(m.get('since', str(since)))
    supers = [e.get('name') for e in c if e.tag in ('extends', 'implements')]
    classes[name] = (since, members, supers)
def lookup(cls, member, seen=None):
    seen = seen or set()
    if cls in seen or cls not in classes: return None
    seen.add(cls)
    since, members, supers = classes[cls]
    if member in members: return max(since, members[member])
    for s in supers:
        r = lookup(s, member, seen)
        if r is not None: return r
    return None
files = [str(p) for p in pathlib.Path(cls_dir).rglob('*.class')]
out = subprocess.run(['javap', '-c', '-p'] + files, capture_output=True, text=True).stdout
ref = re.compile(r'// (Method|InterfaceMethod|Field) ([\w/$]+)\.("?[<\w>$]+"?):(\S+)')
ANDROID_FLAGS = 1 | 2 | 4 | 8 | 16 | 32 | 64   # UNIX_LINES CASE_INSENSITIVE COMMENTS MULTILINE LITERAL DOTALL UNICODE_CASE
const = re.compile(r'\b(?:iconst_(m1|\d)|bipush\s+(-?\d+)|sipush\s+(-?\d+)|ldc(?:_w)?\s+#\d+\s+// int (-?\d+))\b')
problems = set(); cur = None; last_int = None; flag_calls = 0
for line in out.splitlines():
    if not line.startswith(' ') and line.rstrip().endswith('{'):
        cur = line.strip().rstrip('{').strip()
    c = const.search(line)
    if c:
        g = c.groups(); v = g[0] if g[0] is not None else (g[1] or g[2] or g[3])
        last_int = -1 if v == 'm1' else int(v)
    if 'java/util/regex/Pattern.compile:(Ljava/lang/String;I)' in line:
        flag_calls += 1
        if last_int is None or last_int & ~ANDROID_FLAGS:
            problems.add(('java/util/regex/Pattern', 'compile flags=%s in %s' % (last_int, cur), 'unsupported on Android'))
    m = ref.search(line)
    if not m: continue
    kind, owner, name, desc = m.groups()
    if not (owner.startswith('java/') or owner.startswith('android/') or owner.startswith('javax/')): continue
    name = name.strip('"')
    key = name + (desc if kind != 'Field' else '')
    if owner not in classes:
        problems.add((owner, key, 'class missing')); continue
    lvl = lookup(owner, key)
    if lvl is None:
        problems.add((owner, key, 'member not in api-versions')); continue
    if lvl > min_api: problems.add((owner, key, 'since %d' % lvl))
for o, k, w in sorted(problems): print('API>%d  %s.%s  (%s)' % (min_api, o, k, w))
print('scan done: %d problem(s); %d Pattern.compile(flags) call(s) checked' % (len(problems), flag_calls))
sys.exit(1 if problems else 0)
