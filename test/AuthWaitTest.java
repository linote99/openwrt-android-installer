package top.linote.owrtinstaller;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The debugging-authorisation wait of the readiness ladder: polls every 2 s, survives the read
 * timeouts until the user allows (auth-slow), and gives up promptly when the user stops (auth-wait).
 * Args: mode(slow|wait) port workDir
 */
public class AuthWaitTest {
    static int pass, fail;
    static void check(String n, boolean ok) { if (ok) pass++; else fail++; System.out.println((ok ? "PASS " : "FAIL ") + n); }

    public static void main(String[] a) throws Exception {
        String mode = a[0];
        int port = Integer.parseInt(a[1]);
        AdbKey key = AdbKey.loadOrCreate(new File(a[2], "adbkey"));
        List<String> prompts = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        if (mode.equals("slow")) {
            AdbConnection.abort = () -> false;
            AdbConnection c = AdbConnection.connect("127.0.0.1", port, key, "owrt-test@pc", prompts::add);
            long dt = System.currentTimeMillis() - t0;
            check("allowed after 5 s: connected across several 2 s polls (" + dt + " ms)", dt >= 4500 && !c.isClosed());
            check("prompt shown once", prompts.size() == 1);
            StringBuilder b = new StringBuilder();
            c.shell("echo hello", l -> b.append(l), 10000);
            check("stream works after the polled handshake", b.toString().contains("hello"));
            c.close();
        } else {
            final long stopAt = t0 + 3000;
            AdbConnection.abort = () -> System.currentTimeMillis() > stopAt;
            String msg = null;
            try { AdbConnection.connect("127.0.0.1", port, key, "owrt-test@pc", prompts::add); }
            catch (AdbConnection.AuthPending e) { msg = e.getMessage(); }
            long dt = System.currentTimeMillis() - t0;
            check("never allowed + stop at 3 s -> AuthPending within 6 s (" + dt + " ms)", msg != null && dt < 6000);
            check("message says stopped", msg != null && msg.contains("已停止等待调试授权"));
            check("prompt shown once", prompts.size() == 1);
        }
        System.out.println("auth-wait(" + mode + "): " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
