package top.linote.owrtinstaller;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of the computer-side "uninstall and return space" planning (backend/uninstall.py prepare(),
 * device_layout(), android_volume(); backend/uninstall_growth.py eligible() / growth_tools()).
 * Everything here is read-only on the target. Writing happens in UninstallRun.
 */
final class Uninstall {
    private Uninstall() { }

    static final Pattern NUM = Pattern.compile("^[0-9]+$");
    static final Pattern CID = Pattern.compile("^[0-9a-f]{32}$");
    static final Pattern NODE = Pattern.compile("^mmcblk[0-9]p[0-9]+$");
    static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");
    static final long MIB4 = 4194304L;
    static final byte[] LINUX_FS_GUID = {(byte) 0xaf, 0x3d, (byte) 0xc6, 0x0f, (byte) 0x83, (byte) 0x84, 0x72, 0x47,
            (byte) 0x8e, 0x79, 0x3d, 0x69, (byte) 0xd8, 0x47, 0x7d, (byte) 0xe4};   // 0fc63daf-...-3d69d8477de4, bytes_le

    /** Refusal with the original's bilingual wording. */
    static final class Refused extends IOException {
        Refused(String m) { super(m); }
    }

    /** Package-side inputs: the verified Linux boot base, the worker and the maintenance tool sources. */
    static final class Pkg {
        String family;                    // "e5" or "mu300"
        byte[] base;                      // e5: files/boot.img ; mu300: payload/boot-linux.img (64 MiB v4)
        byte[] worker;                    // assets/uninstall-worker.sh (pinned hash)
        File repairDir;                   // mu300: app-side desktop/files/boot/repair/
        Map<String, String> repairSums;   // mu300: LOCAL-SUMS entries for repair files (rel -> sha)
        Source ext4Tar;                   // e5: files/ext4-tools.tar from the staged package (read only when needed)
        String rootName() { return "e5".equals(family) ? "e5root" : "mu300root"; }
    }
    interface Source { byte[] get() throws IOException; }

    /** adapter.probe() for uninstall: storage facts, slot, boot id, readiness. */
    static final class Device {
        Mu300Internal.Storage s;
        String slot, other, bootId, serial, profile, model;
        long rootStart, rootSize;          // ROOT_START / ROOT_SIZE from the running kernel
        boolean booted;
        String partitionNode() { return s.node.substring(0, s.node.indexOf('p')); }   // mmcblk0
    }

    static Device probe(Mu300Internal.Io io, String rootName, String profile) throws IOException {
        String out = io.sh("set -e; d=$(readlink -f /dev/block/by-name/userdata); n=${d##*/}; b=${n%p*}; "
                + "echo NODE=$n; echo CID=$(cat /sys/class/block/$b/device/cid); echo START=$(cat /sys/class/block/$n/start); "
                + "echo SIZE=$(cat /sys/class/block/$n/size); echo DISK_SIZE=$(cat /sys/class/block/$b/size); "
                + "echo FREE=$(df -Pk /data | tail -n1 | awk '{print $4}'); "
                + "echo STATE=$(getprop ro.crypto.state); echo TYPE=$(getprop ro.crypto.type); "
                + "echo SOURCE=$(awk '$2==\"/data\" {print $1;exit}' /proc/mounts); "
                + "for u in /sys/class/block/${b}p*/uevent; do if grep -qx PARTNAME=" + rootName + " \"$u\"; then r=${u%/uevent}; r=${r##*/}; "
                + "echo ROOT=$r; echo ROOT_START=$(cat /sys/class/block/$r/start); echo ROOT_SIZE=$(cat /sys/class/block/$r/size); fi; done; "
                + "echo SLOT=$(getprop ro.boot.slot_suffix); echo BOOTED=$(getprop sys.boot_completed); echo SETUP=$(settings get secure user_setup_complete 2>/dev/null); "
                + "echo MODEL=$(getprop ro.product.model); echo BOOT_ID=$(cat /proc/sys/kernel/random/boot_id)", true);
        Map<String, String> r = Mu300Internal.kv(out);
        Device d = new Device();
        Mu300Internal.Storage s = new Mu300Internal.Storage();
        s.node = r.getOrDefault("NODE", "");
        s.cid = r.getOrDefault("CID", "").toLowerCase(java.util.Locale.ROOT);
        if (!NODE.matcher(s.node).matches() || !CID.matcher(s.cid).matches()) throw new IOException("无法识别 eMMC（Invalid eMMC identity）");
        s.start = num(r, "START"); s.size = num(r, "SIZE"); s.diskSize = num(r, "DISK_SIZE"); s.free = num(r, "FREE");
        s.state = r.getOrDefault("STATE", ""); s.type = r.getOrDefault("TYPE", ""); s.source = r.getOrDefault("SOURCE", "");
        String root = r.get("ROOT");
        if (root != null && NODE.matcher(root).matches()) {
            s.root = root;
            d.rootStart = num(r, "ROOT_START"); d.rootSize = num(r, "ROOT_SIZE");
        }
        d.s = s;
        String slot = r.getOrDefault("SLOT", "");
        if (slot.startsWith("_")) slot = slot.substring(1);
        if (!"a".equals(slot) && !"b".equals(slot)) throw new IOException("无法识别 Android 槽位");
        d.slot = slot; d.other = "a".equals(slot) ? "b" : "a";
        // adapter.probe readiness: boot completed and the Android setup wizard finished.
        d.booted = "1".equals(r.getOrDefault("BOOTED", "")) && !"0".equals(r.getOrDefault("SETUP", "").trim());
        d.bootId = r.getOrDefault("BOOT_ID", "");
        d.model = r.getOrDefault("MODEL", "");
        d.profile = profile;
        return d;
    }
    private static long num(Map<String, String> r, String k) throws IOException {
        String v = r.get(k);
        if (v == null || !NUM.matcher(v).matches()) throw new IOException("无法读取存储信息：" + k);
        return Long.parseLong(v);
    }

