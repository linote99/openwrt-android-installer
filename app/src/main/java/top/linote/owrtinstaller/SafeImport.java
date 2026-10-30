package top.linote.owrtinstaller;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Offline, data-only import for installer backup exports and continuation records.
 * It has no device, shell, extraction, restore, or write APIs. Only validated GPT bytes
 * are returned to the caller; all other ZIP members are streamed, hashed and discarded.
 */
final class SafeImport {
    private SafeImport() { }

    static final int MAX_ENTRIES = 4096;
    static final long MAX_ENTRY_BYTES = 128L << 20;
    static final long MAX_TOTAL_BYTES = 512L << 20;
    static final long MAX_COMPRESSION_RATIO = 200L;
    static final int MAX_JSON_BYTES = 1024 * 1024;
    static final int MAX_SUMS_BYTES = 1024 * 1024;
    private static final String HEX64 = "[a-f0-9]{64}";
    private static final String HEX32 = "[a-f0-9]{32}";
    private static final Set<String> MU300_FILES = setOf(
            "SHA256SUMS", "gpt-first.bin", "gpt-last.bin", "gpt-layout.json", "identity.json",
            "transaction.json", "package-manifest.json", "misc-head.bin", "boot_a.img", "boot_b.img",
            "uboot_a.img", "uboot_b.img", "sml_a.img", "sml_b.img", "teecfg_a.img", "teecfg_b.img",
            "trustos_a.img", "trustos_b.img", "vbmeta_a.img", "vbmeta_b.img");

    static final class RecordSummary {
        final String family;
        final String version;
        final String phase;
        final boolean complete;
        final String note;
        RecordSummary(String family, String version, String phase, boolean complete, String note) {
            this.family = family; this.version = version; this.phase = phase; this.complete = complete; this.note = note;
        }
        String safeText(boolean archiveHashVerified) {
            String s = family + " 记录 · 格式 " + version + " · 状态 " + phase + " · 只读查看";
            if (!complete) s += " · 部分旧字段缺失，不能用于续装";
            s += archiveHashVerified ? " · ZIP 清单哈希已匹配（不证明来源或设备身份）"
                    : " · 未提供外层文件哈希，仅检查了记录结构和内含哈希格式";
            return s + "。记录不会导入为可续装事务。";
        }
    }

    static final class BackupItem {
        final String family;
        final PartitionPlanner.Snapshot baseline;
        final RecordSummary record;
        final int partitionCount;
        final boolean identityConsistent;
        BackupItem(String family, PartitionPlanner.Snapshot baseline, RecordSummary record,
                   int partitionCount, boolean identityConsistent) {
            this.family = family; this.baseline = baseline; this.record = record;
            this.partitionCount = partitionCount; this.identityConsistent = identityConsistent;
        }
        String safeLabel(int index) {
            return "历史 GPT 备份 " + index + " · " + partitionCount + " 个分区 · "
                    + baseline.logicalBlockSize + " B 逻辑块";
        }
    }

    static final class ArchiveReport {
        final List<BackupItem> backups;
        final List<RecordSummary> records;
        final int verifiedFileCount;
        final boolean outerHashVerified;
        final int ignoredOpaqueFiles;
        ArchiveReport(List<BackupItem> backups, List<RecordSummary> records, int fileCount,
                      boolean outerHashVerified, int ignoredOpaqueFiles) {
            this.backups = Collections.unmodifiableList(new ArrayList<>(backups));
            this.records = Collections.unmodifiableList(new ArrayList<>(records));
            this.verifiedFileCount = fileCount; this.outerHashVerified = outerHashVerified;
            this.ignoredOpaqueFiles = ignoredOpaqueFiles;
        }
        String safeSummary() {
            StringBuilder b = new StringBuilder("ZIP 校验完成：").append(verifiedFileCount)
                    .append(" 个文件").append(outerHashVerified ? "的外层 SHA-256 清单匹配" : "通过旧式 GPT 内层清单校验");
            b.append("；可用历史 GPT 基线 ").append(backups.size()).append(" 份；续装记录 ")
                    .append(records.size()).append(" 条（均只读查看）");
            if (ignoredOpaqueFiles > 0) b.append("；另有 ").append(ignoredOpaqueFiles)
                    .append(" 个未识别附件只做流式校验并丢弃");
            b.append("。只会保存你选中的 GPT 主/备快照；ZIP 内身份 JSON、事务、镜像、路径和脚本均不落盘或执行。");
            return b.toString();
        }
    }

