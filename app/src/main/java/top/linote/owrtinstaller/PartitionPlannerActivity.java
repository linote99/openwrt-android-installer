package top.linote.owrtinstaller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Advanced in-app partition planner. This screen runs only against synthetic in-memory GPT images. */
public final class PartitionPlannerActivity extends Activity {
    private Ui ui;
    private EditText userdataSize, wipePhrase;
    private CheckBox formatUserdata, use4k;
    private LinearLayout partitionRows, result;
    private TextView baselineStatus;
    private PartitionPlanner.Snapshot importedBaseline;
    private final List<Row> rows = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private int extraNumber = 1;
    private static final int REQUEST_BASELINE_ZIP = 891;
    private static final int REQUEST_RESUME_JSON = 892;

    private static final class Row {
        final LinearLayout box;
        final EditText purpose, label, size;
        Row(LinearLayout box, EditText purpose, EditText label, EditText size) {
            this.box = box; this.purpose = purpose; this.label = label; this.size = size;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        setTitle("分区规划（高级）");
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(ui.bg);
        LinearLayout body = ui.col();
        body.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(28));
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        body.addView(ui.h1("多分区规划"));
        body.addView(ui.sub("集成在安装器高级功能中。当前页面只生成本地合成 GPT 预览，不读取或修改任何设备。"), ui.mw(4));
        body.addView(ui.notice("i", "基线与当前布局", "上传的原始 GPT 备份是安装前基线，只能作比较参考；它不是当前布局，不能直接写回。当前设备的独立只读 GPT 证据尚未取得。下面的布局是虚构的合成样例，不代表你的设备。", 0), ui.mw(14));
        body.addView(ui.notice("!", "设备写入已关闭", PartitionPlanner.WRITE_BLOCKER + " 需要补齐设备专属 GPT 类型/唯一 GUID、userdata 起始边界、加密与 metadata、原厂 recovery/fstab/vold/init 和 A/B/AVB 启动证据；没有这些证据时不能开放设备写入，也不能承诺 Android 可正常启动。", 0), ui.mw(10));

        LinearLayout baselineCard = ui.card();
        baselineCard.addView(ui.text("原始 GPT 备份（历史基线，只读）", 15, ui.text, true), ui.mw(8));
        baselineStatus = ui.sub("尚未导入。基线只用于比较，不会写回；不包含当前设备只读快照。");
        baselineCard.addView(baselineStatus, ui.mw(4));
        baselineCard.addView(ui.button("导入原始 GPT 备份 ZIP", 0, v -> chooseBaselineZip()), ui.mw(8));
        baselineCard.addView(ui.button("导入续装记录 JSON（仅查看）", 0, v -> chooseResumeRecord()), ui.mw(4));
        baselineCard.addView(ui.text("记录不会变成可续装任务；记录中的命令、路径、脚本和附件不会执行或跟随。", 12, ui.muted, false), ui.mw(4));
        body.addView(baselineCard, ui.mw(12));

        LinearLayout options = ui.card();
        options.addView(ui.text("合成布局参数", 16, ui.text, true), ui.mw(8));
        options.addView(ui.text("示例盘：16 GiB；保留 boot_a / boot_b、vbmeta、misc、metadata、system 和一个既有 mu300root。userdata 起点固定不动。", 12.5f, ui.muted, false), ui.mw(4));
        userdataSize = numeric("userdata 规划容量（MiB）", "7168");
        options.addView(fieldRow("userdata 规划容量（MiB）", userdataSize), ui.mw(8));
        formatUserdata = new CheckBox(this);
        formatUserdata.setText("模拟格式化 userdata（将清空全部 Android 用户数据）");
        formatUserdata.setTextColor(ui.danger);
        options.addView(formatUserdata, ui.mw(6));
        wipePhrase = new EditText(this);
        wipePhrase.setSingleLine(true);
        wipePhrase.setTextSize(13);
        wipePhrase.setHint("只有选择模拟格式化时，输入本计划专属确认语");
        wipePhrase.setVisibility(View.GONE);
        options.addView(wipePhrase, ui.mw(4));
        use4k = new CheckBox(this);
        use4k.setText("模拟 4096-byte GPT 逻辑块（否则 512-byte）");
        use4k.setTextColor(ui.text);
        options.addView(use4k, ui.mw(4));
        body.addView(options, ui.mw(12));

