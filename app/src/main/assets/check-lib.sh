#!/system/bin/sh
# 只读预检（APK 自带）：以库方式载入安装包的 files/magisk-tf-install.sh
# （E5_TF_LIB=1 时该脚本只定义函数、不执行安装），然后只调用其 preflight()。
# preflight 只做包校验、型号/槽位/解锁/启动控制块/启动链 check、TF 卡识别和空间检查，
# 不写分区、不清卡、不切槽。由 owrt-apk.sh check 在全局挂载命名空间中运行。
set -eu
P=${E5_PACKAGE:?}
BB=$P/files/busybox
STATE=$("$BB" mktemp -d /data/adb/owrt-apk/check.XXXXXX)
cleanup_check() { "$BB" rm -rf "$STATE"; }
trap cleanup_check EXIT
"$BB" chmod 700 "$STATE"
"$BB" mkdir "$STATE/bin"
"$BB" --install -s "$STATE/bin"
export PATH="$STATE/bin:/system/bin:$PATH"
# shellcheck disable=SC2034
E5_TF_LIB=1  # read by the sourced package script (library mode)
. "$P/files/magisk-tf-install.sh"
echo '[检查] 运行安装包自带预检（只读，不写分区）/ package preflight, read-only'
preflight
echo "OWRT-APK-SLOTS=$ANDROID_SLOT,$LINUX_SLOT"
echo "OWRT-APK-TARGET=$PART"
echo "OWRT-APK-TARGET-BYTES=$(blockdev --getsize64 "$PART")"
card=${SD##*/}
cardname=$(cat "/sys/class/block/$card/device/name" 2>/dev/null || echo unknown)
echo "OWRT-APK-CARD=$SD $cardname $(blockdev --getsize64 "$SD")"
echo "OWRT-APK-FREE-KB=$(df -Pk "$STATE" | awk 'END {print $4}')"
echo 'OWRT-APK-CHECK-OK'
