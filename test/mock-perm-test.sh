#!/usr/bin/env bash
# v1.6.3 regression test: the system the APK installs must have the same directory modes as one
# installed by the original package (Magisk / desktop su run its scripts with umask 022).
# v1.6.2 ran the package scripts under owrt-apk.sh's global "umask 077": the vendor staging that
# tf-install-worker.sh merges with "cp -a staging/. newroot/" made /, /lib, /opt and
# /opt/mu300/android/vendor 0700, so the unprivileged modem_control could not read the vendor
# configuration (modem state 255, WAN never up). The E5 family's firmware.tar / device-files.tar had
# the same problem for /lib and /etc.
#
# FAKE packages written here imitate, step by step, what the real package scripts do with
# directories (mkdir -p / tar -x of implicit parents / cp -a / tar -c), without setting a umask where
# the real scripts do not set one; nothing from a real installer package is executed.
# Usage: sudo bash test/mock-perm-test.sh [workdir]   (OWRT_ASSETS=dir to test other helper scripts)
set -u
HERE=$(cd "$(dirname "$0")/.." && pwd)
T=${1:-$HERE/build-test/mock-perm}
A=${OWRT_ASSETS:-$HERE/app/src/main/assets}
[ "$(id -u)" = 0 ] || { echo 'FAIL: run as root'; exit 1; }
rm -rf "$T"; mkdir -p "$T"
pass=0; fail=0
ok()  { echo "PASS: $1"; pass=$((pass+1)); }
bad() { echo "FAIL: $1"; fail=$((fail+1)); }
mode_is() {  # $1 = expected octal mode, $2 = label, $3... = paths
    want=$1; label=$2; shift 2
    for f in "$@"; do
        got=$(stat -c %a "$f" 2>/dev/null || echo missing)
        [ "$got" = "$want" ] && ok "$label: ${f#$T/} is $want" || bad "$label: ${f#$T/} is $got, want $want"
    done
}
# Whatever umask the caller (the app process / su) has, the helper must not leak it.
umask 077

# The helper scripts, rewritten for the host exactly like the other mock tests do.
helpers() {  # $1 = scratch dir
    local H=$1
    mkdir -p "$H/app/files" "$H/base" "$H/bin"
    sed -e "s|^BASE=/data/adb/owrt-apk|BASE=$H/base|" \
        -e "s|^REMOTE_STAGE=/data/local/tmp/owrt-apk-stage|REMOTE_STAGE=$H/remote|" \
        -e "s|/system/bin/sh |/bin/sh |g" \
        -e "s|\[ \"\$(uname -m)\" = aarch64 \]|true|" \
        -e "s|\"\$BB\" nsenter -t 1 -m ||" \
        -e "s|ASH_STANDALONE=1 \"\$BB\" sh -c 'PATH=/nonexistent; sha256sum|true \|\| \"\$BB\" sh -c 'PATH=/nonexistent; sha256sum|" \
        -e "s|/data/user/\[0-9\]\*/\"\$APP_ID\"/files/stage/pkg|$H/app/files/stage/pkg|" \
        "$A/owrt-apk.sh" > "$H/app/files/owrt-apk.sh"
    sed -e "s|/data/adb/owrt-apk/|$H/base/|" "$A/check-lib.sh" > "$H/app/files/check-lib.sh"
    cp "$A/userdata-lib.sh" "$H/app/files/userdata-lib.sh"
    sed -e "s|^T=/data/local/tmp/mu300-tf-install|T=$H/tmp|" \
        -e "s|/dev/block/by-name/boot_|$H/dev/boot_|g" \
        -e "s|/dev/block/mmcblk1p1|$H/dev/mmcblk1p1|g; s|/dev/block/mmcblk1\b|$H/dev/mmcblk1|g" \
        -e "s|\"\$BB\" nsenter -t 1 -m ||" \
        "$A/mu300-lib.sh" > "$H/app/files/mu300-lib.sh"
}
wait_job() {  # $1 = base dir; waits for the detached job to record its exit code
    for _ in $(seq 1 60); do [ -s "$1/job/rc" ] && return 0; sleep 0.5; done
    return 1
}

