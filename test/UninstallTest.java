package top.linote.owrtinstaller;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Regression coverage for uninstall planning/build/evidence. Package files are read as bytes only;
 * neither package payloads nor uninstall-worker.sh are executed.
 */
public class UninstallTest {
    static int passed, failed;
    static void ok(boolean c, String s) { if (c) { passed++; System.out.println("PASS " + s); } else { failed++; System.out.println("FAIL " + s); } }
    static String msg(Throwable t) { return t == null ? "" : String.valueOf(t.getMessage()); }
    interface R { void run() throws Exception; }
    static void rejects(R r, String s) { try { r.run(); ok(false, s); } catch (Throwable t) { ok(true, s + " [" + msg(t) + "]"); } }
    static byte[] zip(String file, String name) throws Exception { try (ZipFile z = new ZipFile(file); InputStream in = z.getInputStream(z.getEntry(name))) { return in.readAllBytes(); } }
    static byte[] worker() throws Exception { return Files.readAllBytes(Paths.get("app/src/main/assets/uninstall-worker.sh")); }
    static byte[] base(String family, String zip) throws Exception { return zip(zip, "e5".equals(family) ? "files/boot.img" : "payload/boot-linux.img"); }
    static byte[] image(String family, String zip) throws Exception {
        byte[] b = base(family, zip), w = worker();
        LinkedHashMap<String, byte[]> f = new LinkedHashMap<>();
        f.put("openwrt-uninstall/request", "cid:1:2:3:4:5:6:7:8:9:10:11:12:13:14:15:16:17:18:19:20\n".getBytes(StandardCharsets.US_ASCII));
        byte[] out = UninstallBoot.build(b, family, f, w);
        ok(out.length == UninstallBoot.BOOT_BYTES, family + " boot output is 64 MiB");
        ok(new String(out, 0, 8, StandardCharsets.ISO_8859_1).equals("ANDROID!"), family + " output parses as Android boot");
        ok(UninstallBoot.ramdiskFiles(UninstallBoot.ramdisk(out)).containsKey("openwrt-uninstall/worker.sh"), family + " ramdisk contains worker");
        ok(Arrays.equals(UninstallBoot.ramdiskFiles(UninstallBoot.ramdisk(out)).get("openwrt-uninstall/worker.sh").data, w), family + " worker bytes are pinned");
        ok(new String(out, 44, 1536, StandardCharsets.ISO_8859_1).contains("openwrt.uninstall=1"), family + " uninstall boot guard is present");
        return out;
    }
    static void bootTests(String family, String zip) throws Exception {
        byte[] b = base(family, zip), w = worker();
        image(family, zip);
        byte[] bad = w.clone(); bad[0] ^= 1;
        rejects(() -> UninstallBoot.build(b, family, new LinkedHashMap<String, byte[]>(), bad), family + " rejects wrong worker sha");
        UninstallBoot.inspectLinuxBoot(Arrays.copyOf(b, 4096), Arrays.copyOfRange(b, b.length - 512, b.length), b.length, family, "b");
        ok(true, family + " accepts the package Linux boot base");
    }
    static void evidence(String family, String zip, File work) throws Exception {
        byte[] boot = base(family, zip);
        if ("mu300".equals(family)) {
            LinkedHashMap<String, byte[]> overlay = new LinkedHashMap<>();
            overlay.put("etc/mu300-desktop-root-mode", "part\n".getBytes(StandardCharsets.US_ASCII));
            boot = UninstallBoot.build(boot, family, overlay, worker());
            // The uninstall builder adds a guard for its offline image. Remove only that
            // command-line word so this remains a synthetic already-installed Linux boot.
            String cmd = new String(boot, 44, 1536, StandardCharsets.ISO_8859_1).replace(" openwrt.uninstall=1", "").trim();
            Arrays.fill(boot, 44, 1580, (byte)0);
            byte[] cb = cmd.getBytes(StandardCharsets.US_ASCII); System.arraycopy(cb, 0, boot, 44, cb.length);
        }
        File tree = new File(work, family + "-tree");
        String[] paths = "e5".equals(family) ? new String[]{"etc/e5/part-root", "etc/openwrt_release"}
                : new String[]{"openwrt/etc/mu300/desktop-mode", ".mu300/boot-os", "openwrt/etc/openwrt_release"};
        for (String p : paths) { File f = new File(tree, p); if (!f.getParentFile().mkdirs() && !f.getParentFile().isDirectory()) throw new IOException("mkdir"); Files.writeString(f.toPath(), p.endsWith("desktop-mode") ? "part\n" : (p.endsWith("part-root") ? "e5root /dev/mmcblk0" : (p.endsWith("boot-os") ? "android\n" : "DISTRIB_ID='OpenWrt'\n"))); }
        File img = new File(work, family + ".ext4");
        Process p = new ProcessBuilder("truncate", "-s", "64M", img.getAbsolutePath()).inheritIO().start(); ok(p.waitFor() == 0, family + " creates ext4 fixture");
        p = new ProcessBuilder("mkfs.ext4", "-q", "-F", "-d", tree.getAbsolutePath(), img.getAbsolutePath()).inheritIO().start(); ok(p.waitFor() == 0, family + " formats ext4 fixture");
        byte[] raw = Files.readAllBytes(img.toPath());
        final byte[] cleanRaw = raw;
        InstallEvidence.Reader rd = (off, len) -> Arrays.copyOfRange(cleanRaw, (int)off, (int)off + len);
        final byte[] installedBoot = boot;
        byte[] header = Arrays.copyOf(installedBoot, 4096), tail = Arrays.copyOfRange(installedBoot, installedBoot.length - 512, installedBoot.length);
        long start = 4096L + ((Mu300Boot.u32le(header, 8) + 4095L) / 4096L) * 4096L;
        long n = (Mu300Boot.u32le(header, 12) + 4095L) / 4096L;
        byte[] pages = Arrays.copyOfRange(boot, (int)start, (int)(start + n * 4096L));
        InstallEvidence.inspect(family, "mmcblk0", "b", installedBoot.length, header, tail, pages, rd, cleanRaw.length);
        ok(true, family + " accepts clean ext4 evidence and Linux boot");
        byte[] dirty = raw.clone(); dirty[1024 + 58] = 2; // ext4 state = error
        byte[] d = dirty;
        final byte[] dirtyRaw = dirty;
        rejects(() -> InstallEvidence.inspect(family, "mmcblk0", "b", installedBoot.length, header, tail, pages, (o,l) -> Arrays.copyOfRange(dirtyRaw,(int)o,(int)o+l), dirtyRaw.length), family + " rejects unclean ext4");
        // needs_recovery (journal not replayed) must be refused, and the message must name the failed condition with numbers.
        for (long[] c : new long[][]{{0x4, 0}, {0x8000, 1}}) {
            byte[] rec = raw.clone(); int inc = 1024 + 96;
            long v = (rec[inc] & 0xffL) | (rec[inc+1] & 0xffL) << 8 | (rec[inc+2] & 0xffL) << 16 | (rec[inc+3] & 0xffL) << 24;
            v |= c[0]; for (int b = 0; b < 4; b++) rec[inc + b] = (byte) (v >>> (8 * b));
            final byte[] recRaw = rec; String m = null;
            try { InstallEvidence.inspect(family, "mmcblk0", "b", installedBoot.length, header, tail, pages, (o,l) -> Arrays.copyOfRange(recRaw,(int)o,(int)o+l), recRaw.length); }
            catch (Throwable t) { m = msg(t); }
            String hex = String.format("incompat=0x%08x", v);
            boolean good = m != null && m.contains(hex) && m.contains("state=0x0001") && m.contains("log_block_size=")
                    && (c[1] == 0 ? m.contains("needs_recovery(0x4)") && !m.contains("布局不受支持，不是关机问题")
                                  : m.contains("inline_data") && m.contains("布局不受支持，不是关机问题") && !m.contains("needs_recovery(0x4)"));
            ok(good, family + (c[1] == 0 ? " refuses needs_recovery and logs state/incompat/log_block_size" : " refuses unsupported incompat and names the feature") + " [" + m + "]");
        }
        byte[] noMark = raw.clone();
        // Corrupt the superblock magic: this exercises the bounded raw-reader refusal without mounting.
        noMark[1024 + 56] = 0; byte[] nm = noMark;
        final byte[] noMarkerRaw = noMark;
        rejects(() -> InstallEvidence.inspect(family, "mmcblk0", "b", installedBoot.length, header, tail, pages, (o,l) -> Arrays.copyOfRange(noMarkerRaw,(int)o,(int)o+l), noMarkerRaw.length), family + " rejects missing/invalid evidence filesystem");
    }
    static void helperTests(String family) throws Exception {
        byte[] bc = new byte[32]; bc[0]='_'; bc[1]='a'; bc[4]='B'; bc[5]='C'; bc[6]='A'; bc[7]='B'; bc[8]=1; bc[9]=2; bc[12]=(byte)0x8f; bc[14]=(byte)0x8e; Mu300Boot.put32le(bc,28,Mu300Boot.crc32(bc,0,28));
        byte[] trial = UninstallBoot.trialControl(bc, "a");
        ok(trial[1] == 'b' && (trial[12] & 15) == 14 && (trial[14] & 15) == 15, family + " trial control selects other slot once");
        byte[] android = UninstallBoot.androidControl(bc, "a");
        UninstallBoot.checkAndroidHealthy(android, "a"); ok((android[12] & 0xff) == 159 && (android[14] & 0xff) == 30, family + " finish control restores Android slot");
        Uninstall.Volume v = new Uninstall.Volume(); v.source="/dev/mmcblk0p39"; v.filesystem="ext4"; v.uuid="1234567890abcdef1234567890abcdef"; v.mappingOverhead=0; Uninstall.Device d = new Uninstall.Device(); d.s=new Mu300Internal.Storage(); d.s.node="mmcblk0p39"; d.s.state="unencrypted"; d.s.type=""; Uninstall.eligible(d,v); ok(true, family + " accepts eligible direct unencrypted ext4");
        d.s.state="encrypted"; rejects(() -> Uninstall.eligible(d,v), family + " refuses encrypted preservation");
    }

