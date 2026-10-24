package android.hardware.usb;
/** Desktop test stub; the "device" is a TCP endpoint of test/mock-adbd.py. */
public class UsbDevice {
    final String name, product;
    final UsbInterface[] intfs;
    public final String host;
    public final int port;
    public UsbDevice(String name, String product, String host, int port, UsbInterface... intfs) {
        this.name = name; this.product = product; this.host = host; this.port = port; this.intfs = intfs;
    }
    public String getDeviceName() { return name; }
    public int getInterfaceCount() { return intfs.length; }
    public UsbInterface getInterface(int i) { return intfs[i]; }
    public String getManufacturerName() { return "Mock"; }
    public String getProductName() { return product; }
    public int getVendorId() { return 0x18d1; }
    public int getProductId() { return 0x4ee7; }
}
