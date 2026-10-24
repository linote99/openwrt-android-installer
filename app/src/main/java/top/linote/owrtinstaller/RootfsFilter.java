package top.linote.owrtinstaller;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Port of public_rootfs() in the MU300 package's desktop/installer.py: the shared rootfs
 * archive minus opt/mu300/android (the build machine's modem runtime; the device's own files
 * are copied in later by mu300-vendor-from-device.sh) and minus lib/firmware except rtl_nic
 * and the regulatory database. The kept members are copied header-for-header, so the
 * device unpacks exactly the same entries the original filtered archive contains.
 * Only plain ustar/v7 members are accepted (the MU300 packages use nothing else).
 */
final class RootfsFilter {
    private RootfsFilter() {}

    interface Progress { void bytes(long in) throws IOException; }

    static final class Stats { long members, kept, dropped, tarBytes; }

    static String normalize(String raw) throws IOException {
        String n = raw;
        if (n.startsWith("./")) n = n.substring(2);
        if (n.startsWith("/")) throw new IOException("unsafe rootfs path: " + raw);
        StringBuilder b = new StringBuilder();
        for (String part : n.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) throw new IOException("unsafe rootfs path: " + raw);
            if (b.length() > 0) b.append('/');
            b.append(part);
        }
        return b.length() == 0 ? "." : b.toString();
    }

    static boolean drop(String n) {
        if (n.equals("opt/mu300/android") || n.startsWith("opt/mu300/android/")) return true;
        return n.startsWith("lib/firmware/") && !(n.startsWith("lib/firmware/rtl_nic/")
                || n.equals("lib/firmware/regulatory.db") || n.equals("lib/firmware/regulatory.db.p7s"));
    }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) { if (off == 0) return false; throw new EOFException("truncated tar"); }
            off += r;
        }
        return true;
    }

    private static String cstr(byte[] h, int off, int len) {
        int e = off;
        while (e < off + len && h[e] != 0) e++;
        return new String(h, off, e - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] h, int off, int len) throws IOException {
        if ((h[off] & 0x80) != 0) throw new IOException("unsupported tar size encoding");
        String s = cstr(h, off, len).trim();
        if (s.isEmpty()) return 0;
        try { return Long.parseLong(s, 8); } catch (NumberFormatException e) { throw new IOException("bad tar number"); }
    }

    private static void checksum(byte[] h) throws IOException {
        long want = octal(h, 148, 8);
        long sum = 0;
        for (int i = 0; i < 512; i++) sum += (i >= 148 && i < 156) ? 32 : (h[i] & 0xff);
        if (sum != want) throw new IOException("tar header checksum mismatch");
    }

    static Stats filter(InputStream gz, OutputStream outRaw, Progress progress) throws IOException {
        Stats st = new Stats();
        CountingIn counting = new CountingIn(gz);
        InputStream in = new GZIPInputStream(counting, 1 << 16);
        GZIPOutputStream out = new GZIPOutputStream(outRaw, 1 << 16) { { def.setLevel(6); } };
        byte[] h = new byte[512];
        byte[] buf = new byte[1 << 16];
        long written = 0;
        boolean ended = false;
        while (readFully(in, h)) {
            boolean zero = true;
            for (byte x : h) if (x != 0) { zero = false; break; }
            if (zero) { ended = true; break; }
            checksum(h);
            char type = (char) (h[156] == 0 ? '0' : h[156]);
            if ("01257".indexOf(type) < 0) throw new IOException("unsupported tar member type '" + type + "'");
            String name = cstr(h, 0, 100);
            String magic = new String(h, 257, 6, StandardCharsets.US_ASCII);
            if (magic.startsWith("ustar")) {
                String prefix = cstr(h, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
            }
            String n = normalize(name);
            long size = (type == '0' || type == '7') ? octal(h, 124, 12) : 0;
            long padded = (size + 511) / 512 * 512;
            st.members++;
            boolean keep = !drop(n);
            if (keep) { out.write(h); written += 512; st.kept++; } else st.dropped++;
            long left = padded;
            while (left > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) throw new EOFException("truncated tar member " + n);
                if (keep) out.write(buf, 0, r);
                left -= r;
            }
            if (keep) written += padded;
            if (progress != null) progress.bytes(counting.count);
        }
        if (!ended) throw new IOException("tar archive has no end marker");
        if (st.kept == 0) throw new IOException("rootfs archive is empty");
        // two zero blocks, then pad to a 10240-byte record like tarfile does
        byte[] zero = new byte[1024];
        out.write(zero); written += 1024;
        long rec = (10240 - written % 10240) % 10240;
        while (rec > 0) { int k = (int) Math.min(zero.length, rec); out.write(zero, 0, k); rec -= k; written += k; }
        out.finish();
        st.tarBytes = written;
        return st;
    }

    private static final class CountingIn extends java.io.FilterInputStream {
        long count;
        CountingIn(InputStream in) { super(in); }
        @Override public int read() throws IOException { int r = super.read(); if (r >= 0) count++; return r; }
        @Override public int read(byte[] b, int o, int l) throws IOException { int r = super.read(b, o, l); if (r > 0) count += r; return r; }
    }
}
