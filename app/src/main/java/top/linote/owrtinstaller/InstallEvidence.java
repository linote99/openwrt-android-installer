package top.linote.owrtinstaller;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * backend/install_evidence.py, ported: proves that the partition named by the GPT really holds this installer's
 * OpenWrt (boot evidence in the other slot plus marker files read straight from the ext4 metadata, never mounted).
 * Used when the phone has no original install record for the device ("no original backups" route).
 */
final class InstallEvidence {
    private InstallEvidence() {}

    interface Reader { byte[] read(long offset, int length) throws IOException; }

    static final class Failed extends IOException {
        Failed(String m) { super("Cannot verify the installed OpenWrt partition without original backups / 没有原备份，且无法核实设备上的 OpenWrt 安装；已停止: " + m); }
    }

    private static int u16(byte[] b, int p) { return (b[p] & 0xff) | (b[p + 1] & 0xff) << 8; }
    private static long u32(byte[] b, int p) { return Mu300Boot.u32le(b, p) & 0xffffffffL; }

    /** Ext4Evidence: bounded raw reads of an ext4 image's metadata (4 MiB budget). */
    static final class Ext4 {
        final Reader raw; final long capacity;
        long budget = 4L << 20;
        final Map<String, byte[]> cache = new HashMap<>();
        byte[] sb; long bs, blocks, inodes, perGroup; int isz, desc; long incompat;

        Ext4(Reader raw, long capacity) throws IOException {
            this.raw = raw; this.capacity = capacity;
            sb = rd(1024, 1024);
            if (sb[56] != 'S' || (sb[57] & 0xff) != 0xef) throw new IllegalArgumentException("OpenWrt partition is not ext4 / OpenWrt 分区不是 ext4");
            long log = u32(sb, 24); incompat = u32(sb, 96); int state = u16(sb, 58);
            if (log > 2 || (incompat & ~0x22C2L) != 0 || state != 1)
                throw new IllegalArgumentException("OpenWrt filesystem is unclean or unsupported; boot and shut it down cleanly first / OpenWrt 文件系统未正常卸载或布局不受支持，请先启动 OpenWrt 并正常切回 Android");
            bs = 1024L << log;
            blocks = u32(sb, 4) | ((incompat & 0x80) != 0 ? u32(sb, 336) << 32 : 0);
            inodes = u32(sb, 0); perGroup = u32(sb, 40); isz = u16(sb, 88);
            desc = (incompat & 0x80) != 0 ? u16(sb, 254) : 32;
            boolean zero = true;
            for (int i = 104; i < 120; i++) if (sb[i] != 0) zero = false;
            if (blocks <= 0 || blocks * bs > capacity || inodes <= 0 || perGroup <= 0 || isz < 128 || isz > bs
                    || Integer.bitCount(isz) != 1 || !(desc == 32 || desc == 64) || zero)
                throw new IllegalArgumentException("Invalid OpenWrt filesystem geometry or identity / OpenWrt 文件系统几何参数或身份无效");
        }

