#!/system/bin/sh
# userdata 安装方式（APK 自带，按电脑端 OpenWrt-Installer.py 的 install_part / install 流程移植）。
#
#   userdata-lib.sh check part            只读：缩容 userdata 安装的预检，输出可选分区大小
#   userdata-lib.sh check image           只读：userdata 镜像安装【实验性】的预检，输出可选镜像大小
#   userdata-lib.sh install part MIB GROW 缩容 userdata 安装：落位载荷、同步启动链、写 boot、设置 boot-os=part、
#                                         试启动槽。缩容/建分区/写入由安装包 boot.img 的 initramfs
#                                         (etc/install-part.sh) 在下次启动时离线完成。
#   userdata-lib.sh install image MIB     userdata 镜像安装：/data/e5linux/openwrt.ext4（安装包 userdata-image.sh）
#   userdata-lib.sh tool SUB ...          格式化 userdata 两阶段安装与重装已有 e5root 用的单步操作（流程由 APK 里的
#                                         E5Part 按电脑端 scripts/installer/part-two-stage.py 驱动）：
#                                         preflight | firmware | bootchain-sync | arm | part-write DEV NEED RAW GROW TOKEN
#
# 只调用安装包自己的函数和脚本：以库方式载入 files/magisk-tf-install.sh（E5_TF_LIB=1）取得
# prepare_firmware / write_boot；启动链用 files/android-bootchain.sh；试启动用 files/e5-bootctl。
# 加密 userdata（或用户选择格式化）时的两阶段安装由 E5Part 驱动，这里只提供 tool 单步。
# 由 owrt-apk.sh 在全局挂载命名空间、随包 BusyBox（ASH_STANDALONE=1）下运行。
set -eu
P=${E5_PACKAGE:?}
BB=$P/files/busybox
ACTION=${1:-}
METHOD=${2:-}
case "$ACTION/$METHOD" in
    check/part|check/image|install/part|install/image) ;;
    tool/preflight|tool/firmware|tool/bootchain-sync|tool/arm|tool/part-write) ;;
    *) echo "OWRT-APK-ERROR: usage" >&2; exit 2 ;;
esac
D=/data/e5linux
TMPD=/data/local/tmp
MIB=1048576
DATA_RESERVE_MIB=1024           # 电脑端 DATA_RESERVE
DATA_FIRMWARE_BUDGET_MIB=512    # 电脑端 DATA_FIRMWARE_BUDGET

# shellcheck disable=SC2034
E5_TF_LIB=1  # read by the sourced package script (library mode)
. "$P/files/magisk-tf-install.sh"
fail() { echo "E5-DATA-ERROR: $*" >&2; exit 1; }

mib_up() { echo $(( ($1 + MIB - 1) / MIB )); }

