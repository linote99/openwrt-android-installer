package top.linote.owrtinstaller;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Runs shell text through the device's su (Magisk / KernelSU / KSUNext). */
final class RootShell {
    interface LineSink { void line(String s); }

    private static final String[] CANDIDATES = {"su", "/system/bin/su", "/system/xbin/su",
            "/debug_ramdisk/su", "/sbin/su"};
    private static volatile String su;

    private RootShell() {}

    /** Returns the working su path, or null. May trigger the root manager's grant prompt. */
    static String detect() {
        if (su != null) return su;
        for (String c : CANDIDATES) {
            if (c.startsWith("/") && !new File(c).exists()) continue;
            try {
                StringBuilder out = new StringBuilder();
                int rc = runWith(c, "id", out::append, 60);
                if (rc == 0 && out.toString().matches("(?s).*\\buid=0\\b.*")) {
                    su = c;
                    return c;
                }
            } catch (IOException ignored) {
                // try the next candidate
            }
        }
        return null;
    }

    static int run(String script, LineSink sink, long timeoutSeconds) throws IOException {
        String s = detect();
        if (s == null) throw new IOException("没有 root 权限：请在 Magisk / KernelSU 中允许本应用");
        return runWith(s, script, sink, timeoutSeconds);
    }

    private static int runWith(String suPath, String script, LineSink sink, long timeoutSeconds) throws IOException {
        Process p = new ProcessBuilder(suPath).redirectErrorStream(true).start();
        try {
            try (OutputStream o = p.getOutputStream()) {
                o.write((script + "\nexit\n").getBytes(StandardCharsets.UTF_8));
            }
            Thread killer = null;
            if (timeoutSeconds > 0) {
                final Process proc = p;
                killer = new Thread(() -> {
                    try {
                        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) proc.destroy();
                    } catch (InterruptedException ignored) { }
                });
                killer.setDaemon(true);
                killer.start();
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sink.line(line);
            }
            try {
                return p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        } finally {
            p.destroy();
        }
    }

    /** Runs shell text as root and copies its raw stdout to out (stderr discarded); returns the exit status. */
    static int readBinary(String script, OutputStream out, long timeoutSeconds) throws IOException {
        String s = detect();
        if (s == null) throw new IOException("没有 root 权限：请在 Magisk / KernelSU 中允许本应用");
        Process p = new ProcessBuilder(s).start();
        try {
            try (OutputStream o = p.getOutputStream()) {
                o.write((script + "\nexit\n").getBytes(StandardCharsets.UTF_8));
            }
            final Process proc = p;
            Thread err = new Thread(() -> {
                try (java.io.InputStream e = proc.getErrorStream()) {
                    byte[] b = new byte[4096];
                    while (e.read(b) > 0) { /* discard */ }
                } catch (IOException ignored) { }
            });
            err.setDaemon(true);
            err.start();
            if (timeoutSeconds > 0) {
                Thread killer = new Thread(() -> {
                    try { if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) proc.destroy(); }
                    catch (InterruptedException ignored) { }
                });
                killer.setDaemon(true);
                killer.start();
            }
            try (java.io.InputStream in = p.getInputStream()) {
                byte[] b = new byte[1 << 16];
                int r;
                while ((r = in.read(b)) > 0) out.write(b, 0, r);
            }
            try { return p.waitFor(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return -1; }
        } finally {
            p.destroy();
        }
    }

    /** Single-quote a value for sh. */
    static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
}
