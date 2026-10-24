package top.linote.owrtinstaller;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Desktop test of AdbConnection/AdbKey against test/mock-adbd.py. */
public class AdbTest {
    static int pass, fail;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("PASS: " + what); }
        else { fail++; System.out.println("FAIL: " + what); }
    }

    static String sha(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(Files.readAllBytes(f.toPath()));
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02x", x));
        return b.toString();
    }

    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(a[0]);
        File work = new File(a[1]);
        File root = new File(a[2]);
        AdbKey key = AdbKey.loadOrCreate(new File(work, "adbkey"));
        AdbKey again = AdbKey.loadOrCreate(new File(work, "adbkey"));
        check("key persists across loads", key.fingerprint().equals(again.fingerprint()));

        String[] e = AdbConnection.parseEndpoint("192.168.0.1");
        check("endpoint default port", e[0].equals("192.168.0.1") && e[1].equals("5555"));
        e = AdbConnection.parseEndpoint("[fe80::1]:5556");
        check("endpoint ipv6", e[0].equals("fe80::1") && e[1].equals("5556"));
        boolean bad = false;
        try { AdbConnection.parseEndpoint("host:99999"); } catch (java.io.IOException x) { bad = true; }
        check("endpoint rejects bad port", bad);

        final List<String> prompts = new ArrayList<>();
        AdbConnection c = AdbConnection.connect("127.0.0.1", port, key, "owrt-test@pc", prompts::add);
        check("connected + authorised (prompt shown once)", prompts.size() == 1 && prompts.get(0).equals(key.fingerprint()));
        check("banner parsed", "E5".equals(c.banner.get("ro.product.model")));

        final List<String> lines = new ArrayList<>();
        int rc = c.shell("echo one; echo 二; printf 'no-newline'; exit 3", lines::add);
        check("shell rc 3", rc == 3);
        check("shell lines", lines.size() == 3 && lines.get(0).equals("one") && lines.get(1).equals("二") && lines.get(2).equals("no-newline"));
        String big = c.shellText("i=0; while [ $i -lt 20000 ]; do echo line-$i-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx; i=$((i+1)); done");
        check("large shell output (multi-WRTE)", big.split("\n").length >= 20000 && big.contains("line-19999-"));

        File src = new File(work, "src");
        src.mkdirs();
        Random r = new Random(1);
        File f1 = new File(src, "big.bin"), f2 = new File(src, "empty"), f3 = new File(src, "odd.bin");
        try (FileOutputStream o = new FileOutputStream(f1)) { byte[] b = new byte[1 << 20]; for (int i = 0; i < 5; i++) { r.nextBytes(b); o.write(b); } o.write(b, 0, 12345); }
        new FileOutputStream(f2).close();
        try (FileOutputStream o = new FileOutputStream(f3)) { byte[] b = new byte[65537]; r.nextBytes(b); o.write(b); }
        final long[] last = {0};
        c.push(new File[]{f1, f2, f3},
                new String[]{"/data/local/tmp/owrt-apk-stage/pkg/files/big.bin", "/data/local/tmp/owrt-apk-stage/pkg/empty",
                        "/data/local/tmp/owrt-apk-stage/owrt-apk.sh"},
                new int[]{0600, 0600, 0700},
                new AdbConnection.Progress() {
                    @Override public void bytes(long done) { last[0] = done; }
                    @Override public boolean cancelled() { return false; }
                });
        long total = f1.length() + f2.length() + f3.length();
        check("push progress reaches total", last[0] == total);
        File o1 = new File(root, "data/local/tmp/owrt-apk-stage/pkg/files/big.bin");
        File o2 = new File(root, "data/local/tmp/owrt-apk-stage/pkg/empty");
        File o3 = new File(root, "data/local/tmp/owrt-apk-stage/owrt-apk.sh");
        check("pushed big file identical", o1.isFile() && sha(o1).equals(sha(f1)));
        check("pushed empty file", o2.isFile() && o2.length() == 0);
        check("pushed odd-size file identical", o3.isFile() && sha(o3).equals(sha(f3)));
        check("pushed mode 0700", o3.canExecute() && !o2.canExecute());
        check("shell still works after push", c.shellText("echo after").trim().equals("after"));
        c.close();
        check("closed", c.isClosed());

        // Second connection with the same key: the mock asks again (no stored keys), prompt again.
        AdbConnection c2 = AdbConnection.connect("127.0.0.1", port, key, "owrt-test@pc", prompts::add);
        check("reconnect", c2.shellText("echo ok").trim().equals("ok"));
        c2.close();
        System.out.println("adb result: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
