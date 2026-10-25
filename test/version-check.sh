#!/bin/bash
# The app version must be the same everywhere: version.properties (the only source), the APK file
# name, the manifest versionName/versionCode, the compiled BuildInfo used by the header card and the
# 使用说明 page, the .sha256 file, the README title and the release notes. Run after ./build.sh.
set -u
cd "$(dirname "$0")/.."
SDK=${ANDROID_HOME:-$HOME/android-sdk}
BT=$SDK/build-tools/34.0.0
V=$(sed -n 's/^VERSION_NAME=//p' version.properties)
C=$(sed -n 's/^VERSION_CODE=//p' version.properties)
pass=0; fail=0
t() { if eval "$2"; then pass=$((pass+1)); echo "ok   $1"; else fail=$((fail+1)); echo "FAIL $1"; fi; }
APK=dist/OpenWrt-Installer-Android-$V.apk
t "APK file name carries $V" '[ -f "$APK" ]'
t "manifest versionName=$V versionCode=$C" '"$BT/aapt2" dump badging "$APK" | head -1 | grep -q "versionCode='"'"'$C'"'"' versionName='"'"'$V'"'"'"'
t "compiled BuildInfo.VERSION_NAME=$V" 'javap -constants -cp build/classes top.linote.owrtinstaller.BuildInfo | grep -q "VERSION_NAME = \"$V\""'
t "APK dex contains BuildInfo with $V" 'unzip -p "$APK" classes.dex | grep -aq "$V"'
t "header card and 使用说明 page read BuildInfo" 'grep -q "安卓版 v\" + BuildInfo.VERSION_NAME" app/src/main/java/top/linote/owrtinstaller/MainActivity.java && grep -q "return BuildInfo.VERSION_NAME" app/src/main/java/top/linote/owrtinstaller/AboutActivity.java'
t "no hard-coded app version in sources" '! grep -rnE "(VERSION|versionName)[A-Z_]* *= *\"[0-9]+\.[0-9]+" app/src/main/java'
t "build.sh has no literal version" '! grep -nE "VERSION_(NAME|CODE)=[\$]?\{?[A-Z_]*:-?[0-9]" build.sh'
t ".sha256 lists the bare file name and matches" '(cd dist && grep -qx "[0-9a-f]\{64\}  OpenWrt-Installer-Android-$V.apk" "OpenWrt-Installer-Android-$V.apk.sha256" && sha256sum -c --quiet "OpenWrt-Installer-Android-$V.apk.sha256")'
t "README title is v$V" 'head -1 README.md | grep -q "v$V\$"'
t "README APK line names $V / versionCode $C" 'grep -q "OpenWrt-Installer-Android-$V.apk.*versionCode $C" README.md'
t "release notes for $V exist and match" '[ -f dist/release-notes-$V.md ] && head -1 dist/release-notes-$V.md | grep -q "v$V\$"'
echo "version check: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
