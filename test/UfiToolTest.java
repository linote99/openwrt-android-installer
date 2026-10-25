package top.linote.owrtinstaller;

/** Desktop checks for the Lino-UFI 工具 hint texts (UfiTool): per-family wording and link targets. */
public class UfiToolTest {
    static int pass, fail;
    static void t(String name, boolean ok) {
        if (ok) pass++; else { fail++; System.out.println("FAIL " + name); }
    }

    public static void main(String[] a) {
        t("package name", UfiTool.PKG.equals("top.linote.ufiinstaller"));
        t("blog article", UfiTool.BLOG.equals("https://www.linote.top/posts/lino-ufi-tools-android/"));
        t("button installed", UfiTool.buttonLabel(true).equals("打开 Lino-UFI 工具"));
        t("button missing says 点此安装", UfiTool.buttonLabel(false).contains("点此安装"));

        // MU300: UFI-TOOLS turns ADB on, root through mirroring, in this order.
        String m = UfiTool.noAdbAfterFormat(true, false);
        int i1 = m.indexOf("安装 UFI-TOOLS"), i2 = m.indexOf("在 UFI-TOOLS 里开启 ADB"), i3 = m.indexOf("恢复安装包"),
            i4 = m.indexOf("ADB 投屏"), i5 = m.indexOf("继续格式化后的安装");
        t("mu300 order: UFI-TOOLS → ADB → restore → mirror grant → continue", i1 > 0 && i1 < i2 && i2 < i3 && i3 < i4 && i4 < i5);
        t("mu300 mentions Magisk restore", m.contains("Magisk（面具）"));
        t("mu300 UFI-TOOLS address", m.contains("http://192.168.0.1:2333"));
        t("mu300 no developer-options path", !m.contains("开发者选项"));
        t("mu300 uninstall variant ends with 卸载并归还空间", UfiTool.noAdbAfterFormat(true, true).contains("“卸载并归还空间”完成收尾"));

        // E5 family: phones with a screen: developer options, restore, grant on screen; no UFI-TOOLS.
        String e = UfiTool.noAdbAfterFormat(false, false);
        t("e5 developer options + USB debugging", e.contains("开发者选项") && e.contains("USB 调试"));
        t("e5 restores Magisk and own apps", e.contains("Magisk（面具）和个人软件"));
        t("e5 grants on the device screen", e.contains("在设备屏幕上"));
        t("e5 does not require UFI-TOOLS", !e.contains("UFI-TOOLS"));
        t("e5 does not push mirroring", !e.contains("投屏"));
        t("e5 ends with continue", e.contains("继续格式化后的安装"));

        // Root grant: MU300 mirroring first, E5 on the screen with mirroring optional.
        String rm = UfiTool.rootGrant(true, false), re = UfiTool.rootGrant(false, false);
        t("mu300 root grant via mirroring", rm.contains("ADB 投屏") && rm.contains("允许"));
        t("e5 root grant on screen", re.startsWith("请直接在目标设备屏幕上") && re.contains("（可选）"));
        t("usb note only when on USB (mu300)", UfiTool.rootGrant(true, true).contains(UfiTool.USB_NOTE) && !rm.contains(UfiTool.USB_NOTE));
        t("magisk hint mu300 restore + mirror", UfiTool.magisk(true, false).contains("恢复安装包") && UfiTool.magisk(true, false).contains("ADB 投屏"));
        t("magisk hint e5 on screen", UfiTool.magisk(false, false).contains("在设备屏幕上") && !UfiTool.magisk(false, false).contains("投屏"));

        // Before formatting: back up Magisk + own apps; packages only.
        String b = UfiTool.backupBeforeFormat(false);
        t("backup: Magisk required", b.contains("一定要勾选 Magisk（面具）"));
        t("backup: own apps", b.contains("自己要保留的软件"));
        t("backup: packages only, data not included", b.contains("只备份安装包") && b.contains("不含 App 里的数据"));
        t("backup: uninstall variant", UfiTool.backupBeforeFormat(true).contains("完成卸载收尾"));

        // Never a hand-written way to switch ADB on (ZTE web backend, setprop, etc.).
        for (String s : new String[]{m, e, rm, re, b})
            t("no own ADB switch", !s.contains("goform") && !s.contains("setprop") && !s.contains("8080"));

        System.out.println("UfiToolTest: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
