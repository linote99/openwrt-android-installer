#!/system/bin/sh
# APK 自带（不来自安装包）：MU300 / F50 / U30 Air / V50 安装包（payload/ 布局）的入口。
# 完全按安装包 payload/customize.sh 的方式准备参数并调用包内 payload/tf-install-worker.sh：
#   mu300-lib.sh check     只运行 worker 的 --preflight（校验载荷、TF/boot/misc、启动链 check、空间）
#   mu300-lib.sh install   运行 worker 完整流程（与 Magisk 刷入 ZIP 时相同的分阶段安装）
# 由 owrt-apk.sh 以 root 调用；APK 已核对 customize.sh 的部署参数与这里写入的完全一致。
#
# umask：worker 及其子脚本（暂存本机厂商配置 mu300-vendor-from-device.sh、解系统
# android-install.sh）一律在 022 下运行，与 Magisk 刷入 ZIP 时相同。worker 先在临时
# 目录里暂存厂商文件，再 cp -a 合并进新系统的根目录，暂存目录的权限会原样带进
# /、/lib、/opt、/opt/mu300/android/vendor；077 下它们全是 0700，降权运行的
# modem_control 读不到厂商配置（基带状态 255，WAN 连不上）。
# 本脚本自己写的东西（参数文件、临时目录）仍然只给 root：077 / chmod 600。
set -eu
PK=${OWRT_PACKAGE:?}
P=$PK/payload
BB=$P/busybox
T=/data/local/tmp/mu300-tf-install

fail() { echo "MU300-APK-ERROR: $*"; exit 1; }

[ "$(id -u)" = 0 ] || fail 'root required / 需要 root'
[ -f "$P/tf-install-worker.sh" ] && [ -f "$BB" ] || fail '没有暂存的 MU300 安装包 / no staged MU300 package'
. "$P/locale.sh"
MU300_INSTALL_LANG=$(mu300_install_lang)
export MU300_INSTALL_LANG
chmod 755 "$BB" "$P"/*.sh

# Same as customize.sh: Linux goes to the other slot, the TF target is mmcblk1p1 or the whole card.
slot=$(getprop ro.boot.slot_suffix | "$BB" tr -d _)
case $slot in a) linux_slot=b ;; b) linux_slot=a ;; *) fail "无法识别 Android 启动槽位 / Android boot slot is unknown: $slot" ;; esac
bootdev=/dev/block/by-name/boot_$linux_slot
[ -b "$bootdev" ] || fail "找不到 boot_$linux_slot 分区 / boot_$linux_slot was not found"
R=/dev/block/mmcblk1p1
[ -b "$R" ] || R=/dev/block/mmcblk1
[ -b "$R" ] || fail '未找到 TF 卡 / TF card was not found'
KERNEL=$("$BB" sed -n 's/^KERNEL=\([0-9][0-9.]*\)$/\1/p' "$P/customize.sh")
case $KERNEL in ''|*[!0-9.]*) fail 'payload/customize.sh 中没有 KERNEL' ;; esac

umask 077
cat > "$P/mu300-install.env" <<EOF
SD_MODE=1
SD_DEV=$R
FORMAT=1
SYNC_BOOTCHAIN=${MU300_SYNC_BOOTCHAIN:-1}
OSES="openwrt"
WIPE_LEGACY=0
UPDATE=0
BOOT_OS=openwrt
DEFAULT_LINUX=1
BOOT_ATTEMPTS=5
IMPORT_HOTSPOT=1
KERNEL=$KERNEL
PWHASH=''
EOF
chmod 600 "$P/mu300-install.env"
mkdir -p "$T"

run_worker() (
    umask 022
    MU300_PAYLOAD_DIR="$P" MU300_INSTALL_TMP="$T" \
    MU300_VENDOR_FROM_DEVICE=1 MU300_KEEP_PAYLOAD=1 MU300_BUSYBOX="$BB" \
    MU300_TF_BOOTDEV="$bootdev" MAGISKTMP=${MAGISKTMP:-/data/adb/magisk} ASH_STANDALONE=1 \
        "$BB" nsenter -t 1 -m "$BB" sh "$P/tf-install-worker.sh" "$@"
)

case ${1:-} in
    check)
        echo '[检查] 运行安装包自带预检（tf-install-worker.sh --preflight，只读，不写分区）'
        BIN=$("$BB" mktemp -d "$T/apk-check-bin.XXXXXX")
        trap '"$BB" rm -rf "$BIN"' EXIT
        "$BB" --install -s "$BIN"
        rc=0
        MU300_TF_BIN=$BIN run_worker --preflight || rc=$?
        [ "$rc" = 0 ] || { echo "MU300-APK-PREFLIGHT-RC=$rc"; exit "$rc"; }
        card=/dev/block/mmcblk1
        cardname=$(cat /sys/class/block/mmcblk1/device/name 2>/dev/null || echo unknown)
        echo "OWRT-APK-SLOTS=$slot,$linux_slot"
        echo "OWRT-APK-TARGET=$R"
        echo "OWRT-APK-TARGET-BYTES=$("$BB" blockdev --getsize64 "$R")"
        echo "OWRT-APK-CARD=$card $cardname $("$BB" blockdev --getsize64 "$card")"
        echo "OWRT-APK-FREE-KB=$("$BB" df -Pk "$T" | "$BB" awk 'END {print $4}')"
        echo 'OWRT-APK-CHECK-OK'
        ;;
    install)
        echo "- 正在将 OpenWrt 根文件系统安装到 $R（Linux 写入 boot_$linux_slot）"
        run_worker
        echo '- 安装完成；重启后进入 OpenWrt。如果 Linux 启动失败，设备会自动返回 Android'
        ;;
    *) fail 'usage: mu300-lib.sh check|install' ;;
esac
