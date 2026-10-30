package top.linote.owrtinstaller;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.regex.Pattern;

/**
 * In-memory partition planning and preview only. This class never opens a block device and has no
 * GPT write/apply API. Device writes remain disabled because the available source/evidence does not
 * establish profile-specific Android recovery, userdata encryption, mount, and boot compatibility.
 * All geometry is represented as 512-byte sectors; GPT LBAs are converted using the logical block size.
 */
final class PartitionPlanner {
    private PartitionPlanner() { }

    static final long ALIGNMENT_SECTORS = 2048L; // 1 MiB, expressed in 512-byte sectors
    static final int ENTRY_BYTES = 128;
    static final int ENTRY_COUNT_LIMIT = 1024;
    static final String LINUX_FILESYSTEM_GUID = "0fc63daf-8483-4772-8e79-3d69d8477de4";
    static final String WRITE_BLOCKER = "此版本没有设备分区写入入口：缺少设备/固件专属的 Android 启动与 userdata/recovery 加密兼容证据；不得据此声称改分区后 Android 一定能启动。";
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9._-]{1,36}");
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    private static final String ZERO_GUID = "00000000-0000-0000-0000-000000000000";
    private static final long MAX_ZIP_SCAN_BYTES = 1L << 30;

    enum Origin { CURRENT_DEVICE_READ_ONLY, BASELINE_BACKUP, SYNTHETIC }

    static final class Entry {
        final int number;
        final String typeGuid;
        final String uniqueGuid;
        final String name;
        final long start; // inclusive, 512-byte sectors
        final long end;   // inclusive, 512-byte sectors
        final long attributes;

        Entry(int number, String typeGuid, String uniqueGuid, String name, long start, long end, long attributes) {
            this.number = number;
            this.typeGuid = typeGuid;
            this.uniqueGuid = uniqueGuid;
            this.name = name;
            this.start = start;
            this.end = end;
            this.attributes = attributes;
        }

        long sectors() { return end - start + 1; }
        long sizeMiB() { return sectors() / ALIGNMENT_SECTORS; }

        boolean sameAs(Entry other) {
            return other != null && number == other.number && typeGuid.equals(other.typeGuid)
                    && uniqueGuid.equals(other.uniqueGuid) && name.equals(other.name)
                    && start == other.start && end == other.end && attributes == other.attributes;
        }
    }

    /** Validated GPT bytes and source provenance. Bytes stay in memory and are never persisted here. */
    static final class Snapshot {
        final byte[] first;
        final byte[] last;
        final long sectors512;
        final int logicalBlockSize;
        final Origin origin;
        final String identityDigest;
        final String fingerprint;
        final List<Entry> entries;
        final List<String> integrityWarnings;
        final long firstUsable512;
        final long lastUsable512;

        private Snapshot(byte[] first, byte[] last, long sectors512, Origin origin, String identityDigest) throws IOException {
            if (first == null || last == null || origin == null || sectors512 <= 0)
                throw new IOException("GPT snapshot fields are incomplete");
            try {
                Mu300Gpt.Space verified = Mu300Gpt.space(first, last, sectors512);
                logicalBlockSize = verified.blockSize;
                int factor = logicalBlockSize / 512;
                byte[] primary = Arrays.copyOfRange(first, logicalBlockSize, logicalBlockSize * 2);
                ByteBuffer h = le(primary);
                long firstLba = h.getLong(40), lastLba = h.getLong(48);
                if (firstLba < 0 || lastLba < firstLba || lastLba > Long.MAX_VALUE / factor)
                    throw new IOException("GPT usable bounds are invalid");
                firstUsable512 = checkedMultiplyExact(firstLba, factor);
                lastUsable512 = Math.addExact(checkedMultiplyExact(lastLba, factor), factor - 1L);
                List<Entry> parsed = readEntries(first, primary, factor, firstUsable512, lastUsable512);
                integrityWarnings = Collections.unmodifiableList(identityWarnings(parsed));
                entries = Collections.unmodifiableList(parsed);
                if (origin == Origin.BASELINE_BACKUP) checkNoOverlap(entries);
                else checkUnique(entries);
            } catch (IllegalArgumentException | ArithmeticException e) {
                throw new IOException("GPT 当前快照未通过主备表/CRC/几何校验：" + e.getMessage(), e);
            }
            if (origin == Origin.CURRENT_DEVICE_READ_ONLY) {
                if (identityDigest == null || !SHA256.matcher(identityDigest).matches())
                    throw new IOException("当前布局证据缺少经过哈希的设备绑定标识");
            }
            this.first = first.clone();
            this.last = last.clone();
            this.sectors512 = sectors512;
            this.origin = origin;
            this.identityDigest = identityDigest == null ? "" : identityDigest;
            this.fingerprint = sha256(join(first, last, longBytes(sectors512), intBytes(logicalBlockSize),
                    this.identityDigest.getBytes(StandardCharsets.US_ASCII)));
        }

        static Snapshot verified(byte[] first, byte[] last, long sectors512, Origin origin, String identityDigest) throws IOException {
            return new Snapshot(first, last, sectors512, origin, identityDigest);
        }

        boolean independentlyReadOnly() { return origin == Origin.CURRENT_DEVICE_READ_ONLY; }
    }

    static final class NewPartition {
        final String purpose;
        final String label;
        final long sizeMiB;
        final String uniqueGuid; // optional; deterministic per plan when null

        NewPartition(String purpose, String label, long sizeMiB) { this(purpose, label, sizeMiB, null); }
        NewPartition(String purpose, String label, long sizeMiB, String uniqueGuid) {
            this.purpose = purpose == null ? "" : purpose.trim();
            this.label = label == null ? "" : label.trim();
            this.sizeMiB = sizeMiB;
            this.uniqueGuid = uniqueGuid == null ? "" : uniqueGuid.trim().toLowerCase(Locale.ROOT);
        }
    }

    static final class Request {
        final long userdataTargetMiB;
        final boolean formatUserdata;
        final boolean userdataPreservationEvidence;
        final String typedWipeConfirmation;
        final List<NewPartition> partitions;

        Request(long userdataTargetMiB, boolean formatUserdata, boolean userdataPreservationEvidence,
                String typedWipeConfirmation, List<NewPartition> partitions) {
            this.userdataTargetMiB = userdataTargetMiB;
            this.formatUserdata = formatUserdata;
            this.userdataPreservationEvidence = userdataPreservationEvidence;
            this.typedWipeConfirmation = typedWipeConfirmation == null ? "" : typedWipeConfirmation.trim();
            this.partitions = partitions == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(partitions));
        }

        Request withWipeConfirmation(String phrase) {
            return new Request(userdataTargetMiB, formatUserdata, userdataPreservationEvidence, phrase, partitions);
        }
    }

    static final class Preview {
        final Snapshot current;
        final Snapshot baseline;
        final List<Entry> proposedEntries;
        final byte[] firstAfter;
        final byte[] lastAfter;
        final boolean baselineMatches;
        final boolean destructiveConfirmationRequired;
        final boolean destructiveConfirmationAccepted;
        final boolean primaryCrcValid;
        final boolean backupCrcValid;
        final String fingerprint;
        final String requiredWipePhrase;
        final List<String> blockers;
        final List<String> impacts;

        private Preview(Snapshot current, Snapshot baseline, List<Entry> proposedEntries, byte[] firstAfter,
                        byte[] lastAfter, boolean baselineMatches, boolean wipeRequired, boolean wipeAccepted,
                        String fingerprint, List<String> blockers, List<String> impacts) {
            this.current = current;
            this.baseline = baseline;
            this.proposedEntries = Collections.unmodifiableList(new ArrayList<>(proposedEntries));
            this.firstAfter = firstAfter.clone();
            this.lastAfter = lastAfter.clone();
            this.baselineMatches = baselineMatches;
            this.destructiveConfirmationRequired = wipeRequired;
            this.destructiveConfirmationAccepted = wipeAccepted;
            this.primaryCrcValid = true;
            this.backupCrcValid = true;
            this.fingerprint = fingerprint;
            this.requiredWipePhrase = wipeRequired ? wipePhrase(fingerprint) : "";
            this.blockers = Collections.unmodifiableList(new ArrayList<>(blockers));
            this.impacts = Collections.unmodifiableList(new ArrayList<>(impacts));
        }

        /** A hard constant, not a UI preference or user override. */
        boolean deviceWriteAllowed() { return false; }

        String dataImpact() {
            StringBuilder b = new StringBuilder();
            for (String item : impacts) b.append("• ").append(item).append('\n');
            return b.length() == 0 ? "无新建或格式化的数据影响" : b.toString().trim();
        }

        String gptImpact() {
            return "仅内存预览：GPT 主表与备份表都会因 userdata/新分区表项而改变；"
                    + "主、备表及两份 CRC 均已在合成结果中重新计算并校验。不会写入磁盘。";
        }
    }

    /**
     * Computes a preview from two separate inputs: an independently read-only current snapshot and an
     * optional historical baseline. The baseline is never copied to a device; a mismatch is shown and
     * blocks any hypothetical apply path. The current implementation has no such apply path at all.
     */
    static Preview preview(Snapshot current, Snapshot baseline, Request request) throws IOException {
        if (current == null || request == null) throw new IOException("当前布局或规划参数缺失");
        if (request.partitions.isEmpty()) throw new IOException("至少添加一个新分区");
        Entry userdata = uniqueUserdata(current.entries);
        if (userdata.start % ALIGNMENT_SECTORS != 0)
            throw new IOException("userdata 起始扇区不是 1 MiB 对齐；不得移动或重建其起始边界");
        if (request.userdataTargetMiB < 2048 || request.userdataTargetMiB > userdata.sectors() / ALIGNMENT_SECTORS)
            throw new IOException("userdata 新容量必须在 2048 MiB 与当前容量之间；本规划器只允许从原边界向后缩小");

        List<String> impacts = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        if (!current.independentlyReadOnly()) blockers.add("当前布局不是独立获取的设备 GPT 只读快照（来源：" + current.origin + "）");
        if (baseline == null) blockers.add("没有原始 GPT 备份基线；基线只用于比较，不能作为待写回布局");
        boolean baselineMatches = baseline != null && sameSnapshot(current, baseline);
        if (baseline != null && !baselineMatches)
            blockers.add("当前 GPT 与原始备份基线不匹配；旧备份可能是安装前布局，禁止直接写回或按旧表规划设备写入");
        if (baseline != null && !baseline.integrityWarnings.isEmpty())
            blockers.add("历史 GPT 基线有结构警告（仅保留作比较，不可作为当前布局）：检测到 "
                    + baseline.integrityWarnings.size() + " 项重复/异常 GUID 或标签；具体值不显示");
        blockers.add("缺少设备型号/固件专属的 Android boot、recovery、userdata 类型 GUID、加密/metadata/vold/fstab 兼容证明");
        blockers.add(WRITE_BLOCKER);

        if (request.formatUserdata) {
            impacts.add("userdata 将被格式化：其中应用、照片、聊天记录、设置及加密元数据都会清空；Android 之后能否重新创建加密并启动尚未证明");
        } else if (request.userdataTargetMiB < userdata.sectors() / ALIGNMENT_SECTORS) {
            impacts.add("userdata 只从尾部缩小；起始扇区、类型 GUID、唯一 GUID、名称及属性保持不变");
            if (!request.userdataPreservationEvidence)
                blockers.add("保留 userdata 数据需要本机文件系统、加密状态、可用空间和恢复流程证据；当前仅为模拟");
        }
        impacts.add("所有既有非 userdata 分区（包括 boot/vbmeta/recovery/misc/super/system/metadata 和未知分区）原样保留；规划器不移动、不删除、不覆盖它们");
        impacts.add("每个新分区占用 GPT 解析出的空闲扇区，初始化/格式化后为空；不会覆盖已有分区数据");

        long oldUserMiB = userdata.sectors() / ALIGNMENT_SECTORS;
        long newUserSectors = Math.multiplyExact(request.userdataTargetMiB, ALIGNMENT_SECTORS);
        if (newUserSectors % (current.logicalBlockSize / 512) != 0)
            throw new IOException("userdata 容量未按 GPT 逻辑块对齐");
        long newUserEnd = Math.addExact(userdata.start, newUserSectors) - 1;
        if (newUserEnd > userdata.end) throw new IOException("userdata 不能扩展越过当前边界");

        List<Entry> working = new ArrayList<>();
        for (Entry old : current.entries) {
            if (old.number == userdata.number) {
                working.add(new Entry(old.number, old.typeGuid, old.uniqueGuid, old.name, old.start, newUserEnd, old.attributes));
            } else {
                working.add(old);
            }
        }
        List<NewPartition> specs = new ArrayList<>(request.partitions);
        Set<String> labels = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (Entry old : current.entries) {
            labels.add(old.name.toLowerCase(Locale.ROOT));
            ids.add(old.uniqueGuid.toLowerCase(Locale.ROOT));
        }
        int factor = current.logicalBlockSize / 512;
        int entryCount = entryCount(current.first, current.logicalBlockSize);
        Set<Integer> emptyEntries = emptyEntryNumbers(current.first, current.logicalBlockSize);
        for (int i = 0; i < specs.size(); i++) {
            NewPartition spec = specs.get(i);
            if (!LABEL.matcher(spec.label).matches()) throw new IOException("分区标签只能含 1–36 个 ASCII 字母、数字、点、下划线或短横线：" + spec.label);
            if (spec.purpose.isEmpty()) throw new IOException("每个新分区都必须说明用途");
            if (spec.sizeMiB < 1) throw new IOException("新分区大小必须大于 0 MiB：" + spec.label);
            if (!labels.add(spec.label.toLowerCase(Locale.ROOT))) throw new IOException("分区标签重复：" + spec.label);
            if (emptyEntries.isEmpty()) throw new IOException("GPT 没有空分区表项，拒绝增加分区");
            long sizeSectors = Math.multiplyExact(spec.sizeMiB, ALIGNMENT_SECTORS);
            if (sizeSectors % factor != 0) throw new IOException("新分区大小未按 GPT 逻辑块对齐：" + spec.label);
            String id = spec.uniqueGuid.isEmpty() ? deterministicGuid(current.fingerprint, request, i, spec) : canonicalGuid(spec.uniqueGuid);
            if (ZERO_GUID.equals(id) || !ids.add(id)) throw new IOException("新分区唯一 GUID 无效或重复：" + spec.label);
            int number = emptyEntries.iterator().next();
            emptyEntries.remove(number);
            long[] range = allocate(working, current.firstUsable512, current.lastUsable512, sizeSectors);
            Entry added = new Entry(number, LINUX_FILESYSTEM_GUID, id, spec.label, range[0], range[1], 0);
            working.add(added);
            impacts.add("新建 " + spec.label + "（" + spec.purpose + "）：" + spec.sizeMiB + " MiB，扇区 " + range[0] + "–" + range[1] + "；从空闲空间初始化/格式化");
        }
        Collections.sort(working, Comparator.comparingInt(e -> e.number));
        verifyProtectedEntries(current.entries, working, userdata.number);
        checkUnique(working);
        for (Entry e : working) {
            if (e.start < current.firstUsable512 || e.end > current.lastUsable512 || e.start > e.end)
                throw new IOException("规划后分区越过 GPT usable bounds：" + e.name);
            if (e.number <= 0 || e.number > entryCount) throw new IOException("分区表项编号超界：" + e.number);
        }
        byte[][] changed = changeGptInMemory(current, userdata.number, newUserEnd, working, factor);
        try { Mu300Gpt.space(changed[0], changed[1], current.sectors512); }
        catch (IllegalArgumentException e) { throw new IOException("内存 GPT 预览未通过主备表/CRC/无重叠复核；停止：" + e.getMessage(), e); }

        String fingerprint = planFingerprint(current, baseline, request, changed[0], changed[1]);
        boolean wipeRequired = request.formatUserdata;
        String required = wipeRequired ? wipePhrase(fingerprint) : "";
        boolean wipeAccepted = wipeRequired && required.equals(request.typedWipeConfirmation);
        if (wipeRequired && !wipeAccepted) blockers.add("清空 userdata 尚未针对本份具体计划输入完整确认语：" + required);
        if (request.formatUserdata && !request.typedWipeConfirmation.isEmpty() && !wipeAccepted)
            blockers.add("清空 userdata 确认语与当前计划指纹不匹配；计划变更后必须重新确认");
        return new Preview(current, baseline, working, changed[0], changed[1], baselineMatches,
                wipeRequired, wipeAccepted, fingerprint, blockers, impacts);
    }

    static String wipePhrase(String fingerprint) { return "清空 userdata " + fingerprint.substring(0, 12); }

    static String formatRange(Entry entry) {
        return entry.name + " · " + entry.sizeMiB() + " MiB · " + entry.start + "–" + entry.end;
    }

    /** Generic synthetic 16 GiB layout for offline UI and tests; it contains no real device identity. */
    static Snapshot syntheticBaseline(int blockSize) throws IOException {
        return syntheticLayout(blockSize, false, Origin.BASELINE_BACKUP);
    }

    /** Synthetic post-install layout intentionally differs from the historical baseline. */
    static Snapshot syntheticCurrent(int blockSize) throws IOException {
        return syntheticLayout(blockSize, true, Origin.SYNTHETIC);
    }

    /**
     * Reads only the GPT baseline members of the installer's backup ZIP. No entry is extracted to
     * storage; unrelated images/identity records are streamed past and discarded. This is historical
     * evidence only, never current-device evidence and never a layout to write back.
     */
    static Snapshot readBaselineZip(InputStream input) throws IOException {
        SafeImport.ArchiveReport report = SafeImport.readBackupZip(input);
        if (report.backups.size() != 1)
            throw new IOException("ZIP 必须恰好包含一份可用 GPT 历史基线；请在导入界面选择具体备份");
        return report.backups.get(0).baseline;
    }

    private static byte[] readBounded(InputStream in, int limit, String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            if (n == 0) continue;
            if (out.size() > limit - n) throw new IOException("GPT 备份项目超过安全上限：" + name);
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void verifyManifestHash(byte[] manifest, String basename, byte[] content) throws IOException {
        String text = new String(manifest, StandardCharsets.UTF_8);
        String found = null;
        for (String line : text.split("\\r?\\n")) {
            String[] fields = line.trim().split("\\s+", 2);
            if (fields.length == 2 && fields[1].replaceFirst("^\\*", "").endsWith(basename)) {
                if (found != null || !SHA256.matcher(fields[0]).matches()) throw new IOException("SHA256SUMS 中 GPT 项格式重复或无效：" + basename);
                found = fields[0];
            }
        }
        if (found == null || !found.equals(sha256(content))) throw new IOException("GPT 基线文件 SHA-256 校验失败：" + basename);
    }

    private static long jsonLong(byte[] json, String key) throws IOException {
        String text = new String(json, StandardCharsets.UTF_8);
        java.util.regex.Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*([0-9]+)").matcher(text);
        if (!m.find()) throw new IOException("gpt-layout.json 缺少数值字段：" + key);
        try { return Long.parseLong(m.group(1)); }
        catch (NumberFormatException e) { throw new IOException("gpt-layout.json 数值越界：" + key, e); }
    }

    private static Snapshot syntheticLayout(int blockSize, boolean withRoot, Origin origin) throws IOException {
        if (blockSize != 512 && blockSize != 4096) throw new IOException("Unsupported synthetic GPT block size");
        int factor = blockSize / 512;
        long sectors512 = 16L * 1024 * 1024 * 1024 / 512;
        long logical = sectors512 / factor;
        int tableBlocks = (ENTRY_COUNT_LIMIT * ENTRY_BYTES + blockSize - 1) / blockSize;
        long firstTable = 2;
        long backupTable = logical - 1 - tableBlocks;
        long userdataStart = 4096L * ALIGNMENT_SECTORS;
        byte[] table = new byte[ENTRY_COUNT_LIMIT * ENTRY_BYTES];
        int n = 1;
        n = putSyntheticEntry(table, n, "boot_a", 1L * ALIGNMENT_SECTORS, 16L * ALIGNMENT_SECTORS, "11111111-1111-4111-8111-111111111101", factor);
        n = putSyntheticEntry(table, n, "boot_b", 17L * ALIGNMENT_SECTORS, 16L * ALIGNMENT_SECTORS, "11111111-1111-4111-8111-111111111102", factor);
        n = putSyntheticEntry(table, n, "vbmeta_a", 33L * ALIGNMENT_SECTORS, 2L * ALIGNMENT_SECTORS, "11111111-1111-4111-8111-111111111103", factor);
        n = putSyntheticEntry(table, n, "vbmeta_b", 35L * ALIGNMENT_SECTORS, 2L * ALIGNMENT_SECTORS, "11111111-1111-4111-8111-111111111104", factor);
        n = putSyntheticEntry(table, n, "misc", 37L * ALIGNMENT_SECTORS, 2L * ALIGNMENT_SECTORS, "11111111-1111-4111-8111-111111111105", factor);
        n = putSyntheticEntry(table, n, "metadata", 39L * ALIGNMENT_SECTORS, 16L * ALIGNMENT_SECTORS, "11111111-1111-4111-8111-111111111106", factor);
        long systemStart = 64L * ALIGNMENT_SECTORS;
        putSyntheticEntry(table, n, "system", systemStart, userdataStart - systemStart, "11111111-1111-4111-8111-111111111107", factor);
        int u = 8;
        putSyntheticEntry(table, u, "userdata", userdataStart, 8192L * ALIGNMENT_SECTORS, "11111111-1111-4111-8111-111111111108", factor);
        if (withRoot) putSyntheticEntry(table, 9, "mu300root", userdataStart + 8192L * ALIGNMENT_SECTORS,
                1024L * ALIGNMENT_SECTORS, "11111111-1111-4111-8111-111111111109", factor);

        byte[] first = new byte[(int) ((firstTable + tableBlocks) * blockSize)];
        byte[] last = new byte[(int) ((1 + tableBlocks) * blockSize)];
        System.arraycopy(table, 0, first, (int) (firstTable * blockSize), table.length);
        System.arraycopy(table, 0, last, 0, table.length);
        UUID diskGuid = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        writeHeader(first, blockSize, blockSize, 1, logical - 1, firstTable, 2 + tableBlocks,
                logical - 2 - tableBlocks, diskGuid, table);
        writeHeader(last, last.length - blockSize, blockSize, logical - 1, 1, backupTable, 2 + tableBlocks,
                logical - 2 - tableBlocks, diskGuid, table);
        return Snapshot.verified(first, last, sectors512, origin, "");
    }

    private static int putSyntheticEntry(byte[] table, int number, String name, long start512, long size512, String uniqueGuid, int factor) {
        int offset = (number - 1) * ENTRY_BYTES;
        System.arraycopy(Mu300Gpt.guidLe(UUID.fromString(LINUX_FILESYSTEM_GUID)), 0, table, offset, 16);
        System.arraycopy(Mu300Gpt.guidLe(UUID.fromString(uniqueGuid)), 0, table, offset + 16, 16);
        ByteBuffer b = le(table);
        b.putLong(offset + 32, start512 / factor);
        b.putLong(offset + 40, (start512 + size512 - 1) / factor);
        byte[] encoded = name.getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(encoded, 0, table, offset + 56, encoded.length);
        return number + 1;
    }

    private static void writeHeader(byte[] raw, int offset, int blockSize, long current, long alternate,
                                    long tableLba, long firstUsable, long lastUsable, UUID diskGuid, byte[] table) {
        byte[] h = new byte[blockSize];
        System.arraycopy("EFI PART".getBytes(StandardCharsets.US_ASCII), 0, h, 0, 8);
        put32(h, 8, 0x00010000); put32(h, 12, 92);
        put64(h, 24, current); put64(h, 32, alternate); put64(h, 40, firstUsable); put64(h, 48, lastUsable);
        System.arraycopy(Mu300Gpt.guidLe(diskGuid), 0, h, 56, 16);
        put64(h, 72, tableLba); put32(h, 80, ENTRY_COUNT_LIMIT); put32(h, 84, ENTRY_BYTES);
        put32(h, 88, Mu300Boot.crc32(table, 0, table.length)); put32(h, 16, 0); put32(h, 16, Mu300Boot.crc32(h, 0, 92));
        System.arraycopy(h, 0, raw, offset, blockSize);
    }

    private static List<Entry> readEntries(byte[] first, byte[] primary, int factor, long firstUsable, long lastUsable) throws IOException {
        ByteBuffer h = le(primary);
        int count = checkedInt(u32(h, 80), "GPT entry count");
        int entrySize = checkedInt(u32(h, 84), "GPT entry size");
        if (count < 1 || count > ENTRY_COUNT_LIMIT || entrySize != ENTRY_BYTES) throw new IOException("Unsupported GPT entry geometry");
        long tableLba = h.getLong(72);
        long tableOffsetLong = Math.multiplyExact(tableLba, primary.length == 0 ? 512L : (long) Mu300Gpt.sectorSize(first));
        int tableOffset = checkedInt(tableOffsetLong, "GPT primary table offset");
        int len = Math.multiplyExact(count, entrySize);
        if (tableOffset < 0 || tableOffset > first.length - len) throw new IOException("GPT primary entry table is outside the snapshot");
        List<Entry> out = new ArrayList<>();
        ByteBuffer t = le(first);
        for (int i = 0; i < count; i++) {
            int o = tableOffset + i * entrySize;
            byte[] type = Arrays.copyOfRange(first, o, o + 16);
            if (allZero(type)) continue;
            byte[] id = Arrays.copyOfRange(first, o + 16, o + 32);
            String typeGuid = guidString(type), uniqueGuid = guidString(id);
            long startLba = t.getLong(o + 32), endLba = t.getLong(o + 40);
            if (startLba < 0 || endLba < startLba || endLba > Long.MAX_VALUE / factor)
                throw new IOException("GPT entry has an invalid range");
            long start = checkedMultiplyExact(startLba, factor);
            long end = Math.addExact(checkedMultiplyExact(endLba, factor), factor - 1L);
            if (start < firstUsable || end > lastUsable) throw new IOException("GPT entry is outside usable bounds");
            long attrs = t.getLong(o + 48);
            String rawName = new String(first, o + 56, 72, StandardCharsets.UTF_16LE);
            int nul = rawName.indexOf('\0');
            String name = (nul < 0 ? rawName : rawName.substring(0, nul)).trim();
            if (name.isEmpty()) throw new IOException("Non-empty GPT entry has an empty partition name");
            out.add(new Entry(i + 1, typeGuid, uniqueGuid, name, start, end, attrs));
        }
        return out;
    }

    private static void checkUnique(List<Entry> entries) throws IOException {
        List<String> warnings = identityWarnings(entries);
        if (!warnings.isEmpty()) throw new IOException(warnings.get(0));
        checkNoOverlap(entries);
    }

    private static List<String> identityWarnings(List<Entry> entries) {
        Set<String> ids = new HashSet<>(), labels = new HashSet<>();
        List<String> warnings = new ArrayList<>();
        for (Entry e : entries) {
            if (ZERO_GUID.equals(e.typeGuid)) warnings.add("分区类型 GUID 无效：" + e.name);
            if (ZERO_GUID.equals(e.uniqueGuid)) warnings.add("分区唯一 GUID 无效：" + e.name);
            if (!ids.add(e.uniqueGuid.toLowerCase(Locale.ROOT))) warnings.add("GPT 唯一 GUID 重复：" + e.name);
            if (!labels.add(e.name.toLowerCase(Locale.ROOT))) warnings.add("GPT 分区标签重复：" + e.name);
        }
        return warnings;
    }

    private static void checkNoOverlap(List<Entry> entries) throws IOException {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingLong(e -> e.start));
        Entry previous = null;
        for (Entry e : sorted) {
            if (e.start > e.end) throw new IOException("GPT 分区边界无效：" + e.name);
            if (previous != null && e.start <= previous.end) throw new IOException("GPT 分区重叠：" + previous.name + " / " + e.name);
            previous = e;
        }
    }

    private static Entry uniqueUserdata(List<Entry> entries) throws IOException {
        Entry found = null;
        for (Entry e : entries) if ("userdata".equalsIgnoreCase(e.name)) {
            if (found != null) throw new IOException("GPT 存在多个 userdata 标签，拒绝选择其中之一");
            found = e;
        }
        if (found == null) throw new IOException("当前 GPT 未找到唯一 userdata 分区");
        return found;
    }

    private static void verifyProtectedEntries(List<Entry> original, List<Entry> planned, int userdataNumber) throws IOException {
        for (Entry old : original) {
            Entry next = null;
            for (Entry n : planned) if (n.number == old.number) { next = n; break; }
            if (next == null) throw new IOException("规划尝试删除既有分区：" + old.name);
            if (old.number == userdataNumber) {
                if (!old.typeGuid.equals(next.typeGuid) || !old.uniqueGuid.equals(next.uniqueGuid)
                        || !old.name.equals(next.name) || old.start != next.start || old.attributes != next.attributes
                        || next.end > old.end)
                    throw new IOException("userdata 只能保留原起始边界/类型/GUID/名称/属性并向后缩小");
            } else if (!old.sameAs(next)) {
                throw new IOException("规划尝试移动或修改既有 Android/启动关键分区：" + old.name);
            }
        }
    }

    private static long[] allocate(List<Entry> occupiedEntries, long firstUsable, long lastUsable, long need) throws IOException {
        List<Entry> occupied = new ArrayList<>(occupiedEntries);
        occupied.sort(Comparator.comparingLong(e -> e.start));
        long at = firstUsable;
        for (Entry e : occupied) {
            if (e.start < at) throw new IOException("规划前后分区重叠：" + e.name);
            long start = alignUp(at);
            if (start <= e.start - 1 && need <= e.start - start) return new long[]{start, start + need - 1};
            at = Math.max(at, e.end + 1);
        }
        long start = alignUp(at);
        if (start <= lastUsable && need - 1 <= lastUsable - start) return new long[]{start, start + need - 1};
        throw new IOException("所有 GPT 空闲范围均不足以容纳所请求的新分区");
    }

    private static long alignUp(long n) throws IOException {
        if (n < 0 || n > Long.MAX_VALUE - (ALIGNMENT_SECTORS - 1)) throw new IOException("分区对齐计算溢出");
        return ((n + ALIGNMENT_SECTORS - 1) / ALIGNMENT_SECTORS) * ALIGNMENT_SECTORS;
    }

    private static byte[][] changeGptInMemory(Snapshot current, int userdataNumber, long userdataEnd, List<Entry> planned, int factor) throws IOException {
        int block = current.logicalBlockSize;
        byte[] first = current.first.clone(), last = current.last.clone();
        byte[] primaryHeader = Arrays.copyOfRange(first, block, block * 2);
        int count = entryCount(first, block);
        long tableLba = le(primaryHeader).getLong(72);
        int tableOffset = checkedInt(checkedMultiplyExact(tableLba, block), "primary table offset");
        int tableLength = Math.multiplyExact(count, ENTRY_BYTES);
        if (tableOffset < 0 || tableOffset > first.length - tableLength || tableLength > last.length - block)
            throw new IOException("GPT table ranges are not fully present in the snapshots");
        byte[] table = Arrays.copyOfRange(first, tableOffset, tableOffset + tableLength);
        if (!Arrays.equals(table, Arrays.copyOfRange(last, 0, tableLength))) throw new IOException("GPT primary/backup tables differ before planning");
        for (Entry e : planned) {
            int o = (e.number - 1) * ENTRY_BYTES;
            if (o < 0 || o > table.length - ENTRY_BYTES) throw new IOException("GPT table entry index is outside the entry array");
            if (e.number == userdataNumber) {
                put64(table, o + 40, (e.end + 1) / factor - 1);
            } else if (!containsNumber(current.entries, e.number)) {
                byte[] entry = new byte[ENTRY_BYTES];
                System.arraycopy(Mu300Gpt.guidLe(UUID.fromString(e.typeGuid)), 0, entry, 0, 16);
                System.arraycopy(Mu300Gpt.guidLe(UUID.fromString(e.uniqueGuid)), 0, entry, 16, 16);
                put64(entry, 32, e.start / factor);
                put64(entry, 40, e.end / factor);
                put64(entry, 48, e.attributes);
                byte[] name = e.name.getBytes(StandardCharsets.UTF_16LE);
                if (name.length > 72) throw new IOException("GPT label is too long");
                System.arraycopy(name, 0, entry, 56, name.length);
                System.arraycopy(entry, 0, table, o, ENTRY_BYTES);
            }
        }
        System.arraycopy(table, 0, first, tableOffset, table.length);
        System.arraycopy(table, 0, last, 0, table.length);
        int primaryOffset = block;
        int backupOffset = last.length - block;
        put32(first, primaryOffset + 88, Mu300Boot.crc32(table, 0, table.length));
        put32(last, backupOffset + 88, Mu300Boot.crc32(table, 0, table.length));
        refreshHeaderCrc(first, primaryOffset, block);
        refreshHeaderCrc(last, backupOffset, block);
        return new byte[][]{first, last};
    }

    private static void refreshHeaderCrc(byte[] snapshot, int offset, int block) throws IOException {
        long size = u32(le(snapshot), offset + 12);
        if (size < 92 || size > block) throw new IOException("GPT header size is invalid during preview");
        put32(snapshot, offset + 16, 0);
        put32(snapshot, offset + 16, Mu300Boot.crc32(snapshot, offset, (int) size));
    }

    private static String deterministicGuid(String current, Request request, int index, NewPartition p) {
        String s = current + "|" + request.userdataTargetMiB + "|" + request.formatUserdata + "|" + index + "|" + p.label + "|" + p.sizeMiB;
        return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String planFingerprint(Snapshot current, Snapshot baseline, Request request, byte[] firstAfter, byte[] lastAfter) {
        StringBuilder c = new StringBuilder(current.fingerprint).append('|').append(baseline == null ? "none" : baseline.fingerprint)
                .append('|').append(request.userdataTargetMiB).append('|').append(request.formatUserdata);
        for (NewPartition p : request.partitions) c.append('|').append(p.purpose).append('|').append(p.label).append('|').append(p.sizeMiB).append('|').append(p.uniqueGuid);
        return sha256(join(c.toString().getBytes(StandardCharsets.UTF_8), firstAfter, lastAfter));
    }

    private static boolean sameSnapshot(Snapshot a, Snapshot b) {
        return a != null && b != null && a.sectors512 == b.sectors512 && a.logicalBlockSize == b.logicalBlockSize
                && Arrays.equals(a.first, b.first) && Arrays.equals(a.last, b.last);
    }

    private static boolean containsNumber(List<Entry> entries, int n) {
        for (Entry e : entries) if (e.number == n) return true;
        return false;
    }

    /** API-26-compatible equivalent of Math.multiplyExact(long, int). */
    static long checkedMultiplyExact(long value, int factor) {
        if (value == 0 || factor == 0) return 0;
        if (value > 0) {
            if (factor > 0) {
                if (value > Long.MAX_VALUE / factor) throw new ArithmeticException("long overflow");
            } else if (factor < Long.MIN_VALUE / value) {
                throw new ArithmeticException("long overflow");
            }
        } else if (factor > 0) {
            if (value < Long.MIN_VALUE / factor) throw new ArithmeticException("long overflow");
        } else if (value < Long.MAX_VALUE / factor) {
            throw new ArithmeticException("long overflow");
        }
        return value * factor;
    }

    private static Set<Integer> emptyEntryNumbers(byte[] first, int block) throws IOException {
        byte[] primary = Arrays.copyOfRange(first, block, block * 2);
        int count = entryCount(first, block);
        long tableLba = le(primary).getLong(72);
        int offset = checkedInt(checkedMultiplyExact(tableLba, block), "table offset");
        int size = Math.multiplyExact(count, ENTRY_BYTES);
        if (offset < 0 || offset > first.length - size) throw new IOException("GPT table does not fit current snapshot");
        Set<Integer> empty = new java.util.LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            int p = offset + i * ENTRY_BYTES;
            boolean zero = true;
            for (int k = 0; k < 16; k++) if (first[p + k] != 0) { zero = false; break; }
            if (zero) empty.add(i + 1);
        }
        return empty;
    }

    private static int entryCount(byte[] first, int block) throws IOException {
        if (first == null || first.length < block * 2) throw new IOException("Incomplete primary GPT snapshot");
        int count = checkedInt(u32(le(first), block + 80), "GPT entry count");
        int size = checkedInt(u32(le(first), block + 84), "GPT entry size");
        if (count < 1 || count > ENTRY_COUNT_LIMIT || size != ENTRY_BYTES) throw new IOException("Unsupported GPT entry geometry");
        return count;
    }

    private static int checkedInt(long value, String name) throws IOException {
        if (value < 0 || value > Integer.MAX_VALUE) throw new IOException(name + " is out of range");
        return (int) value;
    }

    private static String canonicalGuid(String value) throws IOException {
        try { return UUID.fromString(value).toString().toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException e) { throw new IOException("分区唯一 GUID 格式无效", e); }
    }

    private static String guidString(byte[] raw) throws IOException {
        if (raw.length != 16) throw new IOException("GUID length is invalid");
        return String.format(Locale.ROOT, "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                raw[3] & 255, raw[2] & 255, raw[1] & 255, raw[0] & 255, raw[5] & 255, raw[4] & 255,
                raw[7] & 255, raw[6] & 255, raw[8] & 255, raw[9] & 255, raw[10] & 255, raw[11] & 255,
                raw[12] & 255, raw[13] & 255, raw[14] & 255, raw[15] & 255);
    }

    private static boolean allZero(byte[] b) { for (byte x : b) if (x != 0) return false; return true; }
    private static ByteBuffer le(byte[] b) { return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); }
    private static long u32(ByteBuffer b, int p) { return b.getInt(p) & 0xffffffffL; }
    private static void put32(byte[] b, int p, long v) { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(p, (int) v); }
    private static void put64(byte[] b, int p, long v) { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putLong(p, v); }
    private static byte[] intBytes(int n) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array(); }
    private static byte[] longBytes(long n) { return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array(); }

    private static byte[] join(byte[]... parts) {
        int length = 0;
        for (byte[] p : parts) length = Math.addExact(length, p.length);
        byte[] out = new byte[length]; int at = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, at, p.length); at += p.length; }
        return out;
    }

    private static String sha256(byte[] b) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder s = new StringBuilder(64);
            for (byte x : hash) s.append(String.format(Locale.ROOT, "%02x", x & 255));
            return s.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
