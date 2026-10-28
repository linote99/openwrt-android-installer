#!/usr/bin/env bash
# Mock test of the APK's own helper scripts (owrt-apk.sh / check-lib.sh) on a Linux PC.
# Uses a FAKE installer package written here; never runs anything from a real package.
# Paths, the arch check, nsenter and the BusyBox standalone-shell probe (host busybox
# lacks that mode; the real package busybox is probed by the package's customize.sh too)
# are rewritten so it runs outside Android.
set -u
HERE=$(cd "$(dirname "$0")/.." && pwd)
T=${1:-$HERE/build-test/mock}
rm -rf "$T"
mkdir -p "$T/app/files" "$T/base"
A=$HERE/app/src/main/assets
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
cp "$A/mu300-lib.sh" "$T/app/files/mu300-lib.sh"

make_pkg() {  # $1 = install exit code, $2 = staging dir
    S=${2:-$T/app/files/stage/pkg}
    rm -rf "$S"; mkdir -p "$S/files"
    cp "$(command -v busybox)" "$S/files/busybox"
    cat > "$S/files/magisk-tf-install.sh" <<EOF
set -eu
preflight() {
    cd "\$P"
    sha256sum -c files/SHA256SUMS > "\$STATE/package-check.log"
    [ -n "\${STATE:-}" ] || exit 9
    ANDROID_SLOT=a; LINUX_SLOT=b; SD=/dev/block/mmcblk1; PART=/dev/block/mmcblk1p1
    blockdev() { echo 31914983424; }
    echo "[TF] Model E5, target \$PART; SELECTED TF TARGET WILL BE ERASED (no repartitioning)"
}
if [ "\${E5_TF_LIB:-0}" = 1 ]; then return 0; fi
trap 'exit 130' HUP
echo "[TF] Package, model, boot chain and capacity checks / 预检"
sleep 1; echo "[TF] Prepare and convert this device firmware / 提取并转换本机固件"
echo "[TF] Hotspot / 热点：E5-OpenWrt-1234; key / 密码：abcd1234"
sleep 2; echo "[TF] Write and verify Linux boot / 写入并校验内核"
[ "$1" = 0 ] || { echo "E5-TF-ERROR: simulated failure" >&2; exit $1; }
echo 'E5-TF-INSTALL-OK: reboot manually / 请手动重启'
EOF
    echo fake > "$S/files/e5-bootctl"
    echo fakeimg > "$S/files/openwrt.ext4.gz"; echo fakeboot > "$S/files/boot.img"
    echo 'e5-openwrt-mock' > "$S/files/VERSION"
    (cd "$S" && sha256sum files/busybox files/magisk-tf-install.sh files/e5-bootctl files/openwrt.ext4.gz files/boot.img > files/SHA256SUMS && cp files/SHA256SUMS files/SHA256SUMS.full)
}

H="sh $T/app/files/owrt-apk.sh"
pass=0; fail=0
expect() { if echo "$2" | grep -q -- "$3"; then echo "PASS: $1"; pass=$((pass+1)); else echo "FAIL: $1 (wanted '$3')"; echo "$2" | tail -8; fail=$((fail+1)); fi; }

make_pkg 0
out=$($H stage "$T/app/files/stage/pkg" 2>&1);       expect 'stage'             "$out" 'OWRT-APK-STAGED='
[ ! -e "$T/app/files/stage/pkg" ] && { echo 'PASS: source moved out of app dir'; pass=$((pass+1)); } || { echo 'FAIL: source left'; fail=$((fail+1)); }
out=$($H stage /tmp/evil 2>&1);                        expect 'stage rejects foreign path' "$out" 'unexpected source directory'
out=$($H check 2>&1);                                  expect 'check ok'          "$out" 'OWRT-APK-CHECK-OK'
expect 'check reports target' "$out" 'OWRT-APK-TARGET=/dev/block/mmcblk1p1'
expect 'check rc 0' "$out" 'OWRT-APK-CHECK-RC=0'
ls "$T/base" | grep -q '^check\.' && { echo 'FAIL: check temp left'; fail=$((fail+1)); } || { echo 'PASS: check temp removed'; pass=$((pass+1)); }
out=$(timeout 60 $H start 2>&1);                       expect 'install ok'        "$out" 'OWRT-APK-RC=0'
expect 'log streamed' "$out" 'Hotspot / 热点：E5-OpenWrt-1234'
expect 'install marker' "$out" 'OWRT-APK-INSTALL-OK'
[ ! -e "$T/base/pkg/files/openwrt.ext4.gz" ] && { echo 'PASS: payload removed after success'; pass=$((pass+1)); } || { echo 'FAIL: payload kept'; fail=$((fail+1)); }
out=$($H status 2>&1);                                 expect 'status done 0'     "$out" 'OWRT-APK-JOB=done 0'
out=$(timeout 30 $H follow 2>&1);                      expect 'follow replays log' "$out" 'E5-TF-INSTALL-OK'
out=$($H start 2>&1);                                  expect 'restart refused w/o payload (re-stage required)' "$out" 'no staged package'

