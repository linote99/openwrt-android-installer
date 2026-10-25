package top.linote.owrtinstaller;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Desktop test of the E5-series "format userdata" two-stage install (E5Part, ported from part-two-stage.py and
 * boot-slots.py): pure boot-image/BCB helpers, stage one, resume after the reset, and rewriting an existing e5root.
 * The device is simulated from command arguments; no package script or binary is ever executed.
 */
public class E5PartTest {
    static int fails, passes;

    static void ok(boolean c, String what) {
        if (c) passes++; else fails++;
        System.out.println((c ? "ok   " : "FAIL ") + what);
    }

    static String thrown(Runnable r) {
        try { r.run(); return null; } catch (RuntimeException e) { return e.getMessage(); }
    }

    interface IoCall { void run() throws IOException; }

    static String ioThrown(IoCall r) {
        try { r.run(); return null; } catch (IOException e) { return e.getMessage(); }
    }

    static boolean contains(byte[] hay, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + n.length <= hay.length; i++) {
            for (int j = 0; j < n.length; j++) if (hay[i + j] != n[j]) continue outer;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ synthetic inputs
    static final int KERNEL = 8192, RAMDISK = 4096, VBMETA = 2048, IMAGE = 1 << 20;

    /** Android boot header v4 with kernel, ramdisk, vbmeta and an AVB footer. */
    static byte[] bootImage(String cmdline) {
        byte[] b = new byte[IMAGE];
        System.arraycopy("ANDROID!".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 8);
        Mu300Boot.put32le(b, 8, KERNEL);
        Mu300Boot.put32le(b, 12, RAMDISK);
        Mu300Boot.put32le(b, 40, 4);
        byte[] c = cmdline.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(c, 0, b, 44, c.length);
        Arrays.fill(b, 4096, 4096 + KERNEL, (byte) 0x4b);
        Arrays.fill(b, 4096 + KERNEL, 4096 + KERNEL + RAMDISK, (byte) 0x52);
        int vb = 4096 + KERNEL + RAMDISK;
        for (int i = 0; i < VBMETA; i++) b[vb + i] = (byte) (i * 7);
        int f = IMAGE - 64;
        System.arraycopy("AVBf".getBytes(StandardCharsets.US_ASCII), 0, b, f, 4);
        E5Part.putBe64(b, f + 12, vb);
        E5Part.putBe64(b, f + 20, vb);
        E5Part.putBe64(b, f + 28, VBMETA);
        return b;
    }

    /** A valid BCAB block: Android on slot a (successful, priority 15), slot b priority 14. */
    static byte[] control() {
        byte[] d = new byte[32];
        d[0] = '_'; d[1] = 'a';
        d[4] = 'B'; d[5] = 'C'; d[6] = 'A'; d[7] = 'B'; d[8] = 1; d[9] = 2;
        d[12] = (byte) 0x8f; d[14] = (byte) 0x8e;
        Mu300Boot.put32le(d, 28, Mu300Boot.crc32(d, 0, 28));
        return d;
    }

    // ------------------------------------------------------------------ simulated target
    static final class Dev implements Mu300Internal.Io {
        String slot = "_a", serial = "SER5", model = "E5";
        final String cid = "0123456789abcdef0123456789abcdef";
        final Map<String, long[]> rows = new LinkedHashMap<>();   // name -> {partno, start, size}
        final Map<String, byte[]> byName = new HashMap<>();
        final Map<String, byte[]> files = new HashMap<>();
        final List<String> tools = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        final List<String> writes = new ArrayList<>();
        byte[] misc = new byte[4096];
        byte[] rootHead = new byte[128];
        String installedConf = "__E5_NO_OLD_SYSTEM__";
        String failTool;
        boolean rebooted;
        final long diskSectors = 16777216;
        final File cache;

        Dev(File cache) { this.cache = cache; System.arraycopy(control(), 0, misc, 2048, 32); }

        static String hexdump(byte[] b) {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < b.length; i++) s.append(String.format(" %02x", b[i] & 0xff)).append(i % 16 == 15 ? "\n" : "");
            return s.toString();
        }

        byte[] eval(String cmd) {
            Matcher m = Pattern.compile("^cat '?/dev/block/by-name/(\\w+)'?$").matcher(cmd);
            if (m.matches()) return byName.get(m.group(1)).clone();
            m = Pattern.compile("^dd if=/dev/block/mmcblk0 bs=512 skip=(\\d+) count=(\\d+) 2>/dev/null$").matcher(cmd);
            if (m.matches()) return new byte[Integer.parseInt(m.group(2)) * 512];
            if (cmd.equals("mkdir -p /data/adb/e5-bootchain; tar -cf - -C /data/adb e5-bootchain")) return "BOOTCHAIN-TAR".getBytes(StandardCharsets.US_ASCII);
            m = Pattern.compile("^head -c 128 /dev/block/(mmcblk0p\\d+)$").matcher(cmd);
            if (m.matches()) return rootHead.clone();
            throw new AssertionError("unexpected binary read: " + cmd);
        }

        @Override public String sh(String script, boolean root) throws IOException {
            switch (script) {
                case "getprop ro.boot.slot_suffix": return slot + "\n";
                case "getprop ro.serialno": return serial + "\n";
                case "getprop ro.product.model": return model + "\n";
                case "getprop sys.boot_completed": return "1\n";
                case "dd if=/dev/block/by-name/misc bs=1 skip=2048 count=32 2>/dev/null | od -An -tx1 -v": return hexdump(Arrays.copyOfRange(misc, 2048, 2080));
                case "dd if=/dev/block/by-name/misc bs=1 count=2048 2>/dev/null | od -An -tx1 -v": return hexdump(Arrays.copyOfRange(misc, 0, 2048));
                case "cat /sys/class/block/mmcblk0/size": return diskSectors + "\n";
                case "df -Pk /data/local/tmp | tail -n1 | awk '{print $4}'": return "9000000\n";
                default: break;
            }
            if (script.startsWith("for u in /sys/class/block/mmcblk*p*/uevent; do ")) {
                StringBuilder b = new StringBuilder();
                for (Map.Entry<String, long[]> e : rows.entrySet())
                    b.append("mmcblk0p").append(e.getValue()[0]).append(' ').append(cid).append(' ').append(e.getValue()[1]).append(' ')
                            .append(e.getValue()[2]).append(" PARTNAME=").append(e.getKey()).append('\n');
                return b.toString();
            }
            if (script.matches("umask 077; mkdir '?/data/local/tmp/openwrt-read-[0-9a-f]+'?") || script.startsWith("rm -f /data/local/tmp/openwrt-read-")) return "";
            if (script.startsWith("set -e; set -o pipefail; (")) {
                int a = "set -e; set -o pipefail; (".length(), z = script.indexOf(") > ", a);
                String remote = script.substring(z + 4).split("[;& ]")[0];
                byte[] data = eval(script.substring(a, z));
                files.put(remote, data);
                String h = E5Part.sha(data);
                return data.length + "\n" + h + "  " + remote + "\n" + h + "  -\n";
            }
            Matcher m = Pattern.compile("^blockdev --getsize64 '/dev/block/by-name/(\\w+)' 2>/dev/null \\|\\| stat -c %s '/dev/block/by-name/\\w+'$").matcher(script);
            if (m.matches()) return byName.get(m.group(1)).length + "\n";
            m = Pattern.compile("^sh /data/adb/owrt-apk/owrt-apk.sh e5-tool (.*)$").matcher(script);
            if (m.matches()) {
                tools.add(m.group(1));
                if (failTool != null && m.group(1).startsWith(failTool)) return "ERROR: simulated " + failTool + "\nOWRT-APK-TOOL-RC=4\n";
                return "[TOOL] " + m.group(1) + "\nOWRT-APK-TOOL-OK\nOWRT-APK-TOOL-RC=0\n";
            }
            m = Pattern.compile("^set -e; dd if=/data/local/tmp/e5-two-stage-boot.img of=/dev/block/by-name/(boot_[ab]) bs=1048576 conv=fsync; "
                    + "\\[ \"\\$\\(sha256sum /dev/block/by-name/boot_[ab] \\| cut -d \" \" -f1\\)\" = ([0-9a-f]{64}) \\]; rm -f /data/local/tmp/e5-two-stage-boot.img$").matcher(script);
            if (m.matches()) {
                byte[] img = files.remove("/data/local/tmp/e5-two-stage-boot.img");
                if (img == null || !E5Part.sha(img).equals(m.group(2))) throw new IOException("flash: missing upload or checksum mismatch");
                if (img.length > byName.get(m.group(1)).length) throw new IOException("flash: image larger than partition");
                byName.put(m.group(1), img);
                writes.add(m.group(1));
                return "";
            }
            if (script.startsWith("set -e; bcb_hash() {")) {
                Arrays.fill(misc, 0, 32, (byte) 0);
                writes.add("misc-bcb");
                return "";
            }
            if (script.startsWith("rm -rf /data/local/tmp/e5-reinstall-mnt; ")) return installedConf + "\n";
            throw new AssertionError("unexpected command: " + script);
        }

        @Override public void pull(String remote, File dest) throws IOException {
            byte[] d = files.remove(remote);
            if (d == null) throw new IOException("pull: no " + remote);
            Mu300Internal.writeAll(dest, d);
        }

        @Override public void push(File local, String remote) throws IOException { files.put(remote, Mu300Internal.readAll(local)); }
        @Override public void reboot() { rebooted = true; }
        @Override public void log(String line) { logs.add(line); }
        @Override public void progress(int percent) { }
        @Override public File cacheDir() { return cache; }
    }

