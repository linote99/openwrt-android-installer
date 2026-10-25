package top.linote.owrtinstaller;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Target readiness ladder, same order as the desktop installer (adapter.probe + transport.root_status):
 *   not visible over ADB          -> ANDROID_BOOT       (waiting_device: booting / rebooting)
 *   visible but ADB has no ADB    -> USB_DEBUGGING      (USB only: a USB device without an ADB interface)
 *   ADB key not accepted          -> DEBUG_AUTHORIZATION
 *   sys.boot_completed != 1       -> ANDROID_BOOT       (still booting; keep waiting)
 *   user_setup_complete == 0      -> ANDROID_SETUP
 *   root_status: id -u == 0 -> READY (su ""); else for su, /debug_ramdisk/su, /sbin/su:
 *     "command -v NAME >/dev/null 2>&1; echo $?" == 0 -> found; "NAME -c id" (8 s) matches \buid=0\b -> READY(NAME)
 *     none ready: any found -> ROOT_PERMISSION, none found -> MAGISK.
 * Every state except READY is a "keep waiting" state; nothing here is an error.
 */
final class TargetProbe {
    enum State { READY, ANDROID_BOOT, USB_DEBUGGING, DEBUG_AUTHORIZATION, ANDROID_SETUP, MAGISK, ROOT_PERMISSION }

    /** Shell on the connected target (as the adb shell user). timeoutMs = longest silence, 0 = none. */
    interface Sh { String run(String cmd, int timeoutMs) throws IOException; }

    static final String[] SU = {"su", "/debug_ramdisk/su", "/sbin/su"};
    static final int SU_TIMEOUT_MS = 8000;
    private static final Pattern UID0 = Pattern.compile("\\buid=0\\b");

    static final class Result {
        final State state;
        final String su;        // READY: "" (adbd root) or the su path
        final String foundSu;   // ROOT_PERMISSION: the first su that exists
        Result(State s, String su, String found) { state = s; this.su = su; foundSu = found; }
    }

    /** Runs the in-Android part of the ladder on an established ADB connection. */
    static Result evaluate(Sh sh) throws IOException {
        if (!"1".equals(sh.run("getprop sys.boot_completed", 15000).trim())) return new Result(State.ANDROID_BOOT, null, null);
        if ("0".equals(sh.run("settings get secure user_setup_complete 2>/dev/null", 15000).trim()))
            return new Result(State.ANDROID_SETUP, null, null);
        return rootStatus(sh);
    }

    static Result rootStatus(Sh sh) throws IOException {
        if ("0".equals(sh.run("id -u", 15000).trim())) return new Result(State.READY, "", null);
        String found = null;
        for (String name : SU) {
            String rc = sh.run("command -v " + name + " >/dev/null 2>&1; echo $?", 15000).trim();
            if (!"0".equals(rc)) continue;
            if (found == null) found = name;
            String out;
            try { out = sh.run(name + " -c id", SU_TIMEOUT_MS); }
            catch (IOException e) { if (e instanceof Fatal) throw e; continue; }   // timeout: request not answered yet
            if (UID0.matcher(out).find()) return new Result(State.READY, name, null);
        }
        return found != null ? new Result(State.ROOT_PERMISSION, null, found) : new Result(State.MAGISK, null, null);
    }

    /** Thrown by Sh implementations when the link itself is gone (stops the ladder, triggers a reconnect). */
    static final class Fatal extends IOException { Fatal(String m) { super(m); } }

    /** Desktop stage name, for logs and records. */
    static String stage(State s) {
        switch (s) {
            case ANDROID_BOOT: return "android_boot";
            case USB_DEBUGGING: return "usb_debugging";
            case DEBUG_AUTHORIZATION: return "debug_authorization";
            case ANDROID_SETUP: return "android_setup";
            case MAGISK: return "magisk";
            case ROOT_PERMISSION: return "root_permission";
            default: return "ready";
        }
    }

    /** Short title of a waiting state. */
    static String title(State s) {
        switch (s) {
            case ANDROID_BOOT: return "等待目标设备开机";
            case USB_DEBUGGING: return "等待打开 USB 调试";
            case DEBUG_AUTHORIZATION: return "等待允许调试授权";
            case ANDROID_SETUP: return "等待完成开机向导";
            case MAGISK: return "等待安装 Magisk（面具）";
            case ROOT_PERMISSION: return "等待给 Shell 授予 root";
            default: return "目标设备已就绪";
        }
    }

