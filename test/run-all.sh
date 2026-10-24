#!/bin/bash
# Runs every desktop test. Usage: bash test/run-all.sh <dir-with-installer-zips>
# The installer ZIPs are only read and unpacked (checksums); nothing inside them is executed.
set -u
cd "$(dirname "$0")/.."
Z=${1:?usage: run-all.sh <dir with installer zips>}
J=app/src/main/java/top/linote/owrtinstaller
B=build-test
sudo rm -rf "$B"; mkdir -p "$B/cls" "$B/adb" "$B/usb"
summary=()
note() { summary+=("$1"); echo "== $1"; }

javac -encoding UTF-8 -nowarn -d "$B/cls" $J/MiniJson.java $J/PackageInspector.java test/DesktopInspect.java test/RuleTest.java 2>&1 | grep -v '^Note'
java -cp "$B/cls" top.linote.owrtinstaller.RuleTest > "$B/rule.txt" 2>&1; note "RuleTest rc=$? $(tail -1 "$B/rule.txt")"

for z in "$Z"/*.zip; do
  n=$(basename "$z")
  rm -rf "$B/work"; mkdir -p "$B/work"
  java -cp "$B/cls" DesktopInspect "$z" "$B/work" > "$B/inspect.txt" 2>&1
  note "inspect rc=$? $n :: $(grep -m1 -E '机型|model' "$B/inspect.txt" | cut -c1-80) :: $(tail -1 "$B/inspect.txt" | cut -c1-100)"
done

E5=$(ls "$Z"/e5-openwrt-*.zip 2>/dev/null | head -1)
MU=$(ls "$Z"/mu300-openwrt-*.zip 2>/dev/null | head -1)
K1=$(ls "$Z"/k1-openwrt-*.zip 2>/dev/null | head -1)
[ -n "$K1" ] && { python3 test/negative-package-test.py "$K1" "$B/cls" "$B/neg-k1" > "$B/negk1.txt" 2>&1; note "negative-package(K1, model rules) rc=$? $(tail -1 "$B/negk1.txt")"; }
[ -n "$E5" ] && { python3 test/negative-package-test.py "$E5" "$B/cls" "$B/neg" > "$B/neg.txt" 2>&1; note "negative-package rc=$? $(tail -1 "$B/neg.txt")"; }
[ -n "$MU" ] && { python3 test/negative-mu300-test.py "$MU" "$B/cls" "$B/neg-mu" > "$B/negmu.txt" 2>&1; note "negative-mu300 rc=$? $(tail -1 "$B/negmu.txt")"; }

javac -encoding UTF-8 -nowarn -d "$B/adb" $J/AdbConnection.java $J/AdbKey.java test/AdbTest.java 2>&1 | grep -v '^Note'
mkdir -p "$B/adb-root" "$B/adb-work"
python3 test/mock-adbd.py 15555 "$B/adb-root" > "$B/adbd.log" 2>&1 & AP=$!
sleep 1.5
timeout 120 java -cp "$B/adb" top.linote.owrtinstaller.AdbTest 15555 "$B/adb-work" "$B/adb-root" > "$B/adb.txt" 2>&1; note "AdbTest(TCP) rc=$? $(tail -1 "$B/adb.txt")"
kill $AP 2>/dev/null

javac -encoding UTF-8 -nowarn -d "$B/usb" $(find test/usbstub -name '*.java') $J/AdbConnection.java $J/AdbKey.java $J/UsbAdbTransport.java test/UsbAdbTest.java 2>&1 | grep -v '^Note'
mkdir -p "$B/usb-root" "$B/usb-work"
python3 test/mock-adbd.py 15556 "$B/usb-root" > "$B/adbd-usb.log" 2>&1 & UP=$!
sleep 1.5
timeout 120 java -cp "$B/usb" top.linote.owrtinstaller.UsbAdbTest 15556 "$B/usb-work" "$B/usb-root" > "$B/usbt.txt" 2>&1; note "UsbAdbTest rc=$? $(tail -1 "$B/usbt.txt")"
kill $UP 2>/dev/null

for t in mock-helper-test.sh mock-userdata-test.sh mock-mu300-test.sh; do
  sudo bash "test/$t" > "$B/$t.txt" 2>&1; note "$t rc=$? $(tail -1 "$B/$t.txt")"
done
grep -h '^FAIL' "$B"/*.txt | head -20
echo; printf '%s\n' "${summary[@]}"
