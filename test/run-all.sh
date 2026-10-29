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

MUJ="$J/Mu300Internal.java $J/Mu300Gpt.java $J/Mu300Boot.java $J/Lz4.java $J/RootfsFilter.java"
AJ=$(ls -d "${ANDROID_HOME:-/workspace/.android-sdk}"/platforms/android-35/android.jar 2>/dev/null)
javac -encoding UTF-8 -nowarn -cp "$AJ" -sourcepath app/src/main/java:build/gen -d "$B/cls" $J/MiniJson.java $J/PackageInspector.java $MUJ test/DesktopInspect.java test/RuleTest.java test/Mu300FlowTest.java test/UfsGptTest.java 2>&1 | grep -v '^Note'
java -cp "$B/cls" top.linote.owrtinstaller.RuleTest > "$B/rule.txt" 2>&1; note "RuleTest rc=$? $(tail -1 "$B/rule.txt")"
mkdir -p "$B/mismatch"
javac -encoding UTF-8 -nowarn -cp "build/classes:$AJ" -d "$B/mismatch" test/ReadOnlyMismatchTest.java
java -cp "$B/mismatch:build/classes:$AJ" top.linote.owrtinstaller.ReadOnlyMismatchTest > "$B/mismatch.txt" 2>&1
note "ReadOnlyMismatchTest rc=$? $(tail -1 "$B/mismatch.txt")"
java -cp "$B/cls" top.linote.owrtinstaller.UfsGptTest > "$B/ufs-gpt.txt" 2>&1; note "UfsGptTest rc=$? $(tail -1 "$B/ufs-gpt.txt")"
bash test/version-check.sh > "$B/version.txt" 2>&1; note "version-check rc=$? $(tail -1 "$B/version.txt")"
bash test/ui-check.sh > "$B/ui.txt" 2>&1; note "ui-check rc=$? $(tail -1 "$B/ui.txt")"
# Android compatibility of the APK's own classes (build.sh output): APIs must exist at minSdk 26 and
# regex flags must be ones Android accepts (v1.4.0 crashed on Pattern.UNICODE_CHARACTER_CLASS).
API_XML=${ANDROID_HOME:-$HOME/android-sdk}/platforms/android-34/data/api-versions.xml
if [ -d build/classes ] && [ -f "$API_XML" ]; then
  python3 test/api-level-scan.py build/classes "$API_XML" 26 > "$B/apiscan.txt" 2>&1; note "android-compat-scan rc=$? $(tail -1 "$B/apiscan.txt")"
else
  note "android-compat-scan rc=1 missing build/classes (run ./build.sh first) or $API_XML"
fi

