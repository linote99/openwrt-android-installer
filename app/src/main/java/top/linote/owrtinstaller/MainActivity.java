package top.linote.owrtinstaller;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * Tabbed installer in the style of the desktop OpenWrt Installer. Fixed header and tab strip,
 * one page per step (设备 / 安装包 / 安装方式 / 检查与计划 / 安装 / 完成) plus a 日志 tab,
 * and a fixed 上一步 / 下一步 bar. The page is rebuilt only when the structural state or the tab
 * changes; timers and progress update in place.
 */
public class MainActivity extends Activity implements JobManager.Listener {
    private static final int PICK_ZIP = 7;
    static final String VERSION = "1.4.0";

    static final int T_DEVICE = 0, T_PACKAGE = 1, T_METHOD = 2, T_PLAN = 3, T_INSTALL = 4, T_DONE = 5, T_LOG = 6;
    private static final String[] TAB_NAMES = {"设备", "安装包", "安装方式", "检查与计划", "安装", "完成", "日志"};
    private static final int STEPS = 6;

    private JobManager jm;
    private Ui ui;
    private LinearLayout tabStrip, navBar;
    private HorizontalScrollView tabScroll;
    private ScrollView scroll;
    private LinearLayout content;
    private TextView logView, logCount, statusLine, stageLine, timerLine, tabBadge;
    private ProgressBar bar;
    private TextView sizeInfo, startBtn;
    private String signature = "";
    private String endpointText;
    private String sizeText;
    private boolean confirmed, sizeValid = true;
    private int tab = -1;
    private boolean methodSeen;
    // previous values for automatic tab advance
    private boolean wasTargetReady, wasInstallOk, wasInstalling, wasBusy;
    private String wasPkg;

