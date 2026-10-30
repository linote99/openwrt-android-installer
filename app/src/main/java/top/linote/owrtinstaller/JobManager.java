package top.linote.owrtinstaller;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Process-wide state shared by the activity and the foreground service.
 *
 * Two kinds of target device:
 *  - LOCAL: this phone, through its own su (needs root here);
 *  - LAN:   another device on the same network, through classic wireless ADB
 *           and that device's su (this phone needs no root).
 * Both run the same APK helper (owrt-apk.sh) and the package's own scripts on the target.
 */
final class JobManager {
    interface Listener {
        void onLog(String line);
        void onStateChanged();
    }

    enum Mode { LOCAL, LAN }

    /** Install methods, as in the desktop installer menu (TF = 1, PART = 2, IMAGE = 7). */
    enum Method {
        TF("TF 卡安装", "清空所选 TF 卡并写入 OpenWrt；内部存储与 Android 数据不变",
                "TF 卡安装", "清空所选 TF 卡并写入 OpenWrt；内部存储与 Android 数据不变"),
        PART("缩容 userdata 安装", "下次启动时离线缩小 userdata，新建 e5root 分区写入 OpenWrt；未加密时保留 Android 数据，"
                        + "userdata 已加密或数据放不下时可选“格式化 userdata”（Android 数据全部清空，分两阶段完成）",
                "独立内置分区安装", "优先使用 eMMC 未分配空间（不动 Android 数据）；空间不够时离线缩小 userdata 并保留数据；"
                        + "userdata 已加密或数据放不下时可选“格式化 userdata”（Android 数据全部清空），首次开机自动重启一次"),
        IMAGE("userdata 镜像安装【实验性】", "在 /data/e5linux 中创建 openwrt.ext4 镜像，不格式化 userdata（仅限未加密 userdata）",
                "userdata 镜像安装【实验性】", "在 /data/mu300linux 中创建 OpenWrt 镜像，不格式化 userdata（仅限未加密）；可能损坏 Android 的 F2FS 文件系统"),
        REPART("重装已有内置分区【高级】", "清空已有的 e5root 分区（OpenWrt 系统及其配置）并重新写入；不改分区表，不动 Android 数据",
                "重装已有内置分区【高级】", "清空已有的 mu300root 分区（含 OpenWrt 设置）并重新写入；不改分区表，不动 Android 数据"),
        RESUME("继续格式化后的安装", "“格式化 userdata”安装的第二阶段：Android 重置后先重新打开 ADB、重新安装 Magisk（面具）并给 Shell 授予 root，再"
                        + "把 OpenWrt 写入已准备好的 e5root（需要第一阶段在本机保存的记录）",
                "继续格式化后的安装", "“格式化 userdata”安装的第二阶段：Android 重置后先重新打开 ADB、重新安装 Magisk（面具）并给 Shell 授予 root，再"
                        + "把 OpenWrt 写入已准备好的 mu300root（需要第一阶段在本机保存的记录）"),
        UNINSTALL("卸载并归还空间【高级】", "删除内置 e5root / mu300root 分区，空间还给 Android（相邻时）或释放为未分配空间；能保留时保留 Android 数据，"
                        + "不能保留时说明原因并需额外确认恢复出厂。只能通过 USB / 局域网操作另一台设备",
                "卸载并归还空间【高级】", "删除内置 mu300root 分区（MU300 UFS 仅支持指定安装包），空间还给 Android（相邻时）或释放为未分配空间；能保留时保留 Android 数据，"
                        + "不能保留时说明原因并需额外确认恢复出厂。只能通过 USB / 局域网操作另一台设备");
        final String title, summary, muTitle, muSummary;
        Method(String t, String s, String mt, String ms) { title = t; summary = s; muTitle = mt; muSummary = ms; }
    }

    static final class Device {
        String model = "?", device = "?", slot = "?", vbstate = "?", kernelArch = "?", serial = "", platform = "?";
        String root;          // su path ("" = adbd already root), null when unavailable
        boolean probed;
        String endpoint;      // remote only: "host:port", or a USB label
        String usbName;       // remote over USB OTG: UsbDevice.getDeviceName()
        String usbLabel;      // remote over USB OTG: manufacturer + product (finds it again after a reboot)
        volatile TargetProbe.State wait;   // readiness ladder state while waiting, null otherwise
        volatile String waitHint;          // what the user has to do now
    }

    static final String BASE = "/data/adb/owrt-apk";
    static final String BASE_HELPER = BASE + "/owrt-apk.sh";
    static final String REMOTE_STAGE = "/data/local/tmp/owrt-apk-stage";
    private static final String PREFS = "owrt";

    private static JobManager instance;

    static synchronized JobManager get(Context c) {
        if (instance == null) instance = new JobManager(c.getApplicationContext());
        return instance;
    }

    final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    { AdbConnection.abort = () -> this.stopRequested && this.busy != null; }
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private static final int MAX_LOG = 4000;
    private final SharedPreferences prefs;

    volatile Mode mode;
    final Device local = new Device();
    Device remote;                 // last connected LAN device
    private AdbConnection adb;     // used only from the worker thread
    volatile List<LanScanner.Hit> scanHits = new ArrayList<>();
    volatile boolean scanning;

    PackageInspector.Result pkg;
    String pkgName = "";
    String stagedFingerprint;      // fingerprint staged on the current target
    String checkedFingerprint;     // fingerprint that passed the read-only check on the current target
    String checkTarget, checkCard, checkSlots;
    long checkTargetBytes, checkFreeKb;
    volatile Method method = Method.TF;
    Method installedMethod;
    // userdata check results (MiB)
    String dataNode, crypto;
    long udataMib, usedMib, freeMib, rangeMin, rangeMax, rangeDefault;
    long chosenMib;
    boolean growFs = true;
    // MU300 internal-storage methods (ported computer-side flows)
    Mu300Internal.Plan muPlan;
    Mu300Internal.Outcome muOutcome;
    /** The last install was the first stage of a "format userdata" install (device is resetting). */
    boolean stage1;
    String muBackup;
    // Independent partition: the three routes of the original (unallocated space / keep data / format userdata)
    /** The user ticked "格式化 userdata（Android 数据全部清空）"; never on by default, reset by every check. */
    volatile boolean formatUserdata;
    long[] keepRange, wipeRange;       // E5 PART: preserving shrink / format userdata (min, max, default)
    String keepNo, wipeNo;             // why a route is unavailable
    boolean e5Encrypted;
    E5Part.Pkg e5Pkg;
    String resumeInfo;
    String lastJob = "none";       // none | running | done N | lost
    String hotspot;                // "SSID / key" from the installer log
    boolean installOk;
    String installRc;              // null while running/unknown
    volatile String lastLine = "";
    /** The step of the running task now waiting on the target, and whether that step writes to it. */
    volatile String step;
    volatile boolean stepWrites;
    volatile long stepSince;
    /** Last time the running task showed any sign of life (log line, upload progress). */
    volatile long lastActivity;
    /** The user pressed "停止" on the running task. */
    volatile boolean stopRequested;
    /** Message of the last task that failed (null after a success) and that task's description. */
    volatile String lastFail, lastFailTask;
    volatile long taskStarted;

    volatile String busy;          // task description or null
    volatile int progress = -1;    // 0..100 during ZIP reading / pushing
    volatile boolean cancel;

