import top.linote.owrtinstaller.PackageInspector;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/** Desktop check of the APK's package parser: reads/extracts only, executes nothing from the package. */
public class DesktopInspect {
    public static void main(String[] a) throws Exception {
        File zip = new File(a[0]);
        File work = new File(a[1]);
        long t = System.currentTimeMillis();
        try (InputStream in = new BufferedInputStream(new FileInputStream(zip), 1 << 16)) {
            PackageInspector.Result r = PackageInspector.inspect(in, zip.length(), work, new PackageInspector.Progress() {
                long last = -1;
                public void onProgress(long n, long total, String e) {
                    long p = n * 10 / Math.max(1, total);
                    if (p != last) { last = p; System.out.println("progress " + p * 10 + "% " + e); }
                }
                public boolean isCancelled() { return false; }
            });
            System.out.println("OK prefix='" + r.prefix + "' listed=" + r.listedFiles + " verified=" + r.verifiedFiles
                    + " extracted=" + r.extractedFiles + " bytes=" + r.extractedBytes);
            System.out.println("version=" + r.version + "\nprofile=" + r.profile + " models=" + r.androidModels
                    + " openwrt=" + r.openwrtVersion + " kernel=" + r.kernelRelease + " build=" + r.buildTime
                    + "\nmodule=" + r.moduleName + " / " + r.moduleVersion + " ssid=" + r.defaultSsid
                    + " raw=" + r.rawBytes + " boot=" + r.bootBytes + "\nfingerprint=" + r.fingerprint
                    + "\nwarnings=" + r.warnings);
        } catch (PackageInspector.PackageException e) {
            System.out.println("REFUSED: " + e.getMessage());
            System.exit(2);
        }
        System.out.println("time " + (System.currentTimeMillis() - t) + " ms");
    }
}