        byte[] rd(long off, int size) throws IOException {
            String key = off + ":" + size;
            byte[] c = cache.get(key);
            if (c != null) return c;
            if (off < 0 || size < 0 || off + size > capacity || (blocks > 0 && off + size > blocks * bs))
                throw new IllegalArgumentException("Filesystem read exceeds the OpenWrt partition / 文件系统读取超出 OpenWrt 分区");
            budget -= size;
            if (budget < 0) throw new IllegalArgumentException("Installation evidence exceeds the metadata read limit / 安装证据超过元数据读取上限");
            byte[] d = raw.read(off, size);
            if (d == null || d.length != size) throw new IllegalArgumentException("Incomplete OpenWrt partition read / OpenWrt 分区读取不完整");
            cache.put(key, d);
            return d;
        }
        byte[] block(long n) throws IOException {
            if (n < 0 || n >= blocks) throw new IllegalArgumentException("Invalid OpenWrt metadata block / OpenWrt 元数据块无效");
            return rd(n * bs, (int) bs);
        }
        byte[] inode(long n) throws IOException {
            if (n < 1 || n > inodes) throw new IllegalArgumentException("Invalid OpenWrt inode / OpenWrt inode 无效");
            long g = (n - 1) / perGroup, i = (n - 1) % perGroup;
            long descOff = (bs == 1024 ? 2 : 1) * bs + g * desc;
            byte[] d = rd(descOff, desc);
            long table = u32(d, 8) | (desc == 64 ? u32(d, 40) << 32 : 0);
            if (table <= 0 || table >= blocks) throw new IllegalArgumentException("Invalid OpenWrt inode table / OpenWrt inode 表无效");
            return rd(table * bs + i * isz, isz);
        }
        void extents(byte[] node, int off, int len, Integer expected, TreeMap<Long, Long> ptrs, long count, Set<Long> visited) throws IOException {
            int magic = u16(node, off), used = u16(node, off + 2), max = u16(node, off + 4), depth = u16(node, off + 6);
            if (magic != 0xF30A || used <= 0 || used > max || max > (len - 12) / 12 || depth > 4 || (expected != null && expected != depth))
                throw new IllegalArgumentException("Invalid OpenWrt extent tree / OpenWrt extent 树无效");
            for (int k = 0; k < used; k++) {
                int e = off + 12 + 12 * k;
                long logical = u32(node, e);
                if (depth > 0) {
                    long b = u32(node, e + 4) | (long) u16(node, e + 8) << 32;
                    if (!visited.add(b)) throw new IllegalArgumentException("Repeated OpenWrt extent node / OpenWrt extent 节点重复");
                    byte[] child = block(b);
                    extents(child, 0, child.length, depth - 1, ptrs, count, visited);
                } else {
                    int length = u16(node, e + 4);
                    long phys = (long) u16(node, e + 6) << 32 | u32(node, e + 8);
                    if (length <= 0 || length > 32768) throw new IllegalArgumentException("Unwritten OpenWrt evidence extent / OpenWrt 证据 extent 未写入");
                    if (phys <= 0 || phys + length > blocks) throw new IllegalArgumentException("OpenWrt extent exceeds the filesystem / OpenWrt extent 超出文件系统");
                    for (long n = logical; n < Math.min(logical + length, count); n++) {
                        if (ptrs.containsKey(n)) throw new IllegalArgumentException("Overlapping OpenWrt extents / OpenWrt extent 重叠");
                        ptrs.put(n, phys + n - logical);
                    }
                }
            }
        }
        byte[] contents(byte[] inode, int kind, long limit) throws IOException {
            int mode = u16(inode, 0);
            if ((mode & UninstallBoot.S_IFMT) != kind || u16(inode, 26) == 0)
                throw new IllegalArgumentException("Installation evidence must use regular files and directories, without symlinks / 安装标记路径必须是普通目录和文件，不能是符号链接");
            long size = u32(inode, 4) | u32(inode, 108) << 32;
            if (size <= 0 || size > limit) throw new IllegalArgumentException("Installation evidence file is empty or too large / 安装证据文件为空或过大");
            long count = (size + bs - 1) / bs;
            long flags = u32(inode, 32);
            if ((flags & 0x10100004L) != 0) throw new IllegalArgumentException("Unsupported OpenWrt evidence inode flags / OpenWrt 证据 inode 标志不受支持");
            TreeMap<Long, Long> ptrs = new TreeMap<>();
            if ((flags & 0x80000L) == 0) {
                if (count > 12) throw new IllegalArgumentException("Unsupported indirect evidence file / 不支持间接块的证据文件");
                for (int n = 0; n < count; n++) ptrs.put((long) n, u32(inode, 40 + 4 * n));
            } else {
                extents(inode, 40, 60, null, ptrs, count, new HashSet<>());
            }
            if (ptrs.size() != count || (count > 0 && (ptrs.firstKey() != 0 || ptrs.lastKey() != count - 1)))
                throw new IllegalArgumentException("Installation evidence contains sparse or missing blocks / 安装证据含稀疏或缺失的数据块");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (long n = 0; n < count; n++) { byte[] b = block(ptrs.get(n)); out.write(b, 0, b.length); }
            return Arrays.copyOf(out.toByteArray(), (int) size);
        }
        byte[] file(String path) throws IOException {
            byte[] node = inode(2);
            for (String comp : path.split("/")) {
                byte[] dir = contents(node, UninstallBoot.S_IFDIR, 262144);
                int at = 0, found = 0; long ino = 0;
                while (at < dir.length) {
                    if (dir.length - at < 8) throw new IllegalArgumentException("Truncated OpenWrt directory / OpenWrt 目录不完整");
                    long i = u32(dir, at); int rec = u16(dir, at + 4), nl = dir[at + 6] & 0xff;
                    if (rec < 8 || rec % 4 != 0 || rec > bs - at % bs || at + rec > dir.length || nl > rec - 8)
                        throw new IllegalArgumentException("Invalid OpenWrt directory entry / OpenWrt 目录项无效");
                    if (i != 0 && new String(dir, at + 8, nl, StandardCharsets.UTF_8).equals(comp)) { found++; ino = i; }
                    at += rec;
                }
                if (found != 1) throw new IllegalArgumentException("Missing or ambiguous installed-system marker / 安装标记缺失或存在重名: " + path);
                node = inode(ino);
            }
            return contents(node, UninstallBoot.S_IFREG, 4096);
        }
    }

