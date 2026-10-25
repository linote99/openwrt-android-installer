#!/system/bin/sh
# OpenWrt 安装器 APK 自带的 root 辅助脚本（属于 APK，不来自安装包）。
# 它只做暂存、调用安装包自己的设备端脚本（E5 系列 files/magisk-tf-install.sh；
# MU300 系列经 mu300-lib.sh 调用 payload/tf-install-worker.sh），以及记录/跟踪日志；
# 分区写入全部由安装包脚本按原逻辑完成。
#
#   owrt-apk.sh helpers         只更新 APK 自带的助手脚本（暂存包完整、助手来自旧版时）
#   owrt-apk.sh stage SRC_DIR   把 APK 已校验的安装文件移入 /data/adb/owrt-apk/pkg
#                               （本机：APK 私有目录；局域网设备：无线 ADB 推送到 /data/local/tmp/owrt-apk-stage）
#   owrt-apk.sh check           只运行安装包的 preflight()（只读预检，TF 卡）
#   owrt-apk.sh check-data part|image   userdata 两种方式的只读预检（userdata-lib.sh check）
#   owrt-apk.sh start [tf | part MIB GROW | image MIB]   后台启动安装，并跟踪日志
#   owrt-apk.sh e5-tool SUB ...  两阶段安装 / 重装已有 e5root 的单步（userdata-lib.sh tool）
#   owrt-apk.sh follow          重新跟踪正在进行/上次的安装日志
#   owrt-apk.sh status          显示暂存与任务状态
#   owrt-apk.sh clean           删除暂存的安装文件（不动安装器日志与启动链备份）
umask 077
BASE=/data/adb/owrt-apk
P=$BASE/pkg
JOB=$BASE/job
APP_ID=top.linote.owrtinstaller
REMOTE_STAGE=/data/local/tmp/owrt-apk-stage
HELPERS='owrt-apk.sh check-lib.sh userdata-lib.sh mu300-lib.sh'

say() { echo "OWRT-APK: $*"; }
die() { echo "OWRT-APK-ERROR: $*"; exit 1; }

[ "$(id -u)" = 0 ] || die 'root required / 需要 root'

safe_base() {
    [ ! -L "$BASE" ] || die 'unsafe /data/adb/owrt-apk (symlink)'
    mkdir -p "$BASE" || die 'cannot create /data/adb/owrt-apk'
    chmod 700 "$BASE"
}

job_running() {
    [ -d "$JOB" ] || return 1
    [ ! -e "$JOB/rc" ] || return 1
    pid=$(cat "$JOB/pid" 2>/dev/null) || return 1
    case $pid in ''|*[!0-9]*) return 1 ;; esac
    kill -0 "$pid" 2>/dev/null
}

# Package layout: "mu300" (payload/, desktop/manifest.json) or "e5" (files/SHA256SUMS).
layout_of() {
    if [ -f "$1/OWRT-APK-LAYOUT" ] && [ "$(cat "$1/OWRT-APK-LAYOUT")" = mu300 ]; then echo mu300; else echo e5; fi
}
has_pkg() {
    case $(layout_of "$P") in
        # 安装成功后会删除大文件（与安装包 customize.sh 相同），这时不再算“已暂存”，APK 会重新暂存。
        mu300) [ -f "$P/payload/tf-install-worker.sh" ] && [ -f "$BASE/mu300-lib.sh" ] && \
               [ -f "$P/payload/mu300-openwrt.tar.gz" ] && [ -f "$P/payload/boot-linux.img" ] ;;
        *) [ -f "$P/files/magisk-tf-install.sh" ] && [ -f "$P/files/openwrt.ext4.gz" ] && [ -f "$P/files/boot.img" ] ;;
    esac
}
fingerprint() {
    case $(layout_of "$P") in
        mu300) f=$P/desktop/manifest.json ;;
        *) f=$P/files/SHA256SUMS.full ;;
    esac
    if [ -f "$f" ]; then sha256sum "$f" | cut -d ' ' -f1; else echo none; fi
}
bb_path() {
    case $(layout_of "$P") in mu300) echo "$P/payload/busybox" ;; *) echo "$P/files/busybox" ;; esac
}

