#!/usr/bin/env bash
# Mock test of the APK's own mu300-lib.sh + owrt-apk.sh MU300 path on a Linux PC.
# Uses a FAKE MU300-layout package written here (fake worker/locale/customize);
# never runs anything from a real package. Android paths are rewritten for the host.
set -u
HERE=$(cd "$(dirname "$0")/.." && pwd)
T=${1:-$HERE/build-test/mock-mu300}
rm -rf "$T"; mkdir -p "$T/app/files" "$T/base" "$T/dev" "$T/bin" "$T/tmp"
A=$HERE/app/src/main/assets
fails=0
ok()   { echo "PASS: $1"; }
bad()  { echo "FAIL: $1"; fails=$((fails+1)); }
# Fake block devices (the test only checks -b), getprop stub.
mknod "$T/dev/boot_b" b 7 200 2>/dev/null; mknod "$T/dev/mmcblk1p1" b 7 201 2>/dev/null
cat > "$T/bin/getprop" <<G
#!/bin/sh
case \$1 in ro.boot.slot_suffix) echo _a ;; persist.sys.locale) echo zh-CN ;; *) echo ;; esac
G
chmod 755 "$T/bin/getprop"; export PATH="$T/bin:$PATH"
sed -e "s|^BASE=/data/adb/owrt-apk|BASE=$T/base|" \
    -e "s|^REMOTE_STAGE=/data/local/tmp/owrt-apk-stage|REMOTE_STAGE=$T/remote|" \
    -e "s|/system/bin/sh |/bin/sh |g" \
    -e "s|\[ \"\$(uname -m)\" = aarch64 \]|true|" \
    -e "s|\"\$BB\" nsenter -t 1 -m ||" \
    -e "s|ASH_STANDALONE=1 \"\$BB\" sh -c 'PATH=/nonexistent; sha256sum|true \|\| \"\$BB\" sh -c 'PATH=/nonexistent; sha256sum|" \
    -e "s|/data/user/\[0-9\]\*/\"\$APP_ID\"/files/stage/pkg|$T/app/files/stage/pkg|" \
    "$A/owrt-apk.sh" > "$T/app/files/owrt-apk.sh"
sed -e "s|/data/adb/owrt-apk/|$T/base/|" "$A/check-lib.sh" > "$T/app/files/check-lib.sh"
cp "$A/userdata-lib.sh" "$T/app/files/userdata-lib.sh"
sed -e "s|^T=/data/local/tmp/mu300-tf-install|T=$T/tmp|" \
    -e "s|/dev/block/by-name/boot_|$T/dev/boot_|g" \
    -e "s|/dev/block/mmcblk1p1|$T/dev/mmcblk1p1|g; s|/dev/block/mmcblk1\b|$T/dev/mmcblk1|g" \
    -e "s|\"\$BB\" nsenter -t 1 -m ||" \
    -e "s|\"\$BB\" blockdev --getsize64 \"\$R\"|echo 31914983424|; s|\"\$BB\" blockdev --getsize64 \"\$card\"|echo 31914983424|" \
    "$A/mu300-lib.sh" > "$T/app/files/mu300-lib.sh"
