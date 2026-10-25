package top.linote.owrtinstaller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 「清理本机占用」: frees space on THIS phone only (no target connection). Partition backups and
 * uninstall records can only be deleted after a verified ZIP export in this session; files of
 * unfinished records are never deleted; the ADB key is never deleted.
 */
public final class CleanupActivity extends Activity {
    private static final int REQ_EXPORT = 41;

    /** One selectable category. backup = needs a verified export before deletion. */
    private static final class Cat {
        final String title, note;
        final List<File> roots = new ArrayList<>();
        final boolean backup;
        boolean checked;
        long bytes;
        CheckBox box;
        Cat(String title, String note, boolean backup) { this.title = title; this.note = note; this.backup = backup; }
    }

    private Ui ui;
    private JobManager jm;
    private LinearLayout body;
    private final List<Cat> cats = new ArrayList<>();
    private Set<File> keep;
    private boolean exportVerified;
    private String exportName;
    private TextView status;
    private volatile boolean working;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        jm = JobManager.get(this);
        if (Build.VERSION.SDK_INT >= 21) getWindow().setStatusBarColor(ui.bg);
        LinearLayout root = ui.col();
        root.setBackgroundColor(ui.bg);
        LinearLayout head = ui.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(ui.dp(8), ui.dp(8), ui.dp(16), ui.dp(8));
        TextView back = ui.text("←", 20, ui.text, true);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回");
        back.setBackground(ui.ripple(ui.circle(ui.bg, 0)));
        back.setOnClickListener(v -> finish());
        head.addView(back, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        TextView t = ui.text("清理本机占用", 17, ui.text, true);
        t.setPadding(ui.dp(6), 0, 0, 0);
        head.addView(t, ui.weight(1));
        root.addView(head, ui.mw(0));
        root.addView(ui.divider());
        ScrollView scroll = new ScrollView(this);
        body = ui.col();
        body.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(28));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        scan();
    }

    private File files(String n) { return new File(getFilesDir(), n); }

    private void scan() {
        cats.clear();
        keep = jm.protectedDirs();
        Cat b = new Cat("分区备份与续装记录", "mu300-backup、e5-part：安装前备份的 GPT、misc、启动链和续装记录。"
                + "以后卸载、或把启动链恢复成 Android 原样时需要它们。只能在下方导出 ZIP 并校验通过后删除；未完成的续装记录不会删除。", true);
        b.roots.add(jm.backupBase("mu300-backup"));
        b.roots.add(jm.backupBase("e5-part"));
        Cat u = new Cat("卸载记录与备份", "uninstall：卸载前的分区备份和卸载记录。只能在导出 ZIP 并校验通过后删除；未完成的卸载记录不会删除。", true);
        u.roots.add(jm.uninstallRoot());
        Cat g = new Cat("内置安装文件与生成的启动镜像", "mu300-local：从安装包提取的内置安装文件和生成的启动镜像。删除后需要重新选择安装包。", false);
        g.roots.add(jm.muLocal());
        Cat s = new Cat("解出的安装文件", "stage：从安装包解出的待上传文件。删除后需要重新选择安装包。", false);
        s.roots.add(files("stage"));
        Cat l = new Cat("日志与临时文件", "last-crash.txt（上次崩溃记录）和缓存目录。", false);
        l.roots.add(jm.crashFile());
        l.roots.add(getCacheDir());
        cats.add(b); cats.add(u); cats.add(g); cats.add(s); cats.add(l);
        for (Cat c : cats) for (File r : c.roots) c.bytes += size(r, true);
        build();
    }

    /** Size of f; when skipKept, files inside protected (unfinished-record) dirs are not counted. */
    private long size(File f, boolean skipKept) {
        if (f == null || !f.exists()) return 0;
        if (skipKept && kept(f)) return 0;
        if (f.isFile()) return f.length();
        long n = 0;
        File[] k = f.listFiles();
        if (k != null) for (File c : k) n += size(c, skipKept);
        return n;
    }
    private boolean kept(File f) {
        String p = f.getAbsolutePath();
        for (File k : keep) {
            String kp = k.getAbsolutePath();
            if (p.equals(kp) || p.startsWith(kp + "/")) return true;
        }
        return false;
    }

    private void build() {
        body.removeAllViews();
        boolean busy = jm.busy != null;
        body.addView(ui.text("只清理这台手机上本应用占用的空间，不连接、不改动目标设备。", 14, ui.muted, false), ui.mw(10));
        if (busy)
            body.addView(ui.notice("!", "正在执行任务", jm.busy + "。任务结束后再清理。", 1), ui.mw(10));
        if (!keep.isEmpty()) {
            StringBuilder k = new StringBuilder();
            for (File f : keep) k.append(k.length() > 0 ? "\n" : "").append(f.getAbsolutePath());
            body.addView(ui.notice("i", "保留未完成的记录", "下面这些未完成的续装 / 卸载记录及其备份不会被删除（也不计入可清理大小）：\n" + k, 0), ui.mw(10));
        }
        long total = 0;
        for (Cat c : cats) {
            total += c.bytes;
            LinearLayout card = ui.card();
            c.box = new CheckBox(this);
            c.box.setText(c.title + " · " + human(c.bytes));
            c.box.setTextColor(ui.text);
            c.box.setTextSize(14.5f);
            c.box.setButtonTintList(ColorStateList.valueOf(c.backup ? ui.danger : ui.primary));
            c.box.setChecked(c.checked);
            c.box.setEnabled(!busy && c.bytes > 0);
            final Cat cc = c;
            c.box.setOnCheckedChangeListener((x, v) -> cc.checked = v);
            card.addView(c.box, ui.mw(4));
            card.addView(ui.text(c.note, 12.5f, ui.muted, false), ui.mw(8));
            body.addView(card, ui.mw(10));
        }
        LinearLayout key = ui.card();
        key.addView(ui.text("ADB 授权密钥 · 不可删除", 14.5f, ui.text, true), ui.mw(4));
        key.addView(ui.text("adbkey：目标设备上“允许 USB 调试”记住的就是这把密钥。删除后每台目标设备都要重新授权，所以这里不提供删除。", 12.5f, ui.muted, false), ui.mw(8));
        body.addView(key, ui.mw(10));
        body.addView(ui.text("可清理合计：" + human(total), 14, ui.text, true), ui.mw(10));

        TextView exp = ui.button("导出分区备份与卸载记录为 ZIP（推荐先做）", 1, v -> exportZip());
        exp.setEnabled(!busy && !working && !exportSet().isEmpty());
        body.addView(exp, ui.mw(8));
        status = ui.text(exportVerified ? "✓ 已导出并校验：" + exportName + "。现在可以删除分区备份与卸载记录。"
                : "分区备份与卸载记录须先导出 ZIP 并校验通过，才能删除。ZIP 内含 SHA256SUMS 和 info.json（应用版本、来源目录、导出时间）。",
                12.5f, exportVerified ? ui.primary : ui.muted, false);
        body.addView(status, ui.mw(10));
        TextView del = ui.button("删除所选", 2, v -> confirmDelete());
        del.setEnabled(!busy && !working);
        body.addView(del, ui.mw(8));
    }

    // ------------------------------------------------------------------ export
    private List<File[]> exportSet() {
        // {file, base} pairs; ALL backups (including protected unfinished records) are exported
        List<File[]> out = new ArrayList<>();
        for (File base : new File[]{jm.backupBase("mu300-backup"), jm.backupBase("e5-part"), jm.uninstallRoot()})
            collect(base, base.getParentFile(), out);
        return out;
    }
    private static void collect(File f, File base, List<File[]> out) {
        if (f == null || !f.exists()) return;
        if (f.isFile()) { out.add(new File[]{f, base}); return; }
        File[] k = f.listFiles();
        if (k == null) return;
        java.util.Arrays.sort(k);
        for (File c : k) collect(c, base, out);
    }
    private void exportZip() {
        if (exportSet().isEmpty()) { toast("没有可导出的备份"); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
        i.putExtra(Intent.EXTRA_TITLE, "openwrt-installer-backup-" + ts + ".zip");
        try { startActivityForResult(i, REQ_EXPORT); }
        catch (RuntimeException e) { toast("这台手机没有可用的文件选择器：" + e.getMessage()); }
    }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_EXPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        working = true;
        exportVerified = false;
        build();
        status.setText("正在导出并校验……");
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                int n = writeZip(uri);
                verifyZip(uri);
                ok = true;
                exportName = (uri.getLastPathSegment() != null ? uri.getLastPathSegment() : uri.toString()) + "（" + n + " 个文件）";
                msg = null;
            } catch (Exception e) {
                msg = "导出失败：" + e.getMessage() + "。本机文件未删除。";
            }
            final boolean fok = ok;
            final String fm = msg;
            main.post(() -> {
                working = false;
                exportVerified = fok;
                build();
                if (fm != null) { status.setText(fm); status.setTextColor(ui.danger); }
            });
        }, "cleanup-export").start();
    }
    private LinkedHashMap<String, String> manifest;
    private int writeZip(Uri uri) throws IOException {
        List<File[]> set = exportSet();
        manifest = new LinkedHashMap<>();
        try (OutputStream raw = getContentResolver().openOutputStream(uri, "w")) {
            if (raw == null) throw new IOException("无法写入所选位置");
            ZipOutputStream z = new ZipOutputStream(raw);
            byte[] buf = new byte[1 << 16];
            for (File[] fb : set) {
                String name = fb[1].toURI().relativize(fb[0].toURI()).getPath();
                MessageDigest md = PackageInspector.sha256();
                z.putNextEntry(new ZipEntry(name));
                try (InputStream in = new FileInputStream(fb[0])) {
                    for (int r; (r = in.read(buf)) > 0; ) { md.update(buf, 0, r); z.write(buf, 0, r); }
                }
                z.closeEntry();
                manifest.put(name, PackageInspector.hex(md.digest()));
            }
            StringBuilder sums = new StringBuilder();
            for (Map.Entry<String, String> e : manifest.entrySet()) sums.append(e.getValue()).append("  ").append(e.getKey()).append('\n');
            z.putNextEntry(new ZipEntry("SHA256SUMS"));
            z.write(sums.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            z.closeEntry();
            LinkedHashMap<String, Object> info = new LinkedHashMap<>();
            info.put("app", "OpenWrt 安装器（安卓版）");
            info.put("app_version", BuildInfo.VERSION_NAME);
            info.put("phone_model", Build.MODEL);
            info.put("created", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US).format(new java.util.Date()));
            info.put("files", manifest.size());
            List<Object> src = new ArrayList<>();
            for (File d : new File[]{jm.backupBase("mu300-backup"), jm.backupBase("e5-part"), jm.uninstallRoot()}) src.add(d.getAbsolutePath());
            info.put("source_dirs", src);
            z.putNextEntry(new ZipEntry("info.json"));
            z.write(Mu300Internal.json(info, true).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            z.closeEntry();
            z.finish();
            z.flush();
        }
        return manifest.size();
    }
    /** Re-opens the written document and checks every entry against the manifest. */
    private void verifyZip(Uri uri) throws IOException {
        LinkedHashMap<String, String> seen = new LinkedHashMap<>();
        boolean sums = false, info = false;
        try (InputStream raw = getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("无法重新读取导出的 ZIP");
            ZipInputStream z = new ZipInputStream(raw);
            byte[] buf = new byte[1 << 16];
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                MessageDigest md = PackageInspector.sha256();
                for (int r; (r = z.read(buf)) > 0; ) md.update(buf, 0, r);
                if (e.getName().equals("SHA256SUMS")) sums = true;
                else if (e.getName().equals("info.json")) info = true;
                else if (seen.put(e.getName(), PackageInspector.hex(md.digest())) != null) throw new IOException("ZIP 中有重复条目 " + e.getName());
            }
        }
        if (!sums || !info) throw new IOException("ZIP 缺少 SHA256SUMS 或 info.json");
        if (!seen.equals(manifest)) throw new IOException("回读校验不一致（" + seen.size() + "/" + manifest.size() + " 个文件）");
    }

    // ------------------------------------------------------------------ delete
    private void confirmDelete() {
        if (jm.busy != null) { toast("正在执行任务，结束后再清理"); return; }
        List<Cat> sel = new ArrayList<>();
        boolean backups = false;
        long bytes = 0;
        for (Cat c : cats) if (c.checked && c.bytes > 0) { sel.add(c); bytes += c.bytes; backups |= c.backup; }
        if (sel.isEmpty()) { toast("请先勾选要删除的内容"); return; }
        if (backups && !exportVerified) {
            new AlertDialog.Builder(this).setTitle("请先导出备份")
                    .setMessage("分区备份与卸载记录删除后无法从本机恢复；以后卸载、或把启动链恢复成 Android 原样时需要它们。"
                            + "请先点“导出分区备份与卸载记录为 ZIP”，校验通过后再删除。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        StringBuilder m = new StringBuilder("将删除：\n");
        for (Cat c : sel) m.append("• ").append(c.title).append("（").append(human(c.bytes)).append("）\n");
        m.append("\n未完成的续装 / 卸载记录和 ADB 授权密钥不会删除。");
        if (backups) m.append("\n分区备份已导出到：").append(exportName).append("。\n请输入“删除备份”确认：");
        final EditText in = new EditText(this);
        AlertDialog.Builder d = new AlertDialog.Builder(this).setTitle("确认清理 " + human(bytes)).setMessage(m.toString());
        final boolean fb = backups;
        if (backups) d.setView(in);
        d.setNegativeButton("取消", null).setPositiveButton("删除", (x, w) -> {
            if (fb && !"删除备份".equals(in.getText().toString().trim())) { toast("输入不正确，未删除"); return; }
            doDelete(sel);
        }).show();
    }
    private void doDelete(List<Cat> sel) {
        working = true;
        build();
        new Thread(() -> {
            long freed = 0;
            for (Cat c : sel) for (File r : c.roots) freed += delete(r);
            final long f = freed;
            main.post(() -> {
                working = false;
                toast("已释放 " + human(f));
                scan();
            });
        }, "cleanup-delete").start();
    }
    /** Deletes f except protected dirs and the ADB key; returns bytes freed. */
    private long delete(File f) {
        if (f == null || !f.exists() || kept(f)) return 0;
        if (f.getName().equals("adbkey") || f.getName().equals("adbkey.pub")) return 0;
        if (f.isFile()) { long n = f.length(); return f.delete() ? n : 0; }
        long n = 0;
        File[] k = f.listFiles();
        if (k != null) for (File c : k) n += delete(c);
        // keep the root dirs themselves (backupBase dirs, cache dir); remove emptied subdirs
        String[] left = f.list();
        boolean root = f.equals(getCacheDir()) || f.equals(jm.uninstallRoot()) || f.equals(jm.backupBase("mu300-backup")) || f.equals(jm.backupBase("e5-part"));
        if (!root && left != null && left.length == 0) f.delete();
        return n;
    }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }
    static String human(long b) {
        if (b < (1L << 20)) return String.format(java.util.Locale.US, "%.1f KiB", b / 1024.0);
        if (b < (1L << 30)) return String.format(java.util.Locale.US, "%.1f MiB", b / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GiB", b / 1073741824.0);
    }
}
