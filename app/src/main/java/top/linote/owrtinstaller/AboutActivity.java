package top.linote.owrtinstaller;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** About page: usage, supported models, notes, author and links (no changelog). */
public final class AboutActivity extends Activity {
    private Ui ui;
    private LinearLayout body;
    private LinearLayout sectionContent;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        setTitle("设置");
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
        TextView t = ui.text("设置", 17, ui.text, true);
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
        build();
    }

    /** Same value as the APK's versionName: both come from version.properties via build.sh. */
    private String version() { return BuildInfo.VERSION_NAME; }

    private void copy(String s, float sp, int color, boolean bold, int top, int bottom) {
        TextView v = ui.text(s, sp, color, bold);
        v.setLineSpacing(ui.dp(3), 1.05f);
        LinearLayout.LayoutParams p = ui.mw(top);
        p.bottomMargin = ui.dp(bottom);
        destination().addView(v, p);
    }

    private LinearLayout destination() { return sectionContent == null ? body : sectionContent; }

    private void section(String title) {
        LinearLayout card = ui.card();
        card.setPadding(0, 0, 0, 0);
        LinearLayout header = ui.row();
        header.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        header.setMinimumHeight(ui.dp(48));
        header.setClickable(true);
        header.setFocusable(true);
        header.setBackground(ui.ripple(ui.round(0x00000000, 0, 12)));
        TextView label = ui.text(title, 15, ui.text, true);
        header.addView(label, ui.weight(1));
        TextView arrow = ui.text("⌄", 18, ui.primary, true);
        arrow.setGravity(Gravity.CENTER);
        header.addView(arrow, new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));

        LinearLayout details = ui.col();
        details.setVisibility(View.GONE);
        details.setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(14));
        details.addView(ui.divider());
        header.setContentDescription(title + "，已折叠，点击展开");
        header.setOnClickListener(v -> {
            boolean expanded = details.getVisibility() != View.VISIBLE;
            details.setVisibility(expanded ? View.VISIBLE : View.GONE);
            arrow.setText(expanded ? "⌃" : "⌄");
            header.setContentDescription(title + (expanded ? "，已展开，点击折叠" : "，已折叠，点击展开"));
        });
        card.addView(header);
        card.addView(details);
        body.addView(card, ui.mw(9));
        sectionContent = details;
    }
    private void para(String s) { copy(s, 14, ui.muted, false, 0, 6); }

    private void model(String name, String req, String methods, String addr) {
        LinearLayout c = ui.card();
        c.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        c.addView(ui.text(name, 14.5f, ui.text, true));
        TextView d = ui.text("机型：" + req + "\n安装方式：" + methods + "\n管理地址：" + addr, 12.5f, ui.muted, false);
        d.setLineSpacing(ui.dp(2), 1.05f);
        c.addView(d, ui.mw(4));
        destination().addView(c, ui.mw(6));
    }

    private void link(int icon, String title, String url, boolean tint) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ui.dp(3), 0, ui.dp(3));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(title);
        row.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (RuntimeException e) { Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show(); }
        });
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (tint && Build.VERSION.SDK_INT >= 21) iv.setImageTintList(ColorStateList.valueOf(ui.text));
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24));
        ip.rightMargin = ui.dp(8);
        row.addView(iv, ip);
        row.addView(ui.text(title, 14, ui.text, true));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(32));
        rp.bottomMargin = ui.dp(2);
        destination().addView(row, rp);
    }

    private void build() {
        LinearLayout top = ui.row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        top.addView(logo, new LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)));
        LinearLayout tt = ui.col();
        tt.setPadding(ui.dp(14), 0, 0, 0);
        tt.addView(ui.text("OpenWrt 安装器", 21, ui.text, true));
        tt.addView(ui.text("安卓版 · 版本 " + version(), 13.5f, ui.muted, false), ui.mw(2));
        tt.addView(ui.text("作者：Lino", 13.5f, ui.muted, false), ui.mw(2));
        top.addView(tt, ui.weight(1));
        body.addView(top, ui.mw(0));
        copy("把“OpenWrt 一键刷入包”的安装流程移植到手机上：选择完整安装包 ZIP，校验后只提取需要的安装文件，"
                + "再调用安装包自带的设备端脚本安装 OpenWrt，不需要电脑。", 14, ui.muted, false, 14, 0);
        copy("点击下方栏目标题展开或折叠内容。", 12.5f, ui.muted, false, 8, 0);

        section("使用说明");
        para("1. 设备：选择“本机”（本机需 root），或“其他设备”——用 OTG 线有线连接（USB 调试），"
                + "或同一局域网的无线 ADB（5555 端口）。连接其他设备时本机无需 root，但目标设备需要已 root；"
                + "首次连接请在目标设备上允许调试，并在其 root 管理器中允许 Shell 的超级用户请求。");
        para("  连接后按电脑端的顺序逐项检测：是否已开机、是否允许了 USB 调试、开机向导是否走完、是否有 root"
                + "（adbd 本身是 root，或 su / /debug_ramdisk/su / /sbin/su 授权给 Shell）。没就绪时设备页会说明缺哪一步"
                + "（面具没装 / 已有 su 但 Shell 未授权 / 未允许调试 / 开机向导未完成 / 正在开机），每 3 秒自动重试，"
                + "在目标设备上操作完会自动往下走，不想等可以点“停止”。本机模式只检查本机 root。");
        para("  续装或卸载等待目标设备时可以换连接方式（USB ↔ 局域网、换 IP）：会核对 eMMC/UFS 存储身份和槽位，确认是同一台设备才接着做。");
        para("2. 安装包：选择与目标设备匹配的完整 ZIP，不用解压。应用会逐个校验 ZIP 内所有文件的 SHA-256。");
        para("3. 安装方式：TF 卡或独立内置分区，以机型支持为准；重装已有内置分区、userdata 镜像（实验性）在“高级功能”里，见本页底部。");
        para("4. 检查与计划：先做只读检查，不写入任何分区；核对“数据影响”，勾选确认并输入 yes 后才开始安装"
                + "（需要格式化 userdata 时还要另外勾选，并输入“清空数据”）。");
        para("5. 安装与完成：安装在目标设备后台运行，USB 拔线或网络断开不影响；重新连接即可继续查看进度。"
                + "完成后按提示重启目标设备进入 OpenWrt，默认用户名和密码均为 root，请尽快修改。");

        section("MU300 UFS 适配");
        para("支持 MU300 的 UFS 独立内置分区安装与重装；该安装包脚本支持的两阶段路径是先准备、按提示重启后再续装。");
        para("现在可用“卸载并归还空间”从 UFS 删除 OpenWrt 并把空间归还给 Android；只适用于上方列出的完整安装包。"
                + "UFS 安装与重装流程保持不变，原有 eMMC 内置安装和 TF 卡流程也保留。");
        para("UFS 卸载只接受已识别的 MU300 UFS 身份、512 或 4096 字节 GPT 逻辑块，以及边界完整且唯一的 mu300root/userdata 布局和安装证据。"
                + "无法唯一确认设备、分区归属、GPT 范围、扇区单位或布局时会拒绝；同意恢复出厂也不能绕过这些拒绝。");
        para("若卸载检查只因 mu300root 的 ext4 needs_recovery 停止，符合严格条件时可选择“仅回放 mu300root ext4 journal”："
                + "根分区节点必须与当前 GPT 中唯一的 mu300root 标签及其分区几何一致（分区号不固定），并满足 state=0x0001、incompat=0x00000046、4096-byte block、error_count=0 和内部 journal；"
                + "目标必须未挂载，且不能配置 data=journal。输入“修复 mu300root journal”后，只执行 e2fsck -p -E journal_only，"
                + "再做完整只读检查并重新核对安装证据。其它状态、身份变化、修复返回异常或只读复核失败时停止，不会用 -y、格式化、扩容、重试或自动卸载；"
                + "修复成功后仍要单独走正常卸载确认。工具暂存于 RAM；只写目标 ext4 内部 journal/元数据，不写 GPT、boot、misc、userdata 或其它分区。");
        para("UFS 卸载目前只通过离线静态检查、固定哈希校验、512/4096-byte 合成 GPT 测试和模拟流程验证；"
                + "没有在真实 UFS 设备上验证安装或卸载，不能据此认定实际设备操作成功。开始前请先备份重要数据。");

        section("获取更新");
        para("新版安装包可从以下两个渠道获取：打开 Lino 笔记中本应用的对应文章，查看文章内的下载链接；或查看 GitHub 开源仓库。下载 APK 后直接安装更新。");
        link(R.drawable.linote_logo, "博客文章（含下载链接）", "https://www.linote.top/posts/openwrt-android-installer/", false);
        link(R.drawable.github_invertocat, "GitHub 开源仓库", "https://github.com/linote99/openwrt-android-installer", true);

        section("支持机型");
        para("每个安装包只能装到对应机型，型号或主板不符时会拒绝安装。");
        String all = "TF 卡 / 独立内置分区；高级功能：重装已有内置分区、userdata 镜像（实验性）";
        model("E5", "E5（需 Bootloader 已解锁：orange）", all, "http://192.168.9.1");
        model("远航41S", "ZTE 7546N，主板 P720S11", all, "http://192.168.9.1");
        model("Changxing60", "ZTE 7552N，主板 P720S20", all, "http://192.168.9.1");
        model("T551M", "T551M（需 orange）", all, "http://192.168.9.1");
        model("T512N", "T512N", all, "http://192.168.9.1");
        model("VIPME M8", "VIPME M8（需 orange）", all, "http://192.168.9.1");
        model("angelcare K1", "angelcare K1，主板 ums9157_2h10（需 orange）", all, "http://192.168.9.1");
        model("MU300（内核 7.2.8 / 6.18.54 两个包）", "ZTE F50 / MU300 / U30 Air，或 V50 / MU3351（UMS9620 平台）",
                all, "http://192.168.77.1");

        section("注意事项");
        para("• 不要把安装包刷进不在支持列表里的设备（例如用来操作的手机本身）。");
        para("• TF 卡方式会清空 TF 卡上的目标分区或整卡；内部存储和 Android 数据不动。");
        para("• 独立内置分区方式会修改内部存储的分区表，开机时离线缩小 userdata，断电可能损坏数据，请先备份。"
                + "保留数据缩容要求 userdata 未加密，E5 系还要求 userdata 为 F2FS。");
        para("• userdata 已加密或空间不够时，与电脑端一样只能“格式化 userdata 后安装”：Android 应用、账号、照片及内部存储文件全部清空。"
                + "分两阶段：先重建分区并由原厂 recovery 清空数据；Android 重新设置后，目标设备的开发者选项、USB 调试（以及网络 ADB）、"
                + "Magisk 和 Shell 的 root 授权都没有了：要重新打开 ADB、重新安装 Magisk（面具），连接时在超级用户请求里允许“Shell”，"
                + "再在「安装方式」选“继续格式化后的安装”写入 OpenWrt。这条路径必须用另一台手机通过 USB/局域网操作，"
                + "因为备份和续装记录保存在操作的手机上。");
        para("• MU300 独立内置分区：优先用 eMMC 未分配空间，不动 Android 数据；空间不够时才在首次开机离线缩小 userdata，"
                + "完成后自动重启一次。开始前会把分区表、misc 和两槽启动链备份到本应用目录。"
                + "MU300 的 userdata 镜像方式为实验性，可能损坏 Android 的 F2FS 文件系统。");
        para("• 安装会写入另一个槽的 boot 并切换启动槽；连续启动失败两次会自动回到 Android。当前 Android 槽和 NV 不会写入。");
        para("• 安装失败后，同一次开机内不能重试，请先查看日志，并按提示重启或处理。");
        para("• 校验只能保证文件完整、没被改过，不能证明安装包的来源，请只使用可信来源的安装包。");
        para("• 刷机有风险，请自行承担后果。");

        section("高级功能");
        para("开启：连点页面顶部的“OpenWrt 安装器”标题 5 下（从第 3 下起会提示还差几次），提示“已开启高级功能”后，"
                + "「安装方式」页会多出“高级选项”。状态会记住，下次打开仍然有效。");
        para("关闭：再连点标题 5 下，或点“高级选项”右侧的“关闭高级功能”。"
                + "“继续格式化后的安装”是普通安装方式，不受这个开关影响，一直显示在「安装方式」页。");
        para("• 多分区规划【仅离线模拟】：可预览多个分区的布局和 userdata 影响；当前布局仍是合成预览，设备分区写入入口关闭。"
                + "安全导入只用于对照历史备份，不能验证当前设备身份，也不授权恢复或写入。");
        para("• 导入分区备份 ZIP：校验受支持的嵌套备份目录、文件路径/清单 SHA-256、GPT 主备 CRC 与几何后，仅把选定的一份 GPT 历史基线保存在应用私有存储。"
                + "其它附件不会提取；身份、布局或续装记录不一致、损坏、未知格式会拒绝或只显示警告。导入不是设备验证。");
        para("• 导入续装记录 JSON：只校验已支持版本的字段和哈希格式并脱敏展示，不写入本机续装队列、不执行命令或路径。"
                + "不支持的旧版/未知字段或损坏记录会拒绝；不能据此恢复任务。");
        para("• 重装已有内置分区【高级】：清空已有的 OpenWrt 内置分区（e5root / mu300root，含 OpenWrt 系统和设置）并重新写入；"
                + "不改分区表，保留 Android。适合已有内置 OpenWrt、只想重新安装，无需插 TF 卡。");
        para("• userdata 镜像安装【实验性】：在 userdata 里创建 OpenWrt 镜像，不格式化 userdata，仅限未加密的 userdata。"
                + "userdata 镜像是实验性功能，可能损坏 Android F2FS 文件系统，建议优先选择 TF 卡或独立内置分区。");

        para("• 卸载并归还空间【高级】：删除内置 OpenWrt 分区（e5root / mu300root），适用于受支持的 eMMC；MU300 UFS 仅适用于上方指定的完整安装包。"
                + "与 userdata 相邻时把空间还给 Android 并离线扩容，"
                + "不相邻时释放为未分配空间。只能通过 USB / 局域网操作另一台设备，本机模式不提供。卸载前先在 OpenWrt 里导出配置，"
                + "切回 Android，开启 USB 调试或无线 ADB 并给 Shell 授予 root。");
        para("  只读检查会核对 eMMC CID 或 UFS 身份、盘符/LUN、GPT 逻辑块大小、分区表、已安装的启动镜像和设备上的安装标记，"
                + "并显示存储类型、Android 容量、归还 Android 的空间、释放为空闲的空间。"
                + "只有 userdata 未加密、data 直接在 userdata 上且工具支持时才保留 Android 数据；否则说明原因，"
                + "需要勾选“格式化 userdata（恢复出厂）后卸载”并输入“清空数据”才会继续——格式化会清空 Android 应用、账号、照片和内部存储。"
                + "分区归属或布局无法确认这类错误不能靠同意格式化绕过。TF 卡、userdata 镜像安装不使用这个入口。");
        para("  顺序与电脑端相同：备份 GPT / misc / 启动镜像到本应用 → 上传并校验离线卸载启动镜像 → 写入启动请求 → 目标设备重启离线卸载。"
                + "设备若一直停在 Android，请手动重启一次；恢复出厂后先完成 Android 设置，重新打开 ADB、重新安装 Magisk（面具）并给 Shell 授予 root。"
                + "然后重新连接，选“卸载并归还空间”重新检查，会自动接上记录完成收尾。关闭本应用不会撤销已写入的卸载请求；记录和备份保留在本应用。");
        section("清理本机占用");
        para("只清理这台手机上本应用占用的空间（分区备份、卸载记录、解出的安装文件、生成的启动镜像、日志），不连接目标设备。"
                + "分区备份与卸载记录须先导出为 ZIP 并回读校验通过才能删除；未完成的续装 / 卸载记录和 ADB 授权密钥不会删除。");
        TextView clean = ui.button("打开「清理本机占用」", 0, v -> startActivity(new android.content.Intent(this, CleanupActivity.class)));
        destination().addView(clean, ui.mw(8));
        section("配合 " + UfiTool.NAME);
        para(UfiTool.NAME + "（安卓版）提供 ADB 投屏、安装包备份 / 恢复和一键安装 UFI-TOOLS。需要 root 授权、格式化前备份或格式化后重新打开 ADB 时，"
                + "本应用会显示“打开 " + UfiTool.NAME + "”；没有安装时显示“点此安装”，用浏览器打开下载文章。");
        para("• 格式化前：用它的「ADB 工具」→「备份安装包」备份 Magisk（面具）和自己要保留的软件（只备份安装包，不含 App 数据，其他资料请自行备份）。");
        para("• MU300 系列（MU300 / F50 / U30 Air / V50）：格式化后检测不到 ADB 时，先用它给目标设备安装 UFI-TOOLS，在 UFI-TOOLS（"
                + UfiTool.UFI_TOOLS_URL + "）里开启 ADB；再用它恢复面具和软件，并用「ADB 投屏」在目标设备上给 Shell 授予 root。");
        para("• E5 系（E5、远航 41S、T551M、T512N、M8、K1）：设备有屏幕，直接在开发者选项里打开 USB 调试，恢复备份的面具和软件（可以用它恢复），"
                + "在设备屏幕上给 Shell 授权；投屏只是可选方式。");
        para("• " + UfiTool.USB_NOTE);
        TextView ufi = ui.button(UfiTool.buttonLabel(UfiTool.installed(this)), 0, v -> {
            if (UfiTool.installed(this)) UfiTool.open(this); else UfiTool.openBlog(this);
        });
        destination().addView(ufi, ui.mw(8));
        section("友情链接");
        link(R.drawable.linote_logo, "Lino 笔记", "https://www.linote.top/", false);
        link(R.drawable.github_invertocat, "GitHub 主页", "https://github.com/linote99", true);
        link(R.drawable.github_invertocat, "开源仓库（本应用源码）", "https://github.com/linote99/openwrt-android-installer", true);
    }
}
