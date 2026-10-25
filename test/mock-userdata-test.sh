#!/usr/bin/env bash
# Mock test of the APK's userdata methods (owrt-apk.sh check-data/start + userdata-lib.sh) on a Linux PC.
# Uses a FAKE package written here (stub prepare_firmware / write_boot / bootchain / e5-bootctl /
# userdata-image.sh); never runs anything from a real installer package. Android paths are
# rewritten into a scratch directory; getprop/df/blockdev are shell functions of the fake package.
# Run as root (the helpers insist on uid 0).
set -u
HERE=$(cd "$(dirname "$0")/.." && pwd)
T=${1:-$HERE/build-test/mock-data}
rm -rf "$T"
mkdir -p "$T/app/files" "$T/base" "$T/data/e5linux" "$T/data/local/tmp" "$T/adb" "$T/byname" "$T/sys/mmcblk0p70" "$T/sys/mmcblk0p1"
A=$HERE/app/src/main/assets
LOG=$T/calls.log
: > "$LOG"
sed -e "s|^BASE=/data/adb/owrt-apk|BASE=$T/base|" \
    -e "s|/system/bin/sh |/bin/sh |g" \
    -e "s|\[ \"\$(uname -m)\" = aarch64 \]|true|" \
    -e "s|\"\$BB\" nsenter -t 1 -m ||" \
    -e "s|ASH_STANDALONE=1 \"\$BB\" sh -c 'PATH=/nonexistent; sha256sum|true \|\| \"\$BB\" sh -c 'PATH=/nonexistent; sha256sum|" \
    -e "s|/data/user/\[0-9\]\*/\"\$APP_ID\"/files/stage/pkg|$T/app/files/stage/pkg|" \
    "$A/owrt-apk.sh" > "$T/app/files/owrt-apk.sh"
cp "$A/check-lib.sh" "$T/app/files/check-lib.sh"
sed -e "s|^D=/data/e5linux|D=$T/data/e5linux|" \
    -e "s|^TMPD=/data/local/tmp|TMPD=$T/data/local/tmp|" \
    -e "s|^BASE=/data/adb/e5-tf-install|BASE=$T/adb/e5-tf-install|" \
    -e "s|TBASE=/data/adb/e5-tf-install|TBASE=$T/adb/e5-tf-install|" \
    -e "s|/data/adb/e5-boot-control|$T/adb/e5-boot-control|g" \
    -e "s|/data/adb/owrt-apk/check|$T/base/check|" \
    -e "s|/sys/class/block|$T/sys|g" \
    -e "s|/proc/mounts|$T/mounts|" \
    -e "s|/dev/block/by-name|$T/byname|g" \
    -e "s|\"/dev/block/\$DATA_NODE\"|\"$T/blk/\$DATA_NODE\"|" \
    "$A/userdata-lib.sh" > "$T/app/files/userdata-lib.sh"
grep -n '/data/\|/dev/block/by-name\|/sys/class' "$T/app/files/userdata-lib.sh" | grep -v "$T" | grep -v '^.*#' | grep -v "fail '" | grep -v "KEEP_NO='" | grep -v 'OWRT-APK-DATA-NODE\|dm-\|mapper\|/dev/block/\*' && { echo 'FAIL: unrewritten Android path'; exit 1; }
cp "$A/mu300-lib.sh" "$T/app/files/mu300-lib.sh"
# fake partitions and /data mount
printf 'MAJOR=179\nPARTNAME=userdata\n' > "$T/sys/mmcblk0p70/uevent"; echo 134217728 > "$T/sys/mmcblk0p70/size"   # 64 GiB
printf 'MAJOR=179\nPARTNAME=boot_a\n' > "$T/sys/mmcblk0p1/uevent"; echo 131072 > "$T/sys/mmcblk0p1/size"
echo "/dev/block/mmcblk0p70 /data f2fs rw,lazytime 0 0" > "$T/mounts"
# fake userdata block device: F2FS superblock magic 0xF2F52010 (little endian) at offset 1024
mkdir -p "$T/blk"; f2fs_dev() { head -c 4096 /dev/zero > "$T/blk/mmcblk0p70"; printf '\020\040\365\362' | dd of="$T/blk/mmcblk0p70" bs=1 seek=1024 conv=notrunc 2>/dev/null; }
f2fs_dev
for n in boot_a boot_b misc l_agdsp_a; do mknod "$T/byname/$n" b 7 250 2>/dev/null || { echo "need root for mknod"; exit 1; }; done