    // Build a small but fully checksummed GPT snapshot. The partition geometry is
    // deliberately kept independent of any host block device.
    static byte[][] gpt(boolean adjacent, String rootName) throws Exception {
        return gpt(adjacent, rootName, 512);
    }
    static byte[][] gpt(boolean adjacent, String rootName, int block) throws Exception {
        final int factor = block / 512;
        final long sectors = 1L << 24, logical = sectors / factor, tableBlocks = (128L * 128 + block - 1) / block;
        final long firstTable = 2, backupTable = logical - 1 - tableBlocks, tail = backupTable * factor;
        byte[] entries = new byte[128 * 128];
        java.util.UUID disk = java.util.UUID.fromString("11111111-2222-3333-4444-555555555555");
        int n = 0;
        long rootStart = adjacent ? 32768 : 65536;
        n = gptPart(entries, n, 10000 / factor, (adjacent ? rootStart - 1 : 49999) / factor, "userdata", 1);
        n = gptPart(entries, n, rootStart / factor, (rootStart + 4194303) / factor, rootName, 2);
        n = gptPart(entries, n, 9000000 / factor, 9002047 / factor, "misc", 3);
        n = gptPart(entries, n, 9010000 / factor, 9018191 / factor, "boot_a", 4);
        n = gptPart(entries, n, 9020000 / factor, 9028191 / factor, "boot_b", 5);
        byte[] p = new byte[(int) ((2 + tableBlocks) * block)], q = new byte[(int) ((logical - backupTable) * block)];
        System.arraycopy(entries, 0, p, (int) (firstTable * block), entries.length);
        System.arraycopy(entries, 0, q, 0, entries.length);
        header(p, block, block, 1, logical - 1, 2 + tableBlocks, backupTable - 1, firstTable, entries, disk, logical);
        header(q, q.length - block, block, logical - 1, 1, 2 + tableBlocks, backupTable - 1, backupTable, entries, disk, logical);
        return new byte[][]{p, q};
    }
    static int gptPart(byte[] t, int n, long a, long b, String name, int seed) {
        int o = n * 128; byte[] type = Mu300Gpt.guidLe(java.util.UUID.fromString("0fc63daf-8483-4772-8e79-3d69d8477de4"));
        System.arraycopy(type, 0, t, o, 16);
        byte[] id = Mu300Gpt.guidLe(new java.util.UUID(0x1000000000000000L + seed, seed));
        System.arraycopy(id, 0, t, o + 16, 16);
        for (int i = 0; i < 8; i++) { t[o + 32 + i] = (byte) (a >>> (8 * i)); t[o + 40 + i] = (byte) (b >>> (8 * i)); }
        byte[] nm = name.getBytes(StandardCharsets.UTF_16LE); System.arraycopy(nm, 0, t, o + 56, nm.length);
        return n + 1;
    }
    static void header(byte[] raw, int at, long current, long alternate, long table, byte[] entries,
                       java.util.UUID disk, long sectors) {
        header(raw, at, 512, current, alternate, 34, sectors - 34, table, entries, disk, sectors);
    }
    static void header(byte[] raw, int at, int block, long current, long alternate, long firstUsable, long lastUsable,
                       long table, byte[] entries, java.util.UUID disk, long sectors) {
        byte[] h = new byte[block]; System.arraycopy("EFI PART".getBytes(StandardCharsets.US_ASCII), 0, h, 0, 8);
        Mu300Boot.put32le(h, 8, 0x00010000); Mu300Boot.put32le(h, 12, 92);
        put64(h, 24, current); put64(h, 32, alternate); put64(h, 40, firstUsable); put64(h, 48, lastUsable);
        byte[] dg = Mu300Gpt.guidLe(disk); System.arraycopy(dg, 0, h, 56, 16); put64(h, 72, table); Mu300Boot.put32le(h, 80, 128); Mu300Boot.put32le(h, 84, 128);
        Mu300Boot.put32le(h, 88, Mu300Boot.crc32(entries, 0, entries.length)); Mu300Boot.put32le(h, 16, Mu300Boot.crc32(h, 0, 92));
        System.arraycopy(h, 0, raw, at, block);
    }
    static void put64le(byte[] b, int p, long v) { for (int i = 0; i < 8; i++) b[p + i] = (byte) (v >>> (8 * i)); }
    static void refresh(byte[][] copies, int block) {
        int tableBytes = 128 * 128, primaryAt = block, backupAt = copies[1].length - block;
        int crc = Mu300Boot.crc32(copies[0], 2 * block, tableBytes);
        Mu300Boot.put32le(copies[0], primaryAt + 88, crc); Mu300Boot.put32le(copies[1], backupAt + 88, crc);
        Mu300Boot.put32le(copies[0], primaryAt + 16, 0); Mu300Boot.put32le(copies[0], primaryAt + 16, Mu300Boot.crc32(copies[0], primaryAt, 92));
        Mu300Boot.put32le(copies[1], backupAt + 16, 0); Mu300Boot.put32le(copies[1], backupAt + 16, Mu300Boot.crc32(copies[1], backupAt, 92));
    }
    static void put64(byte[] b, int p, long v) { for (int i = 0; i < 8; i++) b[p + i] = (byte) (v >>> (8 * i)); }
    static long get64(byte[] b, int p) { long v = 0; for (int i = 7; i >= 0; i--) v = (v << 8) | (b[p + i] & 255L); return v; }

