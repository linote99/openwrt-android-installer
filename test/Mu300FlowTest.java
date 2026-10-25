package top.linote.owrtinstaller;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Desktop test of the MU300 internal-storage port (check + install for PART free/shrink, REPART, IMAGE) against
 * a simulated device: a sparse eMMC with a real GPT, by-name partitions and a fake shell. The package's device-side
 * scripts are NEVER executed: their effect is simulated from the arguments the app passes, and every command the
 * app sends must be one the simulator recognises (anything else fails the test).
 *
 * Usage: Mu300FlowTest <mu300 zip> <work dir>
 */
public class Mu300FlowTest {
    static int failures = 0, checks = 0;

    static void ok(boolean c, String what) {
        checks++;
        if (!c) { failures++; System.out.println("FAIL " + what); }
        else System.out.println("ok   " + what);
    }

    // ------------------------------------------------------------------ simulated device

    static final class Dev implements Mu300Internal.Io {
        final long diskSectors;
        final Map<Long, byte[]> disk = new HashMap<>();           // sparse sectors of mmcblk0
        final Map<String, byte[]> byName = new LinkedHashMap<>();  // by-name partition content
        final Map<String, byte[]> files = new HashMap<>();         // files on the device
        final List<String> commands = new ArrayList<>();
        final List<String> runs = new ArrayList<>();               // bodies of run.sh
        final List<String> pushed = new ArrayList<>();
        final File cache;
        String model = "MU300\nMU300", platform = "ums9620", slot = "_a";
        String cid = "15010044353136474102a1b2c3d4e5f6".substring(0, 32);
        long udStart, udSize, freeKib;
        String state = "unencrypted", type = "", source = "/dev/block/by-name/userdata", magic = "1020f5f2", ext = "0000";
        String rootNode;            // existing mu300root node, e.g. mmcblk0p40
        long rootSectors, rootStart;
        byte[] rootHead = new byte[512];   // first sector of mu300root (prepare-stage marker)
        boolean rebooted;
        String logText = "";
        int lastRc = 0;
        String failStep;            // simulate a failing device step by script name
        String dieStep;             // background step whose process vanishes without an exit code
        String stallStep;           // background step that keeps running silently
        boolean launchFails;        // the detached launcher never starts (no run.pid)
        boolean killedOnClose;      // the detached process is ended when the launching session closes
        String pendingLaunch;       // launcher parked until the go file is created
        boolean bbBroken;           // the package busybox cannot run on this device
        int cancelAfterPolls = -1;  // the user presses 停止 after this many polls
        int polls;
        boolean finished = true, alive;
        String pid;
        final List<String> modes = new ArrayList<>();   // "fg <step>" / "bg <step>"
        final List<String> steps = new ArrayList<>();   // io.step() reports

        Dev(long diskSectors, File cache) { this.diskSectors = diskSectors; this.cache = cache; }

        byte[] sectors(long skip, long count) {
            byte[] out = new byte[(int) (count * 512)];
            for (long i = 0; i < count; i++) {
                byte[] s = disk.get(skip + i);
                if (s != null) System.arraycopy(s, 0, out, (int) (i * 512), 512);
            }
            return out;
        }

        void writeSectors(long at, byte[] data) {
            for (int i = 0; i < data.length / 512; i++) disk.put(at + i, Arrays.copyOfRange(data, i * 512, i * 512 + 512));
        }

        private static final Pattern DD_DISK = Pattern.compile("^dd if=/dev/block/mmcblk0 bs=512(?: skip=(\\d+))? count=(\\d+) 2>/dev/null$");
        private static final Pattern DD_MISC = Pattern.compile("^dd if=/dev/block/by-name/misc bs=(\\d+)(?: skip=(\\d+))? count=(\\d+)(?: 2>/dev/null)?$");
        private static final Pattern CAT = Pattern.compile("^cat /dev/block/by-name/(\\w+) 2>/dev/null$");

        byte[] eval(String cmd) {
            Matcher m = DD_DISK.matcher(cmd);
            if (m.matches()) return sectors(m.group(1) == null ? 0 : Long.parseLong(m.group(1)), Long.parseLong(m.group(2)));
            m = DD_MISC.matcher(cmd.replace(" 2>/dev/null 2>/dev/null", " 2>/dev/null"));
            if (m.matches()) {
                int bs = Integer.parseInt(m.group(1));
                long skip = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
                int count = Integer.parseInt(m.group(3));
                byte[] misc = byName.get("misc");
                return Arrays.copyOfRange(misc, (int) (skip * bs), (int) (skip * bs + (long) count * bs));
            }
            if (rootNode != null && cmd.equals("dd if=/dev/block/" + rootNode + " bs=512 count=1 2>/dev/null")) return rootHead.clone();
            m = CAT.matcher(cmd);
            if (m.matches() && byName.containsKey(m.group(1))) return byName.get(m.group(1));
            throw new AssertionError("unexpected binary read: " + cmd);
        }

        static String sha(byte[] b) { return Mu300Internal.hex(Mu300Internal.sha().digest(b)); }

