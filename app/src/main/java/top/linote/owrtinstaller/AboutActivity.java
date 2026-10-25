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

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        setTitle("使用说明");
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
        TextView t = ui.text("使用说明", 17, ui.text, true);
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
        body.addView(v, p);
    }

    private void section(String title) { copy(title, 18, ui.text, true, 18, 8); }
    private void para(String s) { copy(s, 14, ui.muted, false, 0, 6); }

    private void model(String name, String req, String methods, String addr) {
        LinearLayout c = ui.card();
        c.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        c.addView(ui.text(name, 14.5f, ui.text, true));
        TextView d = ui.text("机型：" + req + "\n安装方式：" + methods + "\n管理地址：" + addr, 12.5f, ui.muted, false);
        d.setLineSpacing(ui.dp(2), 1.05f);
        c.addView(d, ui.mw(4));
        body.addView(c, ui.mw(6));
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
        body.addView(row, rp);
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

        section("使用说明");
        para("1. 设备：选择“本机”（本机需 root），或“其他设备”——用 OTG 线有线连接（USB 调试），"
                + "或同一局域网的无线 ADB（5555 端口）。连接其他设备时本机无需 root，但目标设备需要已 root；"
                + "首次连接请在目标设备上允许调试，并在其 root 管理器中允许 Shell 的超级用户请求。");
        para("2. 安装包：选择与目标设备匹配的完整 ZIP，不用解压。应用会逐个校验 ZIP 内所有文件的 SHA-256。");
        para("3. 安装方式：TF 卡或独立内置分区，以机型支持为准；重装已有内置分区、userdata 镜像（实验性）在“高级功能”里，见本页底部。");
        para("4. 检查与计划：先做只读检查，不写入任何分区；核对“数据影响”，勾选确认并输入 yes 后才开始安装"
                + "（需要格式化 userdata 时还要另外勾选，并输入“清空数据”）。");
        para("5. 安装与完成：安装在目标设备后台运行，USB 拔线或网络断开不影响；重新连接即可继续查看进度。"
                + "完成后按提示重启目标设备进入 OpenWrt，默认用户名和密码均为 root，请尽快修改。");

        section("支持机型");
        para("每个安装包只能装到对应机型，型号或主板不符时会拒绝安装。");
        String all = "TF 卡 / 独立内置分区；高级功能：重装已有内置分区、userdata 镜像（实验性）";
        model("E5", "E5（需 Bootloader 已解锁：orange）", all, "http://192.168.9.1");
        model("远航41S", "ZTE 7546N，主板 P720S11", all, "http://192.168.9.1");
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
        para("• 重装已有内置分区【高级】：清空已有的 OpenWrt 内置分区（e5root / mu300root，含 OpenWrt 系统和设置）并重新写入；"
                + "不改分区表，保留 Android。适合已有内置 OpenWrt、只想重新安装，无需插 TF 卡。");
        para("• userdata 镜像安装【实验性】：在 userdata 里创建 OpenWrt 镜像，不格式化 userdata，仅限未加密的 userdata。"
                + "userdata 镜像是实验性功能，可能损坏 Android F2FS 文件系统，建议优先选择 TF 卡或独立内置分区。");

        section("友情链接");
        link(R.drawable.linote_logo, "Lino 笔记", "https://www.linote.top/", false);
        link(R.drawable.github_invertocat, "GitHub 主页", "https://github.com/linote99", true);
        link(R.drawable.github_invertocat, "开源仓库（本应用源码）", "https://github.com/linote99/openwrt-android-installer", true);
    }
}