    // ================================================================== GPT helpers (uninstall.py)

    static Mu300Gpt.Part partition(Mu300Gpt.Space info, String name) throws Refused {
        List<Mu300Gpt.Part> rows = new ArrayList<>();
        for (Mu300Gpt.Part p : info.parts) if (p.name.equals(name)) rows.add(p);
        if (rows.size() != 1) throw new Refused("Ambiguous or missing partition / 分区缺失或存在重名: " + name);
        return rows.get(0);
    }
    static Mu300Gpt.Space space(byte[] first, byte[] last, long sectors) throws Refused {
        try { return Mu300Gpt.space(first, last, sectors); }
        catch (IllegalArgumentException e) { throw new Refused(e.getMessage()); }
    }
    private static long u32(byte[] b, int p) { return Mu300Boot.u32le(b, p) & 0xffffffffL; }
    private static long u64(byte[] b, int p) { return u32(b, p) | (u32(b, p + 4) << 32); }
    private static void put64(byte[] b, int p, long v) { for (int i = 0; i < 8; i++) b[p + i] = (byte) (v >>> (8 * i)); }

    /** table(first): the primary entry array. */
    static byte[] table(byte[] first) {
        long at = u64(first, 584), count = u32(first, 592);
        return Arrays.copyOfRange(first, (int) (at * 512), (int) (at * 512 + count * 128));
    }
    /** with_table(): both GPT copies with the updated entry array and fresh CRCs, validated by gpt_space. */
    static byte[][] withTable(byte[] first, byte[] last, byte[] updated, long sectors) throws Refused {
        byte[][] out = new byte[2][];
        for (int k = 0; k < 2; k++) {
            byte[] raw = (k == 0 ? first : last).clone();
            int header = k == 0 ? 512 : raw.length - 512;
            int offset = k == 0 ? (int) (u64(first, 584) * 512) : 0;
            System.arraycopy(updated, 0, raw, offset, updated.length);
            Mu300Boot.put32le(raw, header + 88, Mu300Boot.crc32(updated, 0, updated.length));
            Mu300Boot.put32le(raw, header + 16, 0);
            int length = (int) u32(raw, header + 12);
            Mu300Boot.put32le(raw, header + 16, Mu300Boot.crc32(raw, header, length));
            out[k] = raw;
        }
        space(out[0], out[1], sectors);
        return out;
    }

