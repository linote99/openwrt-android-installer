package top.linote.owrtinstaller;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Wired ADB over USB OTG: this phone is the USB host, the target device (USB debugging on)
 * is the ADB device. Uses the ADB interface (class 0xff, subclass 0x42, protocol 1) and its
 * two bulk endpoints. Like the official adb host, every write() is its own bulk transfer,
 * followed by a zero-length packet when the length is a multiple of the endpoint packet size.
 */
final class UsbAdbTransport implements AdbConnection.Transport {
    static final int ADB_CLASS = 0xff, ADB_SUBCLASS = 0x42, ADB_PROTOCOL = 1;
    private static final int CHUNK = 16384;          // bulkTransfer limit on older Android

    /** An attached USB device that offers an ADB interface. */
    static final class Candidate {
        final UsbDevice dev;
        final String name;   // UsbDevice.getDeviceName(), e.g. /dev/bus/usb/001/002
        final String label;  // manufacturer + product, for display
        Candidate(UsbDevice dev, String label) { this.dev = dev; this.name = dev.getDeviceName(); this.label = label; }
    }

    static UsbInterface findAdbInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == ADB_CLASS && f.getInterfaceSubclass() == ADB_SUBCLASS
                    && f.getInterfaceProtocol() == ADB_PROTOCOL && f.getEndpointCount() == 2) return f;
        }
        return null;
    }

    static List<Candidate> list(UsbManager um) {
        List<Candidate> out = new ArrayList<>();
        if (um == null) return out;
        for (UsbDevice d : um.getDeviceList().values()) {
            if (findAdbInterface(d) == null) continue;
            String label = "";
            try {
                String m = d.getManufacturerName(), p = d.getProductName();
                label = ((m == null ? "" : m + " ") + (p == null ? "" : p)).trim();
            } catch (RuntimeException ignored) { }
            if (label.isEmpty()) label = String.format("USB %04x:%04x", d.getVendorId(), d.getProductId());
            out.add(new Candidate(d, label));
        }
        return out;
    }

    private final UsbDeviceConnection conn;
    private final UsbInterface intf;
    private final UsbEndpoint epIn, epOut;
    private final UsbRequest inReq;
    private final ByteBuffer inBuf = ByteBuffer.allocate(CHUNK);
    private volatile boolean closed;
    private volatile int timeoutMs;
    final String deviceName;

    private UsbAdbTransport(UsbDeviceConnection c, UsbInterface f, UsbEndpoint in, UsbEndpoint out, String name) throws IOException {
        conn = c;
        intf = f;
        epIn = in;
        epOut = out;
        deviceName = name;
        inReq = new UsbRequest();
        if (!inReq.initialize(conn, epIn)) throw new IOException("无法初始化 USB 读取请求");
    }

    /** Opens the device; the caller must already hold USB permission for it. */
    static UsbAdbTransport open(UsbManager um, UsbDevice d) throws IOException {
        UsbInterface f = findAdbInterface(d);
        if (f == null) throw new IOException("该 USB 设备没有 ADB 接口：请在目标设备上开启 USB 调试");
        UsbEndpoint in = null, out = null;
        for (int i = 0; i < f.getEndpointCount(); i++) {
            UsbEndpoint e = f.getEndpoint(i);
            if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (e.getDirection() == UsbConstants.USB_DIR_IN) in = e; else out = e;
        }
        if (in == null || out == null) throw new IOException("ADB 接口缺少 bulk 端点");
        if (!um.hasPermission(d)) throw new IOException("没有该 USB 设备的访问权限");
        UsbDeviceConnection c = um.openDevice(d);
        if (c == null) throw new IOException("无法打开 USB 设备");
        if (!c.claimInterface(f, true)) { c.close(); throw new IOException("无法占用 USB ADB 接口（可能被其他应用占用）"); }
        try {
            return new UsbAdbTransport(c, f, in, out, d.getDeviceName());
        } catch (IOException e) {
            c.releaseInterface(f);
            c.close();
            throw e;
        }
    }

    private final InputStream input = new InputStream() {
        private final byte[] buf = new byte[CHUNK];
        private int pos, lim;
        private boolean queued;

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int r = read(one, 0, 1);
            return r < 0 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (pos >= lim) fill(len);
            int n = Math.min(len, lim - pos);
            System.arraycopy(buf, pos, b, off, n);
            pos += n;
            return n;
        }

        /**
         * Receives at most want bytes. The request must not be larger than what the caller needs:
         * a bulk IN request only completes when it is full or a short packet arrives, and adbd
         * sends neither a zero-length packet after a payload that is a multiple of the packet size
         * nor its next packet before our OKAY. A 16 KiB request for a 4096-byte payload therefore
         * waited forever (seen as a backup stuck on boot_a.img). AdbConnection reads exactly the
         * 24-byte header and then exactly the payload, like the desktop adb's USB backends.
         */
        private void fill(int want) throws IOException {
            if (closed) throw new IOException("USB 连接已断开");
            if (!queued) {
                inBuf.clear();
                inBuf.limit(Math.min(want, CHUNK));
                if (!inReq.queue(inBuf)) { close(); throw new IOException("USB 读取失败（设备可能已拔出）"); }
                queued = true;
            }
            UsbRequest r;
            try {
                int t = timeoutMs;
                r = t > 0 ? conn.requestWait(t) : conn.requestWait();
            } catch (TimeoutException e) {
                throw new SocketTimeoutException("USB 读取超时");   // request stays queued
            } catch (RuntimeException e) {
                close();
                throw new IOException("USB 读取失败（设备可能已拔出）");
            }
            if (r == null || closed) { close(); throw new IOException("USB 连接已断开（设备可能已拔出）"); }
            if (r != inReq) return;   // not ours (no other requests are queued); keep waiting
            queued = false;
            inBuf.flip();
            lim = inBuf.remaining();  // 0 for a zero-length packet: read again
            inBuf.get(buf, 0, lim);
            pos = 0;
        }
    };

    private final OutputStream output = new OutputStream() {
        @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (closed) throw new IOException("USB 连接已断开");
            int done = 0;
            while (done < len) {
                int n = Math.min(CHUNK, len - done);
                byte[] chunk = off + done == 0 && n == b.length ? b : java.util.Arrays.copyOfRange(b, off + done, off + done + n);
                int w = conn.bulkTransfer(epOut, chunk, n, 30000);
                if (w != n) { close(); throw new IOException("USB 写入失败（设备可能已拔出）"); }
                done += n;
            }
            int mps = epOut.getMaxPacketSize();
            if (len > 0 && mps > 0 && len % mps == 0 && conn.bulkTransfer(epOut, new byte[0], 0, 5000) < 0) {
                close();
                throw new IOException("USB 写入失败（设备可能已拔出）");
            }
        }
    };

    @Override public InputStream in() { return input; }
    @Override public OutputStream out() { return output; }
    @Override public void setTimeout(int ms) { timeoutMs = Math.max(0, ms); }
    @Override public boolean isClosed() { return closed; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { inReq.cancel(); } catch (RuntimeException ignored) { }
        try { conn.releaseInterface(intf); } catch (RuntimeException ignored) { }
        try { conn.close(); } catch (RuntimeException ignored) { }
    }
}
