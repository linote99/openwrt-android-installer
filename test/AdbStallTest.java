package top.linote.owrtinstaller;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
/**
 * The v1.4.2 hang ("上传 … 100%" and then nothing) and the "意外的握手报文 0x45534c43" connect failure, against
 * test/mock-adbd.py started with MOCK_MODE=stale-before / stale-after / hang-done, over the stubbed USB transport.
 * Args: portStaleBefore portStaleAfter portHangDone workDir
 */
public class AdbStallTest {
    static int pass, fail;
    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("PASS: " + what); }
        else { fail++; System.out.println("FAIL: " + what); }
    }
    static UsbInterface adbIntf() {
        return new UsbInterface(0xff, 0x42, 1,
                new UsbEndpoint(UsbConstants.USB_DIR_IN, UsbConstants.USB_ENDPOINT_XFER_BULK, 512),
                new UsbEndpoint(UsbConstants.USB_DIR_OUT, UsbConstants.USB_ENDPOINT_XFER_BULK, 512));
    }
    static AdbConnection open(UsbManager um, int port, String name, AdbKey key) throws IOException {
        UsbDevice d = new UsbDevice("/dev/bus/usb/002/0" + (port % 100), name, "127.0.0.1", port, adbIntf());
        um.devices.put(d.getDeviceName(), d);
        return AdbConnection.connect(UsbAdbTransport.open(um, d), "USB · " + name, key, "owrt-test@pc", fp -> { });
    }
    static String echo(AdbConnection c) throws IOException {
        StringBuilder b = new StringBuilder();
        int rc = c.shell("echo alive", l -> b.append(l), 10000);
        return rc + ":" + b;
    }
    public static void main(String[] a) throws Exception {
        int before = Integer.parseInt(a[0]), after = Integer.parseInt(a[1]), hang = Integer.parseInt(a[2]);
        File work = new File(a[3]);
        work.mkdirs();
        AdbKey key = AdbKey.loadOrCreate(new File(work, "adbkey"));
        UsbManager um = new UsbManager();

        // 1. Leftover CLSE and half a packet already queued on the IN endpoint: drained before CNXN.
        try (AdbConnection c = open(um, before, "StaleBefore", key)) {
            check("stale-before: connected, drained " + c.staleBytes + " leftover bytes", c.staleBytes >= 24);
            check("stale-before: link works afterwards (" + echo(c) + ")", echo(c).equals("0:alive"));
        } catch (IOException e) { check("stale-before: connect (" + e.getMessage() + ")", false); }

        // 2. Leftover CLSE (0x45534c43, the packet in the user's log) and OKAY arriving after our CNXN: skipped.
        try (AdbConnection c = open(um, after, "StaleAfter", key)) {
            check("stale-after: connected, skipped " + c.staleDropped + " stale packets", c.staleDropped == 2);
            check("stale-after: link works afterwards", echo(c).equals("0:alive"));
        } catch (IOException e) { check("stale-after: connect (" + e.getMessage() + ")", false); }

        // 3. Device never answers sync DONE: push must fail with a timeout naming the step, not hang at 100%.
        AdbConnection.SYNC_DONE_MS = 2000;
        File f = new File(work, "payload.bin");
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(new byte[300 * 1024]); }
        try (AdbConnection c = open(um, hang, "HangDone", key)) {
            final long[] last = {0};
            long t0 = System.currentTimeMillis();
            String err = null;
            try {
                c.push(new File[]{f}, new String[]{"/data/local/tmp/x/payload.bin"}, new int[]{0644}, new AdbConnection.Progress() {
                    @Override public void bytes(long done) { last[0] = done; }
                    @Override public boolean cancelled() { return false; }
                });
            } catch (IOException e) { err = e.getMessage(); }
            long dt = System.currentTimeMillis() - t0;
            check("hang-done: all bytes sent before the wait (" + last[0] + ")", last[0] == f.length());
            check("hang-done: push fails in " + dt + " ms instead of hanging (" + err + ")", err != null && dt < 15000);
            check("hang-done: error says what it waited for", err != null && err.contains("数据已全部发送") && err.contains("秒无响应"));
            check("hang-done: connection closed after the timeout", c.isClosed());
        }

        // 4. A shell that prints nothing for longer than its silence limit is abandoned with a clear error.
        try (AdbConnection c = open(um, after, "Silent", key)) {
            long t0 = System.currentTimeMillis();
            String err = null;
            try { c.shell("sleep 6; echo late", l -> { }, 1500); } catch (IOException e) { err = e.getMessage(); }
            long dt = System.currentTimeMillis() - t0;
            check("shell silence: gives up in " + dt + " ms (" + err + ")", err != null && dt < 5000 && err.contains("秒无响应"));
        }
        // 5. silence 0 keeps the old behaviour: waits as long as it takes.
        try (AdbConnection c = open(um, after, "Patient", key)) {
            StringBuilder b = new StringBuilder();
            int rc = c.shell("sleep 2; echo late", l -> b.append(l), 0);
            check("shell without limit: waits for slow output", rc == 0 && b.toString().equals("late"));
        }
        System.out.println("stall result: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
