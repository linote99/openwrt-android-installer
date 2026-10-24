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
        long rootSectors;
        boolean rebooted;
        String logText = "";
        int lastRc = 0;
        String failStep;            // simulate a failing device step by script name

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
                String remote = script.substring(b + 4, script.indexOf(';', b));
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
            if (script.startsWith("rm -f ") && script.contains("/run.log") && script.contains(" setsid ")) return launch(script);
            if (script.startsWith("s=$(stat -c %s ")) {
                m = Pattern.compile("tail -c \\+(\\d+) ").matcher(script);
                if (!m.find()) throw new AssertionError("bad poll");
                long off = Long.parseLong(m.group(1)) - 1;
                byte[] log = logText.getBytes(StandardCharsets.UTF_8);
                byte[] chunk = off >= log.length ? new byte[0] : Arrays.copyOfRange(log, (int) off, Math.min(log.length, (int) off + 49152));
                return "SIZE=" + log.length + "\nRC=" + lastRc + "\nDATA\n" + java.util.Base64.getMimeEncoder().encodeToString(chunk) + "\n";
            }
            throw new AssertionError("unexpected command: " + script);
        }

        /** The detached run.sh launcher: simulate the package script's effect from the arguments; never execute it. */
        String launch(String script) throws IOException {
            Matcher m = Pattern.compile(" sh (/data/local/tmp/mu300-desktop-[0-9a-f]{12})/run\\.sh ").matcher(script);
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
        @Override public void log(String line) { }
        @Override public void progress(int percent) { }
        @Override public File cacheDir() { return cache; }
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

        // C: encrypted userdata and no gap → refused
        d = device(cache, false, boot);
        d.state = "encrypted"; d.type = "file";
        String why = expectFail(d, Mu300Internal.Kind.PART);
        ok(why != null && why.contains("加密"), "C encrypted + no gap refused: " + why);
        why = expectFail(d, Mu300Internal.Kind.IMAGE);
        ok(why != null && why.contains("加密"), "C encrypted image refused");
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

        System.out.println(failures == 0 ? "PASS " + checks + " checks" : "FAILED " + failures + "/" + checks);
        System.exit(failures == 0 ? 0 : 1);
    }
}