# 与电脑端 android_checks + install_preflight 对应，和 TF 预检相同的部分保持原样，去掉 TF 卡识别。
preflight_common() {
    cd "$P"
    sha256sum -c files/SHA256SUMS > "$STATE/package-check.log"
    . "$P/files/tf-install.env"
    [ "$(id -u)" = 0 ] || fail 'root required'
    [ "$(getprop ro.product.model)" = "$ANDROID_MODEL" ] || fail 'wrong device model for this package'
    case "$(getprop ro.boot.slot_suffix)" in
        _a) ANDROID_SLOT=a; LINUX_SLOT=b ;; _b) ANDROID_SLOT=b; LINUX_SLOT=a ;;
        *) fail 'Android slot unavailable' ;;
    esac
    [ "$ANDROID_SLOT" != b ] || [ "${SLOT_ROLES_VERSION:-0}" -ge 1 ] || fail 'package initramfs lacks dynamic-slot support'
    case "${PROFILE:-}" in
        t512n) ;;
        yuanhang41s) [ "$(getprop ro.product.device)" = P720S11 ] || fail 'unknown 41S board' ;;
        k1)
            [ "$(getprop ro.product.device)" = ums9157_2h10 ] || fail 'unknown K1 board'
            [ "$(getprop ro.boot.verifiedbootstate)" = orange ] || fail 'bootloader must be unlocked'
            ;;
        *) [ "$(getprop ro.boot.verifiedbootstate)" = orange ] || fail 'bootloader must be unlocked' ;;
    esac
    [ -s "$P/files/ext4-tools.tar" ] || fail 'missing bundled ext4 tools'
    tar -tf "$P/files/ext4-tools.tar" > "$STATE/ext4-tools.list"
    for tool in lib/ld-musl-aarch64.so.1 usr/sbin/e2fsck usr/sbin/resize2fs ext4-geometry.sh SHA256SUMS; do
        grep -qx "$tool" "$STATE/ext4-tools.list" || fail "missing ext4 tool: $tool"
    done
    [ ! -L "$D" ] || fail 'unsafe boot selector directory'
    for selector in boot-os boot-os-next openwrt-default-boot; do
        [ ! -L "$D/$selector" ] || fail 'unsafe boot selector'
    done
    BOOT=/dev/block/by-name/boot_$LINUX_SLOT
    [ -b "$BOOT" ] && [ -b /dev/block/by-name/misc ] || fail 'missing boot partitions'
    [ -e "/dev/block/by-name/l_agdsp_$ANDROID_SLOT" ] || fail 'Android audio firmware partition missing'
    [ "$(blockdev --getsize64 "$BOOT")" = "$BOOT_BYTES" ] || fail 'boot partition size mismatch'
    [ $((RAW_BYTES % MIB)) = 0 ] || fail 'image size is not MiB aligned'
    "$P/files/e5-bootctl" check "$ANDROID_SLOT"
    sh "$P/files/android-bootchain.sh" check
    # 本机固件转换会临时解开完整镜像（与 TF 方式相同）。
    free_kb=$(df -Pk "$STATE" | awk 'END {print $4}')
    [ "$free_kb" -ge $((RAW_BYTES / 1024 + BOOT_BYTES / 1024 + 262144)) ] || fail 'insufficient Android temporary space (raw image + boot backup + 256 MiB required)'
}

