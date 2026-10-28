#!/bin/bash
# The release must preserve the official package/component namespace while updating the app version.
set -u
cd "$(dirname "$0")/.."
SDK=${ANDROID_HOME:-$HOME/android-sdk}
BT=$SDK/build-tools/34.0.0
V=$(sed -n 's/^VERSION_NAME=//p' version.properties)
C=$(sed -n 's/^VERSION_CODE=//p' version.properties)
APP_ID=top.linote.owrtinstaller
JAVA_NS=top.linote.owrtinstaller
pass=0; fail=0
t() { if eval "$2"; then pass=$((pass+1)); echo "ok   $1"; else fail=$((fail+1)); echo "FAIL $1"; fi; }
APK=dist/OpenWrt-Installer-Android-$V.apk
MANIFEST=app/src/main/AndroidManifest.xml
JOB=app/src/main/java/top/linote/owrtinstaller/JobManager.java

t "APK file name carries $V" '[ -f "$APK" ]'
t "manifest versionName=$V versionCode=$C" '"$BT/aapt2" dump badging "$APK" | head -1 | grep -q "versionCode='"'"'$C'"'"' versionName='"'"'$V'"'"'"'
t "APK package remains $APP_ID" '"$BT/aapt2" dump badging "$APK" | head -1 | grep -q "package: name=.$APP_ID."'
t "Manifest package remains $APP_ID" 'grep -Fq "package=\"$APP_ID\"" "$MANIFEST"'
t "existing relative activity/service declarations remain" 'grep -Fq "android:name=\".MainActivity\"" "$MANIFEST" && grep -Fq "android:name=\".AboutActivity\"" "$MANIFEST" && grep -Fq "android:name=\".CleanupActivity\"" "$MANIFEST" && grep -Fq "android:name=\".InstallService\"" "$MANIFEST"'
t "APK launcher maps to the existing MainActivity" '"$BT/aapt2" dump badging "$APK" | grep -q "launchable-activity: name=.$JAVA_NS.MainActivity."'
t "Manifest still has no FileProvider or content authority" '! grep -Eq "<provider|android:authorities" "$MANIFEST"'
t "USB permission action remains in the official package namespace" 'grep -Fq "top.linote.owrtinstaller.USB_PERMISSION" "$JOB"'
t "compiled BuildInfo.VERSION_NAME=$V" 'javap -constants -cp build/classes top.linote.owrtinstaller.BuildInfo | grep -q "VERSION_NAME = \"$V\""'
t "APK dex contains BuildInfo with $V" 'unzip -p "$APK" classes.dex | grep -aq "$V"'
t "header card and 使用说明 page read BuildInfo" 'grep -q "安卓版 v\" + BuildInfo.VERSION_NAME" app/src/main/java/top/linote/owrtinstaller/MainActivity.java && grep -q "return BuildInfo.VERSION_NAME" app/src/main/java/top/linote/owrtinstaller/AboutActivity.java'
t "no hard-coded app version in sources" '! grep -rnE "(VERSION|versionName)[A-Z_]* *= *\"[0-9]+\.[0-9]+" app/src/main/java'
t "build.sh has no literal version" '! grep -nE "VERSION_(NAME|CODE)=[\$]?\{?[A-Z_]*:-?[0-9]" build.sh'
t ".sha256 lists the bare release APK file name and matches" '(cd dist && grep -qx "[0-9a-f]\{64\}  OpenWrt-Installer-Android-$V.apk" "OpenWrt-Installer-Android-$V.apk.sha256" && sha256sum -c --quiet "OpenWrt-Installer-Android-$V.apk.sha256")'
t "README title identifies v$V" 'head -1 README.md | grep -q "v$V"'
t "README names release APK and versionCode $C" 'grep -q "OpenWrt-Installer-Android-$V.apk.*versionCode $C" README.md'
t "UFS support notes exist and match" '[ -f docs/ufs-support.md ] && head -1 docs/ufs-support.md | grep -q "v$V"'
echo "version/package check: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