        @Override public String sh(String script, boolean root) throws IOException {
            commands.add(script);
            if (script.startsWith("set -e; set -o pipefail; (")) {
                int a = "set -e; set -o pipefail; (".length();
                int b = script.indexOf(") > ", a);
                String inner = script.substring(a, b);
                String remote = script.substring(b + 4).split("[;& ]")[0];
                if (!root) throw new AssertionError("binary read must run as root");
                byte[] data = eval(inner);
                files.put(remote, data);
                String h = sha(data);
                return data.length + "\n" + h + "  " + remote + "\n" + h + "  -\n";
            }
            if (script.equals("df -Pk /data/local/tmp | tail -n1 | awk '{print $4}'")) return "9000000\n";
            if (script.startsWith("umask 077; mkdir /data/local/tmp/openwrt-read-")) return "";
            if (script.startsWith("rm -f /data/local/tmp/openwrt-read-")) { return ""; }
            if (script.equals("getprop ro.product.model; getprop ro.product.device")) return model + "\n";
            if (script.equals("getprop ro.board.platform")) return platform + "\n";
            if (script.equals("getprop ro.boot.slot_suffix")) return slot + "\n";
            if (script.startsWith("set -e; d=$(readlink -f /dev/block/by-name/userdata)")) {
                return "NODE=mmcblk0p39\nCID=" + cid + "\nSTART=" + udStart + "\nSIZE=" + udSize + "\nDISK_SIZE=" + diskSectors
                        + "\nFREE=" + freeKib + "\nSTATE=" + state + "\nTYPE=" + type + "\nSOURCE=" + source
                        + "\nMAGIC=" + magic + "\nEXT=" + ext + "\n" + (rootNode != null ? "ROOT=" + rootNode + "\n" : "");
            }
            if (rootNode != null && script.equals("cat /sys/class/block/" + rootNode + "/size")) return rootSectors + "\n";
            if (rootNode != null && script.equals("cat /sys/class/block/" + rootNode + "/start /sys/class/block/" + rootNode + "/size"))
                return rootStart + "\n" + rootSectors + "\n";
            Matcher m = Pattern.compile("^blockdev --getsize64 /dev/block/by-name/(\\w+)$").matcher(script);
            if (m.matches()) {
                byte[] p = byName.get(m.group(1));
                if (p == null) throw new IOException("no partition " + m.group(1));
                return p.length + "\n";
            }
            m = Pattern.compile("^sha256sum '?([^' ]+)'?$").matcher(script);
            if (m.matches()) {
                byte[] f = files.get(m.group(1));
                if (f == null) throw new IOException("sha256sum: " + m.group(1) + ": No such file");
                return sha(f) + "  " + m.group(1) + "\n";
            }
            if (script.matches("^mkdir -p /data/local/tmp/mu300-desktop-[0-9a-f]{12}/work; chown 2000:2000 .*; chmod 700 .*$")) return "";
            if (script.startsWith("mkdir -p ") && script.contains("; chown 2000:2000 ") && script.contains("; chmod 700 ")) return "";
            if (script.startsWith("chmod 755 ") || script.startsWith("chmod -R u+rx ")) return "";
            if (script.startsWith("rm -rf /data/local/tmp/mu300-desktop-")) { files.keySet().removeIf(k -> k.startsWith(script.substring(7))); return ""; }
            if (script.startsWith("rm -f ") && script.contains("/run.log") && script.contains("/run.pid") && script.contains(" setsid ")) {
                // two-phase start: PID first, then nothing until the go file exists; HUP ignored
                Matcher g = Pattern.compile("sh -c 'echo \\$\\$ > (\\S+/run\\.pid); i=0; while \\[ ! -f (\\S+/run\\.go) \\]; do .*?done; \\S+/busybox nsenter ").matcher(script);
                if (!g.find() || !script.contains("/run.go") || !script.contains("trap '' HUP; "))
                    throw new AssertionError("launcher must record its PID and wait for the go file before running: " + script);
                if (!script.contains("echo OWRT-LAUNCH-PID=")) throw new AssertionError("launcher must wait for the PID: " + script);
                pid = null;
                pendingLaunch = null;
                if (launchFails) { logText = "setsid: applet not found\n"; return "OWRT-LAUNCH-PID=\n"; }
                pendingLaunch = script;
                pid = "4242";
                logText = "";
                return "OWRT-LAUNCH-PID=4242\n";
            }
            if (pid != null && script.matches("^if kill -0 " + pid + " 2>/dev/null; then : > \\S+/run\\.go && echo OWRT-GO; fi; true$")) {
                if (killedOnClose || pendingLaunch == null) { pendingLaunch = null; return ""; }
                launch(pendingLaunch);
                pendingLaunch = null;
                modes.add("bg " + runs.get(runs.size() - 1).split("\n")[0]);
                String step = runs.get(runs.size() - 1);
                finished = true; alive = false;
                if (dieStep != null && step.contains(dieStep)) { logText = "writing...\n"; finished = false; alive = false; }
                if (stallStep != null && step.contains(stallStep)) { logText = "working...\n"; finished = false; alive = true; }
                return "OWRT-GO\n";
            }
            if (script.matches("^rm -f \\S+/run\\.go; (kill \\d+ 2>/dev/null; )?tail -c 2000 \\S+/run\\.log 2>/dev/null; true$")) {
                pendingLaunch = null;
                return logText;
            }
            if (script.matches("^cat /data/local/tmp/mu300-desktop-[0-9a-f]{12}/run\\.pid 2>/dev/null; true$")) return pid == null ? "" : pid + "\n";
            if (script.matches("^tail -c 2000 /data/local/tmp/mu300-desktop-[0-9a-f]{12}/run\\.log 2>/dev/null; true$")) return logText;
            if (script.matches("^/data/local/tmp/mu300-desktop-[0-9a-f]{12}/busybox echo OWRT-BB-OK 2>&1; true$"))
                return bbBroken ? "/system/bin/sh: busybox: not executable: 64-bit ELF file\n" : "OWRT-BB-OK\n";
            if (script.matches("^/data/local/tmp/mu300-desktop-[0-9a-f]{12}/busybox nsenter -t 1 -m \\S+/busybox sh \\S+/run\\.sh 2>&1; echo OWRT-RUN-RC=\\$\\?$")) {
                launch(script);
                modes.add("fg " + runs.get(runs.size() - 1).split("\n")[0]);
                return logText + "OWRT-RUN-RC=" + lastRc + "\n";
            }
            if (script.startsWith("if kill -0 " + pid + " 2>/dev/null; then echo ALIVE=1; else echo ALIVE=0; fi; s=$(stat -c %s ")) {
                polls++;
                m = Pattern.compile("tail -c \\+(\\d+) ").matcher(script);
                if (!m.find()) throw new AssertionError("bad poll");
                long off = Long.parseLong(m.group(1)) - 1;
                byte[] log = logText.getBytes(StandardCharsets.UTF_8);
                byte[] chunk = off >= log.length ? new byte[0] : Arrays.copyOfRange(log, (int) off, Math.min(log.length, (int) off + 49152));
                return "ALIVE=" + (alive ? 1 : 0) + "\nSIZE=" + log.length + "\n" + (finished ? "RC=" + lastRc + "\n" : "") + "DATA\n"
                        + java.util.Base64.getMimeEncoder().encodeToString(chunk) + "\n";
            }
            throw new AssertionError("unexpected command: " + script);
        }

