package top.linote.owrtinstaller;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Pure in-memory synthetic GPT/ZIP tests only: no block device, Android bridge, or uploaded file is used. */
public final class PartitionPlannerTest {
    private static int passed, failed;

    private static void ok(boolean condition, String label) {
        if (condition) { passed++; System.out.println("PASS " + label); }
        else { failed++; System.out.println("FAIL " + label); }
    }

    private interface Check { void run() throws Exception; }

    private static void rejects(Check check, String label) {
        try { check.run(); ok(false, label); }
        catch (Throwable expected) { ok(true, label + " [" + expected.getMessage() + "]"); }
    }

    private static void arithmeticOverflow(Check check, String label) {
        try { check.run(); ok(false, label + " [no exception]"); }
        catch (ArithmeticException expected) { ok(true, label); }
        catch (Throwable unexpected) { ok(false, label + " [wrong exception: " + unexpected + "]"); }
    }

    private static PartitionPlanner.Request request(boolean format, String phrase, List<PartitionPlanner.NewPartition> additions) {
        return new PartitionPlanner.Request(7168, format, false, phrase, additions);
    }

    private static List<PartitionPlanner.NewPartition> twoParts() {
        List<PartitionPlanner.NewPartition> list = new ArrayList<>();
        list.add(new PartitionPlanner.NewPartition("OpenWrt 辅助", "owrt_aux", 1024));
        list.add(new PartitionPlanner.NewPartition("共享存储", "shared", 512));
        return list;
    }

    private static PartitionPlanner.Entry byName(List<PartitionPlanner.Entry> entries, String name) {
        for (PartitionPlanner.Entry e : entries) if (name.equalsIgnoreCase(e.name)) return e;
        return null;
    }

    private static String sha(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder s = new StringBuilder();
        for (byte b : hash) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }

    private static byte[][] withDuplicateIdentity(PartitionPlanner.Snapshot source) {
        int block = source.logicalBlockSize;
        byte[] first = source.first.clone(), last = source.last.clone();
        int header = block;
        ByteBuffer h = ByteBuffer.wrap(first).order(ByteOrder.LITTLE_ENDIAN);
        int table = (int) (h.getLong(header + 72) * block);
        int count = h.getInt(header + 80), entrySize = h.getInt(header + 84);
        byte[] entries = Arrays.copyOfRange(first, table, table + count * entrySize);
        System.arraycopy(entries, 16, entries, entrySize + 16, 16); // duplicate the first unique GUID
        System.arraycopy(entries, 56, entries, entrySize + 56, 72); // duplicate the first label
        System.arraycopy(entries, 0, first, table, entries.length);
        System.arraycopy(entries, 0, last, 0, entries.length);
        int tableCrc = Mu300Boot.crc32(entries, 0, entries.length);
        put32(first, header + 88, tableCrc);
        int backupHeader = last.length - block;
        put32(last, backupHeader + 88, tableCrc);
        refreshHeader(first, header, block);
        refreshHeader(last, backupHeader, block);
        return new byte[][]{first, last};
    }

    private static void put32(byte[] b, int p, int v) { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(p, v); }

    private static void refreshHeader(byte[] b, int at, int block) {
        ByteBuffer x = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int headerSize = x.getInt(at + 12);
        put32(b, at + 16, 0);
        put32(b, at + 16, Mu300Boot.crc32(b, at, headerSize));
    }

    private static void duplicateIdentityTests() throws Exception {
        PartitionPlanner.Snapshot valid = PartitionPlanner.syntheticBaseline(512);
        byte[][] duplicate = withDuplicateIdentity(valid);
        PartitionPlanner.Snapshot historical = PartitionPlanner.Snapshot.verified(duplicate[0], duplicate[1], valid.sectors512,
                PartitionPlanner.Origin.BASELINE_BACKUP, "");
        ok(historical.integrityWarnings.size() >= 2 && historical.integrityWarnings.toString().contains("重复"),
                "historical baseline with duplicate unique GUID/label remains importable with explicit warnings");
        PartitionPlanner.Preview preview = PartitionPlanner.preview(PartitionPlanner.syntheticCurrent(512), historical,
                request(false, "", twoParts()));
        ok(preview.blockers.stream().anyMatch(w -> w.contains("历史 GPT 基线有结构警告")),
                "duplicate-identity warnings from a historical baseline flow into the visible fail-closed plan blockers");
        rejects(() -> PartitionPlanner.Snapshot.verified(duplicate[0], duplicate[1], valid.sectors512,
                PartitionPlanner.Origin.SYNTHETIC, ""),
                "same duplicate-GUID/label layout is rejected when treated as a current layout");
    }

