#!/bin/bash
# The release must preserve the official package/component namespace. Source-only candidates
# intentionally skip APK inspection when no signed binary is present.
set -u
cd "$(dirname "$0")/.."
SDK=${ANDROID_HOME:-$HOME/android-sdk}
BT=$SDK/build-tools/34.0.0
V=$(sed -n 's/^VERSION_NAME=//p' version.properties)
C=$(sed -n 's/^VERSION_CODE=//p' version.properties)
APP_ID=top.linote.owrtinstaller
JAVA_NS=top.linote.owrtinstaller
JOB=app/src/main/java/top/linote/owrtinstaller/JobManager.java
APK=dist/OpenWrt-Installer-Android-$V.apk
MANIFEST=app/src/main/AndroidManifest.xml
pass=0; fail=0
t() { if eval "$2"; then pass=$((pass+1)); echo "ok   $1"; else fail=$((fail+1)); echo "FAIL $1"; fi; }

t "source release is v1.7.6/versionCode 28" '[ "$V" = "1.7.6" ] && [ "$C" = "28" ]'
t "Manifest package remains $APP_ID" 'grep -Fq "package=\"$APP_ID\"" "$MANIFEST"'
t "existing and planner Activity/service declarations remain internal" 'grep -Fq "android:name=\".MainActivity\"" "$MANIFEST" && grep -Fq "android:name=\".AboutActivity\"" "$MANIFEST" && grep -Fq "android:name=\".CleanupActivity\"" "$MANIFEST" && grep -Fq "android:name=\".PartitionPlannerActivity\"" "$MANIFEST" && grep -Fq "android:name=\".InstallService\"" "$MANIFEST"'
t "planner Activity is not exported" 'grep -A2 "android:name=\".PartitionPlannerActivity\"" "$MANIFEST" | grep -q "android:exported=\"false\""'
t "Manifest still has no FileProvider or content authority" '! grep -Eq "<provider|android:authorities" "$MANIFEST"'
t "USB permission action remains in the official package namespace" 'grep -Fq "top.linote.owrtinstaller.USB_PERMISSION" "$JOB"'
if [ -d build/classes ]; then
  t "compiled BuildInfo.VERSION_NAME=$V" 'javap -constants -cp build/classes top.linote.owrtinstaller.BuildInfo | grep -q "VERSION_NAME = \"$V\""'
else
  echo "skip compiled BuildInfo check (source-only; no build/classes)"
fi
t "header card and 使用说明 page read BuildInfo" 'grep -q "安卓版 v\" + BuildInfo.VERSION_NAME" app/src/main/java/top/linote/owrtinstaller/MainActivity.java && grep -q "return BuildInfo.VERSION_NAME" app/src/main/java/top/linote/owrtinstaller/AboutActivity.java'
t "no hard-coded app version in sources" '! grep -rnE "(VERSION|versionName)[A-Z_]* *= *\"[0-9]+\.[0-9]+\.[0-9]+" app/src/main/java'
t "build.sh has no literal version" '! grep -nE "VERSION_(NAME|CODE)=[\$]?\{?[A-Z_]*:-?[0-9]" build.sh'
t "README title identifies v$V" 'head -1 README.md | grep -q "v$V"'
t "README links the published APK and source ZIP" 'grep -q "OpenWrt-Installer-Android-$V.apk.*versionCode $C" README.md && grep -q "releases/download/v$V/OpenWrt-Installer-Android-$V.apk" README.md && grep -q "releases/download/v$V/OpenWrt-Installer-Android-$V-source.zip" README.md'
t "README documents the published signer and uninstall requirement" 'grep -q "证书 SHA-256 指纹" README.md && grep -q "卸载旧版后安装 v$V" README.md'
t "UFS support notes identify v$V release source" '[ -f docs/ufs-support.md ] && head -1 docs/ufs-support.md | grep -q "v$V"'

if [ -f "$APK" ] && [ -x "$BT/aapt2" ]; then
  t "APK file name carries $V" '[ -f "$APK" ]'
  t "manifest versionName=$V versionCode=$C" '"$BT/aapt2" dump badging "$APK" | head -1 | grep -q "versionCode='"'"'$C'"'"' versionName='"'"'$V'"'"'"'
  t "APK package remains $APP_ID" '"$BT/aapt2" dump badging "$APK" | head -1 | grep -q "package: name=.$APP_ID."'
  t "APK launcher maps to the existing MainActivity" '"$BT/aapt2" dump badging "$APK" | grep -q "launchable-activity: name=.$JAVA_NS.MainActivity."'
  t "APK dex contains BuildInfo with $V" 'unzip -p "$APK" classes.dex | grep -aq "$V"'
  t ".sha256 lists the release APK and matches" '(cd dist && grep -qx "[0-9a-f]\{64\}  OpenWrt-Installer-Android-'"$V"'.apk" "OpenWrt-Installer-Android-'"$V"'.apk.sha256" && sha256sum -c --quiet "OpenWrt-Installer-Android-'"$V"'.apk.sha256")'
else
  echo "skip APK/aapt2/signature/hash checks (no APK or Android build-tools; source candidate only)"
fi

echo "version/package source check: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