    private static final class Member {
        final String path;
        final String digest;
        final byte[] captured;
        final long size;
        Member(String path, String digest, byte[] captured, long size) {
            this.path = path; this.digest = digest; this.captured = captured; this.size = size;
        }
    }

    static ArchiveReport readBackupZip(InputStream input) throws IOException {
        return readBackupZip(input, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES, MAX_COMPRESSION_RATIO);
    }

    /** Test seam may only tighten the fixed production caps. */
    static ArchiveReport readBackupZip(InputStream input, long maxEntryBytes, long maxTotalBytes,
                                       long maxCompressionRatio) throws IOException {
        if (input == null) throw new IOException("未提供 ZIP 文件");
        if (maxEntryBytes <= 0 || maxEntryBytes > MAX_ENTRY_BYTES || maxTotalBytes <= 0
                || maxTotalBytes > MAX_TOTAL_BYTES || maxCompressionRatio <= 0
                || maxCompressionRatio > MAX_COMPRESSION_RATIO)
            throw new IOException("ZIP 安全限额参数无效");
        Map<String, Member> files = new LinkedHashMap<>();
        Set<String> canonical = new HashSet<>();
        long total = 0;
        int count = 0, opaque = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry e;
            byte[] buf = new byte[32768];
            while ((e = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) throw new IOException("ZIP 项目数超过安全上限");
                String name = validateArchivePath(e.getName(), e.isDirectory());
                String key = canonicalPath(name);
                if (!canonical.add(key) || files.containsKey(name)) throw new IOException("ZIP 含重名或规范化后冲突的路径");
                long declared = e.getSize();
                if (declared > maxEntryBytes) throw new IOException("ZIP 单个文件超过安全上限");
                boolean capture = shouldCapture(name);
                int captureLimit = captureLimit(name);
                ByteArrayOutputStream out = capture ? new ByteArrayOutputStream() : null;
                MessageDigest digest = sha();
                long entryBytes = 0;
                for (int n; (n = zip.read(buf)) != -1; ) {
                    if (n == 0) continue;
                    if (entryBytes > maxEntryBytes - n || total > maxTotalBytes - n)
                        throw new IOException("ZIP 解压数据超过安全上限（疑似压缩炸弹）");
                    entryBytes += n; total += n; digest.update(buf, 0, n);
                    if (out != null) {
                        if (out.size() > captureLimit - n) throw new IOException("ZIP 元数据超过安全上限");
                        out.write(buf, 0, n);
                    }
                }
                zip.closeEntry();
                long compressed = e.getCompressedSize();
                // Whitelisted metadata/GPT snapshots are individually capped before buffering;
                // sparse GPTs may legitimately compress very well. Apply the ratio guard to
                // opaque streamed payloads that are discarded without being buffered.
                if (out == null && compressedExceedsLimit(entryBytes, compressed, maxCompressionRatio))
                    throw new IOException("ZIP 压缩比超过安全上限（疑似压缩炸弹）");
                if (declared >= 0 && declared != entryBytes) throw new IOException("ZIP 声明长度与实际解压长度不一致");
                byte[] bytes = out == null ? null : out.toByteArray();
                files.put(name, new Member(name, hex(digest.digest()), bytes, entryBytes));
                if (isOpaque(name)) opaque++;
            }
        } catch (IOException e) {
            throw new IOException("备份导入拒绝：" + safeError(e.getMessage()), e);
        } catch (RuntimeException e) {
            throw new IOException("备份导入拒绝：ZIP 或元数据格式无效", e);
        }
        if (files.isEmpty()) throw new IOException("备份导入拒绝：ZIP 中没有文件");