    /** What the user has to do on the target, in Chinese. */
    static String hint(Result r, boolean usb, boolean booted) {
        switch (r.state) {
            case ANDROID_BOOT:
                if (booted) return "目标设备已连上，但 Android 还没启动完成（sys.boot_completed 不是 1）。请等它进入桌面，本应用会自动继续。";
                return usb ? "还看不到目标设备的 ADB：目标设备可能正在开机或重启。开机后请确认 OTG 线已插好、USB 调试已打开。"
                        : "目标设备的网络 ADB 没有响应：可能正在开机或重启，或还没开启 5555 网络 ADB、手机不在它的热点 / 局域网里。"
                        + "地址不对请点“停止”后修改。";
            case USB_DEBUGGING:
                return "已检测到 USB 设备，但它没有提供 ADB：请在目标设备的“设置 → 开发者选项”里打开“USB 调试”"
                        + "（开发者选项需先在“关于手机”里连点版本号 7 次开启）。";
            case DEBUG_AUTHORIZATION:
                return "请在目标设备屏幕上的“允许 USB 调试吗？”对话框里点“允许”（建议勾选“始终允许使用这台计算机进行调试”）。"
                        + "没有看到对话框时，可在开发者选项里关闭再打开 USB 调试。";
            case ANDROID_SETUP:
                return "目标设备的开机向导还没走完：请在目标设备上完成 Android 首次设置（语言、网络、账户可跳过），进入桌面后会自动继续。";
            case MAGISK:
                return "目标设备上找不到 su（已检查 su、/debug_ramdisk/su、/sbin/su）：Magisk（面具）没有安装或没有生效。"
                        + "请在目标设备上重新安装 Magisk 并让它生效（修补启动镜像后重启），完成后会自动继续。";
            case ROOT_PERMISSION:
                return "目标设备已有 su（" + r.foundSu + "），但 Shell 还没有 root 授权：请在目标设备弹出的超级用户请求里允许“Shell”，"
                        + "或在 Magisk → 超级用户里打开 Shell 的开关。授权后会自动继续。";
            default:
                return "目标设备 root 可用（" + (r.su == null || r.su.isEmpty() ? "adbd 已是 root" : r.su) + "）。";
        }
    }

    /** Rank of a state on the user's checklist (higher = further along). */
    private static int rank(State s) {
        switch (s) {
            case ANDROID_BOOT: case USB_DEBUGGING: return 1;
            case DEBUG_AUTHORIZATION: return 2;
            case ANDROID_SETUP: return 3;
            case MAGISK: return 4;
            case ROOT_PERMISSION: return 5;
            default: return 6;
        }
    }

    /**
     * Checklist of the steps after a factory reset (the format-userdata continuation and a reset
     * uninstall), with the current state marked: "✓" done, "→" now, "·" still to do. state null = unknown.
     */
    static String checklist(State state, Boolean lan) {
        String[][] steps = {
                {"1", "完成 Android 首次设置（开机向导），进入桌面"},
                {"1", lan == null ? "重新打开 ADB：在开发者选项里打开 USB 调试（用局域网连接的还要重新开启 5555 网络 ADB）"
                        : lan ? "重新打开 ADB：打开 USB 调试，并重新开启 5555 网络 ADB" : "重新打开 ADB：在开发者选项里打开 USB 调试"},
                {"2", "在目标设备上允许本应用的调试授权"},
                {"4", "重新安装 Magisk（面具）并让它生效"},
                {"5", "在超级用户请求里允许“Shell”（或在 Magisk → 超级用户里打开 Shell）"},
        };
        int now = state == null ? 0 : rank(state);
        StringBuilder b = new StringBuilder();
        for (String[] st : steps) {
            int r = Integer.parseInt(st[0]);
            String mark = state == null ? "·" : r < now ? "✓" : r == now ? "→" : "·";
            if (state == State.ANDROID_SETUP && st[1].startsWith("完成 Android")) mark = "→";
            else if (state == State.ANDROID_SETUP && r <= 2) mark = "✓";
            if (b.length() > 0) b.append('\n');
            b.append(mark).append(' ').append(st[1]);
        }
        return b.toString();
    }
}
