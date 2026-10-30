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
    private static void p64(byte[] b, int p, long v) {
        for (int i = 0; i < 8; i++) b[p + i] = (byte) (v >>> (8 * i));
    }
    private static void gptPart(byte[] entries, int number, long startLba, long endLba, String name, int seed) {
        int o = (number - 1) * 128;
        byte[] type = Mu300Gpt.guidLe(java.util.UUID.fromString("0fc63daf-8483-4772-8e79-3d69d8477de4"));
        byte[] id = Mu300Gpt.guidLe(new java.util.UUID(0x1000000000000000L + seed, seed));
        System.arraycopy(type, 0, entries, o, 16); System.arraycopy(id, 0, entries, o + 16, 16);
        p64(entries, o + 32, startLba); p64(entries, o + 40, endLba);
        byte[] nm = name.getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(nm, 0, entries, o + 56, Math.min(nm.length, 72));
    }
    private static void gptHeader(byte[] raw, int at, int block, long current, long alternate,
                                  long firstUsable, long lastUsable, long table, byte[] entries,
                                  java.util.UUID disk, long logicalSectors) {
        byte[] h = new byte[block];
        System.arraycopy("EFI PART".getBytes(StandardCharsets.US_ASCII), 0, h, 0, 8);
        Mu300Boot.put32le(h, 8, 0x00010000); Mu300Boot.put32le(h, 12, 92);
        p64(h, 24, current); p64(h, 32, alternate); p64(h, 40, firstUsable); p64(h, 48, lastUsable);
        System.arraycopy(Mu300Gpt.guidLe(disk), 0, h, 56, 16); p64(h, 72, table);
        Mu300Boot.put32le(h, 80, 128); Mu300Boot.put32le(h, 84, 128);
        Mu300Boot.put32le(h, 88, Mu300Boot.crc32(entries, 0, entries.length));
        Mu300Boot.put32le(h, 16, 0); Mu300Boot.put32le(h, 16, Mu300Boot.crc32(h, 0, 92));
        System.arraycopy(h, 0, raw, at, block);
    }
    private static byte[][] gptWithUserdata75Root76(boolean duplicateRoot) {
        int block = 4096, factor = block / 512;
        long sectors = 1L << 24, logical = sectors / factor, tableBlocks = 4, firstTable = 2;
        long backupTable = logical - 1 - tableBlocks;
        byte[] entries = new byte[128 * 128];
        for (int n = 1; n <= 74; n++) {
            long lba = 7 + n;
            gptPart(entries, n, lba, lba, "oem" + n, n);
        }
        gptPart(entries, 75, 10000 / factor, 32767 / factor, "userdata", 75);
        gptPart(entries, 76, 32768 / factor, (32768 + 4194303) / factor, "mu300root", 76);
        if (duplicateRoot) gptPart(entries, 77, (32768 + 4194304) / factor,
                (32768 + 4194304) / factor, "mu300root", 77);
        byte[] primary = new byte[(int) ((firstTable + tableBlocks) * block)];
        byte[] backup = new byte[(int) ((logical - backupTable) * block)];
        System.arraycopy(entries, 0, primary, (int) (firstTable * block), entries.length);
        System.arraycopy(entries, 0, backup, 0, entries.length);
        java.util.UUID disk = java.util.UUID.fromString("11111111-2222-3333-4444-555555555555");
        gptHeader(primary, block, block, 1, logical - 1, 6, logical - 7, firstTable, entries, disk, logical);
        gptHeader(backup, backup.length - block, block, logical - 1, 1, 6, logical - 7,
                backupTable, entries, disk, logical);
        return new byte[][]{primary, backup};
    }
    private static Uninstall.Device deviceForLayout(Uninstall.Layout layout, String rootNode) throws Exception {
        Uninstall.Device d = new Uninstall.Device(); d.s = new Mu300Internal.Storage();
        d.s.kind = "ufs"; d.s.diskNode = "sda"; d.s.root = rootNode;
        d.s.start = layout.data.start; d.s.size = Uninstall.sectorsBetween(layout.data.start, layout.data.end);
        d.rootStart = layout.root.start; d.rootSize = Uninstall.sectorsBetween(layout.root.start, layout.root.end);
        return d;
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
        c.rootNode = "sda75"; c.gptFrontSectors512 = 48; c.gptTailStart512 = (1L << 24) - 40;
        c.device.rootStart = start; c.device.rootSize = 8192; c.device.s.start = 10000; c.device.s.size = 22768;
        c.rootGuid = repeated('1', 32);
        c.capacityBytes = 8192L * 512;
        c.fs = new Mu300Ext4Recovery.FsFacts();
        c.fs.uuid = uuid(); c.fs.blocks = 1024; c.fs.logBlockSize = 2;
        c.fs.incompat = Mu300Ext4Recovery.RECOVERY_INCOMPAT;
        c.gptPrimarySha = repeated('a', 64); c.gptBackupSha = repeated('b', 64);
        c.superblockSha = repeated('c', 64);
        c.pkg = new Uninstall.Pkg(); c.pkg.family = "mu300";
        c.pkg.storageLibrary = "# synthetic storage helper".getBytes(StandardCharsets.UTF_8);
        c.proof = new LinkedHashMap<>(); c.proof.put("evidence", "synthetic");
        c.fingerprint = Mu300Ext4Recovery.fingerprint(c);
        return c;
    }
    private static void profileTests() {
        ok(Mu300Ext4Recovery.supportedMu300Profile("f50"), "accepts MU300/F50 UFS profile");
        ok(Mu300Ext4Recovery.supportedMu300Profile("u30air"), "accepts U30 Air UFS profile");
        ok(Mu300Ext4Recovery.supportedMu300Profile("v50"), "accepts V50 UFS profile");
        ok(!Mu300Ext4Recovery.supportedMu300Profile("unknown"), "rejects unknown device profile");
        ok(!Mu300Ext4Recovery.supportedMu300Profile(null), "rejects missing device profile");
        ok(Mu300Ext4Recovery.matchesAndroidProfile("U30 Air\nU30Air", "ums9620", "u30air"),
                "matches U30 Air model/device properties to u30air profile");
        ok(Mu300Ext4Recovery.matchesAndroidProfile("ZTE F50\nF50", "ums9620", "f50"),
                "matches F50 model/device properties to f50 profile");
        ok(Mu300Ext4Recovery.matchesAndroidProfile("V50\nMU3351", "ums9620", "v50"),
                "matches V50 properties with required platform");
        ok(!Mu300Ext4Recovery.matchesAndroidProfile("U30 Air\nU30Air", "ums9620", "f50"),
                "rejects a profile inconsistent with Android properties");
        ok(!Mu300Ext4Recovery.matchesAndroidProfile("V50\nMU3351", "ums9230", "v50"),
                "rejects V50 on an unsupported platform");
    }
    private static void targetGateTests() {
        Mu300Ext4Recovery.Candidate c = candidate(repeated('a', 32), 32768);
        c.device.profile = "u30air"; c.device.booted = true;
        Uninstall.Pkg pkg = new Uninstall.Pkg(); pkg.family = "mu300";
        ok(Mu300Ext4Recovery.targetGateFailure(c.device, pkg) == null,
                "U30 Air with verified UFS identity passes preliminary target gates");
        c.device.s.root = "sda76";
        ok(Mu300Ext4Recovery.targetGateFailure(c.device, pkg) == null,
                "preliminary gates do not hard-code the root partition number");
        c.device.s.root = "/dev/block/sda76";
        String reason = Mu300Ext4Recovery.targetGateFailure(c.device, pkg);
        ok(reason != null && reason.contains("root_node=<missing_or_invalid>") && !reason.contains(c.device.s.cid),
                "rejects a malformed root node without exposing the complete CID");
        c.device.s.root = "sda75"; c.device.s.diskNode = "sdb";
        reason = Mu300Ext4Recovery.targetGateFailure(c.device, pkg);
        ok(reason != null && reason.contains("disk_node=sdb"), "rejects a different UFS disk/LUN");
        c.device.s.diskNode = "sda";
        c.device.s.root = "sda75"; c.device.s.cid = "invalid-cid";
        reason = Mu300Ext4Recovery.targetGateFailure(c.device, pkg);
        ok(reason != null && reason.contains("storage_identity_format=invalid") && !reason.contains("invalid-cid"),
                "reports an invalid identity format without echoing the identity");
        c.device.s.cid = repeated('a', 32); c.device.s.sectorSize = 1024;
        reason = Mu300Ext4Recovery.targetGateFailure(c.device, pkg);
        ok(reason != null && reason.contains("logical_block_size=1024"), "reports an unsupported logical block size");
        c.device.s.sectorSize = 512; c.device.profile = "unknown";
        reason = Mu300Ext4Recovery.targetGateFailure(c.device, pkg);
        ok(reason != null && reason.contains("device_profile=unsupported_or_missing"), "reports an unsupported profile safely");
    }
    private static void rootBindingTests() throws Exception {
        long sectors = 1L << 24;
        byte[][] copies = gptWithUserdata75Root76(false);
        Uninstall.Layout layout = Uninstall.deviceLayout(copies[0], copies[1], sectors, "mu300root");
        Uninstall.Device d = deviceForLayout(layout, "sda76");
        ok(layout.data.n == 75 && layout.root.n == 76,
                "synthetic GPT has userdata at sda75 and the unique mu300root at sda76");
        ok("sda76".equals(Mu300Ext4Recovery.resolveRootNode(d, layout)),
                "binds candidate to the unique GPT mu300root node sda76");
        d.s.root = "sda77";
        rejects(() -> Mu300Ext4Recovery.resolveRootNode(d, layout), "rejects an unknown kernel root node not named by GPT");
        d.s.root = "sda76"; d.s.diskNode = "sdb";
        rejects(() -> Mu300Ext4Recovery.resolveRootNode(d, layout), "rejects a different parent disk/LUN");
        d.s.diskNode = "sda"; d.rootStart++;
        rejects(() -> Mu300Ext4Recovery.resolveRootNode(d, layout), "rejects a mismatched root start geometry");
        d.rootStart = layout.root.start; d.rootSize++;
        rejects(() -> Mu300Ext4Recovery.resolveRootNode(d, layout), "rejects a mismatched root size geometry");
        d.rootSize = Uninstall.sectorsBetween(layout.root.start, layout.root.end); d.s.start++;
        rejects(() -> Mu300Ext4Recovery.resolveRootNode(d, layout), "rejects a mismatched userdata start");
        d.s.start = layout.data.start; d.s.size++;
        rejects(() -> Mu300Ext4Recovery.resolveRootNode(d, layout), "rejects a mismatched userdata size");
        byte[][] duplicate = gptWithUserdata75Root76(true);
        rejects(() -> Uninstall.deviceLayout(duplicate[0], duplicate[1], sectors, "mu300root"),
                "rejects duplicate mu300root labels instead of choosing one");
        byte[][] damaged = gptWithUserdata75Root76(false); damaged[1][0] ^= 1;
        rejects(() -> Uninstall.deviceLayout(damaged[0], damaged[1], sectors, "mu300root"),
                "rejects mismatched primary/backup GPT snapshots");
    }
    private static void scriptBindingTests(File work) throws Exception {
        Mu300Ext4Recovery.Candidate c = candidate(repeated('a', 32), 32768);
        c.device.s.sectorSize = 4096; c.device.s.root = "sda76";
        c.root = new Mu300Gpt.Part(76, 32768, 32768 + 8191, "mu300root"); c.rootNode = "sda76";
        c.device.rootStart = c.root.start; c.device.rootSize = 8192;
        c.gptFrontSectors512 = 48; c.gptTailStart512 = (1L << 24) - 40;
        c.fingerprint = Mu300Ext4Recovery.fingerprint(c);
        String remote = "/dev/.owrt-ext4-journal-" + repeated('d', 32);
        String replay = Mu300Ext4Recovery.replayScript(remote, c);
        ok(replay.contains("/dev/block/sda76") && replay.contains("N=sda76; B=sda") && !replay.contains("sda75"),
                "live replay script binds D and N to the candidate's sda76 path");
        ok(replay.contains("-p -E journal_only \"$D\"") && !replay.contains(" -y ")
                        && !replay.contains("umount") && !replay.contains("mkfs") && !replay.contains("resize2fs"),
                "mutation remains journal-only with no broad repair or automatic unmount");
        ok(replay.contains("DEVFS=") && replay.contains("{ [ \"$DEVFS\" != tmpfs ] && [ \"$DEVFS\" != devtmpfs ]; }"),
                "replay rejects non-RAM /dev using an explicitly grouped shell condition");
        ok(replay.contains("sha256sum") && replay.contains("skip=1024 count=1024")
                        && replay.contains("mu300_storage_part mu300root")
                        && replay.contains("/sys/class/block/$N/holders") && replay.contains("/proc/self/mountinfo"),
                "replay rechecks both GPT copies, superblock hash, GPT label, holders and mounts");
        String verify = Mu300Ext4Recovery.readOnlyFsckScript(remote, c);
        ok(verify.contains("/dev/block/sda76") && verify.contains("-f -n \"$D\"")
                        && verify.contains("/sys/class/block/$N/holders") && verify.contains("/proc/self/mountinfo"),
                "post-replay read-only fsck uses the same candidate path and busy checks");
        Files.write(new File(work, "replay-script.sh").toPath(), replay.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(work, "readonly-script.sh").toPath(), verify.getBytes(StandardCharsets.UTF_8));
    }
    private static void mutationMustBeRefused(Mu300Ext4Recovery.Candidate expected,
                                               Mu300Ext4Recovery.Candidate changed,
                                               String label, boolean[] called) throws Exception {
        changed.fingerprint = Mu300Ext4Recovery.fingerprint(changed);
        rejects(() -> Mu300Ext4Recovery.replayGuarded(expected, changed, false,
                node -> { called[0] = true; return 0; }), label);
    }
    private static void superblockTests() throws Exception {
        byte[] dirty = sb(0x46, 1, 2, 0, 0, 8, 0, "");
        Mu300Ext4Recovery.FsFacts r = Mu300Ext4Recovery.parseRecoverySuperblock(dirty, capacity(dirty));
        ok(r.incompat == 0x46 && r.logBlockSize == 2 && r.errorCount == 0, "accepts exact needs_recovery candidate");

        byte[] clean = sb(0x42, 1, 2, 0, 0, 8, 0, "");
        Mu300Ext4Recovery.FsFacts c = Mu300Ext4Recovery.parseCleanSuperblock(clean, capacity(clean), uuid());
        ok(c.incompat == 0x42 && c.state == 1, "accepts clean post-replay layout with same UUID");
        rejects(() -> Mu300Ext4Recovery.parseCleanSuperblock(clean, capacity(clean), repeated('0', 32)), "rejects changed ext4 UUID");

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
        Mu300Ext4Recovery.Candidate expected = candidate(repeated('a', 32), 32768);
        Mu300Ext4Recovery.Candidate same = candidate(repeated('a', 32), 32768);
        String[] target = {""};
        int rc = Mu300Ext4Recovery.replayGuarded(expected, same, false, node -> {
            target[0] = node;
            rootExt4[1024] ^= 1; // Synthetic replay mutates only the root ext4 fixture.
            return 1;
        });
        ok(rc == 1 && expected.devicePath().equals(target[0]), "mutation callback receives exactly the path bound to its read-only candidate");
        ok(Arrays.equals(gpt, g0) && Arrays.equals(boot, b0) && Arrays.equals(misc, m0) && Arrays.equals(userdata, u0)
                && !Arrays.equals(rootExt4, r0), "synthetic replay changes root ext4 fixture only; GPT/boot/misc/userdata remain byte-identical");

        Mu300Ext4Recovery.Candidate expected76 = candidate(repeated('a', 32), 32768);
        expected76.root = new Mu300Gpt.Part(76, expected76.root.start, expected76.root.end, "mu300root");
        expected76.rootNode = "sda76"; expected76.device.s.root = "sda76";
        expected76.fingerprint = Mu300Ext4Recovery.fingerprint(expected76);
        Mu300Ext4Recovery.Candidate current76 = candidate(repeated('a', 32), 32768);
        current76.root = new Mu300Gpt.Part(76, current76.root.start, current76.root.end, "mu300root");
        current76.rootNode = "sda76"; current76.device.s.root = "sda76";
        current76.fingerprint = Mu300Ext4Recovery.fingerprint(current76);
        String[] target76 = {""};
        int rc76 = Mu300Ext4Recovery.replayGuarded(expected76, current76, false, node -> { target76[0] = node; return 0; });
        ok(rc76 == 0 && "/dev/block/sda76".equals(target76[0]),
                "journal callback follows the same verified sda76 path when GPT assigns mu300root entry 76");

        final boolean[] called = {false};
        Mu300Ext4Recovery.Candidate wrongDevice = candidate(repeated('f', 32), 32768);
        rejects(() -> Mu300Ext4Recovery.replayGuarded(expected, wrongDevice, false, node -> { called[0] = true; return 0; }), "refuses changed device identity");
        Mu300Ext4Recovery.Candidate wrongGeometry = candidate(repeated('a', 32), 32769);
        rejects(() -> Mu300Ext4Recovery.replayGuarded(expected, wrongGeometry, false, node -> { called[0] = true; return 0; }), "refuses changed GPT geometry");
        Mu300Ext4Recovery.Candidate wrongNode = candidate(repeated('a', 32), 32768);
        wrongNode.root = new Mu300Gpt.Part(76, wrongNode.root.start, wrongNode.root.end, "mu300root");
        wrongNode.rootNode = "sda76"; wrongNode.device.s.root = "sda76";
        mutationMustBeRefused(expected, wrongNode, "refuses a changed root-node binding", called);
        Mu300Ext4Recovery.Candidate wrongGuid = candidate(repeated('a', 32), 32768);
        wrongGuid.rootGuid = repeated('2', 32);
        mutationMustBeRefused(expected, wrongGuid, "refuses a changed GPT partition GUID", called);
        Mu300Ext4Recovery.Candidate wrongUuid = candidate(repeated('a', 32), 32768);
        wrongUuid.fs.uuid = repeated('3', 32);
        mutationMustBeRefused(expected, wrongUuid, "refuses a changed ext4 UUID", called);
        Mu300Ext4Recovery.Candidate wrongGptHash = candidate(repeated('a', 32), 32768);
        wrongGptHash.gptBackupSha = repeated('4', 64);
        mutationMustBeRefused(expected, wrongGptHash, "refuses a changed GPT snapshot hash", called);
        Mu300Ext4Recovery.Candidate wrongSuperblock = candidate(repeated('a', 32), 32768);
        wrongSuperblock.superblockSha = repeated('5', 64);
        mutationMustBeRefused(expected, wrongSuperblock, "refuses a changed ext4 superblock hash", called);
        Mu300Ext4Recovery.Candidate wrongDiskSize = candidate(repeated('a', 32), 32768);
        wrongDiskSize.device.s.diskSize++;
        mutationMustBeRefused(expected, wrongDiskSize, "refuses a changed parent disk size", called);
        Mu300Ext4Recovery.Candidate wrongBlockSize = candidate(repeated('a', 32), 32768);
        wrongBlockSize.device.s.sectorSize = 4096;
        mutationMustBeRefused(expected, wrongBlockSize, "refuses a changed logical block size", called);
        Mu300Ext4Recovery.Candidate wrongBoot = candidate(repeated('a', 32), 32768);
        wrongBoot.device.bootId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        mutationMustBeRefused(expected, wrongBoot, "refuses a changed Android boot identity", called);
        rejects(() -> Mu300Ext4Recovery.replayGuarded(expected, same, true, node -> { called[0] = true; return 0; }), "refuses mounted/held target");
        rejects(() -> Mu300Ext4Recovery.replayGuarded(expected, same, false, node -> 4), "stops on replay error requiring manual action");
        ok(!called[0], "all wrong-device/path/GPT/UUID/geometry/busy guards avoid mutation callback");
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
        v.put("cid", repeated('a', 32));
        v.put("remote_tmpfs", "/dev/.owrt-ext4-journal-" + repeated('a', 32));
        v.put("candidate_fingerprint", repeated('f', 64)); v.put("root_guid", repeated('1', 32));
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

        File duplicates = new File(work, "duplicate-records");
        String duplicateCid = repeated('e', 32);
        for (int i = 0; i < 2; i++) {
            File child = new File(duplicates, "record-" + i);
            if (!child.mkdirs()) throw new IllegalStateException("cannot create duplicate record fixture");
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("cid", duplicateCid); record.put("stage", "precheck_failed");
            record.put("remote_tmpfs", "/dev/.owrt-ext4-journal-" + repeated((char) ('a' + i), 32));
            Files.write(new File(child, Mu300Ext4Recovery.RECORD).toPath(),
                    Uninstall.Json.write(record, true).getBytes(StandardCharsets.UTF_8));
        }
        rejects(() -> Mu300Ext4Recovery.findForCid(duplicates, duplicateCid),
                "rejects multiple transaction records for the same storage identity");
    }
    public static void main(String[] args) throws Exception {
        File work = args.length > 0 ? new File(args[0]) : Files.createTempDirectory("mu300-ext4-recovery-test-").toFile();
        if (!work.exists() && !work.mkdirs()) throw new IllegalStateException("cannot create test directory");
        profileTests();
        targetGateTests();
        rootBindingTests();
        superblockTests();
        scriptBindingTests(work);
        guardAndInvariantTests();
        interruptedAndRecordTests(work);
        System.out.println("Mu300Ext4RecoveryTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }
}