# The same bundled-BusyBox checks as the package's own customize.sh.
validate_bb() {
    BB=$(bb_path)
    [ -f "$BB" ] && [ ! -L "$BB" ] || die '缺少随包安装工具 / bundled BusyBox missing'
    [ "$(uname -m)" = aarch64 ] || die '需要 ARM64 内核 / ARM64 kernel required'
    chmod 700 "$BB"
    list=$("$BB" --list) || die '随包安装工具无法运行 / bundled BusyBox cannot run'
    if [ "$(layout_of "$P")" = mu300 ]; then
        tools='sh nsenter setsid timeout kill sha256sum dd od blockdev tar mktemp stat truncate du find df awk tr'
    else
        tools='sh chroot nsenter timeout sha256sum dd od blockdev tar gzip losetup mount umount mktemp stat readlink'
    fi
    for tool in $tools; do
        echo "$list" | "$BB" grep -qx "$tool" || die "安装工具缺少功能 / applet missing: $tool"
    done
    ASH_STANDALONE=1 "$BB" sh -c 'PATH=/nonexistent; sha256sum "$1" >/dev/null' sh "$BB" || \
        die '随包安装工具不兼容 / bundled BusyBox is incompatible'
    if echo "$list" | "$BB" grep -qx setsid; then SETSID="$BB setsid"
    elif command -v setsid >/dev/null 2>&1; then SETSID=setsid
    else SETSID=; fi
    if echo "$list" | "$BB" grep -qx tail; then TAIL="$BB tail"; else TAIL="tail"; fi
}

pick_tail() {
    b=$(bb_path)
    if [ -x "$b" ] && "$b" --list 2>/dev/null | grep -qx tail; then
        TAIL="$b tail"
    else
        TAIL="tail"
    fi
}

