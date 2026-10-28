package top.linote.owrtinstaller;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The computer installer's offline-uninstall boot pieces, ported from backend/uninstall_boot.py (build) and
 * backend/boot_image.py (inspect_linux_boot, trial_control). Static port of the bytecode; every check and
 * message is kept. The worker script is the original backend's literal, shipped verbatim as an asset.
 */
final class UninstallBoot {
    private UninstallBoot() {}

    /** sha256 of assets/uninstall-worker.sh (backend uninstall_worker WORKER with the GROWTH functions). */
    static final String WORKER_SHA = "d60044caa3315f56ba69b01ac0da52ded743e541dfd930f62e17ba3134728526";
    static final int BOOT_BYTES = 64 << 20;
    static final int LZ4_MAGIC = 0x184C2102;
    static final int S_IFMT = 0170000, S_IFREG = 0100000, S_IFDIR = 0040000;

    static int limit(String family) {
        if ("e5".equals(family)) return 56 << 20;
        if ("mu300".equals(family)) return 48 << 20;
        throw new IllegalArgumentException("Unsupported uninstall family / 不支持的卸载机型系列");
    }

    static final class Entry {
        final byte[] data; final int mode;
        Entry(byte[] d, int m) { data = d; mode = m; }
        boolean regular() { return (mode & S_IFMT) == S_IFREG; }
    }

    private static int u32(byte[] b, int p) { return Mu300Boot.u32le(b, p); }

    /** uninstall_boot.archives / install_evidence: LZ4-legacy frames of a ramdisk, each decoded. */
    static List<byte[]> archives(byte[] ram) {
        List<byte[]> out = new ArrayList<>();
        int at = 0;
        long total = 0;
        while (at < ram.length) {
            if (ram.length - at < 4 || u32(ram, at) != LZ4_MAGIC) {
                boolean rest = true;
                for (int i = at; i < ram.length; i++) if (ram[i] != 0) { rest = false; break; }
                if (rest) break;
                throw new IllegalArgumentException("Unsupported uninstall ramdisk compression / 卸载启动镜像压缩格式不受支持");
            }
            at += 4;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            while (at + 4 <= ram.length && u32(ram, at) != LZ4_MAGIC) {
                long size = u32(ram, at) & 0xffffffffL;
                if (size == 0) { at += 4; break; }
                if (size > 9437184 || at + 4 + size > ram.length)
                    throw new IllegalArgumentException("Invalid uninstall ramdisk block / 卸载启动镜像数据块无效");
                byte[] blk = Lz4.decodeBlock(ram, at + 4, (int) size);
                total += blk.length;
                if (total > (192L << 20)) throw new IllegalArgumentException("Uninstall ramdisk is too large / 卸载启动镜像过大");
                buf.write(blk, 0, blk.length);
                at += 4 + (int) size;
            }
            out.add(buf.toByteArray());
        }
        return out;
    }

    /** newc entries of one archive, in order (later names win in the merged map). */
    static void entries(byte[] a, Map<String, Entry> into) {
        int at = 0;
        while (at + 110 <= a.length) {
            String magic = new String(a, at, 6, StandardCharsets.US_ASCII);
            if (!magic.equals("070701") && !magic.equals("070702")) break;
            long[] f = new long[13];
            for (int k = 0; k < 13; k++) f[k] = Long.parseLong(new String(a, at + 6 + 8 * k, 8, StandardCharsets.US_ASCII), 16);
            int mode = (int) f[1], nlen = (int) f[11];
            long size = f[6];
            if (nlen < 1 || at + 110 + nlen > a.length) throw new IllegalArgumentException("Truncated ramdisk archive / 启动镜像归档不完整");
            String name = new String(a, at + 110, nlen - 1, StandardCharsets.UTF_8);
            at = (at + 110 + nlen + 3) & ~3;
            if (size > a.length - at) throw new IllegalArgumentException("Truncated ramdisk archive / 启动镜像归档不完整");
            byte[] data = Arrays.copyOfRange(a, at, at + (int) size);
            at = (int) ((at + size + 3) & ~3L);
            if (name.equals("TRAILER!!!")) break;
            into.put(name, new Entry(data, mode));
        }
    }

    static Map<String, Entry> ramdiskFiles(byte[] ram) {
        Map<String, Entry> files = new LinkedHashMap<>();
        for (byte[] a : archives(ram)) entries(a, files);
        return files;
    }