# 电脑端 part_userdata_policy / check_userdata_image_source：加密或映射的 /data 不做保数据缩容。
data_plain() {
    CRYPTO_STATE=$(getprop ro.crypto.state)
    CRYPTO_TYPE=$(getprop ro.crypto.type)
    DATA_SOURCE=$(awk '$2 == "/data" {print $1; exit}' /proc/mounts)
    echo "OWRT-APK-CRYPTO=${CRYPTO_STATE:-unknown}/${CRYPTO_TYPE:-none} ${DATA_SOURCE:-?}"
    case "$CRYPTO_TYPE" in file|block) return 1 ;; esac
    [ "$CRYPTO_STATE" = unencrypted ] || return 1
    case "$DATA_SOURCE" in /dev/block/dm-*|/dev/mapper/*) return 1 ;; /dev/block/*) ;; *) return 1 ;; esac
    return 0
}

# 电脑端 part_userdata_policy：state == encrypted、type 为 file/block，或 /data 来自 dm-/mapper 映射，都算加密。
# 加密时保留数据的缩容不可行，只能走“格式化 userdata 两阶段安装”（E5Part）。
data_encrypted() {
    CRYPTO_STATE=$(getprop ro.crypto.state)
    CRYPTO_TYPE=$(getprop ro.crypto.type)
    DATA_SOURCE=$(awk '$2 == "/data" {print $1; exit}' /proc/mounts)
    echo "OWRT-APK-CRYPTO=${CRYPTO_STATE:-unknown}/${CRYPTO_TYPE:-none} ${DATA_SOURCE:-?}"
    [ "$CRYPTO_STATE" = encrypted ] && return 0
    case "$CRYPTO_TYPE" in file|block) return 0 ;; esac
    case "$DATA_SOURCE" in /dev/block/dm-*|/dev/mapper/*) return 0 ;; esac
    return 1
}

# 输出两套大小范围：保留数据缩容（KEEP，F2FS 且未加密才可用）和格式化 userdata（WIPE，两阶段）。
# 电脑端 part_target：min = 镜像 MiB + 256；max = userdata MiB - (格式化时 0，否则已用 MiB) - 2048。
preflight_part() {
    preflight_common
    ENCRYPTED=0
    if data_encrypted; then ENCRYPTED=1; fi
    echo "OWRT-APK-ENCRYPTED=$ENCRYPTED"
    DATA_NODE='' DATA_SECTORS='' EXISTING=''
    for u in /sys/class/block/mmcblk*p*/uevent; do
        n=${u%/uevent}; n=${n##*/}
        if grep -qx PARTNAME=userdata "$u" 2>/dev/null; then DATA_NODE=$n; DATA_SECTORS=$(cat "/sys/class/block/$n/size"); fi
        if grep -qx PARTNAME=e5root "$u" 2>/dev/null; then EXISTING=$n; fi
    done
    [ -z "$EXISTING" ] || fail "the internal partition $EXISTING (e5root) already exists; choose the method 'reinstall the existing internal partition' to write a new system into it (userdata and the partition table stay, nothing is erased). Nothing was written."
    case "$DATA_SECTORS" in ''|*[!0-9]*|0) fail 'userdata is not in the partition table of this device' ;; esac
    used_kb=$(df -Pk /data | awk 'END {print $3}')
    USED_MIB=$((used_kb / 1024))
    UDATA_MIB=$((DATA_SECTORS / 2048))
    IMAGE_MIB=$(mib_up "$RAW_BYTES")
    echo "OWRT-APK-DATA-NODE=/dev/block/$DATA_NODE"
    echo "OWRT-APK-UDATA-MIB=$UDATA_MIB"
    echo "OWRT-APK-USED-MIB=$USED_MIB"
    # 格式化 userdata（两阶段）：不受已用空间限制。
    WIPE_MIN=$((IMAGE_MIB + 256))
    WIPE_MAX=$((UDATA_MIB - 2048))
    WIPE_DEFAULT=$(( ( (IMAGE_MIB + 1024 + 1023) / 1024 ) * 1024 ))
    [ "$WIPE_DEFAULT" -le "$WIPE_MAX" ] || WIPE_DEFAULT=$WIPE_MAX
    if [ "$WIPE_MAX" -ge "$WIPE_MIN" ]; then
        echo "OWRT-APK-WIPE-RANGE=$WIPE_MIN $WIPE_MAX $WIPE_DEFAULT"
    else
        echo "OWRT-APK-WIPE-NO=not enough room: userdata is $UDATA_MIB MiB, the image needs a $WIPE_MIN MiB partition"
    fi
    KEEP_NO=''
    if [ "$ENCRYPTED" = 1 ]; then
        KEEP_NO='userdata is encrypted (FBE/metadata/block): data cannot be kept; formatting userdata is required'
    # 与安装包 initramfs (etc/install-part.sh) 相同的判断：只有 F2FS 的 userdata 能离线缩容，否则它会放弃。
    # 只读 4 字节超级块魔数，提前拒绝，免得重启后才发现没有安装。
    elif [ "$(od -An -tx4 -j 1024 -N 4 "/dev/block/$DATA_NODE" | tr -d ' \n')" != f2f52010 ]; then
        KEEP_NO='userdata is not F2FS: the package initramfs can only shrink F2FS userdata while keeping data'
    else
        echo "OWRT-APK-DATA-FS=f2fs"
        for f in part-install.conf part-install.img.gz part-install.settings; do
            [ ! -L "$D/$f" ] || fail 'unsafe staged install path'
        done
        if [ -e "$D/part-install.conf" ]; then
            KEEP_NO='a previous shrink install is already pending in /data/e5linux/part-install.conf; inspect it before staging another one'
        fi
    fi
    if [ -z "$KEEP_NO" ]; then
        PART_MIN=$((IMAGE_MIB + 256))
        [ "$PART_MIN" -ge 2048 ] || PART_MIN=2048        # initramfs install-part.sh: PART_MIB >= 2048
        PART_MAX=$((UDATA_MIB - USED_MIB - 2048))
        if [ "$PART_MAX" -ge "$PART_MIN" ]; then
            PART_DEFAULT=$(( ( (IMAGE_MIB + 1024 + 1023) / 1024 ) * 1024 ))
            [ "$PART_DEFAULT" -le "$PART_MAX" ] || PART_DEFAULT=$PART_MAX
            [ "$PART_DEFAULT" -ge "$PART_MIN" ] || PART_DEFAULT=$PART_MIN
            echo "OWRT-APK-PART-RANGE=$PART_MIN $PART_MAX $PART_DEFAULT"
        else
            KEEP_NO="not enough room: userdata is $UDATA_MIB MiB with $USED_MIB MiB used, the image needs a $PART_MIN MiB partition"
        fi
    fi
    [ -z "$KEEP_NO" ] || echo "OWRT-APK-KEEP-NO=$KEEP_NO"
    [ -n "$KEEP_NO" ] && [ "$WIPE_MAX" -lt "$WIPE_MIN" ] && fail "$KEEP_NO; formatting userdata would not fit either (userdata $UDATA_MIB MiB, partition needs $WIPE_MIN MiB)"
    true
}

