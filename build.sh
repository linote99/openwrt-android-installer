#!/usr/bin/env bash
# Build OpenWrt 安装器 APK without Gradle: aapt2 + javac + d8 + zipalign + apksigner.
# Needs JDK 17+ and an Android SDK with platforms;android-34 and build-tools;34.0.0.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/android-sdk}
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
VERSION_CODE=${VERSION_CODE:-4}
VERSION_NAME=${VERSION_NAME:-1.3.0}
OUT=build
APP=app/src/main
SIGN=signing

for t in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$JAR"; do
    [ -e "$t" ] || { echo "missing $t" >&2; exit 1; }
done

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" dist

# 1. Signing key: generated for this project only (never reuse other projects' keys).
if [ ! -f "$SIGN/release.keystore" ]; then
    mkdir -p "$SIGN"
    chmod 700 "$SIGN"
    pass=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
    keytool -genkeypair -keystore "$SIGN/release.keystore" -storetype PKCS12 -alias owrtinstaller \
        -keyalg RSA -keysize 3072 -validity 10000 -storepass "$pass" -keypass "$pass" \
        -dname "CN=OpenWrt Android Installer, O=linote, C=CN" >/dev/null
    printf 'storePassword=%s\nkeyAlias=owrtinstaller\n' "$pass" > "$SIGN/keystore.properties"
    chmod 600 "$SIGN/release.keystore" "$SIGN/keystore.properties"
    echo "generated new signing key in $SIGN/ (keep it to publish updates)"
fi
STOREPASS=$(sed -n 's/^storePassword=//p' "$SIGN/keystore.properties")

# 2. Resources + manifest.
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest "$APP/AndroidManifest.xml" \
    -A "$APP/assets" --java "$OUT/gen" --min-sdk-version 26 --target-sdk-version 34 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" "$OUT/res.zip"

# 3. Java -> dex.
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -classpath "$JAR" \
    -Xlint:-options -d "$OUT/classes" @"$OUT/sources.txt"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$OUT/dex" @"$OUT/classes.txt"

# 4. Package, align, sign, verify.
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X ../unsigned.apk classes.dex)
"$BT/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
NAME=dist/OpenWrt-Installer-Android-$VERSION_NAME.apk
"$BT/apksigner" sign --ks "$SIGN/release.keystore" --ks-key-alias owrtinstaller \
    --ks-pass "pass:$STOREPASS" --key-pass "pass:$STOREPASS" --out "$NAME" "$OUT/aligned.apk"
"$BT/apksigner" verify --verbose "$NAME" | head -6
sha256sum "$NAME" | tee "$NAME.sha256"
echo "built $NAME"
