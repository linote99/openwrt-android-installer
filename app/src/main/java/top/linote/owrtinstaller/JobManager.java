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
        TF("TF 卡安装", "清空所选 TF 卡并写入 OpenWrt；内部存储与 Android 数据不变"),
        PART("缩容 userdata 安装", "保留 Android 数据：下次启动时离线缩小 userdata，新建 e5root 分区写入 OpenWrt（仅限未加密 userdata）"),
        IMAGE("userdata 镜像安装【实验性】", "在 /data/e5linux 中创建 openwrt.ext4 镜像，不格式化 userdata（仅限未加密 userdata）");
        final String title, summary;
        Method(String t, String s) { title = t; summary = s; }
    }

    static final class Device {
        String model = "?", device = "?", slot = "?", vbstate = "?", kernelArch = "?", serial = "", platform = "?";
        String root;          // su path ("" = adbd already root), null when unavailable
        boolean probed;
        String endpoint;      // remote only: "host:port", or a USB label
        String usbName;       // remote over USB OTG: UsbDevice.getDeviceName()
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
    String lastJob = "none";       // none | running | done N | lost
    String hotspot;                // "SSID / key" from the installer log
    boolean installOk;
    String installRc;              // null while running/unknown
    volatile String lastLine = "";
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
    }

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
    boolean checkPassed() { return packageReady() && checkKey() != null && checkKey().equals(checkedFingerprint); }
    boolean localCopyAvailable() {
        return new File(localPkgDir(), "files/magisk-tf-install.sh").isFile()
                || new File(localPkgDir(), "payload/tf-install-worker.sh").isFile();
    }

    /** MU300 packages only port the package's own TF worker. */
    boolean methodAllowed(Method m) { return m == Method.TF || pkg == null || !pkg.tfOnly; }

    String adminUrl() { return pkg != null ? pkg.adminUrl : "http://192.168.9.1"; }
    String logDir() { return pkg != null ? pkg.logDir : "/data/adb/e5-tf-install"; }

    boolean modelMatches() { return pkg != null && target() != null && problemFor(pkg, target()) == null; }

    /** Null when the package's identity rules accept this device (model, board, bootloader state). */
    static String problemFor(PackageInspector.Result p, Device d) {
        if (p == null || d == null || !d.probed) return null;
        return p.targetProblem(d.model, d.device, d.vbstate, d.platform);
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
        dataNode = crypto = null;
        udataMib = usedMib = freeMib = rangeMin = rangeMax = rangeDefault = chosenMib = 0;
    }

    void setMethod(Method m) {
        if (busy != null) { append("正在执行：" + busy + "，完成后再切换安装方式"); return; }
        if (method == m) return;
        if (!methodAllowed(m)) { append("该安装包（" + pkg.title + "）只支持 TF 卡安装"); changed(); return; }
        method = m;
        checkedFingerprint = null;
        checkTarget = checkCard = null;
        clearDataCheck();
        append("安装方式：" + m.title + "（需要重新做只读检查）");
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
        progress = -1;
        taskStarted = System.currentTimeMillis();
        lastLine = "";
        changed();
        if (foreground) InstallService.start(app, what);
        worker.execute(() -> {
            append("▶ " + what);
            try {
                t.run();
            } catch (PackageInspector.PackageException e) {
                append("✗ " + e.getMessage());
            } catch (Exception e) {
                append("✗ " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            } finally {
                busy = null;
                progress = -1;
                if (foreground) InstallService.stop(app);
                append("■ 结束：" + what);
                changed();
            }
        });
        return true;
    }

    // ------------------------------------------------------------ target execution
    private AdbConnection adb() throws IOException {
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
            AdbConnection c = adb();
            prefs.edit().putString("endpoint", ep).apply();
            finishRemote(d, c);
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
        if (busy != null) { append("正在执行：" + busy + "，完成后再连接"); return; }
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
            remote = d;
            changed();
            append("通过 USB 连接 " + label + " ……");
            AdbConnection c = adb();
            finishRemote(d, c);
        });
    }

    /** Common part after a wired or wireless ADB connection: device properties and root. */
    private void finishRemote(Device d, AdbConnection c) throws IOException {
            append("✓ 已连接（" + (c.banner.containsKey("ro.product.model") ? c.banner.get("ro.product.model") : "设备") + "）");
            parseProps(d, c.shellText(propsScriptPlain()));
            d.probed = true;
            if ("0".equals(c.shellText("id -u").trim())) {
                d.root = "";
            } else {
                append("正在请求目标设备的 root：如目标设备弹出超级用户请求，请允许“Shell”。");
                for (String s : new String[]{"su", "/debug_ramdisk/su", "/sbin/su"}) {
                    String out = c.shellText(s + " -c id 2>/dev/null");
                    if (out.matches("(?s).*\\buid=0\\b.*")) { d.root = s; break; }
                }
            }
            describe(d, d.usbName != null ? "USB 设备" : "局域网设备");
            if (d.root == null) {
                append("✗ 目标设备没有可用的 root：请在其 Magisk / KernelSU / KSUNext 中允许 Shell 的超级用户请求后重新连接。");
                changed();
                return;
            }
            append("✓ 目标设备 root 可用（" + (d.root.isEmpty() ? "adbd 已是 root" : d.root) + "）");
            refreshStatus();
    }

    void disconnectLan() {
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
            append("  版本：" + r.version + "；适用机型：" + r.title + " " + r.androidModels + "（profile " + r.profile
                    + (r.board != null ? "，主板 " + r.board : "") + (r.unlockRequired ? "，需已解锁" : "，不要求 orange") + "）");
            if (r.tfOnly && method != Method.TF) {
                method = Method.TF;
                checkedFingerprint = null;
                clearDataCheck();
                append("  该安装包只支持 TF 卡安装，已切换到“" + Method.TF.title + "”");
            }
            String why = problemFor(r, target());
            if (why != null) append("✗ " + why + "：安装包脚本会拒绝安装。不要跨机型刷入。");
            changed();
        });
    }

    /** Puts the verified subset onto the current target (move locally, wireless-ADB push for LAN). */
    private void ensureStaged() throws IOException {
        if (pkg == null) throw new IOException("请先选择安装包");
        if (!targetReady()) throw new IOException(mode == Mode.LOCAL ? "本机没有 root" : "请先连接目标设备（USB 或局域网）并取得 root");
        if (pkg.fingerprint.equals(stagedFingerprint)) return;
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
        submit("只读检查（" + m.title + "）", true, () -> {
            ensureStaged();
            checkedFingerprint = null;
            checkTarget = checkCard = checkSlots = null;
            clearDataCheck();
            if (m == Method.TF) checkTf(); else checkData(m);
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
            else if (l.startsWith("OWRT-APK-IMAGE-RANGE=") && m == Method.IMAGE) range[0] = three(l.substring(21));
        });
        if (ok[0] && rc[0] == 0 && range[0] != null && range[0][0] <= range[0][1]) {
            rangeMin = range[0][0];
            rangeMax = range[0][1];
            rangeDefault = Math.max(rangeMin, Math.min(rangeMax, range[0][2]));
            chosenMib = rangeDefault;
            checkedFingerprint = pkg.fingerprint + ":" + m.name();
            if (m == Method.PART)
                append("✓ 检查通过，未修改任何分区。userdata " + dataNode + "：" + udataMib + " MiB，已用 " + usedMib
                        + " MiB；OpenWrt 分区可选 " + rangeMin + "–" + rangeMax + " MiB（默认 " + rangeDefault + " MiB）");
            else
                append("✓ 检查通过，未修改任何分区。/data 可用 " + freeMib + " MiB；镜像大小可选 " + rangeMin + "–" + rangeMax
                        + " MiB（默认 " + rangeDefault + " MiB）");
        } else {
            append("✗ 检查未通过（退出码 " + rc[0] + "）。请根据上面的 E5-DATA-ERROR / E5-TF-ERROR / BOOTCHAIN-ERROR 提示处理，未写入任何分区。");
        }
    }

    void runInstall() {
        final Method m = method;
        submit("传输及安装系统（" + m.title + "）", true, () -> {
            if (!checkPassed()) throw new IOException("请先对当前安装包和安装方式完成只读检查");
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
            if (!targetReady()) throw new IOException(mode == Mode.LOCAL ? "本机没有 root" : "请先连接目标设备（USB 或局域网）");
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
            append("已删除本机解出的安装文件");
            if (targetReady()) {
                helperRunIfPresent("clean");
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
