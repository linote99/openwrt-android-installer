package android.hardware.usb;
import java.nio.ByteBuffer;
/** Desktop test stub. */
public class UsbRequest {
    UsbDeviceConnection conn;
    ByteBuffer buf;
    boolean cancelled;
    public boolean initialize(UsbDeviceConnection c, UsbEndpoint ep) { conn = c; return ep.getDirection() == UsbConstants.USB_DIR_IN; }
    public boolean queue(ByteBuffer b) {
        if (conn.closed || conn.unplugged) return false;
        if (conn.queued != null) throw new IllegalStateException("request queued twice");
        buf = b; conn.queued = this; return true;
    }
    public boolean cancel() { cancelled = true; return true; }
    public void close() { }
}