    static void ufsUninstallTests() throws Exception {
        final long sectors = 1L << 24;
        for (int block : new int[]{512, 4096}) {
            byte[][] adjacent = gpt(true, "mu300root", block), gap = gpt(false, "mu300root", block);
            Uninstall.Layout a = Uninstall.deviceLayout(adjacent[0], adjacent[1], sectors, "mu300root");
            Uninstall.Layout g = Uninstall.deviceLayout(gap[0], gap[1], sectors, "mu300root");
            ok(a.expands && a.target.start == a.data.start && a.target.end == a.root.end,
                    "UFS " + block + "-byte adjacent root is reclaimed into userdata exactly");
            ok(!g.expands && g.target.start == g.data.start && g.target.end == g.data.end && g.root.end > g.data.end,
                    "UFS " + block + "-byte non-adjacent root leaves intervening gap untouched");
            ok(a.after[0].length == adjacent[0].length && a.after[1].length == adjacent[1].length,
                    "UFS " + block + "-byte primary/backup snapshot lengths retain exact bytes");

            byte[][] badBackup = gpt(true, "mu300root", block); badBackup[1][0] ^= 1;
            rejects(() -> Uninstall.deviceLayout(badBackup[0], badBackup[1], sectors, "mu300root"),
                    "UFS " + block + "-byte uninstall refuses mismatched backup GPT");
            byte[][] badRootAlignment = gpt(true, "mu300root", block);
            int factor = block / 512, pEntry = 2 * block + 128, qEntry = 128;
            long rootStartLba = get64(badRootAlignment[0], pEntry + 32);
            long rootEndLba = get64(badRootAlignment[0], pEntry + 40);
            put64le(badRootAlignment[0], pEntry + 32, rootStartLba + 1); put64le(badRootAlignment[0], pEntry + 40, rootEndLba + 1);
            put64le(badRootAlignment[1], qEntry + 32, rootStartLba + 1); put64le(badRootAlignment[1], qEntry + 40, rootEndLba + 1);
            refresh(badRootAlignment, block);
            rejects(() -> Uninstall.deviceLayout(badRootAlignment[0], badRootAlignment[1], sectors, "mu300root"),
                    "UFS " + block + "-byte uninstall refuses misaligned reclaim range");

            byte[][] beyondDisk = gpt(true, "mu300root", block);
            long past = sectors / factor + 10;
            put64le(beyondDisk[0], pEntry + 40, past); put64le(beyondDisk[1], qEntry + 40, past);
            refresh(beyondDisk, block);
            rejects(() -> Uninstall.deviceLayout(beyondDisk[0], beyondDisk[1], sectors, "mu300root"),
                    "UFS " + block + "-byte uninstall refuses partition beyond last usable LBA");
            rejects(() -> Uninstall.deviceLayout(adjacent[0], adjacent[1], sectors, "unknown-root"),
                    "UFS " + block + "-byte uninstall refuses unsupported root identity");
        }
        ok(Uninstall.bytes512(8) == 4096, "512-byte sector to byte conversion is exact");
        rejects(() -> { try { Uninstall.bytes512(Long.MAX_VALUE); } catch (Exception e) { throw new RuntimeException(e); } },
                "byte conversion refuses overflowing sector count");
        Uninstall.Device d = new Uninstall.Device(); d.booted = true; d.s = new Mu300Internal.Storage(); d.s.kind = "ufs"; d.s.root = "sda38";
        Uninstall.Pkg pkg = new Uninstall.Pkg(); pkg.family = "mu300";
        rejects(() -> { try { Uninstall.prepare(null, d, pkg); } catch (Exception e) { throw new RuntimeException(e); } },
                "UFS uninstall refuses missing pinned storage identity library before I/O");
        pkg.storageLibrary = "wrong library".getBytes(StandardCharsets.UTF_8);
        rejects(() -> { try { Uninstall.prepare(null, d, pkg); } catch (Exception e) { throw new RuntimeException(e); } },
                "UFS uninstall refuses a storage identity library with the wrong SHA-256");
    }

