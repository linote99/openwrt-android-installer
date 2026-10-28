package top.linote.owrtinstaller;

/** Offline regression tests for the step-4 package/device compatibility fail-closed gate. */
public final class ReadOnlyMismatchTest {
    private static int checks;

    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) throw new AssertionError("FAIL: " + name);
        System.out.println("ok   " + name);
    }

    private static PackageInspector.Result e5Package() {
        PackageInspector.Result p = new PackageInspector.Result();
        p.layout = "e5";
        p.title = "E5";
        p.androidModels.add("E5");
        p.unlockRequired = false;
        return p;
    }

    private static JobManager.Device device(String model, String platform, boolean probed) {
        JobManager.Device d = new JobManager.Device();
        d.model = model;
        d.device = "board";
        d.platform = platform;
        d.vbstate = "orange";
        d.probed = probed;
        return d;
    }

    public static void main(String[] args) {
        String unknown = JobManager.readOnlyCheckBlocker(e5Package(), device("E5", "", false));
        check(unknown != null && unknown.contains("无法确认机型") && unknown.contains("未写入任何分区"),
                "unknown/unprobed model stops before read-only preflight");

        String e5Mismatch = JobManager.readOnlyCheckBlocker(e5Package(), device("MU300", "ums9620", true));
        check(e5Mismatch != null && e5Mismatch.contains("安装包与目标设备机型不匹配")
                        && e5Mismatch.contains("未写入任何分区"),
                "E5 package on MU300 returns safe mismatch result");

        PackageInspector.Result mu = new PackageInspector.Result();
        mu.layout = "mu300";
        String muMismatch = JobManager.readOnlyCheckBlocker(mu, device("Pixel 8", "tensor", true));
        check(muMismatch != null && muMismatch.contains("MU300 安装包会拒绝")
                        && muMismatch.contains("未写入任何分区"),
                "MU300 package on unrelated model returns safe mismatch result");

        check(JobManager.readOnlyCheckBlocker(e5Package(), device("E5", "", true)) == null,
                "matching E5 model remains eligible for read-only preflight");
        check(JobManager.readOnlyCheckBlocker(mu, device("U30 Air", "", true)) == null,
                "matching MU300-family model remains eligible for read-only preflight");
        check(JobManager.readOnlyCheckBlocker(null, device("E5", "", true)) != null,
                "missing package is rejected with a safe result");

        System.out.println("ReadOnlyMismatchTest: " + checks + " passed");
    }
}
