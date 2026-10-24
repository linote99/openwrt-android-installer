package android.hardware.usb;
import java.util.HashMap;
/** Desktop test stub. */
public class UsbManager {
    public final HashMap<String, UsbDevice> devices = new HashMap<>();
    public boolean permission = true;
    public UsbDeviceConnection last;
    public HashMap<String, UsbDevice> getDeviceList() { return new HashMap<>(devices); }
    public boolean hasPermission(UsbDevice d) { return permission; }
    public UsbDeviceConnection openDevice(UsbDevice d) {
        try { return last = new UsbDeviceConnection(d); } catch (java.io.IOException e) { return null; }
    }
}