    /** The ramdisk bytes of a v4 boot image (header ramdisk size). */
    static byte[] ramdisk(byte[] img) {
        long kernel = u32(img, 8) & 0xffffffffL, size = u32(img, 12) & 0xffffffffL;
        long start = 4096 + (kernel + 4095) / 4096 * 4096;
        if (start + size > img.length) throw new IllegalArgumentException("Invalid boot payload bounds");
        return Arrays.copyOfRange(img, (int) start, (int) (start + size));
    }

    /** uninstall_boot.build(base, family, files): the package's Linux boot with the offline worker as init. */
    static byte[] build(byte[] base, String family, LinkedHashMap<String, byte[]> callerFiles, byte[] worker) {
        int limit = limit(family);
        if (worker == null || !Mu300Boot.sha256(worker).equals(WORKER_SHA))
            throw new IllegalArgumentException("Offline uninstall worker differs / 离线卸载脚本校验不符");
        if (base == null || base.length != BOOT_BYTES || !new String(base, 0, 8, StandardCharsets.ISO_8859_1).equals("ANDROID!")
                || u32(base, 40) != 4 || !new String(base, base.length - 64, 4, StandardCharsets.ISO_8859_1).equals("AVBf"))
            throw new IllegalArgumentException("A verified 64 MiB boot v4 image is required for uninstall / 卸载需要经过校验的 64 MiB v4 启动镜像");
        long kernel = u32(base, 8) & 0xffffffffL, size = u32(base, 12) & 0xffffffffL;
        long start = 4096 + (kernel + 4095) / 4096 * 4096;
        long originalSize = E5Part.be64(base, base.length - 52), avb = E5Part.be64(base, base.length - 44),
                avbSize = E5Part.be64(base, base.length - 36);
        if (!(kernel > 0 && size > 0 && start + size <= avb && avb + avbSize <= limit && originalSize == avb))
            throw new IllegalArgumentException("Invalid boot payload bounds / 启动镜像数据范围无效");
        byte[] ram = Arrays.copyOfRange(base, (int) start, (int) (start + size));
        Map<String, Entry> files = ramdiskFiles(ram);
        Entry initEntry = files.get("init");
        if (initEntry == null) throw new IllegalArgumentException("Cannot find the packaged init script / 找不到安装包的 init 脚本");
        if (!initEntry.regular()) throw new IllegalArgumentException("Uninstall requires a regular init script / 卸载需要普通文件形式的 init 脚本");
        String init = new String(initEntry.data, StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (!init.startsWith("#!")) throw new IllegalArgumentException("Cannot find the packaged init script / 找不到安装包的 init 脚本");
        String anchor = "e5".equals(family) ? "\ntf_update_probe\n" : "\nROOT_OFFSET=";
        if (count(init, anchor) != 1)
            throw new IllegalArgumentException("Unsupported offline uninstall hook / 此安装包不支持离线卸载，请使用新版安装包");
        int begin = init.indexOf("persist() {");
        int end = begin >= 0 ? init.indexOf("\n}\n", begin) : -1;
        if (begin < 0 || end < 0) throw new IllegalArgumentException("Cannot disable boot log writes during uninstall / 卸载时无法停用启动日志写入");
        init = init.substring(0, begin) + "persist() { :; }" + init.substring(end + 2);
        String hook = "\ntouch /run/stay\n" + ("mu300".equals(family) ? "setup_usb_gadget\n" : "")
                + "exec /bin/busybox sh /openwrt-uninstall/worker.sh\n";
        init = init.substring(0, init.indexOf(anchor)) + hook;
        LinkedHashMap<String, byte[]> all = new LinkedHashMap<>();
        all.put("init", init.getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, byte[]> e : callerFiles.entrySet()) all.put(e.getKey(), e.getValue());
        all.put("openwrt-uninstall/worker.sh", worker);
        byte[] overlay = overlay(all);
        ByteArrayOutputStream newRam = new ByteArrayOutputStream();
        newRam.write(ram, 0, ram.length);
        newRam.write(overlay, 0, overlay.length);
        byte[] header = Arrays.copyOf(base, 4096);
        Mu300Boot.put32le(header, 12, newRam.size());
        int nul = 44;
        while (nul < 1580 && header[nul] != 0) nul++;
        byte[] cmd = (new String(header, 44, nul - 44, StandardCharsets.ISO_8859_1) + " openwrt.uninstall=1").getBytes(StandardCharsets.ISO_8859_1);
        if (cmd.length >= 1536) throw new IllegalArgumentException("Boot command line has no room for the uninstall guard / 启动参数没有空间写入卸载保护");
        Arrays.fill(header, 44, 1580, (byte) 0);
        System.arraycopy(cmd, 0, header, 44, cmd.length);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(header, 0, 4096);
        body.write(base, 4096, (int) (start - 4096));
        byte[] r = newRam.toByteArray();
        body.write(r, 0, r.length);
        while (body.size() % 4096 != 0) body.write(0);
        long newAvb = body.size();
        body.write(base, (int) avb, (int) avbSize);
        if (body.size() > limit) throw new IllegalArgumentException("Uninstall request would overlap boot diagnostics; nothing written / 卸载请求会覆盖启动诊断区，未写入");
        byte[] out = Arrays.copyOf(body.toByteArray(), base.length);
        System.arraycopy(base, base.length - 64, out, base.length - 64, 64);
        E5Part.putBe64(out, base.length - 52, newAvb);
        E5Part.putBe64(out, base.length - 44, newAvb);
        E5Part.putBe64(out, base.length - 36, avbSize);
        return out;
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) n++;
        return n;
    }
    private static int slashes(String s) { int n = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '/') n++; return n; }

    /** The overlay newc archive (dirs 0700, tools and init 0755, others 0600) as LZ4 legacy in 8 MiB chunks. */
    static byte[] overlay(LinkedHashMap<String, byte[]> files) {
        TreeSet<String> dirSet = new TreeSet<>();
        dirSet.add("openwrt-uninstall");
        for (String name : files.keySet()) {
            Mu300Boot.safeName(name);
            String[] parts = name.split("/");
            StringBuilder p = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                if (i > 0) p.append('/');
                p.append(parts[i]);
                dirSet.add(p.toString());
            }
        }
        List<String> dirs = new ArrayList<>(dirSet);
        Collections.sort(dirs, (a, b) -> slashes(a) != slashes(b) ? Integer.compare(slashes(a), slashes(b)) : a.compareTo(b));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int ino = 1;
        for (String d : dirs) Mu300Boot.cpioRecord(out, d, new byte[0], S_IFDIR | 0700, ino++, 0, 0);
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String n = e.getKey();
            boolean exec = n.equals("init") || n.startsWith("openwrt-uninstall/tools/");
            Mu300Boot.cpioRecord(out, n, e.getValue(), S_IFREG | (exec ? 0755 : 0600), ino++, 0, 0);
        }
        Mu300Boot.cpioRecord(out, "TRAILER!!!", new byte[0], 0, ino, 0, 0);
        return Lz4.legacy(out.toByteArray());
    }

    // ================================================================== boot_image.py
    static final class LinuxBoot {
        long kernel, ramdisk; String headerSha;
    }

    /** boot_image.inspect_linux_boot: header = first 4096 bytes, tail = last 512 bytes of the slot. */
    static LinuxBoot inspectLinuxBoot(byte[] header, byte[] tail, long size, String family, String slot) {
        int limit = limit(family);
        if (size < limit || size % 512 != 0) throw new IllegalArgumentException("Invalid boot partition size / 启动分区大小无效");
        if (header == null || header.length != 4096 || !new String(header, 0, 8, StandardCharsets.ISO_8859_1).equals("ANDROID!"))
            throw new IllegalArgumentException("Installed boot header is missing or incomplete / 已安装启动镜像头缺失或不完整");
        long kernel = u32(header, 8) & 0xffffffffL, ramdisk = u32(header, 12) & 0xffffffffL;
        if (u32(header, 40) != 4 || u32(header, 20) != 1584 || kernel == 0 || ramdisk == 0)
            throw new IllegalArgumentException("Unsupported installed boot format / 已安装启动镜像格式不受支持");
        int nul = -1;
        for (int i = 44; i < 1580; i++) if (header[i] == 0) { nul = i; break; }
        if (nul < 0) throw new IllegalArgumentException("Invalid installed boot command line / 已安装启动参数无效");
        for (int i = 44; i < nul; i++) if ((header[i] & 0x80) != 0) throw new IllegalArgumentException("Invalid installed boot command line / 已安装启动参数无效");
        String cmd = new String(header, 44, nul - 44, StandardCharsets.US_ASCII);
        List<String> words = new ArrayList<>();
        for (String w : cmd.trim().split("\\s+")) if (!w.isEmpty()) words.add(w);
        boolean recognized;
        if ("e5".equals(family)) recognized = words.contains("e5.part_v=2");
        else {
            boolean e5 = false;
            for (String w : words) if (w.startsWith("e5.")) e5 = true;
            recognized = words.contains("loglevel=5") && !e5;
        }
        if (!recognized) throw new IllegalArgumentException("No recognized " + family + " Linux boot in slot " + slot
                + " / 目标槽未识别到相应机型系列的 Linux 启动镜像");
        for (String w : words) if (w.startsWith("e5.part_prepare="))
            throw new IllegalArgumentException("Partition preparation is pending; resume the saved installation / 分区准备尚未完成，请从保存记录续装");
        if (words.contains("openwrt.uninstall=1"))
            throw new IllegalArgumentException("Uninstall is pending; resume its saved record instead of booting OpenWrt / 卸载尚未完成，请从保存记录继续，不要临时启动 OpenWrt");
        long end = 4096 + (kernel + 4095) / 4096 * 4096 + (ramdisk + 4095) / 4096 * 4096;
        if (tail == null || tail.length != 512 || !new String(tail, 448, 4, StandardCharsets.ISO_8859_1).equals("AVBf"))
            throw new IllegalArgumentException("Installed boot footer is missing / 已安装启动镜像尾部无效");
        long original = E5Part.be64(tail, 460), offset = E5Part.be64(tail, 468), length = E5Part.be64(tail, 476);
        if (original != end || offset != end || length <= 0 || offset + length > limit || offset + length > size - 64)
            throw new IllegalArgumentException("Installed boot payload bounds are invalid / 已安装启动镜像范围无效");
        LinuxBoot b = new LinuxBoot();
        b.kernel = kernel; b.ramdisk = ramdisk; b.headerSha = Mu300Boot.sha256(header);
        return b;
    }

    /** True when the 32-byte BCAB block has magic, version 1, 2 slots and a valid CRC. */
    static boolean bcab(byte[] bc) {
        return bc != null && bc.length == 32 && bc[4] == 'B' && bc[5] == 'C' && bc[6] == 'A' && bc[7] == 'B' && bc[8] == 1
                && (bc[9] & 7) == 2 && Mu300Boot.crc32(bc, 0, 28) == u32(bc, 28);
    }
    private static boolean healthy(byte[] bc, int cur) {
        return (bc[cur] & 128) != 0 && (bc[cur] & 15) != 0 && (bc[13] & 1) == 0 && (bc[15] & 1) == 0;
    }

    /** boot_image.trial_control: boot the other slot once; Android stays the fallback. */
    static byte[] trialControl(byte[] before, String slot) {
        if (!("a".equals(slot) || "b".equals(slot)) || !bcab(before))
            throw new IllegalArgumentException("Invalid current slot or BCAB format/CRC / 当前槽位或 misc 校验无效");
        int cur = "a".equals(slot) ? 12 : 14, opp = "a".equals(slot) ? 14 : 12;
        if (!healthy(before, cur))
            throw new IllegalArgumentException("Android slot is not healthy; finish Android setup/update first / Android 槽位未就绪，请先完成 Android 初始化或更新");
        if ((before[opp] & 15) > (before[cur] & 15))
            throw new IllegalArgumentException("Another slot has higher priority; finish or cancel the pending boot first / 另一槽位优先级较高，请先完成或取消待执行的启动");
        byte[] r = before.clone();
        r[0] = '_'; r[1] = (byte) ("a".equals(slot) ? 'b' : 'a'); r[2] = 0; r[3] = 0;
        r[cur] = (byte) ((before[cur] & 240) | 14);
        r[opp] = 47;
        Mu300Boot.put32le(r, 28, Mu300Boot.crc32(r, 0, 28));
        return r;
    }

    /** Uninstaller.arm android-bc / finish_boot desired: Android slot only, (159, 30) priorities. */
    static byte[] androidControl(byte[] control, String slot) {
        byte[] r = control.clone();
        r[0] = '_'; r[1] = (byte) slot.charAt(0); r[2] = 0; r[3] = 0;
        if ("a".equals(slot)) { r[12] = (byte) 159; r[14] = 30; } else { r[12] = 30; r[14] = (byte) 159; }
        Mu300Boot.put32le(r, 28, Mu300Boot.crc32(r, 0, 28));
        return r;
    }

    /** finish_boot: the live control after the offline removal must be a healthy Android-only state. */
    static void checkAndroidHealthy(byte[] bc, String slot) {
        int cur = "a".equals(slot) ? 12 : 14, opp = "a".equals(slot) ? 14 : 12;
        if (!bcab(bc) || !healthy(bc, cur))
            throw new IllegalArgumentException("Android boot control is not healthy / Android 启动控制尚未就绪");
        if ((bc[opp] & 15) > (bc[cur] & 15))
            throw new IllegalArgumentException("Another Android slot update is pending / 另一 Android 槽位存在待执行更新，请先处理");
    }
}