    private JobManager(Context c) {
        app = c;
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mode = "LAN".equals(prefs.getString("mode", "LOCAL")) ? Mode.LAN : Mode.LOCAL;
        local.model = Build.MODEL;
        local.device = Build.DEVICE;
        installHelpers();
        reportLastCrash();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            recordCrash(t, e, true);
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** The last read-only check ended with an error (shown on the check page). */
    boolean checkFailed() {
        String t = lastFailTask;
        return busy == null && lastFail != null && t != null && t.startsWith("只读检查") && !checkPassed();
    }

    File crashFile() { return new File(app.getFilesDir(), "last-crash.txt"); }

    /** Keeps the stack trace of an internal error so it can be shown (and copied from the log) later. */
    void recordCrash(Thread t, Throwable e, boolean fatal) {
        try (java.io.PrintWriter w = new java.io.PrintWriter(new FileOutputStream(crashFile()))) {
            w.println((fatal ? "闪退" : "内部错误") + " · " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + " · v" + versionName() + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · 线程 " + t.getName());
            e.printStackTrace(w);
        } catch (Throwable ignored) { }
    }

    private void reportLastCrash() {
        File f = crashFile();
        if (!f.isFile()) return;
        try {
            String text = new String(Mu300Internal.readAll(f), java.nio.charset.StandardCharsets.UTF_8);
            String[] ls = text.split("\n");
            append("上次运行记录到异常（可在日志页复制后反馈）：");
            for (int i = 0; i < Math.min(ls.length, 25); i++) append("  " + ls[i]);
        } catch (Throwable ignored) { }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private String versionName() { return BuildInfo.VERSION_NAME; }

    File helper() { return new File(app.getFilesDir(), "owrt-apk.sh"); }
    File checkLib() { return new File(app.getFilesDir(), "check-lib.sh"); }
    File localPkgDir() { return new File(app.getFilesDir(), "stage/pkg"); }

    private void installHelpers() {
        for (String name : new String[]{"owrt-apk.sh", "check-lib.sh", "userdata-lib.sh", "mu300-lib.sh"}) {
            File out = new File(app.getFilesDir(), name);
            try (InputStream in = app.getAssets().open(name); OutputStream o = new FileOutputStream(out)) {
                byte[] b = new byte[8192];
                int r;
                while ((r = in.read(b)) > 0) o.write(b, 0, r);
            } catch (IOException e) {
                append("无法释放辅助脚本 " + name + ": " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------ state helpers
    Device target() { return mode == Mode.LOCAL ? local : remote; }

    boolean targetReady() {
        Device d = target();
        return d != null && d.probed && d.root != null;
    }

    String lastEndpoint() { return prefs.getString("endpoint", "192.168.0.1"); }

    String adbKeyFingerprint() {
        try { return adbKey().fingerprint(); } catch (IOException e) { return "?"; }
    }

    private AdbKey adbKey() throws IOException { return AdbKey.loadOrCreate(new File(app.getFilesDir(), "adbkey")); }

    boolean packageReady() { return pkg != null && pkg.fingerprint.equals(stagedFingerprint); }
    private String checkKey() { return pkg == null ? null : pkg.fingerprint + ":" + method.name(); }
    boolean checkPassed() {
        return (packageReady() || (muInternal(method) && muLocalReady())) && checkKey() != null && checkKey().equals(checkedFingerprint);
    }

    /** MU300 internal-storage methods run the ported computer-side flow from the app-side copy. */
    boolean muInternal(Method m) { return m != Method.TF && pkg != null && pkg.isMu300(); }
    File muLocal() { return new File(app.getFilesDir(), "mu300-local"); }
    boolean muLocalReady() {
        if (pkg == null) return false;
        File f = new File(muLocal(), "FINGERPRINT");
        try { return f.isFile() && new String(Mu300Internal.readAll(f), java.nio.charset.StandardCharsets.UTF_8).trim().equals(pkg.fingerprint); }
        catch (IOException e) { return false; }
    }
    String titleOf(Method m) { return pkg != null && pkg.isMu300() ? m.muTitle : m.title; }
    String summaryOf(Method m) { return pkg != null && pkg.isMu300() ? m.muSummary : m.summary; }
    /** PART on MU300: true when the chosen size fits unallocated eMMC space (no shrink). */
    boolean muUsesFree() { return muPlan != null && Mu300Internal.usesFreeSpace(muPlan, chosenMib); }
    /** PART on MU300 with shrinking: userdata size after the offline shrink, or -1. */
    long muShrinkTargetMib(long mib) {
        if (muPlan == null) return -1;
        try { return Mu300Gpt.allocation(muPlan.s.size, mib)[0] / 2048; } catch (IllegalArgumentException e) { return -1; }
    }
    /** Methods the desktop installer lists under 「高级选项」; shown in step 3 only after the title is tapped 5 times. */
    static boolean advanced(Method m) { return m == Method.IMAGE || m == Method.REPART || m == Method.UNINSTALL; }

    enum Route { FREE, KEEP, FORMAT }
    private static boolean in(long[] r, long v) { return r != null && v >= r[0] && v <= r[1]; }
    /** PART: the route the chosen size and the "格式化 userdata" box lead to, as the original decides it; null = none. */
    Route route() {
        if (method != Method.PART || rangeMax <= 0) return null;
        if (muInternal(method)) {
            if (muPlan == null) return null;
            Mu300Internal.Route r = Mu300Internal.route(muPlan, chosenMib, formatUserdata);
            if (r == Mu300Internal.Route.FORMAT && formatNo() != null) return null;
            return r == null ? null : Route.valueOf(r.name());
        }
        boolean wipeOk = wipeNo == null && in(wipeRange, chosenMib);
        if (formatUserdata) return wipeOk ? Route.FORMAT : null;
        if (keepNo == null && in(keepRange, chosenMib)) return Route.KEEP;
        return wipeOk ? Route.FORMAT : null;
    }
    /** Why formatting userdata cannot be offered at all (null = it can). */
    String formatNo() {
        if (method != Method.PART) return "只用于独立内置分区安装";
        if (muInternal(method)) {
            if (muPlan == null || muPlan.formatMax < muPlan.min) return "格式化 userdata 后也放不下（或 userdata 加密状态无法确认/起点未对齐）";
            if (mode == Mode.LOCAL) return "格式化 userdata 会清空这台手机自己的数据，包括本应用保存的备份和续装记录，第二阶段无法续上；请用另一台手机通过 USB/局域网操作";
            return null;
        }
        return wipeNo;
    }
    /** The format box may be ticked (formatting is possible for this check). */
    boolean formatOffered() { return method == Method.PART && checkPassed() && formatNo() == null; }
    /** The chosen size can only be installed by formatting userdata (encrypted, or the data does not fit). */
    boolean formatRequired() {
        if (!formatOffered()) return false;
        if (muInternal(method)) return muPlan != null && Mu300Internal.formatRequired(muPlan, chosenMib);
        return !(keepNo == null && in(keepRange, chosenMib)) && in(wipeRange, chosenMib);
    }
    void setFormatUserdata(boolean v) {
        if (busy != null) return;
        if (v && !formatOffered()) { append("不能格式化 userdata：" + formatNo()); changed(); return; }
        formatUserdata = v;
        append(v ? "已勾选：格式化 userdata（Android 数据全部清空）" : "已取消勾选格式化 userdata");
        changed();
    }
    /** One line: the route and why, for the plan page and the log. */
    String routeText() {
        Route r = route();
        if (muInternal(method)) return muPlan == null ? "" : r == Route.FORMAT && !formatUserdata
                ? Mu300Internal.why(muPlan, chosenMib, true) + "。需要勾选“格式化 userdata”才能继续"
                : r == null && formatNo() != null && Mu300Internal.route(muPlan, chosenMib, true) == Mu300Internal.Route.FORMAT
                ? "此大小只能格式化 userdata，但" + formatNo()
                : Mu300Internal.why(muPlan, chosenMib, formatUserdata);
        if (r == Route.KEEP) return "离线缩小 userdata 并保留 Android 数据：userdata 未加密，" + chosenMib + " MiB 放得下现有数据和余量";
        if (r == Route.FORMAT) return "格式化 userdata（Android 数据全部清空）：" + (e5Encrypted ? "userdata 已加密，加密数据无法离线保留"
                : formatUserdata ? "按你的选择不保留数据" : "已用数据放不进缩小后的 userdata") + (formatUserdata ? "" : "。需要勾选“格式化 userdata”才能继续");
        if (formatUserdata && wipeNo != null) return "不能格式化 userdata：" + wipeNo;
        return "所选大小没有可用的安装路径" + (keepNo != null ? "（保留数据：" + keepNo + "）" : "") + (wipeNo != null ? "（格式化：" + wipeNo + "）" : "");
    }
    /** Null when the install button may proceed; otherwise what is missing. */
    String installBlocker() {
        if (method == Method.UNINSTALL) {
            if (mode == Mode.LOCAL) return "本机模式不提供卸载";
            if (unResume == null && unPrep == null) return "请先完成只读检查";
            if (unResume == null && unPrep.wipe && !unResetOk) return "无法保留 Android 数据，需要先勾选“格式化 userdata（恢复出厂）后卸载”";
            return null;
        }
        if (method != Method.PART) return null;
        Route r = route();
        if (r == null) return routeText();
        if (r == Route.FORMAT && !formatUserdata) return "此大小需要格式化 userdata（Android 数据全部清空），请先勾选确认";
        return null;
    }
    /** Methods that continue an unfinished record; never hidden behind the advanced switch. */
    static boolean continuation(Method m) { return m == Method.RESUME; }
    /** Unfinished local transaction records (formatting install stage 1, uninstall), shown even when
     *  the advanced options are hidden. Returns a short Chinese description or null. */
    /** Message when no usable target is connected; points at the re-setup steps while a formatting install is unfinished. */
    String notReady(boolean withRoot) {
        if (mode == Mode.LOCAL) return "本机没有 root";
        String s = withRoot ? "请先连接目标设备（USB 或局域网）并取得 root" : "请先连接目标设备（USB 或局域网）";
        String na = noAdbHint();
        if (na != null) s += "。\n" + na;
        else if (pendingRecord() != null)
            s += "。格式化后的续装：先在目标设备上重新打开 ADB、重新安装 Magisk（面具），连接时给 Shell 授予 root（见「设备」页的提示）";
        return s;
    }

    /**
     * MU300 family (MU300 / F50 / U30 Air / V50: no usable screen, ADB from UFI-TOOLS, root through
     * screen mirroring) or E5 family (phones with a screen). From the unfinished record, else the
     * chosen package, else the connected device.
     */
    boolean mu300Family() {
        File un = UninstallRun.pending(uninstallRoot());
        if (un != null) {
            try { return "mu300".equals(UninstallRun.load(un).s("family")); } catch (IOException ignored) { }
        }
        String p = pendingRecord();
        if (p != null && p.contains("（MU300）")) return true;
        if (p != null && p.contains("（E5 系）")) return false;
        if (pkg != null) return pkg.isMu300();
        Device d = remote;
        return d != null && d.probed && PackageInspector.Result.mu300Problem(d.model, d.device, d.platform) == null;
    }

    /** True when the unfinished record follows a factory reset of the target (formatting install, or an uninstall with reset). */
    boolean afterReset() {
        File un = UninstallRun.pending(uninstallRoot());
        if (un != null) {
            try { return Boolean.TRUE.equals(UninstallRun.load(un).plan().get("wipe")); } catch (IOException | RuntimeException e) { return false; }
        }
        return pendingRecord() != null;
    }

    /** Steps when no ADB is visible after a factory reset (per family), or null when not in that situation. */
    String noAdbHint() {
        if (mode == Mode.LOCAL || !afterReset()) return null;
        Device d = remote;
        if (d != null && d.probed && d.wait != TargetProbe.State.ANDROID_BOOT && d.wait != TargetProbe.State.USB_DEBUGGING) return null;
        return UfiTool.noAdbAfterFormat(mu300Family(), UninstallRun.pending(uninstallRoot()) != null);
    }

    /** Companion-app hint for a waiting state (root grant / Magisk), or null. */
    String ufiHint(TargetProbe.State s, boolean usb) {
        if (mode == Mode.LOCAL || s == null) return null;
        if (s == TargetProbe.State.ROOT_PERMISSION) return UfiTool.rootGrant(mu300Family(), usb);
        if (s == TargetProbe.State.MAGISK) return UfiTool.magisk(mu300Family(), usb);
        if (s == TargetProbe.State.ANDROID_BOOT || s == TargetProbe.State.USB_DEBUGGING) return noAdbHint();
        return null;
    }
    String pendingRecord() {
        File un = UninstallRun.pending(uninstallRoot());
        if (un != null) {
            String st = "?";
            try { st = UninstallRun.load(un).s("stage"); } catch (IOException e) { /* the check step reports it */ }
            return "未完成的卸载，记录 " + un.getName() + "，阶段 " + st;
        }
        String[][] kinds = {{"e5-part", "phase", "complete", "格式化后的安装（E5 系）"},
                            {"mu300-backup", "state", "installed", "格式化后的安装（MU300）"}};
        for (String[] k : kinds) {
            File[] dirs = backupBase(k[0]).listFiles();
            if (dirs == null) continue;
            for (File d : dirs) {
                File t = new File(d, "transaction.json");
                if (!t.isFile()) continue;
                try {
                    Object o = MiniJson.parse(new String(java.nio.file.Files.readAllBytes(t.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                    if (!(o instanceof java.util.Map)) continue;
                    Object v = ((java.util.Map<?, ?>) o).get(k[1]);
                    if (v != null && !k[2].equals(String.valueOf(v))) return k[3] + "，记录 " + d.getName() + "，状态 " + v;
                } catch (Exception e) { /* unreadable record: the check step reports it */ }
            }
        }
        String recovery = Mu300Ext4Recovery.pendingDescription(backupBase(Mu300Ext4Recovery.ROOT_NAME));
        if (recovery != null) return recovery;
        return null;
    }
    /** Dirs of unfinished resume / uninstall records (never deleted by the local cleanup). */
    java.util.Set<File> protectedDirs() {
        java.util.LinkedHashSet<File> out = new java.util.LinkedHashSet<>();
        File un = uninstallRoot();
        File[] all = un.listFiles();
        if (all != null) for (File d : all) {
            if (!new File(d, UninstallRun.RECORD).isFile()) continue;
            try { UninstallRun.Rec r = UninstallRun.load(d); if (!r.b("complete") && !r.b("abandoned")) out.add(d); }
            catch (IOException | RuntimeException e) { out.add(d); }   // unreadable: keep
        }
        String[][] kinds = {{"e5-part", "phase", "complete"}, {"mu300-backup", "state", "installed"}};
        for (String[] k : kinds) {
            File[] dirs = backupBase(k[0]).listFiles();
            if (dirs == null) continue;
            for (File d : dirs) {
                File t = new File(d, "transaction.json");
                if (!t.isFile()) continue;
                try {
                    Object o = MiniJson.parse(new String(java.nio.file.Files.readAllBytes(t.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                    Object v = o instanceof java.util.Map ? ((java.util.Map<?, ?>) o).get(k[1]) : null;
                    if (v == null || !k[2].equals(String.valueOf(v))) out.add(d);
                } catch (Exception e) { out.add(d); }
            }
        }
        out.addAll(Mu300Ext4Recovery.protectedDirs(backupBase(Mu300Ext4Recovery.ROOT_NAME)));
        return out;
    }
    File backupBase(String name) {
        File ext = app.getExternalFilesDir(null);
        return new File(ext != null ? ext : app.getFilesDir(), name);
    }
    String targetSerial() {
        Device d = target();
        return d != null && d.serial != null && !d.serial.isEmpty() ? d.serial : Build.MODEL;
    }
    boolean localCopyAvailable() {
        return new File(localPkgDir(), "files/magisk-tf-install.sh").isFile()
                || new File(localPkgDir(), "payload/tf-install-worker.sh").isFile();
    }

    boolean methodAllowed(Method m) {
        if (m == Method.TF) return true;
        if (pkg == null) return m != Method.REPART && m != Method.RESUME && m != Method.UNINSTALL;
        if (pkg.isMu300()) return pkg.internalProblem == null;
        return !pkg.tfOnly;
    }

    String adminUrl() { return pkg != null ? pkg.adminUrl : "http://192.168.9.1"; }
    String logDir() { return pkg != null ? pkg.logDir : "/data/adb/e5-tf-install"; }

    boolean modelMatches() { return pkg != null && target() != null && problemFor(pkg, target()) == null; }

    /** Null when the package's identity rules accept this device (model, board, bootloader state). */
    static String problemFor(PackageInspector.Result p, Device d) {
        if (p == null || d == null || !d.probed) return null;
        return p.targetProblem(d.model, d.device, d.vbstate, d.platform);
    }

    /** Fail closed before any step-4 preflight when the target identity is unknown or incompatible. */
    static String readOnlyCheckBlocker(PackageInspector.Result p, Device d) {
        if (p == null) return "请先选择并校验安装包；只读检查已停止，未写入任何分区";
        if (d == null || !d.probed)
            return "尚未完成目标设备信息检查，无法确认机型；请先在“设备”页完成检查。只读检查已停止，未写入任何分区";
        String why = problemFor(p, d);
        return why == null ? null : "安装包与目标设备机型不匹配：" + why + "。只读检查已安全停止，未写入任何分区";
    }

    private void resetTargetState() {
        stagedFingerprint = null;
        checkedFingerprint = null;
        checkTarget = checkCard = checkSlots = null;
        checkTargetBytes = checkFreeKb = 0;
        clearDataCheck();
        lastJob = "none";
        installOk = false;
        installRc = null;
        hotspot = null;
    }

    private void clearDataCheck() {
        muPlan = null;
        dataNode = crypto = null;
        udataMib = usedMib = freeMib = rangeMin = rangeMax = rangeDefault = chosenMib = 0;
        formatUserdata = false;
        keepRange = wipeRange = null;
        keepNo = wipeNo = resumeInfo = null;
        e5Encrypted = false;
        e5Pkg = null;
        muPlan = null;
        unPrep = null;
        unResume = null;
        unResetOk = false;
        unSummary = null;
        ext4RecoveryPlan = null;
        ext4RecoveryMessage = null;
    }

    void setMethod(Method m) {
        if (busy != null) { append("正在执行：" + busy + "，完成后再切换安装方式"); return; }
        if (method == m) return;
        if (!methodAllowed(m)) { append("该安装包（" + (pkg != null ? pkg.title : "?") + "）不提供“" + titleOf(m) + "”"); changed(); return; }
        method = m;
        checkedFingerprint = null;
        checkTarget = checkCard = null;
        clearDataCheck();
        append("安装方式：" + titleOf(m) + "（需要重新做只读检查）");
        changed();
    }

    /** Partition/image size chosen in the UI; clamped to the range the device check reported. */
    boolean setChosenMib(long v) {
        if (rangeMax <= 0 || v < rangeMin || v > rangeMax) return false;
        chosenMib = v;
        return true;
    }

    // ------------------------------------------------------------ listeners/log
    void addListener(Listener l) { synchronized (listeners) { listeners.add(l); } }
    void removeListener(Listener l) { synchronized (listeners) { listeners.remove(l); } }

    List<String> logLines() { synchronized (log) { return new ArrayList<>(log); } }

    String logText() {
        StringBuilder b = new StringBuilder();
        synchronized (log) { for (String s : log) b.append(s).append('\n'); }
        return b.toString();
    }

    void append(String line) {
        lastActivity = System.currentTimeMillis();
        final String l = now() + "  " + line;
        synchronized (log) {
            log.add(l);
            while (log.size() > MAX_LOG) log.removeFirst();
        }
        main.post(() -> {
            synchronized (listeners) { for (Listener x : new ArrayList<>(listeners)) x.onLog(l); }
        });
    }

    void changed() {
        main.post(() -> {
            synchronized (listeners) { for (Listener l : new ArrayList<>(listeners)) l.onStateChanged(); }
        });
    }

    private static String now() { return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()); }

    // ------------------------------------------------------------ task runner
    private interface Task { void run() throws Exception; }

    private boolean submit(String what, boolean foreground, Task t) {
        if (busy != null) { append("正在执行：" + busy + "，请稍候"); return false; }
        busy = what;
        cancel = false;
        stopRequested = false;
        step = null;
        stepWrites = false;
        stepSince = lastActivity = System.currentTimeMillis();
        progress = -1;
        taskStarted = System.currentTimeMillis();
        lastLine = "";
        lastFail = null;
        lastFailTask = what;
        changed();
        taskForeground = foreground;
        if (foreground) InstallService.start(app, what);
        worker.execute(() -> {
            append("▶ " + what);
            try {
                t.run();
            } catch (PackageInspector.PackageException e) {
                lastFail = e.getMessage();
                append("✗ " + e.getMessage());
            } catch (Exception e) {
                lastFail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                append("✗ " + lastFail);
            } catch (Throwable e) {
                // An Error here (class init, linkage, memory) must not take the whole app down mid-task.
                lastFail = "内部错误：" + e;
                append("✗ " + lastFail);
                recordCrash(Thread.currentThread(), e, false);
            } finally {
                busy = null;
                step = null;
                stepWrites = false;
                progress = -1;
                if (foreground) InstallService.stop(app);
                append("■ 结束：" + what);
                changed();
                if (reconnectAfter) {
                    reconnectAfter = false;
                    if (mode != Mode.LOCAL && remote != null && remote.endpoint != null) main.postDelayed(this::reconnectAfterReboot, 3000);
                }
            }
        });
        return true;
    }

    // ------------------------------------------------------------ target execution
    private AdbConnection adb() throws IOException {
        if (stopRequested && busy != null) throw new IOException("已按要求停止");
        if (remote == null || remote.endpoint == null) throw new IOException("尚未连接其他设备");
        if ((adb == null || adb.isClosed()) && remote.usbName != null) {
            UsbManager um = (UsbManager) app.getSystemService(Context.USB_SERVICE);
            UsbDevice dev = um == null ? null : um.getDeviceList().get(remote.usbName);
            if (dev == null) throw new IOException("USB 设备已断开：请重新插好 OTG 线，然后在“设备”页重新连接");
            UsbAdbTransport t = UsbAdbTransport.open(um, dev);
            adb = AdbConnection.connect(t, remote.endpoint, adbKey(), "owrt-installer@" + Build.MODEL,
                    fp -> append("请在目标设备屏幕上允许 USB 调试（建议勾选“始终允许”）。本应用密钥指纹：" + fp));
        }
        if (adb == null || adb.isClosed()) {
            String[] hp = AdbConnection.parseEndpoint(remote.endpoint);
            adb = AdbConnection.connect(hp[0], Integer.parseInt(hp[1]), adbKey(), "owrt-installer@" + Build.MODEL,
                    fp -> append("请在目标设备屏幕上允许调试（建议勾选“始终允许”）。本应用密钥指纹：" + fp));
        }
        return adb;
    }

    /** Runs shell text as root on the current target, streaming lines. */
    private int rootRun(String script, RootShell.LineSink sink, long timeout) throws IOException {
        if (mode == Mode.LOCAL) return RootShell.run(script, sink, timeout);
        Device d = remote;
        if (d == null || d.root == null) throw new IOException("目标设备没有可用的 root");
        String cmd = d.root.isEmpty() ? script : d.root + " -c " + RootShell.q(script);
        return adb().shell(cmd, sink::line);
    }

    private int helperRun(String args, long timeout, RootShell.LineSink extra) throws IOException {
        return rootRun("sh " + BASE_HELPER + " " + args, line -> {
            if (extra != null) extra.line(line);
            if (!line.startsWith("OWRT-APK-") || line.startsWith("OWRT-APK-ERROR") || line.startsWith("OWRT-APK-INSTALL"))
                if (!line.trim().isEmpty()) lastLine = line;
            append(line);
        }, timeout);
    }

    // ------------------------------------------------------------ target selection
    void setMode(Mode m) {
        if (busy != null) { append("正在执行：" + busy + "，完成后再切换设备"); return; }
        if (mode == m) return;
        mode = m;
        prefs.edit().putString("mode", m.name()).apply();
        resetTargetState();
        append(m == Mode.LOCAL ? "目标设备：本机" : "目标设备：其他设备（USB 有线 ADB 或局域网无线 ADB）");
        changed();
        if (m == Mode.LOCAL) probeLocal();
        else if (remote != null && remote.usbName != null) connectUsb(remote.usbName);
        else if (remote != null && remote.endpoint != null) connectLan(remote.endpoint);
    }

    void probe() {
        if (mode == Mode.LOCAL) probeLocal();
        else if (remote != null && remote.usbName != null) connectUsb(remote.usbName);
        else if (remote != null && remote.endpoint != null) connectLan(remote.endpoint);
        else changed();
    }

    void probeLocal() {
        submit("检测本机 root 与设备信息", false, () -> {
            local.root = RootShell.detect();
            local.probed = true;
            if (local.root == null) {
                append("✗ 本机未获得 root。请在 Magisk / KernelSU / KSUNext 中给“OpenWrt 安装器”授予超级用户权限；"
                        + "或改用“局域网设备”安装到其他设备（本机无需 root）。");
                return;
            }
            append("✓ 本机 root 可用（" + local.root + "）");
            final StringBuilder props = new StringBuilder();
            RootShell.run(propsScript(), l -> props.append(l).append('\n'), 60);
            parseProps(local, props.toString());
            describe(local, "本机");
            refreshStatus();
        });
    }

    void connectLan(String endpointText) {
        if (busy != null) { switchConnection(() -> connectLan(endpointText)); return; }
        submit("连接局域网设备", false, () -> {
            String[] hp = AdbConnection.parseEndpoint(endpointText);
            String ep = hp[0].contains(":") ? "[" + hp[0] + "]:" + hp[1] : hp[0] + ":" + hp[1];
            if (adb != null) { adb.close(); adb = null; }
            resetTargetState();
            Device d = new Device();
            d.endpoint = ep;
            remote = d;
            changed();
            append("连接无线 ADB " + ep + " ……");
            prefs.edit().putString("endpoint", ep).apply();
            waitReady(d);
        });
    }

    // ------------------------------------------------------------ USB OTG (wired ADB)
    private static final String ACTION_USB_PERMISSION = "top.linote.owrtinstaller.USB_PERMISSION";
    volatile List<UsbAdbTransport.Candidate> usbHits = new ArrayList<>();

    private UsbManager usbManager() { return (UsbManager) app.getSystemService(Context.USB_SERVICE); }

    /** Refreshes the USB list without logging (e.g. when the app returns to the foreground). */
    void refreshUsbQuiet() { usbHits = UsbAdbTransport.list(usbManager()); }

    /** Lists attached USB devices that expose an ADB interface (no permission needed). */
    void scanUsb() {
        List<UsbAdbTransport.Candidate> l = UsbAdbTransport.list(usbManager());
        usbHits = l;
        if (l.isEmpty()) append("没有发现 USB ADB 设备。请用 OTG 线连接目标设备，并在目标设备上开启 USB 调试。"
                + "如果本机不支持 OTG（USB 主机模式），请改用局域网连接。");
        else for (UsbAdbTransport.Candidate c : l) append("发现 USB 设备：" + c.label);
        changed();
    }

    /** Asks for USB permission if needed (system dialog), then connects over wired ADB. */
    void connectUsb(String deviceName) {
        if (busy != null) { switchConnection(() -> connectUsb(deviceName)); return; }
        UsbManager um = usbManager();
        UsbDevice dev = um == null ? null : um.getDeviceList().get(deviceName);
        if (dev == null) { append("✗ USB 设备已断开：请重新插好 OTG 线后点“检测 USB 设备”"); scanUsb(); return; }
        if (um.hasPermission(dev)) { connectUsbNow(deviceName); return; }
        append("请在弹出的系统对话框中允许“OpenWrt 安装器”访问该 USB 设备……");
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try { app.unregisterReceiver(this); } catch (RuntimeException ignored) { }
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connectUsbNow(deviceName);
                else { append("✗ 未获得 USB 访问权限"); changed(); }
            }
        };
        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(r, f);
        Intent pi = new Intent(ACTION_USB_PERMISSION).setPackage(app.getPackageName());
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        um.requestPermission(dev, PendingIntent.getBroadcast(app, 1, pi, flags | PendingIntent.FLAG_UPDATE_CURRENT));
    }

    private void connectUsbNow(String deviceName) {
        submit("连接 USB 设备", false, () -> {
            String label = deviceName;
            for (UsbAdbTransport.Candidate c : UsbAdbTransport.list(usbManager()))
                if (c.name.equals(deviceName)) label = c.label;
            if (adb != null) { adb.close(); adb = null; }
            resetTargetState();
            Device d = new Device();
            d.endpoint = "USB · " + label;
            d.usbName = deviceName;
            d.usbLabel = label;
            remote = d;
            changed();
            append("通过 USB 连接 " + label + " ……");
            waitReady(d);
        });
    }

    // ------------------------------------------------------------ readiness ladder (adapter.probe)
    /** True while the running task only waits for the target to become ready (no device writes). */
    volatile boolean waiting;
    /** The running task already holds the foreground service (submit(..., true, ..)). */
    private volatile boolean taskForeground;
    /** Set by a reboot of the target inside a task: reconnect and wait once that task ends. */
    private volatile boolean reconnectAfter;
    private final java.util.Set<String> usbAsked = new java.util.HashSet<>();

    /** No ADB interface on any attached USB device although something is attached. */
    private static final class NoAdbInterface extends IOException { NoAdbInterface() { super("USB 设备没有 ADB 接口"); } }

    /**
     * adb_connect: the connection may only change while the task is waiting for the device / the
     * user (desktop: status waiting_user / waiting_device in one of Rebind.WAIT_STAGES).
     */
    private void switchConnection(Runnable then) {
        if (!waiting) { append("✗ " + Rebind.PAUSE_FIRST + "（正在执行：" + busy + "）"); return; }
        append("停止等待当前连接，改用新的连接……");
        stopRequested = true;
        AdbConnection c = adb;
        if (c != null) c.close();
        new Thread(() -> {
            for (int i = 0; i < 200 && busy != null; i++) {
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
            main.post(() -> { if (busy == null) then.run(); else append("✗ 当前等待没有及时结束，请稍后再试"); });
        }, "switch-conn").start();
    }

    /** The ADB connection for the waiting loop; finds a USB target again after it re-enumerated. */
    private AdbConnection adbWait(Device d) throws IOException {
        if (adb != null && !adb.isClosed()) return adb;
        AdbConnection.AuthListener al = fp -> {
            if (d.wait != TargetProbe.State.DEBUG_AUTHORIZATION)
                append("请在目标设备屏幕上允许调试（建议勾选“始终允许”）。本应用密钥指纹：" + fp);
        };
        if (d.usbName != null) {
            UsbManager um = usbManager();
            if (um == null) throw new AdbConnection.Permanent("本机不支持 USB 主机模式，请改用局域网连接");
            UsbDevice dev = um.getDeviceList().get(d.usbName);
            if (dev == null || UsbAdbTransport.findAdbInterface(dev) == null) {
                // A reboot gives the target a new device node: take the same product again, or the only ADB device.
                List<UsbAdbTransport.Candidate> l = UsbAdbTransport.list(um);
                usbHits = l;
                UsbAdbTransport.Candidate pick = null;
                for (UsbAdbTransport.Candidate c : l) if (pick == null && c.label.equals(d.usbLabel)) pick = c;
                if (pick == null && l.size() == 1) pick = l.get(0);
                if (pick == null) {
                    if (!l.isEmpty()) throw new IOException("连接着多台 USB ADB 设备，请只连接目标设备");
                    if (!um.getDeviceList().isEmpty()) throw new NoAdbInterface();
                    throw new IOException("看不到 USB 设备");
                }
                dev = pick.dev;
                d.usbName = pick.name;
            }
            if (!um.hasPermission(dev)) {
                if (usbAsked.add(dev.getDeviceName())) {
                    append("目标设备重新出现：请在本机弹出的对话框里允许“OpenWrt 安装器”访问该 USB 设备");
                    Intent pi = new Intent(ACTION_USB_PERMISSION).setPackage(app.getPackageName());
                    int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                    um.requestPermission(dev, PendingIntent.getBroadcast(app, 2, pi, flags | PendingIntent.FLAG_UPDATE_CURRENT));
                }
                throw new IOException("等待本机允许访问 USB 设备");
            }
            adb = AdbConnection.connect(UsbAdbTransport.open(um, dev), d.endpoint, adbKey(), "owrt-installer@" + Build.MODEL, al);
        } else {
            String[] hp = AdbConnection.parseEndpoint(d.endpoint);
            adb = AdbConnection.connect(hp[0], Integer.parseInt(hp[1]), adbKey(), "owrt-installer@" + Build.MODEL, al);
        }
        return adb;
    }

    private static String waitSh(AdbConnection c, String cmd, int ms) throws IOException {
        StringBuilder b = new StringBuilder();
        try {
            c.shell(cmd, l -> b.append(l).append('\n'), ms);
        } catch (IOException e) {
            if (c.isClosed()) throw new TargetProbe.Fatal(e.getMessage() == null ? "连接已断开" : e.getMessage());
            throw e;
        }
        return b.toString();
    }

    /**
     * Polls the readiness ladder until the target is ready (root usable) or the user stops: every
     * state but READY is a waiting state (desktop: waiting_device for android_boot, waiting_user
     * otherwise) with its own hint; the target reconnects by itself after reboots.
     */
    private void waitReady(Device d) throws IOException {
        waiting = true;
        // While the user works in another app (e.g. Lino-UFI 工具 mirroring), a cached process may be
        // frozen (Android 12+): keep a foreground notification so the retries go on.
        final boolean ownFg = !taskForeground;
        if (ownFg) InstallService.start(app, "等待目标设备就绪");
        try {
            TargetProbe.State last = null;
            String lastSerial = d.serial;
            while (true) {
                if (stopRequested) throw new IOException("已停止等待目标设备（可随时重新连接）");
                TargetProbe.Result r;
                boolean booted = false;
                try {
                    boolean fresh = adb == null || adb.isClosed();
                    AdbConnection c = adbWait(d);
                    if (fresh) {
                        if (c.staleDropped > 0 || c.staleBytes > 0)
                            append("已丢弃上一次会话残留的数据（" + c.staleDropped + " 个报文" + (c.staleBytes > 0 ? "，" + c.staleBytes + " 字节" : "") + "）");
                        append("✓ 已连接（" + (c.banner.containsKey("ro.product.model") ? c.banner.get("ro.product.model") : "设备") + "）");
                        parseProps(d, c.shellText(propsScriptPlain()));
                        d.probed = true;
                        if (lastSerial != null && !lastSerial.isEmpty() && !lastSerial.equals(d.serial))
                            append("! 现在连上的设备序列号与之前不同（" + maskSerial(lastSerial) + " → " + maskSerial(d.serial) + "）：如果不是同一台设备，续装/卸载会拒绝继续");
                        lastSerial = d.serial;
                        describe(d, d.usbName != null ? "USB 设备" : "局域网设备");
                    }
                    booted = true;
                    r = TargetProbe.evaluate((cmd, ms) -> waitSh(c, cmd, ms));
                } catch (AdbConnection.Permanent e) {
                    throw e;
                } catch (AdbConnection.AuthPending e) {
                    closeAdb();
                    r = new TargetProbe.Result(TargetProbe.State.DEBUG_AUTHORIZATION, null, null);
                } catch (NoAdbInterface e) {
                    r = new TargetProbe.Result(TargetProbe.State.USB_DEBUGGING, null, null);
                } catch (IOException e) {
                    if (stopRequested) throw new IOException("已停止等待目标设备（可随时重新连接）");
                    closeAdb();
                    booted = false;
                    r = new TargetProbe.Result(TargetProbe.State.ANDROID_BOOT, null, null);
                }
                if (r.state == TargetProbe.State.READY) {
                    d.root = r.su;
                    d.wait = null;
                    d.waitHint = null;
                    append("✓ 目标设备已就绪：Android 已启动、调试已授权，root 可用（" + (r.su.isEmpty() ? "adbd 已是 root" : r.su) + "）");
                    if (pendingRecord() != null) append("本机有未完成的记录（" + pendingRecord() + "），可以在「安装方式」里接着继续。");
                    refreshStatus();
                    changed();
                    return;
                }
                d.root = null;
                String hint = TargetProbe.hint(r, d.usbName != null, booted);
                d.wait = r.state;
                d.waitHint = hint;
                if (r.state != last) {
                    append("… " + TargetProbe.title(r.state) + "（" + TargetProbe.stage(r.state) + "）：" + hint);
                    String uh = ufiHint(r.state, d.usbName != null);
                    if (uh != null) for (String l : uh.split("\n")) append("  " + l);
                    if (pendingRecord() != null) {
                        append("  恢复出厂后的准备清单（✓ 已完成，→ 现在这一步）：");
                        for (String l : TargetProbe.checklist(r.state, d.usbName == null).split("\n")) append("  " + l);
                    }
                    append("  本应用每 3 秒自动重试，完成后自动往下走；不想等了可以点“停止”。");
                    last = r.state;
                }
                step = TargetProbe.title(r.state) + "（自动重试中）";
                stepWrites = false;
                lastActivity = System.currentTimeMillis();
                changed();
                for (int i = 0; i < 12 && !stopRequested; i++) {
                    try { Thread.sleep(250); } catch (InterruptedException e) { throw new IOException("已停止等待目标设备"); }
                }
                lastActivity = System.currentTimeMillis();
            }
        } finally {
            waiting = false;
            if (ownFg) InstallService.stop(app);
        }
    }

    private void closeAdb() {
        AdbConnection c = adb;
        adb = null;
        if (c != null) c.close();
    }

    /** "usb:<serial>" / "tcp:<host:port>" of the connection in use; "" in local mode. */
    String transportId() {
        Device d = remote;
        if (mode == Mode.LOCAL || d == null || d.endpoint == null) return "";
        return d.usbName != null ? "usb:" + d.serial : "tcp:" + d.endpoint;
    }

    /** After the target rebooted inside a task: wait for it to come back, then continue. */
    void reconnectAfterReboot() {
        if (busy != null || remote == null || mode == Mode.LOCAL) return;
        final Device d = remote;
        submit("等待目标设备重新连接", false, () -> {
            closeAdb();
            d.root = null;
            append("目标设备正在重启：本应用会自动等它回到 Android 并重新连接（不用手动点重试，可以点“停止”）。");
            waitReady(d);
        });
    }

    void disconnectLan() {
        if (busy != null) { switchConnection(this::disconnectLan); return; }
        submit("断开目标设备", false, () -> {
            if (adb != null) adb.close();
            adb = null;
            if (remote != null) append("已断开 " + remote.endpoint);
            remote = null;
            if (mode == Mode.LAN) resetTargetState();
        });
    }

    void scanLan() {
        if (scanning) return;
        scanning = true;
        scanHits = new ArrayList<>();
        changed();
        append("扫描局域网中开放无线 ADB（5555 端口）的设备……");
        new Thread(() -> {
            List<LanScanner.Hit> hits = LanScanner.scan(app, 5555, () -> false);
            scanHits = hits;
            scanning = false;
            if (hits.isEmpty()) append("未发现开放 5555 端口的设备。请确认手机已连上目标设备的热点/同一局域网，且目标设备已开启无线 ADB。");
            else for (LanScanner.Hit h : hits) append("发现：" + h.endpoint() + (h.gateway ? "（网关）" : ""));
            changed();
        }, "lan-scan").start();
    }

    private static String propsScriptPlain() {
        return "echo \"model=$(getprop ro.product.model)\"; echo \"device=$(getprop ro.product.device)\";"
                + " echo \"slot=$(getprop ro.boot.slot_suffix)\"; echo \"vb=$(getprop ro.boot.verifiedbootstate)\";"
                + " echo \"arch=$(uname -m)\"; echo \"serial=$(getprop ro.serialno)\";"
                + " echo \"platform=$(getprop ro.board.platform)\"";
    }

    private static String propsScript() { return propsScriptPlain(); }

    private static void parseProps(Device d, String text) {
        for (String l : text.split("\n")) {
            int eq = l.indexOf('=');
            if (eq < 0) continue;
            String k = l.substring(0, eq), v = l.substring(eq + 1).trim();
            switch (k) {
                case "model": if (!v.isEmpty()) d.model = v; break;
                case "device": if (!v.isEmpty()) d.device = v; break;
                case "slot": d.slot = v.isEmpty() ? "无 A/B 槽位" : v; break;
                case "vb": d.vbstate = v.isEmpty() ? "?" : v; break;
                case "arch": d.kernelArch = v; break;
                case "serial": d.serial = v; break;
                case "platform": d.platform = v.isEmpty() ? "?" : v; break;
                default: break;
            }
        }
    }

    private void describe(Device d, String what) {
        append(what + "：" + d.model + " / " + d.device + "，槽位 " + d.slot + "，verifiedbootstate=" + d.vbstate
                + "，内核 " + d.kernelArch + (d.serial.isEmpty() ? "" : "，序列号 " + maskSerial(d.serial)));
        String why = problemFor(pkg, d);
        if (why != null) append("✗ " + why + "：安装包脚本会拒绝安装。");
        changed();
    }

    static String maskSerial(String s) {
        if (s == null || s.isEmpty()) return "";
        return "••••" + (s.length() > 4 ? s.substring(s.length() - 4) : s);
    }

    private void refreshStatus() throws IOException {
        final String[] staged = {null};
        final String[] job = {"none"};
        rootRun("if [ -f " + BASE_HELPER + " ]; then sh " + BASE_HELPER + " status;"
                + " else echo OWRT-APK-STAGED=none; echo OWRT-APK-JOB=none; fi", l -> {
            if (l.startsWith("OWRT-APK-STAGED=")) staged[0] = l.substring(16).trim();
            else if (l.startsWith("OWRT-APK-JOB=")) job[0] = l.substring(13).trim();
            else if (l.startsWith("OWRT-APK-ERROR")) append(l);
        }, 60);
        stagedFingerprint = (staged[0] == null || staged[0].equals("none")) ? null : staged[0];
        lastJob = job[0];
        if (lastJob.equals("running")) append("检测到正在进行的安装任务，可在首页点“继续未完成的安装”查看进度。");
        else if (lastJob.startsWith("done")) append("上次安装任务已结束（退出码 " + lastJob.substring(4).trim() + "）。");
        else if (lastJob.equals("lost")) append("上次安装任务异常中断（无退出码）。请先查看日志和 " + logDir() + "（MU300 为 /data/local/tmp/mu300-tf-install），再决定是否重试。");
        changed();
    }

    // ------------------------------------------------------------ package
    void loadPackage(Uri uri) {
        submit("读取并校验安装包", true, () -> {
            pkg = null;
            checkedFingerprint = null;
            installOk = false;
            installRc = null;
            hotspot = null;
            pkgName = displayName(uri);
            long size = displaySize(uri);
            File work = new File(app.getFilesDir(), "stage");
            PackageInspector.deleteTree(work);
            long free = new StatFs(app.getFilesDir().getAbsolutePath()).getAvailableBytes();
            append("安装包：" + pkgName + (size > 0 ? "（" + mib(size) + "）" : "") + "；本机可用空间 " + mib(free));
            if (free < (600L << 20)) throw new PackageInspector.PackageException("本机可用空间不足 600 MiB，无法解包");
            try (InputStream raw = app.getContentResolver().openInputStream(uri)) {
                if (raw == null) throw new IOException("无法打开所选文件");
                InputStream in = new BufferedInputStream(raw, 1 << 16);
                append("正在逐个校验 ZIP 内全部文件的 SHA-256，并只提取设备端安装文件……");
                pkg = PackageInspector.inspect(in, size, work, new PackageInspector.Progress() {
                    @Override public void onProgress(long n, long total, String entry) {
                        int p = total > 0 ? (int) Math.min(100, n * 100 / total) : -1;
                        if (p != progress) { progress = p; changed(); }
                    }
                    @Override public boolean isCancelled() { return cancel; }
                });
            } catch (PackageInspector.PackageException | IOException e) {
                PackageInspector.deleteTree(work);
                throw e;
            }
            PackageInspector.Result r = pkg;
            append("✓ 校验通过：" + r.verifiedFiles + "/" + r.listedFiles + " 个文件与包内"
                    + (r.isMu300() ? " desktop/manifest.json" : " SHA256SUMS") + " 一致");
            append("  已提取安装文件 " + r.extractedFiles + " 个，共 " + mib(r.extractedBytes)
                    + (r.prefix.isEmpty() ? "" : "（包目录 " + r.prefix + "）"));
            for (String w : r.warnings) append("  提示：" + w);
            if (r.isMu300()) prepareMu300Internal(r);
            append("  版本：" + r.version + "；适用机型：" + r.title + " " + r.androidModels + "（profile " + r.profile
                    + (r.board != null ? "，主板 " + r.board : "") + (r.unlockRequired ? "，需已解锁" : "，不要求 orange") + "）");
            if (!methodAllowed(method)) {
                method = Method.TF;
                checkedFingerprint = null;
                clearDataCheck();
                append("  该安装包不提供之前选择的安装方式，已切换到“" + Method.TF.title + "”");
            }
            String why = problemFor(r, target());
            if (why != null) append("✗ " + why + "：安装包脚本会拒绝安装。不要跨机型刷入。");
            changed();
        });
    }

    /** Builds the app-side copy for the MU300 internal methods; TF stays available if this fails. */
    private void prepareMu300Internal(PackageInspector.Result r) {
        File out = muLocal();
        PackageInspector.deleteTree(out);
        if (r.internalProblem == null) {
            append("  准备内置存储安装文件（按原安装器剔除构建机基带运行时与非公开固件，并试生成启动镜像）……");
            try {
                Mu300Internal.prepareLocal(localPkgDir(), r.internalSrc, out, r.listedMap, (what, pct) -> {
                    if (pct != progress) { progress = pct; lastLine = what + " " + pct + "%"; changed(); }
                });
                Mu300Internal.writeAll(new File(out, "FINGERPRINT"), (r.fingerprint + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                append("  ✓ 内置存储安装文件已就绪（启动镜像生成自检通过，未连接任何设备）");
            } catch (IOException | RuntimeException | LinkageError e) {
                PackageInspector.deleteTree(out);
                r.internalProblem = "内置安装文件准备失败：" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            } catch (OutOfMemoryError e) {
                PackageInspector.deleteTree(out);
                r.internalProblem = "内存不足，无法生成启动镜像";
            }
        }
        if (r.internalSrc != null) PackageInspector.deleteTree(r.internalSrc);
        progress = -1;
        r.tfOnly = r.internalProblem != null;
        if (r.internalProblem != null) append("  提示：内置存储安装不可用（" + r.internalProblem + "），只能用 TF 卡安装");
        else append("  可用安装方式：TF 卡 / 独立内置分区 / 重装已有内置分区 / userdata 镜像（实验性）");
    }

    /** Target access for the ported MU300 computer-side flow. */
    private Mu300Internal.Io muIo() {
        return new Mu300Internal.Io() {
            @Override public String storageLibrary() throws IOException {
                File lib = new File(muLocal(), "desktop/storage-lib.sh");
                if (!lib.isFile()) return null;
                String expected = Mu300Internal.localSums(muLocal()).get("desktop/storage-lib.sh");
                byte[] bytes = Mu300Internal.readAll(lib);
                if (expected == null || !expected.equals(Mu300Internal.hex(Mu300Internal.sha().digest(bytes)))
                        || !expected.equals("7dc62136b7f95a3d18e8218b13649c04729fe3c7d29814acb68b82ceb1f44233"))
                    throw new IOException("UFS storage library checksum mismatch; refuse device probing");
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
            @Override public String sh(String script, boolean root) throws IOException { return sh(script, root, 600); }
            @Override public String sh(String script, boolean root, int silenceSec) throws IOException {
                return sh(script, root, silenceSec, null);
            }
            @Override public String sh(String script, boolean root, int silenceSec,
                                       java.util.function.Consumer<String> live) throws IOException {
                StringBuilder b = new StringBuilder();
                RootShell.LineSink sink = l -> {
                    b.append(l).append('\n');
                    if (live != null) live.accept(l);
                };
                int rc;
                int ms = silenceSec <= 0 ? 0 : silenceSec * 1000;
                if (mode == Mode.LOCAL) rc = RootShell.run(script, sink, silenceSec <= 0 ? 6 * 3600 : Math.max(silenceSec, 60));
                else {
                    Device d = remote;
                    String cmd = !root || d == null || d.root == null || d.root.isEmpty() ? script : d.root + " -c " + RootShell.q(script);
                    if (root && (d == null || d.root == null)) throw new IOException("目标设备没有可用的 root");
                    rc = adb().shell(cmd, sink::line, ms);
                }
                if (rc != 0) {
                    String[] ls = b.toString().trim().split("\n");
                    throw new IOException("目标设备命令失败（" + rc + "）" + (ls.length > 0 && !ls[ls.length - 1].isEmpty() ? "：" + ls[ls.length - 1] : ""));
                }
                return b.toString();
            }

            @Override public void pull(String remote, File dest) throws IOException { pull(remote, dest, null); }
            @Override public void pull(String remote, File dest, java.util.function.LongConsumer received) throws IOException {
                try (OutputStream file = new FileOutputStream(dest)) {
                    OutputStream o = received == null ? file : new java.io.FilterOutputStream(file) {
                        long n;
                        @Override public void write(int x) throws IOException { file.write(x); received.accept(++n); }
                        @Override public void write(byte[] d, int off, int len) throws IOException {
                            file.write(d, off, len);
                            n += len;
                            received.accept(n);
                        }
                    };
                    if (mode == Mode.LOCAL) {
                        int rc = RootShell.readBinary("cat " + RootShell.q(remote), o, 1800);
                        if (rc != 0) throw new IOException("读取失败（" + rc + "）：" + remote);
                    } else {
                        adb().execRaw("cat " + RootShell.q(remote), o, 600_000);
                    }
                }
            }

            @Override public void push(File local, String remote) throws IOException {
                if (mode == Mode.LOCAL) {
                    int rc = RootShell.run("cat " + RootShell.q(local.getAbsolutePath()) + " > " + RootShell.q(remote), JobManager.this::append, 1800);
                    if (rc != 0) throw new IOException("复制失败：" + remote);
                    return;
                }
                final long total = Math.max(1, local.length());
                final boolean big = total > (4L << 20);
                adb().push(new File[]{local}, new String[]{remote}, new int[]{0644}, new AdbConnection.Progress() {
                    @Override public void bytes(long done) {
                        if (!big) return;
                        int pct = (int) (done * 100 / total);
                        String l = "上传 " + local.getName() + " " + pct + "%";
                        lastActivity = System.currentTimeMillis();
                        if (!l.equals(lastLine)) { lastLine = l; changed(); }
                    }
                    // Only a staging file in /data/local/tmp is being written: stopping here changes nothing else.
                    @Override public boolean cancelled() { return stopRequested; }
                });
            }

            @Override public void reboot() throws IOException {
                if (mode == Mode.LOCAL) {
                    RootShell.run("sync; (sleep 2; svc power reboot || reboot) >/dev/null 2>&1 &", JobManager.this::append, 60);
                    return;
                }
                adb().reboot();
                if (adb != null) adb.close();
                reconnectAfter = true;
                adb = null;
            }

            @Override public void log(String line) {
                if (!line.trim().isEmpty()) lastLine = line;
                append(line);
                changed();
            }

            @Override public void progress(int percent) { progress = percent; changed(); }

            @Override public void status(String line) {
                lastLine = line;
                lastActivity = System.currentTimeMillis();
                changed();
            }
            @Override public File cacheDir() { return app.getCacheDir(); }
            @Override public void step(String what, boolean writes) {
                if (!what.equals(step)) stepSince = System.currentTimeMillis();
                step = what;
                stepWrites = writes;
                lastActivity = System.currentTimeMillis();
                changed();
            }
            @Override public boolean cancelled() { return stopRequested; }
            @Override public String transport() { return transportId(); }
            @Override public boolean ready() { return mode == Mode.LOCAL ? local.root != null : remote != null && remote.root != null; }
        };
    }

    private static Mu300Internal.Kind kind(Method m) {
        return m == Method.PART ? Mu300Internal.Kind.PART : m == Method.REPART ? Mu300Internal.Kind.REPART
                : m == Method.RESUME ? Mu300Internal.Kind.RESUME : Mu300Internal.Kind.IMAGE;
    }

    private void checkMu300(Method m) throws IOException {
        if (!targetReady()) throw new IOException(notReady(true));
        if (!muLocalReady()) throw new IOException("本机已没有内置安装文件，请重新选择安装包");
        String why = problemFor(pkg, target());
        if (why != null) throw new IOException(why + "；不要跨机型刷入");
        lastLine = "只读检查内部存储、GPT 与 userdata";
        changed();
        Mu300Internal.Plan p = Mu300Internal.check(muIo(), kind(m));
        muPlan = p;
        checkSlots = p.slot + "," + p.linux;
        dataNode = "/dev/block/" + p.s.node;
        crypto = p.encryptedNote != null ? p.encryptedNote : "未加密（" + p.s.state + "）";
        udataMib = p.s.size / 2048;
        freeMib = p.s.free / 1024;
        usedMib = Math.max(0, udataMib - freeMib);
        if (m == Method.RESUME) {
            Mu300Internal.Resume rec = Mu300Internal.loadResume(muIo(), p, muLocal(), backupBase("mu300-backup"), targetSerial());
            resumeInfo = "续装记录 " + rec.file.getParentFile().getName() + "，状态 " + rec.r.get("state") + "；" + p.summary;
            rangeMin = rangeMax = rangeDefault = chosenMib = p.rootMib;
        } else if (m == Method.REPART) {
            rangeMin = rangeMax = rangeDefault = chosenMib = p.rootMib;
        } else {
            rangeMin = p.min;
            rangeMax = p.maximum;
            rangeDefault = p.def;
            chosenMib = p.def;
        }
        checkedFingerprint = checkKey();
        append("✓ 检查通过，未修改任何分区。设备 " + p.model + "（" + p.profile + "），Android 槽 " + p.slot + "，Linux 槽 " + p.linux);
        append("  " + p.summary + (m == Method.REPART || m == Method.RESUME ? "" : "；大小可选 " + rangeMin + "–" + rangeMax + " MiB（默认 " + rangeDefault + " MiB）"));
        if (m == Method.PART) {
            if (p.formatMax >= p.min && mode == Mode.LOCAL) append("  格式化 userdata 路径在本机模式下不提供：" + formatNo());
            append("  默认 " + chosenMib + " MiB 的路径：" + routeText());
        }
    }

    private void installMu300(Method m) throws IOException {
        if (muPlan == null) throw new IOException("请先完成只读检查");
        if (m != Method.REPART && m != Method.RESUME && (chosenMib < rangeMin || chosenMib > rangeMax))
            throw new IOException("所选大小不在检查给出的范围内（" + rangeMin + "–" + rangeMax + " MiB）");
        String block = installBlocker();
        if (block != null) throw new IOException(block + "；未做任何修改");
        Route rt = m == Method.PART ? route() : null;
        Mu300Internal.Route expect = rt == null ? null : Mu300Internal.Route.valueOf(rt.name());
        boolean fmt = rt == Route.FORMAT && formatUserdata;   // never implied by encryption alone
        if (rt != null) append("安装路径：" + Mu300Internal.routeName(expect) + "。原因：" + routeText());
        installOk = false;
        installRc = null;
        hotspot = null;
        muOutcome = null;
        installedMethod = m;
        stage1 = false;
        lastJob = "running";
        changed();
        File backups = backupBase("mu300-backup");
        try {
            Mu300Internal.Outcome o = Mu300Internal.install(muIo(), muLocal(), pkg.kernelFamily, kind(m), chosenMib, backups,
                    targetSerial(), expect, fmt, path -> muBackup = path);
            muOutcome = o;
            stage1 = fmt;
            installOk = true;
            installRc = "0";
            lastJob = "done 0";
            append("✓ " + o.message);
            append("  " + o.next);
            if (muBackup != null) append("  分区备份（GPT、misc、两槽启动链）：" + muBackup);
            if (!stage1) append("  管理地址：" + adminUrl() + "  用户 root，初始密码 root（请尽快修改）");
        } catch (IOException | RuntimeException e) {
            installRc = "1";
            lastJob = "done 1";
            changed();
            throw e;
        } finally {
            progress = -1;
        }
        changed();
    }

    // ================================================================== uninstall (v1.6.0)
    Uninstall.Prepared unPrep;          // fresh read-only plan from the check step
    File unResume;                      // unfinished record for the connected device (continue instead of a new plan)
    boolean unResetOk;                  // user ticked the factory-reset box on the confirm page
    String unSummary;                   // plan text for the confirm page
    UninstallRun.Outcome unOutcome;
    Mu300Ext4Recovery.Candidate ext4RecoveryPlan;
    String ext4RecoveryMessage;
    private String unProfile;
    File uninstallRoot() { return backupBase("uninstall"); }
    File ext4RecoveryRoot() { return backupBase(Mu300Ext4Recovery.ROOT_NAME); }
    boolean uninstallPending() { return UninstallRun.pending(uninstallRoot()) != null; }
    boolean uninstallWipe() { return method == Method.UNINSTALL && unResume == null && unPrep != null && unPrep.wipe; }
    void setUninstallReset(boolean v) { unResetOk = v; changed(); }
    static final String UNINSTALL_LOCAL_NO = "本机模式不提供卸载：卸载会让目标设备重启进入离线卸载（恢复出厂时还会清空本应用保存的备份和卸载记录），"
            + "本机无法全程监督和收尾。请在另一台手机上安装本应用，通过 USB OTG 或局域网连接这台设备后再卸载。";

    /** Package-side inputs for uninstall: Linux boot base, maintenance tools and the pinned worker. */
    private Uninstall.Pkg uninstallPkg(Mu300Internal.Io io) throws IOException {
        Uninstall.Pkg up = new Uninstall.Pkg();
        try (InputStream in = app.getAssets().open("uninstall-worker.sh")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
            up.worker = bo.toByteArray();
        }
        if (!Mu300Boot.sha256(up.worker).equals(UninstallBoot.WORKER_SHA)) throw new IOException("内置卸载脚本校验失败，请重新安装本应用");
        if (pkg.isMu300()) {
            up.family = "mu300";
            if (!muLocalReady()) throw new IOException("本机已没有内置安装文件，请重新选择安装包");
            java.util.Map<String, String> sums = Mu300Internal.localSums(muLocal());
            up.base = Mu300Internal.readAll(new File(muLocal(), Mu300Internal.BOOT));
            if (!Mu300Boot.sha256(up.base).equals(sums.get(Mu300Internal.BOOT))) throw new IOException("本机暂存的 boot-linux.img 已变化，请重新选择安装包");
            String storageLibrary = io.storageLibrary();
            if (storageLibrary == null || !Mu300Boot.sha256(storageLibrary.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .equals("7dc62136b7f95a3d18e8218b13649c04729fe3c7d29814acb68b82ceb1f44233"))
                throw new IOException("本机暂存的 UFS storage library 缺失或校验不符");
            up.storageLibrary = storageLibrary.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            up.repairDir = new File(muLocal(), Mu300Internal.REPAIR);
            up.repairSums = sums;
            unProfile = Mu300Internal.identify(io).profile;
        } else {
            up.family = "e5";
            ensureStaged();
            E5Part.Pkg ep = E5Part.loadPkg(io, pkg.rawBytes);
            up.base = ep.boot;
            unProfile = ep.profile;
            if (ep.hasTools) {
                final String tar = E5Part.P + "/files/ext4-tools.tar";
                up.ext4Tar = () -> {
                    String n = io.sh("stat -c %s " + RootShell.q(tar), true).trim();
                    if (!n.matches("\\d+") || Long.parseLong(n) > (64L << 20)) throw new IOException("ext4-tools.tar 大小异常");
                    return Mu300Internal.read(io, "cat " + RootShell.q(tar), Long.parseLong(n));
                };
            }
        }
        return up;
    }
    String uninstallActionText(Uninstall.Prepared p) {
        switch (p.action) {
            case "gap": return "OpenWrt 分区不与 userdata 相邻：删除后释放为未分配空间，不改动 Android 数据";
            case "grow-ext4": case "grow-f2fs": return "保留 Android 数据：离线删除 OpenWrt 分区，把 userdata 扩大并在线下扩容 "
                    + p.volume.filesystem + " 文件系统";
            default: return "无法保留 Android 数据，只能格式化 userdata（恢复出厂）后卸载。原因：" + p.reason;
        }
    }
    private void checkUninstall() throws IOException {
        if (mode == Mode.LOCAL) throw new IOException(UNINSTALL_LOCAL_NO);
        if (!targetReady()) throw new IOException(notReady(true));
        String why = problemFor(pkg, target());
        if (why != null) throw new IOException(why + "；请选择这台设备对应的安装包");
        Mu300Internal.Io io = muIo();
        lastLine = "读取安装包中的卸载启动镜像与维护工具";
        changed();
        Uninstall.Pkg up = uninstallPkg(io);
        File pend = UninstallRun.pending(uninstallRoot());
        if (pend != null) {
            UninstallRun.Rec rec = UninstallRun.load(pend);
            Uninstall.Device d = Uninstall.probe(io, up.rootName(), rec.s("profile"));
            String recordedKind = rec.s("storage_kind").isEmpty() ? "emmc" : rec.s("storage_kind");
            if (d.s.cid.equals(rec.s("cid")) && d.s.kind.equals(recordedKind)) {
                if (!up.family.equals(rec.s("family")))
                    throw new IOException("这台设备有未完成的卸载（" + rec.s("family") + " 系列），请选择与记录相同机型的安装包");
                unResume = pend;
                unSummary = "继续未完成的卸载：记录 " + pend.getName() + "，阶段 " + rec.s("stage")
                        + "\n数据处理：" + uninstallRecordAction(rec);
                checkedFingerprint = checkKey();
                append("✓ 找到这台设备未完成的卸载记录 " + pend.getName() + "（阶段 " + rec.s("stage") + "）。不会创建新的卸载计划；点“开始”继续收尾。");
                return;
            }
            append("  本机还有另一台设备（" + ("ufs".equals(rec.s("storage_kind")) ? "UFS 身份" : "eMMC CID") + " …" + rec.s("cid").substring(24)
                    + "）未完成的卸载记录 " + pend.getName() + "，与当前设备无关");
        }
        lastLine = "只读检查：分区表、启动镜像、安装证据与 Android data";
        changed();
        Uninstall.Device d = Uninstall.probe(io, up.rootName(), unProfile);
        Mu300Ext4Recovery.Tx recoveryTx = Mu300Ext4Recovery.findForCid(ext4RecoveryRoot(), d.s.cid);
        Uninstall.Prepared p;
        if (recoveryTx != null && ("journal_replay_started".equals(recoveryTx.stage())
                || "journal_replay_returned".equals(recoveryTx.stage()))) {
            append("发现上次 ext4 journal 修复中断记录；只执行只读核验，不会重放或重试。");
            p = Mu300Ext4Recovery.verifyInterrupted(io, ext4RecoveryRoot(), recoveryTx, d, up);
            ext4RecoveryMessage = "检测到上次修复中断；只读 e2fsck、分区身份与正常卸载证据均已通过。尚未卸载。";
        } else {
            if (recoveryTx != null && "manual_review".equals(recoveryTx.stage()))
                throw new IOException("此前 ext4 journal 修复未通过，需人工检查；应用不会再次写入或卸载。");
            try {
                p = Uninstall.prepare(io, d, up);
            } catch (IOException e) {
                String detail = e.getMessage() == null ? "" : e.getMessage();
                if ("mu300".equals(up.family) && detail.contains("needs_recovery(0x4)")) {
                    try {
                        ext4RecoveryPlan = Mu300Ext4Recovery.prepareCandidate(io, d, up);
                        ext4RecoveryMessage = "只读预检通过：仅允许对已验证的 MU300 mu300root 回放内部 ext4 journal。请查看范围并输入指定确认语；不会自动卸载。";
                        append("⚠ 已生成受限的 ext4 journal 修复候选；正常卸载检查仍失败，未写入任何分区。");
                    } catch (IOException candidateFailure) {
                        ext4RecoveryPlan = null;
                        ext4RecoveryMessage = "受限修复预检未通过：" + candidateFailure.getMessage();
                        append("  修复候选拒绝：" + candidateFailure.getMessage());
                    }
                }
                throw e;
            }
        }
        showPreparedUninstall(d, up, p);
    }

    private void showPreparedUninstall(Uninstall.Device d, Uninstall.Pkg up, Uninstall.Prepared p) {
        unPrep = p;
        unResume = null;
        StringBuilder b = new StringBuilder();
        b.append("将删除：").append(up.rootName()).append("（/dev/block/").append(d.s.root).append("，").append(Uninstall.human(p.openwrtBytes)).append("，含 OpenWrt 系统与全部设置）");
        b.append("\n内部存储：").append("ufs".equals(d.s.kind) ? "UFS" : "eMMC").append(" · /dev/block/").append(d.s.diskNode)
                .append(" · GPT 逻辑块 ").append(d.s.sectorSize).append(" B");
        b.append("\nAndroid 容量：").append(Uninstall.human(p.previousAndroidBytes)).append(" → ").append(Uninstall.human(p.androidBytes));
        b.append("\n归还 Android 的空间：").append(Uninstall.human(Math.max(0, p.reclaimed())));
        b.append("\n释放为空闲的空间：").append(Uninstall.human(p.released()));
        b.append("\n数据处理：").append(uninstallActionText(p));
        b.append("\n历史 GPT 对照：").append(ImportedBaselineStore.compare(app.getFilesDir(), p.before[0], p.before[1], p.total));
        unSummary = b.toString();
        checkedFingerprint = checkKey();
        append("✓ 检查通过，未修改任何分区。设备 " + d.model + "，Android 槽 " + d.slot);
        for (String l : unSummary.split("\n")) append("  " + l);
        if (p.wipe) append("  ⚠ 若继续，需要在确认页勾选“格式化 userdata（恢复出厂）后卸载”，并输入“清空数据”。Android 应用、账号、照片和内部存储都会被清空。");
    }
    static String uninstallRecordAction(UninstallRun.Rec rec) {
        Object a = rec.plan() == null ? null : rec.plan().get("data_action");
        String s = a == null ? "?" : String.valueOf(a);
        return "reset".equals(s) ? "格式化 userdata（恢复出厂）" : "gap".equals(s) ? "释放为未分配空间" : "保留 Android 数据（" + s + "）";
    }

    void runExt4Recovery() {
        if (busy != null) { append("正在执行：" + busy + "，请稍候"); return; }
        final Mu300Ext4Recovery.Candidate approved = ext4RecoveryPlan;
        if (approved == null) { append("没有有效的 ext4 修复候选；请先重新做只读检查"); return; }
        if (method != Method.UNINSTALL || mode == Mode.LOCAL) { append(UNINSTALL_LOCAL_NO); return; }
        ext4RecoveryPlan = null; // A failed or ambiguous run must be re-preflighted, never retried from a stale approval.
        ext4RecoveryMessage = "正在执行受限 journal 回放；不会自动卸载。";
        changed();
        submit("MU300 ext4 journal 修复与复核", true, () -> {
            try {
                if (!targetReady()) throw new IOException(notReady(true));
                String why = problemFor(pkg, target());
                if (why != null) throw new IOException(why + "；设备状态已变化，未修复");
                Mu300Internal.Io io = muIo();
                Uninstall.Pkg up = uninstallPkg(io);
                Uninstall.Prepared plan = Mu300Ext4Recovery.repairAndRecheck(io, ext4RecoveryRoot(), approved, up, unProfile);
                ext4RecoveryMessage = "ext4 journal 回放后，超级块、完整只读 e2fsck、MU300 安装证据和正常卸载计划均已复核通过。尚未卸载；请另行完成既有卸载确认。";
                showPreparedUninstall(plan.device, up, plan);
                append("✓ 受限修复与正常卸载只读复核完成；未开始卸载。");
            } catch (IOException | RuntimeException e) {
                ext4RecoveryMessage = "修复未完成或结果需要只读核验：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                changed();
                throw e;
            }
        });
    }

    private void installUninstall() throws IOException {
        if (mode == Mode.LOCAL) throw new IOException(UNINSTALL_LOCAL_NO);
        String block = installBlocker();
        if (block != null) throw new IOException(block + "；未做任何修改");
        Mu300Internal.Io io = muIo();
        installOk = false;
        installRc = null;
        hotspot = null;
        muOutcome = null;
        unOutcome = null;
        installedMethod = Method.UNINSTALL;
        stage1 = false;
        lastJob = "running";
        changed();
        try {
            Uninstall.Pkg up = uninstallPkg(io);
            UninstallRun.Outcome o;
            if (unResume != null) {
                append("继续卸载：记录 " + unResume.getName());
                o = UninstallRun.resume(io, unResume, up);
            } else {
                append("重新只读核对设备状态（与检查时必须一致）");
                Uninstall.Prepared fresh = Uninstall.prepare(io, Uninstall.probe(io, up.rootName(), unProfile), up);
                if (!fresh.action.equals(unPrep.action) || fresh.androidBytes != unPrep.androidBytes || !fresh.nodes.equals(unPrep.nodes))
                    throw new Uninstall.Refused("设备状态与检查时不同，请重新做只读检查（未做任何修改）");
                append("数据处理：" + uninstallActionText(fresh));
                o = UninstallRun.start(io, uninstallRoot(), fresh, up, unPrep.wipe && unResetOk ? unPrep.fingerprint : null, false);
            }
            unOutcome = o;
            installOk = o.complete;
            installRc = "0";
            lastJob = "done 0";
            append((o.complete ? "✓ " : "… ") + o.message);
            if (o.next != null) append("  " + o.next);
            File pend = UninstallRun.pending(uninstallRoot());
            unResume = pend;
            if (o.complete) { unPrep = null; checkedFingerprint = null; }
            else if (pend != null) checkedFingerprint = checkKey();
        } catch (IOException | RuntimeException e) {
            installRc = "1";
            lastJob = "done 1";
            unResume = UninstallRun.pending(uninstallRoot());
            append("  卸载记录与备份保存在 " + uninstallRoot() + "；不要删除。处理后重新连接，选“卸载并归还空间”重新检查会自动接上记录。");
            changed();
            throw e;
        } finally {
            progress = -1;
        }
        changed();
    }

    /** Puts the verified subset onto the current target (move locally, wireless-ADB push for LAN). */
    private void ensureStaged() throws IOException {
        if (pkg == null) throw new IOException("请先选择安装包");
        if (!targetReady()) throw new IOException(notReady(true));
        if (pkg.fingerprint.equals(stagedFingerprint) && verifyStaged()) return;
        if (!localCopyAvailable())
            throw new IOException("本地已没有解出的安装文件（已暂存到其他设备或已清理），请重新选择安装包");
        final String[] fp = {null};
        if (mode == Mode.LOCAL) {
            append("正在把安装文件移入本机 root 专用目录 " + BASE + "/pkg ……");
            RootShell.run("sh " + RootShell.q(helper().getAbsolutePath()) + " stage " + RootShell.q(localPkgDir().getAbsolutePath()), l -> {
                if (l.startsWith("OWRT-APK-STAGED=")) fp[0] = l.substring(16).trim();
                append(l);
            }, 1800);
            PackageInspector.deleteTree(new File(app.getFilesDir(), "stage"));
        } else {
            pushToRemote();
            append("在目标设备上用 root 校验并移入 " + BASE + "/pkg ……");
            rootRun("sh " + REMOTE_STAGE + "/owrt-apk.sh stage " + REMOTE_STAGE + "/pkg", l -> {
                if (l.startsWith("OWRT-APK-STAGED=")) fp[0] = l.substring(16).trim();
                append(l);
            }, 1800);
            try { adb().shellText("rm -rf " + REMOTE_STAGE); } catch (IOException ignored) { }
        }
        if (fp[0] == null || !fp[0].equals(pkg.fingerprint)) {
            stagedFingerprint = null;
            throw new IOException("暂存失败或指纹不一致，未写入任何分区");
        }
        stagedFingerprint = fp[0];
        append("✓ 已暂存到目标设备（指纹 " + fp[0].substring(0, 12) + "…）");
        changed();
    }

    static final String[] HELPER_FILES = {"owrt-apk.sh", "check-lib.sh", "userdata-lib.sh", "mu300-lib.sh"};

    /** Shell (root, on the target) that lists files missing from the staged package's checksum list and the helper hashes. */
    static String verifyScript() {
        StringBuilder b = new StringBuilder();
        b.append("P=").append(BASE).append("/pkg; if ! cd \"$P\" 2>/dev/null; then echo OWRT-APK-VERIFY=nopkg; else ")
         .append("if [ -f OWRT-APK-LAYOUT ] && [ \"$(cat OWRT-APK-LAYOUT)\" = mu300 ]; then L=OWRT-APK-SUMS; else L=files/SHA256SUMS; fi; ")
         .append("n=0; m=0; if [ -f \"$L\" ]; then while IFS= read -r line || [ -n \"$line\" ]; do [ -n \"$line\" ] || continue; n=$((n+1)); ")
         .append("f=${line#*  }; [ -f \"$f\" ] || { m=$((m+1)); echo \"OWRT-APK-MISSING=$f\"; }; done < \"$L\"; else echo OWRT-APK-MISSING=$L; m=1; fi; ")
         .append("echo \"OWRT-APK-VERIFY=$n $m\"; fi; ")
         .append("for h in");
        for (String h : HELPER_FILES) b.append(' ').append(h);
        b.append("; do echo \"OWRT-APK-HELPER=$h $(sha256sum ").append(BASE).append("/$h 2>/dev/null | cut -d ' ' -f1)\"; done");
        return b.toString();
    }

    /**
     * The status probe only says a package with this fingerprint was staged. Before reusing it, confirm on the
     * target that every file in its checksum list is still there (a successful install deletes the large payload,
     * like the package's customize.sh) and that the helper scripts are this APK's; refresh outdated helpers.
     */
    private boolean verifyStaged() throws IOException {
        final List<String> missing = new ArrayList<>();
        final java.util.Map<String, String> helpers = new java.util.HashMap<>();
        final String[] v = {null};
        rootRun(verifyScript(), l -> {
            if (l.startsWith("OWRT-APK-MISSING=")) missing.add(l.substring(17).trim());
            else if (l.startsWith("OWRT-APK-VERIFY=")) v[0] = l.substring(16).trim();
            else if (l.startsWith("OWRT-APK-HELPER=")) {
                String[] t = l.substring(16).trim().split(" ", 2);
                helpers.put(t[0], t.length > 1 ? t[1].trim() : "");
            }
        }, 120);
        if (v[0] == null || v[0].equals("nopkg") || !missing.isEmpty()) {
            append("目标设备上暂存的安装包不完整" + (missing.isEmpty() ? "" : "：缺少 " + missing.size() + " 个文件（"
                    + String.join("、", missing.subList(0, Math.min(3, missing.size()))) + (missing.size() > 3 ? " 等" : "") + "）")
                    + "。上次安装成功后安装器会删除镜像文件，这是正常的；现在重新暂存。");
            stagedFingerprint = null;
            return false;
        }
        List<String> old = new ArrayList<>();
        for (String h : HELPER_FILES) {
            String want = PackageInspector.hex(PackageInspector.sha256().digest(Mu300Internal.readAll(new File(app.getFilesDir(), h))));
            if (!want.equals(helpers.get(h))) old.add(h);
        }
        if (old.isEmpty()) return true;
        append("目标设备上的助手脚本来自其他版本的 APK（" + String.join("、", old) + "），更新为本版 ……");
        final boolean[] ok = {false};
        if (mode == Mode.LOCAL) {
            RootShell.run("sh " + RootShell.q(helper().getAbsolutePath()) + " helpers", l -> {
                if (l.equals("OWRT-APK-HELPERS-OK")) ok[0] = true;
                append(l);
            }, 120);
        } else {
            AdbConnection c = adb();
            c.shellText("rm -rf " + REMOTE_STAGE + " && mkdir -p " + REMOTE_STAGE);
            File[] fs = new File[HELPER_FILES.length];
            String[] rs = new String[HELPER_FILES.length];
            int[] ms = new int[HELPER_FILES.length];
            for (int i = 0; i < fs.length; i++) {
                fs[i] = new File(app.getFilesDir(), HELPER_FILES[i]);
                rs[i] = REMOTE_STAGE + "/" + HELPER_FILES[i];
                ms[i] = 0700;
            }
            c.push(fs, rs, ms, null);
            rootRun("sh " + REMOTE_STAGE + "/owrt-apk.sh helpers", l -> {
                if (l.equals("OWRT-APK-HELPERS-OK")) ok[0] = true;
                append(l);
            }, 120);
            try { c.shellText("rm -rf " + REMOTE_STAGE); } catch (IOException ignored) { }
        }
        if (!ok[0]) throw new IOException("更新目标设备上的助手脚本失败，未写入任何分区");
        append("✓ 助手脚本已更新");
        return true;
    }

    private void pushToRemote() throws IOException {
        AdbConnection c = adb();
        String df = c.shellText("rm -rf " + REMOTE_STAGE + " && mkdir -p " + REMOTE_STAGE + "/pkg && df -k /data/local/tmp 2>/dev/null | tail -n 1");
        long availKb = -1;
        String[] t = df.trim().split("\\s+");
        if (t.length >= 4) availKb = parseLong(t[3]);
        long needKb = (pkg.extractedBytes >> 10) + (64L << 10);
        if (availKb >= 0 && availKb < needKb)
            throw new IOException("目标设备 /data 空间不足：推送需要约 " + mib(needKb << 10) + "，可用 " + mib(availKb << 10));
        List<File> files = new ArrayList<>();
        List<String> remotes = new ArrayList<>();
        List<Integer> modes = new ArrayList<>();
        files.add(helper()); remotes.add(REMOTE_STAGE + "/owrt-apk.sh"); modes.add(0700);
        files.add(checkLib()); remotes.add(REMOTE_STAGE + "/check-lib.sh"); modes.add(0700);
        files.add(new File(app.getFilesDir(), "userdata-lib.sh")); remotes.add(REMOTE_STAGE + "/userdata-lib.sh"); modes.add(0700);
        files.add(new File(app.getFilesDir(), "mu300-lib.sh")); remotes.add(REMOTE_STAGE + "/mu300-lib.sh"); modes.add(0700);
        collect(localPkgDir(), "", files, remotes, modes);
        long total = 0;
        for (File f : files) total += f.length();
        final long tot = total;
        append("通过 ADB 推送 " + files.size() + " 个文件（" + mib(total) + "）到目标设备 " + REMOTE_STAGE + " ……");
        long t0 = System.currentTimeMillis();
        int[] mm = new int[modes.size()];
        for (int i = 0; i < mm.length; i++) mm[i] = modes.get(i);
        c.push(files.toArray(new File[0]), remotes.toArray(new String[0]), mm, new AdbConnection.Progress() {
            @Override public void bytes(long done) {
                int p = (int) (done * 100 / Math.max(1, tot));
                if (p != progress) { progress = p; lastLine = "推送安装文件 " + p + "%"; changed(); }
            }
            @Override public boolean cancelled() { return cancel; }
        });
        long ms = Math.max(1, System.currentTimeMillis() - t0);
        append(String.format(Locale.US, "✓ 推送完成，用时 %.0f 秒（约 %.1f MiB/s）", ms / 1000.0, total / 1048576.0 / (ms / 1000.0)));
        progress = -1;
    }

    private void collect(File dir, String rel, List<File> files, List<String> remotes, List<Integer> modes) throws IOException {
        File[] list = dir.listFiles();
        if (list == null) throw new IOException("无法读取本地安装文件");
        java.util.Arrays.sort(list);
        for (File f : list) {
            String r = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            if (f.isDirectory()) collect(f, r, files, remotes, modes);
            else { files.add(f); remotes.add(REMOTE_STAGE + "/pkg/" + r); modes.add(0600); }
        }
    }

    // ------------------------------------------------------------ check / install
    void runCheck() {
        final Method m = method;
        submit("只读检查（" + titleOf(m) + "）", true, () -> {
            checkedFingerprint = null;
            checkTarget = checkCard = checkSlots = null;
            clearDataCheck();
            String blocker = readOnlyCheckBlocker(pkg, target());
            if (blocker != null) throw new IOException(blocker);
            if (m == Method.UNINSTALL) { checkUninstall(); return; }
            if (muInternal(m)) { checkMu300(m); return; }
            ensureStaged();
            if (m == Method.TF) checkTf();
            else if (m == Method.REPART) checkE5Reinstall();
            else if (m == Method.RESUME) checkE5Resume();
            else checkData(m);
        });
    }

    private void checkTf() throws IOException {
        final boolean[] ok = {false};
        final int[] rc = {-1};
        lastLine = "运行安装包自带的只读预检";
        changed();
        helperRun("check", 1000, l -> {
            if (l.equals("OWRT-APK-CHECK-OK")) ok[0] = true;
            else if (l.startsWith("OWRT-APK-CHECK-RC=")) rc[0] = parseInt(l.substring(18));
            else if (l.startsWith("OWRT-APK-TARGET=")) checkTarget = l.substring(16).trim();
            else if (l.startsWith("OWRT-APK-TARGET-BYTES=")) checkTargetBytes = parseLong(l.substring(22));
            else if (l.startsWith("OWRT-APK-CARD=")) checkCard = l.substring(14).trim();
            else if (l.startsWith("OWRT-APK-SLOTS=")) checkSlots = l.substring(15).trim();
            else if (l.startsWith("OWRT-APK-FREE-KB=")) checkFreeKb = parseLong(l.substring(17));
        });
        if (ok[0] && rc[0] == 0 && checkTarget != null) {
            checkedFingerprint = pkg.fingerprint + ":" + Method.TF.name();
            append("✓ 检查通过，未修改任何分区。将被清空的 TF 目标：" + checkTarget + "（" + gib(checkTargetBytes) + "）");
        } else {
            append("✗ 检查未通过（退出码 " + rc[0] + "）。请根据上面的错误提示（E5-TF-ERROR / tf_die / BOOTCHAIN-ERROR 等）处理，未写入任何分区。");
        }
    }

    private static long[] three(String v) {
        String[] t = v.trim().split("\\s+");
        if (t.length != 3) return null;
        long[] r = new long[3];
        for (int i = 0; i < 3; i++) { r[i] = parseLong(t[i]); if (r[i] <= 0) return null; }
        return r;
    }

    private void checkData(Method m) throws IOException {
        final boolean[] ok = {false};
        final int[] rc = {-1};
        final long[][] range = {null};
        final String arg = m == Method.PART ? "part" : "image";
        lastLine = "运行 userdata 只读预检";
        changed();
        helperRun("check-data " + arg, 1000, l -> {
            if (l.equals("OWRT-APK-CHECK-OK")) ok[0] = true;
            else if (l.startsWith("OWRT-APK-CHECK-RC=")) rc[0] = parseInt(l.substring(18));
            else if (l.startsWith("OWRT-APK-SLOTS=")) checkSlots = l.substring(15).trim();
            else if (l.startsWith("OWRT-APK-DATA-NODE=")) dataNode = l.substring(19).trim();
            else if (l.startsWith("OWRT-APK-CRYPTO=")) crypto = l.substring(16).trim();
            else if (l.startsWith("OWRT-APK-UDATA-MIB=")) udataMib = parseLong(l.substring(19));
            else if (l.startsWith("OWRT-APK-USED-MIB=")) usedMib = parseLong(l.substring(18));
            else if (l.startsWith("OWRT-APK-FREE-MIB=")) freeMib = parseLong(l.substring(18));
            else if (l.startsWith("OWRT-APK-PART-RANGE=") && m == Method.PART) range[0] = three(l.substring(20));
            else if (l.startsWith("OWRT-APK-WIPE-RANGE=") && m == Method.PART) wipeRange = three(l.substring(20));
            else if (l.startsWith("OWRT-APK-KEEP-NO=")) keepNo = l.substring(17).trim();
            else if (l.startsWith("OWRT-APK-WIPE-NO=")) wipeNo = l.substring(17).trim();
            else if (l.startsWith("OWRT-APK-ENCRYPTED=")) e5Encrypted = "1".equals(l.substring(19).trim());
            else if (l.startsWith("OWRT-APK-IMAGE-RANGE=") && m == Method.IMAGE) range[0] = three(l.substring(21));
        });
        if (m == Method.PART && ok[0] && rc[0] == 0) { checkE5Part(range[0]); return; }
        if (ok[0] && rc[0] == 0 && range[0] != null && range[0][0] <= range[0][1]) {
            rangeMin = range[0][0];
            rangeMax = range[0][1];
            rangeDefault = Math.max(rangeMin, Math.min(rangeMax, range[0][2]));
            chosenMib = rangeDefault;
            checkedFingerprint = pkg.fingerprint + ":" + m.name();
            append("✓ 检查通过，未修改任何分区。/data 可用 " + freeMib + " MiB；镜像大小可选 " + rangeMin + "–" + rangeMax
                    + " MiB（默认 " + rangeDefault + " MiB）");
        } else {
            append("✗ 检查未通过（退出码 " + rc[0] + "）。请根据上面的 E5-DATA-ERROR / E5-TF-ERROR / BOOTCHAIN-ERROR 提示处理，未写入任何分区。");
        }
    }
    /** E5 PART: preserving shrink (KEEP) and/or the two-stage format-userdata install, as part_target()/part_userdata_policy(). */
    private void checkE5Part(long[] keep) throws IOException {
        keepRange = keep != null && keep[0] <= keep[1] ? keep : null;
        if (keepRange == null && keepNo == null) keepNo = "保留数据缩容放不下";
        if (wipeRange != null && wipeRange[0] > wipeRange[1]) wipeRange = null;
        if (wipeRange == null && wipeNo == null) wipeNo = "userdata 太小";
        if (wipeRange != null && wipeNo == null) {
            if (mode == Mode.LOCAL) {
                wipeNo = "格式化 userdata 会清空这台手机自己的数据，包括本应用保存的事务记录和备份，第二阶段无法续上；请用另一台手机通过 USB/局域网操作";
            } else {
                append("  只读检查两阶段安装的前提（启动镜像、启动控制块、BCB、分区表）…");
                e5Pkg = E5Part.loadPkg(muIo(), pkg.rawBytes);
                E5Part.WipeCheck w = E5Part.checkWipe(muIo(), e5Pkg, false);
                if (!w.ok) wipeNo = w.reason;
            }
        }
        if (wipeNo != null) wipeRange = null;
        if (keepRange == null && wipeRange == null) {
            append("✗ 检查未通过：保留数据缩容不可用（" + keepNo + "）；格式化 userdata 不可用（" + wipeNo + "）。未写入任何分区。");
            return;
        }
        rangeMin = Math.min(keepRange != null ? keepRange[0] : Long.MAX_VALUE, wipeRange != null ? wipeRange[0] : Long.MAX_VALUE);
        rangeMax = Math.max(keepRange != null ? keepRange[1] : 0, wipeRange != null ? wipeRange[1] : 0);
        // The default never lands on the destructive route when the data can be kept.
        rangeDefault = keepRange != null ? keepRange[2] : wipeRange[2];
        chosenMib = rangeDefault;
        checkedFingerprint = pkg.fingerprint + ":" + Method.PART.name();
        append("✓ 检查通过，未修改任何分区。userdata " + dataNode + "：" + udataMib + " MiB，已用 " + usedMib + " MiB，"
                + (e5Encrypted ? "已加密" : "未加密") + "（" + crypto + "）");
        append("  路径一 保留数据缩容：" + (keepRange != null ? keepRange[0] + "–" + keepRange[1] + " MiB" : "不可用，" + keepNo));
        append("  路径二 格式化 userdata（Android 数据全部清空，分两阶段）：" + (wipeRange != null ? wipeRange[0] + "–" + wipeRange[1] + " MiB" : "不可用，" + wipeNo));
        append("  默认 " + chosenMib + " MiB 的路径：" + routeText());
    }
    private void checkE5Reinstall() throws IOException {
        e5Pkg = E5Part.loadPkg(muIo(), pkg.rawBytes);
        E5Part.Reinstall r = E5Part.checkReinstall(muIo(), e5Pkg);
        rangeMin = rangeMax = rangeDefault = chosenMib = r.root.size / 2048;
        dataNode = "/dev/block/" + r.data.node;
        checkedFingerprint = pkg.fingerprint + ":" + Method.REPART.name();
        append("✓ 检查通过，未修改任何分区。将重装 /dev/block/" + r.root.node + "（e5root，" + chosenMib + " MiB）；userdata 与分区表不动");
    }
    private void checkE5Resume() throws IOException {
        e5Pkg = E5Part.loadPkg(muIo(), pkg.rawBytes);
        resumeInfo = E5Part.checkResume(muIo(), e5Pkg, backupBase("e5-part"));
        checkedFingerprint = pkg.fingerprint + ":" + Method.RESUME.name();
        append("✓ 检查通过，未修改任何分区。" + resumeInfo);
    }
    /** E5 internal flows ported from part-two-stage.py; bookkeeping as installMu300(). */
    private void installE5(Method m) throws IOException {
        String block = installBlocker();
        if (block != null) throw new IOException(block + "；未做任何修改");
        installOk = false;
        installRc = null;
        hotspot = null;
        muOutcome = null;
        installedMethod = m;
        stage1 = false;
        lastJob = "running";
        changed();
        try {
            E5Part.Pkg ep = E5Part.loadPkg(muIo(), pkg.rawBytes);
            if (e5Pkg != null && !(ep.bootSha.equals(e5Pkg.bootSha) && String.valueOf(ep.imageSha).equals(String.valueOf(e5Pkg.imageSha))))
                throw new IOException("目标设备上暂存的安装包与检查时不同；请重新做只读检查");
            File base = backupBase("e5-part");
            Mu300Internal.Outcome o;
            if (m == Method.PART) {
                append("安装路径：格式化 userdata（两阶段）。原因：" + routeText());
                String ssid = ep.hotspotDefault, key = E5Part.randomKey();
                hotspot = ssid + " / " + key;
                o = E5Part.start(muIo(), ep, base, dataNode, chosenMib, udataMib - chosenMib, growFs ? 1 : 0, "", ssid, key, mode == Mode.LOCAL);
            } else if (m == Method.REPART) {
                o = E5Part.reinstall(muIo(), ep, base, growFs ? 1 : 0);
            } else {
                o = E5Part.resume(muIo(), ep, base);
            }
            muOutcome = o;
            stage1 = m == Method.PART;
            installOk = true;
            installRc = "0";
            lastJob = "done 0";
            append("✓ " + o.message);
            append("  " + o.next);
            append("  事务记录与备份保存在本机：" + base);
            if (hotspot != null) append("  热点：" + hotspot);
        } catch (IOException | RuntimeException e) {
            installRc = "1";
            lastJob = "done 1";
            changed();
            throw e;
        } finally {
            progress = -1;
        }
        changed();
    }
    void runInstall() {
        final Method m = method;
        submit("传输及安装系统（" + titleOf(m) + "）", true, () -> {
            if (!checkPassed()) throw new IOException("请先对当前安装包和安装方式完成只读检查");
            if (m == Method.UNINSTALL) { installUninstall(); return; }
            if (muInternal(m)) { installMu300(m); return; }
            if (m == Method.REPART || m == Method.RESUME || (m == Method.PART && route() == Route.FORMAT)) { installE5(m); return; }
            if (m == Method.PART) {
                String block = installBlocker();
                if (block != null) throw new IOException(block + "；未做任何修改");
                append("安装路径：保留数据缩容。原因：" + routeText());
            }
            String args;
            if (m == Method.TF) {
                long need = pkg.rawBytes + pkg.bootBytes + (256L << 20);
                if (checkFreeKb > 0 && (checkFreeKb << 10) < need)
                    throw new IOException("目标设备 /data 空间不足：需要约 " + mib(need) + "，当前 " + mib(checkFreeKb << 10));
                args = "start tf";
            } else {
                if (chosenMib < rangeMin || chosenMib > rangeMax || rangeMax <= 0)
                    throw new IOException("所选大小不在检查给出的范围内（" + rangeMin + "–" + rangeMax + " MiB）");
                args = m == Method.PART ? "start part " + chosenMib + " " + (growFs ? 1 : 0) : "start image " + chosenMib;
            }
            installOk = false;
            installRc = null;
            hotspot = null;
            installedMethod = m;
            lastJob = "running";
            changed();
            followOutput(args);
        });
    }

    void attach() {
        submit("查看安装进度", true, () -> {
            if (!targetReady()) throw new IOException(notReady(false));
            followOutput("follow");
        });
    }

    private void followOutput(String cmd) throws IOException {
        final String[] rc = {null};
        final boolean[] ok = {false};
        try {
            helperRun(cmd, 0, l -> {
                if (l.startsWith("OWRT-APK-RC=")) rc[0] = l.substring(12).trim();
                else if (l.equals("OWRT-APK-INSTALL-OK") || l.startsWith("E5-TF-INSTALL-OK")) ok[0] = true;
                else if (l.equals("OWRT-APK-JOB=none")) rc[0] = "none";
                int h = l.indexOf("Hotspot / 热点：");
                if (h >= 0) hotspot = l.substring(h + "Hotspot / 热点：".length()).trim();
                int h2 = l.indexOf("热点：已导入 ");
                if (h2 >= 0) hotspot = l.substring(h2 + "热点：已导入 ".length()).trim();
                changed();
            });
        } catch (IOException e) {
            if (mode == Mode.LAN) {
                lastJob = "running";
                throw new IOException("与目标设备的连接中断：" + e.getMessage()
                        + "。安装任务在目标设备上后台运行，不会因此停止；恢复 USB 或网络连接后重新连接并点“继续未完成的安装”。");
            }
            throw e;
        }
        if ("none".equals(rc[0])) { append("没有安装任务记录。"); lastJob = "none"; return; }
        installRc = rc[0];
        if ("0".equals(rc[0]) && ok[0]) {
            installOk = true;
            lastJob = "done 0";
            // 与安装包 customize.sh 相同，成功后目标设备上的大文件已删除；下次检查必须重新暂存。
            stagedFingerprint = null;
            if (installedMethod == Method.PART) {
                append("✓ 缩容安装已就绪：载荷、内核和启动槽已写入并校验，userdata 尚未改动。");
                append("  请手动重启：下次启动时 initramfs 会先离线检查并缩小 userdata，再新建 e5root 分区写入 OpenWrt，"
                        + "随后自动再重启一次进入 OpenWrt。整个过程可能需要十几分钟，屏幕显示进度，期间不要断电或强制关机。");
            } else if (pkg != null && pkg.isMu300()) {
                append("✓ OpenWrt 已安装到 TF 卡并校验。重启后进入 OpenWrt；如果 Linux 启动失败，设备会自动返回 Android。");
            } else {
                append("✓ OpenWrt 已安装并校验。重启后进入 OpenWrt（首次启动约 2 分钟；失败两次会自动回到 Android）。");
            }
            if (hotspot != null) append("  热点：" + hotspot);
            append("  管理地址：" + adminUrl() + "  用户 root，初始密码 root（请尽快修改）");
        } else if (rc[0] == null || rc[0].equals("lost")) {
            lastJob = "lost";
            append("✗ 与安装任务的连接中断或任务异常结束。不要立即重试；先查看进度，并检查 " + logDir() + "。");
        } else {
            lastJob = "done " + rc[0];
            append("✗ 安装未完成（退出码 " + rc[0] + "）。安装器在失败时不会切槽；日志和备份保留在 " + logDir() + "，"
                    + "检查设备状态前不要重试。同一次开机内安装器锁会阻止重试，处理后重启 Android 再试。");
        }
        changed();
    }

    void clean() {
        submit("清理暂存文件", false, () -> {
            PackageInspector.deleteTree(new File(app.getFilesDir(), "stage"));
            PackageInspector.deleteTree(muLocal());
            append("已删除本机解出的安装文件");
            if (targetReady()) {
                helperRunIfPresent("clean");
                // MU300 internal installs stage in /data/local/tmp/mu300-desktop-*; a directory whose background
                // step is still alive (run.pid) is left alone.
                rootRun("for d in /data/local/tmp/mu300-desktop-* /data/local/tmp/openwrt-read-*; do [ -d \"$d\" ] || continue; "
                        + "p=$(cat \"$d/run.pid\" 2>/dev/null); "
                        + "if [ -n \"$p\" ] && [ ! -f \"$d/run.rc\" ] && kill -0 \"$p\" 2>/dev/null; then echo \"保留（后台步骤仍在运行，PID $p）：$d\"; "
                        + "else rm -rf \"$d\" && echo \"已删除目标设备暂存目录：$d\"; fi; done; true", this::append, 300);
                if (mode == Mode.LAN) { try { adb().shellText("rm -rf " + REMOTE_STAGE); } catch (IOException ignored) { } }
                refreshStatus();
            }
            pkg = null;
            stagedFingerprint = null;
            checkedFingerprint = null;
        });
    }

    private void helperRunIfPresent(String args) throws IOException {
        rootRun("if [ -f " + BASE_HELPER + " ]; then sh " + BASE_HELPER + " " + args + "; else echo 'OWRT-APK: 目标设备没有暂存文件'; fi",
                this::append, 300);
    }

    void reboot() {
        submit("重启目标设备", false, () -> {
            try {
                rootRun("sync; (sleep 1; svc power reboot || reboot) >/dev/null 2>&1 &", this::append, 60);
            } catch (IOException e) {
                if (mode == Mode.LOCAL) throw e;
            }
            if (mode == Mode.LAN) {
                if (adb != null) adb.close();
                adb = null;
                append("已发送重启命令；目标设备重启后 ADB 连接（USB 或无线）会断开。");
            }
        });
    }

    void requestCancel() { cancel = true; }

    /**
     * Stops waiting for the running task: closes the link so a blocked read returns at once. A step running
     * detached on the target is not killed (killing a write half-way is worse than letting it finish).
     */
    void stopTask() {
        if (busy == null) return;
        stopRequested = true;
        cancel = true;
        String st = step;
        append("■ 已请求停止" + (st != null ? "（当前步骤：" + st + "）" : "") + (stepWrites
                ? "。目标设备上的写入在后台继续，不会被中途打断：请保持目标设备通电、不要重启，几分钟后重新连接查看状态"
                : "。当前步骤不写入目标设备"));
        AdbConnection c = adb;
        if (c != null) c.close();
        changed();
    }

    // ------------------------------------------------------------ helpers
    private String displayName(Uri uri) {
        try (Cursor c = app.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) { }
        String s = uri.getLastPathSegment();
        return s == null ? "安装包.zip" : s;
    }

    private long displaySize(Uri uri) {
        try (Cursor c = app.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) { }
        return -1;
    }

    static String mib(long b) { return String.format(Locale.US, "%.1f MiB", b / 1048576.0); }
    static String gib(long b) { return String.format(Locale.US, "%.2f GiB", b / 1073741824.0); }
    private static int parseInt(String s) { try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; } }
    private static long parseLong(String s) { try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return -1; } }

    static Intent shareIntent(String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        return Intent.createChooser(i, "导出日志");
    }
}