cmd_stage() {
    SRC=${1:-}
    case "$SRC" in
        /data/user/[0-9]*/"$APP_ID"/files/stage/pkg|/data/data/"$APP_ID"/files/stage/pkg) ;;
        "$REMOTE_STAGE"/pkg) [ -d "$REMOTE_STAGE" ] && [ ! -L "$REMOTE_STAGE" ] || die 'unsafe remote stage directory' ;;
        *) die "unexpected source directory: $SRC" ;;
    esac
    [ -d "$SRC" ] && [ ! -L "$SRC" ] || die 'staged source missing'
    lay=$(layout_of "$SRC")
    if [ "$lay" = mu300 ]; then
        [ -f "$SRC/desktop/manifest.json" ] && [ -f "$SRC/OWRT-APK-SUMS" ] || die 'checksum lists missing'
        [ -f "$SRC/payload/tf-install-worker.sh" ] || die 'staged source is not an installer package'
    else
        [ -f "$SRC/files/SHA256SUMS.full" ] && [ -f "$SRC/files/SHA256SUMS" ] || die 'checksum lists missing'
        [ -f "$SRC/files/magisk-tf-install.sh" ] || die 'staged source is not an installer package'
    fi
    safe_base
    ! job_running || die '有安装任务正在进行 / an install job is running'
    rm -rf "$BASE/pkg.new" "$BASE/pkg.old"
    if ! mv "$SRC" "$BASE/pkg.new"; then
        rm -rf "$BASE/pkg.new"
        die 'cannot move package into /data/adb/owrt-apk'
    fi
    rm -rf "$SRC"
    chown -R 0:0 "$BASE/pkg.new" || die 'chown failed'
    chmod -R go-rwx "$BASE/pkg.new"
    if [ -e "$P" ]; then mv "$P" "$BASE/pkg.old" || die 'cannot replace old package'; fi
    mv "$BASE/pkg.new" "$P" || die 'cannot activate package'
    rm -rf "$BASE/pkg.old"
    install_helpers
    # Every subset line must appear in the package's own manifest (whose fingerprint the
    # APK compares), so the subset list cannot be altered on its own.
    if [ "$lay" = mu300 ]; then
        sums=OWRT-APK-SUMS
        while IFS= read -r line || [ -n "$line" ]; do
            [ -n "$line" ] || continue
            h=${line%%  *}; f=${line#*  }
            case $h in *[!0-9a-f]*|'') die 'subset list malformed' ;; esac
            [ ${#h} = 64 ] && [ "$f" != "$line" ] || die 'subset list malformed'
            grep -Fq -- "\"$f\": \"$h\"" "$P/desktop/manifest.json" || die '安装文件清单与安装包不一致 / subset list mismatch'
        done < "$P/$sums"
    else
        sums=files/SHA256SUMS
        while IFS= read -r line || [ -n "$line" ]; do
            [ -n "$line" ] || continue
            grep -Fxq -- "$line" "$P/files/SHA256SUMS.full" || die '安装文件清单与安装包不一致 / subset list mismatch'
        done < "$P/files/SHA256SUMS"
    fi
    (cd "$P" && sha256sum -c "$sums" >/dev/null 2>&1) || die '暂存后校验失败 / staged checksum mismatch'
    restorecon -R "$BASE" >/dev/null 2>&1 || true
    say "staged to $P"
    echo "OWRT-APK-STAGED=$(fingerprint)"
}

install_helpers() {
    here=${0%/*}
    [ "$here" != "$BASE" ] || return 0
    for f in $HELPERS; do
        cp "$here/$f" "$BASE/$f.new" && chown 0:0 "$BASE/$f.new" && chmod 700 "$BASE/$f.new" && \
            mv -f "$BASE/$f.new" "$BASE/$f" || die "cannot install helper $f"
    done
}
# 只更新 APK 自带的助手脚本（暂存包完整、但目标设备上的助手来自旧版 APK 时）。
cmd_helpers() {
    safe_base
    ! job_running || die '有安装任务正在进行 / an install job is running'
    [ "${0%/*}" != "$BASE" ] || die 'run helpers from the uploaded copy'
    mkdir -p "$BASE" && chmod 700 "$BASE"
    install_helpers
    restorecon -R "$BASE" >/dev/null 2>&1 || true
    echo 'OWRT-APK-HELPERS-OK'
}

cmd_check() {
    has_pkg || die '没有暂存的安装包 / no staged package'
    ! job_running || die '有安装任务正在进行 / an install job is running'
    validate_bb
    if [ "$(layout_of "$P")" = mu300 ]; then
        "$BB" timeout -k 10 900 "$BB" env OWRT_PACKAGE="$P" ASH_STANDALONE=1 "$BB" sh "$BASE/mu300-lib.sh" check
        rc=$?
        echo "OWRT-APK-CHECK-RC=$rc"
        return 0
    fi
    [ -f "$BASE/check-lib.sh" ] || die '没有暂存的安装包 / no staged package'
    chmod 755 "$P/files/"*.sh "$P/files/e5-bootctl"
    "$BB" timeout -k 10 900 "$BB" nsenter -t 1 -m "$BB" env E5_PACKAGE="$P" ASH_STANDALONE=1 \
        "$BB" sh "$BASE/check-lib.sh"
    rc=$?
    echo "OWRT-APK-CHECK-RC=$rc"
    return 0
}

cmd_check_data() {
    case ${1:-} in part|image) ;; *) die 'check-data part|image' ;; esac
    [ "$(layout_of "$P")" != mu300 ] || die 'MU300 安装包只支持 TF 卡安装 / MU300 packages support TF install only'
    [ -f "$P/files/magisk-tf-install.sh" ] && [ -f "$BASE/userdata-lib.sh" ] || die '没有暂存的安装包 / no staged package'
    ! job_running || die '有安装任务正在进行 / an install job is running'
    validate_bb
    chmod 755 "$P/files/"*.sh "$P/files/e5-bootctl"
    "$BB" timeout -k 10 900 "$BB" nsenter -t 1 -m "$BB" env E5_PACKAGE="$P" ASH_STANDALONE=1 \
        "$BB" sh "$BASE/userdata-lib.sh" check "$1"
    rc=$?
    echo "OWRT-APK-CHECK-RC=$rc"
    return 0
}
# 两阶段 / 重装用的单步（userdata-lib.sh tool），在全局挂载命名空间、随包 BusyBox 下运行。
cmd_e5_tool() {
    case ${1:-} in preflight|firmware|bootchain-sync|arm|part-write) ;; *) die 'e5-tool preflight|firmware|bootchain-sync|arm|part-write' ;; esac
    [ "$(layout_of "$P")" != mu300 ] || die 'not an E5-family package'
    [ -f "$P/files/magisk-tf-install.sh" ] && [ -f "$BASE/userdata-lib.sh" ] || die '没有暂存的安装包 / no staged package'
    ! job_running || die '有安装任务正在进行 / an install job is running'
    validate_bb
    chmod 755 "$P/files/"*.sh "$P/files/e5-bootctl"
    "$BB" timeout -k 10 2400 "$BB" nsenter -t 1 -m "$BB" env E5_PACKAGE="$P" ASH_STANDALONE=1 \
        "$BB" sh "$BASE/userdata-lib.sh" tool "$@"
    rc=$?
    echo "OWRT-APK-TOOL-RC=$rc"
    return 0
}
valid_method() {
    case "${1:-tf}" in
        tf) [ $# -le 1 ] ;;
        part) [ $# = 3 ] && case "$2$3" in *[!0-9]*) false ;; *) [ "$3" = 0 ] || [ "$3" = 1 ] ;; esac ;;
        image) [ $# = 2 ] && case "$2" in ''|*[!0-9]*) false ;; *) true ;; esac ;;
        *) false ;;
    esac
}
cmd_start() {
    valid_method "$@" || die 'start [tf | part MIB GROW | image MIB]'
    has_pkg || die '没有暂存的安装包 / no staged package'
    ! job_running || die '已有安装任务正在进行 / already running'
    validate_bb
    if [ "$(layout_of "$P")" = mu300 ]; then
        [ "${1:-tf}" = tf ] || die 'MU300 安装包只支持 TF 卡安装 / MU300 packages support TF install only'
        [ -f "$P/payload/mu300-openwrt.tar.gz" ] && [ -f "$P/payload/boot-linux.img" ] || \
            die '暂存包缺少镜像（可能已安装过），请重新选择 ZIP / payload missing, reselect the ZIP'
    else
        [ -f "$P/files/openwrt.ext4.gz" ] && [ -f "$P/files/boot.img" ] || \
            die '暂存包缺少镜像（可能已安装过），请重新选择 ZIP / payload missing, reselect the ZIP'
    fi
    rm -rf "$BASE/job.prev"
    [ ! -d "$JOB" ] || mv "$JOB" "$BASE/job.prev"
    mkdir "$JOB" || die 'cannot create job directory'
    : > "$JOB/log"
    if [ -n "$SETSID" ]; then
        $SETSID /system/bin/sh "$BASE/owrt-apk.sh" run-install "$@" < /dev/null >> "$JOB/log" 2>&1 &
    else
        nohup /system/bin/sh "$BASE/owrt-apk.sh" run-install "$@" < /dev/null >> "$JOB/log" 2>&1 &
    fi
    echo $! > "$JOB/pid"
    say "安装任务已在后台启动 / job started (pid $!)"
    cmd_follow
}

# Runs detached. Mirrors the package's customize.sh: same BusyBox checks,
# same global mount namespace, same 1800 s limit, same script.
cmd_run_install() {
    rc=1
    trap 'echo "$rc" > "$JOB/rc.tmp"; mv -f "$JOB/rc.tmp" "$JOB/rc"' EXIT
    trap '' HUP
    say "安装开始 / install started: $(date)"
    lay=$(layout_of "$P")
    if [ "$lay" = mu300 ]; then
        say "安装包 / package: $(grep -o '"package_name": *"[^"]*"' "$P/desktop/release.json" 2>/dev/null | cut -d '"' -f4)"
    else
        say "安装包 / package: $(cat "$P/files/VERSION" 2>/dev/null)"
    fi
    method=${1:-tf}
    say "安装方式 / method: $*"
    valid_method "$@" || die 'bad method'
    validate_bb
    if [ "$lay" = mu300 ]; then
        [ "$method" = tf ] && [ -f "$BASE/mu300-lib.sh" ] || die 'MU300 安装包只支持 TF 卡安装'
        # Same as Magisk running payload/customize.sh (no extra outer limit; every
        # worker stage has its own timeout).
        OWRT_PACKAGE="$P" ASH_STANDALONE=1 "$BB" sh "$BASE/mu300-lib.sh" install
        rc=$?
        if [ "$rc" = 0 ]; then
            rm -f "$P/payload/mu300-openwrt.tar.gz" "$P/payload/boot-linux.img"
            echo 'OWRT-APK-INSTALL-OK'
        else
            echo "OWRT-APK-INSTALL-FAILED rc=$rc；请保留 /data/local/tmp/mu300-tf-install 日志，重启 Android 后再试"
        fi
        exit "$rc"
    fi
    chmod 755 "$P/files/"*.sh "$P/files/e5-bootctl"
    if [ "$method" = tf ]; then
        "$BB" timeout -k 10 1800 "$BB" nsenter -t 1 -m "$BB" env E5_PACKAGE="$P" ASH_STANDALONE=1 \
            "$BB" sh "$P/files/magisk-tf-install.sh"
        rc=$?
    else
        [ -f "$BASE/userdata-lib.sh" ] || die 'userdata helper missing'
        shift
        "$BB" timeout -k 10 3600 "$BB" nsenter -t 1 -m "$BB" env E5_PACKAGE="$P" ASH_STANDALONE=1 \
            "$BB" sh "$BASE/userdata-lib.sh" install "$method" "$@"
        rc=$?
    fi
    if [ "$rc" = 0 ]; then
        # Same as customize.sh: bulk payload is not kept after deployment.
        rm -f "$P/files/openwrt.ext4.gz" "$P/files/boot.img"
        echo 'OWRT-APK-INSTALL-OK'
    else
        echo "OWRT-APK-INSTALL-FAILED rc=$rc；保留 /data/adb/e5-tf-install 日志和备份，检查前不要重试"
    fi
    exit "$rc"
}

cmd_follow() {
    [ -d "$JOB" ] || { echo 'OWRT-APK-JOB=none'; return 0; }
    pick_tail
    $TAIL -n +1 -f "$JOB/log" &
    t=$!
    lost=0
    while [ ! -e "$JOB/rc" ]; do
        if job_running; then lost=0; else lost=$((lost + 1)); [ "$lost" -lt 3 ] || break; fi
        sleep 1
    done
    sleep 2
    kill "$t" 2>/dev/null
    wait "$t" 2>/dev/null
    echo
    if [ -e "$JOB/rc" ]; then echo "OWRT-APK-RC=$(cat "$JOB/rc")"; else echo 'OWRT-APK-RC=lost'; fi
}

cmd_status() {
    if has_pkg; then
        echo "OWRT-APK-STAGED=$(fingerprint)"
    else
        echo 'OWRT-APK-STAGED=none'
    fi
    if [ ! -d "$JOB" ]; then echo 'OWRT-APK-JOB=none'
    elif [ -e "$JOB/rc" ]; then echo "OWRT-APK-JOB=done $(cat "$JOB/rc")"
    elif job_running; then echo 'OWRT-APK-JOB=running'
    else echo 'OWRT-APK-JOB=lost'; fi
}

cmd_clean() {
    ! job_running || die '有安装任务正在进行 / an install job is running'
    [ ! -L "$BASE" ] || die 'unsafe base'
    rm -rf "$P" "$BASE/pkg.new" "$BASE/pkg.old" "$BASE"/check.*
    say '已删除暂存安装文件；安装器日志 /data/adb/e5-tf-install 与启动链备份 /data/adb/e5-bootchain 保留'
    echo 'OWRT-APK-CLEAN-OK'
}

case ${1:-} in
    stage) cmd_stage "${2:-}" ;;
    check) cmd_check ;;
    helpers) cmd_helpers ;;
    check-data) cmd_check_data "${2:-}" ;;
    e5-tool) shift; cmd_e5_tool "$@" ;;
    start) shift; cmd_start "$@" ;;
    run-install) shift; cmd_run_install "$@" ;;
    follow) cmd_follow ;;
    status) cmd_status ;;
    clean) cmd_clean ;;
    *) die 'usage: owrt-apk.sh stage|helpers|check|check-data|start|follow|status|clean' ;;
esac