# Failure + detach: the caller is killed mid-way, job must keep running and record rc.
make_pkg 3
$H stage "$T/app/files/stage/pkg" >/dev/null 2>&1
( $H start > "$T/first-follow.log" 2>&1 & echo $! > "$T/follower.pid"; wait ) &
sleep 1.5
kill "$(cat "$T/follower.pid")" 2>/dev/null; pkill -f "tail -n +1 -f $T/base/job/log" 2>/dev/null
out=$($H status 2>&1);                                 expect 'job survives caller kill' "$out" 'OWRT-APK-JOB=running'
out=$($H clean 2>&1);                                  expect 'clean refused while running' "$out" 'running'
out=$(timeout 30 $H follow 2>&1);                      expect 'reattach gets failure rc' "$out" 'OWRT-APK-RC=3'
expect 'failure text' "$out" 'E5-TF-ERROR: simulated failure'
[ -e "$T/base/pkg/files/openwrt.ext4.gz" ] && { echo 'PASS: payload kept after failure'; pass=$((pass+1)); } || { echo 'FAIL: payload removed after failure'; fail=$((fail+1)); }
out=$($H clean 2>&1);                                  expect 'clean'             "$out" 'OWRT-APK-CLEAN-OK'
out=$($H status 2>&1);                                 expect 'status after clean' "$out" 'OWRT-APK-STAGED=none'
# LAN mode: files pushed over wireless ADB to the remote stage, helper run from there.
mkdir -p "$T/remote"; cp "$T/app/files/owrt-apk.sh" "$T/app/files/check-lib.sh" "$T/app/files/userdata-lib.sh" "$T/app/files/mu300-lib.sh" "$T/remote/"
make_pkg 0 "$T/remote/pkg"
out=$(sh "$T/remote/owrt-apk.sh" stage "$T/remote/pkg" 2>&1); expect 'LAN stage from remote dir' "$out" 'OWRT-APK-STAGED='
[ "$(stat -c %u "$T/base/pkg/files/boot.img")" = 0 ] && [ "$(stat -c %a "$T/base/pkg")" = 700 ] && { echo 'PASS: LAN stage root-owned, 700'; pass=$((pass+1)); } || { echo 'FAIL: LAN stage ownership'; fail=$((fail+1)); }
make_pkg 0 "$T/remote/pkg"; echo tampered >> "$T/remote/pkg/files/boot.img"
out=$(sh "$T/remote/owrt-apk.sh" stage "$T/remote/pkg" 2>&1); expect 'LAN stage rejects tampered file' "$out" 'checksum mismatch'
make_pkg 0 "$T/remote/pkg"; sed -i '1s/^[0-9a-f]*/0000000000000000000000000000000000000000000000000000000000000000/' "$T/remote/pkg/files/SHA256SUMS"
out=$(sh "$T/remote/owrt-apk.sh" stage "$T/remote/pkg" 2>&1); expect 'LAN stage rejects altered subset list' "$out" 'subset list mismatch'
mv "$T/remote" "$T/remote.real"; ln -s "$T/remote.real" "$T/remote"
out=$(sh "$T/remote.real/owrt-apk.sh" stage "$T/remote/pkg" 2>&1); expect 'LAN stage rejects symlinked stage dir' "$out" 'unsafe remote stage'
rm "$T/remote"; mv "$T/remote.real" "$T/remote"
echo "mock result: $pass passed, $fail failed"
[ "$fail" = 0 ]