        LinearLayout partitionCard = ui.card();
        LinearLayout titleRow = ui.row();
        titleRow.addView(ui.text("计划新建的分区", 16, ui.text, true), ui.weight(1));
        titleRow.addView(ui.button("添加分区", 0, v -> addRow("额外数据", "extra" + extraNumber++, "256")));
        partitionCard.addView(titleRow, ui.mw(8));
        partitionRows = ui.col();
        partitionCard.addView(partitionRows, ui.mw(6));
        body.addView(partitionCard, ui.mw(12));
        addRow("OpenWrt 辅助数据", "owrt_aux", "1024");
        addRow("共享存储", "shared", "512");

        TextView generate = ui.button("生成 / 更新离线预览", 1, v -> generatePreview());
        body.addView(generate, ui.mw(12));
        result = ui.col();
        body.addView(result, ui.mw(10));
        formatUserdata.setOnCheckedChangeListener((button, checked) -> {
            wipePhrase.setVisibility(checked ? View.VISIBLE : View.GONE);
            generatePreview();
        });
        use4k.setOnCheckedChangeListener((button, checked) -> generatePreview());
        loadSharedBaseline();
        generatePreview();
    }

    private void loadSharedBaseline() {
        try {
            importedBaseline = ImportedBaselineStore.load(getFilesDir());
            if (importedBaseline != null) baselineStatus.setText(ImportedBaselineStore.description(getFilesDir())
                    + " 这是历史基线，不是当前设备只读快照；不会授权恢复或写入。");
        } catch (IOException e) {
            importedBaseline = null;
            baselineStatus.setText("本地历史基线校验失败，不会用于规划；请从备份 ZIP 重新导入。设备写入仍关闭。");
        }
    }

