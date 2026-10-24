package android.hardware.usb;
/** Desktop test stub. */
public class UsbInterface {
    final int cls, sub, proto;
    final UsbEndpoint[] eps;
    public UsbInterface(int cls, int sub, int proto, UsbEndpoint... eps) { this.cls = cls; this.sub = sub; this.proto = proto; this.eps = eps; }
    public int getInterfaceClass() { return cls; }
    public int getInterfaceSubclass() { return sub; }
    public int getInterfaceProtocol() { return proto; }
    public int getEndpointCount() { return eps.length; }
    public UsbEndpoint getEndpoint(int i) { return eps[i]; }
}