make_pkg() {
    S=$T/app/files/stage/pkg
    rm -rf "$S"; mkdir -p "$S/files" "$S/x"
    cp "$(command -v busybox)" "$S/files/busybox"
    cat > "$S/files/magisk-tf-install.sh" <<EOF
set -eu
fail() { echo "E5-TF-ERROR: \$*" >&2; exit 1; }
sha() { r=\$(sha256sum "\$1"); echo "\${r%% *}"; }
getprop() { case \$1 in
    ro.product.model) echo E5 ;; ro.product.device) echo e5 ;; ro.boot.slot_suffix) echo _a ;;
    ro.boot.verifiedbootstate) echo orange ;; ro.crypto.state) echo "\${MOCK_CRYPTO_STATE-unencrypted}" ;;
    ro.crypto.type) echo "\${MOCK_CRYPTO_TYPE-}" ;; esac; }
blockdev() { echo 67108864; }
df() { for p; do :; done; echo 'Filesystem 1024-blocks Used Available Capacity Mounted'; echo "/dev/block/mmcblk0p70 67108864 \${MOCK_USED_KB:-20971520} \${MOCK_AVAIL_KB:-31457280} 40% /data"; }
prepare_firmware() {
    echo "prepare_firmware" >> $LOG
    echo "[TF] Hotspot / 热点：E5-OpenWrt-77; key / 密码：k3y"
    printf 'devfiles' > "\$STATE/device-files.tar"; printf 'E5_WIFI_SSID=E5-OpenWrt-77\n' > "\$STATE/install.conf"
    BOOT_SHA256=abc
    [ "\${MOCK_FAIL:-}" != firmware ] || fail 'simulated firmware failure'
}
write_boot() { echo "write_boot \$BOOT" >> $LOG; [ "\${MOCK_FAIL:-}" != boot ] || fail 'simulated boot failure'; }
if [ "\${E5_TF_LIB:-0}" = 1 ]; then return 0; fi
echo 'tf main must not run in these tests'; exit 99
EOF
    printf '#!/bin/sh\necho "bootctl $*" >> %s\n[ "$1" != arm ] || : > "$2"\n' "$LOG" > "$S/files/e5-bootctl"
    printf '#!/bin/sh\necho "bootchain $1" >> %s\n' "$LOG" > "$S/files/android-bootchain.sh"
    printf '# fake android-loop\n' > "$S/files/android-loop.sh"
    cat > "$S/files/part-write.sh" <<EOF
#!/bin/sh
echo "part-write \$*" >> $LOG
for f in openwrt.ext4.gz e5-ext4-tools.tar e5-part-write.sh e5-install.conf e5-device-files.tar; do
    [ -s $T/data/local/tmp/\$f ] || { echo "missing \$f" >&2; exit 7; }
done
[ "\${MOCK_FAIL:-}" != partwrite ] || { echo 'simulated write failure' >&2; exit 5; }
echo E5-PART-WRITE-OK
EOF
    cat > "$S/files/userdata-image.sh" <<EOF
