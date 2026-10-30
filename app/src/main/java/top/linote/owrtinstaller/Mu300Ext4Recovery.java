package top.linote.owrtinstaller;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Narrow MU300 UFS ext4 journal recovery. The only block device ever passed to a
 * mutating command is the unique mu300root partition resolved from the verified current GPT.
 * Maintenance binaries and logs are staged under /dev (tmpfs/devtmpfs), never
 * under userdata. This class does not edit GPT, boot, misc, userdata or other nodes.
 */
final class Mu300Ext4Recovery {
    private Mu300Ext4Recovery() { }

    static final String ROOT_NAME = "mu300-ext4-journal-recovery";
    static final String RECORD = "journal-recovery.json";
    static final String DISK_NODE = "sda";
    static final String ROOT_LABEL = "mu300root";
    static final int EXT4_BLOCK_BYTES = 4096;
    static final long RECOVERY_INCOMPAT = 0x46L; // filetype + needs_recovery + extents
    static final long CLEAN_INCOMPAT = 0x42L;    // same layout after journal replay
    static final long NEEDS_RECOVERY = 0x4L;
    static final String UFS_LIBRARY_SHA = "7dc62136b7f95a3d18e8218b13649c04729fe3c7d29814acb68b82ceb1f44233";
    private static final Pattern HEX32 = Pattern.compile("[a-f0-9]{32}");
    private static final Pattern UFS_PARTITION_NODE = Pattern.compile("sda[1-9][0-9]{0,2}");
    private static final String TOOL_PREFIX = "openwrt-uninstall/tools/";
    private static final String E2FSCK_REL = "usr/sbin/e2fsck";
    private static final String LOADER_REL = "lib/ld-musl-aarch64.so.1";

    static final class FsFacts {
        int state, logBlockSize;
        long compat, incompat, errorCount, blocks, bytes, defaultMountOpts, journalInode, journalDev;
        String uuid, mountOpts;
    }

    static final class Candidate {
        Uninstall.Device device;
        Uninstall.Pkg pkg;
        Mu300Gpt.Part root;
        long capacityBytes;
        String rootNode, rootGuid, gptPrimarySha, gptBackupSha, superblockSha, fingerprint;
        long gptFrontSectors512, gptTailStart512;
        FsFacts fs;
        Map<String, Object> proof;
        LinkedHashMap<String, byte[]> tools = new LinkedHashMap<>();
        Map<String, String> toolHashes = new TreeMap<>();

        String devicePath() {
            return rootNode != null && UFS_PARTITION_NODE.matcher(rootNode).matches()
                    ? "/dev/block/" + rootNode : "";
        }
        String summary() {
            return "设备：MU300 UFS · /dev/block/" + DISK_NODE + "\n分区：mu300root · " + devicePath()
                    + "\next4 UUID：" + fs.uuid + " · 4096 B/block · incompat=0x"
                    + String.format(Locale.US, "%08x", fs.incompat)
                    + "\n只回放 ext4 journal；不会卸载、格式化、扩容或改写 GPT/boot/misc/userdata。";
        }
    }

    static final class Tx {
        final File dir;
        final Map<String, Object> values;
        Tx(File dir, Map<String, Object> values) { this.dir = dir; this.values = values; }
        String s(String k) { Object v = values.get(k); return v == null ? "" : String.valueOf(v); }
        long n(String k) { try { return Long.parseLong(s(k)); } catch (RuntimeException e) { return -1; } }
        String stage() { return s("stage"); }
        void stage(String next) throws IOException { values.put("stage", next); values.put("updated_ms", System.currentTimeMillis()); save(); }
        void manualReview(String detail) throws IOException {
            values.put("stage", "manual_review");
            values.put("detail", detail == null ? "" : detail);
            values.put("updated_ms", System.currentTimeMillis());
            save();
        }
        void save() throws IOException {
            File record = new File(dir, RECORD);
            writeAtomic(record, Uninstall.Json.write(values, true).getBytes(StandardCharsets.UTF_8));
            Tx disk = load(record);
            if (!s("id").equals(disk.s("id")) || !s("stage").equals(disk.stage()) || !s("cid").equals(disk.s("cid"))
                    || !s("remote_tmpfs").equals(disk.s("remote_tmpfs"))
                    || !s("candidate_fingerprint").equals(disk.s("candidate_fingerprint"))
                    || !s("root_node").equals(disk.s("root_node")) || !s("root_path").equals(disk.s("root_path"))
                    || !s("root_guid").equals(disk.s("root_guid")) || !s("ext4_uuid").equals(disk.s("ext4_uuid"))
                    || n("root_start") != disk.n("root_start") || n("root_end") != disk.n("root_end")
                    || !s("pre_superblock_sha256").equals(disk.s("pre_superblock_sha256")))
                throw new IOException("ext4 修复事务阶段写入后回读不一致；停止执行");
        }
    }

    interface Replay {
        int run(String devicePath) throws IOException;
    }

    /** Pure guard used by synthetic tests: a mutation callback receives only the bound candidate path. */
    static int replayGuarded(Candidate expected, Candidate current, boolean mounted, Replay replay) throws IOException {
        if (expected == null || current == null || replay == null) throw new IOException("修复计划不完整");
        if (mounted) throw new IOException("mu300root 仍被挂载或映射，拒绝修复");
        if (!samePreWrite(expected, current)) throw new IOException("设备、GPT 或 ext4 身份与预检不一致，拒绝写入");
        if (expected.devicePath().isEmpty() || !expected.devicePath().equals(current.devicePath()))
            throw new IOException("修复目标路径与只读候选不一致，拒绝写入");
        int rc = replay.run(current.devicePath());
        if (!acceptedReplayRc(rc)) throw new IOException("e2fsck journal-only 返回码 " + rc + "；停止，不自动重试或扩大修复范围");
        return rc;
    }

    static boolean acceptedReplayRc(int rc) { return rc == 0 || rc == 1; }
    static boolean acceptedReadOnlyRc(int rc) { return rc == 0; }

    /** Candidate gate: exact known layout, clean error flag, 4 KiB blocks, only needs_recovery unsupported. */
    static FsFacts parseRecoverySuperblock(byte[] sb, long capacityBytes) throws IOException {
        FsFacts f = parseCommon(sb, capacityBytes);
        if (f.state != 1) throw new IOException("ext4 state 不是 clean (state=0x" + String.format(Locale.US, "%04x", f.state) + ")");
        if (f.incompat != RECOVERY_INCOMPAT || (f.incompat & NEEDS_RECOVERY) == 0)
            throw new IOException("仅支持 incompat=0x00000046 的 needs_recovery 状态；拒绝其他 ext4 特性位");
        if (f.logBlockSize != 2) throw new IOException("ext4 不是已核验的 4096 B block 布局");
        if (f.errorCount != 0) throw new IOException("ext4 error_count 非零，需人工检查");
        if ((f.compat & 0x4L) == 0) throw new IOException("ext4 未声明 journal，拒绝回放");
        ensureMetadataOnlyJournal(f);
        return f;
    }

