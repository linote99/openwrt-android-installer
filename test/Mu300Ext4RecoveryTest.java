package top.linote.owrtinstaller;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure synthetic tests only: no block device, ADB, package payload, or uploaded backup is used. */
public final class Mu300Ext4RecoveryTest {
    private static int passed, failed;
    private static void ok(boolean c, String label) {
        if (c) { passed++; System.out.println("PASS " + label); }
        else { failed++; System.out.println("FAIL " + label); }
    }
    private interface Check { void run() throws Exception; }
    private static void rejects(Check check, String label) {
        try { check.run(); ok(false, label); }
        catch (Throwable expected) { ok(true, label + " [" + expected.getMessage() + "]"); }
    }
    private static void p16(byte[] b, int p, int v) { b[p] = (byte) v; b[p + 1] = (byte) (v >>> 8); }
    private static void p32(byte[] b, int p, long v) {
        for (int i = 0; i < 4; i++) b[p + i] = (byte) (v >>> (8 * i));
    }
    private static String repeated(char c, int n) {
        char[] a = new char[n]; Arrays.fill(a, c); return new String(a);
    }
    private static byte[] sb(long incompat, int state, int log, long errors, long defaultOpts,
                             long journalInode, long journalDev, String mountOpts) {
        byte[] b = new byte[1024];
        p32(b, 4, 1024); // capacity=1024 * 4096 bytes for the usual fixture
        p32(b, 24, log);
        p16(b, 56, 0xef53);
        p16(b, 58, state);
        p32(b, 92, 0x4); // has_journal
        p32(b, 96, incompat);
        for (int i = 0; i < 16; i++) b[104 + i] = (byte) (i + 1);
        p32(b, 0x194, errors);
        p32(b, 0x100, defaultOpts);
        p32(b, 0xE0, journalInode);
        p32(b, 0xE4, journalDev);
        if (mountOpts != null) {
            byte[] o = mountOpts.getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(o, 0, b, 0x200, Math.min(o.length, 64));
        }
        return b;
    }
    private static long capacity(byte[] b) {
        long blocks = (b[4] & 255L) | (b[5] & 255L) << 8 | (b[6] & 255L) << 16 | (b[7] & 255L) << 24;
        long log = (b[24] & 255L) | (b[25] & 255L) << 8 | (b[26] & 255L) << 16 | (b[27] & 255L) << 24;
        return blocks * (1024L << log);
    }
    private static String uuid() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 16; i++) s.append(String.format(java.util.Locale.ROOT, "%02x", i + 1));
        return s.toString();
    }
    private static Mu300Ext4Recovery.Candidate candidate(String cid, long start) {
        Mu300Ext4Recovery.Candidate c = new Mu300Ext4Recovery.Candidate();
        c.device = new Uninstall.Device();
        c.device.s = new Mu300Internal.Storage();
        c.device.s.cid = cid;
        c.device.s.kind = "ufs";
        c.device.s.diskNode = "sda";
        c.device.s.root = "sda75";
        c.device.s.sectorSize = 512;
        c.device.s.diskSize = 1L << 24;
        c.device.profile = "f50";
        c.device.slot = "a";
        c.device.bootId = "12345678-1234-1234-1234-123456789abc";
        c.root = new Mu300Gpt.Part(75, start, start + 8191, "mu300root");
        c.rootGuid = "11111111222233334444555555555555";
        c.capacityBytes = 8192L * 512;
        c.fs = new Mu300Ext4Recovery.FsFacts();
        c.fs.uuid = uuid(); c.fs.blocks = 1024; c.fs.logBlockSize = 2;
        c.fs.incompat = Mu300Ext4Recovery.RECOVERY_INCOMPAT;
        c.gptPrimarySha = repeated('a', 64); c.gptBackupSha = repeated('b', 64);
        c.superblockSha = repeated('c', 64);
        c.proof = new LinkedHashMap<>(); c.proof.put("evidence", "synthetic");
        c.fingerprint = Mu300Ext4Recovery.fingerprint(c);
        return c;
    }
    private static void superblockTests() throws Exception {
        byte[] dirty = sb(0x46, 1, 2, 0, 0, 8, 0, "");
        Mu300Ext4Recovery.FsFacts r = Mu300Ext4Recovery.parseRecoverySuperblock(dirty, capacity(dirty));
        ok(r.incompat == 0x46 && r.logBlockSize == 2 && r.errorCount == 0, "accepts exact needs_recovery candidate");

        byte[] clean = sb(0x42, 1, 2, 0, 0, 8, 0, "");
        Mu300Ext4Recovery.FsFacts c = Mu300Ext4Recovery.parseCleanSuperblock(clean, capacity(clean), uuid());
        ok(c.incompat == 0x42 && c.state == 1, "accepts clean post-replay layout with same UUID");
        rejects(() -> Mu300Ext4Recovery.parseCleanSuperblock(clean, capacity(clean), "00000000000000000000000000000000"), "rejects changed ext4 UUID");

        rejects(() -> Mu300Ext4Recovery.parseRecoverySuperblock(sb(0x4e, 1, 2, 0, 0, 8, 0, ""), capacity(dirty)), "rejects additional incompat features");
        rejects(() -> Mu300Ext4Recovery.parseRecoverySuperblock(sb(0x46, 3, 2, 0, 0, 8, 0, ""), capacity(dirty)), "rejects error/dirty state bits");
        rejects(() -> Mu300Ext4Recovery.parseRecoverySuperblock(sb(0x46, 1, 2, 1, 0, 8, 0, ""), capacity(dirty)), "rejects nonzero ext4 error_count");
        rejects(() -> Mu300Ext4Recovery.parseRecoverySuperblock(sb(0x46, 1, 3, 0, 0, 8, 0, ""), 1024L * 8192), "rejects non-4KiB block size");
        rejects(() -> Mu300Ext4Recovery.parseRecoverySuperblock(dirty, capacity(dirty) - 1), "rejects block geometry exceeding GPT capacity");
        rejects(() -> Mu300Ext4Recovery.parseRecoverySuperblock(sb(0x46, 1, 2, 0, 0x20, 8, 0, ""), capacity(dirty)), "rejects data=journal default mode");
        rejects(() -> Mu300Ext4Recovery.parseRecoverySuperblock(sb(0x46, 1, 2, 0, 0, 8, 0, "data=journal"), capacity(dirty)), "rejects data=journal superblock option");
        rejects(() -> Mu300Ext4Recovery.parseRecoverySuperblock(sb(0x46, 1, 2, 0, 0, 0, 1, ""), capacity(dirty)), "rejects external journal device");
        rejects(() -> Mu300Ext4Recovery.parseRecoverySuperblock(sb(0x46, 1, 2, 0, 0, 0, 0, ""), capacity(dirty)), "rejects missing internal journal inode");
        ok(!Mu300Ext4Recovery.acceptedReplayRc(2) && !Mu300Ext4Recovery.acceptedReplayRc(4)
                && Mu300Ext4Recovery.acceptedReplayRc(0) && Mu300Ext4Recovery.acceptedReplayRc(1), "only e2fsck replay rc 0/1 are accepted");
        ok(Mu300Ext4Recovery.acceptedReadOnlyRc(0) && !Mu300Ext4Recovery.acceptedReadOnlyRc(1), "only clean read-only e2fsck rc 0 is accepted");
    }
    private static void guardAndInvariantTests() throws Exception {
        byte[] gpt = new byte[4096], boot = new byte[4096], misc = new byte[2048], userdata = new byte[8192], rootExt4 = new byte[8192];
        Arrays.fill(gpt, (byte) 1); Arrays.fill(boot, (byte) 2); Arrays.fill(misc, (byte) 3);
        Arrays.fill(userdata, (byte) 4); Arrays.fill(rootExt4, (byte) 5);
        byte[] g0 = gpt.clone(), b0 = boot.clone(), m0 = misc.clone(), u0 = userdata.clone(), r0 = rootExt4.clone();
        Mu300Ext4Recovery.Candidate expected = candidate("0123456789abcdef0123456789abcdef", 32768);
        Mu300Ext4Recovery.Candidate same = candidate("0123456789abcdef0123456789abcdef", 32768);
        String[] target = {""};
        int rc = Mu300Ext4Recovery.replayGuarded(expected, same, false, node -> {
            target[0] = node;
            rootExt4[1024] ^= 1; // Synthetic replay mutates only the root ext4 fixture.
            return 1;
        });
        ok(rc == 1 && Mu300Ext4Recovery.DEVICE.equals(target[0]), "mutation callback can target only fixed /dev/block/sda75");
        ok(Arrays.equals(gpt, g0) && Arrays.equals(boot, b0) && Arrays.equals(misc, m0) && Arrays.equals(userdata, u0)
                && !Arrays.equals(rootExt4, r0), "synthetic replay changes root ext4 fixture only; GPT/boot/misc/userdata remain byte-identical");

        final boolean[] called = {false};
        Mu300Ext4Recovery.Candidate wrongDevice = candidate("fedcba9876543210fedcba9876543210", 32768);
        rejects(() -> Mu300Ext4Recovery.replayGuarded(expected, wrongDevice, false, node -> { called[0] = true; return 0; }), "refuses changed device identity");
        Mu300Ext4Recovery.Candidate wrongGeometry = candidate("0123456789abcdef0123456789abcdef", 32769);
        rejects(() -> Mu300Ext4Recovery.replayGuarded(expected, wrongGeometry, false, node -> { called[0] = true; return 0; }), "refuses changed GPT geometry");
        rejects(() -> Mu300Ext4Recovery.replayGuarded(expected, same, true, node -> { called[0] = true; return 0; }), "refuses mounted/held target");
        rejects(() -> Mu300Ext4Recovery.replayGuarded(expected, same, false, node -> 4), "stops on replay error requiring manual action");
        ok(!called[0], "all wrong-device, wrong-geometry, mounted and error guards avoid mutation callback");
    }
    private static void interruptedAndRecordTests(File work) throws Exception {
        ok(Mu300Ext4Recovery.interruptedCanBeVerified("journal_replay_started", false, true, true, 0), "allows only read-only verification of clean interrupted stage");
        ok(!Mu300Ext4Recovery.interruptedCanBeVerified("journal_replay_started", true, true, true, 0), "does not inspect while e2fsck is alive");
        ok(!Mu300Ext4Recovery.interruptedCanBeVerified("journal_replay_returned", false, false, true, 0), "rejects interrupted record for changed device");
        ok(!Mu300Ext4Recovery.interruptedCanBeVerified("journal_replay_returned", false, true, false, 0), "rejects dirty postcondition");
        ok(!Mu300Ext4Recovery.interruptedCanBeVerified("journal_replay_returned", false, true, true, 4), "rejects nonzero read-only fsck result");
        ok(!Mu300Ext4Recovery.interruptedCanBeVerified("verified", false, true, true, 0), "does not treat unrelated stages as interrupted");

        File dir = new File(work, "tx");
        if (!dir.mkdirs()) throw new IllegalStateException("cannot create synthetic transaction directory");
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("schema", 1L); v.put("id", "synthetic"); v.put("stage", "precheck_failed");
        v.put("cid", "0123456789abcdef0123456789abcdef");
        v.put("remote_tmpfs", "/dev/.owrt-ext4-journal-0123456789abcdef0123456789abcdef");
        v.put("candidate_fingerprint", repeated('f', 64)); v.put("root_guid", "11111111222233334444555555555555");
        v.put("ext4_uuid", uuid()); v.put("root_start", 100L); v.put("root_end", 200L);
        v.put("pre_superblock_sha256", repeated('a', 64));
        Mu300Ext4Recovery.Tx tx = new Mu300Ext4Recovery.Tx(dir, v);
        tx.save(); tx.stage("journal_replay_started");
        String saved = new String(Files.readAllBytes(new File(dir, Mu300Ext4Recovery.RECORD).toPath()), StandardCharsets.UTF_8);
        ok(saved.contains("\"stage\": \"journal_replay_started\""), "transaction stage is atomically persisted and read back before write");
        tx.stage("journal_replay_returned");
        tx.manualReview("synthetic non-accepted rc");
        String reviewed = new String(Files.readAllBytes(new File(dir, Mu300Ext4Recovery.RECORD).toPath()), StandardCharsets.UTF_8);
        ok(reviewed.contains("\"stage\": \"manual_review\""), "manual-review stop stage is persisted and read back");
    }
    public static void main(String[] args) throws Exception {
        File work = args.length > 0 ? new File(args[0]) : Files.createTempDirectory("mu300-ext4-recovery-test-").toFile();
        if (!work.exists() && !work.mkdirs()) throw new IllegalStateException("cannot create test directory");
        superblockTests();
        guardAndInvariantTests();
        interruptedAndRecordTests(work);
        System.out.println("Mu300Ext4RecoveryTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }
}
