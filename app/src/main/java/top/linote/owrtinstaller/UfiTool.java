package top.linote.owrtinstaller;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

/**
 * Link to the companion app "Lino-UFI 工具（安卓版）" (top.linote.ufiinstaller), read statically
 * from its source (1.2.1): only its launcher MainActivity is exported, so it is opened through
 * the launch intent and the user picks the function there. What it offers, as named in its UI:
 *   - "开始一键安装UFI-TOOLS": installs UFI-TOOLS (com.minikano.f50_sms) on ZTE portable WiFi;
 *     UFI-TOOLS then runs at http://192.168.0.1:2333.
 *   - card "ADB 工具": "ADB 投屏（查看并操作设备屏幕）", "备份安装包", "恢复安装包" (.apk / .apks,
 *     installer packages only, no app data); it connects by wireless ADB (default
 *     192.168.0.1:5555, "⚙ 设置") or USB OTG.
 * Not installed: "点此安装" opens the blog article in the browser.
 *
 * Wording depends on the family: MU300 (MU300 / F50 / U30 Air / V50) has no usable screen, so ADB
 * is turned on from UFI-TOOLS and root is granted through screen mirroring; the E5 family are
 * phones with a screen, so the user works on the device itself and mirroring is only optional.
 * Hint texts are plain strings so the desktop tests can check them.
 */
final class UfiTool {
    static final String PKG = "top.linote.ufiinstaller";
    static final String NAME = "Lino-UFI 工具";
    static final String BLOG = "https://www.linote.top/posts/lino-ufi-tools-android/";
    static final String UFI_TOOLS_URL = "http://192.168.0.1:2333";

    private UfiTool() { }

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    /** Opens the companion app; when it is missing (or cannot be started) opens the blog article. Returns true if the app opened. */
    static boolean open(Context c) {
        Intent i = null;
        try { i = c.getPackageManager().getLaunchIntentForPackage(PKG); } catch (RuntimeException ignored) { }
        if (i != null) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return true;
            } catch (RuntimeException ignored) { /* fall through to the article */ }
        }
        openBlog(c);
        return false;
    }

    static void openBlog(Context c) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BLOG)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { /* no browser */ }
    }

    /** Label of the link button. */
    static String buttonLabel(boolean installed) {
        return installed ? "打开 " + NAME : "未安装 " + NAME + " · 点此安装（打开博客文章）";
    }

    /** Both apps cannot hold one USB link at the same time. */
    static final String USB_NOTE = "本应用正用 USB 连接时，" + NAME + " 请用无线 ADB（它的「ADB 工具」→「⚙ 设置」，默认 192.168.0.1:5555）："
            + "同一个 USB 连接不能同时给两个应用用。";

    /** Root grant (TargetProbe ROOT_PERMISSION). MU300: through screen mirroring; E5 family: on the screen, mirroring optional. */
    static String rootGrant(boolean mu300, boolean usb) {
        if (!mu300)
            return "请直接在目标设备屏幕上的超级用户请求里点“允许”（错过了就打开 Magisk → 超级用户，打开 Shell 的开关）。"
                    + "也可以用 " + NAME + " 的「ADB 投屏」在本机上操作（可选）。";
        return "这类随身 WiFi 不方便直接操作屏幕：用 " + NAME + " 投屏授权——打开它 →「ADB 工具」→「ADB 投屏（查看并操作设备屏幕）」，"
                + "在投屏画面里的超级用户请求上点“允许”给 Shell 授权（错过了就在投屏里打开 Magisk → 超级用户，打开 Shell 的开关）。"
                + "本应用在后台一直自动重试，授权后自动往下走。" + (usb ? "\n" + USB_NOTE : "");
    }

    /** Magisk missing (TargetProbe MAGISK): restore the backed-up Magisk app, then grant. */
    static String magisk(boolean mu300, boolean usb) {
        if (!mu300)
            return "可以直接在目标设备上装回 Magisk（面具），或用 " + NAME + " →「ADB 工具」→「恢复安装包」装回之前备份的面具和个人软件；"
                    + "然后在设备屏幕上打开面具完成设置，并在超级用户请求上允许 Shell。";
        return "用 " + NAME + " →「ADB 工具」→「恢复安装包」装回之前备份的 Magisk（面具），"
                + "再用「ADB 投屏」在目标设备上打开面具完成设置，并在超级用户请求上允许 Shell。" + (usb ? "\n" + USB_NOTE : "");
    }

    /** Check / plan page of a path that formats userdata: back up installer packages first. */
    static String backupBeforeFormat(boolean uninstall) {
        return "格式化会清空目标设备上的全部 App 和数据。开始前请先用 " + NAME + " 备份安装包：打开它 →「ADB 工具」→「备份安装包」，\n"
                + "1. 一定要勾选 Magisk（面具）：格式化后" + (uninstall ? "完成卸载收尾" : "继续安装") + "时要先装回面具，才能给 Shell 授予 root；\n"
                + "2. 再勾选你自己要保留的软件。\n"
                + "它只备份安装包（.apk / .apks），不含 App 里的数据、账号和登录状态；这些以及照片、文件请自行另外备份。\n" + USB_NOTE;
    }

    /** Last step of the no-ADB list. */
    private static String finish(boolean uninstall) {
        return uninstall ? "设备就绪后在「安装方式」页选“卸载并归还空间”完成收尾。" : "设备就绪后在「安装方式」页选“继续格式化后的安装”继续。";
    }

    /**
     * After the formatting first stage (or a reset uninstall) when no ADB is visible: ADB is off.
     * MU300: turn it on from UFI-TOOLS; E5 family: in the developer options on the device screen.
     */
    static String noAdbAfterFormat(boolean mu300, boolean uninstall) {
        if (mu300)
            return "检测不到目标设备的 ADB：格式化后 ADB 已关闭。按下面的顺序做：\n"
                    + "1. 本机连上目标设备的热点，用 " + NAME + " 给目标设备安装 UFI-TOOLS（「开始一键安装UFI-TOOLS」）；\n"
                    + "2. 在 UFI-TOOLS 里开启 ADB（浏览器打开 " + UFI_TOOLS_URL + "）；\n"
                    + "3. 连上以后，用 " + NAME + " →「ADB 工具」→「恢复安装包」恢复之前备份的 Magisk（面具）和软件；\n"
                    + "4. 回到本应用连接目标设备（局域网 192.168.0.1:5555 或 USB），本应用请求 root 时，用 " + NAME
                    + " 的「ADB 投屏」在目标设备上允许 Shell 的超级用户请求；\n"
                    + "5. " + finish(uninstall);
        return "检测不到目标设备的 ADB：格式化后 ADB 已关闭。目标设备有屏幕，直接在它上面操作：\n"
                + "1. 完成 Android 首次设置，进入桌面；\n"
                + "2. 在“设置 → 关于手机”里连点版本号 7 次打开开发者选项，再在开发者选项里打开 USB 调试（用局域网连接的还要重新开启 5555 网络 ADB）；\n"
                + "3. 连上以后恢复之前备份的软件：Magisk（面具）和个人软件（可以用 " + NAME + " →「ADB 工具」→「恢复安装包」）；\n"
                + "4. 回到本应用连接目标设备，在设备屏幕上允许调试，并在超级用户请求里允许 Shell；\n"
                + "5. " + finish(uninstall);
    }
}
