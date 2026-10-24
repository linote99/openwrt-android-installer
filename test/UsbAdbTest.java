package top.linote.owrtinstaller;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.File;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Desktop test of UsbAdbTransport + AdbConnection against test/mock-adbd.py, through the stubbed
 * android.hardware.usb classes in test/usbstub (USB bulk semantics and ADB-over-USB framing checks).
 */
public class UsbAdbTest {
    static int pass, fail;
    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("PASS: " + what); }
        else { fail++; System.out.println("FAIL: " + what); }
    }
    static String sha(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder b = new StringBuilder();
        for (byte x : md.digest(Files.readAllBytes(f.toPath()))) b.append(String.format("%02x", x));
        return b.toString();
    }
    static UsbInterface adbIntf() {
        return new UsbInterface(0xff, 0x42, 1,
                new UsbEndpoint(UsbConstants.USB_DIR_IN, UsbConstants.USB_ENDPOINT_XFER_BULK, 512),
                new UsbEndpoint(UsbConstants.USB_DIR_OUT, UsbConstants.USB_ENDPOINT_XFER_BULK, 512));
    }

    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(a[0]);
        File work = new File(a[1]), root = new File(a[2]);
        AdbKey key = AdbKey.loadOrCreate(new File(work, "adbkey"));
        UsbManager um = new UsbManager();
        UsbDevice adbDev = new UsbDevice("/dev/bus/usb/001/002", "E5", "127.0.0.1", port, adbIntf());
        UsbDevice mtpOnly = new UsbDevice("/dev/bus/usb/001/003", "MTP phone", "127.0.0.1", port,
                new UsbInterface(6, 1, 1));
        UsbDevice composite = new UsbDevice("/dev/bus/usb/001/004", "Composite", "127.0.0.1", port,
                new UsbInterface(6, 1, 1), adbIntf());
        um.devices.put(adbDev.getDeviceName(), adbDev);
        um.devices.put(mtpOnly.getDeviceName(), mtpOnly);
        um.devices.put(composite.getDeviceName(), composite);

        List<UsbAdbTransport.Candidate> l = UsbAdbTransport.list(um);
        boolean hasAdb = false, hasMtp = false, hasComp = false;
        for (UsbAdbTransport.Candidate c : l) {
            hasAdb |= c.name.equals(adbDev.getDeviceName());
            hasMtp |= c.name.equals(mtpOnly.getDeviceName());
            hasComp |= c.name.equals(composite.getDeviceName());
        }
        check("list: ADB device and composite device found, MTP-only device skipped", hasAdb && hasComp && !hasMtp && l.size() == 2);

        boolean noAdb = false;
        try { UsbAdbTransport.open(um, mtpOnly); } catch (java.io.IOException e) { noAdb = e.getMessage().contains("ADB 接口"); }
        check("open: device without ADB interface rejected", noAdb);
        um.permission = false;
        boolean noPerm = false;
        try { UsbAdbTransport.open(um, adbDev); } catch (java.io.IOException e) { noPerm = e.getMessage().contains("权限"); }
        check("open: missing permission rejected", noPerm);
        um.permission = true;

        final List<String> prompts = new ArrayList<>();
        AdbConnection c = AdbConnection.connect(UsbAdbTransport.open(um, adbDev), "USB · E5", key, "owrt-test@pc", prompts::add);
        UsbDeviceConnection uc = um.last;
        check("USB: connected + authorised", prompts.size() == 1 && "E5".equals(c.banner.get("ro.product.model")));
        check("USB: interface claimed", uc.claimed);
        List<String> lines = new ArrayList<>();
        int rc = c.shell("echo one; echo 二; printf 'no-newline'; exit 3", lines::add);
        check("USB: shell rc + lines", rc == 3 && lines.size() == 3 && lines.get(1).equals("二") && lines.get(2).equals("no-newline"));
        String big = c.shellText("i=0; while [ $i -lt 20000 ]; do echo line-$i-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx; i=$((i+1)); done");
        check("USB: large shell output", big.split("\n").length >= 20000 && big.contains("line-19999-"));

        File src = new File(work, "usbsrc");
        src.mkdirs();
        Random r = new Random(2);
        File f1 = new File(src, "big.bin"), f2 = new File(src, "exact.bin");
        try (FileOutputStream o = new FileOutputStream(f1)) { byte[] b = new byte[1 << 20]; for (int i = 0; i < 3; i++) { r.nextBytes(b); o.write(b); } o.write(b, 0, 777); }
        try (FileOutputStream o = new FileOutputStream(f2)) { byte[] b = new byte[512 * 64]; r.nextBytes(b); o.write(b); }
        final long[] last = {0};
        c.push(new File[]{f1, f2},
                new String[]{"/data/local/tmp/owrt-apk-stage/usb/big.bin", "/data/local/tmp/owrt-apk-stage/usb/exact.bin"},
                new int[]{0600, 0600},
                new AdbConnection.Progress() {
                    @Override public void bytes(long done) { last[0] = done; }
                    @Override public boolean cancelled() { return false; }
                });
        File o1 = new File(root, "data/local/tmp/owrt-apk-stage/usb/big.bin");
        File o2 = new File(root, "data/local/tmp/owrt-apk-stage/usb/exact.bin");
        check("USB: push progress reaches total", last[0] == f1.length() + f2.length());
        check("USB: pushed files identical", o1.isFile() && sha(o1).equals(sha(f1)) && o2.isFile() && sha(o2).equals(sha(f2)));
        check("USB framing: header/payload separate, <=16 KiB transfers, ZLPs where required (errors=" + uc.framingErrors + ")",
                uc.framingErrors == 0 && uc.maxTransfer <= 16384);
        check("USB framing: zero-length packets were sent (" + uc.zlps + ")", uc.zlps > 0);
        check("USB: shell after push", c.shellText("echo after").trim().equals("after"));

        // Cable pulled: the next command fails with a clear error instead of hanging.
        uc.unplug();
        String err = null;
        try { c.shellText("echo x"); } catch (java.io.IOException e) { err = e.getMessage(); }
        check("USB: unplug -> IOException (" + err + ")", err != null);
        check("USB: connection marked closed after unplug", c.isClosed());

        // A "device" that never answers the handshake: connect must time out, not hang.
        try (ServerSocket silent = new ServerSocket(0)) {
            Thread th = new Thread(() -> { try { Socket s = silent.accept(); Thread.sleep(30000); s.close(); } catch (Exception ignored) { } });
            th.setDaemon(true);
            th.start();
            UsbDevice dead = new UsbDevice("/dev/bus/usb/001/009", "Silent", "127.0.0.1", silent.getLocalPort(), adbIntf());
            long t0 = System.currentTimeMillis();
            String e2 = null;
            try { AdbConnection.connect(UsbAdbTransport.open(um, dead), "USB · Silent", key, "owrt-test@pc", prompts::add); }
            catch (java.io.IOException e) { e2 = e.getMessage(); }
            long dt = System.currentTimeMillis() - t0;
            check("USB: silent device -> timeout error in " + dt + " ms (" + e2 + ")", e2 != null && dt < 25000);
        }
        System.out.println("usb result: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
