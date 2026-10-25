package top.linote.owrtinstaller;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The MU300 package's computer-side internal-storage installs, ported from desktop/installer.py
 * (class Android, install(), direct_write(), free_write()):
 *  - PART:   independent partition "mu300root" in unallocated eMMC space (Android untouched), or, when no
 *            gap is large enough, an offline userdata shrink that preserves Android data (unencrypted only;
 *            the first boot resizes offline and reboots once into OpenWrt);
 *  - REPART: reinstall into an existing mu300root (erases only that partition);
 *  - IMAGE:  experimental userdata image (/data/mu300linux), unencrypted userdata only.
 * The destructive "format userdata" variant and its resume transaction are not ported.
 * The device-side scripts are the package's own desktop/*.sh and desktop/files/boot/*, run with the
 * same environment and arguments; only the transport (this app instead of adb on a computer) differs.
 */
final class Mu300Internal {
    private Mu300Internal() {}

    /** Target access. All root commands run on the target device; files stay binary-safe. */
    interface Io {
        /** Runs a short command and returns its output; throws IOException on a non-zero status. */
        String sh(String script, boolean root) throws IOException;
        /** Copies a remote file that the shell user can read into dest, byte for byte. */
        void pull(String remote, File dest) throws IOException;
        /** Copies local into remote (as the ADB shell user when possible; root locally). */
        void push(File local, String remote) throws IOException;
        void reboot() throws IOException;
        void log(String line);
        void progress(int percent);
        File cacheDir();
    }

    // ------------------------------------------------------------------ pinned, adapted files

    /** desktop/ files whose interface this port relies on (identical in both MU300 kernel builds). */
    static final String[][] PINNED = {
            {"desktop/android-bootchain.sh", "3a7fd552462f8e972787e0dc4b20b70c58246f62f42830b2628198ab97a8e600"},
            {"desktop/android-image.sh", "5434f27cf1e9552d807bcf61a8206408b64f2a6314282e9c6a60f9a0f4fb5191"},
            {"desktop/android-loop.sh", "4b886f574bfcbc48558dee23d37c1fbce4ddcac2e3d6b328527d9c0dfee71cf6"},
            {"desktop/android-write-free.sh", "66f84af49644b212916f50aaba332804bd107868d75b39eb83ac69e2dde7c4d8"},
            {"desktop/android-write-part.sh", "289d607f359a95b1f48dd135a3ba532164df7e5f06b1ba42996a052cfbd1f738"},
            {"desktop/bootchain.sh", "3a7fd552462f8e972787e0dc4b20b70c58246f62f42830b2628198ab97a8e600"},
            {"desktop/files/boot/ext4-geometry.sh", "6ee8b5b429cae620f9c382bae14d127168bb9205aa82406bbca2f23aac7babc6"},
            {"desktop/files/boot/install-part.sh", "3679792a48129b0be7d8fcaac0eff5cb13e8cf94670769b04410fc348f7d7f7b"},
            {"desktop/files/boot/internal-ext4.sh", "6a151650f2dc76a27e0fd32ab9b637d97f3d396e8f06d54ede529b8d39aeb42c"},
            {"desktop/files/boot/mu300-gpt", "5c8fd0ab85714c360499c898a6b9551e0b689dc27efe0091cc8aab45c1c340aa"},
            {"desktop/files/boot/original-init", "43cf275b551ce1b35c69bc42650783e0734233cb83f76dd17a3fcd88267a8710"},
            {"desktop/files/boot/part-prepare.sh", "5086af4be59c320da21fef65b6ae164ba263a8805e1c16c2c45cde7ffd004d5e"},
            {"desktop/files/boot/part-progress.sh", "0ded2ac1b99010ff639255b0d2c5725d36e1c5411a07fed7fb9808dd3d336159"},
            {"desktop/files/boot/repair/manifest.json", "b3988894aa88560c73f6e07d9809df8b7fd4815697a5cbb607c7b6fcdcd1bdfc"},
            {"desktop/files/boot/storage.sh", "0f1b30737b8749af275c61ee21d835d9f858d4347935fa062ccf6020ab48b4e1"},
    };

    /** payload/ files prepare_payload() uploads for the internal methods (rootfs is filtered separately). */
    static final String[] PAYLOAD = {"busybox", "switch.sh", "mu300-vendor-from-device.sh", "rootfs-critical.sha256",
            "rootfs.requirements"};
    static final String[] DESKTOP_SH = {"android-image.sh", "android-write-part.sh", "android-write-free.sh", "android-loop.sh"};
    static final String REPAIR = "desktop/files/boot/repair/";
    static final String LOCAL_SUMS = "LOCAL-SUMS";
    static final String ROOTFS = "payload/mu300-openwrt.tar.gz";
    static final String BOOT = "payload/boot-linux.img";

    /** Null when the package carries exactly the adapted desktop files; otherwise the reason. */
    static String pinnedProblem(Map<String, String> listed) {
        for (String[] p : PINNED) {
            String h = listed.get(p[0]);
            if (h == null) return "安装包缺少 " + p[0];
            if (!h.equals(p[1])) return p[0] + " 与本应用适配的版本不同";
        }
        boolean repair = false;
        for (String k : listed.keySet()) if (k.startsWith(REPAIR) && !k.equals(REPAIR + "manifest.json")) repair = true;
        return repair ? null : "安装包缺少 desktop/files/boot/repair 工具";
    }

    static boolean wanted(String rel) {
        if (rel.startsWith(REPAIR)) return true;
        for (String[] p : PINNED) if (p[0].equals(rel)) return true;
        return false;
    }

    // ------------------------------------------------------------------ app-side copy

    interface Progress { void step(String what, int percent); }