    /** Independent postcondition: exact same layout with needs_recovery cleared and no ext4 error flag. */
    static FsFacts parseCleanSuperblock(byte[] sb, long capacityBytes, String expectedUuid) throws IOException {
        FsFacts f = parseCommon(sb, capacityBytes);
        if (f.state != 1 || f.incompat != CLEAN_INCOMPAT || (f.incompat & NEEDS_RECOVERY) != 0)
            throw new IOException("journal recovery 后 ext4 superblock 未达到预期 clean layout");
        if (f.logBlockSize != 2 || f.errorCount != 0 || (f.compat & 0x4L) == 0)
            throw new IOException("journal recovery 后 ext4 状态、块大小或 journal 不符合预期");
        ensureMetadataOnlyJournal(f);
        if (expectedUuid != null && !expectedUuid.equals(f.uuid)) throw new IOException("ext4 UUID 与修复前不同");
        return f;
    }

    private static FsFacts parseCommon(byte[] sb, long capacityBytes) throws IOException {
        if (sb == null || sb.length != 1024) throw new IOException("ext4 superblock 长度无效");
        if (u16(sb, 56) != 0xef53) throw new IOException("ext4 superblock magic 不匹配");
        FsFacts f = new FsFacts();
        f.state = u16(sb, 58);
        f.compat = u32(sb, 92);
        f.incompat = u32(sb, 96);
        f.logBlockSize = (int) u32(sb, 24);
        f.errorCount = u32(sb, 0x194);
        f.defaultMountOpts = u32(sb, 0x100);
        f.journalInode = u32(sb, 0xE0);
        f.journalDev = u32(sb, 0xE4);
        f.mountOpts = new String(sb, 0x200, 64, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        f.blocks = u32(sb, 4);
        if ((f.incompat & 0x80L) != 0) f.blocks |= u32(sb, 336) << 32;
        f.uuid = hex(sb, 104, 16);
        if (f.uuid.matches("0{32}")) throw new IOException("ext4 UUID 无效");
        if (f.logBlockSize < 0 || f.logBlockSize > 6) throw new IOException("ext4 log_block_size 越界");
        long blockBytes = 1024L << f.logBlockSize;
        if (f.blocks <= 0 || f.blocks > Long.MAX_VALUE / blockBytes) throw new IOException("ext4 block count 越界");
        f.bytes = f.blocks * blockBytes;
        if (capacityBytes <= 0 || f.bytes > capacityBytes || capacityBytes - f.bytes > (4L << 20))
            throw new IOException("ext4 block count 与 GPT 分区容量不符");
        return f;
    }

    private static void ensureMetadataOnlyJournal(FsFacts f) throws IOException {
        if (f.journalInode <= 0 || f.journalDev != 0)
            throw new IOException("只支持分区内部 journal；外置 journal 可能改写其他设备，拒绝回放");
        if ((f.defaultMountOpts & 0x60L) == 0x20L || f.mountOpts.contains("data=journal"))
            throw new IOException("ext4 配置为 data=journal；回放可能写入文件数据，拒绝修复");
    }

    static boolean interruptedCanBeVerified(String stage, boolean processAlive, boolean sameIdentity,
                                             boolean cleanSuperblock, int readOnlyRc) {
        if (!("journal_replay_started".equals(stage) || "journal_replay_returned".equals(stage))) return false;
        return !processAlive && sameIdentity && cleanSuperblock && acceptedReadOnlyRc(readOnlyRc);
    }

    /** Read-only preparation. It never invokes an e2fsck mutating mode. */
    static Candidate prepareCandidate(Mu300Internal.Io io, Uninstall.Device d, Uninstall.Pkg pkg) throws IOException {
        return observe(io, d, pkg, true);
    }

    private static Candidate observe(Mu300Internal.Io io, Uninstall.Device d, Uninstall.Pkg pkg,
                                     boolean recoveryCandidate) throws IOException {
        requireTarget(io, d, pkg);
        long sectors = d.s.diskSize;
        int block = d.s.sectorSize, factor = block / 512;
        if ((block != 512 && block != 4096) || sectors <= 0 || sectors % factor != 0)
            throw new IOException("UFS GPT 逻辑块几何不受支持");
        String disk = "/dev/block/" + DISK_NODE;
        long logicalSectors = sectors / factor;
        byte[] primary = Mu300Internal.read(io, "dd if=" + Mu300Internal.q(disk) + " bs=" + block + " skip=1 count=1 2>/dev/null", block);
        byte[] backup = Mu300Internal.read(io, "dd if=" + Mu300Internal.q(disk) + " bs=" + block + " skip=" + (logicalSectors - 1) + " count=1 2>/dev/null", block);
        long[] ft;
        try { ft = Mu300Gpt.backupLayout(primary, backup, sectors); }
        catch (IllegalArgumentException e) { throw new IOException("UFS GPT 主/备份头校验失败：" + e.getMessage()); }
        byte[] first = Mu300Internal.read(io, "dd if=" + Mu300Internal.q(disk) + " bs=" + block + " count=" + (ft[0] / factor) + " 2>/dev/null", Uninstall.bytes512(ft[0]));
        byte[] last = Mu300Internal.read(io, "dd if=" + Mu300Internal.q(disk) + " bs=" + block + " skip=" + (ft[1] / factor) + " count=" + ((sectors - ft[1]) / factor) + " 2>/dev/null", Uninstall.bytes512(sectors - ft[1]));
        Uninstall.Layout layout;
        try { layout = Uninstall.deviceLayout(first, last, sectors, ROOT_LABEL); }
        catch (Uninstall.Refused e) { throw new IOException(e.getMessage()); }
        String rootNode = resolveRootNode(d, layout);
        verifySysfs(io, d, layout.root, rootNode, sectors);

        Map<String, Object> proof;
        try {
            proof = recoveryCandidate ? Uninstall.evidenceForJournalRecovery(io, d, pkg, layout.root, null)
                    : Uninstall.evidence(io, d, pkg, layout.root, null);
        } catch (Uninstall.Refused e) { throw new IOException(e.getMessage()); }
        long capacity = Uninstall.bytes512(Uninstall.sectorsBetween(layout.root.start, layout.root.end));
        byte[] sb = readSuperblock(io, d, rootNode, capacity);
        FsFacts fs = recoveryCandidate ? parseRecoverySuperblock(sb, capacity)
                : parseCleanSuperblock(sb, capacity, null);
        String guid;
        try {
            byte[] table = Uninstall.table(first);
            int off = Math.multiplyExact(layout.root.n - 1, 128);
            if (off < 0 || off > table.length - 128) throw new IOException("GPT root entry index 越界");
            guid = hex(table, off + 16, 16);
            if (guid.matches("0{32}")) throw new IOException("GPT mu300root 唯一 GUID 无效");
        } catch (IllegalArgumentException | ArithmeticException e) { throw new IOException("无法读取 GPT mu300root 唯一 GUID"); }

        Candidate c = new Candidate();
        c.device = d; c.pkg = pkg; c.root = layout.root; c.rootNode = rootNode;
        c.gptFrontSectors512 = ft[0]; c.gptTailStart512 = ft[1];
        c.capacityBytes = capacity; c.rootGuid = guid;
        c.gptPrimarySha = Mu300Boot.sha256(first); c.gptBackupSha = Mu300Boot.sha256(last);
        c.superblockSha = Mu300Boot.sha256(sb); c.fs = fs; c.proof = proof;
        c.tools = maintenanceSubset(Uninstall.growthTools(pkg, "ext4"));
        for (Map.Entry<String, byte[]> e : c.tools.entrySet()) c.toolHashes.put(e.getKey(), Mu300Boot.sha256(e.getValue()));
        c.fingerprint = fingerprint(c);
        return c;
    }

    /** Bind the kernel-reported nodes and geometry to the unique labels in the just-read GPT. */
    static String resolveRootNode(Uninstall.Device d, Uninstall.Layout layout) throws IOException {
        if (d == null || d.s == null || layout == null || layout.root == null || layout.data == null)
            throw new IOException("设备或 GPT 分区布局不完整");
        if (!DISK_NODE.equals(d.s.diskNode)) throw new IOException("MU300 UFS GPT 磁盘节点不是已核验的 sda");
        if (!ROOT_LABEL.equals(layout.root.name)) throw new IOException("GPT 根分区标签不是 mu300root");
        final String node;
        try { node = Uninstall.partNode(DISK_NODE, layout.root.n); }
        catch (IllegalArgumentException e) { throw new IOException("GPT mu300root 分区号无效"); }
        if (!UFS_PARTITION_NODE.matcher(node).matches()) throw new IOException("GPT mu300root 节点格式不受支持");
        if (!node.equals(d.s.root)) throw new IOException("内核 mu300root 节点与 GPT 唯一标签位置不一致");
        long rootSectors = Uninstall.sectorsBetween(layout.root.start, layout.root.end);
        long dataSectors = Uninstall.sectorsBetween(layout.data.start, layout.data.end);
        if (d.rootStart != layout.root.start || d.rootSize != rootSectors)
            throw new IOException("mu300root 起点/长度与 GPT 不一致");
        if (d.s.start != layout.data.start || d.s.size != dataSectors)
            throw new IOException("userdata 起点/长度与 GPT 不一致");
        return node;
    }

    static boolean supportedMu300Profile(String profile) {
        return "f50".equals(profile) || "u30air".equals(profile) || "v50".equals(profile);
    }

    static boolean matchesAndroidProfile(String model, String platform, String profile) {
        if (!supportedMu300Profile(profile)) return false;
        try { return profile.equals(Mu300Gpt.androidProfile(model, platform)); }
        catch (IllegalArgumentException e) { return false; }
    }

    static String targetGateFailure(Uninstall.Device d, Uninstall.Pkg pkg) {
        if (d == null || d.s == null || pkg == null) return "设备或安装包信息缺失";
        List<String> failed = new ArrayList<>();
        if (!"mu300".equals(pkg.family)) failed.add("package_family 不匹配");
        if (!"ufs".equals(d.s.kind)) failed.add("storage_kind=" + ("emmc".equals(d.s.kind) ? "emmc" : "other") + "（要求 ufs）");
        if (!DISK_NODE.equals(d.s.diskNode)) failed.add("disk_node=" + safeNode(d.s.diskNode) + "（要求 " + DISK_NODE + "）");
        if (d.s.root == null || !UFS_PARTITION_NODE.matcher(d.s.root).matches())
            failed.add("root_node=" + safeNode(d.s.root) + "（须匹配当前 GPT 中唯一的 mu300root）");
        if (!HEX32.matcher(d.s.cid == null ? "" : d.s.cid).matches()) failed.add("storage_identity_format=invalid（值已隐藏）");
        if (d.s.sectorSize != 512 && d.s.sectorSize != 4096) failed.add("logical_block_size=" + d.s.sectorSize + "（仅支持 512/4096）");
        if (!supportedMu300Profile(d.profile)) failed.add("device_profile=unsupported_or_missing");
        return failed.isEmpty() ? null : String.join("；", failed);
    }

    private static String safeNode(String node) {
        return node != null && node.matches("[A-Za-z0-9]+") ? node : "<missing_or_invalid>";
    }

    private static void requireTarget(Mu300Internal.Io io, Uninstall.Device d, Uninstall.Pkg pkg) throws IOException {
        if (d == null || d.s == null || pkg == null || !d.booted)
            throw new IOException("需要已启动并完成设置的 rooted Android");
        String gateFailure = targetGateFailure(d, pkg);
        if (gateFailure != null)
            throw new IOException("仅支持已核验的 MU300 UFS 唯一 mu300root 分区；未通过项：" + gateFailure);
        if (pkg.storageLibrary == null || !UFS_LIBRARY_SHA.equals(Mu300Boot.sha256(pkg.storageLibrary)))
            throw new IOException("UFS identity library 缺失或校验不符");
        String props = io.sh("getprop ro.product.model; getprop ro.product.device", true);
        String platform = io.sh("getprop ro.board.platform", true).trim();
        if (!matchesAndroidProfile(props, platform, d.profile))
            throw new IOException("设备属性与已识别的 MU300 系列 profile 不一致，拒绝修复");
        if (!"a".equals(d.slot) && !"b".equals(d.slot)) throw new IOException("Android slot 无效");
        if (d.bootId == null || !d.bootId.matches("[0-9a-fA-F-]{20,64}")) throw new IOException("无法确认本次 Android 启动身份");
    }

    private static void verifySysfs(Mu300Internal.Io io, Uninstall.Device d, Mu300Gpt.Part root,
                                    String rootNode, long diskSectors) throws IOException {
        String script = "set -e; n=" + Mu300Internal.q(rootNode) + "; b=" + DISK_NODE + "; u=/sys/class/block/$n/uevent; "
                + "grep -qx PARTNAME=" + ROOT_LABEL + " \"$u\"; test -b /dev/block/$n; "
                + "echo ROOT_START=$(cat /sys/class/block/$n/start); echo ROOT_SIZE=$(cat /sys/class/block/$n/size); "
                + "echo DISK_SIZE=$(cat /sys/class/block/$b/size); "
                + "echo LOGICAL=$(cat /sys/class/block/$b/queue/logical_block_size); "
                + "echo DEV=$(cat /sys/class/block/$n/dev); echo BOOT_ID=$(cat /proc/sys/kernel/random/boot_id)";
        Map<String, String> m = Mu300Internal.kv(io.sh(script, true));
        if (!String.valueOf(root.start).equals(m.get("ROOT_START"))
                || !String.valueOf(Uninstall.sectorsBetween(root.start, root.end)).equals(m.get("ROOT_SIZE"))
                || !String.valueOf(diskSectors).equals(m.get("DISK_SIZE"))
                || !String.valueOf(d.s.sectorSize).equals(m.get("LOGICAL"))
                || m.getOrDefault("DEV", "").isEmpty() || !d.bootId.equals(m.get("BOOT_ID")))
            throw new IOException("mu300root 的 sysfs 节点、GPT 几何或启动身份与只读探测不一致");
    }

    private static byte[] readSuperblock(Mu300Internal.Io io, Uninstall.Device d, String rootNode, long capacity) throws IOException {
        if (capacity < 2048) throw new IOException("mu300root 分区容量过小");
        int ioBlock = d.s.sectorSize;
        long offset = 1024, skip = offset / ioBlock;
        int remainder = (int) (offset % ioBlock);
        long count = (remainder + 1024L + ioBlock - 1) / ioBlock;
        byte[] raw = Mu300Internal.read(io, "dd if=" + Mu300Internal.q("/dev/block/" + rootNode) + " bs=" + ioBlock
                + " skip=" + skip + " count=" + count + " 2>/dev/null", count * ioBlock);
        if (raw.length < remainder + 1024) throw new IOException("ext4 superblock 读取不完整");
        return Arrays.copyOfRange(raw, remainder, remainder + 1024);
    }

    private static LinkedHashMap<String, byte[]> maintenanceSubset(Map<String, byte[]> all) throws IOException {
        TreeMap<String, byte[]> sorted = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : all.entrySet()) {
            if (!e.getKey().startsWith(TOOL_PREFIX)) throw new IOException("维护工具路径前缀不安全");
            String rel = e.getKey().substring(TOOL_PREFIX.length());
            if (E2FSCK_REL.equals(rel) || rel.startsWith("lib/") || rel.startsWith("usr/lib/")) sorted.put(rel, e.getValue());
        }
        if (!sorted.containsKey(E2FSCK_REL) || !sorted.containsKey(LOADER_REL))
            throw new IOException("安装包缺少已校验的 e2fsck 或 ARM64 loader");
        long total = 0;
        LinkedHashMap<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : sorted.entrySet()) {
            total += e.getValue().length;
            out.put(e.getKey(), e.getValue());
        }
        if (total <= 0 || total > (32L << 20)) throw new IOException("ext4 journal 工具暂存大小异常");
        return out;
    }

    static String fingerprint(Candidate c) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("cid", c.device.s.cid); m.put("kind", c.device.s.kind); m.put("disk", DISK_NODE);
        m.put("root", c.rootNode); m.put("root_path", c.devicePath());
        m.put("profile", c.device.profile); m.put("slot", c.device.slot); m.put("boot_id", c.device.bootId);
        m.put("sector_size", c.device.s.sectorSize); m.put("disk_size", c.device.s.diskSize);
        m.put("root_guid", c.rootGuid); m.put("root_start", c.root.start); m.put("root_end", c.root.end);
        m.put("root_number", (long) c.root.n); m.put("gpt_front_sectors_512", c.gptFrontSectors512);
        m.put("gpt_tail_start_sectors_512", c.gptTailStart512);
        m.put("uuid", c.fs.uuid); m.put("blocks", c.fs.blocks); m.put("block_bytes", 1L << (10 + c.fs.logBlockSize));
        m.put("gpt_primary_sha256", c.gptPrimarySha); m.put("gpt_backup_sha256", c.gptBackupSha);
        m.put("superblock_sha256", c.superblockSha); m.put("proof", c.proof); m.put("tool_hashes", c.toolHashes);
        return Mu300Boot.sha256(Uninstall.Json.write(m, true).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean samePreWrite(Candidate a, Candidate b) {
        return a.fingerprint != null && a.fingerprint.equals(b.fingerprint)
                && a.fs.incompat == RECOVERY_INCOMPAT && b.fs.incompat == RECOVERY_INCOMPAT;
    }

    private static boolean sameLayoutAndDevice(Candidate a, Candidate b, boolean requireBootId) {
        return a != null && b != null && a.device.s.cid.equals(b.device.s.cid)
                && a.device.s.kind.equals(b.device.s.kind) && a.device.s.sectorSize == b.device.s.sectorSize
                && a.device.s.diskSize == b.device.s.diskSize && a.device.profile.equals(b.device.profile)
                && a.device.slot.equals(b.device.slot) && (!requireBootId || a.device.bootId.equals(b.device.bootId))
                && a.rootNode.equals(b.rootNode) && a.devicePath().equals(b.devicePath())
                && a.root.n == b.root.n && a.rootGuid.equals(b.rootGuid)
                && a.root.start == b.root.start && a.root.end == b.root.end
                && a.gptFrontSectors512 == b.gptFrontSectors512 && a.gptTailStart512 == b.gptTailStart512
                && a.gptPrimarySha.equals(b.gptPrimarySha) && a.gptBackupSha.equals(b.gptBackupSha)
                && a.fs.uuid.equals(b.fs.uuid) && a.fs.blocks == b.fs.blocks
                && a.fs.logBlockSize == b.fs.logBlockSize;
    }

    static Tx findForCid(File root, String cid) throws IOException {
        if (!root.isDirectory()) return null;
        File[] dirs = root.listFiles();
        if (dirs == null) throw new IOException("无法读取 ext4 修复事务目录；为安全起见停止");
        Arrays.sort(dirs, (a, b) -> a.getName().compareTo(b.getName()));
        Tx found = null;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            File record = new File(dir, RECORD);
            if (!record.exists()) continue;
            Tx tx;
            try { tx = load(record); }
            catch (IOException e) { throw new IOException("发现无法读取的 ext4 修复记录；为安全起见停止：" + dir.getName()); }
            if (cid.equals(tx.s("cid"))) {
                if (found != null) throw new IOException("发现同一设备的多个 ext4 修复记录；为安全起见停止");
                found = tx;
            }
        }
        return found;
    }

    static String pendingDescription(File root) {
        if (!root.isDirectory()) return null;
        File[] dirs = root.listFiles();
        if (dirs == null) return "MU300 ext4 修复记录不可读";
        Arrays.sort(dirs, (a, b) -> b.getName().compareTo(a.getName()));
        for (File dir : dirs) {
            File f = new File(dir, RECORD);
            if (!f.isFile()) continue;
            try {
                Tx tx = load(f);
                if (!"verified".equals(tx.stage()) && !"precheck_failed".equals(tx.stage()))
                    return "MU300 ext4 日志修复记录（阶段 " + tx.stage() + "），须先完成只读核验";
            } catch (IOException e) { return "MU300 ext4 修复记录无法读取，已停止后续操作"; }
        }
        return null;
    }

    static List<File> protectedDirs(File root) {
        List<File> out = new ArrayList<>();
        File[] dirs = root.listFiles();
        if (dirs == null) return out;
        for (File dir : dirs) if (dir.isDirectory() && new File(dir, RECORD).isFile()) out.add(dir);
        return out;
    }

    private static Tx create(File root, Candidate c, String remoteRoot) throws IOException {
        if (!root.exists() && !root.mkdirs()) throw new IOException("无法创建本机 ext4 修复事务目录");
        String id = String.format(Locale.US, "%013d-%s", System.currentTimeMillis(), UUID.randomUUID().toString().replace("-", ""));
        File dir = new File(root, id);
        if (!dir.mkdir()) throw new IOException("无法创建本机 ext4 修复记录");
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("schema", 1L); m.put("id", id); m.put("created_ms", System.currentTimeMillis()); m.put("updated_ms", System.currentTimeMillis());
        m.put("stage", "precheck_failed"); m.put("operation", "e2fsck -p -E journal_only");
        m.put("cid", c.device.s.cid); m.put("storage_kind", c.device.s.kind); m.put("disk", DISK_NODE);
        m.put("root_node", c.rootNode); m.put("root_path", c.devicePath());
        m.put("root_label", ROOT_LABEL); m.put("profile", c.device.profile); m.put("slot", c.device.slot); m.put("boot_id", c.device.bootId);
        m.put("sector_size", (long) c.device.s.sectorSize); m.put("disk_size", c.device.s.diskSize);
        m.put("root_guid", c.rootGuid); m.put("root_start", c.root.start); m.put("root_end", c.root.end);
        m.put("ext4_uuid", c.fs.uuid); m.put("ext4_blocks", c.fs.blocks); m.put("ext4_log_block_size", (long) c.fs.logBlockSize);
        m.put("pre_incompat", c.fs.incompat); m.put("pre_superblock_sha256", c.superblockSha);
        m.put("gpt_primary_sha256", c.gptPrimarySha); m.put("gpt_backup_sha256", c.gptBackupSha);
        m.put("gpt_front_sectors_512", c.gptFrontSectors512); m.put("gpt_tail_start_sectors_512", c.gptTailStart512);
        m.put("candidate_fingerprint", c.fingerprint); m.put("tool_hashes", c.toolHashes); m.put("remote_tmpfs", remoteRoot);
        Tx tx = new Tx(dir, m); tx.save(); return tx;
    }

    private static Tx load(File record) throws IOException {
        try {
            Object parsed = MiniJson.parse(new String(Files.readAllBytes(record.toPath()), StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) throw new IOException("bad record");
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) parsed;
            Object cid = m.get("cid"), stage = m.get("stage"), remote = m.get("remote_tmpfs");
            if (!(cid instanceof String) || !HEX32.matcher((String) cid).matches() || !(stage instanceof String)
                    || !(remote instanceof String) || !((String) remote).matches("/dev/\\.owrt-ext4-journal-[a-f0-9]{32}"))
                throw new IOException("invalid record fields");
            if (!"journal_replay_started".equals(stage) && !"journal_replay_returned".equals(stage)
                    && !"verified".equals(stage) && !"manual_review".equals(stage) && !"precheck_failed".equals(stage))
                throw new IOException("invalid stage");
            return new Tx(record.getParentFile(), m);
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw new IOException("cannot parse record", e); }
    }

    /** Run after the user explicitly approves the typed repair confirmation. */
    static Uninstall.Prepared repairAndRecheck(Mu300Internal.Io io, File recordRoot, Candidate approved,
                                                Uninstall.Pkg pkg, String profile) throws IOException {
        if (approved == null || !samePreWrite(approved, observe(io,
                Uninstall.probe(io, pkg.rootName(), profile), pkg, true)))
            throw new IOException("设备或 ext4 状态已变化，请重新执行只读检查；未开始修复");
        Stage stage = stageTools(io, approved);
        Tx tx = null;
        boolean uncertain = false;
        boolean commandReturned = false;
        boolean rcVerified = false;
        boolean returnedStageSaved = false;
        try {
            String version = io.sh(optionProbe(stage.remoteRoot), true, 120);
            if (!version.toLowerCase(Locale.ROOT).contains("e2fsck")
                    || parseRc(version, "OWRT-E2FSCK-OPTION-RC=") != 0)
                throw new IOException("无法确认 e2fsck 支持 journal_only；未写入 ext4");
            Candidate fresh = observe(io, Uninstall.probe(io, pkg.rootName(), profile), pkg, true);
            if (!samePreWrite(approved, fresh)) throw new IOException("写入前最后一次设备核对不一致；未开始修复");
            tx = create(recordRoot, fresh, stage.remoteRoot);
            tx.stage("journal_replay_started");
            io.step("仅回放 mu300root ext4 journal（写入该 ext4 分区）", true);
            uncertain = true;
            String out = io.sh(replayScript(stage.remoteRoot, fresh), true, 1800, line -> io.status(line));
            commandReturned = true;
            uncertain = false;
            if (out.contains("OWRT-REPLAY-NOT-STARTED")) {
                tx.stage("precheck_failed");
                throw new IOException("写入前设备/挂载复核未通过，e2fsck 没有启动；请重新做只读检查");
            }
            if (!out.contains("OWRT-REPLAY-STARTED")) {
                throw new IOException("修复命令返回但缺少启动标记；按中断处理，不会自动重试");
            }
            int rc = parseRc(out, "OWRT-E2FSCK-RC=");
            rcVerified = true;
            tx.stage("journal_replay_returned");
            returnedStageSaved = true;
            if (!acceptedReplayRc(rc)) {
                tx.manualReview("e2fsck returned " + rc);
                throw new IOException("e2fsck 返回码 " + rc + "；不执行 -y、格式化或其他修复，需人工检查");
            }
            io.step("复读 ext4 superblock 并执行完整只读 e2fsck", false);
            Candidate post = observe(io, Uninstall.probe(io, pkg.rootName(), profile), pkg, false);
            if (!sameLayoutAndDevice(fresh, post, true)) throw new IOException("修复后设备、GPT 或 ext4 UUID/几何发生变化");
            int verifyRc = runReadOnlyFsck(io, stage.remoteRoot, post);
            if (!acceptedReadOnlyRc(verifyRc)) throw new IOException("完整只读 e2fsck 返回码 " + verifyRc + "；停止，不继续卸载");
            Uninstall.Prepared plan = Uninstall.prepare(io, post.device, pkg);
            if (!planMatchesCandidate(fresh, plan)) throw new IOException("正常卸载证据或 GPT 与修复前不一致");
            tx.stage("verified");
            io.step("ext4 与卸载证据复核完成；尚未卸载", false);
            return plan;
        } catch (IOException | RuntimeException e) {
            if (tx != null && commandReturned && rcVerified && returnedStageSaved && !"manual_review".equals(tx.stage())
                    && !"precheck_failed".equals(tx.stage()) && !"verified".equals(tx.stage())) {
                try { tx.manualReview(e.getMessage()); } catch (IOException ignored) { }
            }
            throw e;
        } finally {
            if (!uncertain) cleanupRemote(io, stage.remoteRoot);
            deleteTree(stage.localRoot);
        }
    }

    /** Interrupted operation is only verified read-only; this method never retries journal replay. */
    static Uninstall.Prepared verifyInterrupted(Mu300Internal.Io io, File recordRoot, Tx tx,
                                                Uninstall.Device current, Uninstall.Pkg pkg) throws IOException {
        if (tx == null || !("journal_replay_started".equals(tx.stage()) || "journal_replay_returned".equals(tx.stage())))
            throw new IOException("没有可供只读核验的中断修复记录");
        boolean alive = remoteProcessAlive(io, tx.s("remote_tmpfs"));
        if (alive) throw new IOException("发现 e2fsck 仍在运行；保持设备供电，勿再次修复或卸载，稍后再做只读检查");
        Stage stage = stageTools(io, null, pkg);
        try {
            Candidate clean = observe(io, current, pkg, false);
            if (!matchesTx(tx, clean)) throw new IOException("中断后设备身份、GPT 或 ext4 UUID/几何与修复记录不同");
            int rc = runReadOnlyFsck(io, stage.remoteRoot, clean);
            if (!interruptedCanBeVerified(tx.stage(), false, true, true, rc))
                throw new IOException("中断後的完整只读 e2fsck 未返回 clean 状态");
            Uninstall.Prepared plan = Uninstall.prepare(io, current, pkg);
            if (!planMatchesCandidate(clean, plan)) throw new IOException("正常卸载证据或 GPT 与中断修复记录不一致");
            tx.stage("verified");
            return plan;
        } catch (IOException | RuntimeException e) {
            try { tx.manualReview(e.getMessage()); } catch (IOException ignored) { }
            throw new IOException("检测到 ext4 修复中断；未重试写入。只读核验未通过，停止卸载：" + e.getMessage(), e);
        } finally {
            cleanupRemote(io, stage.remoteRoot);
            deleteTree(stage.localRoot);
        }
    }

    private static boolean matchesTx(Tx tx, Candidate c) {
        return tx.s("cid").equals(c.device.s.cid) && "ufs".equals(tx.s("storage_kind"))
                && DISK_NODE.equals(tx.s("disk")) && c.rootNode.equals(tx.s("root_node"))
                && c.devicePath().equals(tx.s("root_path"))
                && ROOT_LABEL.equals(tx.s("root_label")) && tx.s("profile").equals(c.device.profile)
                && tx.s("slot").equals(c.device.slot) && tx.s("boot_id").equals(c.device.bootId)
                && tx.n("sector_size") == c.device.s.sectorSize && tx.n("disk_size") == c.device.s.diskSize
                && tx.s("root_guid").equals(c.rootGuid)
                && tx.n("root_start") == c.root.start && tx.n("root_end") == c.root.end
                && tx.s("ext4_uuid").equals(c.fs.uuid) && tx.n("ext4_blocks") == c.fs.blocks
                && tx.n("ext4_log_block_size") == c.fs.logBlockSize
                && tx.s("gpt_primary_sha256").equals(c.gptPrimarySha)
                && tx.s("gpt_backup_sha256").equals(c.gptBackupSha);
    }

    private static boolean planMatchesCandidate(Candidate c, Uninstall.Prepared p) {
        if (p == null || p.before == null || p.before.length != 2) return false;
        return c.gptPrimarySha.equals(Mu300Boot.sha256(p.before[0]))
                && c.gptBackupSha.equals(Mu300Boot.sha256(p.before[1]))
                && p.device != null && c.device.s.cid.equals(p.device.s.cid)
                && c.rootNode.equals(p.nodes.get(ROOT_LABEL));
    }

    private static final class Stage {
        File localRoot;
        String remoteRoot;
    }

    private static Stage stageTools(Mu300Internal.Io io, Candidate c) throws IOException {
        return stageTools(io, c, c.pkg);
    }

    private static Stage stageTools(Mu300Internal.Io io, Candidate c, Uninstall.Pkg pkg) throws IOException {
        Map<String, byte[]> tools = c == null ? maintenanceSubset(Uninstall.growthTools(pkg, "ext4")) : c.tools;
        Map<String, String> hashes = new TreeMap<>();
        long total = 0;
        for (Map.Entry<String, byte[]> e : tools.entrySet()) { hashes.put(e.getKey(), Mu300Boot.sha256(e.getValue())); total += e.getValue().length; }
        String id = UUID.randomUUID().toString().replace("-", "");
        String remote = "/dev/.owrt-ext4-journal-" + id;
        String fs = io.sh("awk '$2==\"/dev\" {print $3; exit}' /proc/mounts", true).trim();
        if (!("tmpfs".equals(fs) || "devtmpfs".equals(fs))) throw new IOException("目标 /dev 不是 tmpfs/devtmpfs；拒绝把修复工具写入持久存储");
        String free = io.sh("df -Pk /dev | tail -n 1 | awk '{print $4}'", true).trim();
        if (!free.matches("[0-9]+") || Long.parseLong(free) * 1024L < total + (512L << 10))
            throw new IOException("目标 RAM 暂存空间不足；未写入 ext4");
        io.sh("set -e; [ ! -e " + Mu300Internal.q(remote) + " ]; mkdir " + Mu300Internal.q(remote)
                + "; chmod 0777 " + Mu300Internal.q(remote), true);
        Stage s = new Stage(); s.remoteRoot = remote;
        File cache = io.cacheDir();
        if (cache == null) throw new IOException("本机缓存目录不可用");
        s.localRoot = new File(cache, "mu300-ext4-journal-" + id);
        if (!s.localRoot.mkdirs()) throw new IOException("无法创建本机临时工具目录");
        boolean ok = false;
        try {
            for (Map.Entry<String, byte[]> e : tools.entrySet()) {
                String rel = e.getKey();
                File local = new File(s.localRoot, rel);
                File parent = local.getParentFile();
                if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("无法创建本机临时工具子目录");
                Mu300Internal.writeAll(local, e.getValue());
                String remoteFile = remote + "/" + rel;
                String remoteParent = remoteFile.substring(0, remoteFile.lastIndexOf('/'));
                io.sh("mkdir -p " + Mu300Internal.q(remoteParent) + "; chmod 0777 " + Mu300Internal.q(remoteParent), true);
                io.push(local, remoteFile);
            }
            StringBuilder verify = new StringBuilder("set -e; ");
            for (Map.Entry<String, String> e : hashes.entrySet()) {
                String path = remote + "/" + e.getKey();
                verify.append("[ \"$(sha256sum ").append(Mu300Internal.q(path)).append(" | awk '{print $1}')\" = ")
                        .append(Mu300Internal.q(e.getValue())).append(" ]; chmod 0700 ").append(Mu300Internal.q(path)).append("; ");
            }
            String loader = remote + "/" + LOADER_REL;
            String e2fsck = remote + "/" + E2FSCK_REL;
            verify.append("[ -x ").append(Mu300Internal.q(loader)).append(" ]; [ -x ").append(Mu300Internal.q(e2fsck)).append(" ]");
            io.sh(verify.toString(), true);
            ok = true;
            return s;
        } finally {
            if (!ok) { cleanupRemote(io, remote); deleteTree(s.localRoot); }
        }
    }

    private static String optionProbe(String remote) {
        String loader = remote + "/" + LOADER_REL;
        String e2fsck = remote + "/" + E2FSCK_REL;
        String libs = remote + "/lib:" + remote + "/usr/lib";
        return "set +e; LC_ALL=C " + Mu300Internal.q(loader) + " --library-path " + Mu300Internal.q(libs)
                + " " + Mu300Internal.q(e2fsck) + " -E journal_only -V; rc=$?; echo OWRT-E2FSCK-OPTION-RC=$rc; exit 0";
    }

    private static String gptMismatchConditions(Candidate c) throws IOException {
        int block = c.device.s.sectorSize;
        int factor = block / 512;
        if ((block != 512 && block != 4096) || c.gptFrontSectors512 <= 0 || c.gptTailStart512 <= 0
                || c.gptFrontSectors512 % factor != 0 || c.gptTailStart512 % factor != 0
                || c.device.s.diskSize <= c.gptTailStart512 || c.gptFrontSectors512 > c.gptTailStart512
                || (c.device.s.diskSize - c.gptTailStart512) % factor != 0)
            throw new IOException("候选中的 GPT 读取范围无效；拒绝启动 e2fsck");
        long frontBlocks = c.gptFrontSectors512 / factor;
        long tailStartBlocks = c.gptTailStart512 / factor;
        long tailCountBlocks = (c.device.s.diskSize - c.gptTailStart512) / factor;
        String disk = Mu300Internal.q("/dev/block/" + DISK_NODE);
        return "[ \"$(dd if=" + disk + " bs=" + block + " count=" + frontBlocks
                + " 2>/dev/null | sha256sum | awk '{print $1}')\" != " + Mu300Internal.q(c.gptPrimarySha) + " ]"
                + " || [ \"$(dd if=" + disk + " bs=" + block + " skip=" + tailStartBlocks + " count=" + tailCountBlocks
                + " 2>/dev/null | sha256sum | awk '{print $1}')\" != " + Mu300Internal.q(c.gptBackupSha) + " ]";
    }

    private static String superblockMismatchCondition(Candidate c) {
        return "[ \"$(dd if=\"$D\" bs=1 skip=1024 count=1024 2>/dev/null | sha256sum | awk '{print $1}')\" != "
                + Mu300Internal.q(c.superblockSha) + " ]";
    }

    static String replayScript(String remote, Candidate c) throws IOException {
        String lib = new String(c.pkg.storageLibrary, StandardCharsets.UTF_8);
        String loader = remote + "/" + LOADER_REL, e2fsck = remote + "/" + E2FSCK_REL;
        String libs = remote + "/lib:" + remote + "/usr/lib";
        String bootId = c.device.bootId;
        String cid = c.device.s.cid;
        String dev = c.devicePath();
        if (dev.isEmpty() || !c.rootNode.equals(c.device.s.root))
            throw new IOException("候选根分区路径无效或未绑定当前内核节点；拒绝启动 e2fsck");
        long rootSize = Uninstall.sectorsBetween(c.root.start, c.root.end);
        return lib + "\nset +e\n"
                + "R=" + Mu300Internal.q(remote) + "; D=" + Mu300Internal.q(dev) + "; N=" + Mu300Internal.q(c.rootNode)
                + "; B=" + DISK_NODE + "; DEVFS=$(awk '$2==\"/dev\" {print $3; exit}' /proc/mounts)\n"
                + "if [ \"$(mu300_storage_identity \"$B\")\" != " + Mu300Internal.q(cid) + " ]"
                + " || [ \"$(mu300_storage_parent \"$N\")\" != \"$B\" ]"
                + " || [ \"$(mu300_storage_part " + ROOT_LABEL + " \"$B\")\" != \"$N\" ]"
                + " || [ \"$(cat /sys/class/block/$B/size 2>/dev/null)\" != " + c.device.s.diskSize + " ]"
                + " || [ \"$(cat /sys/class/block/$B/queue/logical_block_size 2>/dev/null)\" != " + c.device.s.sectorSize + " ]"
                + " || [ \"$(cat /sys/class/block/$N/start 2>/dev/null)\" != " + c.root.start + " ]"
                + " || [ \"$(cat /sys/class/block/$N/size 2>/dev/null)\" != " + rootSize + " ]"
                + " || ! grep -qx PARTNAME=" + ROOT_LABEL + " /sys/class/block/$N/uevent 2>/dev/null"
                + " || [ ! -b \"$D\" ] || [ \"$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)\" != " + Mu300Internal.q(bootId) + " ]"
                + " || " + gptMismatchConditions(c)
                + " || " + superblockMismatchCondition(c)
                + " || { [ \"$DEVFS\" != tmpfs ] && [ \"$DEVFS\" != devtmpfs ]; }"
                + " || [ -n \"$(ls -A /sys/class/block/$N/holders 2>/dev/null)\" ]"
                + " || awk -v n=\"$(cat /sys/class/block/$N/dev 2>/dev/null)\" '$3==n {busy=1} END {exit !busy}' /proc/self/mountinfo; then\n"
                + "  echo OWRT-REPLAY-NOT-STARTED; exit 0\nfi\n"
                + "echo OWRT-REPLAY-STARTED\n"
                + "( " + Mu300Internal.q(loader) + " --library-path " + Mu300Internal.q(libs) + " " + Mu300Internal.q(e2fsck)
                + " -p -E journal_only \"$D\" > \"$R/replay.log\" 2>&1 ) &\n"
                + "pid=$!; echo \"$pid\" > \"$R/replay.pid\"; wait \"$pid\"; rc=$?\n"
                + "cat \"$R/replay.log\" 2>/dev/null; echo \"$rc\" > \"$R/replay.rc\"; echo OWRT-E2FSCK-RC=$rc; exit 0";
    }

    static String readOnlyFsckScript(String remote, Candidate c) throws IOException {
        String loader = remote + "/" + LOADER_REL, e2fsck = remote + "/" + E2FSCK_REL;
        String libs = remote + "/lib:" + remote + "/usr/lib";
        String dev = c.devicePath();
        if (dev.isEmpty() || !c.rootNode.equals(c.device.s.root))
            throw new IOException("只读核验路径未绑定当前候选节点");
        long rootSize = Uninstall.sectorsBetween(c.root.start, c.root.end);
        return new String(c.pkg.storageLibrary, StandardCharsets.UTF_8) + "\nset +e\n"
                + "D=" + Mu300Internal.q(dev) + "; N=" + Mu300Internal.q(c.rootNode) + "; B=" + DISK_NODE + "\n"
                + "if [ \"$(mu300_storage_identity " + DISK_NODE + ")\" != " + Mu300Internal.q(c.device.s.cid)
                + " ] || [ \"$(mu300_storage_parent \"$N\")\" != \"$B\" ]"
                + " || [ \"$(mu300_storage_part " + ROOT_LABEL + " \"$B\")\" != \"$N\" ]"
                + " || [ \"$(cat /sys/class/block/$B/size 2>/dev/null)\" != " + c.device.s.diskSize + " ]"
                + " || [ \"$(cat /sys/class/block/$B/queue/logical_block_size 2>/dev/null)\" != " + c.device.s.sectorSize + " ]"
                + " || [ \"$(cat /sys/class/block/$N/start 2>/dev/null)\" != " + c.root.start
                + " ] || [ \"$(cat /sys/class/block/$N/size 2>/dev/null)\" != " + rootSize
                + " ] || ! grep -qx PARTNAME=" + ROOT_LABEL + " /sys/class/block/$N/uevent 2>/dev/null"
                + " || [ ! -b \"$D\" ] || [ \"$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)\" != " + Mu300Internal.q(c.device.bootId) + " ]"
                + " || " + gptMismatchConditions(c)
                + " || " + superblockMismatchCondition(c)
                + " || [ -n \"$(ls -A /sys/class/block/$N/holders 2>/dev/null)\" ]"
                + " || awk -v n=\"$(cat /sys/class/block/$N/dev 2>/dev/null)\" '$3==n {busy=1} END {exit !busy}' /proc/self/mountinfo; then"
                + " echo OWRT-VERIFY-NOT-STARTED; exit 0; fi\n"
                + "LC_ALL=C " + Mu300Internal.q(loader) + " --library-path " + Mu300Internal.q(libs) + " "
                + Mu300Internal.q(e2fsck) + " -f -n \"$D\" > " + Mu300Internal.q(remote + "/verify.log") + " 2>&1; rc=$?\n"
                + "cat " + Mu300Internal.q(remote + "/verify.log") + " 2>/dev/null; echo OWRT-READONLY-E2FSCK-RC=$rc; exit 0";
    }

    private static int runReadOnlyFsck(Mu300Internal.Io io, String remote, Candidate c) throws IOException {
        String cmd = readOnlyFsckScript(remote, c);
        String out = io.sh(cmd, true, 1800, line -> io.status(line));
        if (out.contains("OWRT-VERIFY-NOT-STARTED")) throw new IOException("只读 e2fsck 前的设备/挂载复核失败");
        return parseRc(out, "OWRT-READONLY-E2FSCK-RC=");
    }

    private static boolean remoteProcessAlive(Mu300Internal.Io io, String remote) throws IOException {
        if (remote == null || !remote.matches("/dev/\\.owrt-ext4-journal-[a-f0-9]{32}"))
            throw new IOException("修复记录中的 RAM 路径无效");
        String pidFile = remote + "/replay.pid";
        String script = "set +e; R=" + Mu300Internal.q(remote) + "; f=" + Mu300Internal.q(pidFile) + "; "
                + "if [ ! -d \"$R\" ] || [ ! -r \"$f\" ]; then echo STOPPED; exit 0; fi; "
                + "p=$(cat \"$f\" 2>/dev/null); case \"$p\" in ''|*[!0-9]*) echo UNKNOWN; exit 0;; esac; "
                + "if kill -0 \"$p\" 2>/dev/null; then c=$(tr '\\000' ' ' < /proc/$p/cmdline 2>/dev/null); "
                + "case \"$c\" in *\"$R/usr/sbin/e2fsck\"*) echo RUNNING;; '') echo UNKNOWN;; *) echo STOPPED;; esac; "
                + "else echo STOPPED; fi; exit 0";
        String out = io.sh(script, true).trim();
        if ("RUNNING".equals(out) || "UNKNOWN".equals(out)) return true;
        if (!"STOPPED".equals(out)) throw new IOException("无法确认中断的 e2fsck 是否仍在运行；拒绝继续");
        return false;
    }

    private static void cleanupRemote(Mu300Internal.Io io, String remote) {
        if (io == null || remote == null || !remote.matches("/dev/\\.owrt-ext4-journal-[a-f0-9]{32}")) return;
        try { io.sh("case " + Mu300Internal.q(remote) + " in /dev/.owrt-ext4-journal-*) rm -rf " + Mu300Internal.q(remote) + ";; esac", true); }
        catch (IOException ignored) { }
    }

    private static int parseRc(String out, String marker) throws IOException {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(marker) + "([0-9]{1,3})\\s*$").matcher(out);
        int rc = -1;
        while (m.find()) rc = Integer.parseInt(m.group(1));
        if (rc < 0 || rc > 255) throw new IOException("修复工具未返回可核验的退出码；按中断处理，不会自动重试");
        return rc;
    }

    private static int u16(byte[] b, int p) { return (b[p] & 255) | ((b[p + 1] & 255) << 8); }
    private static long u32(byte[] b, int p) {
        return (b[p] & 255L) | ((b[p + 1] & 255L) << 8) | ((b[p + 2] & 255L) << 16) | ((b[p + 3] & 255L) << 24);
    }
    private static String hex(byte[] b, int off, int len) {
        StringBuilder s = new StringBuilder(len * 2);
        for (int i = off; i < off + len; i++) s.append(String.format(Locale.US, "%02x", b[i] & 255));
        return s.toString();
    }

    private static void writeAtomic(File target, byte[] bytes) throws IOException {
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) throw new IOException("无法创建事务记录目录");
        File tmp = new File(parent, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(bytes); out.flush(); out.getFD().sync(); }
        if (target.exists() && !target.delete()) throw new IOException("无法替换事务记录");
        if (!tmp.renameTo(target)) { tmp.delete(); throw new IOException("无法提交事务记录"); }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }
}