        boolean hasSums = files.containsKey("SHA256SUMS"), hasInfo = files.containsKey("info.json");
        if (hasSums != hasInfo) throw new IOException("备份导入拒绝：外层 SHA256SUMS 与 info.json 必须同时存在");
        boolean outer = hasSums;
        if (outer) {
            validateInfo(required(files, "info.json").captured, files.size() - 2);
            Map<String, String> sums = parseSums(required(files, "SHA256SUMS").captured, false);
            if (sums.size() != files.size() - 2) throw new IOException("外层 SHA256SUMS 缺文件或含未归档路径");
            for (Member m : files.values()) {
                if ("SHA256SUMS".equals(m.path) || "info.json".equals(m.path)) continue;
                String expected = sums.get(m.path);
                if (expected == null || !expected.equals(m.digest)) throw new IOException("外层 SHA-256 校验失败或清单缺项");
            }
        } else {
            for (String path : files.keySet()) if (path.startsWith("e5-part/") || path.startsWith("uninstall/"))
                throw new IOException("旧式 ZIP 缺少外层 SHA-256；E5/卸载记录仅支持带导出清单的格式");
        }

        Map<String, Map<String, Member>> groups = new LinkedHashMap<>();
        Map<String, Map<String, Member>> e5Groups = new LinkedHashMap<>();
        for (Member m : files.values()) {
            String[] p = m.path.split("/");
            if (p.length >= 3 && "mu300-backup".equals(p[0])) {
                String dir = p[0] + "/" + p[1];
                groups.computeIfAbsent(dir, k -> new LinkedHashMap<>()).put(p[p.length - 1], m);
            } else if (p.length >= 4 && "e5-part".equals(p[0])) {
                String dir = m.path.substring(0, m.path.lastIndexOf('/'));
                e5Groups.computeIfAbsent(dir, k -> new LinkedHashMap<>()).put(p[p.length - 1], m);
            }
        }
        List<BackupItem> backups = new ArrayList<>();
        List<RecordSummary> records = new ArrayList<>();
        for (Map.Entry<String, Map<String, Member>> ge : groups.entrySet()) {
            Map<String, Member> g = ge.getValue();
            for (String leaf : g.keySet()) if (!MU300_FILES.contains(leaf))
                throw new IOException("MU300 备份目录含不受支持的成员；不会提取或执行该附件");
            Member internalSums = g.get("SHA256SUMS");
            Member first = g.get("gpt-first.bin"), last = g.get("gpt-last.bin"), layout = g.get("gpt-layout.json");
            if (internalSums == null || first == null || last == null || layout == null)
                throw new IOException("MU300 备份缺少 GPT 主/备快照、布局或 SHA256SUMS");
            verifyInnerManifest(internalSums.captured, ge.getKey(), g);
            long[] geom = parseLayout(layout.captured);
            if (first.size != bytesForSectors(geom[0])
                    || last.size != bytesForSectors(geom[2] - geom[1]))
                throw new IOException("GPT layout 几何与快照长度不一致");
            PartitionPlanner.Snapshot snap = PartitionPlanner.Snapshot.verified(first.captured, last.captured,
                    geom[2], PartitionPlanner.Origin.BASELINE_BACKUP, "");
            Member identity = g.get("identity.json"), tx = g.get("transaction.json");
            boolean identityOk = false;
            RecordSummary summary = null;
            if (identity != null) {
                Map<String, Object> id = parseObject(identity.captured, "identity.json");
                validateMuIdentity(id);
                if (number(id, "DISK_SIZE") != geom[2]) throw new IOException("身份记录与 GPT 磁盘几何不一致");
                identityOk = true;
                if (tx != null) {
                    Map<String, Object> rec = parseObject(tx.captured, "transaction.json");
                    summary = validateMuRecord(rec);
                    if (!sameString(id.get("serial"), rec.get("serial"))
                            || !sameString(id.get("android_slot"), rec.get("android_slot")))
                        throw new IOException("备份身份与续装记录不一致");
                    compareStorageIdentity(id, object(rec.get("storage"), "storage"));
                }
            } else if (tx != null) {
                throw new IOException("备份含续装记录但缺少可用于一致性检查的身份文件");
            }
            backups.add(new BackupItem("MU300", snap, summary, snap.entries.size(), identityOk));
            if (summary != null) records.add(summary);
        }
        for (Map.Entry<String, Map<String, Member>> ge : e5Groups.entrySet()) {
            Map<String, Member> g = ge.getValue();
            Member tx = g.get("transaction.json");
            if (tx == null) continue;
            Map<String, Object> rec = parseObject(tx.captured, "transaction.json");
            RecordSummary summary = validateE5Record(rec);
            records.add(summary);
            Member first = g.get("gpt-primary.bin"), last = g.get("gpt-backup.bin");
            if (first != null || last != null) {
                if (!outer) throw new IOException("E5 GPT 快照缺少外层 SHA-256 清单");
                if (first == null || last == null) throw new IOException("E5 备份缺少 GPT 主表或备份表");
                if (first.captured == null || last.captured == null) throw new IOException("E5 GPT 快照无法读取");
                if (first.size != 34L * 512 || last.size != 33L * 512)
                    throw new IOException("E5 GPT 快照长度不受支持");
                long sectors = gptDiskSectors(last.captured);
                PartitionPlanner.Snapshot snap = PartitionPlanner.Snapshot.verified(first.captured, last.captured,
                        sectors, PartitionPlanner.Origin.BASELINE_BACKUP, "");
                checkE5RecordGeometry(rec, snap);
                backups.add(new BackupItem("E5", snap, summary, snap.entries.size(), true));
            }
        }
        if (backups.isEmpty() && records.isEmpty()) throw new IOException("ZIP 中没有可识别的分区备份或续装记录");
        return new ArchiveReport(backups, records, outer ? files.size() - 2 : files.size(), outer, opaque);
    }

    static RecordSummary readResumeRecord(InputStream input) throws IOException {
        if (input == null) throw new IOException("未提供续装记录");
        byte[] bytes = readLimited(input, MAX_JSON_BYTES);
        Map<String, Object> map = parseObject(bytes, "续装记录");
        if (map.containsKey("version")) return validateE5Record(map);
        if (map.containsKey("format")) return validateMuRecord(map);
        throw new IOException("续装记录 schema/version 不受支持");
    }

    private static void validateInfo(byte[] bytes, int fileCount) throws IOException {
        Map<String, Object> m = parseObject(bytes, "info.json");
        exactKeys(m, setOf("app", "app_version", "phone_model", "created", "files", "source_dirs"),
                Collections.<String>emptySet(), "info.json");
        if (!"OpenWrt 安装器（安卓版）".equals(string(m, "app"))
                || !string(m, "app_version").matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                || string(m, "phone_model").length() > 256 || !validTimestamp(string(m, "created"))
                || number(m, "files") != fileCount || !(m.get("source_dirs") instanceof List))
            throw new IOException("info.json schema、版本或文件数无效");
        List<?> dirs = (List<?>) m.get("source_dirs");
        if (dirs.size() > 32) throw new IOException("info.json 来源目录数超过上限");
        for (Object o : dirs) if (!(o instanceof String) || ((String) o).length() > 1024)
            throw new IOException("info.json 来源目录字段类型无效");
    }

    private static boolean validTimestamp(String s) {
        if (!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[+-][0-9]{4}")) return false;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
        f.setLenient(false);
        try { Date d = f.parse(s); return d != null; } catch (ParseException e) { return false; }
    }

    private static void verifyInnerManifest(byte[] raw, String dir, Map<String, Member> group) throws IOException {
        Map<String, String> sums = parseSums(raw, true);
        if (!sums.containsKey("gpt-first.bin") || !sums.containsKey("gpt-last.bin"))
            throw new IOException("GPT 内层 SHA256SUMS 缺少主表或备份表");
        for (Map.Entry<String, String> e : sums.entrySet()) {
            Member m = group.get(e.getKey());
            if (m == null || !e.getValue().equals(m.digest)) throw new IOException("GPT 内层 SHA-256 校验失败或清单缺项");
        }
    }

    private static Map<String, String> parseSums(byte[] raw, boolean inner) throws IOException {
        String text = utf8(raw, "SHA256SUMS");
        if (text.length() > MAX_SUMS_BYTES) throw new IOException("SHA256SUMS 超过安全上限");
        Map<String, String> out = new LinkedHashMap<>();
        String[] lines = text.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty() && i == lines.length - 1) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([a-f0-9]{64})  ([A-Za-z0-9._/-]+)$").matcher(line);
            if (!m.matches()) throw new IOException("SHA256SUMS 格式无效");
            String path = m.group(2);
            if (inner) {
                if (path.indexOf('/') >= 0 || !MU300_FILES.contains(path) || "SHA256SUMS".equals(path))
                    throw new IOException("GPT 内层清单包含不受支持的路径");
            } else {
                validateArchivePath(path, false);
                if ("SHA256SUMS".equals(path) || "info.json".equals(path)) throw new IOException("外层清单不应登记自身元数据");
            }
            if (out.put(path, m.group(1)) != null) throw new IOException("SHA256SUMS 存在重复项目");
        }
        return out;
    }

    private static long[] parseLayout(byte[] raw) throws IOException {
        Map<String, Object> m = parseObject(raw, "gpt-layout.json");
        exactKeys(m, setOf("front_sectors", "tail_start", "disk_sectors"), Collections.<String>emptySet(), "gpt-layout.json");
        long front = number(m, "front_sectors"), tail = number(m, "tail_start"), disk = number(m, "disk_sectors");
        if (front <= 0 || tail <= 0 || disk <= tail || front >= disk) throw new IOException("GPT layout 扇区范围无效");
        return new long[]{front, tail, disk};
    }

    private static long bytesForSectors(long sectors) throws IOException {
        if (sectors <= 0 || sectors > MAX_ENTRY_BYTES / 512) throw new IOException("GPT 快照扇区数超过安全范围");
        return sectors * 512L;
    }

    private static void validateMuIdentity(Map<String, Object> m) throws IOException {
        Set<String> required = setOf("NODE", "DISK", "STORAGE_KIND", "SECTOR_SIZE", "CID", "START", "SIZE",
                "DISK_SIZE", "FREE", "STATE", "TYPE", "SOURCE", "MAGIC", "EXT", "serial", "android_slot");
        exactKeys(m, required, setOf("ROOT"), "identity.json");
        validateStorageMap(m, true);
        shortString(m, "serial", 128);
        if (!"a".equals(string(m, "android_slot")) && !"b".equals(string(m, "android_slot")))
            throw new IOException("identity.json 槽位字段无效");
        if (m.containsKey("ROOT") && !string(m, "ROOT").matches("(?:mmcblk[0-9]+p[1-9][0-9]*|sd[a-z]+[1-9][0-9]*)"))
            throw new IOException("identity.json root 节点格式无效");
    }

    private static RecordSummary validateMuRecord(Map<String, Object> m) throws IOException {
        Set<String> required = setOf("format", "state", "serial", "storage", "target", "need", "token");
        Set<String> optional = setOf("transport", "android_slot", "package", "payload_identity", "package_dir", "transport_history");
        exactKeys(m, required, optional, "MU300 transaction.json");
        if (number(m, "format") != 1) throw new IOException("MU300 续装记录版本不受支持");
        String state = string(m, "state");
        if (!("prepared-request".equals(state) || "installed".equals(state))) throw new IOException("MU300 续装记录状态无效");
        shortString(m, "serial", 128);
        if (m.containsKey("transport")) shortString(m, "transport", 256);
        if (m.containsKey("android_slot") && !("a".equals(string(m, "android_slot")) || "b".equals(string(m, "android_slot"))))
            throw new IOException("MU300 续装记录槽位无效");
        Map<String, Object> storage = object(m.get("storage"), "storage");
        validateStorageMap(storage, false);
        long target = number(m, "target"), need = number(m, "need");
        if (target <= 0 || need <= 0 || target >= (1L << 40) || need >= (1L << 40)
                || target % PartitionPlanner.ALIGNMENT_SECTORS != 0 || need % PartitionPlanner.ALIGNMENT_SECTORS != 0)
            throw new IOException("MU300 续装记录分区几何无效");
        if (!string(m, "token").matches(HEX32)) throw new IOException("MU300 续装记录令牌格式无效");
        boolean complete = true;
        if (m.containsKey("package")) validateHash(string(m, "package"), "package"); else complete = false;
        if (m.containsKey("payload_identity")) validateHash(string(m, "payload_identity"), "payload_identity"); else complete = false;
        if (m.containsKey("package_dir")) shortString(m, "package_dir", 4096); // never opened or followed
        validateTransportHistory(m.get("transport_history"));
        return new RecordSummary("MU300", "1", state, complete, "");
    }

    private static void validateStorageMap(Map<String, Object> m, boolean identity) throws IOException {
        Set<String> required = setOf("NODE", "DISK", "STORAGE_KIND", "SECTOR_SIZE", "CID", "START", "SIZE",
                "DISK_SIZE", "FREE", "STATE", "TYPE", "SOURCE", "MAGIC", "EXT");
        Set<String> optional = setOf("ROOT");
        if (identity) optional.addAll(setOf("serial", "android_slot"));
        exactKeys(m, required, optional, identity ? "identity.json" : "transaction storage");
        String node = string(m, "NODE"), disk = string(m, "DISK"), kind = string(m, "STORAGE_KIND"), cid = string(m, "CID");
        if (!node.matches("(?:mmcblk[0-9]+p[1-9][0-9]*|sd[a-z]+[1-9][0-9]*)")
                || !disk.matches("(?:mmcblk[0-9]+|sd[a-z]+)") || !node.startsWith(disk)
                || !("emmc".equals(kind) || "ufs".equals(kind)) || !cid.matches(HEX32))
            throw new IOException("续装记录存储身份字段格式无效");
        long block = number(m, "SECTOR_SIZE");
        if (!(block == 512 || block == 4096)) throw new IOException("续装记录 GPT 逻辑块大小不受支持");
        long start = number(m, "START"), size = number(m, "SIZE"), diskSize = number(m, "DISK_SIZE");
        if (start < 0 || size <= 0 || diskSize <= start || size > diskSize - start || diskSize % (block / 512) != 0)
            throw new IOException("续装记录存储几何字段无效");
        for (String key : new String[]{"FREE"}) if (number(m, key) < 0) throw new IOException("续装记录空间字段无效");
        for (String key : new String[]{"STATE", "TYPE", "SOURCE", "MAGIC", "EXT"}) shortString(m, key, 256);
        if (m.containsKey("ROOT") && !string(m, "ROOT").matches("(?:mmcblk[0-9]+p[1-9][0-9]*|sd[a-z]+[1-9][0-9]*)"))
            throw new IOException("续装记录 root 节点格式无效");
    }

    private static void compareStorageIdentity(Map<String, Object> id, Map<String, Object> storage) throws IOException {
        for (String key : new String[]{"NODE", "DISK", "STORAGE_KIND", "SECTOR_SIZE", "CID", "START", "SIZE", "DISK_SIZE"}) {
            Object a = id.get(key), b = storage.get(key);
            if (a == null || !a.equals(b)) throw new IOException("备份身份与续装记录存储字段不一致");
        }
    }

    private static RecordSummary validateE5Record(Map<String, Object> m) throws IOException {
        Set<String> required = setOf("version", "phase", "token", "serial", "model", "transport", "data", "android_slot",
                "linux_slot", "target", "need", "grow", "boot_sha", "image_sha", "raw_size", "apn", "ssid", "key");
        Set<String> optional = setOf("request_handoff", "transport_history");
        exactKeys(m, required, optional, "E5 transaction.json");
        if (number(m, "version") != 2) throw new IOException("E5 续装记录版本不受支持");
        String phase = string(m, "phase");
        if (!Arrays.asList("prepared", "rearming", "awaiting-android", "writing", "installed", "complete").contains(phase))
            throw new IOException("E5 续装记录阶段无效");
        if (!string(m, "token").matches(HEX32)) throw new IOException("E5 续装记录令牌格式无效");
        shortString(m, "serial", 128); shortString(m, "model", 256); shortString(m, "transport", 256);
        String a = string(m, "android_slot"), l = string(m, "linux_slot");
        if (!("a".equals(a) || "b".equals(a)) || !("a".equals(l) || "b".equals(l)) || a.equals(l))
            throw new IOException("E5 续装记录槽位字段无效");
        Map<String, Object> data = object(m.get("data"), "data");
        exactKeys(data, setOf("node", "cid", "start", "size"), Collections.<String>emptySet(), "E5 data");
        if (!string(data, "node").matches("mmcblk[0-9]+p[1-9][0-9]*") || !string(data, "cid").matches(HEX32)
                || number(data, "start") < 0 || number(data, "size") <= 0)
            throw new IOException("E5 续装记录 userdata 身份/几何无效");
        long target = number(m, "target"), need = number(m, "need"), rawSize = number(m, "raw_size"), grow = number(m, "grow");
        if (target <= 0 || need <= 0 || rawSize <= 0 || target >= (1L << 40) || need >= (1L << 40)
                || target % PartitionPlanner.ALIGNMENT_SECTORS != 0 || need % PartitionPlanner.ALIGNMENT_SECTORS != 0
                || !(grow == 0 || grow == 1)) throw new IOException("E5 续装记录大小字段无效");
        validateHash(string(m, "boot_sha"), "boot_sha"); validateHash(string(m, "image_sha"), "image_sha");
        for (String key : new String[]{"apn", "ssid", "key"}) {
            String value = string(m, key);
            if (value.length() > 512 || value.indexOf('\'') >= 0 || containsControl(value))
                throw new IOException("E5 续装记录设置字段无效");
        }
        if (m.containsKey("request_handoff")) {
            long handoff = number(m, "request_handoff");
            if (!(handoff == 0 || handoff == 1)) throw new IOException("E5 handoff 标记无效");
        }
        validateTransportHistory(m.get("transport_history"));
        return new RecordSummary("E5", "2", phase, true, "");
    }

    private static void validateTransportHistory(Object value) throws IOException {
        if (value == null) return;
        if (!(value instanceof List) || ((List<?>) value).size() > 64) throw new IOException("连接历史字段无效");
        for (Object entry : (List<?>) value) {
            Map<String, Object> item = object(entry, "transport_history item");
            exactKeys(item, setOf("transport", "changed_at"), Collections.<String>emptySet(), "transport_history item");
            shortString(item, "transport", 256);
            if (number(item, "changed_at") < 0) throw new IOException("连接历史时间字段无效");
        }
    }

    private static void checkE5RecordGeometry(Map<String, Object> rec, PartitionPlanner.Snapshot snap) throws IOException {
        Map<String, Object> data = object(rec.get("data"), "E5 data");
        long start = number(data, "start"), size = number(data, "size");
        boolean found = false;
        for (PartitionPlanner.Entry e : snap.entries) if ("userdata".equalsIgnoreCase(e.name)) {
            if (found || e.start != start || e.sectors() != size) throw new IOException("E5 续装记录与 GPT userdata 几何不一致");
            found = true;
        }
        if (!found) throw new IOException("E5 GPT 快照缺少唯一 userdata 或与记录不一致");
    }

    private static long gptDiskSectors(byte[] last) throws IOException {
        if (last.length < 33 * 512) throw new IOException("E5 GPT 备份长度不足");
        int h = last.length - 512;
        if (!Arrays.equals(Arrays.copyOfRange(last, h, h + 8), "EFI PART".getBytes(StandardCharsets.US_ASCII)))
            throw new IOException("E5 GPT 备份头签名无效");
        long lba = ByteBuffer.wrap(last).order(ByteOrder.LITTLE_ENDIAN).getLong(h + 24);
        if (lba < 1 || lba == Long.MAX_VALUE) throw new IOException("E5 GPT 磁盘几何无效");
        return lba + 1;
    }

    private static String validateArchivePath(String name, boolean directory) throws IOException {
        if (directory || name == null || name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0 || name.indexOf(':') >= 0 || !isAscii(name))
            throw new IOException("ZIP 路径不是受支持的规范文件路径");
        String[] p = name.split("/", -1);
        for (String seg : p) {
            if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)
                    || !seg.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || seg.endsWith("."))
                throw new IOException("ZIP 路径包含穿越、空段或非规范名称");
        }
        if ("SHA256SUMS".equals(name) || "info.json".equals(name)) return name;
        if (p.length < 3 || !("mu300-backup".equals(p[0]) || "e5-part".equals(p[0]) || "uninstall".equals(p[0])))
            throw new IOException("ZIP 路径不属于受支持的备份导出目录");
        if ("mu300-backup".equals(p[0]) && p.length != 3) throw new IOException("MU300 ZIP 路径层级不受支持");
        if ("e5-part".equals(p[0]) && p.length < 4) throw new IOException("E5 ZIP 路径层级不受支持");
        return name;
    }

    private static String canonicalPath(String name) {
        // Input path alphabet is ASCII-only; lowercasing also rejects case-fold collisions.
        return name.toLowerCase(Locale.ROOT);
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) > 0x7f) return false;
        return true;
    }

    static boolean compressedExceedsLimit(long expandedBytes, long compressedBytes, long maximumRatio) {
        return expandedBytes >= 0 && compressedBytes > 0 && maximumRatio > 0
                && expandedBytes > compressedBytes * maximumRatio;
    }

    private static boolean shouldCapture(String name) {
        if ("SHA256SUMS".equals(name) || "info.json".equals(name)) return true;
        String leaf = name.substring(name.lastIndexOf('/') + 1);
        return Arrays.asList("SHA256SUMS", "gpt-first.bin", "gpt-last.bin", "gpt-layout.json", "identity.json",
                "transaction.json", "gpt-primary.bin", "gpt-backup.bin").contains(leaf);
    }

    private static int captureLimit(String name) {
        String leaf = name.substring(name.lastIndexOf('/') + 1);
        if ("SHA256SUMS".equals(leaf)) return MAX_SUMS_BYTES;
        if ("gpt-layout.json".equals(leaf) || "identity.json".equals(leaf) || "transaction.json".equals(leaf)
                || "info.json".equals(leaf)) return MAX_JSON_BYTES;
        return 8 * 1024 * 1024;
    }

    private static boolean isOpaque(String name) {
        if ("SHA256SUMS".equals(name) || "info.json".equals(name)) return false;
        String leaf = name.substring(name.lastIndexOf('/') + 1);
        return !(MU300_FILES.contains(leaf) || "transaction.json".equals(leaf) || "gpt-primary.bin".equals(leaf)
                || "gpt-backup.bin".equals(leaf) || leaf.matches("boot-[A-Za-z0-9._-]+\\.img")
                || leaf.equals("boot-control-before.bin") || leaf.equals("bcb-before.bin")
                || leaf.equals("bootchain.tar") || leaf.equals("boot-prepare.img") || leaf.equals("boot-part.img"));
    }

    private static Member required(Map<String, Member> files, String name) throws IOException {
        Member m = files.get(name);
        if (m == null || m.captured == null) throw new IOException("ZIP 缺少必要元数据文件");
        return m;
    }

    private static boolean sameString(Object a, Object b) { return a instanceof String && a.equals(b); }
    private static void validateHash(String s, String name) throws IOException {
        if (s == null || !s.matches(HEX64)) throw new IOException("记录内 " + name + " 哈希格式无效");
    }
    private static void shortString(Map<String, Object> m, String key, int max) throws IOException {
        String s = string(m, key);
        if (s.length() > max || containsControl(s)) throw new IOException("记录字段格式无效：" + key);
    }
    private static boolean containsControl(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 0x20 || s.charAt(i) == 0x7f) return true;
        return false;
    }
    private static Map<String, Object> parseObject(byte[] raw, String what) throws IOException {
        try {
            Object parsed = MiniJson.parse(utf8(raw, what));
            if (!(parsed instanceof Map)) throw new IOException(what + " 必须是 JSON 对象");
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) parsed;
            return map;
        } catch (IllegalArgumentException e) { throw new IOException(what + " JSON 格式无效或含重复字段", e); }
    }
    private static String utf8(byte[] raw, String what) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) { throw new IOException(what + " 不是有效 UTF-8", e); }
    }
    private static Map<String, Object> object(Object o, String where) throws IOException {
        if (!(o instanceof Map)) throw new IOException(where + " schema 类型无效");
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o; return m;
    }
    private static String string(Map<String, Object> m, String key) throws IOException {
        Object o = m.get(key); if (!(o instanceof String)) throw new IOException("记录字段缺失或类型无效：" + key); return (String) o;
    }
    private static long number(Map<String, Object> m, String key) throws IOException {
        Object o = m.get(key); if (!(o instanceof Long)) throw new IOException("记录字段缺失或类型无效：" + key); return (Long) o;
    }
    private static void exactKeys(Map<String, Object> m, Set<String> required, Set<String> optional, String where) throws IOException {
        for (String key : required) if (!m.containsKey(key)) throw new IOException(where + " 缺少必要字段");
        for (String key : m.keySet()) if (!required.contains(key) && !optional.contains(key))
            throw new IOException(where + " 含未知字段，拒绝导入");
    }
    private static Set<String> setOf(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) != -1; ) {
            if (n == 0) continue;
            if (out.size() > limit - n) throw new IOException("续装记录超过安全大小上限");
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }
    private static String safeError(String s) {
        if (s == null || s.isEmpty()) return "文件无效";
        // Do not echo paths, JSON values, device IDs, tokens, or ZIP member contents.
        if (s.contains("路径") || s.contains("字段") || s.contains("身份") || s.contains("哈希")) return s;
        return "文件格式/完整性检查失败";
    }
    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return s.toString();
    }
}
