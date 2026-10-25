package top.linote.owrtinstaller;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * v1.5.1: the backup/read paths of the E5 family (E5Part.backup, GPT reads, bootchain tar) and
 * MU300 (Mu300Internal.download/read, as used by its backup loop) running over the real
 * AdbConnection + UsbAdbTransport, through the USB stub with adbd's packet behaviour (no
 * zero-length packets, next WRTE only after OKAY) and test/mock-adbd.py's exec: streaming.
 * The mock target is a directory: absolute device paths are mapped into it.
 *
 * Usage: UsbBackupTest PORT WORK ROOT
 */
public class UsbBackupTest {
    static int pass, fail;
    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("PASS: " + what); } else { fail++; System.out.println("FAIL: " + what); }
    }
    static UsbInterface adbIntf() {
        return new UsbInterface(0xff, 0x42, 1,
                new UsbEndpoint(UsbConstants.USB_DIR_IN, UsbConstants.USB_ENDPOINT_XFER_BULK, 512),
                new UsbEndpoint(UsbConstants.USB_DIR_OUT, UsbConstants.USB_ENDPOINT_XFER_BULK, 512));
    }
    static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(a[0]);
        File work = new File(a[1]), root = new File(a[2]);
        work.mkdirs();
        final String R = root.getAbsolutePath();
        // the mock "device": partitions and a disk image
        File byName = new File(root, "dev/block/by-name");
        byName.mkdirs();
        new File(root, "data/local/tmp").mkdirs();
        new File(root, "data/adb/e5-bootchain").mkdirs();
        new File(root, "sys/class/block/mmcblk0").mkdirs();
        Random r = new Random(7);
        File boot = new File(byName, "boot_b");
        try (FileOutputStream o = new FileOutputStream(boot)) {
            byte[] b = new byte[1 << 20];
            for (int i = 0; i < 64; i++) { r.nextBytes(b); o.write(b); }    // 64 MiB, like boot_a/boot_b
        }
        File disk = new File(root, "dev/block/mmcblk0");
        try (FileOutputStream o = new FileOutputStream(disk)) { byte[] b = new byte[2048 * 512]; r.nextBytes(b); o.write(b); }
        Files.write(new File(root, "sys/class/block/mmcblk0/size").toPath(), "2048\n".getBytes());
        File misc = new File(byName, "misc");
        try (FileOutputStream o = new FileOutputStream(misc)) { byte[] b = new byte[1 << 20]; r.nextBytes(b); o.write(b); }
        File odd = new File(root, "data/adb/e5-bootchain/uboot_a.img");
        try (FileOutputStream o = new FileOutputStream(odd)) { byte[] b = new byte[3 * 1048576 + 333]; r.nextBytes(b); o.write(b); }

        UsbManager um = new UsbManager();
        UsbDevice dev = new UsbDevice("/dev/bus/usb/001/002", "E5", "127.0.0.1", port, adbIntf());
        um.devices.put(dev.getDeviceName(), dev);
        AdbConnection c = AdbConnection.connect(UsbAdbTransport.open(um, dev), "USB · E5",
                AdbKey.loadOrCreate(new File(work, "adbkey")), "owrt-test@pc", x -> { });
        UsbDeviceConnection uc = um.last;
        final List<String> logs = new ArrayList<>(), statuses = new ArrayList<>(), steps = new ArrayList<>();
        Mu300Internal.Io io = new Mu300Internal.Io() {
            String map(String s) {
                return s.replace("/data/local/tmp", R + "/data/local/tmp").replace("/data/adb", R + "/data/adb")
                        .replace("/dev/block/", R + "/dev/block/").replace("/sys/class/block/", R + "/sys/class/block/");
            }
            @Override public String sh(String script, boolean root) throws IOException { return sh(script, root, 600, null); }
            @Override public String sh(String script, boolean root, int silenceSec) throws IOException { return sh(script, root, silenceSec, null); }
            @Override public String sh(String script, boolean root, int silenceSec, java.util.function.Consumer<String> live) throws IOException {
                StringBuilder b = new StringBuilder();
                // the target's sh is mksh/ash (pipefail); the mock runs /bin/sh, so hand the script to bash
                int rc = c.shell("bash -c " + q(map(script)), l -> {
                    b.append(l).append('\n');
                    if (live != null && l.trim().startsWith("OWRT-")) live.accept(l);
                }, silenceSec * 1000);
                if (rc != 0) throw new IOException("rc " + rc + ": " + b.toString().trim());
                return b.toString();
            }
            @Override public void pull(String remote, File dest) throws IOException { pull(remote, dest, null); }
            @Override public void pull(String remote, File dest, java.util.function.LongConsumer received) throws IOException {
                try (FileOutputStream file = new FileOutputStream(dest)) {
                    java.io.OutputStream o = received == null ? file : new java.io.FilterOutputStream(file) {
                        long n;
                        @Override public void write(byte[] d, int off, int len) throws IOException { file.write(d, off, len); n += len; received.accept(n); }
                    };
                    c.execRaw("cat " + q(map(remote)), o, 20_000);
                }
            }
            @Override public void push(File local, String remote) { throw new AssertionError("no push in backups"); }
            @Override public void reboot() { throw new AssertionError("no reboot in backups"); }
            @Override public void log(String line) { logs.add(line); System.out.println("  log: " + line); }
            @Override public void status(String line) { statuses.add(line); }
            @Override public void step(String what, boolean writes) { steps.add(what); }
            @Override public void progress(int percent) { }
            @Override public File cacheDir() { return work; }
        };
        File out = new File(work, "backup");
        out.mkdirs();

        // E5 family: boot backup of the Linux slot (E5Part.backup, used by the two-stage installs)
        long t0 = System.currentTimeMillis();
        String err = null;
        File b1 = new File(out, "boot-b-before.img");
        try { E5Part.backup(io, "/dev/block/by-name/boot_b", b1, "第 1/3 个"); } catch (IOException e) { err = e.getMessage(); }
        long dt = System.currentTimeMillis() - t0;
        check("E5 over USB: 64 MiB boot_b backup completes in " + dt + " ms" + (err != null ? " (" + err + ")" : ""),
                err == null && dt < 120_000);
        check("E5 over USB: backup identical to the partition", b1.isFile() && Mu300Internal.hashFile(b1).equals(Mu300Internal.hashFile(boot)));
        check("E5: numbered backup line", logs.stream().anyMatch(l -> l.equals("正在备份 第 1/3 个：boot-b-before.img（64.0 MiB）")));
        check("E5: target copy progress shown", statuses.stream().anyMatch(l -> l.startsWith("目标设备读取 boot-b-before.img")));
        check("E5: hashing phases shown", statuses.contains("目标设备计算 boot-b-before.img 的校验值……")
                && statuses.contains("目标设备重读 boot-b-before.img 源数据，核对校验值……"));
        check("E5: download progress with MiB and speed", statuses.stream().anyMatch(l -> l.matches("下载 boot-b-before\\.img：[0-9.]+ MiB / 64\\.0 MiB · [0-9.]+ MiB/s"))
                && statuses.stream().anyMatch(l -> l.startsWith("下载 boot-b-before.img：64.0 MiB / 64.0 MiB")));
        check("E5: completion line with timings", logs.stream().anyMatch(l -> l.matches("  ✓ boot-b-before\\.img 64\\.0 MiB：目标设备读取与校验 [0-9.]+ 秒，传输 [0-9.]+ 秒（[0-9.]+ MiB/s），校验一致")));
        long statusesAfterBoot = statuses.size();

        // E5 GPT copies (backupGpt's reads) and the bootchain tar of unknown size
        err = null;
        try {
            Mu300Internal.download(io, "dd if=/dev/block/mmcblk0 bs=512 skip=0 count=34 2>/dev/null", new File(out, "gpt-primary.bin"), 34 * 512);
            Mu300Internal.download(io, "dd if=/dev/block/mmcblk0 bs=512 skip=" + (2048 - 33) + " count=33 2>/dev/null", new File(out, "gpt-backup.bin"), 33 * 512);
            Mu300Internal.download(io, "mkdir -p /data/adb/e5-bootchain; tar -cf - -C /data/adb e5-bootchain", new File(out, "bootchain.tar"), -1, "bootchain.tar");
        } catch (IOException e) { err = e.getMessage(); }
        byte[] d = Files.readAllBytes(disk.toPath());
        check("E5 over USB: GPT primary/backup and bootchain.tar read" + (err != null ? " (" + err + ")" : ""), err == null
                && java.util.Arrays.equals(Files.readAllBytes(new File(out, "gpt-primary.bin").toPath()), java.util.Arrays.copyOfRange(d, 0, 34 * 512))
                && java.util.Arrays.equals(Files.readAllBytes(new File(out, "gpt-backup.bin").toPath()), java.util.Arrays.copyOfRange(d, (2048 - 33) * 512, 2048 * 512))
                && new File(out, "bootchain.tar").length() > odd.length());
        check("small reads stay quiet (no progress lines for 17 KiB GPT copies)", statuses.subList((int) statusesAfterBoot, statuses.size()).stream().noneMatch(l -> l.contains("gpt-")));

        // MU300 backup item forms: misc head, GPT ranges, whole partitions with 2>/dev/null
        err = null;
        try {
            Mu300Internal.download(io, "dd if=/dev/block/by-name/misc bs=4096 count=1 2>/dev/null", new File(out, "misc-head.bin"), 4096, "misc-head.bin");
            Mu300Internal.download(io, "dd if=/dev/block/mmcblk0 bs=512 count=40 2>/dev/null", new File(out, "gpt-first.bin"), 40 * 512, "gpt-first.bin");
            Mu300Internal.download(io, "cat /dev/block/by-name/boot_b 2>/dev/null", new File(out, "boot_b.img"), boot.length(), "boot_b.img");
            byte[] one = Mu300Internal.read(io, "dd if=/dev/block/mmcblk0 bs=512 skip=1 count=1 2>/dev/null", 512);
            check("MU300 over USB: 512-byte GPT header read", java.util.Arrays.equals(one, java.util.Arrays.copyOfRange(d, 512, 1024)));
        } catch (IOException e) { err = e.getMessage(); }
        check("MU300 over USB: misc head / GPT / 64 MiB boot copies identical" + (err != null ? " (" + err + ")" : ""), err == null
                && java.util.Arrays.equals(Files.readAllBytes(new File(out, "misc-head.bin").toPath()), java.util.Arrays.copyOfRange(Files.readAllBytes(misc.toPath()), 0, 4096))
                && Mu300Internal.hashFile(new File(out, "boot_b.img")).equals(Mu300Internal.hashFile(boot)));
        check("USB: no IN overflow (inErrors=" + uc.inErrors + ")", uc.inErrors == 0);
        String[] left = new File(root, "data/local/tmp").list();
        check("target staging directories removed", left != null && left.length == 0);
        c.close();
        System.out.println("usb-backup result: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