    private static void layoutTests(int blockSize) throws Exception {
        PartitionPlanner.Snapshot current = PartitionPlanner.syntheticCurrent(blockSize);
        PartitionPlanner.Snapshot baseline = PartitionPlanner.syntheticBaseline(blockSize);
        PartitionPlanner.Preview p = PartitionPlanner.preview(current, baseline, request(false, "", twoParts()));
        ok(p.primaryCrcValid && p.backupCrcValid, blockSize + "B preview recomputes and validates primary/backup GPT CRCs");
        ok(!p.baselineMatches && p.blockers.stream().anyMatch(s -> s.contains("不匹配")),
                blockSize + "B old baseline mismatch is visible and blocks hypothetical apply");
        ok(!p.current.independentlyReadOnly() && !p.deviceWriteAllowed(),
                blockSize + "B synthetic layout never qualifies as current-device evidence or enables writes");
        PartitionPlanner.Entry oldData = byName(current.entries, "userdata");
        PartitionPlanner.Entry newData = byName(p.proposedEntries, "userdata");
        ok(oldData != null && newData != null && oldData.start == newData.start
                        && oldData.typeGuid.equals(newData.typeGuid) && oldData.uniqueGuid.equals(newData.uniqueGuid)
                        && oldData.name.equals(newData.name) && newData.end < oldData.end,
                blockSize + "B userdata shrink preserves its original start/type/unique GUID/name");

        boolean nonOverlap = true, aligned = true, inBounds = true;
        List<PartitionPlanner.Entry> byStart = new ArrayList<>(p.proposedEntries);
        byStart.sort((a, b) -> Long.compare(a.start, b.start));
        PartitionPlanner.Entry previous = null;
        for (PartitionPlanner.Entry e : byStart) {
            if (previous != null && e.start <= previous.end) nonOverlap = false;
            if (e.start % PartitionPlanner.ALIGNMENT_SECTORS != 0 || (e.end + 1) % PartitionPlanner.ALIGNMENT_SECTORS != 0) aligned = false;
            if (e.start < current.firstUsable512 || e.end > current.lastUsable512) inBounds = false;
            previous = e;
        }
        ok(nonOverlap, blockSize + "B multiple-partition plan has no overlap");
        ok(aligned, blockSize + "B every planned partition start/end respects 1 MiB alignment");
        ok(inBounds, blockSize + "B all planned extents stay within GPT usable bounds");
        PartitionPlanner.Entry a = byName(p.proposedEntries, "owrt_aux");
        PartitionPlanner.Entry b = byName(p.proposedEntries, "shared");
        ok(a != null && b != null && a.sectors() == 1024 * PartitionPlanner.ALIGNMENT_SECTORS
                        && b.sectors() == 512 * PartitionPlanner.ALIGNMENT_SECTORS,
                blockSize + "B preview shows exact requested sizes and allocated sector ranges for multiple partitions");

        for (String protectedName : Arrays.asList("boot_a", "boot_b", "vbmeta_a", "vbmeta_b", "misc", "metadata", "system", "mu300root")) {
            PartitionPlanner.Entry before = byName(current.entries, protectedName);
            PartitionPlanner.Entry after = byName(p.proposedEntries, protectedName);
            if (before == null || !before.sameAs(after)) {
                ok(false, blockSize + "B protected Android/boot partition unchanged: " + protectedName);
                return;
            }
        }
        ok(true, blockSize + "B boot-critical and every other existing partition are retained byte-for-byte in the plan model");

        Mu300Gpt.space(p.firstAfter, p.lastAfter, current.sectors512);
        byte[] damagedPrimary = p.firstAfter.clone(); damagedPrimary[blockSize + 24] ^= 1;
        rejects(() -> Mu300Gpt.space(damagedPrimary, p.lastAfter, current.sectors512),
                blockSize + "B corrupted primary GPT header CRC fails closed");
        byte[] damagedBackupTable = p.lastAfter.clone(); damagedBackupTable[0] ^= 1;
        rejects(() -> Mu300Gpt.space(p.firstAfter, damagedBackupTable, current.sectors512),
                blockSize + "B corrupted backup GPT table CRC fails closed");
    }