    static void lifecycle(String family, File work) throws Exception {
        String root = "e5".equals(family) ? "e5root" : "mu300root";
        byte[][] adjacent = gpt(true, root), gap = gpt(false, root);
        Uninstall.Layout a = Uninstall.deviceLayout(adjacent[0], adjacent[1], 1L << 24, root);
        Uninstall.Layout g = Uninstall.deviceLayout(gap[0], gap[1], 1L << 24, root);
        ok(a.expands && a.target.end == a.root.end, family + " adjacent root produces grow plan");
        ok(!g.expands && g.target.end == g.data.end, family + " non-adjacent root produces gap plan");
        ok(a.after[0].length == adjacent[0].length && a.after[1].length == adjacent[1].length,
                family + " preserves primary and backup GPT snapshot sizes");

        // Exercise the persisted record lifecycle without running a target worker.
        File rootDir = new File(work, "lifecycle-root-" + family);
        File dir = new File(rootDir, "record");
        Mu300Internal.deleteTree(rootDir); if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("mkdir lifecycle");
        LinkedHashMap<String,Object> r = new LinkedHashMap<>(); r.put("version", 1L); r.put("cid", "0123456789abcdef0123456789abcdef");
        r.put("family", family); r.put("complete", false); r.put("abandoned", false); r.put("stage", "uninstall_offline");
        r.put("plan", new LinkedHashMap<String,Object>()); r.put("uninstall", new LinkedHashMap<String,Object>());
        UninstallRun.Rec rec = new UninstallRun.Rec(dir, r); rec.save();
        ok(UninstallRun.pending(rootDir) != null, family + " pending record is discoverable");
        UninstallRun.Rec loaded = UninstallRun.load(dir); loaded.r.put("complete", true); loaded.stage("complete", null, null);
        ok(UninstallRun.pending(rootDir) == null, family + " completed record leaves pending queue");
        loaded.r.put("complete", false); loaded.r.put("cid", "fedcba9876543210fedcba9876543210"); loaded.save();
        ok(!loaded.s("cid").equals("0123456789abcdef0123456789abcdef"), family + " changed CID is not silently rebound");
    }
    public static void main(String[] args) throws Exception {
        File work = new File(args.length > 0 ? args[0] : "build-test/uninstall"); Mu300Internal.deleteTree(work); if (!work.mkdirs()) throw new IOException("mkdir " + work);
        ufsUninstallTests();
        String e5 = args.length > 1 ? args[1] : "/home/ubuntu/upload/e5-openwrt-25.12.5-r20261023.183851-g33f8a737-s4bbbcbec-k6.1(1).zip";
        String mu = args.length > 2 ? args[2] : "/home/ubuntu/upload/mu300-openwrt-25.12.5-r20261024.082138-g3c350105-sc498f186-k.zip";
        for (String[] x : new String[][]{{"e5",e5},{"mu300",mu}}) { bootTests(x[0],x[1]); evidence(x[0],x[1],work); helperTests(x[0]); lifecycle(x[0], work); }
        System.out.println("UninstallTest: " + passed + " passed, " + failed + " failed"); if (failed != 0) System.exit(1);
    }
}