    /**
     * Builds the app-side copy the internal methods upload: listed files copied with their hash re-checked,
     * the public rootfs filtered like public_rootfs(), and the desktop boot builder exercised like check_boot().
     * LOCAL-SUMS records the expected hash of every file (manifest hash, or the filtered archive's own hash).
     */
    static void prepareLocal(File pkg, File desktop, File out, Map<String, String> listed, Progress pr) throws IOException {
        deleteTree(out);
        if (!out.mkdirs()) throw new IOException("cannot create " + out);
        StringBuilder sums = new StringBuilder();
        List<String> names = new ArrayList<>();
        for (String n : PAYLOAD) names.add("payload/" + n);
        names.add(BOOT);
        for (String k : listed.keySet()) if (wanted(k)) names.add(k);
        int i = 0;
        for (String rel : names) {
            String want = listed.get(rel);
            if (want == null) throw new IOException("清单缺少 " + rel);
            String got = copy(new File(rel.startsWith("payload/") ? pkg : desktop, rel), new File(out, rel));
            if (!got.equals(want)) throw new IOException("复制后校验失败：" + rel);
            sums.append(want).append("  ").append(rel).append('\n');
            if (pr != null) pr.step("准备内置安装文件", 5 + 25 * (++i) / names.size());
        }
        File tgz = new File(out, ROOTFS);
        MessageDigest md = sha();
        long total = new File(pkg, ROOTFS).length();
        try (InputStream in = new FileInputStream(new File(pkg, ROOTFS));
             OutputStream o = new DigestOutputStream(new FileOutputStream(tgz), md)) {
            RootfsFilter.filter(in, o, done -> {
                if (pr != null && total > 0) pr.step("剔除构建机基带运行时与非公开固件", 30 + (int) (60 * done / total));
            });
        }
        sums.append(hex(md.digest())).append("  ").append(ROOTFS).append('\n');
        writeAll(new File(out, LOCAL_SUMS), sums.toString().getBytes(StandardCharsets.UTF_8));
        if (pr != null) pr.step("检查启动镜像生成", 92);
        checkBoot(out);
    }

    /** check_boot(): build sd / install-part images from synthetic A/B data; no device involved. */
    static void checkBoot(File local) throws IOException {
        byte[] base = readAll(new File(local, BOOT));
        Mu300Boot.Assets assets = assets(local);
        String[][] cases = {{"a", "sd"}, {"b", "install-part"}};
        for (String[] c : cases) {
            byte[] bc = new byte[32];
            bc[0] = '_'; bc[1] = (byte) c[0].charAt(0); bc[4] = 'B'; bc[5] = 'C'; bc[6] = 'A'; bc[7] = 'B'; bc[8] = 1; bc[9] = 2;
            if ("a".equals(c[0])) { bc[12] = (byte) 0x9f; bc[14] = 0x1e; } else { bc[12] = 0x1e; bc[14] = (byte) 0x9f; }
            Mu300Boot.put32le(bc, 28, Mu300Boot.crc32(bc, 0, 28));
            byte[] image = Mu300Boot.build(base, assets, c[1], bc, c[0], null, null);
            Mu300Boot.selfCheck(base, image);
        }
    }

    static Mu300Boot.Assets assets(File local) {
        return rel -> readAll(new File(local, "desktop/files/boot/" + rel));
    }