    private final Handler tick = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() { updateDynamic(); tick.postDelayed(this, 1000); }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        jm = JobManager.get(this);
        ui = new Ui(this);
        setTitle("OpenWrt 安装器");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 21) getWindow().setStatusBarColor(ui.bg);
        endpointText = jm.lastEndpoint();
        if (state != null) {
            tab = state.getInt("tab", -1);
            methodSeen = state.getBoolean("methodSeen");
        }

        LinearLayout root = ui.col();
        root.setBackgroundColor(ui.bg);
        root.addView(header(), ui.mw(0));
        tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabStrip = ui.row();
        tabStrip.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(8));
        tabScroll.addView(tabStrip);
        root.addView(tabScroll, ui.mw(0));
        View line = ui.divider();
        root.addView(line);
        scroll = new ScrollView(this);
        content = ui.col();
        content.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(ui.divider());
        navBar = ui.row();
        navBar.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(12));
        navBar.setBackgroundColor(ui.surface);
        root.addView(navBar, ui.mw(0));
        setContentView(root);

        logView = ui.mono("");
        logView.setEllipsize(null);
        logView.setText(jm.logText());
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (jm.mode == JobManager.Mode.LOCAL && !jm.local.probed && jm.busy == null) jm.probeLocal();
        wasTargetReady = jm.targetReady();
        wasInstallOk = jm.installOk;
        wasInstalling = installing();
        wasPkg = jm.pkg == null ? null : jm.pkg.fingerprint;
        if (tab < 0) tab = initialTab();
        if (tab != T_LOG) lastStepTab = tab;
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
        out.putBoolean("methodSeen", methodSeen);
    }

    @Override protected void onResume() {
        super.onResume();
        jm.addListener(this);
        signature = "";
        if (jm.mode == JobManager.Mode.LAN) jm.refreshUsbQuiet();
        onStateChanged();
        tick.post(ticker);
    }

    @Override protected void onPause() {
        jm.removeListener(this);
        tick.removeCallbacks(ticker);
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (tab == T_LOG) { go(lastStepTab); return; }
        if (tab > T_DEVICE && tab <= T_DONE) { go(tab - 1); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------ state
    private boolean installing() {
        String b = jm.busy;
        return b != null && (b.startsWith("传输及安装") || b.startsWith("查看安装进度"));
    }

    private boolean tabDone(int t) {
        switch (t) {
            case T_DEVICE: return jm.targetReady();
            case T_PACKAGE: return jm.pkg != null;
            case T_METHOD: return methodSeen || jm.checkPassed();
            case T_PLAN: return jm.checkPassed() || jm.installOk;
            case T_INSTALL: return jm.installOk;
            default: return false;
        }
    }

    private int initialTab() {
        if (jm.installOk) return T_DONE;
        if (installing() || "running".equals(jm.lastJob)) return T_INSTALL;
        if (!jm.targetReady()) return T_DEVICE;
        if (jm.pkg == null) return T_PACKAGE;
        if (!methodSeen) return T_METHOD;
        return T_PLAN;
    }

    private int lastStepTab = T_DEVICE;

    private void go(int t) {
        if (t < 0 || t > T_LOG) return;
        if (tab == T_METHOD || t > T_METHOD && t != T_LOG) methodSeen = true;
        if (t != T_LOG) lastStepTab = t;
        if (t == tab) return;
        tab = t;
        signature = "";
        onStateChanged();
        scroll.scrollTo(0, 0);
    }

    private String sig() {
        JobManager.Device d = jm.target();
        return tab + "|" + jm.mode + "|" + (jm.busy != null) + "|" + jm.busy + "|" + (d == null ? "-" : d.probed + "," + d.root + "," + d.model + "," + d.endpoint)
                + "|" + (jm.pkg == null ? "-" : jm.pkg.fingerprint) + "|" + jm.stagedFingerprint + "|" + jm.checkPassed()
                + "|" + jm.method + "|" + jm.scanning + "|" + jm.scanHits.size() + "|" + jm.usbHits.size() + "|" + jm.installOk + "|" + jm.lastJob
                + "|" + jm.rangeMin + "-" + jm.rangeMax + "|" + jm.hotspot + "|" + (jm.progress >= 0) + "|" + jm.localCopyAvailable() + "|" + methodSeen;
    }

    @Override
    public void onStateChanged() {
        autoAdvance();
        String s = sig();
        if (!s.equals(signature)) {
            signature = s;
            rebuild();
        }
        updateDynamic();
    }

    /** Moves forward after a step finishes, but only from the page where that step was started. */
    private void autoAdvance() {
        boolean ready = jm.targetReady();
        String pkg = jm.pkg == null ? null : jm.pkg.fingerprint;
        boolean inst = installing();
        int to = -1;
        boolean finished = wasBusy && jm.busy == null;   // a detect / connect task just ended
        if (ready && finished && tab == T_DEVICE) to = jm.pkg == null ? T_PACKAGE : T_METHOD;
        if (pkg != null && !pkg.equals(wasPkg) && tab == T_PACKAGE) to = T_METHOD;
        if (inst && !wasInstalling) to = T_INSTALL;
        if (jm.installOk && !wasInstallOk) to = T_DONE;
        wasBusy = jm.busy != null;
        wasTargetReady = ready; wasPkg = pkg; wasInstalling = inst; wasInstallOk = jm.installOk;
        if (to >= 0 && to != tab) {
            if (tab == T_METHOD || to > T_METHOD) methodSeen = true;
            tab = to;
            lastStepTab = to;
            signature = "";
            if (scroll != null) scroll.post(() -> scroll.scrollTo(0, 0));
        }
    }

    @Override
    public void onLog(String line) {
        logView.append(line + "\n");
        if (logView.getLineCount() > 1500) {
            String all = jm.logText();
            logView.setText(all.length() > 60000 ? all.substring(all.length() - 60000) : all);
        }
        if (logCount != null) logCount.setText(jm.logLines().size() + " 条");
        if (tabBadge != null) tabBadge.setText(String.valueOf(jm.logLines().size()));
    }

    private void updateDynamic() {
        boolean idle = jm.busy == null;
        if (statusLine != null) {
            statusLine.setText(idle ? (jm.installOk ? "●  已完成" : jm.checkPassed() ? "●  检查通过，等待确认" : "●  空闲")
                    : "●  正在执行：" + jm.busy);
            colorDot(statusLine, idle ? (jm.installOk || jm.checkPassed() ? ui.primary : ui.muted) : ui.primary);
        }
        if (stageLine != null) stageLine.setText(jm.lastLine == null || jm.lastLine.isEmpty() ? (idle ? "—" : "准备中……") : jm.lastLine);
        if (timerLine != null) {
            long s = idle ? 0 : (System.currentTimeMillis() - jm.taskStarted) / 1000;
            timerLine.setText(idle ? "" : String.format(Locale.US, "用时 %d:%02d", s / 60, s % 60));
        }
        if (bar != null) {
            bar.setVisibility(!idle && jm.progress >= 0 ? View.VISIBLE : View.GONE);
            if (jm.progress >= 0) bar.setProgress(jm.progress);
        }
    }

    private void colorDot(TextView t, int color) {
        android.text.SpannableString sp = new android.text.SpannableString(t.getText());
        sp.setSpan(new android.text.style.ForegroundColorSpan(color), 0, 1, 0);
        t.setText(sp);
    }

    // ------------------------------------------------------------ frame
    private View header() {
        LinearLayout head = ui.row();
        head.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(4));
        TextView icon = ui.text("⛁", 18, ui.onPrimary, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(ui.round(ui.primary, 0, 9));
        head.addView(icon, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
        LinearLayout ht = ui.col();
        ht.setPadding(ui.dp(12), 0, 0, 0);
        ht.addView(ui.text("OpenWrt 安装器", 16, ui.text, true));
        ht.addView(ui.text("安装、更新与恢复 · 安卓版 v" + VERSION, 12, ui.muted, false));
        head.addView(ht, ui.weight(1));
        TextView about = ui.text("使用说明", 13, ui.text, true);
        about.setGravity(Gravity.CENTER);
        about.setContentDescription("使用说明");
        about.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        about.setBackground(ui.ripple(ui.round(ui.surface, ui.border, 16)));
        about.setOnClickListener(v -> startActivity(new android.content.Intent(this, AboutActivity.class)));
        head.addView(about, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(34)));
        return head;
    }

    private void buildTabs() {
        tabStrip.removeAllViews();
        tabBadge = null;
        View selected = null;
        for (int i = 0; i <= T_LOG; i++) {
            final int t = i;
            boolean on = t == tab, done = tabDone(t);
            LinearLayout chip = ui.row();
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(ui.dp(10), ui.dp(7), ui.dp(12), ui.dp(7));
            chip.setBackground(ui.ripple(ui.round(on ? ui.primary : ui.surface, on ? ui.primary : ui.border, 18)));
            String mark = t == T_LOG ? "≡" : done && !on ? "✓" : String.valueOf(t + 1);
            TextView num = ui.text(mark, 11.5f, on ? ui.primary : done ? ui.onPrimary : ui.muted, true);
            num.setGravity(Gravity.CENTER);
            num.setBackground(ui.circle(on ? ui.onPrimary : done ? ui.primary : ui.bg, on || done ? 0 : ui.border));
            chip.addView(num, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
            TextView name = ui.text(TAB_NAMES[t], 13.5f, on ? ui.onPrimary : ui.text, on);
            name.setPadding(ui.dp(6), 0, 0, 0);
            chip.addView(name);
            if (t == T_LOG) {
                tabBadge = ui.text(String.valueOf(jm.logLines().size()), 11, on ? ui.onPrimary : ui.muted, false);
                tabBadge.setPadding(ui.dp(6), 0, 0, 0);
                chip.addView(tabBadge);
            }
            chip.setOnClickListener(v -> go(t));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = ui.dp(6);
            tabStrip.addView(chip, lp);
            if (on) selected = chip;
        }
        final View sel = selected;
        if (sel != null) tabScroll.post(() -> tabScroll.smoothScrollTo(Math.max(0, sel.getLeft() - ui.dp(40)), 0));
    }

    private void buildNav() {
        navBar.removeAllViews();
        boolean idle = jm.busy == null;
        if (tab == T_LOG) {
            TextView back = ui.button("← 返回「" + TAB_NAMES[lastStepTab] + "」", 0, v -> go(lastStepTab));
            navBar.addView(back, ui.weight(1));
            return;
        }
        TextView prev = ui.button("← 上一步", 0, v -> go(tab - 1));
        prev.setEnabled(tab > T_DEVICE);
        prev.setVisibility(tab > T_DEVICE ? View.VISIBLE : View.INVISIBLE);
        navBar.addView(prev, ui.weight(1));
        TextView pos = ui.text("第 " + (tab + 1) + " / " + STEPS + " 步", 12, ui.muted, false);
        pos.setGravity(Gravity.CENTER);
        navBar.addView(pos, ui.weight(0.8f));
        String label;
        boolean enabled;
        int kind = 1;
        switch (tab) {
            case T_DEVICE: label = "下一步：安装包 →"; enabled = jm.targetReady(); break;
            case T_PACKAGE: label = "下一步：安装方式 →"; enabled = jm.pkg != null; break;
            case T_METHOD: label = "下一步：检查 →"; enabled = true; break;
            case T_PLAN: label = jm.checkPassed() ? "在本页确认开始" : "先完成只读检查"; enabled = false; kind = 0; break;
            case T_INSTALL: label = "下一步：完成 →"; enabled = jm.installOk; break;
            default: label = "查看日志"; enabled = true; kind = 0; break;
        }
        final int target = tab == T_DONE ? T_LOG : tab + 1;
        TextView next = ui.button(label, kind, v -> go(target));
        next.setEnabled(enabled && (idle || tab == T_INSTALL || tab == T_DONE));
        navBar.addView(next, ui.weight(1.4f));
    }

    private void add(View v, int topDp) { content.addView(v, ui.mw(topDp)); }

    private void pageTitle(String overline, String title, String sub) {
        add(ui.overline(overline), 4);
        add(ui.h1(title), 0);
        if (sub != null) add(ui.sub(sub), 0);
    }

    private void rebuild() {
        content.removeAllViews();
        statusLine = stageLine = timerLine = sizeInfo = startBtn = logCount = null;
        bar = null;
        buildTabs();
        buildNav();
        boolean idle = jm.busy == null;
        if (!idle && tab != T_INSTALL && tab != T_LOG) add(busyBanner(), 0);
        switch (tab) {
            case T_DEVICE: devicePage(idle); break;
            case T_PACKAGE: packagePage(idle); break;
            case T_METHOD: methodPage(idle); break;
            case T_PLAN: planPage(idle); break;
            case T_INSTALL: installPage(idle); break;
            case T_DONE: donePage(); break;
            default: logPage(); break;
        }
        if (tab == T_DEVICE || tab == T_DONE) {
            add(ui.text("安装包内的 OpenWrt、内核与固件转换工具来自原作者（e5-linux：Encka · Minikano；mu300-linux：dikeckaan · Minikano）；"
                    + "本 APK 只负责校验、提取、传输并调用安装包自带的设备端脚本。", 11, ui.muted, false), 18);
        }
        updateDynamic();
    }

    /** Small status card shown on other tabs while a task runs. */
    private View busyBanner() {
        LinearLayout card = ui.card();
        LinearLayout r = ui.row();
        statusLine = ui.dot("", ui.primary);
        r.addView(statusLine, ui.weight(1));
        timerLine = ui.text("", 12, ui.muted, false);
        r.addView(timerLine);
        card.addView(r, ui.mw(10));
        stageLine = ui.text("", 12.5f, ui.muted, false);
        stageLine.setMaxLines(2);
        card.addView(stageLine, ui.mw(2));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(ColorStateList.valueOf(ui.primary));
        card.addView(bar, ui.mw(4));
        View pad = new View(this);
        card.addView(pad, new LinearLayout.LayoutParams(1, ui.dp(8)));
        return card;
    }

    private LinearLayout segmented(String a, String b, boolean firstOn, boolean enabled, View.OnClickListener la, View.OnClickListener lb) {
        LinearLayout r = ui.row();
        TextView x = ui.button(a, firstOn ? 1 : 0, la);
        TextView y = ui.button(b, firstOn ? 0 : 1, lb);
        x.setEnabled(enabled); y.setEnabled(enabled);
        LinearLayout.LayoutParams p1 = ui.weight(1), p2 = ui.weight(1);
        p2.leftMargin = ui.dp(8);
        r.addView(x, p1);
        r.addView(y, p2);
        return r;
    }

    // ------------------------------------------------------------ 1 设备
    private void devicePage(boolean idle) {
        boolean local = jm.mode == JobManager.Mode.LOCAL;
        pageTitle("步骤 1 · 目标设备", "安装到哪台设备？", local
                ? "在本机（已 root）上直接安装；也可以切换到“其他设备”，用 OTG 线（有线 ADB）或无线 ADB 安装到另一台设备。"
                : "用 OTG 线（有线 ADB）或同一局域网的无线 ADB 连接目标设备，本机无需 root；目标设备需要已 root。");
        LinearLayout card = ui.card();
        card.addView(segmented("本机", "其他设备", local, idle,
                v -> jm.setMode(JobManager.Mode.LOCAL), v -> jm.setMode(JobManager.Mode.LAN)), ui.mw(12));
        JobManager.Device d = jm.target();
        if (local) {
            describeDevice(card, d);
            if (d.probed && d.root == null)
                card.addView(ui.notice("!", "本机没有 root", "在 Magisk / KernelSU / KSUNext 中给“OpenWrt 安装器”授予超级用户后点“重新检测”；"
                        + "或切换到“其他设备”，通过 USB 或局域网安装到另一台设备。", 1), ui.mw(8));
            TextView re = ui.button("重新检测 root 与设备", 0, v -> jm.probeLocal());
            re.setEnabled(idle);
            card.addView(re, ui.mw(10));
        } else {
            card.addView(ui.text("USB 有线（OTG）", 14, ui.text, true), ui.mw(4));
            card.addView(ui.text("用 OTG 线连接本机和目标设备，目标设备开启 USB 调试。", 12, ui.muted, false), ui.mw(4));
            TextView us = ui.button("检测 USB 设备", 0, v -> jm.scanUsb());
            us.setEnabled(idle);
            card.addView(us, ui.mw(8));
            for (UsbAdbTransport.Candidate c : jm.usbHits) {
                final String n = c.name;
                card.addView(ui.actionRow("⏚", c.label, "USB ADB · 点击连接", idle, v -> jm.connectUsb(n)), ui.mw(4));
            }
            card.addView(ui.text("局域网（无线 ADB）", 14, ui.text, true), ui.mw(16));
            LinearLayout er = ui.row();
            final EditText ep = new EditText(this);
            ep.setSingleLine(true);
            ep.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
            ep.setText(endpointText);
            ep.setHint("192.168.0.1:5555");
            ep.setTextColor(ui.text);
            ep.setHintTextColor(ui.muted);
            ep.setTextSize(14);
            ep.setBackground(ui.round(ui.surface, ui.border, 10));
            ep.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
            ep.setEnabled(idle);
            ep.addTextChangedListener(watcher(s -> endpointText = s));
            er.addView(ep, ui.weight(1));
            TextView goBtn = ui.button("连接", 1, v -> jm.connectLan(endpointText));
            goBtn.setEnabled(idle);
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gp.leftMargin = ui.dp(8);
            er.addView(goBtn, gp);
            card.addView(er, ui.mw(12));
            TextView scan = ui.button(jm.scanning ? "正在扫描局域网……" : "扫描局域网中的设备（5555 端口）", 0, v -> jm.scanLan());
            scan.setEnabled(idle && !jm.scanning);
            card.addView(scan, ui.mw(8));
            List<LanScanner.Hit> hits = jm.scanHits;
            for (LanScanner.Hit h : hits) {
                final String e = h.endpoint();
                card.addView(ui.actionRow("⌁", e, h.gateway ? "网关（多为热点设备）· 点击连接" : "点击连接", idle, v -> {
                    endpointText = e;
                    jm.connectLan(e);
                }), ui.mw(4));
            }
            if (d != null && d.endpoint != null) {
                card.addView(ui.text("已选择 " + d.endpoint, 13, ui.muted, false), ui.mw(10));
                describeDevice(card, d);
                if (d.probed && d.root == null)
                    card.addView(ui.notice("!", "目标设备没有可用的 root", "在目标设备的 root 管理器中允许 Shell 的超级用户请求后重新连接。", 1), ui.mw(8));
                TextView dis = ui.button("断开所选设备", 3, v -> jm.disconnectLan());
                dis.setEnabled(idle);
                card.addView(dis, ui.mw(4));
            }
            card.addView(ui.text("本应用 ADB 密钥指纹：" + jm.adbKeyFingerprint(), 11.5f, ui.muted, false), ui.mw(8));
            View pad = new View(this);
            card.addView(pad, new LinearLayout.LayoutParams(1, ui.dp(6)));
        }
        add(card, 12);
        if (!local) {
            add(ui.notice("i", "USB 有线连接（OTG）",
                    "1. 用 OTG 转接头 / OTG 线把本机接到目标设备的 USB 口（本机作主机，会给目标设备供电）。\n"
                            + "2. 目标设备开启 USB 调试，点“检测 USB 设备”，再点设备连接；本机会询问是否允许访问该 USB 设备。\n"
                            + "3. 首次连接时在目标设备上允许 USB 调试，并在其 root 管理器中允许 Shell 的超级用户请求。\n"
                            + "安装任务在目标设备后台运行，中途拔线不影响；重新连接后可继续查看进度。", 0), 10);
            add(ui.notice("i", "无线 ADB 连接",
                    "1. 让本机连上目标设备的热点或同一路由器。\n2. 目标设备开启 ADB 网络调试（经典 5555 端口，例如已 root 时执行 "
                            + "setprop service.adb.tcp.port 5555 后重启 adbd，或用 USB 执行 adb tcpip 5555）。\n"
                            + "3. 首次连接时在目标设备上允许调试，并在其 root 管理器中允许 Shell 的超级用户请求。\n"
                            + "Android 11+ 的“无线调试”配对码方式暂不支持。", 0), 10);
        }
    }

    private void describeDevice(LinearLayout card, JobManager.Device d) {
        if (d == null || !d.probed) {
            card.addView(ui.text(jm.busy != null ? "正在检测……" : "尚未检测", 13.5f, ui.muted, false), ui.mw(10));
            return;
        }
        LinearLayout kv = ui.col();
        ui.addKvs(kv, new String[][]{
                {"型号", d.model + "（" + d.device + "）"},
                {"root", d.root == null ? "不可用" : d.root.isEmpty() ? "adbd 已是 root" : "可用（" + d.root + "）"},
                {"槽位 / 启动状态", d.slot + " · " + d.vbstate},
                {"内核架构", d.kernelArch},
                {"序列号", JobManager.maskSerial(d.serial)},
        });
        card.addView(kv, ui.mw(6));
        PackageInspector.Result p = jm.pkg;
        String why = JobManager.problemFor(p, d);
        if (why != null)
            card.addView(ui.notice("!", "与已选安装包不匹配", why + "。安装包只适用于 " + p.title + "；不要跨机型刷入。", 1), ui.mw(8));
    }

    // ------------------------------------------------------------ 2 安装包
    private void packagePage(boolean idle) {
        pageTitle("步骤 2 · 安装包", "选择 OpenWrt 完整安装包", "直接选择下载的 ZIP，不用解压。APK 会逐个校验 ZIP 内全部文件的 SHA-256，只提取设备端安装所需文件。");
        LinearLayout card = ui.card();
        PackageInspector.Result p = jm.pkg;
        TextView pick = ui.button(p == null ? "选择安装包（.zip）" : "重新选择安装包", p == null ? 1 : 0, v -> pickZip());
        pick.setEnabled(idle);
        card.addView(pick, ui.mw(12));
        if (!idle && jm.progress >= 0 && jm.busy != null && jm.busy.startsWith("读取")) {
            TextView c = ui.button("取消读取", 3, v -> jm.requestCancel());
            card.addView(c, ui.mw(4));
        }
        if (p != null) {
            LinearLayout kv = ui.col();
            ui.addKvs(kv, new String[][]{
                    {"文件", jm.pkgName},
                    {"版本", p.version},
                    {"OpenWrt / 内核", p.openwrtVersion + " · " + p.kernelRelease},
                    {"适用机型", p.title + "（型号 " + String.join(" / ", p.androidModels)
                            + (p.board != null ? "，主板 " + p.board : "") + "）"},
                    {"解锁要求", p.unlockRequired ? "需要已解锁（verifiedbootstate=orange）" : "不要求 orange（按安装包规则）"},
                    {"完整性", p.verifiedFiles + "/" + p.listedFiles + " 个文件 SHA-256 一致"
                            + (p.isMu300() ? "（desktop/manifest.json）" : "（files/SHA256SUMS）")},
                    {"安装方式", p.isMu300() ? (p.tfOnly ? "仅 TF 卡（" + (p.internalProblem != null ? p.internalProblem : "内置存储方式不可用") + "）"
                            : "TF 卡 / 独立内置分区 / 重装已有内置分区 / userdata 镜像")
                            : p.tfOnly ? "仅 TF 卡" : "TF 卡 / 缩容 userdata / userdata 镜像"},
                    {"暂存到目标设备", jm.packageReady() ? "已就绪" : "检查时自动暂存"},
            });
            card.addView(kv, ui.mw(6));
            JobManager.Device d = jm.target();
            String why = JobManager.problemFor(p, d);
            if (why != null)
                card.addView(ui.notice("!", "与目标设备不匹配", why + "。安装包脚本会拒绝安装；不要跨机型刷入。", 1), ui.mw(8));
        } else if (jm.stagedFingerprint != null) {
            card.addView(ui.text("目标设备上有之前暂存的安装包；请重新选择 ZIP 以便校验与核对。", 13, ui.muted, false), ui.mw(10));
        }
        View pad = new View(this);
        card.addView(pad, new LinearLayout.LayoutParams(1, ui.dp(10)));
        add(card, 12);
        add(ui.notice("i", "关于完整性", "校验清单只能证明文件未损坏且与清单一致，不能证明来源；请只使用可信来源的安装包。", 0), 10);
    }

    // ------------------------------------------------------------ 3 安装方式
    private void methodPage(boolean idle) {
        pageTitle("步骤 3 · 安装方式", "选择写入位置", "切换方式后需要重新做只读检查。");
        if (jm.pkg != null && jm.pkg.tfOnly)
            add(ui.notice("i", "仅支持 TF 卡", jm.pkg.title + " 安装包在本应用中只能用 TF 卡安装"
                    + (jm.pkg.internalProblem != null ? "：" + jm.pkg.internalProblem : "") + "。", 0), 10);
        for (JobManager.Method m : JobManager.Method.values()) {
            if (!jm.methodAllowed(m)) continue;
            boolean on = jm.method == m;
            LinearLayout r = ui.row();
            r.setGravity(Gravity.TOP);
            r.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
            r.setBackground(ui.ripple(ui.round(on ? ui.primarySoft : ui.surface, on ? ui.primary : ui.border, 12)));
            TextView radio = ui.text(on ? "◉" : "○", 18, on ? ui.primary : ui.muted, true);
            r.addView(radio);
            LinearLayout tx = ui.col();
            tx.setPadding(ui.dp(10), 0, 0, 0);
            tx.addView(ui.text(jm.titleOf(m), 15, ui.text, true));
            TextView ds = ui.text(jm.summaryOf(m), 12.5f, ui.muted, false);
            ds.setPadding(0, ui.dp(3), 0, 0);
            tx.addView(ds);
            r.addView(tx, ui.weight(1));
            if (idle) r.setOnClickListener(v -> { confirmed = false; sizeText = null; methodSeen = true; jm.setMethod(m); });
            else r.setAlpha(on ? 1f : 0.6f);
            add(r, 10);
        }
        if (jm.pkg != null && jm.pkg.isMu300())
            add(ui.text("独立内置分区：未分配空间足够时直接使用，不缩容、不动 Android 数据；不够时需要未加密的 userdata，首次开机离线缩容后自动重启一次。"
                    + "需要“清空 Android 数据”的缩容、未完成安装的续装和在线更新依赖电脑端保存的事务，本应用不提供。", 12, ui.muted, false), 14);
        else
            add(ui.text("加密 userdata（FBE / 元数据加密）的“两阶段、清空 Android 数据”内置分区安装、重装已有分区、续装和在线更新"
                    + "依赖电脑端在恢复出厂期间保存的事务，本应用不提供；请使用 TF 卡方式或电脑端安装器。", 12, ui.muted, false), 14);
    }

    // ------------------------------------------------------------ 4 检查与计划
    private void planPage(boolean idle) {
        pageTitle("步骤 4 · 检查与计划", "先只读检查，再确认写入", null);
        JobManager.Device d = jm.target();
        PackageInspector.Result p = jm.pkg;
        if (p == null || !jm.targetReady()) {
            add(ui.notice("i", "还不能检查", !jm.targetReady() ? "请先在「设备」页选择并检测目标设备。" : "请先在「安装包」页选择安装包。", 0), 12);
            TextView back = ui.button(!jm.targetReady() ? "前往「设备」" : "前往「安装包」", 0, v -> go(!jm.targetReady() ? T_DEVICE : T_PACKAGE));
            add(back, 10);
            return;
        }
        String why = JobManager.problemFor(p, d);
        boolean match = d != null && why == null;
        LinearLayout card = ui.card();
        card.addView(ui.text("目标 " + d.model + (d.endpoint != null ? "（" + d.endpoint + "）" : "（本机）") + " · " + jm.titleOf(jm.method), 13, ui.muted, false), ui.mw(12));
        TextView check = ui.button(jm.checkPassed() ? "重新只读检查" : "只读检查（不写入任何分区）", jm.checkPassed() ? 0 : 1, v -> { confirmed = false; jm.runCheck(); });
        check.setEnabled(idle && (jm.packageReady() || jm.localCopyAvailable() || (jm.muInternal(jm.method) && jm.muLocalReady())));
        card.addView(check, ui.mw(8));
        if (!jm.checkPassed()) {
            card.addView(ui.text(jm.muInternal(jm.method)
                    ? "检查只读取设备信息：型号、槽位、eMMC 与 GPT（主备两份）、userdata 文件系统、加密状态与可用空间"
                            + (jm.method == JobManager.Method.REPART ? "、已有 mu300root 分区。" : "；同时算出可选大小。")
                    : "检查会暂存安装文件并运行预检：包完整性、型号、槽位、解锁、启动控制块、启动链"
                    + (jm.method == JobManager.Method.TF ? "、TF 卡识别与空间。" : "、userdata 加密状态与可用空间。"), 13, ui.muted, false), ui.mw(8));
            if (!match) card.addView(ui.text(why + "，检查会失败。", 13, ui.danger, true), ui.mw(6));
            View pad = new View(this);
            card.addView(pad, new LinearLayout.LayoutParams(1, ui.dp(10)));
            add(card, 12);
            return;
        }
        JobManager.Method m = jm.method;
        String slots = jm.checkSlots == null ? "a,b" : jm.checkSlots;
        String linux = slots.contains(",") ? slots.substring(slots.indexOf(',') + 1) : "b";
        LinearLayout kv = ui.col();
        boolean mi = jm.muInternal(m);
        if (mi) {
            Mu300Internal.Plan mp = jm.muPlan;
            String target = m == JobManager.Method.REPART ? "/dev/block/" + (mp != null && mp.s.root != null ? mp.s.root : "?") + "（mu300root，" + jm.chosenMib + " MiB）"
                    : m == JobManager.Method.IMAGE ? "/data/mu300linux 中的 OpenWrt 镜像"
                    : "新建 mu300root 分区（未分配空间或缩小 userdata）";
            ui.addKvs(kv, new String[][]{
                    {"安装方式", jm.titleOf(m)},
                    {"设备", mp != null ? mp.model + "（" + mp.profile + "）" : d.model},
                    {"写入目标", target},
                    {"userdata", jm.dataNode + " · " + jm.udataMib + " MiB，可用 " + jm.freeMib + " MiB"},
                    {"加密状态", jm.crypto},
                    {"检查结果", mp != null ? mp.summary : "-"},
                    {"Linux 槽", "boot_" + linux + "（写入前备份，写入后回读校验）"},
            });
        } else if (m == JobManager.Method.TF) {
            ui.addKvs(kv, new String[][]{
                    {"安装方式", m.title},
                    {"写入目标", jm.checkTarget + "（" + JobManager.gib(jm.checkTargetBytes) + "）"},
                    {"TF 卡", jm.checkCard},
                    {"Linux 槽", "boot_" + linux + (p.isMu300() ? "（启动失败会自动返回 Android）" : "（试启动，失败两次回 Android）")},
                    p.isMu300() ? new String[]{"根文件系统需要", JobManager.mib(p.requiredKib << 10) + "（rootfs.requirements）"}
                            : new String[]{"临时空间", JobManager.mib(p.rawBytes + p.bootBytes + (256L << 20))},
            });
        } else if (m == JobManager.Method.PART) {
            ui.addKvs(kv, new String[][]{
                    {"安装方式", m.title},
                    {"userdata", jm.dataNode + " · " + jm.udataMib + " MiB，已用 " + jm.usedMib + " MiB"},
                    {"加密状态", jm.crypto},
                    {"新分区", "e5root（userdata 之后，由启动时 initramfs 创建）"},
                    {"Linux 槽", "boot_" + linux + "（试启动，失败两次回 Android）"},
            });
        } else {
            ui.addKvs(kv, new String[][]{
                    {"安装方式", m.title},
                    {"镜像文件", "/data/e5linux/openwrt.ext4"},
                    {"加密状态", jm.crypto},
                    {"/data 可用", jm.freeMib + " MiB"},
                    {"Linux 槽", "boot_" + linux + "（试启动，失败两次回 Android）"},
            });
        }
        card.addView(kv, ui.mw(6));
        if (m != JobManager.Method.TF && m != JobManager.Method.REPART) addSizePicker(card, m, idle);
        View pad = new View(this);
        card.addView(pad, new LinearLayout.LayoutParams(1, ui.dp(10)));
        add(card, 12);

        String impact;
        if (mi && m == JobManager.Method.PART)
            impact = "先把 GPT、misc 和两槽启动链备份到本应用目录。所选大小放得进 eMMC 未分配空间时，直接在那里新建 mu300root 分区写入 OpenWrt，"
                    + "不缩容、不动 Android 数据；放不下时在下次开机由 initramfs 离线检查并缩小 userdata（保留数据），再新建分区写入 OpenWrt，"
                    + "完成后自动重启一次。缩容期间断电可能损坏 userdata——请先备份重要文件并保持供电。";
        else if (mi && m == JobManager.Method.REPART)
            impact = "清空已有 mu300root 分区上的全部内容（包括 OpenWrt 的设置）并重新写入；不改分区表，不动 Android 数据。";
        else if (mi)
            impact = "在 userdata 中创建 OpenWrt 镜像文件（占用所选大小的空间），不格式化 userdata。此方式为实验性，原安装器提示可能损坏 Android 的 F2FS 文件系统；"
                    + "请先备份重要文件。";
        else if (m == JobManager.Method.TF && p.isMu300())
            impact = "清空 TF 目标 " + jm.checkTarget + " 并写入 OpenWrt；同时写入另一槽的 boot、启动链（先备份再校验）和 misc 的 32 字节，"
                    + "不动内部存储与 Android 数据。";
        else if (m == JobManager.Method.TF)
            impact = "清空 TF 目标 " + jm.checkTarget + " 上的全部数据并写入 OpenWrt；不改 TF 分区表、不动内部存储与 Android 数据。";
        else if (m == JobManager.Method.PART)
            impact = "保留 Android 数据，但会改动内部存储分区表：下次启动时离线检查并缩小 userdata（F2FS）到 "
                    + "（userdata − 所选分区大小），在其后新建 e5root 分区并写入 OpenWrt。缩容前先做文件系统检查；"
                    + "任何一步失败都会在改分区表之前停下，Android 仍可挂载。缩容期间断电可能损坏 userdata——请先备份重要文件并保持供电。";
        else
            impact = "在 userdata 中新建 OpenWrt 镜像文件（占用所选大小的空间），不格式化 userdata；已有镜像会被移到 /data/e5linux/previous.* 保留。"
                    + "此方式为实验性。";
        add(ui.notice("!", "数据影响", impact, 1), 10);
        if (mi)
            add(ui.notice("i", "共同步骤", "从本机提取厂商固件放入 OpenWrt；在本应用内为 boot_" + linux + " 生成启动镜像，写入前备份、写入后回读校验，"
                    + "并设置下次从 Linux 槽启动（失败会回到 Android）；当前 Android 槽、NV 均不写入。"
                    + (m == JobManager.Method.PART ? "需要缩容时会自动重启，否则需手动重启。" : "完成后需手动重启。"), 0), 8);
        else
            add(ui.notice("i", "共同步骤", "提取本机 Wi-Fi / 蓝牙 / 音频固件写入 OpenWrt；启动链有差异时先备份两槽再同步；写入 boot_" + linux
                    + " 并回读校验；当前 Android 槽、NV 均不写入。完成后需手动重启。", 0), 8);

        CheckBox cb = new CheckBox(this);
        cb.setText("我已核对设备、写入目标和数据影响，并已备份需要保留的文件。");
        cb.setTextColor(ui.text);
        cb.setTextSize(13.5f);
        cb.setButtonTintList(ColorStateList.valueOf(ui.primary));
        cb.setChecked(confirmed);
        cb.setEnabled(idle);
        cb.setOnCheckedChangeListener((b, v) -> { confirmed = v; refreshStart(); });
        add(cb, 10);
        startBtn = ui.button("确认并开始安装 →", 2, v -> confirmInstall());
        add(startBtn, 8);
        refreshStart();
        if (!match) add(ui.text(why + "，禁止安装。", 13, ui.danger, true), 6);
    }

    private void addSizePicker(LinearLayout card, JobManager.Method m, boolean idle) {
        String label = m == JobManager.Method.PART ? "OpenWrt 分区大小（MiB）" : "镜像大小（MiB）";
        card.addView(ui.text(label, 14, ui.text, true), ui.mw(12));
        card.addView(ui.text("允许范围 " + jm.rangeMin + " – " + jm.rangeMax + " MiB（约 " + gibStr(jm.rangeMin) + " – " + gibStr(jm.rangeMax) + "），默认 "
                + jm.rangeDefault + " MiB", 12.5f, ui.muted, false), ui.mw(2));
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (sizeText == null) sizeText = String.valueOf(jm.chosenMib);
        in.setText(sizeText);
        in.setTextColor(ui.text);
        in.setTextSize(15);
        in.setBackground(ui.round(ui.surface, ui.border, 10));
        in.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        in.setEnabled(idle);
        in.addTextChangedListener(watcher(s -> { sizeText = s; applySize(); }));
        card.addView(in, ui.mw(6));
        LinearLayout quick = ui.row();
        String[][] q = {{"最小", String.valueOf(jm.rangeMin)}, {"默认", String.valueOf(jm.rangeDefault)}, {"最大", String.valueOf(jm.rangeMax)}};
        for (int i = 0; i < q.length; i++) {
            final String val = q[i][1];
            TextView b = ui.button(q[i][0], 0, v -> in.setText(val));
            b.setEnabled(idle);
            LinearLayout.LayoutParams lp = ui.weight(1);
            if (i > 0) lp.leftMargin = ui.dp(6);
            quick.addView(b, lp);
        }
        card.addView(quick, ui.mw(6));
        sizeInfo = ui.text("", 12.5f, ui.muted, false);
        card.addView(sizeInfo, ui.mw(4));
        if (m == JobManager.Method.PART && !jm.muInternal(m)) {
            CheckBox grow = new CheckBox(this);
            grow.setText("将 OpenWrt 文件系统扩展到整个分区（推荐）");
            grow.setTextColor(ui.text);
            grow.setTextSize(13.5f);
            grow.setButtonTintList(ColorStateList.valueOf(ui.primary));
            grow.setChecked(jm.growFs);
            grow.setEnabled(idle);
            grow.setOnCheckedChangeListener((b, v) -> jm.growFs = v);
            card.addView(grow, ui.mw(4));
        }
        applySize();
    }

    private static String gibStr(long mib) { return String.format(Locale.US, "%.1f GiB", mib / 1024.0); }

    private void applySize() {
        long v;
        try { v = Long.parseLong(sizeText == null ? "" : sizeText.trim()); } catch (NumberFormatException e) { v = -1; }
        sizeValid = jm.setChosenMib(v);
        if (sizeInfo != null) {
            if (sizeValid) {
                String extra;
                if (jm.method == JobManager.Method.PART && jm.muInternal(jm.method)) {
                    long t = jm.muShrinkTargetMib(v);
                    extra = jm.muUsesFree() ? "；使用 eMMC 未分配空间，不缩小 userdata"
                            : "；未分配空间不够，下次开机离线把 userdata 缩小为 " + (t > 0 ? t + " MiB（约 " + gibStr(t) + "）" : "?");
                } else extra = jm.method == JobManager.Method.PART
                        ? "；userdata 缩小为 " + (jm.udataMib - v) + " MiB（约 " + gibStr(jm.udataMib - v) + "）" : "";
                sizeInfo.setText("将使用 " + v + " MiB（约 " + gibStr(v) + "）" + extra);
                sizeInfo.setTextColor(ui.muted);
            } else {
                sizeInfo.setText("请输入 " + jm.rangeMin + " – " + jm.rangeMax + " 之间的整数");
                sizeInfo.setTextColor(ui.danger);
            }
        }
        refreshStart();
    }

    private void refreshStart() {
        if (startBtn == null) return;
        JobManager.Device d = jm.target();
        boolean match = jm.modelMatches();
        startBtn.setEnabled(jm.busy == null && jm.checkPassed() && match && confirmed
                && (jm.method == JobManager.Method.TF || jm.method == JobManager.Method.REPART || sizeValid));
    }

    // ------------------------------------------------------------ 5 安装
    private void installPage(boolean idle) {
        pageTitle("步骤 5 · 安装与校验", jm.mode == JobManager.Mode.LOCAL ? "本机"
                : (jm.remote != null && jm.remote.endpoint != null ? jm.remote.endpoint : "其他设备"),
                "安装在目标设备后台以 root 运行；关闭本应用或断开网络不会中断，回到本页点“继续未完成的安装”即可接上进度。");
        LinearLayout card = ui.card();
        LinearLayout r = ui.row();
        statusLine = ui.dot("空闲", ui.muted);
        r.addView(statusLine, ui.weight(1));
        timerLine = ui.text("", 12.5f, ui.muted, false);
        r.addView(timerLine);
        card.addView(r, ui.mw(12));
        stageLine = ui.text("", 13.5f, ui.text, false);
        card.addView(stageLine, ui.mw(4));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(ColorStateList.valueOf(ui.primary));
        card.addView(bar, ui.mw(6));
        if ("running".equals(jm.lastJob) && idle)
            card.addView(ui.notice("i", "有后台安装任务", "安装在目标设备上后台运行，与本界面或网络连接无关。点“继续未完成的安装”查看进度。", 0), ui.mw(8));
        else if (jm.lastJob.startsWith("done") && !"done 0".equals(jm.lastJob) && idle && jm.muInternal(jm.installedMethod))
            card.addView(ui.notice("!", "上次安装失败", "启动镜像写入前失败时不会切换启动槽，Android 照常启动。详细原因见下方日志"
                    + (jm.muBackup != null ? "；分区备份在 " + jm.muBackup : "") + "。检查设备状态后重新做只读检查再试。", 1), ui.mw(8));
        else if (jm.lastJob.startsWith("done") && !"done 0".equals(jm.lastJob) && idle)
            card.addView(ui.notice("!", "上次安装失败", "安装器失败时不会设置启动目标。日志和备份保留在 " + jm.logDir() + "；"
                    + "同一次开机内安装器锁会阻止重试，检查后重启 Android 再试。", 1), ui.mw(8));
        else if (jm.installOk)
            card.addView(ui.notice("✓", "安装已完成", "到「完成」页查看热点与管理地址，并重启目标设备。", 0), ui.mw(8));
        LinearLayout br = ui.row();
        TextView att = ui.button("继续未完成的安装 / 查看日志", 0, v -> jm.attach());
        att.setEnabled(idle && jm.targetReady());
        br.addView(att, ui.weight(1.4f));
        TextView cl = ui.button("清理暂存文件", 0, v -> confirmClean());
        cl.setEnabled(idle);
        LinearLayout.LayoutParams lp = ui.weight(1);
        lp.leftMargin = ui.dp(8);
        br.addView(cl, lp);
        card.addView(br, ui.mw(10));
        View pad = new View(this);
        card.addView(pad, new LinearLayout.LayoutParams(1, ui.dp(10)));
        add(card, 12);
        add(logCard(220), 12);
    }

    // ------------------------------------------------------------ 6 完成
    private void donePage() {
        if (!jm.installOk) {
            pageTitle("步骤 6 · 完成", "尚未完成安装", "安装成功后，这里会显示热点名称、管理地址和下一步操作。");
            add(ui.button("前往「" + (jm.checkPassed() ? "检查与计划" : TAB_NAMES[initialTab()]) + "」", 0,
                    v -> go(jm.checkPassed() ? T_PLAN : initialTab())), 12);
            return;
        }
        boolean mi = jm.muInternal(jm.installedMethod);
        boolean part = mi ? jm.muOutcome != null && jm.muOutcome.pending : jm.installedMethod == JobManager.Method.PART;
        pageTitle("步骤 6 · 完成", part ? (mi ? "设备正在重启并离线完成安装" : "已就绪：重启后离线完成安装") : "安装完成", null);
        LinearLayout card = ui.card();
        LinearLayout r = ui.row();
        TextView ok = ui.text("✓", 18, ui.onPrimary, true);
        ok.setGravity(Gravity.CENTER);
        ok.setBackground(ui.circle(ui.primary, 0));
        r.addView(ok, new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        TextView t = ui.text(mi && jm.muOutcome != null ? jm.muOutcome.message : part ? "载荷、内核与启动槽已写入并校验" : "OpenWrt 已写入并设为下次启动", 15, ui.text, true);
        t.setPadding(ui.dp(12), 0, 0, 0);
        r.addView(t, ui.weight(1));
        card.addView(r, ui.mw(14));
        LinearLayout kv = ui.col();
        ui.addKvs(kv, new String[][]{
                {"热点", jm.hotspot == null ? "（见日志）" : jm.hotspot},
                {"管理地址", jm.adminUrl()},
                {"账户", "root / root（登录后请修改）"},
        });
        card.addView(kv, ui.mw(6));
        boolean mu = jm.pkg != null && jm.pkg.isMu300();
        String next = mi && jm.muOutcome != null
                ? "下一步：" + jm.muOutcome.next + (part ? "。离线安装进度可在 http://192.168.77.1:8089/progress.txt 查看。" : "。如果 Linux 启动失败，设备会自动返回 Android。")
                        + (jm.muBackup != null ? "\n分区备份：" + jm.muBackup + "（建议复制到电脑保存）" : "")
                : mu
                ? "下一步：重启进入 OpenWrt。如果 Linux 启动失败，设备会自动返回 Android。"
                : part
                ? "下一步：重启。initramfs 会离线缩小 userdata、创建 e5root 并写入 OpenWrt，屏幕显示进度，完成后自动再重启一次进入 OpenWrt；期间不要断电。"
                : "下一步：重启进入 OpenWrt（首次启动约 2 分钟）。失败两次会自动回到 Android。回 Android：OpenWrt 屏幕“高级 → 系统 → 下次启动 Android”或执行 e5-next-boot android。";
        card.addView(ui.text(next, 13, ui.muted, false), ui.mw(8));
        if (!(mi && part)) {
            TextView rb = ui.button("现在重启目标设备", 1, v -> confirmReboot());
            rb.setEnabled(jm.busy == null);
            card.addView(rb, ui.mw(12));
        }
        View pad = new View(this);
        card.addView(pad, new LinearLayout.LayoutParams(1, ui.dp(10)));
        add(card, 12);
    }

    // ------------------------------------------------------------ 日志
    private void logPage() {
        pageTitle("日志", "详细日志 · DEBUG", "安装器与 APK 的全部输出；可复制或导出后排查问题。");
        add(logCard(0), 12);
    }

    private View logCard(int heightDp) {
        LinearLayout card = ui.card();
        LinearLayout h = ui.row();
        h.addView(ui.text("详细日志 · DEBUG", 14, ui.text, true), ui.weight(1));
        logCount = ui.text(jm.logLines().size() + " 条", 12, ui.muted, false);
        h.addView(logCount);
        card.addView(h, ui.mw(10));
        LinearLayout br = ui.row();
        TextView copy = ui.button("复制", 0, v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("OpenWrt 安装日志", jm.logText()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        TextView share = ui.button("导出日志", 0, v -> startActivity(JobManager.shareIntent(jm.logText())));
        br.addView(copy, ui.weight(1));
        LinearLayout.LayoutParams lp = ui.weight(1);
        lp.leftMargin = ui.dp(8);
        br.addView(share, lp);
        card.addView(br, ui.mw(8));
        if (logView.getParent() != null) ((ViewGroup) logView.getParent()).removeView(logView);
        final ScrollView box = new ScrollView(this);
        box.setBackground(ui.round(ui.logBg, 0, 8));
        box.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
        box.addView(logView);
        box.setOnTouchListener((v, e) -> { v.getParent().requestDisallowInterceptTouchEvent(true); return false; });
        int hpx = heightDp > 0 ? ui.dp(heightDp)
                : Math.max(ui.dp(260), getResources().getDisplayMetrics().heightPixels - ui.dp(380));
        card.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hpx));
        box.post(() -> box.fullScroll(View.FOCUS_DOWN));
        logView.addTextChangedListener(watcher(s -> box.post(() -> box.fullScroll(View.FOCUS_DOWN))));
        View pad = new View(this);
        card.addView(pad, new LinearLayout.LayoutParams(1, ui.dp(8)));
        return card;
    }

    // ------------------------------------------------------------ actions
    private interface Str { void set(String s); }

    private static TextWatcher watcher(Str s) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable e) { s.set(e.toString()); }
        };
    }

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, PICK_ZIP);
        } catch (RuntimeException e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_ZIP && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            confirmed = false;
            sizeText = null;
            jm.loadPackage(uri);
        }
    }

    private void confirmInstall() {
        PackageInspector.Result p = jm.pkg;
        JobManager.Device d = jm.target();
        if (p == null || d == null || !jm.checkPassed()) {
            Toast.makeText(this, "请先完成只读检查", Toast.LENGTH_LONG).show();
            return;
        }
        JobManager.Method m = jm.method;
        String what;
        boolean mi = jm.muInternal(m);
        if (mi && m == JobManager.Method.PART) what = jm.muUsesFree()
                ? "备份分区表与启动链，在 eMMC 未分配空间新建 " + jm.chosenMib + " MiB 的 mu300root 分区写入 OpenWrt（不缩小 userdata）"
                : "备份分区表与启动链，随后自动重启，开机时离线把 userdata 缩小到 " + jm.muShrinkTargetMib(jm.chosenMib)
                        + " MiB，新建 " + jm.chosenMib + " MiB 的 mu300root 分区写入 OpenWrt";
        else if (mi && m == JobManager.Method.REPART) what = "清空已有 mu300root 分区（" + jm.chosenMib + " MiB）并重新写入 OpenWrt";
        else if (mi) what = "在 /data/mu300linux 创建 " + jm.chosenMib + " MiB 的 OpenWrt 镜像（实验性）";
        else if (m == JobManager.Method.TF) what = "清空 TF 目标 " + jm.checkTarget + "（" + JobManager.gib(jm.checkTargetBytes) + "）并写入 OpenWrt";
        else if (m == JobManager.Method.PART) what = "下次启动时把 userdata 从 " + jm.udataMib + " MiB 缩小到 " + (jm.udataMib - jm.chosenMib)
                + " MiB，并新建 " + jm.chosenMib + " MiB 的 e5root 分区写入 OpenWrt";
        else what = "在 /data/e5linux 创建 " + jm.chosenMib + " MiB 的 OpenWrt 镜像";
        String msg = "目标设备：" + d.model + (d.endpoint != null ? "（" + d.endpoint + "）" : "（本机）")
                + "\n安装包：" + p.version + "\n安装方式：" + jm.titleOf(m) + "\n\n将要：" + what
                + "，写入 boot 并设置下次从 OpenWrt 槽试启动。\n\n期间保持供电，不要关机" + (m == JobManager.Method.TF ? "或拔卡" : "")
                + (jm.mode == JobManager.Mode.LAN ? (mi ? "；安装期间保持 USB 或网络连接" : "；网络或 USB 断开不影响目标设备上的后台任务") : "") + "。\n\n输入 yes 确认：";
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint("yes");
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("确认安装 · " + jm.titleOf(m))
                .setMessage(msg)
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始安装", (x, w) -> {
                    if ("yes".equals(input.getText().toString().trim())) jm.runInstall();
                })
                .create();
        dlg.setOnShowListener(x -> {
            final android.widget.Button ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setEnabled(false);
            ok.setTextColor(ui.danger);
            input.addTextChangedListener(watcher(s -> ok.setEnabled("yes".equals(s.trim()))));
        });
        dlg.show();
    }

    private void confirmReboot() {
        boolean part = jm.installedMethod == JobManager.Method.PART;
        new AlertDialog.Builder(this)
                .setTitle("重启目标设备")
                .setMessage(part ? "重启后将离线缩容 userdata 并写入 OpenWrt，完成后自动再重启一次；期间不要断电。现在重启吗？"
                        : "重启后进入 OpenWrt（首次启动约 2 分钟）。现在重启吗？")
                .setNegativeButton("稍后", null)
                .setPositiveButton("现在重启", (x, w) -> jm.reboot())
                .show();
    }

    private void confirmClean() {
        new AlertDialog.Builder(this)
                .setTitle("清理暂存文件")
                .setMessage("删除 APK 暂存的安装文件（目标设备 /data/adb/owrt-apk/pkg 和应用内临时目录）。\n"
                        + "不会删除安装器日志（/data/adb/e5-tf-install 或 /data/local/tmp/mu300-tf-install）、启动链备份，"
                        + "也不会撤销已落位的安装（/data/e5linux）或影响已安装的 OpenWrt。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清理", (x, w) -> jm.clean())
                .show();
    }
}
