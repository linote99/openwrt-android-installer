package top.linote.owrtinstaller;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Pure offline importer regression using synthetic GPTs and synthetic, non-device identifiers only. */
public final class SafeImportTest {
    private static int passed, failed;
    private interface Check { void run() throws Exception; }
    private static void ok(boolean v, String s) { if (v) { passed++; System.out.println("PASS " + s); } else { failed++; System.out.println("FAIL " + s); } }
    private static void rejects(Check c, String s) { try { c.run(); ok(false, s); } catch (Throwable expected) { ok(true, s); } }
    private static String sha(byte[] b) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(b); StringBuilder s = new StringBuilder();
        for (byte x : h) s.append(String.format(java.util.Locale.ROOT, "%02x", x & 255)); return s.toString();
    }
    private static PartitionPlanner.Entry userdata(PartitionPlanner.Snapshot s) {
        for (PartitionPlanner.Entry e : s.entries) if ("userdata".equalsIgnoreCase(e.name)) return e;
        throw new IllegalStateException("synthetic userdata missing");
    }
    private static Map<String, Object> storage(PartitionPlanner.Snapshot s) {
        PartitionPlanner.Entry u = userdata(s); Map<String, Object> m = new LinkedHashMap<>();
        m.put("NODE", "mmcblk0p75"); m.put("DISK", "mmcblk0"); m.put("STORAGE_KIND", "emmc");
        m.put("SECTOR_SIZE", (long) s.logicalBlockSize); m.put("CID", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        m.put("START", u.start); m.put("SIZE", u.sectors()); m.put("DISK_SIZE", s.sectors512);
        m.put("FREE", 123456L); m.put("STATE", "sim-state"); m.put("TYPE", "sim-type");
        m.put("SOURCE", "synthetic"); m.put("MAGIC", "sim-magic"); m.put("EXT", "sim-ext");
        return m;
    }
    private static String json(Object o) {
        if (o instanceof String) return "\"" + ((String) o).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (o instanceof Number || o instanceof Boolean) return String.valueOf(o);
        if (o instanceof Map) {
            StringBuilder b = new StringBuilder("{"); boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) { if (!first) b.append(','); first = false; b.append(json(String.valueOf(e.getKey()))).append(':').append(json(e.getValue())); }
            return b.append('}').toString();
        }
        if (o instanceof List) { StringBuilder b = new StringBuilder("["); boolean first = true; for (Object x : (List<?>) o) { if (!first) b.append(','); first = false; b.append(json(x)); } return b.append(']').toString(); }
        return "null";
    }
    private static Map<String, Object> identity(PartitionPlanner.Snapshot s, String serial) {
        Map<String, Object> m = new LinkedHashMap<>(storage(s));
        m.put("serial", serial); m.put("android_slot", "a"); return m;
    }
    private static Map<String, Object> muRecord(PartitionPlanner.Snapshot s, String serial) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", 1L); m.put("state", "prepared-request"); m.put("serial", serial); m.put("transport", "usb:SIMULATED");
        m.put("android_slot", "a"); m.put("storage", storage(s)); m.put("target", 2048L); m.put("need", 4096L);
        m.put("token", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"); m.put("package", "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
        m.put("payload_identity", "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");
        m.put("package_dir", "/synthetic/never-follow-this-path"); return m;
    }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static byte[] export(PartitionPlanner.Snapshot s, boolean corruptInnerHash, boolean corruptOuterHash,
                                 byte[] alternate, String recordSerial, boolean unknownCommand) throws Exception {
        return export(s, corruptInnerHash, corruptOuterHash, alternate, recordSerial, unknownCommand, false);
    }
    private static byte[] export(PartitionPlanner.Snapshot s, boolean corruptInnerHash, boolean corruptOuterHash,
                                 byte[] alternate, String recordSerial, boolean unknownCommand, boolean badLayout) throws Exception {
        Map<String, byte[]> members = new LinkedHashMap<>();
        String firstHash = corruptInnerHash ? "0".repeat(64) : sha(s.first);
        members.put("mu300-backup/synthetic/SHA256SUMS", bytes(firstHash + "  gpt-first.bin\n" + sha(alternate) + "  gpt-last.bin\n"));
        members.put("mu300-backup/synthetic/gpt-first.bin", s.first);
        members.put("mu300-backup/synthetic/gpt-last.bin", alternate);
        members.put("mu300-backup/synthetic/gpt-layout.json", bytes("{\"front_sectors\":" + (s.first.length / 512 + (badLayout ? 1 : 0))
                + ",\"tail_start\":" + (s.sectors512 - s.last.length / 512) + ",\"disk_sectors\":" + s.sectors512 + "}"));
        members.put("mu300-backup/synthetic/identity.json", bytes(json(identity(s, "SIMULATED-SERIAL"))));
        Map<String, Object> rec = muRecord(s, recordSerial == null ? "SIMULATED-SERIAL" : recordSerial);
        if (unknownCommand) rec.put("command", "touch /tmp/should-never-run");
        members.put("mu300-backup/synthetic/transaction.json", bytes(json(rec)));
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(raw)) {
            for (Map.Entry<String, byte[]> e : members.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue()); z.closeEntry(); }
            StringBuilder sums = new StringBuilder();
            for (Map.Entry<String, byte[]> e : members.entrySet()) {
                String hash = corruptOuterHash && e.getKey().endsWith("gpt-first.bin") ? "0".repeat(64) : sha(e.getValue());
                sums.append(hash).append("  ").append(e.getKey()).append('\n');
            }
            z.putNextEntry(new ZipEntry("SHA256SUMS")); z.write(bytes(sums.toString())); z.closeEntry();
            String info = "{\"app\":\"OpenWrt 安装器（安卓版）\",\"app_version\":\"1.7.5\",\"phone_model\":\"SIMULATED\","
                    + "\"created\":\"2026-10-30T00:00:00+0800\",\"files\":" + members.size() + ",\"source_dirs\":[\"/synthetic\"]}";
            z.putNextEntry(new ZipEntry("info.json")); z.write(bytes(info)); z.closeEntry();
        }
        return raw.toByteArray();
    }

    private static byte[] legacyExport(PartitionPlanner.Snapshot s) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("gpt-first.bin", s.first); files.put("gpt-last.bin", s.last);
        files.put("gpt-layout.json", bytes("{\"front_sectors\":" + (s.first.length / 512)
                + ",\"tail_start\":" + (s.sectors512 - s.last.length / 512) + ",\"disk_sectors\":" + s.sectors512 + "}"));
        String sums = sha(s.first) + "  gpt-first.bin\n" + sha(s.last) + "  gpt-last.bin\n";
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(raw)) {
            z.putNextEntry(new ZipEntry("mu300-backup/legacy/SHA256SUMS")); z.write(bytes(sums)); z.closeEntry();
            for (Map.Entry<String, byte[]> e : files.entrySet()) { z.putNextEntry(new ZipEntry("mu300-backup/legacy/" + e.getKey())); z.write(e.getValue()); z.closeEntry(); }
        }
        return raw.toByteArray();
    }
    private static byte[] missingLastExport(PartitionPlanner.Snapshot s) throws Exception {
        String sums = sha(s.first) + "  gpt-first.bin\n" + sha(s.last) + "  gpt-last.bin\n";
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(raw)) {
            z.putNextEntry(new ZipEntry("mu300-backup/missing/SHA256SUMS")); z.write(bytes(sums)); z.closeEntry();
            z.putNextEntry(new ZipEntry("mu300-backup/missing/gpt-first.bin")); z.write(s.first); z.closeEntry();
            z.putNextEntry(new ZipEntry("mu300-backup/missing/gpt-layout.json"));
            z.write(bytes("{\"front_sectors\":" + (s.first.length / 512) + ",\"tail_start\":" + (s.sectors512 - s.last.length / 512)
                    + ",\"disk_sectors\":" + s.sectors512 + "}")); z.closeEntry();
        }
        return raw.toByteArray();
    }
    private static byte[] e5Export(PartitionPlanner.Snapshot s) throws Exception {
        s = e5SizedSnapshot(s);
        PartitionPlanner.Entry u = userdata(s);
        String rec = e5Json(2).replace("\"start\":8192,\"size\":16384",
                "\"start\":" + u.start + ",\"size\":" + u.sectors());
        Map<String, byte[]> members = new LinkedHashMap<>();
        String base = "e5-part/backups/synthetic/";
        members.put(base + "transaction.json", bytes(rec));
        members.put(base + "gpt-primary.bin", s.first);
        members.put(base + "gpt-backup.bin", s.last);
        members.put(base + "boot-a-before.img", new byte[]{1, 2, 3, 4});
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(raw)) {
            StringBuilder sums = new StringBuilder();
            for (Map.Entry<String, byte[]> e : members.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue()); z.closeEntry();
                sums.append(sha(e.getValue())).append("  ").append(e.getKey()).append('\n');
            }
            z.putNextEntry(new ZipEntry("SHA256SUMS")); z.write(bytes(sums.toString())); z.closeEntry();
            String info = "{\"app\":\"OpenWrt 安装器（安卓版）\",\"app_version\":\"1.7.5\",\"phone_model\":\"SIMULATED\","
                    + "\"created\":\"2026-10-30T00:00:00+0800\",\"files\":" + members.size() + ",\"source_dirs\":[\"/synthetic\"]}";
            z.putNextEntry(new ZipEntry("info.json")); z.write(bytes(info)); z.closeEntry();
        }
        return raw.toByteArray();
    }

    private static byte[] oneEntryZip(String name, byte[] content) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) { z.putNextEntry(new ZipEntry(name)); z.write(content); z.closeEntry(); }
        return b.toByteArray();
    }
    private static void le16(DataOutputStream o, int n) throws IOException { o.writeByte(n); o.writeByte(n >>> 8); }
    private static void le32(DataOutputStream o, long n) throws IOException { le16(o, (int) n); le16(o, (int) (n >>> 16)); }
    private static byte[] duplicateExactZip(String name) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ByteArrayOutputStream central = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(central); byte[] nb = name.getBytes(StandardCharsets.UTF_8); byte[] data = new byte[]{1};
        CRC32 crc = new CRC32(); crc.update(data);
        for (int i = 0; i < 2; i++) {
            long offset = out.size(); DataOutputStream l = new DataOutputStream(out);
            le32(l, 0x04034b50L); le16(l, 20); le16(l, 0); le16(l, 0); le16(l, 0); le16(l, 0);
            le32(l, crc.getValue()); le32(l, 1); le32(l, 1); le16(l, nb.length); le16(l, 0); l.write(nb); l.write(data);
            le32(c, 0x02014b50L); le16(c, 20); le16(c, 20); le16(c, 0); le16(c, 0); le16(c, 0); le16(c, 0);
            le32(c, crc.getValue()); le32(c, 1); le32(c, 1); le16(c, nb.length); le16(c, 0); le16(c, 0); le16(c, 0); le16(c, 0);
            le32(c, 0); le32(c, offset); c.write(nb);
        }
        byte[] cd = central.toByteArray(); DataOutputStream end = new DataOutputStream(out);
        le32(end, 0x06054b50L); le16(end, 0); le16(end, 0); le16(end, 2); le16(end, 2); le32(end, cd.length);
        le32(end, out.size()); le16(end, 0); end.write(cd); return out.toByteArray();
    }

    private static byte[][] duplicateGuid(PartitionPlanner.Snapshot source) {
        int block = source.logicalBlockSize; byte[] first = source.first.clone(), last = source.last.clone();
        ByteBuffer h = ByteBuffer.wrap(first).order(ByteOrder.LITTLE_ENDIAN); int table = (int) (h.getLong(block + 72) * block);
        int count = h.getInt(block + 80), entrySize = h.getInt(block + 84); byte[] entries = Arrays.copyOfRange(first, table, table + count * entrySize);
        System.arraycopy(entries, 16, entries, entrySize + 16, 16); System.arraycopy(entries, 56, entries, entrySize + 56, 72);
        System.arraycopy(entries, 0, first, table, entries.length); System.arraycopy(entries, 0, last, 0, entries.length);
        put32(first, block + 88, Mu300Boot.crc32(entries, 0, entries.length)); put32(last, last.length - block + 88, Mu300Boot.crc32(entries, 0, entries.length));
        refresh(first, block, block); refresh(last, last.length - block, block); return new byte[][]{first, last};
    }
    private static void put32(byte[] b, int p, int n) { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(p, n); }
    private static void refresh(byte[] b, int at, int block) { int len = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(at + 12); put32(b, at + 16, 0); put32(b, at + 16, Mu300Boot.crc32(b, at, len)); }

    private static PartitionPlanner.Snapshot e5SizedSnapshot(PartitionPlanner.Snapshot source) throws Exception {
        int block = 512, entryCount = 128, tableBytes = entryCount * 128;
        ByteBuffer h = ByteBuffer.wrap(source.first).order(ByteOrder.LITTLE_ENDIAN);
        int sourceTable = (int) (h.getLong(block + 72) * block);
        byte[] table = Arrays.copyOfRange(source.first, sourceTable, sourceTable + tableBytes);
        byte[] first = Arrays.copyOf(source.first, 34 * block);
        byte[] last = new byte[33 * block];
        System.arraycopy(table, 0, first, 2 * block, tableBytes);
        System.arraycopy(table, 0, last, 0, tableBytes);
        System.arraycopy(source.last, source.last.length - block, last, last.length - block, block);
        int primary = block, backup = last.length - block;
        int tableCrc = Mu300Boot.crc32(table, 0, table.length);
        put32(first, primary + 80, entryCount); put32(first, primary + 88, tableCrc); refresh(first, primary, block);
        put32(last, backup + 80, entryCount); ByteBuffer.wrap(last).order(ByteOrder.LITTLE_ENDIAN).putLong(backup + 72, source.sectors512 - 33);
        put32(last, backup + 88, tableCrc); refresh(last, backup, block);
        return PartitionPlanner.Snapshot.verified(first, last, source.sectors512, PartitionPlanner.Origin.BASELINE_BACKUP, "");
    }

    private static String e5Json(long version) {
        Map<String, Object> m = new LinkedHashMap<>(); m.put("version", version); m.put("phase", "prepared");
        m.put("token", "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"); m.put("serial", "SIMULATED-SERIAL"); m.put("model", "SIMULATED"); m.put("transport", "usb:SIM");
        Map<String, Object> d = new LinkedHashMap<>(); d.put("node", "mmcblk0p75"); d.put("cid", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"); d.put("start", 8192L); d.put("size", 16384L); m.put("data", d);
        m.put("android_slot", "a"); m.put("linux_slot", "b"); m.put("target", 2048L); m.put("need", 4096L); m.put("grow", 0L);
        m.put("boot_sha", "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
        m.put("image_sha", "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"); m.put("raw_size", 123456L);
        m.put("apn", ""); m.put("ssid", "synthetic"); m.put("key", "synthetic-key"); return json(m);
    }
    private static void jsonRecordTests() throws Exception {
        SafeImport.RecordSummary e5 = SafeImport.readResumeRecord(new ByteArrayInputStream(bytes(e5Json(2))));
        ok("E5".equals(e5.family) && "2".equals(e5.version) && e5.complete, "valid E5 v2 continuation schema and hashes are read-only parseable");
        rejects(() -> SafeImport.readResumeRecord(new ByteArrayInputStream(bytes(e5Json(1)))), "unsupported/old E5 continuation version is rejected");
        rejects(() -> SafeImport.readResumeRecord(new ByteArrayInputStream(bytes("{\"format\":1,\"format\":1}"))), "duplicate JSON fields are rejected");
        rejects(() -> SafeImport.readResumeRecord(new ByteArrayInputStream(bytes("{\"version\":2,\"phase\":\"prepared\"}"))), "continuation record with missing required schema fields is rejected");
        rejects(() -> SafeImport.readResumeRecord(new ByteArrayInputStream(bytes(e5Json(2).replace("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", "not-a-hash")))), "malformed continuation content hash is rejected");
        Map<String, Object> m = new LinkedHashMap<>(); m.put("version", 2L); m.put("phase", "prepared"); m.put("command", "touch /tmp/nope");
        rejects(() -> SafeImport.readResumeRecord(new ByteArrayInputStream(bytes(json(m)))), "malformed/unknown command record is rejected without execution");
        String shown = e5.safeText(false);
        ok(!shown.contains("SIMULATED-SERIAL") && !shown.contains("aaaaaaaa") && !shown.contains("synthetic-key"), "record display summary does not echo identity, CID, or secret fields");
    }

    private static void importTests() throws Exception {
        PartitionPlanner.Snapshot valid = PartitionPlanner.syntheticBaseline(512);
        SafeImport.ArchiveReport okReport = SafeImport.readBackupZip(new ByteArrayInputStream(export(valid, false, false, valid.last, null, false)));
        ok(okReport.backups.size() == 1 && okReport.records.size() == 1 && okReport.outerHashVerified,
                "correct current ZIP imports GPT baseline and validates continuation record hashes/identity");
        ok(!okReport.backups.get(0).baseline.independentlyReadOnly() && okReport.backups.get(0).baseline.identityDigest.isEmpty(),
                "imported GPT remains historical baseline, never current-device evidence");
        SafeImport.ArchiveReport legacy = SafeImport.readBackupZip(new ByteArrayInputStream(legacyExport(valid)));
        ok(legacy.backups.size() == 1 && !legacy.outerHashVerified, "legacy nested GPT ZIP with inner SHA256SUMS remains compatible as view-only baseline");
        SafeImport.ArchiveReport e5 = SafeImport.readBackupZip(new ByteArrayInputStream(e5Export(valid)));
        ok(e5.backups.size() == 1 && "E5".equals(e5.backups.get(0).family) && e5.records.size() == 1,
                "current E5 export path/v2 transaction imports only matched historical GPT and views the record");
        rejects(() -> SafeImport.readBackupZip(new ByteArrayInputStream(export(valid, true, false, valid.last, null, false))), "corrupted GPT SHA-256 manifest is rejected");
        rejects(() -> SafeImport.readBackupZip(new ByteArrayInputStream(export(valid, false, true, valid.last, null, false))), "corrupted outer SHA-256 manifest is rejected");
        rejects(() -> SafeImport.readBackupZip(new ByteArrayInputStream(export(valid, false, false, valid.last, null, false, true))), "GPT layout sector geometry mismatch is rejected");
        rejects(() -> SafeImport.readBackupZip(new ByteArrayInputStream(missingLastExport(valid))), "archive missing a required GPT member is rejected");
        byte[] badLast = valid.last.clone(); badLast[0] ^= 1;
        rejects(() -> SafeImport.readBackupZip(new ByteArrayInputStream(export(valid, false, false, badLast, null, false))), "primary/backup GPT mismatch with refreshed SHA manifests is rejected by CRC/geometry");
        rejects(() -> SafeImport.readBackupZip(new ByteArrayInputStream(export(valid, false, false, valid.last, "OTHER-SYNTHETIC-SERIAL", false))), "backup identity inconsistent with continuation record is rejected");
        byte[][] dup = duplicateGuid(valid);
        PartitionPlanner.Snapshot dupSnap = PartitionPlanner.Snapshot.verified(dup[0], dup[1], valid.sectors512, PartitionPlanner.Origin.BASELINE_BACKUP, "");
        SafeImport.ArchiveReport dupReport = SafeImport.readBackupZip(new ByteArrayInputStream(export(dupSnap, false, false, dup[1], null, false)));
        ok(dupReport.backups.get(0).baseline.integrityWarnings.size() >= 2, "duplicate-GUID historical baseline imports with structural warnings");
        rejects(() -> SafeImport.readBackupZip(new ByteArrayInputStream(oneEntryZip("../escape", new byte[]{1}))), "ZIP path traversal is rejected");
        rejects(() -> SafeImport.readBackupZip(new ByteArrayInputStream(duplicateExactZip("mu300-backup/sim/file.bin"))), "duplicate ZIP member names are rejected");
        rejects(() -> SafeImport.readBackupZip(new ByteArrayInputStream(twoCaseNames())), "case-normalized path collisions are rejected");
        rejects(() -> SafeImport.readBackupZip(new ByteArrayInputStream(oneEntryZip("uninstall/sim/large.bin", new byte[65])), 64, 128, 200), "per-entry/total decompression limit rejects a ZIP bomb simulation");
        ok(SafeImport.compressedExceedsLimit(10001, 10, 200), "compression-ratio bomb threshold is enforced by the same production predicate");
        rejects(() -> SafeImport.readBackupZip(new ByteArrayInputStream(export(valid, false, false, valid.last, null, true))), "unrecognized command field in archive record is rejected; no command is executed");
        ok(!new File(System.getProperty("java.io.tmpdir"), "should-never-run").exists(), "malicious command text has no execution side effect");
        PartitionPlanner.Snapshot current = PartitionPlanner.syntheticCurrent(512);
        PartitionPlanner.Preview preview = PartitionPlanner.preview(current, okReport.backups.get(0).baseline,
                new PartitionPlanner.Request(7168, false, false, "", Arrays.asList(
                        new PartitionPlanner.NewPartition("test", "new_a", 256), new PartitionPlanner.NewPartition("test2", "new_b", 256))));
        ok(!preview.deviceWriteAllowed(), "import failure/data cannot create any partition-device write path");
    }

    private static byte[] twoCaseNames() throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            for (String n : new String[]{"uninstall/sim/opaque.bin", "uninstall/sim/OPAQUE.BIN"}) { z.putNextEntry(new ZipEntry(n)); z.write(1); z.closeEntry(); }
        }
        return b.toByteArray();
    }

    private static void storeTests() throws Exception {
        PartitionPlanner.Snapshot s = PartitionPlanner.syntheticBaseline(512);
        File dir = new File(System.getProperty("java.io.tmpdir"), "owrt-safe-import-store");
        remove(dir); if (!dir.mkdirs()) throw new IOException("temp store");
        ImportedBaselineStore.save(dir, s);
        PartitionPlanner.Snapshot got = ImportedBaselineStore.load(dir);
        ok(got != null && Arrays.equals(s.first, got.first) && Arrays.equals(s.last, got.last)
                && got.identityDigest.isEmpty() && got.origin == PartitionPlanner.Origin.BASELINE_BACKUP,
                "shared app-private store contains only validated GPT baseline bytes and no identity digest");
        byte[] raw; try (FileInputStream in = new FileInputStream(new File(dir, "imported-gpt-baseline.bin"))) { raw = in.readAllBytes(); }
        ok(indexOf(raw, bytes("SIMULATED-SERIAL")) < 0, "shared baseline file does not persist the synthetic private serial/record data");
        raw[raw.length - 1] ^= 1; java.nio.file.Files.write(new File(dir, "imported-gpt-baseline.bin").toPath(), raw);
        rejects(() -> ImportedBaselineStore.load(dir), "corrupted local shared baseline fails closed");
        remove(dir);
    }
    private static int indexOf(byte[] a, byte[] b) { outer: for (int i = 0; i + b.length <= a.length; i++) { for (int j=0;j<b.length;j++) if(a[i+j]!=b[j]) continue outer; return i; } return -1; }
    private static void remove(File f) { if (!f.exists()) return; if (f.isDirectory()) { File[] c=f.listFiles(); if(c!=null) for(File x:c) remove(x); } f.delete(); }

    public static void main(String[] args) throws Exception {
        jsonRecordTests(); importTests(); storeTests();
        System.out.println("SafeImportTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError("SafeImportTest failed");
    }
}
