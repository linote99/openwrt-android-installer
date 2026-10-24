package android.hardware.usb;
/** Desktop test stub. */
public class UsbEndpoint {
    final int dir, type, mps;
    public UsbEndpoint(int dir, int type, int mps) { this.dir = dir; this.type = type; this.mps = mps; }
    public int getDirection() { return dir; }
    public int getType() { return type; }
    public int getMaxPacketSize() { return mps; }
}