# A rootfs archive like the package's: every directory 0755, ordinary files 0644.
R=$T/rootsrc
mkdir -p "$R/lib/firmware" "$R/etc/config" "$R/opt" "$R/sbin" "$R/usr/bin"
echo init > "$R/sbin/init"; echo cfg > "$R/etc/config/network"; echo rtl > "$R/lib/firmware/rtl_nic.bin"
chmod -R u=rwX,go=rX "$R"; chmod 755 "$R" "$R/sbin/init"
# This device's Android side (what mu300-vendor-from-device.sh / prepare_firmware read).
AND=$T/android
mkdir -p "$AND/vendor/etc" "$AND/vendor/bin" "$AND/vendor/lib64" "$AND/odm/firmware" "$AND/dev/__properties__"
echo xml > "$AND/vendor/etc/modem_cp_info.xml"; echo mc > "$AND/vendor/bin/modem_control"
echo so > "$AND/vendor/lib64/libril.so"; echo wcn > "$AND/odm/firmware/wcnmodem.bin"
echo prop > "$AND/dev/__properties__/property_info"
chmod -R u=rwX,go=rX "$AND"; chmod 755 "$AND/vendor/bin/modem_control"

# =========================================================== MU300 family, TF card (owrt-apk.sh start tf)
M=$T/mu300; helpers "$M"
mknod "$M/dev/boot_b" b 7 200 2>/dev/null || { mkdir -p "$M/dev"; mknod "$M/dev/boot_b" b 7 200; }
mknod "$M/dev/mmcblk1p1" b 7 201
cat > "$M/bin/getprop" <<G
#!/bin/sh
case \$1 in ro.boot.slot_suffix) echo _a ;; persist.sys.locale) echo zh-CN ;; *) echo ;; esac
G
chmod 755 "$M/bin/getprop"
TF=$M/tfroot   # stands for the formatted TF card's mounted root
S=$M/app/files/stage/pkg; mkdir -p "$S/payload" "$S/desktop"
cp "$(command -v busybox)" "$S/payload/busybox"
echo mu300 > "$S/OWRT-APK-LAYOUT"
printf 'mu300_install_lang() { echo zh; }\n' > "$S/payload/locale.sh"
printf '#!/system/bin/sh\nKERNEL=7.2\n' > "$S/payload/customize.sh"
tar -czf "$S/payload/mu300-openwrt.tar.gz" -C "$R" .
echo boot > "$S/payload/boot-linux.img"
cat > "$S/payload/tf-install-worker.sh" <<W
set -eu
P=\$MU300_PAYLOAD_DIR; BB=\$MU300_BUSYBOX
echo "worker umask=\$(umask)"
[ "\${1:-}" = --preflight ] && exit 0
# tf-install-worker.sh: private state dir, then the vendor-preflight step
MU300_TF_STATE=\$(mktemp -d "\$MU300_INSTALL_TMP/log.XXXXXX")
V=\$MU300_TF_STATE/vendor
# --- mu300-vendor-from-device.sh "\$MU300_TF_STATE/vendor" openwrt (sets no umask)
F=\$V/lib/firmware; mkdir -p "\$F"
cp -p $AND/odm/firmware/wcnmodem.bin "\$F/wcnmodem.bin"
"\$BB" tar -chf "\$MU300_TF_STATE/a.tar" -C $AND vendor/etc/modem_cp_info.xml vendor/bin/modem_control vendor/lib64/libril.so dev/__properties__/property_info
A=\$V/opt/mu300/android; rm -rf "\$A"; mkdir -p "\$A"; "\$BB" tar -xf "\$MU300_TF_STATE/a.tar" -C "\$A"; rm -f "\$MU300_TF_STATE/a.tar"
mkdir -p "\$A/system/bin" "\$A/linkerconfig"
chmod 755 "\$A/vendor/bin/modem_control"
# --- android-install.sh (its line 19: umask 022) unpacks the rootfs and merges the staging
(
umask 022
N=$TF/openwrt.new; mkdir -p "\$N"
"\$BB" tar -xzpf "\$P/mu300-openwrt.tar.gz" -C "\$N"
rm -rf "\$N/opt/mu300/android"
cp -a "\$V/." "\$N/"
# hotspot password: temporarily 077 + chmod 600, as the original
( umask 077; echo "ssid=MU300-OpenWrt key=12345678" > "\$N/etc/hotspot.conf" ); chmod 600 "\$N/etc/hotspot.conf"
mv "\$N" $TF/openwrt
)
rm -rf "\$V"
echo '热点：已导入 MU300-OpenWrt'
W
(cd "$S" && sha256sum payload/* > OWRT-APK-SUMS)
{ echo '{"magisk": {'; sed 's/^\([0-9a-f]*\)  \(.*\)$/"\2": "\1",/' "$S/OWRT-APK-SUMS"; echo '"x": "y"}}'; } > "$S/desktop/manifest.json"
mkdir -p "$TF"
out=$(PATH="$M/bin:$PATH" sh "$M/app/files/owrt-apk.sh" stage "$S" 2>&1)
echo "$out" | grep -q 'OWRT-APK-STAGED=' && ok 'MU300: stage' || { bad 'MU300: stage'; echo "$out"; }
PATH="$M/bin:$PATH" sh "$M/app/files/owrt-apk.sh" start tf > "$M/start.out" 2>&1
wait_job "$M/base" || bad 'MU300: job did not finish'
log=$(cat "$M/base/job/log" 2>/dev/null)
echo "$log" | grep -q 'OWRT-APK-INSTALL-OK' && ok 'MU300: install finished' || { bad 'MU300: install'; echo "$log"; }
echo "$log" | grep -q '^worker umask=0022$' && ok 'MU300: package worker runs with umask 022 (as Magisk)' || { bad 'MU300: worker umask'; echo "$log" | grep umask; }
O=$TF/openwrt; V=$O/opt/mu300/android
mode_is 755 'MU300 TF system dir' "$O" "$O/lib" "$O/lib/firmware" "$O/etc" "$O/opt" "$O/opt/mu300" "$V" \
    "$V/vendor" "$V/vendor/etc" "$V/vendor/bin" "$V/vendor/lib64" "$V/dev" "$V/dev/__properties__" "$V/system/bin" "$V/linkerconfig"
mode_is 644 'MU300 TF vendor file' "$V/vendor/etc/modem_cp_info.xml" "$V/vendor/lib64/libril.so" "$O/lib/firmware/wcnmodem.bin"
mode_is 755 'MU300 TF executable' "$V/vendor/bin/modem_control"
mode_is 600 'MU300 TF hotspot password' "$O/etc/hotspot.conf"
mode_is 600 'MU300 APK parameter file' "$M/base/pkg/payload/mu300-install.env"
mode_is 700 'MU300 APK private dir' "$M/base" "$M/base/pkg" "$M/base/job" "$M/tmp"
# modem_control runs as an unprivileged user: every directory on its path must be searchable.
# (Only the scratch directories above the "TF card" are opened up for this; the card itself is untouched.)
chmod 755 "$T" "$M" "$TF"
if command -v setpriv >/dev/null 2>&1 && setpriv --reuid=1000 --regid=1000 --clear-groups test -x "$TF"; then
    setpriv --reuid=1000 --regid=1000 --clear-groups cat "$V/vendor/etc/modem_cp_info.xml" >/dev/null 2>&1 \
        && ok 'MU300: uid 1000 can read vendor/etc/modem_cp_info.xml' || bad 'MU300: uid 1000 cannot read the vendor configuration'
    setpriv --reuid=1000 --regid=1000 --clear-groups cat "$M/base/pkg/payload/mu300-install.env" >/dev/null 2>&1 \
        && bad 'MU300: uid 1000 can read the APK parameter file' || ok 'MU300: APK parameter file stays private'
else
    echo 'NOTE: uid 1000 read check skipped (scratch path not reachable for uid 1000)'
fi

# =========================================================== E5 family, TF card (owrt-apk.sh start → magisk-tf-install.sh)
E=$T/e5; helpers "$E"
VIEW=$E/e5root     # stands for the TF root partition after "gzip -dc openwrt.ext4.gz > part; mount"
mkdir -p "$VIEW"; tar -xf <(tar -cf - -C "$R" .) -C "$VIEW"
S=$E/app/files/stage/pkg; mkdir -p "$S/files"
cp "$(command -v busybox)" "$S/files/busybox"
cat > "$S/files/magisk-tf-install.sh" <<EOF
set -eu
if [ "\${E5_TF_LIB:-0}" = 1 ]; then return 0; fi
BB=\$E5_PACKAGE/files/busybox
echo "[TF] package umask=\$(umask)"
BASE=$E/adb/e5-tf-install; mkdir -p "\$BASE"; chmod 700 "\$BASE"
STATE=\$(mktemp -d "\$BASE/run.XXXXXX"); chmod 700 "\$STATE"
# prepare_firmware: magisk-firmware.py (in the converted rootfs' chroot) creates the firmware tree
fw=\$STATE/view/tmp/e5-convert/firmware
mkdir -p "\$fw/lib/firmware/sprd" "\$fw/etc/e5"
cp $AND/odm/firmware/wcnmodem.bin "\$fw/lib/firmware/wcnmodem.bin"
echo pskey > "\$fw/lib/firmware/sprd/marlin3lite_pskey.bin"; echo ucm > "\$fw/etc/e5/audio.conf"
(cd "\$fw" && "\$BB" tar -cf "\$STATE/firmware.tar" lib etc)
# collect-device-files.sh (its own umask 022), as the package
(
umask 022
C=\$STATE/collect; mkdir -p "\$C/opt/e5/android/vendor/bin" "\$C/opt/e5/android/vendor/etc"
cp -L $AND/vendor/bin/modem_control "\$C/opt/e5/android/vendor/bin/"; cp -L $AND/vendor/etc/modem_cp_info.xml "\$C/opt/e5/android/vendor/etc/"
"\$BB" tar -xf "\$STATE/firmware.tar" -C "\$C"
find "\$C/opt/e5/android" -type d -exec chmod 755 {} +
(cd "\$C" && "\$BB" tar -cf "\$STATE/device-files.tar" lib opt etc)
)
# write_root
"\$BB" tar -xf "\$STATE/device-files.tar" -C $VIEW
mkdir -p $VIEW/etc/e5; echo "E5_WIFI_KEY='k'" > $VIEW/etc/e5/install.conf; chmod 600 $VIEW/etc/e5/install.conf
echo 'E5-TF-INSTALL-OK: reboot manually / 请手动重启'
EOF
echo fake > "$S/files/e5-bootctl"; echo fakeimg > "$S/files/openwrt.ext4.gz"; echo fakeboot > "$S/files/boot.img"
echo 'e5-openwrt-mock' > "$S/files/VERSION"
(cd "$S" && sha256sum files/busybox files/magisk-tf-install.sh files/e5-bootctl files/openwrt.ext4.gz files/boot.img > files/SHA256SUMS && cp files/SHA256SUMS files/SHA256SUMS.full)
out=$(sh "$E/app/files/owrt-apk.sh" stage "$S" 2>&1)
echo "$out" | grep -q 'OWRT-APK-STAGED=' && ok 'E5: stage' || { bad 'E5: stage'; echo "$out"; }
out=$(timeout 60 sh "$E/app/files/owrt-apk.sh" start 2>&1)
echo "$out" | grep -q 'OWRT-APK-RC=0' && ok 'E5: install finished' || { bad 'E5: install'; echo "$out" | tail; }
echo "$out" | grep -q 'package umask=0022' && ok 'E5: package installer runs with umask 022 (as Magisk)' || bad 'E5: package umask'
mode_is 755 'E5 TF system dir' "$VIEW" "$VIEW/lib" "$VIEW/lib/firmware" "$VIEW/lib/firmware/sprd" "$VIEW/etc" "$VIEW/etc/e5" \
    "$VIEW/opt" "$VIEW/opt/e5" "$VIEW/opt/e5/android" "$VIEW/opt/e5/android/vendor" "$VIEW/opt/e5/android/vendor/etc"
mode_is 644 'E5 TF firmware file' "$VIEW/lib/firmware/wcnmodem.bin" "$VIEW/lib/firmware/sprd/marlin3lite_pskey.bin" "$VIEW/opt/e5/android/vendor/etc/modem_cp_info.xml"
mode_is 600 'E5 TF hotspot settings' "$VIEW/etc/e5/install.conf"
mode_is 700 'E5 APK private dir' "$E/base" "$E/base/job" "$E/adb/e5-tf-install"
for st in "$E"/adb/e5-tf-install/run.*; do mode_is 700 'E5 package log/backup dir' "$st"; done

echo "mock-perm result: $pass passed, $fail failed"
[ "$fail" = 0 ]
