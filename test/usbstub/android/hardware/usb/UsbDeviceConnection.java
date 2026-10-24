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
 */
public class UsbDeviceConnection {
    final Socket s;
    final InputStream in;
    final OutputStream out;
    UsbRequest queued;
    volatile boolean closed, unplugged;
    public boolean claimed;
    public int framingErrors, zlps, headers, transfers, maxTransfer;
    private int payloadLeft = -1;     // -1: expecting a header
    private boolean needZlp;
    UsbDeviceConnection(UsbDevice d) throws IOException {
        s = new Socket(d.host, d.port);
        s.setTcpNoDelay(true);
        in = s.getInputStream();
        out = s.getOutputStream();
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
        try {
            s.setSoTimeout((int) Math.min(Integer.MAX_VALUE, timeout));
            byte[] tmp = new byte[r.buf.remaining()];
            int n = in.read(tmp);
            if (n < 0) { queued = null; return null; }
            r.buf.put(tmp, 0, n);
            queued = null;
            return r;
        } catch (SocketTimeoutException e) {
            throw new TimeoutException();
        } catch (IOException e) {
            queued = null;
            return null;
        }
    }
}