make_pkg() {  # $1 = worker exit code
    S=$T/app/files/stage/pkg; rm -rf "$S"; mkdir -p "$S/payload" "$S/desktop"
    cp "$(command -v busybox)" "$S/payload/busybox"
    echo mu300 > "$S/OWRT-APK-LAYOUT"
    printf 'mu300_install_lang() { echo zh; }\n' > "$S/payload/locale.sh"
    printf '#!/system/bin/sh\nKERNEL=7.2\n' > "$S/payload/customize.sh"
    echo rootfs > "$S/payload/mu300-openwrt.tar.gz"; echo boot > "$S/payload/boot-linux.img"
    cat > "$S/payload/tf-install-worker.sh" <<W
set -eu
: "\${MU300_PAYLOAD_DIR:?}" "\${MU300_INSTALL_TMP:?}" "\${MU300_BUSYBOX:?}" "\${MU300_TF_BOOTDEV:?}"
[ "\$MU300_KEEP_PAYLOAD" = 1 ] && [ "\$MU300_VENDOR_FROM_DEVICE" = 1 ]
. "\$MU300_PAYLOAD_DIR/mu300-install.env"
echo "worker args=\${1:-full} SD_DEV=\$SD_DEV KERNEL=\$KERNEL BOOTDEV=\$MU300_TF_BOOTDEV TFBIN=\${MU300_TF_BIN:-none}"
[ "\${1:-}" = --preflight ] && { echo '[TF] capacity: target=1 sectors'; exit 0; }
echo '热点：已导入 MU300-OpenWrt'
exit $1
W
    (cd "$S" && sha256sum payload/* > OWRT-APK-SUMS)
    { echo '{"magisk": {'; sed 's/^\([0-9a-f]*\)  \(.*\)$/"\2": "\1",/' "$S/OWRT-APK-SUMS"; echo '"x": "y"}}'; } > "$S/desktop/manifest.json"
}
H=$T/app/files/owrt-apk.sh
make_pkg 0
out=$(sh "$H" stage "$T/app/files/stage/pkg" 2>&1); echo "$out" | grep -q 'OWRT-APK-STAGED=[0-9a-f]\{64\}' && ok stage || { bad stage; echo "$out"; }
[ -f "$T/base/mu300-lib.sh" ] && ok 'helper installed' || bad 'helper installed'
out=$(sh "$H" check 2>&1)
echo "$out" | grep -q 'worker args=--preflight SD_DEV=.*mmcblk1p1 KERNEL=7.2 BOOTDEV=.*boot_b TFBIN=.*apk-check-bin' && ok 'preflight args' || { bad 'preflight args'; echo "$out"; }
echo "$out" | grep -q 'OWRT-APK-SLOTS=a,b' && echo "$out" | grep -q 'OWRT-APK-CHECK-OK' && echo "$out" | grep -q 'OWRT-APK-CHECK-RC=0' && ok 'check markers' || { bad 'check markers'; echo "$out"; }
ls "$T/tmp" | grep -q apk-check-bin && bad 'check bin cleaned' || ok 'check bin cleaned'
out=$(sh "$H" check-data part 2>&1); echo "$out" | grep -q '只支持 TF' && ok 'part refused' || { bad 'part refused'; echo "$out"; }
out=$(sh "$H" start part 4096 1 2>&1); echo "$out" | grep -q '只支持 TF' && ok 'start part refused' || { bad 'start part refused'; echo "$out"; }
sh "$H" start tf >/dev/null 2>&1; sleep 3
st=$(sh "$H" status 2>&1)
log=$(cat "$T/base/job/log" 2>/dev/null)
echo "$log" | grep -q 'worker args=full' && echo "$log" | grep -q 'OWRT-APK-INSTALL-OK' && echo "$log" | grep -q '热点：已导入 MU300-OpenWrt' && ok 'install run' || { bad 'install run'; echo "$log"; echo "$st"; }
[ ! -f "$T/base/pkg/payload/boot-linux.img" ] && ok 'payload images removed after success' || bad 'payload images removed'
# failure path
make_pkg 7; sh "$H" stage "$T/app/files/stage/pkg" >/dev/null 2>&1; sh "$H" start tf >/dev/null 2>&1; sleep 3
log=$(cat "$T/base/job/log" 2>/dev/null)
echo "$log" | grep -q 'OWRT-APK-INSTALL-FAILED rc=7' && ok 'failure reported' || { bad 'failure reported'; echo "$log"; }
[ -f "$T/base/pkg/payload/boot-linux.img" ] && ok 'payload kept after failure' || bad 'payload kept after failure'
# tampered subset list
make_pkg 0; echo 'x' >> "$T/app/files/stage/pkg/payload/locale.sh"
out=$(sh "$H" stage "$T/app/files/stage/pkg" 2>&1); echo "$out" | grep -q '校验失败\|mismatch' && ok 'tampered refused' || { bad 'tampered refused'; echo "$out"; }
[ $fails = 0 ] && echo 'mu300 mock: all passed' || echo "mu300 mock: $fails failed"