    private static void userdataRiskTests() throws Exception {
        PartitionPlanner.Snapshot current = PartitionPlanner.syntheticCurrent(512);
        PartitionPlanner.Snapshot baseline = PartitionPlanner.syntheticBaseline(512);
        PartitionPlanner.Preview pending = PartitionPlanner.preview(current, baseline, request(true, "", twoParts()));
        ok(pending.destructiveConfirmationRequired && !pending.destructiveConfirmationAccepted
                        && pending.blockers.stream().anyMatch(s -> s.contains("清空 userdata")),
                "formatting userdata is visibly destructive and blocked until exact plan-specific confirmation");
        ok(pending.dataImpact().contains("应用、照片") && pending.dataImpact().contains("加密元数据"),
                "userdata warning names user data and encryption metadata loss");
        PartitionPlanner.Preview approvedSimulation = PartitionPlanner.preview(current, baseline,
                request(true, pending.requiredWipePhrase, twoParts()));
        ok(approvedSimulation.destructiveConfirmationAccepted && !approvedSimulation.deviceWriteAllowed(),
                "manual confirmation applies only to this simulated plan and never enables device writes");
        PartitionPlanner.Preview changedPlan = PartitionPlanner.preview(current, baseline,
                request(true, pending.requiredWipePhrase, oneDifferentPart()));
        ok(!changedPlan.destructiveConfirmationAccepted,
                "changing the partition plan invalidates a previously typed userdata confirmation");
    }

    private static List<PartitionPlanner.NewPartition> oneDifferentPart() {
        List<PartitionPlanner.NewPartition> list = new ArrayList<>();
        list.add(new PartitionPlanner.NewPartition("changed", "changed", 1024));
        list.add(new PartitionPlanner.NewPartition("shared", "shared", 512));
        return list;
    }

    private static void duplicateAndFailureTests() throws Exception {
        PartitionPlanner.Snapshot current = PartitionPlanner.syntheticCurrent(512);
        PartitionPlanner.Snapshot baseline = PartitionPlanner.syntheticBaseline(512);
        rejects(() -> PartitionPlanner.preview(current, baseline, request(false, "", Arrays.asList(
                new PartitionPlanner.NewPartition("first", "dup", 256),
                new PartitionPlanner.NewPartition("second", "dup", 256)))),
                "duplicate planned labels are rejected");
        rejects(() -> PartitionPlanner.preview(current, baseline, request(false, "", Arrays.asList(
                new PartitionPlanner.NewPartition("first", "new_a", 256, "11111111-1111-4111-8111-111111111109")))),
                "new GUID duplicated from an existing partition is rejected");
        rejects(() -> PartitionPlanner.preview(current, baseline, request(false, "", Arrays.asList(
                new PartitionPlanner.NewPartition("first", "new_a", 256, "22222222-2222-4222-8222-222222222222"),
                new PartitionPlanner.NewPartition("second", "new_b", 256, "22222222-2222-4222-8222-222222222222")))),
                "duplicate unique GUIDs among planned partitions are rejected");
        rejects(() -> PartitionPlanner.preview(current, baseline, request(false, "", Arrays.asList(
                new PartitionPlanner.NewPartition("first", "boot_a", 256)))),
                "attempt to reuse a boot-critical label is rejected");
        rejects(() -> PartitionPlanner.preview(current, baseline, new PartitionPlanner.Request(9000, false, false, "", twoParts())),
                "userdata growth beyond its existing end is refused");
        rejects(() -> PartitionPlanner.preview(current, baseline, new PartitionPlanner.Request(2048, false, false, "", Arrays.asList(
                new PartitionPlanner.NewPartition("oversized", "too_big", 100000)))),
                "out-of-bounds/oversized allocation stops before any write");

        byte[] badPrimary = current.first.clone(); badPrimary[512 + 24] ^= 1;
        rejects(() -> PartitionPlanner.Snapshot.verified(badPrimary, current.last, current.sectors512,
                PartitionPlanner.Origin.SYNTHETIC, ""), "invalid primary GPT CRC prevents snapshot creation");
        byte[] badBackup = current.last.clone(); badBackup[0] ^= 1;
        rejects(() -> PartitionPlanner.Snapshot.verified(current.first, badBackup, current.sectors512,
                PartitionPlanner.Origin.SYNTHETIC, ""), "invalid backup GPT CRC prevents snapshot creation");

        PartitionPlanner.Snapshot fakeLiveMissingDigest;
        try {
            fakeLiveMissingDigest = PartitionPlanner.Snapshot.verified(current.first, current.last, current.sectors512,
                    PartitionPlanner.Origin.CURRENT_DEVICE_READ_ONLY, "");
            ok(false, "current-device evidence requires a one-way hashed identity binding");
        } catch (IOException expected) { ok(true, "current-device evidence requires a one-way hashed identity binding"); }
    }