    private void chooseBaselineZip() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("application/zip");
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(pick, REQUEST_BASELINE_ZIP); }
        catch (RuntimeException e) {
            baselineStatus.setText("无法打开文件选择器；未导入任何基线。设备写入仍保持关闭。");
        }
    }

    private void chooseResumeRecord() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("application/json");
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(pick, REQUEST_RESUME_JSON); }
        catch (RuntimeException e) { baselineStatus.setText("无法打开记录选择器；没有导入或恢复任何记录。"); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != REQUEST_BASELINE_ZIP && requestCode != REQUEST_RESUME_JSON)
                || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        final int request = requestCode;
        baselineStatus.setText(request == REQUEST_BASELINE_ZIP ? "正在离线校验 ZIP；不会读取设备或提取附件……"
                : "正在离线校验续装记录；仅查看，不会恢复……");
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (request == REQUEST_RESUME_JSON) {
                    SafeImport.RecordSummary record = SafeImport.readResumeRecord(in);
                    main.post(() -> baselineStatus.setText(record.safeText(false)));
                } else {
                    SafeImport.ArchiveReport report = SafeImport.readBackupZip(in);
                    main.post(() -> handleArchiveReport(report));
                }
            } catch (Exception e) {
                main.post(() -> baselineStatus.setText("导入拒绝：" + (e.getMessage() == null ? "文件格式或完整性无效" : e.getMessage())
                        + "。未保存文件、未恢复记录、未访问设备；写入仍关闭。"));
            }
        }, "safe-backup-import").start();
    }

    private void handleArchiveReport(SafeImport.ArchiveReport report) {
        StringBuilder text = new StringBuilder(report.safeSummary());
        for (SafeImport.RecordSummary r : report.records) text.append("\n\n").append(r.safeText(report.outerHashVerified));
        if (report.backups.isEmpty()) {
            baselineStatus.setText(text.toString() + "\n\n未找到可用 GPT 基线；没有保存任何内容。");
            generatePreview();
            return;
        }
        String[] labels = new String[report.backups.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = report.backups.get(i).safeLabel(i + 1);
        if (labels.length == 1) saveSelectedBaseline(report.backups.get(0), text.toString(), report.outerHashVerified);
        else new AlertDialog.Builder(this).setTitle("选择用于比较的历史 GPT 基线")
                .setItems(labels, (d, which) -> saveSelectedBaseline(report.backups.get(which), text.toString(), report.outerHashVerified))
                .setNegativeButton("取消", (d, w) -> baselineStatus.setText(text + "\n\n未更换已保存的历史基线。"))
                .show();
    }

    private void saveSelectedBaseline(SafeImport.BackupItem item, String importSummary, boolean outerHashVerified) {
        try {
            ImportedBaselineStore.save(getFilesDir(), item.baseline);
            importedBaseline = ImportedBaselineStore.load(getFilesDir());
            String identity = "E5".equals(item.family) ? "E5 记录中的 userdata 几何与 GPT 匹配；不证明设备身份"
                    : !item.identityConsistent ? "备份未含可交叉核对的 identity.json（GPT 本身通过结构校验）"
                    : outerHashVerified ? "备份内 identity.json 结构有效，且与所含记录交叉字段一致（如有）；外层哈希不证明设备身份"
                    : "旧式备份的 identity.json/记录字段仅做结构与一致性检查，且未受外层哈希保护；不证明设备身份";
            baselineStatus.setText(importSummary + "\n\n已保存选定的 " + item.family + " 历史 GPT 基线。" + identity
                    + "。仅 GPT 主/备快照写入应用私有目录；不保存其它 ZIP 成员。它不是当前设备证据，不授权恢复或写入。\n"
                    + ImportedBaselineStore.description(getFilesDir()));
        } catch (IOException e) {
            baselineStatus.setText("ZIP 校验完成，但保存历史基线失败；未更换已保存的基线。设备写入仍关闭。");
        }
        generatePreview();
    }

    private EditText numeric(String label, String value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(value);
        e.setTextSize(14);
        e.setSelectAllOnFocus(true);
        e.setContentDescription(label);
        return e;
    }

    private LinearLayout fieldRow(String label, EditText field) {
        LinearLayout r = ui.col();
        r.addView(ui.text(label, 12.5f, ui.muted, false));
        r.addView(field, ui.mw(2));
        return r;
    }

    private void addRow(String purposeValue, String labelValue, String sizeValue) {
        LinearLayout row = ui.card();
        row.setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(8));
        EditText purpose = new EditText(this);
        purpose.setSingleLine(true); purpose.setText(purposeValue); purpose.setTextSize(13);
        purpose.setHint("用途");
        EditText label = new EditText(this);
        label.setSingleLine(true); label.setText(labelValue); label.setTextSize(13);
        label.setHint("GPT 分区标签");
        EditText size = numeric("大小（MiB）", sizeValue);
        LinearLayout fields = ui.col();
        fields.addView(fieldRow("用途", purpose));
        fields.addView(fieldRow("分区标签", label), ui.mw(4));
        fields.addView(fieldRow("大小（MiB）", size), ui.mw(4));
        row.addView(fields, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Row model = new Row(row, purpose, label, size);
        TextView remove = ui.button("移除此分区", 3, v -> {
            if (rows.size() <= 1) return;
            rows.remove(model);
            partitionRows.removeView(row);
            generatePreview();
        });
        row.addView(remove, ui.mw(4));
        rows.add(model);
        partitionRows.addView(row, ui.mw(6));
    }

    private void generatePreview() {
        if (result == null) return;
        result.removeAllViews();
        try {
            int block = use4k != null && use4k.isChecked() ? 4096 : 512;
            PartitionPlanner.Snapshot current = PartitionPlanner.syntheticCurrent(block);
            long userdataMiB = parseLong(userdataSize, "userdata 新容量");
            List<PartitionPlanner.NewPartition> specs = new ArrayList<>();
            for (Row row : rows) {
                specs.add(new PartitionPlanner.NewPartition(row.purpose.getText().toString(), row.label.getText().toString(),
                        parseLong(row.size, "分区大小")));
            }
            PartitionPlanner.Request request = new PartitionPlanner.Request(userdataMiB, formatUserdata.isChecked(), false,
                    wipePhrase.getText().toString(), specs);
            PartitionPlanner.Preview preview = PartitionPlanner.preview(current, importedBaseline, request);
            showPreview(preview);
        } catch (IOException | NumberFormatException | ArithmeticException e) {
            result.addView(ui.notice("!", "无法生成预览", e.getMessage() == null ? "输入无效或空间不足；没有进行任何写入。" : e.getMessage(), 1), ui.mw(6));
        }
    }

    private long parseLong(EditText field, String name) throws IOException {
        String s = field.getText().toString().trim();
        if (!s.matches("[0-9]{1,12}")) throw new IOException(name + "必须是正整数");
        try { long n = Long.parseLong(s); if (n <= 0) throw new IOException(name + "必须大于 0"); return n; }
        catch (NumberFormatException e) { throw new IOException(name + "超出允许范围", e); }
    }

    private void showPreview(PartitionPlanner.Preview p) {
        result.addView(ui.text("离线模拟预览", 18, ui.text, true), ui.mw(4));
        result.addView(ui.notice("!", "不可用于设备写入", "设备写入资格：关闭。即使本计划被手动确认，也只改变内存中的合成字节；不会连接、格式化、写 GPT、写启动分区或重启设备。", 1), ui.mw(8));
        String baseline = p.baseline == null ? "未导入历史备份"
                : p.baselineMatches ? "与合成当前布局一致"
                : "与合成当前布局不同（非设备比较；旧基线不写回）";
        result.addView(ui.card(), ui.mw(8));
        LinearLayout info = (LinearLayout) result.getChildAt(result.getChildCount() - 1);
        ui.addKvs(info, new String[][]{
                {"当前布局证据", p.current.origin == PartitionPlanner.Origin.CURRENT_DEVICE_READ_ONLY ? "设备只读快照" : "合成模拟（非设备证据）"},
                {"历史 GPT 基线", baseline},
                {"基线身份摘要", p.baseline == null ? "无" : "不显示；历史 GPT 仅用于布局比较"},
                {"GPT 逻辑块", p.current.logicalBlockSize + " B"},
                {"userdata 起点", "扇区 " + find(p.proposedEntries, "userdata").start + "（保持不变）"},
                {"userdata 容量", find(p.proposedEntries, "userdata").sizeMiB() + " MiB"},
                {"计划指纹", p.fingerprint.substring(0, 16) + "…"},
        });
        result.addView(ui.text("分区表预览（扇区均为 512-byte sector，end 含最后一个扇区）", 14, ui.text, true), ui.mw(10));
        List<PartitionPlanner.Entry> ordered = new ArrayList<>(p.proposedEntries);
        Collections.sort(ordered, Comparator.comparingLong(e -> e.start));
        for (PartitionPlanner.Entry e : ordered) {
            boolean aligned = e.start % PartitionPlanner.ALIGNMENT_SECTORS == 0
                    && (e.end + 1) % PartitionPlanner.ALIGNMENT_SECTORS == 0;
            String detail = PartitionPlanner.formatRange(e) + "\n起止边界：" + (aligned ? "1 MiB 对齐" : "沿用现状；不由计划移动")
                    + "\n用途：" + purposeFor(e.name) + "\n类型 GUID：" + e.typeGuid + "\n唯一 GUID：" + e.uniqueGuid;
            result.addView(ui.notice(aligned ? "✓" : "i", e.name, detail, 0), ui.mw(6));
        }
        result.addView(ui.notice("i", "GPT 主备表变化", p.gptImpact(), 0), ui.mw(8));
        result.addView(ui.notice("!", "数据影响", p.dataImpact(), 1), ui.mw(8));
        if (p.destructiveConfirmationRequired) {
            result.addView(ui.notice(p.destructiveConfirmationAccepted ? "✓" : "!", "userdata 清空确认",
                    p.destructiveConfirmationAccepted ? "已输入本计划专属确认语；这只确认模拟计划，不授权任何设备写入。"
                            : "输入以下完整文字后可在预览中标记为已确认；计划变化后确认语会变化。\n" + p.requiredWipePhrase,
                    p.destructiveConfirmationAccepted ? 0 : 1), ui.mw(8));
        }
        LinearLayout blockers = ui.col();
        blockers.addView(ui.text("设备写入阻塞项", 14, ui.danger, true));
        for (String b : p.blockers) blockers.addView(ui.text("• " + b, 12.5f, ui.danger, false), ui.mw(4));
        result.addView(blockers, ui.mw(8));
        result.addView(ui.text("合成测试结果不是设备兼容性证明；不要按此预览手工修改 GPT。", 12, ui.muted, false), ui.mw(10));
    }

    private String purposeFor(String label) {
        for (Row r : rows) if (r.label.getText().toString().trim().equals(label)) return r.purpose.getText().toString().trim();
        return "既有分区（受保护、保持原样）";
    }

    private PartitionPlanner.Entry find(List<PartitionPlanner.Entry> entries, String name) {
        for (PartitionPlanner.Entry e : entries) if (name.equalsIgnoreCase(e.name)) return e;
        throw new IllegalStateException("计划中缺少 " + name);
    }
}
