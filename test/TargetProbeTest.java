package top.linote.owrtinstaller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Readiness ladder (adapter.probe / transport.root_status) and connection rebind, against a fake shell. */
public class TargetProbeTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    /** Fake target shell: answers by command; "TIMEOUT" throws a plain IOException, "FATAL" a TargetProbe.Fatal. */
    static final class Fake implements TargetProbe.Sh {
        final Map<String, String> ans = new HashMap<>();
        final List<String> log = new ArrayList<>();
        final List<Integer> timeouts = new ArrayList<>();
        Fake boot(String v) { ans.put("getprop sys.boot_completed", v); return this; }
        Fake setup(String v) { ans.put("settings get secure user_setup_complete 2>/dev/null", v); return this; }
        Fake uid(String v) { ans.put("id -u", v); return this; }
        Fake su(String name, boolean exists, String idOut) {
            ans.put("command -v " + name + " >/dev/null 2>&1; echo $?", exists ? "0" : "1");
            if (idOut != null) ans.put(name + " -c id", idOut);
            return this;
        }
        @Override public String run(String cmd, int ms) throws IOException {
            log.add(cmd);
            timeouts.add(ms);
            String a = ans.get(cmd);
            if (a == null) {
                if (cmd.startsWith("command -v ")) return "1\n";
                throw new IOException("unexpected command: " + cmd);
            }
            if (a.equals("TIMEOUT")) throw new IOException("no output for " + ms + " ms");
            if (a.equals("FATAL")) throw new TargetProbe.Fatal("link gone");
            return a + "\n";
        }
    }
    static Fake booted() { return new Fake().boot("1").setup("1").uid("2000"); }

    public static void main(String[] a) throws Exception {
        // ---- ladder order
        Fake f = new Fake().boot("0");
        check("boot_completed!=1 -> ANDROID_BOOT", TargetProbe.evaluate(f).state == TargetProbe.State.ANDROID_BOOT);
        check("stops after boot check", f.log.size() == 1);
        f = new Fake().boot("").setup("1").uid("0");
        check("empty boot_completed -> ANDROID_BOOT", TargetProbe.evaluate(f).state == TargetProbe.State.ANDROID_BOOT);
        f = new Fake().boot("1").setup("0").uid("0");
        check("user_setup_complete 0 -> ANDROID_SETUP (before root)", TargetProbe.evaluate(f).state == TargetProbe.State.ANDROID_SETUP
                && !f.log.contains("id -u"));
        f = new Fake().boot("1").setup("null").uid("0");
        TargetProbe.Result r = TargetProbe.evaluate(f);
        check("setup 'null' is not 0 -> continues; id -u 0 -> READY su \"\"", r.state == TargetProbe.State.READY && "".equals(r.su));
        check("adbd root: no su probing", f.log.stream().noneMatch(c -> c.startsWith("command -v")));

        // ---- root_status: su candidates in order
        f = booted().su("su", true, "uid=0(root) gid=0(root) context=u:r:magisk:s0");
        r = TargetProbe.evaluate(f);
        check("su grants -> READY su", r.state == TargetProbe.State.READY && "su".equals(r.su));
        check("su -c id uses 8 s timeout", f.timeouts.get(f.log.indexOf("su -c id")) == TargetProbe.SU_TIMEOUT_MS);
        f = booted().su("su", false, null).su("/debug_ramdisk/su", true, "uid=0(root) gid=0(root)");
        r = TargetProbe.evaluate(f);
        check("/debug_ramdisk/su -> READY with that path", r.state == TargetProbe.State.READY && "/debug_ramdisk/su".equals(r.su));
        f = booted().su("su", false, null).su("/debug_ramdisk/su", false, null).su("/sbin/su", true, "uid=0(root)");
        r = TargetProbe.evaluate(f);
        check("/sbin/su -> READY with that path", r.state == TargetProbe.State.READY && "/sbin/su".equals(r.su));
        f = booted().su("su", true, "Permission denied").su("/debug_ramdisk/su", false, null).su("/sbin/su", false, null);
        r = TargetProbe.evaluate(f);
        check("su denied -> ROOT_PERMISSION", r.state == TargetProbe.State.ROOT_PERMISSION && "su".equals(r.foundSu));
        check("all three candidates tried", f.log.contains("command -v /sbin/su >/dev/null 2>&1; echo $?"));
        f = booted().su("su", true, "TIMEOUT");
        r = TargetProbe.evaluate(f);
        check("su -c id unanswered (timeout) -> ROOT_PERMISSION, not an error", r.state == TargetProbe.State.ROOT_PERMISSION);
        f = booted().su("su", true, "uid=2000(shell) gid=2000(shell)");
        check("uid=2000 is not root", TargetProbe.evaluate(f).state == TargetProbe.State.ROOT_PERMISSION);
        f = booted().su("su", true, "uid=01(x)");
        check("\\buid=0\\b does not match uid=01", TargetProbe.evaluate(f).state == TargetProbe.State.ROOT_PERMISSION);
        f = booted().su("su", true, "Permission denied").su("/sbin/su", true, "uid=0(root)");
        r = TargetProbe.evaluate(f);
        check("first denied, later candidate grants -> READY /sbin/su", r.state == TargetProbe.State.READY && "/sbin/su".equals(r.su));
        f = booted();
        r = TargetProbe.evaluate(f);
        check("no su anywhere -> MAGISK", r.state == TargetProbe.State.MAGISK);
        f = booted().su("su", true, "FATAL");
        boolean fatal = false;
        try { TargetProbe.evaluate(f); } catch (TargetProbe.Fatal e) { fatal = true; }
        check("lost link during su -c id propagates (reconnect)", fatal);

        // ---- hints: one per state, Chinese, with the needed action
        check("hint MAGISK names Magisk", TargetProbe.hint(new TargetProbe.Result(TargetProbe.State.MAGISK, null, null), true, true).contains("Magisk"));
        check("hint ROOT_PERMISSION names su path and Shell",
                TargetProbe.hint(new TargetProbe.Result(TargetProbe.State.ROOT_PERMISSION, null, "/sbin/su"), true, true).contains("/sbin/su")
                && TargetProbe.hint(new TargetProbe.Result(TargetProbe.State.ROOT_PERMISSION, null, "su"), true, true).contains("Shell"));
        check("hint DEBUG_AUTHORIZATION says 允许", TargetProbe.hint(new TargetProbe.Result(TargetProbe.State.DEBUG_AUTHORIZATION, null, null), true, false).contains("允许"));
        check("hint ANDROID_SETUP says 开机向导", TargetProbe.hint(new TargetProbe.Result(TargetProbe.State.ANDROID_SETUP, null, null), true, true).contains("开机向导"));
        check("hint USB_DEBUGGING says USB 调试", TargetProbe.hint(new TargetProbe.Result(TargetProbe.State.USB_DEBUGGING, null, null), true, false).contains("USB 调试"));
        check("hint ANDROID_BOOT differs for LAN (5555)", TargetProbe.hint(new TargetProbe.Result(TargetProbe.State.ANDROID_BOOT, null, null), false, false).contains("5555"));
        java.util.Set<String> titles = new java.util.HashSet<>();
        for (TargetProbe.State s : TargetProbe.State.values()) titles.add(TargetProbe.title(s));
        check("every state has its own title", titles.size() == TargetProbe.State.values().length);
        for (TargetProbe.State s : TargetProbe.State.values())
            if (s != TargetProbe.State.READY)
                check("stage " + s + " is a desktop wait stage", Rebind.waitStage(TargetProbe.stage(s)));

        // ---- checklist marks
        String cl = TargetProbe.checklist(TargetProbe.State.MAGISK, false);
        check("checklist MAGISK: setup/ADB/auth done, Magisk now, Shell todo",
                cl.contains("✓ 完成 Android") && cl.contains("✓ 重新打开 ADB") && cl.contains("✓ 在目标设备上允许")
                && cl.contains("→ 重新安装 Magisk") && cl.contains("· 在超级用户请求里允许"));
        cl = TargetProbe.checklist(TargetProbe.State.ANDROID_SETUP, true);
        check("checklist ANDROID_SETUP: wizard now, ADB done; LAN mentions 5555",
                cl.contains("→ 完成 Android") && cl.contains("✓ 重新打开 ADB") && cl.contains("5555"));
        String prep = Mu300Internal.resumePrep();
        check("resumePrep merged with the ladder checklist (numbered, ADB/Magisk/Shell/5555, entry)",
                prep.contains("1. 完成 Android 首次设置") && prep.contains("2. 重新打开 ADB") && prep.contains("重新安装 Magisk")
                && prep.contains("Shell") && prep.contains("5555") && prep.contains("继续格式化后的安装") && !prep.contains("· "));

        // ---- Rebind (service.rebind_uninstall / adb_connect)
        Map<String, Object> rec = new LinkedHashMap<>();
        check("empty transport changes nothing", !Rebind.apply(rec, "", true, null, 1) && !rec.containsKey("transport"));
        check("first binding stores transport", Rebind.apply(rec, "usb:SER1", true, null, 1) && "usb:SER1".equals(rec.get("transport"))
                && !rec.containsKey("transport_history"));
        check("same transport: no change", !Rebind.apply(rec, "usb:SER1", true, null, 2) && !rec.containsKey("transport_history"));
        boolean nr = false;
        try { Rebind.apply(rec, "tcp:192.168.0.1:5555", false, null, 3); } catch (IOException e) { nr = Rebind.NOT_READY.equals(e.getMessage()); }
        check("new transport while not ready -> NOT_READY, record unchanged", nr && "usb:SER1".equals(rec.get("transport")));
        boolean mm = false;
        try { Rebind.apply(rec, "tcp:192.168.0.1:5555", true, "eMMC CID 记录 a / 现在 b", 4); }
        catch (Uninstall.Refused e) { mm = e.getMessage().startsWith(Rebind.MISMATCH); }
        check("new transport, other device -> Refused(MISMATCH), record unchanged", mm && "usb:SER1".equals(rec.get("transport")) && !rec.containsKey("transport_history"));
        check("new transport, same device -> rebound", Rebind.apply(rec, "tcp:192.168.0.1:5555", true, null, 5) && "tcp:192.168.0.1:5555".equals(rec.get("transport")));
        Object h = rec.get("transport_history");
        check("history entry {transport: old, changed_at}", h instanceof List && ((List<?>) h).size() == 1
                && "usb:SER1".equals(((Map<?, ?>) ((List<?>) h).get(0)).get("transport")) && Long.valueOf(5).equals(((Map<?, ?>) ((List<?>) h).get(0)).get("changed_at")));
        check("JSON round trip keeps history", Uninstall.Json.write(rec, false).contains("transport_history"));
        Map<String, Object> old = new LinkedHashMap<>();
        check("pre-1.6.0 record with mismatch: not bound (caller reports)", !Rebind.apply(old, "usb:X", true, "x", 1) && !old.containsKey("transport"));
        check("switching()", Rebind.switching(rec, "usb:SER1") && !Rebind.switching(rec, "tcp:192.168.0.1:5555") && !Rebind.switching(old, "usb:X"));
        check("diff() null when equal", Rebind.diff("a", "1", "1", "b", "x", "x") == null);
        String d = Rebind.diff("eMMC CID", "aa", "bb", "Android 槽位", "_a", "_a");
        check("diff() names only differing fields", d != null && d.contains("eMMC CID") && !d.contains("槽位"));

        System.out.println("TargetProbeTest: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