    static final class Layout {
        byte[][] after; Mu300Gpt.Part data, root, target; boolean expands; Mu300Gpt.Space info;
    }
    /** device_layout(current, sectors, name): remove the OpenWrt entry; merge into userdata when adjacent. */
    static Layout deviceLayout(byte[] first, byte[] last, long sectors, String name) throws Refused {
        Mu300Gpt.Space info = space(first, last, sectors);
        Mu300Gpt.Part data = partition(info, "userdata"), root = partition(info, name);
        byte[] updated = table(first);
        int at = (root.n - 1) * 128;
        byte[] entry = Arrays.copyOfRange(updated, at, at + 128);
        boolean zeroId = true, zeroAttr = true;
        for (int i = 16; i < 32; i++) if (entry[i] != 0) zeroId = false;
        for (int i = 48; i < 56; i++) if (entry[i] != 0) zeroAttr = false;
        if (!Arrays.equals(Arrays.copyOfRange(entry, 0, 16), LINUX_FS_GUID) || zeroId || !zeroAttr
                || root.start % 2048 != 0 || (root.end + 1) % 2048 != 0 || root.end - root.start + 1 < 4194304L)
            throw new Refused("Unsupported OpenWrt partition type, identity or alignment / OpenWrt 分区类型、身份或对齐方式不受支持");
        byte[] guid = Arrays.copyOfRange(entry, 16, 32);
        for (Mu300Gpt.Part p : info.parts) {
            if (p.n == root.n) continue;
            int o = (p.n - 1) * 128;
            if (Arrays.equals(Arrays.copyOfRange(updated, o + 16, o + 32), guid))
                throw new Refused("OpenWrt partition GUID is duplicated / OpenWrt 分区 GUID 存在重复");
        }
        Layout l = new Layout();
        l.expands = root.start == data.end + 1;
        l.target = l.expands ? new Mu300Gpt.Part(data.n, data.start, root.end, data.name) : data;
        Arrays.fill(updated, at, at + 128, (byte) 0);
        put64(updated, (data.n - 1) * 128 + 40, l.target.end);
        l.after = withTable(first, last, updated, sectors);
        l.data = data; l.root = root; l.info = info;
        return l;
    }

    // ================================================================== android_volume / eligible

