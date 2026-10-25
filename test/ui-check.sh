#!/bin/bash
# Static checks for the 1.5.0 UI rules that cannot run on the desktop JVM (they need Android views):
# the advanced options are hidden until the title is tapped 5 times, unfinished continuation records
# are never hidden, the first-run notice is keyed on the version code, and 使用说明 documents it.
set -u
cd "$(dirname "$0")/.."
J=app/src/main/java/top/linote/owrtinstaller
M=$J/MainActivity.java; A=$J/AboutActivity.java; JM=$J/JobManager.java
pass=0; fail=0
t() { if eval "$2"; then pass=$((pass+1)); echo "ok   $1"; else fail=$((fail+1)); echo "FAIL $1"; fi; }
page=$(sed -n '/private void methodPage(boolean idle)/,/private void methodRow/p' "$M")
t "title tap opens the switch" 'grep -q "title.setOnClickListener(v -> titleTapped())" "$M"'
t "5 taps, hint from the 3rd, 1.5 s window" 'grep -q "int left = 5 - titleTaps" "$M" && grep -q "titleTaps >= 3" "$M" && grep -q "now - lastTitleTap > 1500" "$M"'
t "switch state persisted" 'grep -q "putBoolean(\"advanced\", on)" "$M" && grep -q "getBoolean(\"advanced\", false)" "$M"'
t "advanced rows only inside advancedOn block" 'echo "$page" | grep -q "if (advancedOn && anyAdvanced)" && [ "$(echo "$page" | grep -c "JobManager.advanced(m) && !(m == JobManager.Method.UNINSTALL && unPending)) methodRow")" = 1 ]'
t "normal rows skip advanced methods (except a pending uninstall)" 'echo "$page" | grep -q "if (JobManager.advanced(m) && !(m == JobManager.Method.UNINSTALL && unPending)) continue;"'
t "continuation is a normal method (not advanced, no 【高级】 tag)" 'grep -q "static boolean advanced(Method m) { return m == Method.IMAGE || m == Method.REPART || m == Method.UNINSTALL; }" "$JM" && ! grep -rq "继续格式化后的安装【高级】" "$J" && grep -q "RESUME(\"继续格式化后的安装\"" "$JM"'
t "使用说明: continuation not under 高级功能" '! sed -n "/section(\"高级功能\")/,/section(\"友情链接\")/p" "$A" | grep -q "• 继续格式化后的安装"'
t "continuation = RESUME" 'grep -q "static boolean continuation(Method m) { return m == Method.RESUME; }" "$JM"'
t "hidden selection is left when switched off" 'grep -q "private void leaveHiddenMethod()" "$M" && echo "$page" | grep -q "leaveHiddenMethod();" && ! grep -q "Method.RESUME && jm.pendingRecord() != null) return;" "$M"'
t "in-page off button" 'echo "$page" | grep -q "关闭高级功能"'
t "pending records read both families" 'grep -q "\"e5-part\", \"phase\", \"complete\"" "$JM" && grep -q "\"mu300-backup\", \"state\", \"installed\"" "$JM"'
t "first-run notice keyed on version code" 'grep -q "getInt(\"intro_dismissed_code\", -1) == BuildInfo.VERSION_CODE" "$M" && grep -q "if (state == null) maybeIntro();" "$M"'
t "first-run notice: never-again box and About link" 'grep -q "不再提示" "$M" && grep -q "AboutActivity.class" "$M"'
t "使用说明 has 高级功能 section before links" 'awk "/section\(\"高级功能\"\)/{a=NR} /section\(\"友情链接\"\)/{b=NR} END{exit !(a && b && a<b)}" "$A"'
t "使用说明 explains on/off by 5 taps" 'grep -q "连点页面顶部的“OpenWrt 安装器”标题 5 下" "$A" && grep -q "再连点标题 5 下" "$A"'
t "uninstall entry is tagged advanced" 'grep -q "UNINSTALL(\"卸载并归还空间【高级】\"" "$JM"'
t "advanced list matches desktop (reinstall + image)" '[ "$(grep -c "【高级】\|【实验性】" "$JM")" -ge 4 ] && grep -q "static boolean advanced(Method m)" "$JM"'
# v1.5.2: after the formatting first stage, every entry point names the re-setup steps (ADB, Magisk, Shell root)
I=$J/Mu300Internal.java
t "re-setup steps: ADB, Magisk (面具), 5555, Shell grant" 'TP=app/src/main/java/top/linote/owrtinstaller/TargetProbe.java; grep -q "重新打开 ADB" "$TP" && grep -q "重新安装 Magisk（面具）" "$TP" && grep -q "5555 网络 ADB" "$TP" && grep -q "允许“Shell”" "$TP" && sed -n "/static String resumePrep()/,/^    }/p" "$I" | grep -q "TargetProbe.checklist(null, null)"'
t "stage-one done page shows the steps" 'grep -q "if (jm.stage1 \&\& jm.muOutcome != null) { stage1Page(); return; }" "$M" && sed -n "/private void stage1Page()/,/^    }/p" "$M" | grep -q "Mu300Internal.resumePrep()"'
t "method page: pending notice + RESUME impact show the steps" '[ "$(echo "$page" | grep -c "Mu300Internal.resumePrep()")" -ge 1 ] && grep -A1 "也不动重新设置后的 Android 数据" "$M" | grep -q "Mu300Internal.resumePrep()"'
t "device page: before connecting and on no-root" '[ "$(grep -c "Mu300Internal.resumePrep()" "$M")" -ge 5 ] && grep -q "继续格式化后的安装：先准备目标设备" "$M"'
t "connection without root logs the steps while a record is pending" 'sed -n "/private void waitReady/,/^    }/p" "$JM" | grep -A2 "if (pendingRecord() != null)" | grep -q "TargetProbe.checklist(r.state"'
t "not-connected errors point at the steps" '[ "$(grep -c "throw new IOException(notReady(" "$JM")" = 4 ]'
t "stage1 set for both families" 'grep -q "stage1 = fmt;" "$JM" && grep -q "stage1 = m == Method.PART;" "$JM"'
CL=$J/CleanupActivity.java
t "uninstall is an advanced method" 'grep -q "m == Method.UNINSTALL; }" "$JM"'
t "pending uninstall stays visible with advanced off" 'grep -q "m == JobManager.Method.UNINSTALL && unPending" "$M"'
t "local mode refuses uninstall" '[ "$(grep -c "throw new IOException(UNINSTALL_LOCAL_NO)" "$JM")" = 2 ]'
t "reset needs the checkbox and the typed word" 'grep -q "unPrep.wipe && !unResetOk" "$JM" && grep -q "格式化 userdata（恢复出厂）后卸载" "$M"'
t "uninstall dispatched before MU300 internal" 'grep -q "if (m == Method.UNINSTALL) { checkUninstall(); return; }" "$JM" && grep -q "if (m == Method.UNINSTALL) { installUninstall(); return; }" "$JM"'
t "cleanup: backups need a verified export" 'grep -q "backups && !exportVerified" "$CL" && grep -q "verifyZip(uri)" "$CL"'
t "cleanup: never deletes adbkey or unfinished records" 'grep -q "adbkey" "$CL" && grep -q "kept(f)) return 0" "$CL"'
t "cleanup activity registered and linked" 'grep -q "CleanupActivity" app/src/main/AndroidManifest.xml && grep -q "CleanupActivity.class" "$A"'
t "about documents uninstall + cleanup" 'grep -q "卸载并归还空间【高级】" "$A" && grep -q "section(\"清理本机占用\")" "$A"'
echo "ui check: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
