package top.linote.owrtinstaller;

/** Checks Result.targetProblem() against the package scripts' identity rules (no device involved). */
public class RuleTest {
    static int bad;
    static void expect(String profile, String model, String device, String vb, boolean ok) {
        PackageInspector.DeviceRule r = PackageInspector.rule(profile);
        PackageInspector.Result p = new PackageInspector.Result();
        p.profile = profile; p.androidModels.add(r.model); p.title = r.title; p.board = r.board; p.unlockRequired = r.unlockRequired;
        String why = p.targetProblem(model, device, vb);
        boolean pass = (why == null) == ok;
        if (!pass) bad++;
        System.out.println((pass ? "PASS " : "FAIL ") + profile + " " + model + "/" + device + "/" + vb + " -> " + (why == null ? "ok" : why));
    }
    static void mu(String model, String device, String platform, boolean ok) {
        PackageInspector.Result p = new PackageInspector.Result();
        p.layout = "mu300"; p.tfOnly = true;
        String why = p.targetProblem(model, device, "green", platform);
        boolean pass = (why == null) == ok;
        if (!pass) bad++;
        System.out.println((pass ? "PASS " : "FAIL ") + "mu300 " + model + "/" + device + "/" + platform + " -> " + (why == null ? "ok" : why));
    }
    public static void main(String[] a) {
        expect("k1", "angelcare K1", "ums9157_2h10", "orange", true);
        expect("k1", "angelcare K1", "other_board", "orange", false);
        expect("k1", "angelcare K1", "ums9157_2h10", "green", false);
        expect("yuanhang41s", "ZTE 7546N", "P720S11", "green", true);
        expect("yuanhang41s", "ZTE 7546N", "P720S12", "orange", false);
        expect("changxing60", "ZTE 7552N", "P720S20", "green", true);
        expect("changxing60", "ZTE 7552N", "P720S11", "green", false);
        expect("changxing60", "ZTE 7552N", "other_board", "orange", false);
        expect("changxing60", "ZTE 7546N", "P720S20", "green", false);
        expect("t512n", "T512N", "anything", "green", true);
        expect("t551m", "T551M", "x", "orange", true);
        expect("t551m", "T551M", "x", "green", false);
        expect("m8", "VIPME M8", "x", "orange", true);
        expect("m8", "M8", "x", "orange", false);
        expect("e5", "E5", "x", "orange", true);
        expect("e5", "ZTE 8045", "x", "orange", false);
        mu("ZTE F50", "F50", "ums9620", true);
        mu("MU300", "mu300", "x", true);
        mu("U30 Air", "x", "x", true);
        mu("V50", "MU3351", "ums9620", true);
        mu("V50", "MU3351", "ums9230", false);
        mu("E5", "e5", "ums9620", false);
        System.out.println(bad == 0 ? "rule tests: all passed" : "rule tests: " + bad + " failed");
        System.exit(bad == 0 ? 0 : 1);
    }
}