    static Map<String, String> localSums(File local) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        File f = new File(local, LOCAL_SUMS);
        if (!f.isFile()) throw new IOException("内置安装文件尚未准备好，请重新选择安装包");
        for (String line : new String(readAll(f), StandardCharsets.UTF_8).split("\n")) {
            if (line.length() < 67) continue;
            m.put(line.substring(66), line.substring(0, 64));
        }
        return m;
    }

    // ------------------------------------------------------------------ device facts

    static final class Storage {
        String node, cid, state = "", type = "", source = "", magic = "", ext = "", root;
        long start, size, diskSize, free;
        String disk() { return "/dev/block/" + node.substring(0, node.indexOf('p')); }
    }

    /** What the read-only check found; sizes in MiB. */
    static final class Plan {
        String profile, model, slot, linux;
        Storage s;
        Mu300Gpt.Space space;          // PART only
        boolean encrypted;
        String encryptedNote;          // why preservation is impossible / unknown
        long freeMax, shrinkMax, shrinkCap, maximum, rootMib;
        long min = 2048, def = 4096;
        String summary;
    }

    private static final Pattern NODE = Pattern.compile("mmcblk\\d+p\\d+");
    private static final Pattern CID = Pattern.compile("[a-f0-9]{32}");
    private static final Pattern NUM = Pattern.compile("\\d{1,20}");

    static Map<String, String> kv(String out) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String l : out.split("\n")) {
            int e = l.indexOf('=');
            if (e > 0) m.put(l.substring(0, e).trim(), l.substring(e + 1).trim());
        }
        return m;
    }

    static Storage storage(Io io) throws IOException {
        String out = io.sh("set -e; d=$(readlink -f /dev/block/by-name/userdata); n=${d##*/}; b=${n%p*}; "
                + "echo NODE=$n; echo CID=$(cat /sys/class/block/$b/device/cid); echo START=$(cat /sys/class/block/$n/start); "
                + "echo SIZE=$(cat /sys/class/block/$n/size); echo DISK_SIZE=$(cat /sys/class/block/$b/size); "
                + "echo FREE=$(df -Pk /data | tail -n1 | awk '{print $4}'); "
                + "echo STATE=$(getprop ro.crypto.state); echo TYPE=$(getprop ro.crypto.type); "
                + "echo SOURCE=$(awk '$2==\"/data\" {print $1;exit}' /proc/mounts); "
                + "echo MAGIC=$(dd if=\"$d\" bs=1 skip=1024 count=4 2>/dev/null | od -An -tx1 | tr -d \" \\n\"); "
                + "echo EXT=$(dd if=\"$d\" bs=1 skip=1080 count=2 2>/dev/null | od -An -tx1 | tr -d \" \\n\"); "
                + "for u in /sys/class/block/${b}p*/uevent; do if grep -qx PARTNAME=mu300root \"$u\"; then n=${u%/uevent}; echo ROOT=${n##*/}; fi; done", true);
        Map<String, String> r = kv(out);
        Storage s = new Storage();
        s.node = r.getOrDefault("NODE", "");
        s.cid = r.getOrDefault("CID", "");
        if (!NODE.matcher(s.node).matches() || !CID.matcher(s.cid).matches()) throw new IOException("无法识别 eMMC（Invalid eMMC identity）");
        s.start = num(r, "START"); s.size = num(r, "SIZE"); s.diskSize = num(r, "DISK_SIZE"); s.free = num(r, "FREE");
        s.state = r.getOrDefault("STATE", ""); s.type = r.getOrDefault("TYPE", ""); s.source = r.getOrDefault("SOURCE", "");
        s.magic = r.getOrDefault("MAGIC", ""); s.ext = r.getOrDefault("EXT", "");
        String root = r.get("ROOT");
        s.root = root != null && NODE.matcher(root).matches() ? root : null;
        return s;
    }

    private static long num(Map<String, String> r, String k) throws IOException {
        String v = r.get(k);
        if (v == null || !NUM.matcher(v).matches()) throw new IOException("无法读取存储信息：" + k);
        return Long.parseLong(v);
    }

    static Plan identify(Io io) throws IOException {
        Plan p = new Plan();
        String model = io.sh("getprop ro.product.model; getprop ro.product.device", true);
        String platform = io.sh("getprop ro.board.platform", true).trim();
        try { p.profile = Mu300Gpt.androidProfile(model, platform); }
        catch (IllegalArgumentException e) { throw new IOException("机型不符：" + e.getMessage()); }
        p.model = model.trim().replace('\n', '/');
        String slot = io.sh("getprop ro.boot.slot_suffix", true).trim();
        if (slot.startsWith("_")) slot = slot.substring(1);
        if (!"a".equals(slot) && !"b".equals(slot)) throw new IOException("无法识别 Android 槽位");
        p.slot = slot;
        p.linux = "a".equals(slot) ? "b" : "a";
        return p;
    }

    // ------------------------------------------------------------------ binary reads (binary_io.pull_binary)

    static byte[] read(Io io, String command, long expected) throws IOException {
        File f = File.createTempFile("mu300-read", ".bin", io.cacheDir());
        try {
            download(io, command, f, expected);
            return readAll(f);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    static long download(Io io, String command, File dest, long expected) throws IOException {
        long limit = 16L << 20;
        String dir = "/data/local/tmp/openwrt-read-" + token(12);
        String remote = dir + "/data.bin";
        String freeText = io.sh("df -Pk /data/local/tmp | tail -n1 | awk '{print $4}'", false).trim();
        if (!NUM.matcher(freeText).matches()) throw new IOException("无法读取临时空间");
        long free = Long.parseLong(freeText) * 1024;
        if (expected == 0 || expected < -1) throw new IOException("Invalid binary read size");
        if (free < (expected > 0 ? expected : limit) + (32L << 20)) throw new IOException("目标设备临时空间不足");
        io.sh("umask 077; mkdir " + q(dir), false);
        File partial = new File(dest.getPath() + ".partial");
        try {
            String[] meta = io.sh("set -e; set -o pipefail; (" + command + ") > " + remote + "; chmod 644 " + remote
                    + "; wc -c < " + remote + "; sha256sum " + remote + "; (" + command + ") | sha256sum", true).trim().split("\n");
            if (meta.length > 3) meta = java.util.Arrays.copyOfRange(meta, meta.length - 3, meta.length);   // local su merges stderr
            if (meta.length != 3 || !NUM.matcher(meta[0].trim()).matches()) throw new IOException("二进制读取校验信息无效");
            long size = Long.parseLong(meta[0].trim());
            String staged = first(meta[1]), source = first(meta[2]);
            if (size <= 0 || (expected > 0 && size != expected) || (expected < 0 && size > limit))
                throw new IOException("二进制读取大小不符");
            if (!staged.matches("[a-f0-9]{64}") || !source.equals(staged)) throw new IOException("二进制源数据校验不一致");
            io.pull(remote, partial);
            if (partial.length() != size) throw new IOException("二进制文件下载不完整");
            if (!hashFile(partial).equals(staged)) throw new IOException("二进制文件下载校验失败");
            if (dest.exists() && !dest.delete()) throw new IOException("cannot replace " + dest);
            if (!partial.renameTo(dest)) throw new IOException("cannot save " + dest);
            return size;
        } finally {
            try { io.sh("rm -f " + remote + "; rmdir " + dir, true); } catch (IOException ignored) { }
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
        }
    }

    private static String first(String line) {
        String t = line.trim();
        int sp = t.indexOf(' ');
        return sp < 0 ? t : t.substring(0, sp);
    }

    // ------------------------------------------------------------------ session on the target

    static final class Session {
        final Io io;
        final File local;
        final Map<String, String> sums;
        final String p, t, bb;
        final Plan plan;
        final String kernel;

        Session(Io io, File local, Plan plan, String kernel) throws IOException {
            this.io = io; this.local = local; this.plan = plan; this.kernel = kernel;
            sums = localSums(local);
            p = "/data/local/tmp/mu300-desktop-" + token(6);
            t = p + "/work";
            bb = p + "/busybox";
            io.sh("mkdir -p " + t + "; chown 2000:2000 " + p + " " + t + "; chmod 700 " + p, true);
        }

        /** push(): parent owned by shell, then the uploaded bytes are checked as root. */
        String push(File src, String name, String want) throws IOException {
            String remote = p + "/" + name;
            String parent = remote.substring(0, remote.lastIndexOf('/'));
            io.sh("mkdir -p " + q(parent) + "; chown 2000:2000 " + q(parent) + "; chmod 700 " + q(parent), true);
            io.push(src, remote);
            String got = first(io.sh("sha256sum " + q(remote), true));
            if (!got.equals(want)) throw new IOException("上传校验失败：" + name);
            return remote;
        }

        String pushLocal(String rel, String name) throws IOException {
            String want = sums.get(rel);
            if (want == null) throw new IOException("内置安装文件缺少 " + rel);
            File f = new File(local, rel);
            if (!hashFile(f).equals(want)) throw new IOException("本机暂存文件已变化：" + rel + "，请重新选择安装包");
            return push(f, name, want);
        }

        String put(byte[] data, String name) throws IOException {
            File f = File.createTempFile("mu300-put", ".bin", io.cacheDir());
            try {
                writeAll(f, data);
                return push(f, name, hex(sha().digest(data)));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }

        String put(String text, String name) throws IOException { return put(text.getBytes(StandardCharsets.UTF_8), name); }

        /** run(): the original wrapper script, executed detached so a dropped link never interrupts a write. */
        String run(String body) throws IOException {
            String script = "#!/system/bin/sh\nset -eu\nP=" + p + "\nT=" + t + "\nBB=" + bb
                    + "\nexport MU300_PAYLOAD_DIR=$P MU300_INSTALL_TMP=$T MU300_BUSYBOX=$BB MU300_KEEP_PAYLOAD=1\nmkdir -p \"$T/bin\"\n\"$BB\" --install -s \"$T/bin\"\nexport PATH=\"$T/bin:$PATH\"\n"
                    + body + "\n";
            String rs = put(script, "run.sh");
            String logf = p + "/run.log", rcf = p + "/run.rc";
            io.sh("rm -f " + logf + " " + rcf + "; : > " + logf + "; cd /; " + bb + " setsid " + bb + " sh -c "
                    + q(bb + " nsenter -t 1 -m " + bb + " sh " + rs + " >> " + logf + " 2>&1; echo $? > " + rcf)
                    + " </dev/null >/dev/null 2>&1 &", true);
            StringBuilder all = new StringBuilder();
            ByteArrayOutputStream pending = new ByteArrayOutputStream();
            long off = 0;
            int failures = 0;
            long started = System.currentTimeMillis();
            while (true) {
                String out;
                try {
                    out = io.sh("s=$(stat -c %s " + logf + " 2>/dev/null || echo 0); echo SIZE=$s; "
                            + "if [ -f " + rcf + " ]; then echo RC=$(cat " + rcf + "); fi; echo DATA; "
                            + "tail -c +" + (off + 1) + " " + logf + " 2>/dev/null | head -c 49152 | base64", true);
                    failures = 0;
                } catch (IOException e) {
                    if (++failures > 60) throw new IOException("与目标设备的连接中断，目标设备上的步骤可能仍在进行；请勿重复写入，稍后检查设备状态（" + e.getMessage() + "）");
                    sleep(5000);
                    continue;
                }
                int d = out.indexOf("DATA\n");
                Map<String, String> head = kv(d < 0 ? out : out.substring(0, d));
                byte[] chunk = d < 0 ? new byte[0] : java.util.Base64.getMimeDecoder().decode(out.substring(d + 5).trim());
                pending.write(chunk, 0, chunk.length);
                off += chunk.length;
                byte[] buf = pending.toByteArray();
                int last = -1;
                for (int i = buf.length - 1; i >= 0; i--) if (buf[i] == '\n') { last = i; break; }
                String rcText = head.get("RC");
                boolean done = rcText != null && chunk.length == 0;
                int upto = done ? buf.length : last + 1;
                if (upto > 0) {
                    for (String l : new String(buf, 0, upto, StandardCharsets.UTF_8).split("\n")) {
                        if (l.isEmpty()) continue;
                        all.append(l).append('\n');
                        io.log(l);
                    }
                    pending.reset();
                    pending.write(buf, upto, buf.length - upto);
                }
                if (done) {
                    String rc = rcText.trim();
                    if (!"0".equals(rc)) throw new IOException("目标设备上的步骤失败（退出码 " + rc + "）：" + lastLine(all));
                    return all.toString();
                }
                if (System.currentTimeMillis() - started > 6L * 3600 * 1000) throw new IOException("目标设备上的步骤超过 6 小时未结束");
                if (chunk.length < 49152) sleep(1500);
            }
        }

        /** prepare_payload(root, tf=False, with_rootfs=True). */
        void preparePayload() throws IOException {
            io.log("上传安装文件（已剔除构建机基带运行时与非公开固件）");
            String[] names = {"busybox", "android-bootchain.sh", "bootchain.sh", "switch.sh", "mu300-vendor-from-device.sh",
                    "rootfs-critical.sha256", "rootfs.requirements"};
            int i = 0;
            for (String n : names) {
                String rel = n.equals("android-bootchain.sh") || n.equals("bootchain.sh") ? "desktop/" + n : "payload/" + n;
                pushLocal(rel, n);
                io.progress(5 + 5 * (++i) / names.length);
            }
            pushLocal(ROOTFS, "mu300-openwrt.tar.gz");
            io.progress(40);
            io.sh("chmod 755 " + bb + " " + p + "/*.sh", true);
            for (String n : DESKTOP_SH) pushLocal("desktop/" + n, n);
            pushLocal("desktop/files/boot/ext4-geometry.sh", "ext4-geometry.sh");
            for (String rel : sums.keySet()) if (rel.startsWith(REPAIR)) pushLocal(rel, rel);
            io.sh("chmod -R u+rx " + p + "/desktop/files/boot/repair", true);
            io.progress(50);
            run("\"$BB\" sh \"$P/android-bootchain.sh\" check");
        }

        byte[] live() throws IOException {
            byte[] d = read(io, "dd if=/dev/block/by-name/misc bs=1 skip=2048 count=32 2>/dev/null", 32);
            if (d.length != 32) throw new IOException("Incomplete misc read");
            return d;
        }

        byte[] boot(String mode, byte[] live, String prepare, String identity) throws IOException {
            byte[] base = readAll(new File(local, BOOT));
            if (!hex(sha().digest(base)).equals(sums.get(BOOT))) throw new IOException("本机暂存的 boot-linux.img 已变化");
            byte[] image = Mu300Boot.build(base, assets(local), mode, live, plan.slot, prepare, identity);
            Mu300Boot.selfCheck(base, image);
            return image;
        }

        /** flash(): boot_<linux> with readback/restore, then the one-shot misc A/B block with readback/restore. */
        void flash(byte[] image, byte[] bc) throws IOException {
            put(image, "boot-desktop.img");
            put(bc, "misc-linux.bin");
            String out = run("[ \"$(getprop ro.boot.slot_suffix)\" = \"_" + plan.slot + "\" ]\n"
                    + "\"$BB\" sh \"$P/android-bootchain.sh\" sync \"$T/bootchain-backup\"\n"
                    + "dev=/dev/block/by-name/boot_" + plan.linux + "\n"
                    + "[ \"$(blockdev --getsize64 \"$dev\")\" -eq \"$(stat -c %s \"$P/boot-desktop.img\")\" ]\n"
                    + "dd if=\"$dev\" of=\"$T/boot-before.img\" bs=1048576\n"
                    + "dd if=\"$P/boot-desktop.img\" of=\"$dev\" bs=1048576 conv=fsync\n"
                    + "dd if=\"$dev\" of=\"$T/boot-check.img\" bs=1048576\n"
                    + "if ! cmp -s \"$P/boot-desktop.img\" \"$T/boot-check.img\"; then\n"
                    + "    dd if=\"$T/boot-before.img\" of=\"$dev\" bs=1048576 conv=fsync\n"
                    + "    dd if=\"$dev\" of=\"$T/boot-check.img\" bs=1048576\n"
                    + "    cmp -s \"$T/boot-before.img\" \"$T/boot-check.img\" || echo 'BOOT RESTORE FAILED; do not switch slots'\n"
                    + "    exit 1\n"
                    + "fi\n"
                    + "dd if=/dev/block/by-name/misc of=\"$T/misc-before.bin\" bs=1 skip=2048 count=32\n"
                    + "dd if=\"$P/misc-linux.bin\" of=/dev/block/by-name/misc bs=1 seek=2048 conv=notrunc,fsync\n"
                    + "dd if=/dev/block/by-name/misc of=\"$T/misc-check.bin\" bs=1 skip=2048 count=32\n"
                    + "if ! cmp -s \"$P/misc-linux.bin\" \"$T/misc-check.bin\"; then\n"
                    + "    dd if=\"$T/misc-before.bin\" of=/dev/block/by-name/misc bs=1 seek=2048 conv=notrunc,fsync\n"
                    + "    exit 1\n"
                    + "fi\n"
                    + "echo 'BOOT-VERIFIED; OpenWrt selected'");
            if (!out.contains("BOOT-VERIFIED")) throw new IOException("启动镜像写入未确认");
        }

        String settings(long imageMib, String mode) { return Mu300Gpt.settings(imageMib, mode, kernel, true); }

        String imageChecksum() throws IOException {
            String c = first(io.sh("sha256sum " + t + "/openwrt.ext4.new", true));
            if (!c.matches("[a-f0-9]{64}")) throw new IOException("Invalid image checksum");
            return c;
        }

        void cleanup() {
            try { io.sh("rm -rf " + p, true); } catch (IOException ignored) { }
        }
    }

    // ------------------------------------------------------------------ gpt(s) with signature screening

    static Mu300Gpt.Space gpt(Io io, Storage s) throws IOException {
        String disk = s.disk();
        byte[] primary = read(io, "dd if=" + disk + " bs=512 skip=1 count=1 2>/dev/null", 512);
        byte[] backup = read(io, "dd if=" + disk + " bs=512 skip=" + (s.diskSize - 1) + " count=1 2>/dev/null", 512);
        long[] ft;
        try { ft = Mu300Gpt.backupLayout(primary, backup, s.diskSize); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
        byte[] first = read(io, "dd if=" + disk + " bs=512 count=" + ft[0] + " 2>/dev/null", ft[0] * 512);
        byte[] last = read(io, "dd if=" + disk + " bs=512 skip=" + ft[1] + " count=" + (s.diskSize - ft[1]) + " 2>/dev/null",
                (s.diskSize - ft[1]) * 512);
        Mu300Gpt.Space info;
        try { info = Mu300Gpt.space(first, last, s.diskSize); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
        List<Mu300Gpt.Part> userdata = new ArrayList<>();
        for (Mu300Gpt.Part pt : info.parts) if (pt.name.equals("userdata")) userdata.add(pt);
        if (userdata.size() != 1 || userdata.get(0).start != s.start || userdata.get(0).end - userdata.get(0).start + 1 != s.size)
            throw new IOException("GPT 中的 userdata 与运行中的内核不一致；请先重启 Android");
        long end = 0;
        for (Mu300Gpt.Part pt : info.parts) end = Math.max(end, pt.end);
        java.util.TreeSet<Long> candidates = new java.util.TreeSet<>();
        candidates.add(Mu300Gpt.LEGACY_ROOT_SECTOR);
        candidates.add((end / 4096 + 1) * 4096);
        for (long[] g : info.gaps) candidates.add(g[0]);
        List<long[]> blocked = new ArrayList<>();
        for (long offset : candidates) {
            for (long[] g : info.gaps) {
                if (g[0] <= offset && offset + 4 <= g[0] + g[1]) {
                    byte[] raw = read(io, "dd if=" + disk + " bs=512 skip=" + offset + " count=4 2>/dev/null", 2048);
                    if (raw.length != 2048) throw new IOException("Incomplete free-space signature read");
                    boolean ext = (raw[1080] & 0xff) == 0x53 && (raw[1081] & 0xff) == 0xef;
                    boolean f2fs = (raw[1024] & 0xff) == 0x10 && (raw[1025] & 0xff) == 0x20 && (raw[1026] & 0xff) == 0xf5 && (raw[1027] & 0xff) == 0xf2;
                    if (ext || f2fs) blocked.add(g);
                }
            }
        }
        info.safeGaps = new ArrayList<>();
        for (long[] g : info.gaps) if (!blocked.contains(g)) info.safeGaps.add(g);
        long largest = 0;
        for (long[] g : info.safeGaps) largest = Math.max(largest, g[1]);
        info.largest = info.empty.isEmpty() ? 0 : largest;
        info.first = first;
        info.last = last;
        return info;
    }

    // ------------------------------------------------------------------ read-only check

    enum Kind { PART, REPART, IMAGE }

    static Plan check(Io io, Kind kind) throws IOException {
        io.log("  读取设备型号、平台与启动槽…");
        Plan p = identify(io);
        io.log("  设备：" + p.model + "（" + p.profile + "），Android 槽 " + p.slot + "，Linux 槽 " + p.linux);
        io.log("  读取 userdata、eMMC 与加密状态…");
        Storage s = storage(io);
        p.s = s;
        io.log("  userdata：/dev/block/" + s.node + "，" + s.size / 2048 + " MiB，可用 " + s.free / 1024 + " MiB，文件系统 " + fsName(s));
        io.log("  加密属性：ro.crypto.state=" + orDash(s.state) + "，ro.crypto.type=" + orDash(s.type) + (s.source.isEmpty() ? "" : "，挂载源 " + s.source));
        io.log("  eMMC：" + s.disk() + "，" + s.diskSize / 2048 + " MiB" + (s.root != null ? "；已有 mu300root：/dev/block/" + s.root : "；没有 mu300root 分区"));
        try {
            p.encrypted = Mu300Gpt.encrypted(s.state, s.type, s.source, "53ef".equals(s.ext) ? s.ext : s.magic);
            if (p.encrypted) p.encryptedNote = "userdata 已加密";
        } catch (IllegalStateException e) {
            p.encrypted = true;
            p.encryptedNote = "无法确认 userdata 的加密状态";
        }
        io.log("  加密判断：" + (p.encrypted ? p.encryptedNote + ("file".equals(s.type) ? "（文件级加密 FBE）" : "block".equals(s.type) ? "（全盘加密 FDE）" : "") : "未加密"));
        if (kind == Kind.REPART) {
            if (s.root == null) throw new IOException("没有已有的 mu300root 分区可重装");
            String cap = io.sh("cat /sys/class/block/" + s.root + "/size", true).trim();
            if (!NUM.matcher(cap).matches()) throw new IOException("无法读取 mu300root 容量");
            p.rootMib = Long.parseLong(cap) / 2048;
            p.summary = "重装 /dev/block/" + s.root + "（" + p.rootMib + " MiB）";
            return p;
        }
        if (kind == Kind.IMAGE) {
            if (p.encrypted) throw new IOException(p.encryptedNote + "，不能放置可启动镜像（启动 Linux 时无法解密读取 userdata 里的镜像）；请改用 TF 卡或独立内置分区");
            p.maximum = s.free / 1024 - 1024 - 256;
            if (p.maximum < p.min) throw new IOException("userdata 可用空间不足（至少需要约 " + (p.min + 1280) + " MiB）");
            p.def = Math.min(4096, p.maximum) / 4 * 4;
            p.summary = "userdata 可用 " + s.free / 1024 + " MiB";
            return p;
        }
        if (s.root != null) throw new IOException("已有 mu300root 分区：请选择“重装已有内置分区”");
        io.log("  读取并校验 GPT（主表与备份表）…");
        Mu300Gpt.Space space = gpt(io, s);
        p.space = space;
        io.log("  GPT：空闲分区表项 " + space.empty.size() + " 个，最大连续未分配 " + space.largest / 2048 + " MiB");
        if (space.find(Mu300Gpt.ROOT_NAME) != null)
            throw new IOException("GPT 中已有 mu300root：请重启 Android 刷新分区节点后选择“重装已有内置分区”");
        if (space.empty.isEmpty()) throw new IOException("GPT 没有空闲的分区表项；未做任何修改");
        p.freeMax = space.largest / 2048 / 4 * 4;
        p.shrinkMax = (s.size / 2048 - 2048) / 4 * 4;
        boolean shrinkOk = !p.encrypted && s.start % 2048 == 0;
        // Preserving shrink (as install() checks it): target/2 >= used KiB + 1 GiB reserve.
        long reserve = 2 * (s.size / 2 - s.free + 1048576);
        long shrinkCap = shrinkOk ? Math.max(0, Math.min(p.shrinkMax, (s.size - reserve) / 2048 / 4 * 4)) : 0;
        p.shrinkCap = shrinkCap;
        p.maximum = Math.max(p.freeMax, shrinkCap);
        if (p.maximum < p.min) {
            if (!shrinkOk && p.freeMax < p.min)
                throw new IOException("eMMC 没有足够的未分配空间（" + p.freeMax + " MiB），且" + (p.encryptedNote != null ? p.encryptedNote : "userdata 起点未对齐")
                        + "，无法保留数据缩容；请改用 TF 卡安装");
            throw new IOException("可用空间不足 2 GiB（未分配 " + p.freeMax + " MiB，可缩容 " + shrinkCap + " MiB）");
        }
        p.def = Math.min(4096, p.maximum) / 4 * 4;
        p.summary = "eMMC " + s.diskSize / 2048 + " MiB；未分配可用 " + p.freeMax + " MiB" + (shrinkOk ? "；可保留数据缩容至多 " + shrinkCap + " MiB"
                : "；" + (p.encryptedNote != null ? p.encryptedNote : "userdata 未对齐") + "，不能缩容");
        return p;
    }

    private static String fsName(Storage s) {
        if ("53ef".equals(s.ext)) return "ext4";
        if ("1020f5f2".equals(s.magic)) return "F2FS";
        return "未识别（签名 " + orDash(s.magic) + "）";
    }

    private static String orDash(String v) { return v == null || v.isEmpty() ? "-" : v; }

    /** True when a PART install of this size uses unallocated space (no shrink). */
    static boolean usesFreeSpace(Plan p, long mib) {
        return p.space != null && Mu300Gpt.freeRootRange(p.space, mib * 2048) >= 0;
    }

    // ------------------------------------------------------------------ install

    static final class Outcome {
        final boolean pending; final String message, next;
        Outcome(boolean pending, String message, String next) { this.pending = pending; this.message = message; this.next = next; }
    }

    static File backupDir(File root, Storage s) {
        return new File(root, new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + "-" + s.cid.substring(s.cid.length() - 8));
    }

    /** Android.backup(): identity, misc head, both GPT copies and the boot-chain partitions of both slots. */
    static void backup(Io io, File path, Storage s, String slot, String serial) throws IOException {
        if (path.exists() || !path.mkdirs()) throw new IOException("无法创建备份目录 " + path);
        Map<String, Object> id = new LinkedHashMap<>();
        id.put("NODE", s.node); id.put("CID", s.cid); id.put("START", s.start); id.put("SIZE", s.size); id.put("DISK_SIZE", s.diskSize);
        id.put("FREE", s.free); id.put("STATE", s.state); id.put("TYPE", s.type); id.put("SOURCE", s.source); id.put("MAGIC", s.magic);
        id.put("EXT", s.ext); if (s.root != null) id.put("ROOT", s.root); id.put("serial", serial); id.put("android_slot", slot);
        StringBuilder j = new StringBuilder("{\n");
        int k = 0;
        for (Map.Entry<String, Object> e : id.entrySet()) {
            Object v = e.getValue();
            j.append("  \"").append(e.getKey()).append("\": ")
                    .append(v instanceof Long ? v.toString() : "\"" + String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                    .append(++k < id.size() ? ",\n" : "\n");
        }
        writeAll(new File(path, "identity.json"), j.append("}\n").toString().getBytes(StandardCharsets.UTF_8));
        String disk = s.disk();
        byte[] primary = read(io, "dd if=" + disk + " bs=512 skip=1 count=1 2>/dev/null", 512);
        byte[] alternate = read(io, "dd if=" + disk + " bs=512 skip=" + (s.diskSize - 1) + " count=1 2>/dev/null", 512);
        long[] ft;
        try { ft = Mu300Gpt.backupLayout(primary, alternate, s.diskSize); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
        long front = ft[0], tail = ft[1];
        LinkedHashMap<String, String> items = new LinkedHashMap<>();
        items.put("misc-head.bin", "dd if=/dev/block/by-name/misc bs=4096 count=1");
        items.put("gpt-first.bin", "dd if=" + disk + " bs=512 count=" + front);
        items.put("gpt-last.bin", "dd if=" + disk + " bs=512 skip=" + tail + " count=" + (s.diskSize - tail));
        for (String n : new String[]{"boot", "uboot", "sml", "teecfg", "trustos", "vbmeta"})
            for (String sl : new String[]{"a", "b"}) items.put(n + "_" + sl + ".img", "cat /dev/block/by-name/" + n + "_" + sl);
        StringBuilder sums = new StringBuilder();
        for (Map.Entry<String, String> it : items.entrySet()) {
            String n = it.getKey();
            io.log("备份：" + n);
            long size;
            if (n.equals("misc-head.bin")) size = 4096;
            else if (n.equals("gpt-first.bin")) size = front * 512;
            else if (n.equals("gpt-last.bin")) size = (s.diskSize - tail) * 512;
            else {
                String v = io.sh("blockdev --getsize64 /dev/block/by-name/" + n.substring(0, n.length() - 4), true).trim();
                if (!NUM.matcher(v).matches()) throw new IOException("无法读取分区大小：" + n);
                size = Long.parseLong(v);
            }
            File f = new File(path, n);
            download(io, it.getValue() + " 2>/dev/null", f, size);
            sums.append(hashFile(f)).append("  ").append(n).append('\n');
        }
        byte[] first = readAll(new File(path, "gpt-first.bin")), last = readAll(new File(path, "gpt-last.bin"));
        if (first.length != front * 512 || last.length != (s.diskSize - tail) * 512) throw new IOException("Incomplete GPT backup");
        try { Mu300Gpt.space(first, last, s.diskSize); }
        catch (IllegalArgumentException e) { throw new IOException("GPT 备份校验失败：" + e.getMessage()); }
        writeAll(new File(path, "SHA256SUMS"), sums.toString().getBytes(StandardCharsets.UTF_8));
        writeAll(new File(path, "gpt-layout.json"), ("{\n  \"front_sectors\": " + front + ",\n  \"tail_start\": " + tail
                + ",\n  \"disk_sectors\": " + s.diskSize + "\n}\n").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Runs one internal install. The check is repeated on the device first (state may have changed since the
     * read-only check), and the chosen size is re-validated exactly as the original does.
     */
    interface PathSink { void path(String p); }

    static Outcome install(Io io, File local, String kernel, Kind kind, long sizeMib, File backupRoot, String serial,
                           Boolean expectFree, PathSink backupSink) throws IOException {
        Plan p = check(io, kind);
        Storage s = p.s;
        io.log("设备：" + p.model + "（" + p.profile + "），Android 槽 " + p.slot + "，Linux 槽 " + p.linux);
        if (kind == Kind.REPART) {
            Session x = new Session(io, local, p, kernel);
            x.preparePayload();
            Outcome o = directWrite(x, s, rootSectors(io, s));
            x.cleanup();
            return o;
        }
        long size;
        try { size = Mu300Gpt.imageMib(sizeMib, p.maximum); }
        catch (IllegalArgumentException e) { throw new IOException("大小超出范围：" + p.min + "–" + p.maximum + " MiB"); }
        if (kind == Kind.IMAGE) {
            io.log("实验性功能：userdata 镜像模式可能破坏 Android 的 F2FS 文件系统。");
            Session x = new Session(io, local, p, kernel);
            x.preparePayload();
            x.put(x.settings(size, "data-image"), "desktop-settings.env");
            io.progress(60);
            x.run("\"$BB\" sh \"$P/android-image.sh\"");
            io.progress(85);
            byte[] live = x.live();
            x.flash(x.boot("data-image", live, null, null), Mu300Boot.bootControl(live, p.slot));
            x.cleanup();
            return new Outcome(false, "userdata 镜像和启动镜像已安装并校验", "请手动重启设备进入 OpenWrt");
        }
        long offset = Mu300Gpt.freeRootRange(p.space, size * 2048);
        // The user confirmed one of the two paths (unallocated space vs. shrinking userdata); never switch silently.
        if (expectFree != null && expectFree != (offset >= 0))
            throw new IOException("设备分区状态与检查时不同（" + (offset >= 0 ? "现在可以使用未分配空间" : "未分配空间已不够，需要缩小 userdata")
                    + "）；未安装任何内容，请重新做只读检查");
        if (offset >= 0) {
            io.log("使用 eMMC 未分配空间 " + size + " MiB（起始扇区 " + offset + "），不缩容、不格式化 Android");
            File b = backupDir(backupRoot, s);
            backup(io, b, s, p.slot, serial);
            if (backupSink != null) backupSink.path(b.getAbsolutePath());
            io.log("备份已保存：" + b.getAbsolutePath());
            if (!java.util.Arrays.equals(readAll(new File(b, "gpt-first.bin")), p.space.first)
                    || !java.util.Arrays.equals(readAll(new File(b, "gpt-last.bin")), p.space.last))
                throw new IOException("检查期间 GPT 发生变化；未安装任何内容");
            Session x = new Session(io, local, p, kernel);
            x.preparePayload();
            Outcome o = freeWrite(x, s, p.space, offset, size * 2048);
            x.cleanup();
            return o;
        }
        // Offline shrink that preserves Android data.
        if (p.encrypted) throw new IOException((p.encryptedNote != null ? p.encryptedNote : "userdata 已加密") + "，无法保留数据缩容；请减小大小以使用未分配空间，或改用 TF 卡");
        if (s.start % 2048 != 0) throw new IOException("userdata 起点未按 1 MiB 对齐，不支持缩容");
        long[] tn;
        try { tn = Mu300Gpt.allocation(s.size, size); }
        catch (IllegalArgumentException e) { throw new IOException("每个系统至少需要 2 GiB"); }
        long target = tn[0];
        if (target / 2 < (s.size / 2 - s.free + 1048576))
            throw new IOException("缩小后的 userdata 放不下现有 Android 数据和 1 GiB 余量；请减小 OpenWrt 分区");
        io.log("离线缩小 userdata 并保留 Android 数据：userdata " + s.size / 2048 + " → " + target / 2048 + " MiB，OpenWrt " + size + " MiB");
        File b = backupDir(backupRoot, s);
        backup(io, b, s, p.slot, serial);
        if (backupSink != null) backupSink.path(b.getAbsolutePath());
        io.log("备份已保存：" + b.getAbsolutePath());
        Session x = new Session(io, local, p, kernel);
        byte[] live = x.live();
        x.preparePayload();
        x.put(x.settings(2048, "part-stage"), "desktop-settings.env");
        io.progress(60);
        x.run("\"$BB\" sh \"$P/android-image.sh\"");
        x.run("printf \"PART_MIB=" + size + "\\nDATA_MIB=" + target / 2048 + "\\nGROW_FS=1\\nFORMAT_DATA=0\\n\" > /data/mu300linux/part-install.conf\nsync");
        io.progress(85);
        String identity = s.cid + ":" + s.node + ":" + s.start + ":" + s.size + ":" + target;
        x.flash(x.boot("install-part", live, null, identity), Mu300Boot.bootControl(live, p.slot));
        io.log("重启后开始离线安装；进度可在 http://192.168.77.1:8089/progress.txt 查看");
        io.reboot();
        return new Outcome(true, "载荷校验通过，设备将离线缩容并安装",
                "首次开机缩容安装通常需数分钟，数据多或闪存慢时更久；完成后自动重启一次进入 OpenWrt，请保持供电");
    }

    private static long rootSectors(Io io, Storage s) throws IOException {
        String cap = io.sh("cat /sys/class/block/" + s.root + "/size", true).trim();
        if (!NUM.matcher(cap).matches()) throw new IOException("无法读取 mu300root 容量");
        return Long.parseLong(cap);
    }

    /** direct_write(): image built on the device, written into mu300root with the package's own script. */
    static Outcome directWrite(Session x, Storage s, long sectors) throws IOException {
        x.put(x.settings(2048, "part"), "desktop-settings.env");
        x.io.progress(60);
        x.run("\"$BB\" sh \"$P/android-image.sh\"");
        String checksum = x.imageChecksum();
        x.io.progress(75);
        x.run("\"$BB\" sh \"$P/android-write-part.sh\" /dev/block/" + s.root + " " + s.cid + " " + sectors + " " + checksum);
        x.io.progress(88);
        byte[] live = x.live();
        x.flash(x.boot("part", live, null, null), Mu300Boot.bootControl(live, x.plan.slot));
        return new Outcome(false, "独立分区已安装、扩容并校验：" + String.format(Locale.US, "%.2f GiB", sectors / 2097152.0), "请手动重启设备");
    }

    /** free_write(): new GPT entry + image written into the unallocated range, GPT read back independently. */
    static Outcome freeWrite(Session x, Storage s, Mu300Gpt.Space space, long offset, long sectors) throws IOException {
        byte[][] copies;
        try { copies = Mu300Gpt.addRoot(space.first, space.last, s.diskSize, offset, sectors, UUID.randomUUID()); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
        x.put(space.first, "gpt-before-first.bin");
        x.put(space.last, "gpt-before-last.bin");
        x.put(copies[0], "gpt-new-first.bin");
        x.put(copies[1], "gpt-new-last.bin");
        x.put(x.settings(2048, "part"), "desktop-settings.env");
        x.io.progress(60);
        x.run("\"$BB\" sh \"$P/android-image.sh\"");
        String checksum = x.imageChecksum();
        x.io.progress(75);
        String disk = s.disk();
        x.run("\"$BB\" sh \"$P/android-write-free.sh\" " + Mu300Gpt.envArgs(disk, s.cid, s.diskSize, offset, sectors, checksum, space.front, space.tail));
        byte[] f = read(x.io, "dd if=" + disk + " bs=512 count=" + space.front + " 2>/dev/null", space.front * 512);
        byte[] l = read(x.io, "dd if=" + disk + " bs=512 skip=" + space.tail + " count=" + (s.diskSize - space.tail) + " 2>/dev/null",
                (s.diskSize - space.tail) * 512);
        if (!java.util.Arrays.equals(f, copies[0]) || !java.util.Arrays.equals(l, copies[1]))
            throw new IOException("GPT 回读校验失败；未更改启动选择");
        x.io.progress(88);
        byte[] live = x.live();
        x.flash(x.boot("part", live, null, null), Mu300Boot.bootControl(live, x.plan.slot));
        return new Outcome(false, "已在未分配空间安装：" + String.format(Locale.US, "%.2f GiB", sectors / 2097152.0) + "，Android 数据保留",
                "请手动重启设备；新分区在重启后生效");
    }

    // ------------------------------------------------------------------ small utilities

    static String q(String s) { return Mu300Gpt.quote(s); }   // shlex.quote, as the original

    private static final SecureRandom RNG = new SecureRandom();

    static String token(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return hex(b);
    }

    static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String hex(byte[] d) {
        StringBuilder s = new StringBuilder();
        for (byte x : d) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    static String hashFile(File f) throws IOException {
        MessageDigest md = sha();
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[1 << 16];
            int r;
            while ((r = in.read(b)) > 0) md.update(b, 0, r);
        }
        return hex(md.digest());
    }

    static String copy(File from, File to) throws IOException {
        File parent = to.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdir " + parent);
        MessageDigest md = sha();
        try (InputStream in = new FileInputStream(from); OutputStream o = new FileOutputStream(to)) {
            byte[] b = new byte[1 << 16];
            int r;
            while ((r = in.read(b)) > 0) { md.update(b, 0, r); o.write(b, 0, r); }
        }
        return hex(md.digest());
    }

    static byte[] readAll(File f) throws IOException {
        long n = f.length();
        if (n > (80L << 20)) throw new IOException("file too large: " + f.getName());
        byte[] b = new byte[(int) n];
        try (InputStream in = new FileInputStream(f)) {
            int o = 0;
            while (o < b.length) {
                int r = in.read(b, o, b.length - o);
                if (r < 0) throw new IOException("short read " + f.getName());
                o += r;
            }
        }
        return b;
    }

    static void writeAll(File f, byte[] data) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdir " + parent);
        try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null && !java.nio.file.Files.isSymbolicLink(f.toPath())) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String lastLine(StringBuilder all) {
        String[] ls = all.toString().trim().split("\n");
        return ls.length == 0 ? "" : ls[ls.length - 1];
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