    private static void mainAndBackupMismatchTests() throws Exception {
        PartitionPlanner.Snapshot baseline = PartitionPlanner.syntheticBaseline(512);
        PartitionPlanner.Preview same = PartitionPlanner.preview(baseline, baseline,
                new PartitionPlanner.Request(8192, false, true, "", twoParts()));
        ok(same.baselineMatches, "identical current and baseline snapshots compare equal");
        ok(!same.deviceWriteAllowed() && same.blockers.stream().anyMatch(s -> s.contains("Android boot")),
                "missing Android boot/recovery compatibility evidence blocks even a matching baseline");
    }

    private static void checkedMultiplyTests() {
        ok(PartitionPlanner.checkedMultiplyExact(0, Integer.MIN_VALUE) == 0
                        && PartitionPlanner.checkedMultiplyExact(Long.MIN_VALUE, 0) == 0,
                "checked long/int multiplication handles zero operands");
        ok(PartitionPlanner.checkedMultiplyExact(Long.MAX_VALUE, 1) == Long.MAX_VALUE
                        && PartitionPlanner.checkedMultiplyExact(Long.MIN_VALUE, 1) == Long.MIN_VALUE,
                "checked long/int multiplication accepts signed identity boundaries");
        ok(PartitionPlanner.checkedMultiplyExact(Long.MAX_VALUE / 8, 8) == (Long.MAX_VALUE / 8) * 8
                        && PartitionPlanner.checkedMultiplyExact(Long.MIN_VALUE / 8, 8) == Long.MIN_VALUE,
                "checked long/int multiplication accepts largest positive and negative safe products");
        ok(PartitionPlanner.checkedMultiplyExact(Long.MIN_VALUE / Integer.MIN_VALUE, Integer.MIN_VALUE) == Long.MIN_VALUE,
                "checked long/int multiplication accepts exact negative-factor boundary");
        arithmeticOverflow(() -> PartitionPlanner.checkedMultiplyExact(Long.MAX_VALUE, 2),
                "checked long/int multiplication rejects positive overflow");
        arithmeticOverflow(() -> PartitionPlanner.checkedMultiplyExact(Long.MIN_VALUE, -1),
                "checked long/int multiplication rejects minimum times negative one");
        arithmeticOverflow(() -> PartitionPlanner.checkedMultiplyExact(Long.MIN_VALUE, 2),
                "checked long/int multiplication rejects negative-factor underflow");
        arithmeticOverflow(() -> PartitionPlanner.checkedMultiplyExact(Long.MAX_VALUE, -2),
                "checked long/int multiplication rejects positive-factor underflow");
        arithmeticOverflow(() -> PartitionPlanner.checkedMultiplyExact(Long.MAX_VALUE, Integer.MIN_VALUE),
                "checked long/int multiplication rejects extreme negative-factor underflow");
    }

    public static void main(String[] args) throws Exception {
        checkedMultiplyTests();
        layoutTests(512);
        layoutTests(4096);
        userdataRiskTests();
        duplicateAndFailureTests();
        duplicateIdentityTests();
        mainAndBackupMismatchTests();
        System.out.println("\nPartitionPlannerTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }
}
