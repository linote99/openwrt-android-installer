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
t "not-connected errors point at the steps" '[ "$(grep -c "throw new IOException(notReady(" "$JM")" = 5 ]'
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
U=$J/UfiTool.java
t "ufi: package visible (<queries>)" 'grep -q "<package android:name=\"top.linote.ufiinstaller\" />" app/src/main/AndroidManifest.xml'
t "ufi: missing app opens the blog article" 'grep -q "else UfiTool.openBlog(this)" "$M" && grep -q "posts/lino-ufi-tools-android/" "$U"'
t "ufi: waiting card shows family hint + button" 'grep -q "String uh = jm.ufiHint(ws, d.usbName != null);" "$M" && grep -q "if (uh != null) card.addView(ufiButton()" "$M"'
t "ufi: no-root card offers mirroring by family" '[ "$(grep -c "UfiTool.rootGrant(jm.mu300Family()" "$M")" -ge 3 ]'
t "ufi: root probe window is not shown as failure" 'grep -q "正在检查 root 授权" "$M"'
t "ufi: page redraws on ladder state" 'grep -q "d.wait + \",\" + d.waitHint) + \"|\" + jm.waiting" "$M"'
t "ufi: backup notice on formatting plans" 'grep -q "UfiTool.backupBeforeFormat(un)" "$M"'
t "ufi: no-ADB steps on resume / method / done pages" 'grep -q "没有检测到目标设备的 ADB" "$M" && grep -q "String noAdb = pending != null" "$M" && grep -q "UfiTool.noAdbAfterFormat(jm.mu300Family(), false)" "$M"'
t "ufi: ladder log + foreground while waiting" 'grep -q "String uh = ufiHint(r.state" "$JM" && grep -q "if (ownFg) InstallService.start(app" "$JM" && grep -q "if (ownFg) InstallService.stop(app)" "$JM"'
t "ufi: about documents both families" 'grep -q "UfiTool.NAME" "$A" && grep -q "MU300 系列" "$A" && grep -q "E5 系" "$A"'
t "about documents uninstall + cleanup" 'grep -q "卸载并归还空间【高级】" "$A" && grep -q "section(\"清理本机占用\")" "$A"'
t "使用说明 documents bounded MU300 UFS package and limitations" 'grep -q "section(\"MU300 UFS 适配\")" "$A" && grep -q "mu300-openwrt-25.12.5-r20261027.135818-g0043a5d8-s5351d2bc-k.zip" "$A" && grep -q "从 UFS 删除 OpenWrt 并把空间归还给 Android" "$A" && grep -q "UFS 安装与重装流程保持不变" "$A" && grep -q "没有在真实 UFS 设备上验证安装或卸载" "$A"'
t "resume identity note covers eMMC and UFS" 'grep -q "核对 eMMC/UFS 存储身份和槽位" "$A"'
t "launcher name is formal, not UFS test edition" 'grep -Fq "<string name=\"app_name\">OpenWrt 安装器</string>" app/src/main/res/values/strings.xml && ! grep -q "UFS 测试版" app/src/main/res/values/strings.xml'
t "home entry and page header are named 设置" 'grep -q "ui.text(\"设置\", 13" "$M" && grep -q "setTitle(\"设置\")" "$A" && grep -q "ui.text(\"设置\", 17" "$A"'
t "使用说明 and MU300 UFS 适配 remain separate accordion sections" 'grep -q "section(\"使用说明\")" "$A" && grep -q "section(\"MU300 UFS 适配\")" "$A"'
t "settings sections start collapsed and toggle on tap" 'grep -q "details.setVisibility(View.GONE)" "$A" && grep -q "header.setOnClickListener" "$A" && grep -q "details.setVisibility(expanded ? View.VISIBLE : View.GONE)" "$A"'
t "step-4 package/device gate runs before uninstall or storage preflight" 'awk "/void runCheck\\(\\)/{p=1} p && /readOnlyCheckBlocker\\(pkg, target\\(\\)\\)/{g=NR} p && /if \\(m == Method.UNINSTALL\\)/{u=NR} END{exit !(g && u && g<u)}" "$JM"'
t "ext4 repair button is shown only for a current strict candidate" 'grep -q "if (jm.ext4RecoveryPlan != null)" "$M" && grep -q "仅回放 mu300root ext4 journal" "$M" && grep -q "prepareCandidate(io, d, up)" "$JM"'
t "ext4 repair requires the exact typed phrase" 'grep -q "修复 mu300root journal" "$M" && grep -q "word.equals(input.getText().toString().trim())" "$M"'
t "ext4 repair is journal-only, rejects broad auto-fix and does not auto-uninstall" 'grep -q "e2fsck -p -E journal_only" "$M" && grep -q "不会格式化、扩容、使用 -y，也不会自动卸载" "$M"'
t "repair success returns to the ordinary uninstall confirmation path" 'grep -q "repairAndRecheck(io" "$JM" && grep -q "showPreparedUninstall(plan.device, up, plan)" "$JM" && grep -q "尚未卸载；请另行完成既有卸载确认" "$JM"'
t "repair rejects mounted targets and pins the only mutation node" 'grep -q "mu300root 仍被挂载或映射，拒绝修复" app/src/main/java/top/linote/owrtinstaller/Mu300Ext4Recovery.java && grep -q "static final String DEVICE = \"/dev/block/sda75\"" app/src/main/java/top/linote/owrtinstaller/Mu300Ext4Recovery.java'
echo "ui check: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