preflight_image() {
    preflight_common
    data_plain || fail 'userdata image requires confirmed unencrypted, unmapped /data; use TF or the internal installation instead. No image or boot was written.'
    for f in "$D/openwrt.ext4" "$D/openwrt.ext4.part" "$D/openwrt.ext4.new"; do
        [ ! -L "$f" ] || fail 'unsafe image path'
    done
    [ ! -e "$D/openwrt.ext4.part" ] && [ ! -e "$D/openwrt.ext4.new" ] || fail 'a pending image or upgrade exists in /data/e5linux; inspect it before retrying'
    # 电脑端 check：全部按字节计算。free = df 可用 KiB * 1024；uploads = 安装时额外写进 /data 的文件
    # （电脑端是 openwrt.ext4.gz + boot.img；本机安装复制的是 openwrt.ext4.gz + ext4-tools.tar，
    # 另外生成一份绑定槽位的 boot，所以按 gz + boot.img + ext4-tools.tar 计）。
    # minimum = ceil(raw / MiB) MiB；maximum = floor((free - 保留 - 固件预算 - uploads) / MiB) MiB。
    [ -s "$P/files/ext4-tools.tar" ] || fail 'missing bundled ext4 tools'
    free_kb=$(df -Pk /data | awk 'END {print $4}')
    FREE_BYTES=$((free_kb * 1024))
    UPLOAD_BYTES=$(( $(stat -c %s "$P/files/openwrt.ext4.gz") + $(stat -c %s "$P/files/boot.img") + $(stat -c %s "$P/files/ext4-tools.tar") ))
    IMG_MIN=$(mib_up "$RAW_BYTES")
    IMG_MAX=$(( (FREE_BYTES - DATA_RESERVE_MIB * MIB - DATA_FIRMWARE_BUDGET_MIB * MIB - UPLOAD_BYTES) / MIB ))
    [ "$(( FREE_BYTES - DATA_RESERVE_MIB * MIB - DATA_FIRMWARE_BUDGET_MIB * MIB - UPLOAD_BYTES ))" -ge 0 ] || IMG_MAX=-1
    [ "$IMG_MAX" -ge "$IMG_MIN" ] || fail "not enough userdata space for the image and safety reserve (free $((FREE_BYTES / MIB)) MiB, needs $IMG_MIN MiB + 1024 MiB reserve + 512 MiB firmware budget + $(mib_up "$UPLOAD_BYTES") MiB upload)"
    FREE_MIB=$((FREE_BYTES / MIB))
    echo "OWRT-APK-FREE-MIB=$FREE_MIB"
    echo "OWRT-APK-IMAGE-RANGE=$IMG_MIN $IMG_MAX $IMG_MIN"
}

gz_listed() {
    grep -E '^[0-9a-f]{64}  files/openwrt\.ext4\.gz$' "$P/files/SHA256SUMS" | cut -d ' ' -f1
}