    static final class Volume {
        String source = "", filesystem = "unknown", uuid = "";
        long mappedBytes, filesystemBytes, mappingOverhead;
        Map<String, Object> map() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("source", source); m.put("mapped_bytes", mappedBytes); m.put("filesystem_bytes", filesystemBytes);
            m.put("filesystem", filesystem); m.put("uuid", uuid); m.put("mapping_overhead", mappingOverhead);
            return m;
        }
        static Volume of(Map<String, Object> m) {
            Volume v = new Volume();
            if (m == null) return v;
            v.source = str(m.get("source")); v.filesystem = str(m.get("filesystem")); v.uuid = str(m.get("uuid"));
            v.mappedBytes = lng(m.get("mapped_bytes")); v.filesystemBytes = lng(m.get("filesystem_bytes"));
            v.mappingOverhead = lng(m.get("mapping_overhead"));
            return v;
        }
    }

    static Volume androidVolume(Mu300Internal.Io io, Device d, boolean allowSmall) throws IOException {
        String source = io.sh("readlink -f " + Mu300Internal.q(d.s.source), true).trim();
        if (!source.matches("/dev/(?:block/)?(?:mmcblk\\d+p\\d+|dm-\\d+)"))
            throw new Refused("Cannot identify Android data mapping / 无法确认 Android data 映射设备");
        String base = source.substring(source.lastIndexOf('/') + 1);
        String sz = io.sh("cat /sys/class/block/" + base + "/size", true).trim();
        if (!NUM.matcher(sz).matches()) throw new Refused("Cannot identify Android data mapping / 无法确认 Android data 映射设备");
        long mapped = Long.parseLong(sz) * 512;
        byte[] raw = Mu300Internal.read(io, "dd if=" + source + " bs=1024 skip=1 count=1 2>/dev/null", 1024);
        if (raw.length != 1024) throw new Refused("Incomplete Android filesystem superblock / Android 文件系统超级块读取不完整");
        String kind; long size; int uuidAt;
        if ((raw[56] & 0xff) == 0x53 && (raw[57] & 0xff) == 0xef) {
            kind = "ext4"; uuidAt = 104;
            size = ext4Capacity(raw);
        } else if ((u32(raw, 0)) == 0xf2f52010L) {
            kind = "f2fs"; uuidAt = 108;
            if (u32(raw, 16) != 12) throw new Refused("Unsupported Android F2FS block size");
            size = u64(raw, 36) << 12;
        } else {
            throw new Refused("Android data is not a readable ext4/F2FS filesystem / 无法读取 Android data 的 ext4/F2FS 文件系统");
        }
        long userdata = d.s.size * 512;
        if (!(0 < size && size <= mapped && mapped <= userdata && (allowSmall || mapped - size <= MIB4)))
            throw new Refused("Android filesystem/mapping capacity differs / Android 文件系统或映射容量不符: filesystem="
                    + size + ", mapped=" + mapped + ", userdata=" + userdata + " bytes");
        Volume v = new Volume();
        v.source = source; v.mappedBytes = mapped; v.filesystemBytes = size; v.filesystem = kind;
        v.uuid = Mu300Internal.hex(Arrays.copyOfRange(raw, uuidAt, uuidAt + 16));
        v.mappingOverhead = userdata - mapped;
        return v;
    }
    /** service.verify_ext4_capacity(raw, 0): blocks (64-bit, unconditional hi word) * block size. */
    static long ext4Capacity(byte[] raw) throws Refused {
        long blocks = u32(raw, 4) | (u32(raw, 336) << 32);
        long shift = u32(raw, 24);
        if (shift > 6) throw new Refused("Invalid Android ext4 block size");
        long cap = blocks * (1024L << shift);
        if (cap <= 0) throw new Refused("Invalid Android ext4 capacity");
        return cap;
    }

    /** uninstall_growth.eligible(device, volume). */
    static void eligible(Device d, Volume v) throws Refused {
        boolean encrypted;
        // androidVolume() has already verified the filesystem superblock and identity; use
        // its filesystem kind as the equivalent verified magic for the encryption guard.
        String magic = "ext4".equals(v.filesystem) ? "53ef" : ("f2fs".equals(v.filesystem) ? "1020f5f2" : "");
        try { encrypted = Mu300Gpt.encrypted(d.s.state, d.s.type, d.s.source, magic); }
        catch (RuntimeException e) { encrypted = true; }
        if (!"unencrypted".equals(d.s.state) || encrypted || "file".equals(d.s.type) || "block".equals(d.s.type))
            throw new Refused("Android data is encrypted or its encryption state is unconfirmed / Android data 已加密或无法确认加密状态");
        String base = v.source.substring(v.source.lastIndexOf('/') + 1);
        if (!base.equals(d.s.node) || v.mappingOverhead != 0)
            throw new Refused("Android data uses a mapping; direct userdata growth is unproven / Android data 存在映射，无法确认可直接扩容 userdata");
        if (!("ext4".equals(v.filesystem) || "f2fs".equals(v.filesystem)) || v.uuid.isEmpty() || v.uuid.matches("0{32}"))
            throw new Refused("Filesystem type or identity is unsupported / 文件系统类型或身份不受支持");
    }

    /** uninstall_growth.growth_tools(adapter, filesystem): {"openwrt-uninstall/tools/<name>": content}. */
    @SuppressWarnings("unchecked")
    static LinkedHashMap<String, byte[]> growthTools(Pkg pkg, String filesystem) throws IOException {
        TreeMap<String, byte[]> files = new TreeMap<>();
        if ("mu300".equals(pkg.family)) {
            File folder = pkg.repairDir;
            byte[] manifestBytes = Mu300Internal.readAll(new File(folder, "manifest.json"));
            String pinned = pkg.repairSums == null ? null : pkg.repairSums.get(Mu300Internal.REPAIR + "manifest.json");
            if (pinned == null || !pinned.equals(Mu300Boot.sha256(manifestBytes))) throw new Refused("Maintenance checksum differs: manifest.json");
            Object parsed = MiniJson.parse(new String(manifestBytes, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map) || !(((Map<String, Object>) parsed).get("sha256") instanceof Map))
                throw new Refused("Unsafe maintenance tool path");
            for (Map.Entry<String, Object> e : ((Map<String, Object>) ((Map<String, Object>) parsed).get("sha256")).entrySet()) {
                String name = e.getKey();
                if (!safeRel(name)) throw new Refused("Unsafe maintenance tool path");
                File source = new File(folder, name);
                if (!source.isFile() || !source.getCanonicalPath().startsWith(folder.getCanonicalPath() + File.separator) || source.length() > 16777216)
                    throw new Refused("Unsafe maintenance tool file");
                byte[] content = Mu300Internal.readAll(source);
                if (!Mu300Boot.sha256(content).equals(String.valueOf(e.getValue()))) throw new Refused("Maintenance checksum differs: " + name);
                files.put(name, content);
            }
        } else {
            byte[] ram = UninstallBoot.ramdisk(pkg.base);
            for (byte[] archive : UninstallBoot.archives(ram)) {
                LinkedHashMap<String, UninstallBoot.Entry> entries = new LinkedHashMap<>();
                UninstallBoot.entries(archive, entries);
                for (Map.Entry<String, UninstallBoot.Entry> e : entries.entrySet()) {
                    String name = e.getKey();
                    if (!name.startsWith("repair/") || !e.getValue().regular() || name.equals("repair/manifest.json")) continue;
                    files.put(name.substring(7), e.getValue().data);
                }
            }
            if ("ext4".equals(filesystem)) {
                if (pkg.ext4Tar == null) throw new Refused("Package lacks compatible filesystem tools / 安装包缺少兼容的文件系统检查与扩容工具");
                byte[] tar = pkg.ext4Tar.get();
                for (int p = 0; p + 512 <= tar.length; ) {
                    boolean zero = true;
                    for (int i = 0; i < 512; i++) if (tar[p + i] != 0) { zero = false; break; }
                    if (zero) break;
                    String name = cstr(tar, p, 100);
                    String prefix = cstr(tar, p + 345, 155);
                    if (new String(tar, p + 257, 5, StandardCharsets.US_ASCII).equals("ustar") && !prefix.isEmpty()) name = prefix + "/" + name;
                    String octal = cstr(tar, p + 124, 12).trim();
                    long size = octal.isEmpty() ? 0 : Long.parseLong(octal, 8);
                    byte type = tar[p + 156];
                    int data = p + 512;
                    long next = data + (size + 511) / 512 * 512;
                    if (next > tar.length) throw new Refused("Unsafe ext4 maintenance archive");
                    if (name.startsWith("./")) name = name.substring(2);
                    if (type == '0' || type == 0) {
                        if (!safeRel(name) || size > 16777216) throw new Refused("Unsafe ext4 maintenance archive");
                        if (name.startsWith("lib/") || name.startsWith("usr/lib/") || name.startsWith("usr/sbin/"))
                            files.put(name, Arrays.copyOfRange(tar, data, (int) (data + size)));
                    }
                    p = (int) next;
                }
            }
        }
        String[] required = "f2fs".equals(filesystem) ? new String[]{"sbin/fsck.f2fs", "sbin/resize.f2fs"}
                : new String[]{"usr/sbin/e2fsck", "usr/sbin/resize2fs"};
        for (String n : required) if (!files.containsKey(n))
            throw new Refused("Package lacks compatible filesystem tools / 安装包缺少兼容的文件系统检查与扩容工具");
        if (!files.containsKey("lib/ld-musl-aarch64.so.1"))
            throw new Refused("Package lacks compatible filesystem tools / 安装包缺少兼容的文件系统检查与扩容工具");
        String[] libraries = "f2fs".equals(filesystem) ? new String[]{"libf2fs.so.10", "libuuid.so.1", "libgcc_s.so.1"}
                : new String[]{"libext2fs.so.2", "libe2p.so.2", "libcom_err.so.0", "libblkid.so.1", "libuuid.so.1", "libgcc_s.so.1"};
        for (String n : libraries) if (!files.containsKey("lib/" + n) && !files.containsKey("usr/lib/" + n))
            throw new Refused("Filesystem maintenance libraries are incomplete / 文件系统维护库不完整");
        files.put("lib/libc.so", files.get("lib/ld-musl-aarch64.so.1"));
        long total = 0;
        for (byte[] c : files.values()) total += c.length;
        if (total > 33554432L) throw new Refused("Maintenance bundle exceeds ramdisk limit");
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            byte[] c = e.getValue();
            if (c.length < 20 || c[0] != 0x7f || c[1] != 'E' || c[2] != 'L' || c[3] != 'F' || c[4] != 2 || c[5] != 1
                    || ((c[18] & 0xff) | (c[19] & 0xff) << 8) != 183)
                throw new Refused("Maintenance tool is not ARM64 ELF: " + e.getKey());
        }
        if ("f2fs".equals(filesystem)) {
            Matcher m = Pattern.compile("(?<![0-9])1\\.(\\d+)\\.(\\d+)(?![0-9])")
                    .matcher(new String(files.get("sbin/resize.f2fs"), StandardCharsets.ISO_8859_1));
            if (!m.find() || Integer.parseInt(m.group(1)) < 16)
                throw new Refused("F2FS growth tool compatibility is unconfirmed / F2FS 扩容工具版本兼容性未经确认");
        }
        LinkedHashMap<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) out.put("openwrt-uninstall/tools/" + e.getKey(), e.getValue());
        return out;
    }
    private static boolean safeRel(String name) {
        if (name.isEmpty() || name.startsWith("/")) return false;
        for (String part : name.split("/", -1)) if (part.equals("..")) return false;
        return true;
    }
    private static String cstr(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    // ================================================================== installation evidence over ADB

    static Map<String, Object> evidence(Mu300Internal.Io io, Device d, Pkg pkg, Mu300Gpt.Part root, byte[] bootBackup) throws IOException {
        String disk = d.partitionNode();
        String node = disk + "p" + root.n;
        long capacity = (root.end - root.start + 1) * 512;
        if (!NODE.matcher(node).matches() || !node.equals(d.s.root) || d.rootStart != root.start || d.rootSize * 512 != capacity)
            throw new Refused("Kernel OpenWrt node differs from verified GPT; reboot Android / 内核分区节点与 GPT 不符，请重启 Android");
        String idle = io.sh("set -e; n=" + node + "; h=$(ls -A /sys/class/block/$n/holders); d=$(cat /sys/class/block/$n/dev); "
                + "m=$(awk -v n=\"$d\" '$3==n {print \"BUSY\"}' /proc/self/mountinfo); if [ -z \"$h$m\" ]; then echo IDLE; else echo BUSY; fi", true).trim();
        if (!idle.equals("IDLE")) throw new Refused("OpenWrt partition is mounted or mapped / OpenWrt 分区仍被挂载或映射，请先释放占用");
        String dev = "/dev/block/by-name/boot_" + d.other;
        long size; byte[] header, tail, pages;
        if (bootBackup != null) {
            size = bootBackup.length;
            header = Arrays.copyOf(bootBackup, 4096);
            tail = Arrays.copyOfRange(bootBackup, bootBackup.length - 512, bootBackup.length);
        } else {
            String v = io.sh("blockdev --getsize64 " + dev, true).trim();
            if (!NUM.matcher(v).matches()) throw new Refused("Invalid boot partition size / 启动分区大小无效");
            size = Long.parseLong(v);
            header = Mu300Internal.read(io, "dd if=" + dev + " bs=512 count=8 2>/dev/null", 4096);
            tail = Mu300Internal.read(io, "dd if=" + dev + " bs=512 skip=" + (size / 512 - 1) + " count=1 2>/dev/null", 512);
        }
        UninstallBoot.LinuxBoot lb;
        try { lb = UninstallBoot.inspectLinuxBoot(header, tail, size, pkg.family, d.other); }
        catch (IllegalArgumentException e) { throw new Refused(e.getMessage()); }
        long start = 4096 + (lb.kernel + 4095) / 4096 * 4096;
        long count = (lb.ramdisk + 4095) / 4096;
        if (bootBackup != null) {
            if (start + count * 4096 > bootBackup.length) throw new Refused("Incomplete installed ramdisk / 已安装 ramdisk 不完整");
            pages = Arrays.copyOfRange(bootBackup, (int) start, (int) (start + count * 4096));
        } else {
            pages = Mu300Internal.read(io, "dd if=" + dev + " bs=4096 skip=" + (start / 4096) + " count=" + count + " 2>/dev/null", count * 4096);
        }
        final String part = "/dev/block/" + node;
        InstallEvidence.Reader reader = (offset, length) -> {
            if (offset < 0 || length <= 0 || offset + length > capacity) throw new IOException("Incomplete OpenWrt partition read");
            long a = offset / 512, b = (offset + length + 511) / 512;
            byte[] raw = Mu300Internal.read(io, "dd if=" + part + " bs=512 skip=" + a + " count=" + (b - a) + " 2>/dev/null", (b - a) * 512);
            int from = (int) (offset - a * 512);
            if (raw.length < from + length) throw new IOException("Incomplete OpenWrt partition read");
            return Arrays.copyOfRange(raw, from, from + length);
        };
        try { return InstallEvidence.inspect(pkg.family, disk, d.other, size, header, tail, pages, reader, capacity); }
        catch (InstallEvidence.Failed e) { throw new Refused(e.getMessage()); }
    }

    // ================================================================== prepare()

    /** What the read-only check found. */
    static final class Prepared {
        Device device;
        String action, reason, fingerprint;    // fingerprint set when preservation is impossible (reset needs approval)
        boolean wipe, expands;
        long front, tail, total, rootStart, rootSize, openwrtBytes, androidBytes, previousAndroidBytes;
        Volume volume = new Volume();
        Map<String, Object> proof;
        Map<String, String> nodes = new LinkedHashMap<>();
        Map<String, String> toolHashes = new TreeMap<>();
        LinkedHashMap<String, byte[]> tools = new LinkedHashMap<>();
        byte[][] before, after;
        long reclaimed() { return androidBytes - previousAndroidBytes; }
        long released() { return expands ? 0 : openwrtBytes; }
    }

    /**
     * prepare(): GPT snapshots, removal layout, installation proof and the data action
     * ("gap" = OpenWrt was in free space, "grow-ext4/f2fs" = keep Android data, "reset" = factory reset).
     */
    static Prepared prepare(Mu300Internal.Io io, Device d, Pkg pkg) throws IOException {
        if (!d.booted || d.s.root == null)
            throw new Refused("Boot rooted Android with an installed internal partition / 请先启动有 root 的 Android，并确认存在内置 OpenWrt 分区");
        String disk = "/dev/block/" + d.partitionNode();
        long sectors = d.s.diskSize;
        io.step("读取分区表（只读）", false);
        byte[] primary = Mu300Internal.read(io, "dd if=" + disk + " bs=512 skip=1 count=1 2>/dev/null", 512);
        byte[] backup = Mu300Internal.read(io, "dd if=" + disk + " bs=512 skip=" + (sectors - 1) + " count=1 2>/dev/null", 512);
        long[] ft;
        try { ft = Mu300Gpt.backupLayout(primary, backup, sectors); }
        catch (IllegalArgumentException e) { throw new Refused(e.getMessage()); }
        byte[] first = Mu300Internal.read(io, "dd if=" + disk + " bs=512 count=" + ft[0] + " 2>/dev/null", ft[0] * 512);
        byte[] last = Mu300Internal.read(io, "dd if=" + disk + " bs=512 skip=" + ft[1] + " count=" + (sectors - ft[1]) + " 2>/dev/null", (sectors - ft[1]) * 512);
        Layout l = deviceLayout(first, last, sectors, pkg.rootName());
        long userdataBytes = d.s.size * 512;
        if (!(d.partitionNode() + "p" + l.root.n).equals(d.s.root) || d.s.start != l.data.start || userdataBytes != (l.data.end - l.data.start + 1) * 512)
            throw new Refused("Kernel partition nodes differ from GPT; reboot Android / 内核分区节点与 GPT 不符，请重启 Android");
        Mu300Gpt.Part misc = partition(l.info, "misc"), ba = partition(l.info, "boot_a"), bb = partition(l.info, "boot_b");
        io.step("核对设备上的 OpenWrt 安装证据（只读）", false);
        Prepared p = new Prepared();
        p.device = d;
        p.proof = evidence(io, d, pkg, l.root, null);
        p.expands = l.expands;
        p.before = new byte[][]{first, last};
        p.after = l.after;
        p.front = ft[0]; p.tail = ft[1]; p.total = sectors;
        p.rootStart = l.root.start; p.rootSize = l.root.end - l.root.start + 1;
        p.openwrtBytes = p.rootSize * 512;
        p.androidBytes = (l.target.end - l.target.start + 1) * 512;
        p.previousAndroidBytes = userdataBytes;
        String pfx = d.partitionNode() + "p";
        p.nodes.put("userdata", pfx + l.data.n);
        p.nodes.put(pkg.rootName(), pfx + l.root.n);
        p.nodes.put("misc", pfx + misc.n);
        p.nodes.put("boot_a", pfx + ba.n);
        p.nodes.put("boot_b", pfx + bb.n);
        p.action = "gap";
        String reason = null;
        io.step("检查 Android data 能否保留", false);
        try { p.volume = androidVolume(io, d, false); }
        catch (Refused e) {
            if (l.expands) throw e;
            p.volume = new Volume();
            reason = e.getMessage();
        }
        if (l.expands) {
            try {
                if (reason != null) throw new Refused(reason);
                eligible(d, p.volume);
                LinkedHashMap<String, byte[]> tools = growthTools(pkg, p.volume.filesystem);
                UninstallBoot.build(pkg.base, pkg.family, tools, pkg.worker);     // dry build: the request must fit
                p.tools = tools;
                p.action = "grow-" + p.volume.filesystem;
            } catch (IOException | IllegalArgumentException error) {
                reason = error.getMessage();
                p.action = "reset";
                p.tools = new LinkedHashMap<>();
                p.fingerprint = fingerprint(d, p, reason);
            }
        }
        p.reason = reason;
        p.wipe = "reset".equals(p.action);
        for (Map.Entry<String, byte[]> e : p.tools.entrySet()) p.toolHashes.put(e.getKey(), Mu300Boot.sha256(e.getValue()));
        return p;
    }
    /** The reset approval is bound to this exact evidence (sha256 of a sorted JSON document). */
    static String fingerprint(Device d, Prepared p, String reason) {
        TreeMap<String, Object> ev = new TreeMap<>();
        ev.put("cid", d.s.cid);
        ev.put("slot", "_" + d.slot);
        ev.put("profile", d.profile);
        ev.put("layout", Arrays.asList(Mu300Boot.sha256(p.before[0]), Mu300Boot.sha256(p.before[1])));
        ev.put("installation", p.proof);
        ev.put("encryption", Arrays.asList(d.s.state, d.s.type));
        ev.put("volume", p.volume.map());
        ev.put("reason", reason);
        return Mu300Boot.sha256(Json.write(ev, true).getBytes(StandardCharsets.UTF_8));
    }

    // ================================================================== small JSON writer and readers

    static final class Json {
        static String write(Object o, boolean sort) {
            StringBuilder b = new StringBuilder();
            write(b, o, sort, 0);
            return b.toString();
        }
        @SuppressWarnings("unchecked")
        private static void write(StringBuilder b, Object o, boolean sort, int depth) {
            if (o == null) b.append("null");
            else if (o instanceof String) quote(b, (String) o);
            else if (o instanceof Boolean || o instanceof Long || o instanceof Integer) b.append(o);
            else if (o instanceof Number) b.append(((Number) o).longValue());
            else if (o instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) o;
                if (sort) m = new TreeMap<>(m);
                b.append('{');
                int i = 0;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (i++ > 0) b.append(", ");
                    quote(b, e.getKey());
                    b.append(": ");
                    write(b, e.getValue(), sort, depth + 1);
                }
                b.append('}');
            } else if (o instanceof List) {
                b.append('[');
                int i = 0;
                for (Object v : (List<Object>) o) {
                    if (i++ > 0) b.append(", ");
                    write(b, v, sort, depth + 1);
                }
                b.append(']');
            } else quote(b, String.valueOf(o));
        }
        private static void quote(StringBuilder b, String s) {
            b.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c == '\n') b.append("\\n");
                else if (c == '\r') b.append("\\r");
                else if (c == '\t') b.append("\\t");
                else if (c < 0x20) b.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                else b.append(c);
            }
            b.append('"');
        }
    }
    static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    static long lng(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (NumberFormatException e) { return 0; }
    }
    static String human(long b) { return Mu300Internal.human(b); }
}