    static final Pattern RELEASE = Pattern.compile("(?m)^DISTRIB_ID=['\"]OpenWrt['\"]$");

    /**
     * install_evidence.inspect_installation's checks on data already read: other-slot boot header/tail, its ramdisk,
     * and raw partition reads. Returns the evidence map (version, hashes, markers) or throws Failed.
     */
    static LinkedHashMap<String, Object> inspect(String family, String disk, String otherSlot, long bootSize, byte[] header, byte[] tail,
                                                 byte[] ramdiskPages, Reader part, long capacity) throws Failed {
        try {
            UninstallBoot.LinuxBoot lb = UninstallBoot.inspectLinuxBoot(header, tail, bootSize, family, otherSlot);
            if (ramdiskPages == null || ramdiskPages.length < lb.ramdisk) throw new IllegalArgumentException("Incomplete installed ramdisk / 已安装 ramdisk 不完整");
            byte[] ram = Arrays.copyOf(ramdiskPages, (int) lb.ramdisk);
            Map<String, UninstallBoot.Entry> all = UninstallBoot.ramdiskFiles(ram);
            Map<String, byte[]> files = new HashMap<>();
            for (String w : new String[]{"init", "etc/mu300-desktop-prepare", "etc/mu300-desktop-identity", "etc/mu300-desktop-root-mode"}) {
                UninstallBoot.Entry e = all.get(w);
                if (e == null) continue;
                if (!e.regular() || e.data.length > 262144) throw new IllegalArgumentException("Unsupported installed boot evidence / 已安装启动证据不受支持");
                files.put(w, e.data);
            }
            byte[] init = files.containsKey("init") ? files.get("init") : new byte[0];
            LinkedHashMap<String, String> paths = new LinkedHashMap<>();
            if ("e5".equals(family)) {
                if (!contains(init, "/etc/e5/part-root"))
                    throw new IllegalArgumentException("Installed E5 boot does not recognize internal partitions / 已安装的 E5 启动镜像不支持内置分区");
                paths.put("etc/e5/part-root", null);
                paths.put("etc/openwrt_release", null);
            } else {
                String mode = files.containsKey("etc/mu300-desktop-root-mode") ? new String(files.get("etc/mu300-desktop-root-mode"), StandardCharsets.UTF_8).trim() : "";
                if (!(mode.equals("part") || mode.equals("install-part") || files.containsKey("etc/mu300-desktop-prepare") || contains(init, "/etc/mu300-desktop-storage.sh")))
                    throw new IllegalArgumentException("Installed MU300 boot has no completed internal-partition route / 已安装的 MU300 启动镜像未确认完成内置分区安装");
                paths.put("openwrt/etc/mu300/desktop-mode", "part");
                paths.put(".mu300/boot-os", null);
                paths.put("openwrt/etc/openwrt_release", null);
            }
            Ext4 fs = new Ext4(part, capacity);
            TreeMap<String, String> markers = new TreeMap<>();
            for (Map.Entry<String, String> p : paths.entrySet()) {
                String value = new String(fs.file(p.getKey()), StandardCharsets.UTF_8).trim();
                if (p.getValue() != null && !p.getValue().equals(value))
                    throw new IllegalArgumentException("Installed marker differs / 安装标记内容不符: " + p.getKey());
                if (p.getKey().endsWith("openwrt_release") && !RELEASE.matcher(value).find())
                    throw new IllegalArgumentException("OpenWrt distribution identity is missing / 缺少 OpenWrt 系统身份信息");
                if (p.getKey().endsWith("part-root") && !(value.equals("e5root /dev/" + disk) || value.equals("e5root /dev/block/" + disk)))
                    throw new IllegalArgumentException("E5 partition marker names a different disk / E5 安装标记指向另一块磁盘");
                if (p.getKey().equals(".mu300/boot-os") && !(value.equals("android") || value.equals("openwrt")))
                    throw new IllegalArgumentException("MU300 boot selector is unsupported / MU300 启动选择标记不受支持");
                markers.put(p.getKey(), Mu300Boot.sha256(value.getBytes(StandardCharsets.UTF_8)));
            }
            LinkedHashMap<String, Object> r = new LinkedHashMap<>();
            r.put("version", 1L);
            r.put("boot_header_sha256", lb.headerSha);
            r.put("ramdisk_sha256", Mu300Boot.sha256(ram));
            r.put("superblock_sha256", Mu300Boot.sha256(fs.sb));
            r.put("markers", markers);
            return r;
        } catch (IOException | RuntimeException e) {
            throw new Failed(e.getMessage());
        }
    }

    private static boolean contains(byte[] hay, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + n.length <= hay.length; i++) {
            for (int k = 0; k < n.length; k++) if (hay[i + k] != n[k]) continue outer;
            return true;
        }
        return false;
    }
}