# ------------------------------------------------------------------ tool
# 两阶段安装 / 重装已有 e5root 的单步操作。流程顺序、事务记录、启动镜像改写都在 APK（E5Part），
# 与电脑端 part-two-stage.py 一一对应；这里只做必须在目标设备上以 root 完成的部分。
if [ "$ACTION" = tool ]; then
    TBASE=/data/adb/e5-tf-install
    [ ! -L "$TBASE" ] || fail 'unsafe installer directory'
    mkdir -p "$TBASE"
    chmod 700 "$TBASE"
    STATE=$(mktemp -d "$TBASE/tool.XXXXXX")
    chmod 700 "$STATE"
    mkdir "$STATE/bin"
    "$BB" --install -s "$STATE/bin"
    export PATH="$STATE/bin:/system/bin:$PATH"
    LOOP= LOOP_ATTACHED=0 VIEW_MOUNTED=0 heartbeat=
    tool_cleanup() {
        result=$?
        trap - EXIT HUP INT TERM
        [ -z "$heartbeat" ] || { kill -9 "$heartbeat" 2>/dev/null || true; wait "$heartbeat" 2>/dev/null || true; }
        if [ "$VIEW_MOUNTED" = 1 ]; then umount "$STATE/view" || exit 1; fi
        [ "$LOOP_ATTACHED" = 0 ] || losetup -d "$LOOP" || exit 1
        if [ "$METHOD" = part-write ]; then
            rm -f "$TMPD/openwrt.ext4.gz" "$TMPD/e5-ext4-tools.tar" "$TMPD/e5-part-write.sh" \
                  "$TMPD/e5-install.conf" "$TMPD/e5-device-files.tar"
        fi
        if [ "$result" = 0 ]; then rm -rf "$STATE"; else echo "[TOOL] $METHOD FAILED: log $STATE" >&2; fi
        exit "$result"
    }
    trap tool_cleanup EXIT
    trap 'exit 130' HUP INT TERM
    owner=$$
    (elapsed=0; while sleep 10; do kill -0 "$owner" 2>/dev/null || exit 0; elapsed=$((elapsed+10)); echo "[TOOL] $METHOD: ${elapsed}s elapsed"; done) &
    heartbeat=$!
    case "$METHOD" in
        preflight)
            # 电脑端 install_preflight + android_checks 中与 TF 相同的部分
            preflight_common
            ;;
        firmware)
            # 电脑端 pull_device_files：本机固件提取与转换（安装包自己的 prepare_firmware）。
            preflight_common
            prepare_firmware
            [ ! -L "$TMPD/e5-device-files.tar" ] || fail 'unsafe device files path'
            cp "$STATE/device-files.tar" "$TMPD/e5-device-files.tar"
            chmod 600 "$TMPD/e5-device-files.tar"
            echo "OWRT-APK-DEVICE-FILES=$TMPD/e5-device-files.tar"
            ;;
        bootchain-sync)
            # 电脑端 bootchain('sync')
            sh "$P/files/android-bootchain.sh" sync
            ;;
        arm)
            # 电脑端 arm_slot_b：本机用安装包的 e5-bootctl（与 TF / 缩容方式相同），备份在 /data/adb/e5-boot-control。
            case "$(getprop ro.boot.slot_suffix)" in _a) ANDROID_SLOT=a ;; _b) ANDROID_SLOT=b ;; *) fail 'Android slot unavailable' ;; esac
            [ ! -L /data/adb/e5-boot-control ] || fail 'unsafe boot control backup directory'
            mkdir -p /data/adb/e5-boot-control
            backup=$(mktemp /data/adb/e5-boot-control/before.XXXXXX)
            rm -f "$backup"
            "$P/files/e5-bootctl" arm "$backup" "$ANDROID_SLOT"
            echo "Boot control backup: $backup"
            ;;
        part-write)
            # 电脑端 finish() 的上传 + part-write.sh（安装包脚本）；install.conf 与 device-files.tar 已由 APK 放好。
            [ $# -eq 7 ] || fail 'part-write DEV NEED RAW GROW TOKEN'
            case "$3" in /dev/block/mmcblk[0-9]p[0-9]*) ;; *) fail 'part-write: invalid target' ;; esac
            case "${3#/dev/block/mmcblk?p}$4$5" in ''|*[!0-9]*) fail 'part-write: invalid size' ;; esac
            case "$6" in 0|1) ;; *) fail 'part-write: grow flag must be 0 or 1' ;; esac
            case "$7" in *[!0-9a-f]*) fail 'part-write: invalid token' ;; esac
            [ ${#7} -eq 32 ] || fail 'part-write: invalid token'
            [ "$(cat "/sys/class/block/${3#/dev/block/}/partition" 2>/dev/null)" != "" ] || fail 'part-write: target is not a partition'
            grep -qx PARTNAME=e5root "/sys/class/block/${3#/dev/block/}/uevent" || fail 'part-write: target is not e5root'
            [ -s "$TMPD/e5-install.conf" ] && [ -s "$TMPD/e5-device-files.tar" ] || fail 'settings or device files missing'
            want=$(gz_listed)
            [ -n "$want" ] || fail 'image checksum missing'
            for f in openwrt.ext4.gz e5-ext4-tools.tar e5-part-write.sh; do [ ! -L "$TMPD/$f" ] || fail 'unsafe temporary path'; done
            cp "$P/files/openwrt.ext4.gz" "$TMPD/openwrt.ext4.gz"
            [ "$(sha "$TMPD/openwrt.ext4.gz")" = "$want" ] || fail 'image copy mismatch'
            cp "$P/files/ext4-tools.tar" "$TMPD/e5-ext4-tools.tar"
            cp "$P/files/part-write.sh" "$TMPD/e5-part-write.sh"
            ASH_STANDALONE=1 "$BB" sh "$TMPD/e5-part-write.sh" "$3" "$4" "$5" "$6" "$7"
            ;;
    esac
    echo "OWRT-APK-TOOL-OK $METHOD"
    exit 0
fi

if [ "$ACTION" = check ]; then
    STATE=$(mktemp -d /data/adb/owrt-apk/check.XXXXXX)
    trap 'rm -rf "$STATE"' EXIT
    chmod 700 "$STATE"
    mkdir "$STATE/bin"
    "$BB" --install -s "$STATE/bin"
    export PATH="$STATE/bin:/system/bin:$PATH"
    echo "[检查] userdata $METHOD 预检（只读，不写分区）/ read-only preflight"
    "preflight_$METHOD"
    echo "OWRT-APK-SLOTS=$ANDROID_SLOT,$LINUX_SLOT"
    echo "OWRT-APK-METHOD=$METHOD"
    echo 'OWRT-APK-CHECK-OK'
    exit 0
fi

# ------------------------------------------------------------------ install
SIZE=${3:-}
GROW=${4:-1}
case "$SIZE" in ''|*[!0-9]*) fail 'size must be whole MiB' ;; esac
case "$GROW" in 0|1) ;; *) fail 'grow flag must be 0 or 1' ;; esac
BASE=/data/adb/e5-tf-install
[ ! -L "$BASE" ] || fail 'unsafe installer directory'
mkdir -p "$BASE"
chmod 700 "$BASE"
# 与安装包相同的“每次开机一把锁”：失败后同一次开机内不能重试。
lock=$BASE/install-$(cat /proc/sys/kernel/random/boot_id).lock
mkdir "$lock" || fail 'another install is active, or a previous failed install needs inspection'
STATE=$(mktemp -d "$BASE/run.XXXXXX")
chmod 700 "$STATE"
mkdir "$STATE/bin"
"$BB" --install -s "$STATE/bin"
export PATH="$STATE/bin:/system/bin:$PATH"
heartbeat=
LOOP=
LOOP_ATTACHED=0
VIEW_MOUNTED=0
PART_STAGED=0
ARMED=0
cleanup() {
    result=$?
    trap - EXIT HUP INT TERM
    [ -z "$heartbeat" ] || { kill -9 "$heartbeat" 2>/dev/null || true; wait "$heartbeat" 2>/dev/null || true; }
    if [ "$VIEW_MOUNTED" = 1 ]; then umount "$STATE/view" || exit 1; fi
    [ "$LOOP_ATTACHED" = 0 ] || losetup -d "$LOOP" || exit 1
    rm -f "$TMPD/openwrt.ext4.gz" "$TMPD/e5-ext4-tools.tar" "$TMPD/e5-android-loop.sh"
    if [ "$result" = 0 ]; then
        rm -f "$STATE/device-files.tar" "$STATE/install.conf"
        rmdir "$lock"
    else
        # 尚未设置启动目标时撤回缩容请求，避免以后任何一次 OpenWrt 启动意外执行缩容。
        if [ "$PART_STAGED" = 1 ] && [ "$ARMED" = 0 ]; then
            rm -f "$D/part-install.conf" "$D/part-install.img.gz" "$D/part-install.settings"
            sync
            echo "[DATA] staged shrink request withdrawn (boot target was not armed)" >&2
        fi
        echo "[DATA] FAILED: log/backup $STATE; do not retry until checking device state" >&2
    fi
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM
echo "[DATA] Logs and backups / 日志和备份：$STATE"
owner=$$
(elapsed=0; while sleep 10; do kill -0 "$owner" 2>/dev/null || exit 0; elapsed=$((elapsed+10)); echo "[DATA] $(cat "$STATE/stage" 2>/dev/null): ${elapsed}s elapsed"; done) &
heartbeat=$!
dstage() {
    printf '%s\n' "$1" > "$STATE/stage"
    echo "[DATA] $1"
    shift
    "$@"
}

check_size_part() {
    preflight_part
    [ -z "$KEEP_NO" ] || fail "$KEEP_NO. Nothing was written."
    [ "$SIZE" -ge "$PART_MIN" ] && [ "$SIZE" -le "$PART_MAX" ] || fail "partition size outside the permitted range ($PART_MIN-$PART_MAX MiB)"
    DATA_MIB=$((UDATA_MIB - SIZE))
    [ "$DATA_MIB" -ge 2048 ] || fail 'userdata would be smaller than 2 GiB'
    echo "[DATA] userdata $UDATA_MIB MiB -> $DATA_MIB MiB; new partition e5root $SIZE MiB; grow=$GROW; Android files are kept"
}
check_size_image() {
    preflight_image
    [ "$SIZE" -ge "$IMG_MIN" ] && [ "$SIZE" -le "$IMG_MAX" ] || fail "image size outside the permitted range ($IMG_MIN-$IMG_MAX MiB)"
    echo "[DATA] image $D/openwrt.ext4, $SIZE MiB; userdata is not formatted"
}


stage_part() {
    want=$(gz_listed)
    [ -n "$want" ] || fail 'image checksum missing'
    umask 077
    mkdir -p "$D"
    [ ! -L "$D" ] || fail 'unsafe boot selector directory'
    PART_STAGED=1
    cp "$P/files/openwrt.ext4.gz" "$D/part-install.img.gz.tmp"
    [ "$(sha "$D/part-install.img.gz.tmp")" = "$want" ] || fail 'staged image copy mismatch'
    mv -f "$D/part-install.img.gz.tmp" "$D/part-install.img.gz"
    mv -f "$STATE/device-files.tar" "$D/device-files.tar"
    cp "$STATE/install.conf" "$D/part-install.settings"
    printf 'PART_MIB=%s\nDATA_MIB=%s\nGROW_FS=%s\nFORMAT_DATA=0\n' "$SIZE" "$DATA_MIB" "$GROW" > "$D/part-install.conf.tmp"
    mv -f "$D/part-install.conf.tmp" "$D/part-install.conf"
    chmod 600 "$D/part-install.conf" "$D/part-install.settings" "$D/device-files.tar" "$D/part-install.img.gz"
    sync
    # 落位后再检查空间：缩容目标必须仍高于已用空间 + 2 GiB。
    used_kb=$(df -Pk /data | awk 'END {print $3}')
    [ $((used_kb / 1024 + 2048)) -le "$DATA_MIB" ] || fail 'userdata usage grew after staging; the shrink target would no longer fit'
}

write_image() {
    cp "$P/files/android-loop.sh" "$TMPD/e5-android-loop.sh"
    cp "$P/files/ext4-tools.tar" "$TMPD/e5-ext4-tools.tar"
    cp "$P/files/openwrt.ext4.gz" "$TMPD/openwrt.ext4.gz"
    [ "$(sha "$TMPD/openwrt.ext4.gz")" = "$(gz_listed)" ] || fail 'image copy mismatch'
    # 电脑端 ensure_userdata_space：上传和固件收集后按字节复查（上传量记为 0）
    free_kb=$(df -Pk /data | awk 'END {print $4}')
    [ $((SIZE * MIB + DATA_RESERVE_MIB * MIB + DATA_FIRMWARE_BUDGET_MIB * MIB)) -le $((free_kb * 1024)) ] || fail 'userdata free space is insufficient for the new image. Nothing was armed.'
    raw_mib=$((RAW_BYTES / MIB))
    out=$(sh "$P/files/userdata-image.sh" "$RAW_BYTES" "$((SIZE * MIB))" "$raw_mib" "$SIZE" 2>&1) || { echo "$out"; fail 'userdata image creation failed'; }
    echo "$out"
    echo "$out" | grep -q "^USERDATA-IMAGE-OK" || fail 'userdata image was not verified'
}

commit_image() {
    backup=$D/previous.$(date +%Y%m%d-%H%M%S).$$
    if [ -e "$D/openwrt.ext4" ]; then
        mkdir "$backup"
        mv "$D/openwrt.ext4" "$backup/openwrt.ext4"
        for n in device-files.tar openwrt-install.conf; do
            [ ! -e "$D/$n" ] || cp -p "$D/$n" "$backup/$n"
        done
        echo "[DATA] previous image kept: $backup"
    fi
    mv "$D/openwrt.ext4.part" "$D/openwrt.ext4"
    mv -f "$STATE/device-files.tar" "$D/device-files.tar"
    cp "$STATE/install.conf" "$D/openwrt-install.conf"
    chmod 600 "$D/openwrt.ext4" "$D/device-files.tar" "$D/openwrt-install.conf"
    sync
}

arm_target() {
    mkdir -p "$D"
    [ ! -L "$D" ] || fail 'unsafe boot selector directory'
    for name in boot-os boot-os-next; do
        [ ! -L "$D/$name" ] || fail 'unsafe boot selector'
    done
    rm -f "$D/boot-os-next"
    echo "$1" > "$D/boot-os"
    sync
    ARMED=1
    "$P/files/e5-bootctl" arm "$STATE/boot-control.before.bin" "$ANDROID_SLOT"
}

if [ "$METHOD" = part ]; then
    dstage 'Package, model, boot chain, encryption and size checks / 预检' check_size_part
    dstage 'Prepare and convert this device firmware / 提取并转换本机固件' prepare_firmware
    dstage 'Stage the shrink install on userdata / 落位缩容安装载荷' stage_part
    dstage 'Back up and sync boot chain / 备份并同步启动链' sh "$P/files/android-bootchain.sh" sync
    dstage 'Write and verify Linux boot / 写入并校验内核' write_boot
    dstage 'Select OpenWrt (part) / 设置启动目标' arm_target part
    echo complete > "$STATE/stage"
    echo 'E5-PART-STAGED-OK: reboot manually; the next boot shrinks userdata, creates e5root and writes OpenWrt / 请手动重启，下次启动离线缩容并写入'
else
    dstage 'Package, model, boot chain, encryption and space checks / 预检' check_size_image
    dstage 'Prepare and convert this device firmware / 提取并转换本机固件' prepare_firmware
    dstage 'Create, resize and check the userdata image / 创建、扩容并检查镜像' write_image
    dstage 'Back up and sync boot chain / 备份并同步启动链' sh "$P/files/android-bootchain.sh" sync
    dstage 'Commit userdata image / 提交镜像' commit_image
    dstage 'Write and verify Linux boot / 写入并校验内核' write_boot
    dstage 'Select OpenWrt (userdata image) / 设置启动目标' arm_target openwrt
    echo complete > "$STATE/stage"
    echo 'E5-DATA-INSTALL-OK: reboot manually / 请手动重启'
fi