#!/bin/sh
echo "userdata-image \$*" >> $LOG
[ -f $T/data/local/tmp/openwrt.ext4.gz ] && [ -f $T/data/local/tmp/e5-ext4-tools.tar ] && [ -f $T/data/local/tmp/e5-android-loop.sh ] || exit 7
[ "\${MOCK_FAIL:-}" != image ] || { echo 'resize2fs failed' >&2; exit 5; }
echo image > $T/data/e5linux/openwrt.ext4.part
rm -f $T/data/local/tmp/openwrt.ext4.gz $T/data/local/tmp/e5-ext4-tools.tar
echo "USERDATA-IMAGE-OK \$2"
EOF
    (cd "$S/x" && mkdir -p lib usr/sbin && touch lib/ld-musl-aarch64.so.1 usr/sbin/e2fsck usr/sbin/resize2fs ext4-geometry.sh SHA256SUMS && \
        tar -cf ../files/ext4-tools.tar lib/ld-musl-aarch64.so.1 usr/sbin/e2fsck usr/sbin/resize2fs ext4-geometry.sh SHA256SUMS)
    rm -rf "$S/x"
    printf 'PROFILE=e5\nANDROID_MODEL=E5\nDEFAULT_SSID=E5-OpenWrt\nSLOT_ROLES_VERSION=1\nRAW_BYTES=2147483648\nBOOT_BYTES=67108864\n' > "$S/files/tf-install.env"
    head -c 300000 /dev/urandom > "$S/files/openwrt.ext4.gz"; echo fakeboot > "$S/files/boot.img"
    echo 'e5-openwrt-mock' > "$S/files/VERSION"
    (cd "$S" && sha256sum files/* > files/SHA256SUMS.tmp && mv files/SHA256SUMS.tmp files/SHA256SUMS && cp files/SHA256SUMS files/SHA256SUMS.full)
}
H="sh $T/app/files/owrt-apk.sh"
pass=0; fail=0
ok() { echo "PASS: $1"; pass=$((pass+1)); }
bad() { echo "FAIL: $1"; fail=$((fail+1)); }
expect() { if echo "$2" | grep -q -- "$3"; then ok "$1"; else bad "$1 (wanted '$3')"; echo "$2" | tail -12; fi; }
reboot_sim() { rm -rf "$T/adb/e5-tf-install"/install-*.lock; }
restage() { make_pkg; $H stage "$T/app/files/stage/pkg" >/dev/null 2>&1 || { bad 'stage'; }; }

restage
[ -f "$T/base/userdata-lib.sh" ] && ok 'stage installs userdata-lib.sh' || bad 'userdata-lib.sh not installed'
# ---------------- part: check
out=$($H check-data part 2>&1)
expect 'part check ok' "$out" 'OWRT-APK-CHECK-OK'
expect 'part range (min=2048+256, max=65536-20480-2048, default ceil((2048+1024)/1024)*1024=3072)' "$out" 'OWRT-APK-PART-RANGE=2304 43008 3072'
expect 'part reports userdata' "$out" 'OWRT-APK-DATA-NODE=/dev/block/mmcblk0p70'
expect 'check rc 0' "$out" 'OWRT-APK-CHECK-RC=0'
ls "$T/base" | grep -q '^check\.' && bad 'check temp left' || ok 'check temp removed'
[ -z "$(ls "$T/data/e5linux")" ] && ok 'check wrote nothing to /data/e5linux' || bad 'check wrote files'
# 1.5.0: encrypted userdata no longer fails the check (PC part_userdata_policy):
# keep-data shrink is reported impossible, the format-userdata range is offered.
out=$(MOCK_CRYPTO_TYPE=file $H check-data part 2>&1)
expect 'FBE: check passes' "$out" 'OWRT-APK-CHECK-RC=0'
expect 'FBE: flagged encrypted' "$out" 'OWRT-APK-ENCRYPTED=1'
expect 'FBE: keep-data impossible' "$out" 'OWRT-APK-KEEP-NO=userdata is encrypted'
expect 'FBE: format range (min 2304, max 65536-0-2048)' "$out" 'OWRT-APK-WIPE-RANGE=2304 63488 3072'
echo "$out" | grep -q 'OWRT-APK-PART-RANGE' && bad 'FBE: keep range offered' || ok 'FBE: no keep range'
out=$(MOCK_CRYPTO_STATE=encrypted MOCK_CRYPTO_TYPE= $H check-data part 2>&1);  expect 'encrypted state: keep-data impossible' "$out" 'OWRT-APK-KEEP-NO=userdata is encrypted'
# PC only treats state==encrypted, type file/block or dm-mapped /data as encrypted
out=$(MOCK_CRYPTO_STATE= $H check-data part 2>&1);  expect 'unknown crypto state: not encrypted (as PC)' "$out" 'OWRT-APK-ENCRYPTED=0'
expect 'unknown crypto state: keep range offered' "$out" 'OWRT-APK-PART-RANGE=2304 43008 3072'
out=$(MOCK_CRYPTO_TYPE=file timeout 60 $H start part 4096 1 2>&1); expect 'FBE: keep-data start refused' "$out" 'OWRT-APK-RC=1'
[ ! -e "$T/data/e5linux/part-install.conf" ] && ok 'FBE: nothing staged' || bad 'FBE: staged on encrypted'
reboot_sim
echo "/dev/block/dm-5 /data f2fs rw 0 0" > "$T/mounts"
out=$($H check-data part 2>&1); expect 'dm-mapped /data: keep-data impossible' "$out" 'OWRT-APK-KEEP-NO=userdata is encrypted'
echo "/dev/block/mmcblk0p70 /data f2fs rw,lazytime 0 0" > "$T/mounts"
out=$(MOCK_USED_KB=62914560 $H check-data part 2>&1); expect 'full userdata: keep-data impossible' "$out" 'OWRT-APK-KEEP-NO=not enough room'; expect 'full userdata: format range still offered' "$out" 'OWRT-APK-WIPE-RANGE=2304 63488'
mkdir -p "$T/sys/mmcblk0p71"; printf 'PARTNAME=e5root\n' > "$T/sys/mmcblk0p71/uevent"; echo 1 > "$T/sys/mmcblk0p71/size"
out=$($H check-data part 2>&1); expect 'existing e5root refused' "$out" 'already exists'
rm -rf "$T/sys/mmcblk0p71"
head -c 4096 /dev/zero > "$T/blk/mmcblk0p70"
out=$($H check-data part 2>&1); expect 'non-F2FS userdata refused' "$out" 'userdata is not F2FS'
f2fs_dev
out=$($H check-data part 2>&1); expect 'F2FS userdata accepted' "$out" 'OWRT-APK-DATA-FS=f2fs'
# ---------------- part: argument validation
out=$($H start part 4096 2 2>&1);  expect 'bad grow flag refused' "$out" 'start \[tf'
out=$($H start part 40x 1 2>&1);   expect 'non-numeric size refused' "$out" 'start \[tf'
out=$(timeout 60 $H start part 1000 1 2>&1); expect 'size below range refused by device check' "$out" 'outside the permitted range'
expect 'refusal rc' "$out" 'OWRT-APK-RC=1'
[ ! -e "$T/data/e5linux/part-install.conf" ] && ok 'nothing staged after range refusal' || bad 'staged despite refusal'
reboot_sim
# ---------------- part: success
: > "$LOG"
out=$(timeout 60 $H start part 8192 1 2>&1)
expect 'part install rc 0' "$out" 'OWRT-APK-RC=0'
expect 'part marker' "$out" 'E5-PART-STAGED-OK'
expect 'hotspot line streamed' "$out" 'Hotspot / 热点：E5-OpenWrt-77'
C=$(cat "$T/data/e5linux/part-install.conf" 2>/dev/null)
[ "$C" = "$(printf 'PART_MIB=8192\nDATA_MIB=57344\nGROW_FS=1\nFORMAT_DATA=0')" ] && ok 'part-install.conf content' || { bad 'part-install.conf content'; echo "$C"; }
cmp -s "$T/data/e5linux/part-install.img.gz" "$T/base/pkg/files/openwrt.ext4.gz" 2>/dev/null; [ ! -e "$T/base/pkg/files/openwrt.ext4.gz" ] && [ -s "$T/data/e5linux/part-install.img.gz" ] && ok 'image staged (and APK copy removed after success)' || bad 'image staging'
[ "$(cat "$T/data/e5linux/device-files.tar")" = devfiles ] && ok 'device-files staged' || bad 'device-files'
grep -q E5_WIFI_SSID "$T/data/e5linux/part-install.settings" && ok 'settings staged' || bad 'settings'
[ "$(cat "$T/data/e5linux/boot-os")" = part ] && ok 'boot-os=part' || bad 'boot-os'
[ "$(stat -c %a "$T/data/e5linux/part-install.conf")" = 600 ] && ok 'conf mode 600' || bad 'conf mode'
order=$(grep -o 'prepare_firmware\|bootchain sync\|write_boot\|bootctl arm' "$LOG" | tr '\n' ',')
[ "$order" = "prepare_firmware,bootchain sync,write_boot,bootctl arm," ] && ok "order: $order" || bad "order: $order"
grep -q "bootctl arm .*boot-control.before.bin a" "$LOG" && ok 'arm with backup file and Android slot a' || bad 'arm args'
grep -q "write_boot $T/byname/boot_b" "$LOG" && ok 'boot written to boot_b' || bad 'boot target'
ls -d "$T/adb/e5-tf-install"/install-*.lock >/dev/null 2>&1 && bad 'lock left after success' || ok 'lock released after success'
# pending request blocks a second staging
reboot_sim; restage
out=$($H check-data part 2>&1); expect 'pending shrink request detected' "$out" 'already pending'
# ---------------- part: failure after staging withdraws the request
rm -f "$T/data/e5linux/"*; reboot_sim; restage; : > "$LOG"
out=$(MOCK_FAIL=boot timeout 60 $H start part 4096 0 2>&1)
expect 'boot failure rc' "$out" 'OWRT-APK-RC=1'
expect 'withdrawn message' "$out" 'staged shrink request withdrawn'
[ ! -e "$T/data/e5linux/part-install.conf" ] && [ ! -e "$T/data/e5linux/part-install.img.gz" ] && ok 'request withdrawn on failure' || bad 'request left behind'
[ ! -e "$T/data/e5linux/boot-os" ] && ok 'boot-os untouched on failure' || bad 'boot-os written on failure'
grep -q 'bootctl arm' "$LOG" && bad 'armed despite failure' || ok 'not armed on failure'
[ -e "$T/base/pkg/files/openwrt.ext4.gz" ] && ok 'APK payload kept after failure' || bad 'payload removed after failure'
out=$(timeout 60 $H start part 4096 0 2>&1); expect 'same-boot retry blocked by lock' "$out" 'previous failed install needs inspection'
# ---------------- image
rm -rf "$T/data/e5linux/"*; reboot_sim; restage; : > "$LOG"
out=$($H check-data image 2>&1)
expect 'image check ok' "$out" 'OWRT-APK-CHECK-OK'
expect 'image range (2048 .. floor((30720 MiB-1 GiB-512 MiB-upload bytes)/MiB), as PC choose_userdata_size)' "$out" 'OWRT-APK-IMAGE-RANGE=2048 29183 2048'
out=$(MOCK_CRYPTO_TYPE=file $H check-data image 2>&1); expect 'image: FBE refused' "$out" 'requires confirmed unencrypted'
echo old > "$T/data/e5linux/openwrt.ext4"; echo oldconf > "$T/data/e5linux/openwrt-install.conf"
out=$(timeout 60 $H start image 4096 2>&1)
expect 'image install rc 0' "$out" 'OWRT-APK-RC=0'
expect 'image marker' "$out" 'E5-DATA-INSTALL-OK'
grep -q "userdata-image 2147483648 4294967296 2048 4096" "$LOG" && ok 'userdata-image.sh args (raw, selected, raw_mib, sel_mib)' || { bad 'userdata-image args'; grep userdata-image "$LOG"; }
[ "$(cat "$T/data/e5linux/openwrt.ext4")" = image ] && ok 'image committed' || bad 'image commit'
ls "$T/data/e5linux"/previous.*/openwrt.ext4 >/dev/null 2>&1 && ok 'previous image kept' || bad 'previous image'
grep -q E5_WIFI_SSID "$T/data/e5linux/openwrt-install.conf" && ok 'openwrt-install.conf written' || bad 'install conf'
[ "$(cat "$T/data/e5linux/boot-os")" = openwrt ] && ok 'boot-os=openwrt' || bad 'boot-os image'
[ -z "$(ls "$T/data/local/tmp")" ] && ok 'tmp copies cleaned' || { bad 'tmp leftovers'; ls "$T/data/local/tmp"; }
order=$(grep -o 'prepare_firmware\|userdata-image\|bootchain sync\|write_boot\|bootctl arm' "$LOG" | tr '\n' ',')
[ "$order" = "prepare_firmware,userdata-image,bootchain sync,write_boot,bootctl arm," ] && ok "image order: $order" || bad "image order: $order"
# image failure: nothing armed
rm -rf "$T/data/e5linux/"*; reboot_sim; restage; : > "$LOG"
out=$(MOCK_FAIL=image timeout 60 $H start image 4096 2>&1)
expect 'image failure rc' "$out" 'OWRT-APK-RC=1'
[ ! -e "$T/data/e5linux/boot-os" ] && ! grep -q 'bootctl arm' "$LOG" && ok 'image failure: not armed' || bad 'image failure armed'
[ -z "$(ls "$T/data/local/tmp")" ] && ok 'image failure: tmp cleaned' || bad 'image failure tmp leftovers'
out=$($H start image 99999999 2>&1); expect 'job refused while lock held / range' "$out" 'OWRT-APK'
# ---------------- e5-tool (two-stage / reinstall single steps, E5Part)
rm -rf "$T/data/e5linux/"*; reboot_sim; restage; : > "$LOG"; rm -f "$T/data/local/tmp/"*
out=$($H e5-tool bogus 2>&1); expect 'e5-tool: unknown sub refused' "$out" 'e5-tool preflight|firmware'
out=$($H e5-tool preflight 2>&1)
expect 'e5-tool preflight ok' "$out" 'OWRT-APK-TOOL-OK preflight'
expect 'e5-tool preflight rc 0' "$out" 'OWRT-APK-TOOL-RC=0'
ls "$T/adb/e5-tf-install" | grep -q '^tool\.' && bad 'tool state dir left' || ok 'tool state dir removed on success'
out=$($H e5-tool firmware 2>&1)
expect 'e5-tool firmware ok' "$out" 'OWRT-APK-TOOL-OK firmware'
expect 'e5-tool firmware path' "$out" "OWRT-APK-DEVICE-FILES=$T/data/local/tmp/e5-device-files.tar"
[ "$(cat "$T/data/local/tmp/e5-device-files.tar" 2>/dev/null)" = devfiles ] && ok 'device files copied' || bad 'device files'
[ "$(stat -c %a "$T/data/local/tmp/e5-device-files.tar" 2>/dev/null)" = 600 ] && ok 'device files mode 600' || bad 'device files mode'
expect 'e5-tool firmware streams hotspot' "$out" 'Hotspot / 热点：E5-OpenWrt-77'
out=$(MOCK_FAIL=firmware $H e5-tool firmware 2>&1)
expect 'e5-tool firmware failure rc' "$out" 'OWRT-APK-TOOL-RC=1'
expect 'e5-tool failure keeps log' "$out" '\[TOOL\] firmware FAILED: log'
rm -rf "$T/adb/e5-tf-install"/tool.*
: > "$LOG"
out=$($H e5-tool bootchain-sync 2>&1); expect 'e5-tool bootchain-sync rc 0' "$out" 'OWRT-APK-TOOL-RC=0'
grep -qx 'bootchain sync' "$LOG" && ok 'bootchain sync called' || bad 'bootchain sync'
out=$($H e5-tool arm 2>&1); expect 'e5-tool arm rc 0' "$out" 'OWRT-APK-TOOL-RC=0'
expect 'e5-tool arm backup path' "$out" "Boot control backup: $T/adb/e5-boot-control/before\."
grep -q "^bootctl arm $T/adb/e5-boot-control/before\.[A-Za-z0-9]* a\$" "$LOG" && ok 'bootctl arm BACKUP a' || { bad 'bootctl arm args'; cat "$LOG"; }
# part-write argument validation (nothing copied, nothing run)
TOK=0123456789abcdef0123456789abcdef
mkdir -p "$T/sys/mmcblk0p71"; printf 'MAJOR=179\nPARTNAME=e5root\n' > "$T/sys/mmcblk0p71/uevent"; echo 71 > "$T/sys/mmcblk0p71/partition"; echo 8388608 > "$T/sys/mmcblk0p71/size"
echo 70 > "$T/sys/mmcblk0p70/partition"
: > "$LOG"
out=$($H e5-tool part-write /dev/block/mmcblk0p71 4096 2048 1 2>&1); expect 'part-write: arg count' "$out" 'part-write DEV NEED RAW GROW TOKEN'
out=$($H e5-tool part-write /dev/sda1 4096 2048 1 $TOK 2>&1); expect 'part-write: target outside mmcblk' "$out" 'invalid target'
out=$($H e5-tool part-write /dev/block/mmcblk0p71 40x 2048 1 $TOK 2>&1); expect 'part-write: bad size' "$out" 'invalid size'
out=$($H e5-tool part-write /dev/block/mmcblk0p71 4096 2048 2 $TOK 2>&1); expect 'part-write: bad grow' "$out" 'grow flag must be 0 or 1'
out=$($H e5-tool part-write /dev/block/mmcblk0p71 4096 2048 1 0123 2>&1); expect 'part-write: short token' "$out" 'invalid token'
out=$($H e5-tool part-write /dev/block/mmcblk0p71 4096 2048 1 0123456789ABCDEF0123456789abcdef 2>&1); expect 'part-write: upper-case token' "$out" 'invalid token'
out=$($H e5-tool part-write /dev/block/mmcblk0p70 4096 2048 1 $TOK 2>&1); expect 'part-write: userdata is not e5root' "$out" 'target is not e5root'
out=$($H e5-tool part-write /dev/block/mmcblk0p99 4096 2048 1 $TOK 2>&1); expect 'part-write: missing partition' "$out" 'target is not a partition'
rm -f "$T/data/local/tmp/e5-device-files.tar"
out=$($H e5-tool part-write /dev/block/mmcblk0p71 4096 2048 1 $TOK 2>&1); expect 'part-write: device files required' "$out" 'settings or device files missing'
expect 'part-write refusal rc' "$out" 'OWRT-APK-TOOL-RC=1'
! grep -q '^part-write' "$LOG" && ok 'part-write.sh never ran on refusals' || bad 'part-write.sh ran on refusal'
rm -rf "$T/adb/e5-tf-install"/tool.*
# part-write success: package image + tools + script copied, script run with the 5 args, tmp cleaned
printf 'devfiles' > "$T/data/local/tmp/e5-device-files.tar"; printf 'E5_WIFI_SSID=x\n' > "$T/data/local/tmp/e5-install.conf"
out=$($H e5-tool part-write /dev/block/mmcblk0p71 4096 2048 1 $TOK 2>&1)
expect 'part-write ok' "$out" 'OWRT-APK-TOOL-OK part-write'
expect 'part-write marker' "$out" 'E5-PART-WRITE-OK'
grep -qx "part-write /dev/block/mmcblk0p71 4096 2048 1 $TOK" "$LOG" && ok 'part-write.sh args (DEV NEED RAW GROW TOKEN)' || { bad 'part-write args'; cat "$LOG"; }
[ -z "$(ls "$T/data/local/tmp")" ] && ok 'part-write: tmp cleaned' || { bad 'part-write tmp leftovers'; ls "$T/data/local/tmp"; }
printf 'devfiles' > "$T/data/local/tmp/e5-device-files.tar"; printf 'E5_WIFI_SSID=x\n' > "$T/data/local/tmp/e5-install.conf"
out=$(MOCK_FAIL=partwrite $H e5-tool part-write /dev/block/mmcblk0p71 4096 2048 1 $TOK 2>&1)
expect 'part-write failure rc (script rc passed through)' "$out" 'OWRT-APK-TOOL-RC=5'
[ -z "$(ls "$T/data/local/tmp")" ] && ok 'part-write failure: tmp cleaned' || bad 'part-write failure tmp leftovers'
rm -rf "$T/adb/e5-tf-install"/tool.* "$T/sys/mmcblk0p71"
echo "mock-data result: $pass passed, $fail failed"
[ "$fail" = 0 ]
