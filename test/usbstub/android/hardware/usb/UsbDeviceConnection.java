package android.hardware.usb;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
/**
 * Desktop test stub that emulates USB bulk semantics over a TCP connection to the mock adbd and
 * checks ADB-over-USB framing on the OUT side: every message header is its own 24-byte transfer,
 * the payload follows in transfers of at most 16 KiB, and a zero-length packet follows a payload
 * whose length is a multiple of the endpoint packet size.
 *
 * The IN side behaves like adbd on a real USB link: each message header is its own 24-byte
 * transfer, the payload follows in transfers of at most 16 KiB, split into 512-byte packets, and
 * no zero-length packet is sent. A queued request completes only when it is full or a short packet
 * arrives; a packet larger than the space left in the request is an overflow (counted in
 * inErrors and reported as a failed transfer). Partly filled requests survive a requestWait
 * timeout, as in the kernel.
 */
public class UsbDeviceConnection {
    final Socket s;
    final InputStream in;
    final OutputStream out;
    UsbRequest queued;
    volatile boolean closed, unplugged;
    public boolean claimed;
    public int framingErrors, zlps, headers, transfers, maxTransfer;
    public volatile int inErrors, inPackets;
    private static final byte[] EOF = new byte[0];
    private final java.util.concurrent.LinkedBlockingQueue<byte[]> packets = new java.util.concurrent.LinkedBlockingQueue<>();
    private byte[] held;   // the next packet, not yet taken by a request
    private int payloadLeft = -1;     // -1: expecting a header
    private boolean needZlp;
    UsbDeviceConnection(UsbDevice d) throws IOException {
        s = new Socket(d.host, d.port);
        s.setTcpNoDelay(true);
        in = s.getInputStream();
        out = s.getOutputStream();
        Thread t = new Thread(this::device, "usb-in");
        t.setDaemon(true);
        t.start();
    }
    /** Splits the mock adbd's TCP stream into USB transfers and packets, as adbd sends them. */
    private void device() {
        try {
            java.io.DataInputStream d = new java.io.DataInputStream(in);
            while (true) {
                byte[] h = new byte[24];
                s.setSoTimeout(0);
                int n = in.read(h, 0, 24);
                if (n < 0) break;
                // A header that stops short (a stray half packet of an earlier session) is its own
                // short transfer on USB: emit what arrived when nothing follows within 50 ms.
                s.setSoTimeout(50);
                try {
                    while (n < 24) { int r = in.read(h, n, 24 - n); if (r < 0) throw new java.io.EOFException(); n += r; }
                } catch (java.net.SocketTimeoutException e) {
                    packets.put(java.util.Arrays.copyOf(h, n));
                    continue;
                }
                s.setSoTimeout(0);
                packets.put(h);
                int cmd = (h[0] & 0xff) | (h[1] & 0xff) << 8 | (h[2] & 0xff) << 16 | (h[3] & 0xff) << 24;
                int magic = (h[20] & 0xff) | (h[21] & 0xff) << 8 | (h[22] & 0xff) << 16 | (h[23] & 0xff) << 24;
                if ((cmd ^ magic) != -1) continue;   // not a header: no payload follows
                int len = (h[12] & 0xff) | (h[13] & 0xff) << 8 | (h[14] & 0xff) << 16 | (h[15] & 0xff) << 24;
                for (int off = 0; off < len; ) {
                    int tr = Math.min(16384, len - off);
                    for (int o = 0; o < tr; o += 512) {
                        byte[] p = new byte[Math.min(512, tr - o)];
                        d.readFully(p);
                        packets.put(p);
                    }
                    off += tr;
                }
            }
        } catch (Exception e) {
            packets.offer(EOF);
        }
    }
    public boolean claimInterface(UsbInterface f, boolean force) { claimed = true; return true; }
    public boolean releaseInterface(UsbInterface f) { claimed = false; return true; }
    public void close() { closed = true; try { s.close(); } catch (IOException ignored) { } }
    /** Test helper: simulates pulling the cable. */
    public void unplug() { unplugged = true; try { s.close(); } catch (IOException ignored) { } }

    public synchronized int bulkTransfer(UsbEndpoint ep, byte[] b, int len, int timeout) {
        if (closed || unplugged) return -1;
        if (ep.getDirection() != UsbConstants.USB_DIR_OUT) throw new IllegalArgumentException("sync IN not used");
        if (len > 16384) framingErrors++;
        transfers++;
        maxTransfer = Math.max(maxTransfer, len);
        if (len == 0) {
            if (!needZlp) framingErrors++;
            needZlp = false;
            zlps++;
            return 0;
        }
        if (needZlp) { framingErrors++; needZlp = false; }
        if (payloadLeft < 0) {
            if (len != 24) { framingErrors++; }
            else {
                headers++;
                int plen = (b[12] & 0xff) | (b[13] & 0xff) << 8 | (b[14] & 0xff) << 16 | (b[15] & 0xff) << 24;
                if (plen > 0) { payloadLeft = plen; payloadLen = plen; }
            }
        } else {
            payloadLeft -= len;
            if (payloadLeft < 0) framingErrors++;
            if (payloadLeft <= 0) {
                payloadLeft = -1;
                if (payloadLen % ep.getMaxPacketSize() == 0) needZlp = true;
            }
        }
        try { out.write(b, 0, len); out.flush(); } catch (IOException e) { return -1; }
        return len;
    }
    private int payloadLen;

    public UsbRequest requestWait() { try { return requestWait(0); } catch (TimeoutException e) { return null; } }
    public UsbRequest requestWait(long timeout) throws TimeoutException {
        UsbRequest r = queued;
        if (r == null) return null;
        if (r.cancelled || closed || unplugged) { queued = null; return closed || unplugged ? null : r; }
        long end = timeout > 0 ? System.currentTimeMillis() + timeout : Long.MAX_VALUE;
        try {
            while (true) {
                byte[] p = held;
                held = null;
                if (p == null) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) throw new TimeoutException();
                    p = timeout > 0 ? packets.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS) : packets.take();
                    if (p == null) throw new TimeoutException();
                }
                if (p == EOF) { packets.offer(EOF); queued = null; return null; }
                if (closed || unplugged) { queued = null; return null; }
                if (p.length > r.buf.remaining()) { inErrors++; queued = null; return null; }   // babble
                r.buf.put(p);
                inPackets++;
                if (p.length < 512 || !r.buf.hasRemaining()) { queued = null; return r; }
            }
        } catch (InterruptedException e) {
            queued = null;
            return null;
        }
    }
}