        /** The detached run.sh launcher: simulate the package script's effect from the arguments; never execute it. */
        String launch(String script) throws IOException {
            Matcher m = Pattern.compile(" sh (/data/local/tmp/mu300-desktop-[0-9a-f]{12})/run\\.sh[ ']").matcher(script);
            if (!m.find()) throw new AssertionError("launcher without run.sh: " + script);
            String p = m.group(1);
            String body = new String(files.get(p + "/run.sh"), StandardCharsets.UTF_8);
            if (!body.startsWith("#!/system/bin/sh\nset -eu\nP=" + p + "\nT=" + p + "/work\nBB=" + p + "/busybox\n"))
                throw new AssertionError("run.sh header");
            String step = body.substring(body.indexOf("export PATH=\"$T/bin:$PATH\"\n") + "export PATH=\"$T/bin:$PATH\"\n".length());
            runs.add(step);
            StringBuilder log = new StringBuilder();
            lastRc = 0;
            for (String need : new String[]{"busybox", "android-bootchain.sh", "bootchain.sh"})
                if (!files.containsKey(p + "/" + need)) throw new AssertionError("run before upload of " + need);
            if (failStep != null && step.contains(failStep)) {
                logText = "simulated failure in " + failStep + "\n";
                lastRc = 3;
                return "";
            }
            if (step.contains("android-bootchain.sh\" check")) log.append("bootchain: both slots consistent\n");
            else if (step.equals("\"$BB\" sh \"$P/android-image.sh\"\n")) {
                byte[] env = files.get(p + "/desktop-settings.env");
                if (env == null) throw new AssertionError("android-image.sh without desktop-settings.env");
                if (!files.containsKey(p + "/mu300-openwrt.tar.gz")) throw new AssertionError("android-image.sh without rootfs");
                files.put(p + "/work/openwrt.ext4.new", ("ext4 image for " + new String(env, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
                log.append("image built\n");
            } else if (step.startsWith("\"$BB\" sh \"$P/android-write-free.sh\" ")) {
                byte[] nf = files.get(p + "/gpt-new-first.bin"), nl = files.get(p + "/gpt-new-last.bin");
                if (nf == null || nl == null) throw new AssertionError("write-free without new GPT copies");
                String img = sha(files.get(p + "/work/openwrt.ext4.new"));
                if (!step.contains(img)) throw new AssertionError("write-free without the image checksum");
                writeSectors(0, nf);
                long tail = diskSectors - nl.length / 512;
                writeSectors(tail, nl);
                log.append("partition written and verified\n");
            } else if (step.startsWith("\"$BB\" sh \"$P/android-write-part.sh\" /dev/block/")) {
                String img = sha(files.get(p + "/work/openwrt.ext4.new"));
                if (!step.trim().endsWith(" " + rootSectors + " " + img)) throw new AssertionError("write-part args: " + step);
                log.append("mu300root rewritten\n");
            } else if (step.startsWith("printf \"PART_MIB=")) {
                files.put("/data/mu300linux/part-install.conf", step.getBytes(StandardCharsets.UTF_8));
            } else if (step.contains("BOOT-VERIFIED")) {
                if (!step.startsWith("[ \"$(getprop ro.boot.slot_suffix)\" = \"" + slot + "\" ]\n")) throw new AssertionError("flash slot guard");
                String linux = "_a".equals(slot) ? "boot_b" : "boot_a";
                if (!step.contains("dev=/dev/block/by-name/" + linux + "\n")) throw new AssertionError("flash target");
                byte[] img = files.get(p + "/boot-desktop.img");
                if (img.length != byName.get(linux).length) { logText = "size mismatch\n"; lastRc = 1; return ""; }
                byName.put(linux, img);
                byte[] misc = byName.get("misc");
                System.arraycopy(files.get(p + "/misc-linux.bin"), 0, misc, 2048, 32);
                log.append("BOOT-VERIFIED; OpenWrt selected\n");
            } else throw new AssertionError("unexpected step: " + step);
            logText = log.toString();
            return "";
        }

        @Override public void pull(String remote, File dest) throws IOException {
            byte[] d = files.get(remote);
            if (d == null) throw new IOException("pull: no " + remote);
            Mu300Internal.writeAll(dest, d);
        }

        @Override public void push(File local, String remote) throws IOException {
            files.put(remote, Mu300Internal.readAll(local));
            pushed.add(remote.substring(remote.indexOf("/", "/data/local/tmp/".length()) + 1));
        }

        @Override public void reboot() { rebooted = true; }
        final List<String> logs = new ArrayList<>();
        @Override public void log(String line) { logs.add(line); }
        @Override public void progress(int percent) { }
        @Override public File cacheDir() { return cache; }
        @Override public void step(String what, boolean writes) { steps.add((writes ? "W " : "R ") + what); }
        @Override public boolean cancelled() { return cancelAfterPolls >= 0 && polls >= cancelAfterPolls; }
    }

    // ------------------------------------------------------------------ synthetic GPT

    static void gpt(Dev d, String[] names, long[][] ranges) {
        int count = 128;
        byte[] table = new byte[count * 128];
        ByteBuffer t = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < names.length; i++) {
            System.arraycopy(Mu300Gpt.guidLe(UUID.fromString("0fc63daf-8483-4772-8e79-3d69d8477de4")), 0, table, i * 128, 16);
            System.arraycopy(Mu300Gpt.guidLe(UUID.nameUUIDFromBytes(names[i].getBytes())), 0, table, i * 128 + 16, 16);
            t.putLong(i * 128 + 32, ranges[i][0]);
            t.putLong(i * 128 + 40, ranges[i][1]);
            byte[] nm = names[i].getBytes(StandardCharsets.UTF_16LE);
            System.arraycopy(nm, 0, table, i * 128 + 56, nm.length);
        }
        int tcrc = Mu300Boot.crc32(table, 0, table.length);
        long first = 34, last = d.diskSectors - 34;
        UUID disk = UUID.nameUUIDFromBytes("disk".getBytes());
        for (int c = 0; c < 2; c++) {
            byte[] h = new byte[512];
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            System.arraycopy("EFI PART".getBytes(StandardCharsets.US_ASCII), 0, h, 0, 8);
            b.putInt(8, 0x00010000); b.putInt(12, 92);
            long cur = c == 0 ? 1 : d.diskSectors - 1, alt = c == 0 ? d.diskSectors - 1 : 1;
            b.putLong(24, cur); b.putLong(32, alt); b.putLong(40, first); b.putLong(48, last);
            System.arraycopy(Mu300Gpt.guidLe(disk), 0, h, 56, 16);
            b.putLong(72, c == 0 ? 2 : d.diskSectors - 33);
            b.putInt(80, count); b.putInt(84, 128); b.putInt(88, tcrc);
            b.putInt(16, Mu300Boot.crc32(h, 0, 92));
            d.writeSectors(cur, h);
            d.writeSectors(c == 0 ? 2 : d.diskSectors - 33, table);
        }
    }

    static Dev device(File cache, boolean gap, File boot) throws IOException {
        long disk = 15269888L;                                    // 7.28 GiB eMMC
        Dev d = new Dev(disk, cache);
        long bootSec = 131072;                                    // 64 MiB, like boot-linux.img
        String[] names = {"misc", "boot_a", "boot_b", "uboot_a", "uboot_b", "sml_a", "sml_b", "teecfg_a", "teecfg_b",
                "trustos_a", "trustos_b", "vbmeta_a", "vbmeta_b", "userdata"};
        long[][] r = new long[names.length][];
        long at = 2048;
        for (int i = 0; i < names.length - 1; i++) {
            long n = names[i].startsWith("boot_") ? bootSec : 2048;
            r[i] = new long[]{at, at + n - 1};
            at += n;
        }
        d.udStart = (at + 2047) / 2048 * 2048;
        long end = gap ? disk - 34 - 3L * 2097152 : disk - 34;    // leave ~3 GiB unallocated after userdata
        d.udSize = (end - d.udStart) / 2048 * 2048;
        r[names.length - 1] = new long[]{d.udStart, d.udStart + d.udSize - 1};
        d.freeKib = d.udSize / 2 - 600L * 1024;                    // ~600 MiB used by Android
        gpt(d, names, r);
        for (int i = 0; i < names.length - 1; i++) {
            byte[] c = new byte[(int) ((r[i][1] - r[i][0] + 1) * 512)];
            Arrays.fill(c, (byte) (i + 1));
            d.byName.put(names[i], c);
        }
        byte[] misc = d.byName.get("misc");
        Arrays.fill(misc, (byte) 0);
        byte[] bc = new byte[32];
        bc[0] = '_'; bc[1] = 'a'; bc[4] = 'B'; bc[5] = 'C'; bc[6] = 'A'; bc[7] = 'B'; bc[8] = 1; bc[9] = 2;
        bc[12] = (byte) 0x9f; bc[14] = 0x1e;
        Mu300Boot.put32le(bc, 28, Mu300Boot.crc32(bc, 0, 28));
        System.arraycopy(bc, 0, misc, 2048, 32);
        return d;
    }

    static String expectFail(Mu300Internal.Io io, Mu300Internal.Kind k) {
        try { Mu300Internal.check(io, k); return null; } catch (IOException e) { return e.getMessage(); }
    }

    public static void main(String[] a) throws Exception {
        File zip = new File(a[0]);
        File work = new File(a[1]);
        Mu300Internal.deleteTree(work);
        work.mkdirs();
        File cache = new File(work, "cache");
        cache.mkdirs();
        // shlex.quote() parity (ASCII safe set; v1.4.1 dropped the Android-unsupported regex flag)
        ok(Mu300Gpt.quote("").equals("''") && Mu300Gpt.quote("/data/local/tmp/a_b-1.img").equals("/data/local/tmp/a_b-1.img")
                && Mu300Gpt.quote("a b").equals("'a b'") && Mu300Gpt.quote("\u00fc").equals("'\u00fc'")
                && Mu300Gpt.quote("it's").equals("'it'\"'\"'s'") && Mu300Gpt.quote("K=v@x:1,2%+").equals("K=v@x:1,2%+"),
                "quote() matches shlex.quote for safe, spaced, non-ASCII and quoted strings");
        PackageInspector.Result r;
        try (InputStream in = new BufferedInputStream(new FileInputStream(zip), 1 << 16)) {
            r = PackageInspector.inspect(in, zip.length(), new File(work, "stage"), null);
        }
        ok(r.isMu300() && r.internalProblem == null && r.internalSrc != null, "package accepted for internal methods (kernel " + r.kernelFamily + ")");
        File local = new File(work, "mu300-local");
        long t0 = System.currentTimeMillis();
        Mu300Internal.prepareLocal(r.packageDir, r.internalSrc, local, r.listedMap, null);
        System.out.println("prepareLocal " + (System.currentTimeMillis() - t0) + " ms");
        Map<String, String> sums = Mu300Internal.localSums(local);
        ok(sums.containsKey(Mu300Internal.ROOTFS) && sums.containsKey(Mu300Internal.BOOT) && sums.size() >= 7 + 15 + 16,
                "app-side copy with LOCAL-SUMS (" + sums.size() + " files)");
        boolean allMatch = true;
        for (Map.Entry<String, String> e : sums.entrySet())
            if (!Mu300Internal.hashFile(new File(local, e.getKey())).equals(e.getValue())) allMatch = false;
        ok(allMatch, "every app-side file matches LOCAL-SUMS");
        ok(!sums.get(Mu300Internal.ROOTFS).equals(r.listedMap.get(Mu300Internal.ROOTFS)), "rootfs was filtered (hash differs from the package)");
        ok(new File(r.packageDir, "payload/tf-install-worker.sh").isFile() && !new File(r.packageDir, "desktop/android-image.sh").exists(),
                "TF stage untouched by the desktop files");
        File boot = new File(local, Mu300Internal.BOOT);
        byte[] base = Mu300Internal.readAll(boot);

        // A: PART into unallocated space
        Dev d = device(cache, true, boot);
        Mu300Internal.Plan p = Mu300Internal.check(d, Mu300Internal.Kind.PART);
        ok(p.profile.equals("f50") && p.slot.equals("a") && p.linux.equals("b"), "A identify: profile=" + p.profile + " slot=" + p.slot);
        ok(p.freeMax >= 3000 && p.freeMax <= 3072 && !p.encrypted, "A gap found: freeMax=" + p.freeMax + " MiB, shrinkCap=" + p.shrinkCap);
        ok(Mu300Internal.usesFreeSpace(p, 2048) && !Mu300Internal.usesFreeSpace(p, p.freeMax + 4), "A free/shrink decision by size");
        File backups = new File(work, "backups");
        try {
            Mu300Internal.install(d, local, r.kernelFamily, Mu300Internal.Kind.PART, 2048, backups, "SERIAL1", false, null);
            ok(false, "A mismatched expectation must abort");
        } catch (IOException e) {
            ok(e.getMessage().contains("重新做只读检查") && (!backups.exists() || backups.list().length == 0), "A mismatched expectation aborts before any backup/write");
        }
        String[] bk = {null};
        long before = d.disk.size();
        Mu300Internal.Outcome o = Mu300Internal.install(d, local, r.kernelFamily, Mu300Internal.Kind.PART, 2048, backups, "SERIAL1", true, x -> bk[0] = x);
        ok(!o.pending && !d.rebooted, "A outcome: " + o.message);
        Mu300Gpt.Space after = Mu300Gpt.space(d.sectors(0, 34), d.sectors(d.diskSectors - 33, 33), d.diskSectors);
        Mu300Gpt.Part root = after.find("mu300root");
        ok(root != null && root.end - root.start + 1 == 2048L * 2048 && root.start % 2048 == 0 && root.start > d.udStart + d.udSize - 1,
                "A GPT now has mu300root after userdata (" + (root == null ? "-" : root.start + ".." + root.end) + ")");
        ok(after.find("userdata").end - after.find("userdata").start + 1 == d.udSize, "A userdata unchanged");
        byte[] bootB = d.byName.get("boot_b");
        boolean selfOk = true;
        try { Mu300Boot.selfCheck(base, bootB); } catch (RuntimeException e) { selfOk = false; }
        ok(selfOk && !Arrays.equals(bootB, base), "A boot_b holds a generated Linux boot image (self-check passes)");
        ok(Arrays.equals(d.byName.get("boot_a"), device(cache, true, boot).byName.get("boot_a")), "A Android boot_a untouched");
        byte[] misc = Arrays.copyOfRange(d.byName.get("misc"), 2048, 2080);
        ok(Mu300Boot.validLive(misc) && misc[1] == 'b', "A misc A/B block now selects slot b once");
        File bdir = bk[0] == null ? null : new File(bk[0]);
        ok(bdir != null && new File(bdir, "SHA256SUMS").isFile() && new File(bdir, "gpt-first.bin").length() == 34 * 512
                && new File(bdir, "boot_a.img").length() == 64L << 20 && new File(bdir, "identity.json").isFile(), "A backup written: " + bk[0]);
        boolean bsums = true;
        for (String line : new String(Mu300Internal.readAll(new File(bdir, "SHA256SUMS")), StandardCharsets.UTF_8).split("\n"))
            if (!Mu300Internal.hashFile(new File(bdir, line.substring(66))).equals(line.substring(0, 64))) bsums = false;
        ok(bsums, "A backup SHA256SUMS verifies");
        ok(d.pushed.contains("mu300-openwrt.tar.gz") && d.pushed.contains("android-write-free.sh") && d.pushed.contains("desktop/files/boot/repair/manifest.json")
                && !d.pushed.contains("tf-install-worker.sh"), "A uploads: " + d.pushed.size() + " files");
        ok(d.runs.size() == 4 && d.runs.get(0).contains("bootchain.sh\" check") && d.runs.get(3).contains("BOOT-VERIFIED"),
                "A device steps in order: check → image → write-free → flash");
        String env = null;
        for (String c : d.commands) if (c.startsWith("rm -rf /data/local/tmp/mu300-desktop-")) env = c;
        ok(env != null && d.files.keySet().stream().noneMatch(k -> k.startsWith("/data/local/tmp/mu300-desktop-")), "A temporary payload removed");
        ok(d.commands.stream().filter(c -> !c.contains("/repair/")).noneMatch(c -> c.matches("(?s).*(^|[;&|\n] *)(mkfs|sgdisk|resize\\.f2fs|fsck|parted|blkdiscard)\\b.*")),
                "A no direct destructive tool invoked by the app itself");

        // B: no gap → offline preserving shrink, then reboot
        d = device(cache, false, boot);
        p = Mu300Internal.check(d, Mu300Internal.Kind.PART);
        ok(p.freeMax == 0 && p.shrinkCap >= 2048, "B no gap; shrinkCap=" + p.shrinkCap + " MiB, maximum=" + p.maximum);
        o = Mu300Internal.install(d, local, r.kernelFamily, Mu300Internal.Kind.PART, 2048, backups, "SERIAL1", false, null);
        long target = Mu300Gpt.allocation(d.udSize, 2048)[0];
        String conf = new String(d.files.getOrDefault("/data/mu300linux/part-install.conf", new byte[0]), StandardCharsets.UTF_8);
        ok(o.pending && d.rebooted, "B outcome pending + reboot requested");
        ok(conf.contains("PART_MIB=2048") && conf.contains("DATA_MIB=" + target / 2048) && conf.contains("FORMAT_DATA=0"), "B part-install.conf: " + conf.trim().replace('\n', ' '));
        ok(Mu300Gpt.space(d.sectors(0, 34), d.sectors(d.diskSectors - 33, 33), d.diskSectors).find("mu300root") == null,
                "B GPT not changed online (initramfs does it offline)");
        selfOk = true;
        try { Mu300Boot.selfCheck(base, d.byName.get("boot_b")); } catch (RuntimeException e) { selfOk = false; }
        ok(selfOk, "B install-part boot image flashed to boot_b");
        ok(expectFail(d, Mu300Internal.Kind.PART) == null, "B re-check still read-only");

        // B2: the unallocated range still carries an old ext4 superblock → not usable as free space
        d = device(cache, true, boot);
        long gapStart = (d.udStart + d.udSize + 2047) / 2048 * 2048;
        byte[] sb = new byte[512];
        sb[56] = 0x53; sb[57] = (byte) 0xef;                      // byte 1080 of the range = sector 2, offset 56
        d.disk.put(gapStart + 2, sb);
        p = Mu300Internal.check(d, Mu300Internal.Kind.PART);
        ok(p.freeMax == 0 && p.shrinkCap >= 2048 && !Mu300Internal.usesFreeSpace(p, 2048), "B2 leftover ext4 signature blocks the gap; falls back to shrink");

        // C: encrypted userdata and no gap → only the "format userdata" route (as the original: destructive = is_encrypted)
        d = device(cache, false, boot);
        d.state = "encrypted"; d.type = "file";
        p = Mu300Internal.check(d, Mu300Internal.Kind.PART);
        ok(p.shrinkCap == 0 && p.formatMax >= 2048 && Mu300Internal.route(p, 2048, false) == Mu300Internal.Route.FORMAT
                && Mu300Internal.formatRequired(p, 2048), "C encrypted + no gap: check passes, format route required (formatMax=" + p.formatMax + ")");
        ok(p.def <= p.formatMax && String.join("\n", d.logs).contains("路径三 格式化 userdata"), "C check logs all three routes");
        byte[] bootC = d.byName.get("boot_b").clone();
        int backupsBefore = backups.exists() ? backups.list().length : 0;
        String why = null;
        try { Mu300Internal.install(d, local, r.kernelFamily, Mu300Internal.Kind.PART, 2048, backups, "SERIAL1", Mu300Internal.Route.FORMAT, false, null); }
        catch (IOException e) { why = e.getMessage(); }
        ok(why != null && why.contains("勾选") && Arrays.equals(bootC, d.byName.get("boot_b")) && !d.rebooted
                && (backups.exists() ? backups.list().length : 0) == backupsBefore,
                "C format is never implied by encryption: refused before any backup/write: " + why);
        why = null;
        try { Mu300Internal.install(d, local, r.kernelFamily, Mu300Internal.Kind.PART, 2048, backups, "SERIAL1", Mu300Internal.Route.KEEP, false, null); }
        catch (IOException e) { why = e.getMessage(); }
        ok(why != null && why.contains("重新做只读检查"), "C confirmed route mismatch (KEEP) aborts: " + why);
        d.logs.clear();
        o = Mu300Internal.install(d, local, r.kernelFamily, Mu300Internal.Kind.PART, 2048, backups, "SERIAL1", Mu300Internal.Route.FORMAT, true, null);
        ok(o.pending && d.rebooted && o.message.contains("Android 数据全部清空"), "C format: prepare stage written, reboot requested: " + o.message);
        String joinedC = String.join("\n", d.logs);
        ok(joinedC.contains("安装路径：格式化 userdata") && joinedC.contains("userdata 已加密"), "C log names the route and the reason");
        ok(o.next.contains("重新打开 ADB") && o.next.contains("重新安装 Magisk（面具）") && o.next.contains("给 Shell 授予 root")
                && o.next.contains("“继续格式化后的安装”") && !o.next.contains("【高级】") && !o.next.contains("高级功能") && o.next.contains("5555")
                && joinedC.contains("  1. 完成 Android 首次设置") && joinedC.contains("  3. 在目标设备上允许本应用的调试授权") && joinedC.contains("  5. 在超级用户请求里允许“Shell”")
                && joinedC.contains("允许“Shell”"), "C (v1.5.2) stage one tells to reopen ADB, reinstall Magisk and grant Shell root: " + o.next);
        File txDir = null;
        for (File f : backups.listFiles()) if (new File(f, "transaction.json").isFile() && (txDir == null || f.lastModified() >= txDir.lastModified())) txDir = f;
        ok(txDir != null && new File(txDir, "package-manifest.json").isFile() && new File(txDir, "SHA256SUMS").isFile(), "C backup + transaction + manifest snapshot saved: " + txDir);
        @SuppressWarnings("unchecked")
        Map<String, Object> tx = (Map<String, Object>) MiniJson.parse(new String(Mu300Internal.readAll(new File(txDir, "transaction.json")), StandardCharsets.UTF_8));
        long tTarget = Mu300Gpt.allocation(d.udSize, 2048)[0], tNeed = Mu300Gpt.allocation(d.udSize, 2048)[1];
        String token = String.valueOf(tx.get("token"));
        ok("prepared-request".equals(tx.get("state")) && token.matches("[0-9a-f]{32}") && Long.valueOf(tTarget).equals(tx.get("target"))
                && "SERIAL1".equals(tx.get("serial")) && "a".equals(tx.get("android_slot")) && String.valueOf(tx.get("payload_identity")).matches("[0-9a-f]{64}"),
                "C transaction: state/token/target/serial/slot/payload identity");
        selfOk = true;
        try { Mu300Boot.selfCheck(base, d.byName.get("boot_b")); } catch (RuntimeException e) { selfOk = false; }
        ok(selfOk && !Arrays.equals(bootC, d.byName.get("boot_b")), "C prepare boot image flashed to boot_b");
        ok(!d.files.containsKey("/data/mu300linux/part-install.conf"), "C no keep-data part-install.conf written");
        ok(Mu300Gpt.space(d.sectors(0, 34), d.sectors(d.diskSectors - 33, 33), d.diskSectors).find("mu300root") == null,
                "C GPT not changed online (initramfs shrinks and formats offline)");

        // R: resume after the reset (the prepare stage created mu300root and wrote its token marker)
        Dev rd = device(cache, false, boot);
        rd.state = "encrypted"; rd.type = "file";
        rd.udSize = tTarget; rd.rootNode = "mmcblk0p40"; rd.rootStart = rd.udStart + tTarget; rd.rootSectors = tNeed;
        byte[] mk = ("MU300-PART-PREPARED-V2 " + token + "\n").getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(mk, 0, rd.rootHead, 0, mk.length);
        p = Mu300Internal.check(rd, Mu300Internal.Kind.RESUME);
        ok(p.rootSize == tNeed && p.rootStart == rd.udStart + tTarget, "R resume check reads the prepared mu300root: " + p.summary);
        why = null;
        try { Mu300Internal.install(rd, local, r.kernelFamily, Mu300Internal.Kind.RESUME, 0, backups, "OTHER", null, false, null); }
        catch (IOException e) { why = e.getMessage(); }
        ok(why != null && why.contains("序列号"), "R different device serial refused: " + why);
        rd.rootHead[30] ^= 1;
        why = null;
        try { Mu300Internal.install(rd, local, r.kernelFamily, Mu300Internal.Kind.RESUME, 0, backups, "SERIAL1", null, false, null); }
        catch (IOException e) { why = e.getMessage(); }
        ok(why != null && why.contains("令牌"), "R wrong prepare token refused: " + why);
        rd.rootHead[30] ^= 1;
        rd.udSize = tTarget + 2048;
        why = null;
        try { Mu300Internal.install(rd, local, r.kernelFamily, Mu300Internal.Kind.RESUME, 0, backups, "SERIAL1", null, false, null); }
        catch (IOException e) { why = e.getMessage(); }
        ok(why != null && why.contains("位置/大小"), "R geometry differing from the record refused: " + why);
        rd.udSize = tTarget;
        o = Mu300Internal.install(rd, local, r.kernelFamily, Mu300Internal.Kind.RESUME, 0, backups, "SERIAL1", null, false, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> tx2 = (Map<String, Object>) MiniJson.parse(new String(Mu300Internal.readAll(new File(txDir, "transaction.json")), StandardCharsets.UTF_8));
        ok(!o.pending && o.message.contains("独立分区已安装") && "installed".equals(tx2.get("state")), "R resume writes mu300root, record marked installed: " + o.message);
        selfOk = true;
        try { Mu300Boot.selfCheck(base, rd.byName.get("boot_b")); } catch (RuntimeException e) { selfOk = false; }
        ok(selfOk, "R part boot image flashed to boot_b");

        d.logs.clear();
        why = expectFail(d, Mu300Internal.Kind.IMAGE);
        ok(why != null && why.contains("加密"), "C encrypted image refused");
        String joined = String.join("\n", d.logs);
        ok(joined.contains("设备：") && joined.contains("userdata：/dev/block/") && joined.contains("ro.crypto.type=file")
                && joined.contains("加密判断：userdata 已加密（文件级加密 FBE）"),
                "C check logs each step before the refusal (device, userdata, crypto props, verdict)");
        // C2: unknown encryption state
        d = device(cache, false, boot);
        d.state = ""; d.magic = "00000000";
        why = expectFail(d, Mu300Internal.Kind.PART);
        ok(why != null && why.contains("无法确认"), "C2 unknown encryption refused: " + why);

        // D: wrong model
        d = device(cache, true, boot);
        d.model = "ZTE 8045\nP855A01";
        why = expectFail(d, Mu300Internal.Kind.PART);
        ok(why != null && why.startsWith("机型不符"), "D other model refused: " + why);

        // E: existing mu300root → PART refused, REPART works
        d = device(cache, true, boot);
        d.rootNode = "mmcblk0p40"; d.rootSectors = 3L * 2097152;
        why = expectFail(d, Mu300Internal.Kind.PART);
        ok(why != null && why.contains("重装已有内置分区"), "E PART refused when mu300root exists");
        p = Mu300Internal.check(d, Mu300Internal.Kind.REPART);
        ok(p.rootMib == 3072, "E REPART check: " + p.summary);
        o = Mu300Internal.install(d, local, r.kernelFamily, Mu300Internal.Kind.REPART, 0, backups, "SERIAL1", null, null);
        ok(!o.pending && d.runs.size() == 4 && d.runs.get(2).startsWith("\"$BB\" sh \"$P/android-write-part.sh\" /dev/block/mmcblk0p40 " + d.cid),
                "E REPART: " + o.message);

        // F: IMAGE
        d = device(cache, true, boot);
        p = Mu300Internal.check(d, Mu300Internal.Kind.IMAGE);
        ok(p.maximum == d.freeKib / 1024 - 1280 && p.def == Math.min(4096, p.maximum) / 4 * 4, "F IMAGE range 2048–" + p.maximum + " default " + p.def);
        try {
            Mu300Internal.install(d, local, r.kernelFamily, Mu300Internal.Kind.IMAGE, p.maximum + 1, backups, "SERIAL1", null, null);
            ok(false, "F oversize must be refused");
        } catch (IOException e) { ok(d.runs.isEmpty(), "F oversize refused before upload: " + e.getMessage()); }
        o = Mu300Internal.install(d, local, r.kernelFamily, Mu300Internal.Kind.IMAGE, p.def, backups, "SERIAL1", null, null);
        String envText = "";
        for (String run : d.runs) if (run.contains("android-image.sh")) envText = "image";
        ok(!o.pending && envText.equals("image") && d.runs.size() == 3, "F IMAGE: " + o.message);

        // G: a failing device step stops before the boot slot is touched
        d = device(cache, true, boot);
        d.failStep = "android-write-free.sh";
        byte[] bootBefore = d.byName.get("boot_b").clone();
        try {
            Mu300Internal.install(d, local, r.kernelFamily, Mu300Internal.Kind.PART, 2048, backups, "SERIAL1", true, null);
            ok(false, "G failure must propagate");
        } catch (IOException e) {
            ok(e.getMessage().contains("退出码 3") && Arrays.equals(bootBefore, d.byName.get("boot_b")) && d.byName.get("misc")[2049] == 'a',
                    "G failed step → no boot/misc change: " + e.getMessage());
        }

        // H: tampered app-side copy is refused before upload
        d = device(cache, true, boot);
        File sw = new File(local, "payload/switch.sh");
        byte[] orig = Mu300Internal.readAll(sw);
        Mu300Internal.writeAll(sw, (new String(orig, StandardCharsets.UTF_8) + "\n# changed\n").getBytes(StandardCharsets.UTF_8));
        try {
            Mu300Internal.install(d, local, r.kernelFamily, Mu300Internal.Kind.PART, 2048, backups, "SERIAL1", true, null);
            ok(false, "H tamper must be refused");
        } catch (IOException e) {
            ok(e.getMessage().contains("已变化") && !d.pushed.contains("switch.sh"), "H tampered local copy refused: " + e.getMessage());
        }
        Mu300Internal.writeAll(sw, orig);

        // I: read-only steps run in the foreground (like the original installer), writes detached with a PID
        d = device(cache, true, boot);
        d.rootNode = "mmcblk0p40"; d.rootSectors = 3L * 2097152;
        Mu300Internal.check(d, Mu300Internal.Kind.REPART);
        o = Mu300Internal.install(d, local, r.kernelFamily, Mu300Internal.Kind.REPART, 0, backups, "SERIAL1", null, null);
        ok(d.modes.size() == 4 && d.modes.get(0).startsWith("fg ") && d.modes.get(0).contains("android-bootchain.sh\" check")
                && d.modes.get(1).startsWith("bg ") && d.modes.get(2).startsWith("bg ") && d.modes.get(3).startsWith("bg "), "I step modes: " + d.modes);
        ok(d.logs.contains("▸ 检查启动链（android-bootchain.sh check）（只读）") && d.logs.contains("  目标设备后台进程 PID 4242")
                && d.logs.contains("  ✓ mu300-openwrt.tar.gz（" + Mu300Internal.size(new File(local, Mu300Internal.ROOTFS).length()) + "）")
                && d.logs.contains("安装文件已全部上传并校验"), "I log names every step and uploaded file");
        ok(d.steps.contains("R 上传 mu300-openwrt.tar.gz") && d.steps.contains("R 校验 mu300-openwrt.tar.gz 的 SHA-256")
                && d.steps.contains("W 写入 OpenWrt 分区 /dev/block/mmcblk0p40"), "I status names the step being waited on");

        // J (v1.5.1): the detached launcher never starts: each writing step falls back to the foreground
        // (as the original installer runs it), runs exactly once, and the install completes
        long saveLaunch = Mu300Internal.LAUNCH_MS;
        Mu300Internal.LAUNCH_MS = 1500;
        d = device(cache, true, boot);
        d.rootNode = "mmcblk0p40"; d.rootSectors = 3L * 2097152;
        d.launchFails = true;
        t0 = System.currentTimeMillis();
        String jErr = null;
        try { Mu300Internal.install(d, local, r.kernelFamily, Mu300Internal.Kind.REPART, 0, backups, "SERIAL1", null, null); }
        catch (IOException e) { jErr = e.getMessage(); }
        long jdt = System.currentTimeMillis() - t0;
        ok(jErr == null && d.modes.size() == 4 && d.modes.stream().allMatch(x -> x.startsWith("fg ")) && d.runs.size() == 4,
                "J no detached start: writes fall back to the foreground once each in " + jdt + " ms: " + d.modes + (jErr != null ? " " + jErr : ""));
        final Dev dj = d;
        ok(d.logs.stream().anyMatch(l -> l.contains("目标设备上的后台进程没有启动") && l.contains("setsid: applet not found") && l.contains("改为前台执行"))
                && d.steps.stream().anyMatch(x -> x.endsWith("（前台执行）")), "J fallback reason logged and shown");
        // J2: the detached process is ended when the launching session closes (seen on a U30 Air over
        // wireless ADB): it never got the go file, so it ran nothing; the step runs once in the foreground
        d = device(cache, true, boot);
        d.rootNode = "mmcblk0p40"; d.rootSectors = 3L * 2097152;
        d.killedOnClose = true;
        jErr = null;
        try { Mu300Internal.install(d, local, r.kernelFamily, Mu300Internal.Kind.REPART, 0, backups, "SERIAL1", null, null); }
        catch (IOException e) { jErr = e.getMessage(); }
        ok(jErr == null && d.modes.size() == 4 && d.modes.stream().allMatch(x -> x.startsWith("fg ")) && d.runs.size() == 4,
                "J2 process ended with its session: foreground once per step: " + d.modes + (jErr != null ? " " + jErr : ""));
        ok(d.logs.stream().anyMatch(l -> l.contains("（PID 4242）在启动会话结束时被系统结束") && l.contains("尚未执行这一步的任何命令")),
                "J2 reason logged");
        Mu300Internal.LAUNCH_MS = saveLaunch;

        // K: the background write process dies without an exit code: reported, never waited on for hours
        d = device(cache, true, boot);
        d.rootNode = "mmcblk0p40"; d.rootSectors = 3L * 2097152;
        d.dieStep = "android-write-part.sh";
        bootBefore = d.byName.get("boot_b").clone();
        t0 = System.currentTimeMillis();
        try {
            Mu300Internal.install(d, local, r.kernelFamily, Mu300Internal.Kind.REPART, 0, backups, "SERIAL1", null, null);
            ok(false, "K dead process must propagate");
        } catch (IOException e) {
            long dt = System.currentTimeMillis() - t0;
            ok(e.getMessage().contains("意外结束") && e.getMessage().contains("PID 4242") && e.getMessage().contains("请勿直接重试")
                    && e.getMessage().contains("writing...") && dt < 20000 && Arrays.equals(bootBefore, d.byName.get("boot_b")),
                    "K vanished process reported in " + dt + " ms: " + e.getMessage());
        }

        // L: the user stops waiting on a silent background step: told it may still be writing, boot untouched
        d = device(cache, true, boot);
        d.rootNode = "mmcblk0p40"; d.rootSectors = 3L * 2097152;
        d.stallStep = "android-image.sh";
        d.cancelAfterPolls = 2;
        bootBefore = d.byName.get("boot_b").clone();
        try {
            Mu300Internal.install(d, local, r.kernelFamily, Mu300Internal.Kind.REPART, 0, backups, "SERIAL1", null, null);
            ok(false, "L stop must propagate");
        } catch (IOException e) {
            ok(e.getMessage().contains("已停止等待") && e.getMessage().contains("不要重启") && Arrays.equals(bootBefore, d.byName.get("boot_b"))
                    && d.steps.contains("W 生成 OpenWrt 系统镜像（只写临时文件，不改分区）"), "L stop while waiting: " + e.getMessage());
        }

        // M: the package busybox cannot run on the device: refused before any device step
        d = device(cache, true, boot);
        d.bbBroken = true;
        try {
            Mu300Internal.install(d, local, r.kernelFamily, Mu300Internal.Kind.PART, 2048, backups, "SERIAL1", true, null);
            ok(false, "M broken busybox must propagate");
        } catch (IOException e) {
            ok(e.getMessage().contains("无法运行安装包里的 busybox") && e.getMessage().contains("not executable") && d.runs.isEmpty(),
                    "M busybox check: " + e.getMessage());
        }

        // N: the read-only boot-chain check fails in the foreground: its output and exit code are shown
        d = device(cache, true, boot);
        d.failStep = "android-bootchain.sh\" check";
        try {
            Mu300Internal.install(d, local, r.kernelFamily, Mu300Internal.Kind.PART, 2048, backups, "SERIAL1", true, null);
            ok(false, "N failed check must propagate");
        } catch (IOException e) {
            ok(e.getMessage().contains("检查启动链") && e.getMessage().contains("退出码 3") && d.logs.contains("simulated failure in android-bootchain.sh\" check")
                    && d.modes.size() == 1 && d.modes.get(0).startsWith("fg "), "N foreground check failure: " + e.getMessage());
        }

        System.out.println(failures == 0 ? "PASS " + checks + " checks" : "FAILED " + failures + "/" + checks);
        System.exit(failures == 0 ? 0 : 1);
    }
}