    static Dev device(File cache) {
        Dev d = new Dev(cache);
        d.rows.put("userdata", new long[]{50, 1048576, 8388608});          // 4 GiB userdata, 1 MiB aligned
        d.byName.put("boot_a", bootImage("console=ttyS1 e5.part_v=2"));
        d.byName.put("boot_b", new byte[IMAGE]);
        return d;
    }

    static E5Part.Pkg pkg(byte[] boot) {
        E5Part.Pkg p = new E5Part.Pkg();
        p.boot = boot;
        p.bootArgs = "#!/bin/sh\n# boot request reader\n".getBytes(StandardCharsets.US_ASCII);
        p.bootSha = E5Part.sha(boot);
        p.imageSha = "ab".repeat(32);
        p.profile = "e5";
        p.hotspotDefault = E5Part.defaultHotspotName("e5");
        p.slotRoles = 1;
        p.rawSize = 1L << 30;
        p.hasTools = true;
        p.hasPartWrite = true;
        return p;
    }

    public static void main(String[] args) throws Exception {
        File work = new File(args.length > 0 ? args[0] : "build-test/e5part");
        Mu300Internal.deleteTree(work);
        File cache = new File(work, "cache"), base = new File(work, "app");
        if (!cache.mkdirs() || !base.mkdirs()) throw new IOException("mkdir " + work);

        // ---------------------------------------------------------- boot-slots.py / part-two-stage.py helpers
        byte[] bc = control();
        ok("a".equals(E5Part.validateControl(bc, null)), "validate_control: Android slot a recognised");
        byte[] bad = bc.clone(); bad[20] ^= 1;
        ok(thrown(() -> E5Part.validateControl(bad, "a")) != null, "validate_control: CRC error refused");
        byte[] ota = bc.clone(); ota[14] = (byte) 0x8f; ota[12] = (byte) 0x8e; Mu300Boot.put32le(ota, 28, Mu300Boot.crc32(ota, 0, 28));
        ok(String.valueOf(thrown(() -> E5Part.validateControl(ota, "a"))).contains("higher priority"), "validate_control: pending OTA (other slot higher) refused");
        LinkedHashMap<String, byte[]> early = E5Part.earlyFiles(bc, "a");
        byte[] trial = early.get("etc/misc-bc-slot-b-trial.bin"), fallback = early.get("etc/misc-bc-slot-a.bin");
        ok("b\n".equals(new String(early.get("etc/e5/linux-slot"), StandardCharsets.US_ASCII)) && trial[1] == 'b'
                && Mu300Boot.crc32(trial, 0, 28) == E5Part.u32(trial, 28) && (trial[14] & 0xff) == 0x2f && (trial[12] & 15) == 14,
                "role files: Linux slot b, trial block selects b once (CRC valid)");
        ok("a".equals(E5Part.validateControl(fallback, "a")), "role files: fallback block still boots Android slot a");

        byte[] boot = bootImage("console=ttyS1 e5.part_v=2");
        byte[] cmd = E5Part.bootCommand(boot, "e5.root=part", "#!/bin/sh\n".getBytes(StandardCharsets.US_ASCII));
        int end = 44; while (cmd[end] != 0) end++;
        int f = IMAGE - 64;
        long vb = E5Part.be64(cmd, f + 20), newRd = E5Part.u32(cmd, 12) & 0xffffffffL;
        ok(new String(cmd, 44, end - 44, StandardCharsets.US_ASCII).equals("console=ttyS1 e5.part_v=2 e5.root=part"), "boot_command: option appended to the command line");
        ok(cmd.length == boot.length && newRd > RAMDISK && vb % 4096 == 0 && vb >= 4096 + KERNEL + newRd
                && Arrays.equals(Arrays.copyOfRange(cmd, (int) vb, (int) vb + VBMETA), Arrays.copyOfRange(boot, 4096 + KERNEL + RAMDISK, 4096 + KERNEL + RAMDISK + VBMETA))
                && E5Part.be64(cmd, f + 12) == vb && E5Part.be64(cmd, f + 28) == VBMETA,
                "boot_command: ramdisk grown, vbmeta moved and AVB footer rewritten");
        int rdEnd = 4096 + KERNEL + RAMDISK;
        ok((cmd[rdEnd] & 0xff) == 0x02 && (cmd[rdEnd + 1] & 0xff) == 0x21 && contains(cmd, "etc/e5/boot-request") && contains(cmd, "e5.root=part\n")
                && contains(cmd, "etc/boot-args.sh"), "boot_command: appended LZ4-legacy cpio carries etc/e5/boot-request and boot-args.sh");
        ok(Arrays.equals(Arrays.copyOfRange(cmd, 4096, rdEnd), Arrays.copyOfRange(boot, 4096, rdEnd)), "boot_command: kernel and original ramdisk untouched");
        ok(String.valueOf(thrown(() -> E5Part.bootCommand(bootImage("console=ttyS1"), "e5.root=part", new byte[]{1}))).contains("two-stage installer v2"),
                "boot_command: package without e5.part_v=2 refused");
        ok(String.valueOf(thrown(() -> E5Part.bootCommand(bootImage("e5.part_v=2 e5.root=part"), "e5.root=part", new byte[]{1}))).contains("override"),
                "boot_command: an existing override refused");
        ok(thrown(() -> E5Part.bootCommand(boot, "e5.root=part; reboot", new byte[]{1})) != null, "boot_command: malformed request refused");
        ok(String.valueOf(thrown(() -> E5Part.bootCommand(boot, "e5.root=part", null))).contains("boot request reader"), "boot_command: missing boot-args.sh refused");

        ok("empty".equals(E5Part.recoveryMessageKind(new byte[2048])), "recovery message: empty");
        byte[] once = new byte[2048];
        System.arraycopy("bootonce-bootloader".getBytes(StandardCharsets.US_ASCII), 0, once, 0, 19);
        ok("bootloader-only".equals(E5Part.recoveryMessageKind(once)), "recovery message: bootonce-bootloader only");
        byte[] rec = new byte[2048];
        System.arraycopy("boot-recovery".getBytes(StandardCharsets.US_ASCII), 0, rec, 0, 13);
        ok(thrown(() -> E5Part.recoveryMessageKind(rec)) != null, "recovery message: pending recovery/update request refused");
        String clr = E5Part.clearBootloaderCommandScript(once, bc);
        ok(clr.contains(E5Part.sha(once)) && clr.contains(E5Part.sha(new byte[2048])) && clr.contains(E5Part.sha(bc)) && clr.contains("bs=32 count=1 conv=notrunc,fsync"),
                "clear bootloader request: guarded by before/after hashes, only 32 bytes zeroed");

        // ---------------------------------------------------------- stage one
        Dev d = device(cache);
        E5Part.Pkg pk = pkg(d.byName.get("boot_a"));
        E5Part.WipeCheck wc = E5Part.checkWipe(d, pk, true);
        ok(!wc.ok && wc.reason.contains("另一台手机"), "check: formatting the phone running the app is refused (records would be wiped)");
        wc = E5Part.checkWipe(d, pk, false);
        ok(wc.ok && "empty".equals(wc.bcbKind), "check: remote target passes (" + wc.reason + ")");
        E5Part.Pkg old = pkg(d.byName.get("boot_a")); old.hasPartWrite = false;
        ok(!E5Part.checkWipe(d, old, false).ok, "check: package without part-write.sh refused");
        ok(String.valueOf(ioThrown(() -> E5Part.start(d, pk, base, "mmcblk0p50", 2048, 2048, 1, "", "E5-OpenWrt", "abcdefgh23", true))).contains("不能在本机"),
                "start: refused on the local phone");
        ok(ioThrown(() -> E5Part.start(d, pk, base, "mmcblk0p50", 3072, 2048, 1, "", "E5-OpenWrt", "abcdefgh23", false)) != null && d.writes.isEmpty(),
                "start: plan larger than userdata refused before any write");
        Mu300Internal.Outcome o = E5Part.start(d, pk, base, "mmcblk0p50", 2048, 2048, 1, "", "E5-OpenWrt", "abcdefgh23", false);
        File tx = E5Part.findTransaction(base, "SER5");
        Map<String, Object> st = E5Part.load(tx);
        String token = E5Part.str(st, "token");
        ok(o.pending && d.rebooted && "awaiting-android".equals(st.get("phase")), "start: stage one written, phase awaiting-android, reboot");
        ok(o.next.contains("重新打开 ADB") && o.next.contains("重新安装 Magisk（面具）") && o.next.contains("给 Shell 授予 root")
                && o.next.contains("“继续格式化后的安装”") && !o.next.contains("【高级】") && !o.next.contains("两阶段安装」"), "start (v1.5.2): next step names ADB, Magisk, Shell root and the real entry: " + o.next);
        ok(d.tools.equals(Arrays.asList("bootchain-sync", "arm")) && d.writes.equals(Arrays.asList("boot_b")), "start: device steps bootchain-sync → flash boot_b → arm");
        String request = "e5.part_prepare=" + token + ":" + d.cid + ":mmcblk0p50:1048576:8388608:4194304:4194304:" + Mu300Internal.hex(bc);
        byte[] bb = d.byName.get("boot_b");
        ok(contains(bb, request) && contains(bb, "etc/e5/linux-slot") && contains(bb, "etc/misc-bc-slot-b-trial.bin"),
                "start: boot_b carries the prepare request and the slot-role files");
        File dir = tx.getParentFile();
        boolean backups = true;
        for (String n : new String[]{"boot-b-before.img", "gpt-primary.bin", "gpt-backup.bin", "boot-control-before.bin", "bcb-before.bin", "bootchain.tar"})
            backups &= new File(dir, n).isFile();
        ok(backups && new File(dir, "boot-b-before.img").length() == IMAGE && new File(dir, "gpt-primary.bin").length() == 34 * 512,
                "start: boot_b, GPT (both copies), misc and boot chain backed up locally");
        ok(Long.valueOf(4194304).equals(st.get("target")) && Long.valueOf(4194304).equals(st.get("need")) && "abcdefgh23".equals(st.get("key"))
                && Long.valueOf(1).equals(st.get("request_handoff")), "start: transaction keeps target/need/settings/handoff");

        // the device rebooted but nothing was rebuilt yet: never prepare twice after a handed-off request
        d.rebooted = false; d.writes.clear(); d.tools.clear();
        String why = ioThrown(() -> E5Part.resume(d, pk, base));
        ok(why != null && why.contains("不会重复缩容或格式化") && d.writes.isEmpty(), "resume before rebuild: refused, nothing written: " + why);

        // ---------------------------------------------------------- after the offline rebuild + factory reset
        d.rows.put("userdata", new long[]{50, 1048576, 4194304});
        d.rows.put("e5root", new long[]{51, 1048576 + 4194304, 4194304});
        String desc = E5Part.checkResume(d, pk, base);
        ok(desc.contains("分区已按事务重建：/dev/block/mmcblk0p51"), "checkResume: " + desc);
        byte[] mk = ("E5-PART-PREPARED-V2 " + token + "\n").getBytes(StandardCharsets.US_ASCII);
        d.rootHead = new byte[128];
        System.arraycopy(mk, 0, d.rootHead, 0, mk.length);
        d.rootHead[22] ^= 1;
        why = ioThrown(() -> E5Part.resume(d, pk, base));
        ok(why != null && why.contains("准备标记不符") && !d.tools.contains("firmware") && d.writes.isEmpty(), "resume: wrong preparation marker refused before writing");
        d.rootHead[22] ^= 1;
        E5Part.Pkg other = pkg(bootImage("console=ttyS0 e5.part_v=2"));
        ok(String.valueOf(ioThrown(() -> E5Part.checkResume(d, other, base))).contains("同一个安装包"), "resume: a different package refused");
        d.serial = "OTHER";
        ok(ioThrown(() -> E5Part.checkResume(d, pk, base)) != null, "resume: another device has no transaction");
        d.serial = "SER5";
        d.rows.put("e5root", new long[]{51, 1048576 + 4194304, 4192256});
        ok(String.valueOf(ioThrown(() -> E5Part.resume(d, pk, base))).contains("分区布局与事务记录不符"), "resume: geometry differing from the record refused");
        d.rows.put("e5root", new long[]{51, 1048576 + 4194304, 4194304});
        d.tools.clear(); d.writes.clear();
        o = E5Part.resume(d, pk, base);
        st = E5Part.load(tx);
        ok(!o.pending && "complete".equals(st.get("phase")) && o.next.contains("abcdefgh23"), "resume: installed, phase complete: " + o.message);
        ok(d.tools.equals(Arrays.asList("preflight", "firmware", "part-write /dev/block/mmcblk0p51 4194304 1073741824 1 " + token, "bootchain-sync", "arm")),
                "resume: preflight → firmware → part-write(target need raw grow token) → bootchain-sync → arm: " + d.tools);
        String conf = new String(d.files.get("/data/local/tmp/e5-install.conf"), StandardCharsets.UTF_8);
        ok(conf.equals("E5_APN=''\nE5_WIFI_SSID='E5-OpenWrt'\nE5_WIFI_KEY='abcdefgh23'\nE5_DEFAULT_BOOT='linux'\n") && !new File(cache, "e5-install.conf").exists(),
                "resume: install settings uploaded, local copy removed");
        bb = d.byName.get("boot_b");
        ok(contains(bb, "e5.part_v=2 e5.root=part") && !contains(bb, "e5.part_prepare="), "resume: boot_b now boots the internal partition");
        ok(E5Part.findTransaction(base, "SER5") == null, "resume: completed transaction no longer offered");

        // ---------------------------------------------------------- reinstall an existing e5root (keeps userdata)
        Dev r = device(cache);
        r.rows.put("userdata", new long[]{50, 1048576, 4194304});
        r.rows.put("e5root", new long[]{51, 1048576 + 4194304, 4194304});
        r.installedConf = "E5_APN='cmnet'\nE5_WIFI_SSID='Old-AP'\nE5_WIFI_KEY='oldkey123'";
        E5Part.Pkg rp = pkg(r.byName.get("boot_a"));
        E5Part.Reinstall ri = E5Part.checkReinstall(r, rp);
        ok(ri.root.node.equals("mmcblk0p51"), "reinstall check: e5root directly after userdata");
        o = E5Part.reinstall(r, rp, base, 1);
        ok(!o.pending && o.next.contains("Old-AP") && o.next.contains("oldkey123") && !r.rebooted, "reinstall: keeps the old hotspot settings: " + o.next);
        ok(r.tools.size() == 5 && r.tools.get(0).equals("preflight") && r.tools.get(2).startsWith("part-write /dev/block/mmcblk0p51 4194304 "),
                "reinstall: preflight → firmware → part-write (no marker needed) → bootchain-sync → arm: " + r.tools);
        ok(new String(r.files.get("/data/local/tmp/e5-install.conf"), StandardCharsets.UTF_8).contains("E5_APN='cmnet'"), "reinstall: APN carried over");
        Dev g = device(cache);
        g.rows.put("userdata", new long[]{50, 1048576, 4194304});
        g.rows.put("e5root", new long[]{51, 1048576 + 4194304 + 2048, 4194304});
        ok(String.valueOf(ioThrown(() -> E5Part.checkReinstall(g, rp))).contains("does not directly follow"), "reinstall: e5root not right after userdata refused");
        Dev n = device(cache);
        ok(String.valueOf(ioThrown(() -> E5Part.checkReinstall(n, rp))).contains("没有已安装的 e5root"), "reinstall: no e5root refused");
        Dev t = device(cache);
        t.rows.put("userdata", new long[]{50, 1048576, 4194304});
        t.rows.put("e5root", new long[]{51, 1048576 + 4194304, 4194304});
        t.failTool = "part-write";
        why = ioThrown(() -> E5Part.reinstall(t, rp, base, 1));
        ok(why != null && why.contains("退出码 4") && why.contains("simulated part-write") && !t.writes.contains("boot_b"),
                "reinstall: a failing part-write stops before boot_b is changed: " + why);

        System.out.println(fails == 0 ? "PASS " + passes + " checks" : "FAILED " + fails + "/" + (fails + passes));
        System.exit(fails == 0 ? 0 : 1);
    }
}