for z in "$Z"/*.zip; do
  [ -f "$z" ] || continue
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
# MU300 internal-storage port against a simulated device (package scripts are simulated, never executed)
for z in "$Z"/mu300-openwrt-*.zip; do
  [ -f "$z" ] || continue
  java -Xmx3g -cp "$B/cls" top.linote.owrtinstaller.Mu300FlowTest "$z" "$B/muflow" > "$B/muflow-$(basename "$z" .zip).txt" 2>&1
  note "Mu300FlowTest rc=$? $(basename "$z") :: $(tail -1 "$B/muflow-$(basename "$z" .zip).txt")"
done
rm -rf "$B/muflow"
# v1.5.0: E5-series "format userdata" two-stage install, resume and rewrite of e5root (simulated device)
AJ=$(ls -d "${ANDROID_HOME:-/workspace/.android-sdk}"/platforms/android-35/android.jar 2>/dev/null)
if [ -f build/gen/top/linote/owrtinstaller/BuildInfo.java ] && [ -n "$AJ" ]; then
  mkdir -p "$B/e5p"
  javac -encoding UTF-8 -nowarn -cp "$AJ" -sourcepath app/src/main/java:build/gen -d "$B/e5p" test/E5PartTest.java 2>&1 | grep -v '^Note'
  java -cp "$B/e5p:$AJ" top.linote.owrtinstaller.E5PartTest "$B/e5part-work" > "$B/e5part.txt" 2>&1; note "E5PartTest rc=$? $(tail -1 "$B/e5part.txt")"
else
  note "E5PartTest rc=1 FAIL: run ./build.sh first (needs build/gen and android.jar)"
fi
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
# v1.4.3: stale packets at the USB handshake, a device that never confirms sync DONE, shell silence limits
javac -encoding UTF-8 -nowarn -d "$B/stall" $(find test/usbstub -name '*.java') $J/AdbConnection.java $J/AdbKey.java $J/UsbAdbTransport.java test/AdbStallTest.java 2>&1 | grep -v '^Note'
mkdir -p "$B/stall-r1" "$B/stall-r2" "$B/stall-r3" "$B/stall-work"
MOCK_MODE=stale-before python3 test/mock-adbd.py 15561 "$B/stall-r1" > "$B/adbd-stall1.log" 2>&1 & S1=$!
MOCK_MODE=stale-after python3 test/mock-adbd.py 15562 "$B/stall-r2" > "$B/adbd-stall2.log" 2>&1 & S2=$!
MOCK_MODE=hang-done python3 test/mock-adbd.py 15563 "$B/stall-r3" > "$B/adbd-stall3.log" 2>&1 & S3=$!
sleep 1.5
timeout 120 java -cp "$B/stall" top.linote.owrtinstaller.AdbStallTest 15561 15562 15563 "$B/stall-work" > "$B/stall.txt" 2>&1; note "AdbStallTest rc=$? $(tail -1 "$B/stall.txt")"
kill $S1 $S2 $S3 2>/dev/null
# v1.5.1: E5-family and MU300 backups/reads of large partitions over USB (real AdbConnection + UsbAdbTransport)
if [ -n "$AJ" ] && [ -f build/gen/top/linote/owrtinstaller/BuildInfo.java ]; then
  mkdir -p "$B/ub-stub" "$B/ub-main" "$B/ub-cls" "$B/ub-root" "$B/ub-work"
  javac -encoding UTF-8 -nowarn -cp "$AJ" -sourcepath app/src/main/java:build/gen -d "$B/ub-main" $J/E5Part.java $J/Mu300Internal.java $J/RootShell.java 2>&1 | grep -v '^Note'
  javac -encoding UTF-8 -nowarn -d "$B/ub-stub" $(find test/usbstub -name '*.java') 2>&1 | grep -v '^Note'
  javac -encoding UTF-8 -nowarn -cp "$B/ub-stub:$B/ub-main:$AJ" -d "$B/ub-cls" $J/AdbConnection.java $J/AdbKey.java $J/UsbAdbTransport.java test/UsbBackupTest.java 2>&1 | grep -v '^Note'
  python3 test/mock-adbd.py 15570 "$B/ub-root" > "$B/adbd-ub.log" 2>&1 & UB=$!
  sleep 1.5
  timeout 300 java -cp "$B/ub-cls:$B/ub-stub:$B/ub-main:$AJ" top.linote.owrtinstaller.UsbBackupTest 15570 "$B/ub-work" "$B/ub-root" > "$B/usbbackup.txt" 2>&1; note "UsbBackupTest rc=$? $(tail -1 "$B/usbbackup.txt")"
  kill $UB 2>/dev/null
  rm -rf "$B/ub-root"
else
  note "UsbBackupTest rc=1 FAIL: run ./build.sh first (needs build/gen and android.jar)"
fi

if [ -n "$AJ" ] && [ -f build/gen/top/linote/owrtinstaller/BuildInfo.java ]; then
  rm -rf "$B/unt" "$B/unt-work"; mkdir -p "$B/unt"
  javac -encoding UTF-8 -nowarn -cp "$AJ" -sourcepath app/src/main/java:build/gen -d "$B/unt" test/UninstallTest.java 2>&1 | grep -v '^Note'
  E5Z=$(ls "$Z"/e5-openwrt-*.zip 2>/dev/null | head -1); MUZ=$(ls "$Z"/mu300-openwrt-*.zip 2>/dev/null | head -1)
  if [ -n "$E5Z" ] && [ -n "$MUZ" ]; then
    timeout 600 java -Xmx2g -cp "$B/unt:$AJ" top.linote.owrtinstaller.UninstallTest "$B/unt-work" "$E5Z" "$MUZ" > "$B/uninstall.txt" 2>&1
    note "UninstallTest rc=$? $(tail -1 "$B/uninstall.txt")"
  else
    note "UninstallTest skipped: provide both E5 and MU300 installer ZIPs"
  fi
  rm -rf "$B/recovery" "$B/recovery-work"; mkdir -p "$B/recovery"
  javac -encoding UTF-8 -nowarn -cp "$AJ" -sourcepath app/src/main/java:build/gen -d "$B/recovery" test/Mu300Ext4RecoveryTest.java 2>&1 | grep -v '^Note'
  java -cp "$B/recovery:$AJ" top.linote.owrtinstaller.Mu300Ext4RecoveryTest "$B/recovery-work" > "$B/ext4-recovery.txt" 2>&1
  note "Mu300Ext4RecoveryTest rc=$? $(tail -1 "$B/ext4-recovery.txt")"
else
  note "UninstallTest/Mu300Ext4RecoveryTest rc=1 FAIL: run ./build.sh first (needs build/gen and android.jar)"
fi
# command-level uninstall lifecycle against a mock target (both families)
if [ -n "$AJ" ] && [ -f build/gen/top/linote/owrtinstaller/BuildInfo.java ]; then
  rm -rf "$B/unf" "$B/unf-work"; mkdir -p "$B/unf"
  javac -encoding UTF-8 -nowarn -cp "$AJ" -sourcepath app/src/main/java:build/gen -d "$B/unf" test/UninstallFlowTest.java 2>&1 | grep -v '^Note'
  if [ -n "$E5Z" ] && [ -n "$MUZ" ]; then
    timeout 600 java -Xmx2g -cp "$B/unf:$AJ" top.linote.owrtinstaller.UninstallFlowTest "$B/unf-work" "$E5Z" "$MUZ" > "$B/uninstallflow.txt" 2>&1
    note "UninstallFlowTest rc=$? $(tail -1 "$B/uninstallflow.txt")"
  else
    note "UninstallFlowTest skipped: provide both E5 and MU300 installer ZIPs"
  fi
else
  note "UninstallFlowTest rc=1 FAIL: run ./build.sh first (needs build/gen and android.jar)"
fi
# readiness ladder + rebind (pure logic) and the debugging-authorisation wait (mock adbd)
if [ -d build/gen ] && [ -f "$AJ" ]; then
  javac -encoding UTF-8 -nowarn -cp "$AJ" -sourcepath app/src/main/java:build/gen -d "$B/tp" test/TargetProbeTest.java 2>&1 | grep -v '^Note'
  java -cp "$B/tp:$AJ" top.linote.owrtinstaller.TargetProbeTest > "$B/targetprobe.txt" 2>&1; note "TargetProbeTest rc=$? $(tail -1 "$B/targetprobe.txt")"
  javac -encoding UTF-8 -nowarn -cp "$AJ" -sourcepath app/src/main/java:build/gen -d "$B/ufi" test/UfiToolTest.java 2>&1 | grep -v '^Note'
  java -cp "$B/ufi:$AJ" top.linote.owrtinstaller.UfiToolTest > "$B/ufitool.txt" 2>&1; note "UfiToolTest rc=$? $(tail -1 "$B/ufitool.txt")"
else
  note "TargetProbeTest rc=1 FAIL: run ./build.sh first (needs build/gen and android.jar)"
fi
javac -encoding UTF-8 -nowarn -d "$B/aw" $J/AdbConnection.java $J/AdbKey.java test/AuthWaitTest.java 2>&1 | grep -v '^Note'
mkdir -p "$B/aw-r1" "$B/aw-r2" "$B/aw-work"
MOCK_MODE=auth-slow python3 test/mock-adbd.py 15571 "$B/aw-r1" > "$B/adbd-aw1.log" 2>&1 & W1=$!
MOCK_MODE=auth-wait python3 test/mock-adbd.py 15572 "$B/aw-r2" > "$B/adbd-aw2.log" 2>&1 & W2=$!
sleep 1.5
timeout 60 java -cp "$B/aw" top.linote.owrtinstaller.AuthWaitTest slow 15571 "$B/aw-work" > "$B/aw1.txt" 2>&1; note "AuthWaitTest(allow later) rc=$? $(tail -1 "$B/aw1.txt")"
timeout 60 java -cp "$B/aw" top.linote.owrtinstaller.AuthWaitTest wait 15572 "$B/aw-work" > "$B/aw2.txt" 2>&1; note "AuthWaitTest(stop) rc=$? $(tail -1 "$B/aw2.txt")"
kill $W1 $W2 2>/dev/null; wait $W1 $W2 2>/dev/null
for t in mock-helper-test.sh mock-userdata-test.sh mock-mu300-test.sh mock-perm-test.sh; do
  sudo bash "test/$t" > "$B/$t.txt" 2>&1; note "$t rc=$? $(tail -1 "$B/$t.txt")"
done
grep -h '^FAIL' "$B"/*.txt | head -20
echo; printf '%s\n' "${summary[@]}"
