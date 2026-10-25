package top.linote.owrtinstaller;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal blocking ADB host-side client speaking directly to a device's adbd over
 * TCP (classic "adb tcpip" / port 5555 wireless ADB). One connection, one stream
 * at a time; every call must come from the same worker thread.
 *
 * Not supported: Android 11+ "Wireless debugging" pairing/TLS ports (adbd answers
 * STLS there), which this client reports as a clear error.
 */
final class AdbConnection implements AutoCloseable {
    interface AuthListener { void waitingForUser(String fingerprint); }
    interface LineSink { void line(String s); }
    interface Progress { void bytes(long done); boolean cancelled(); }

    /** Byte transport under the ADB protocol: TCP socket (wireless ADB) or USB bulk endpoints. */
    interface Transport {
        InputStream in() throws IOException;
        /** Each write() call must reach the device as one transfer (header and payload are written separately). */
        OutputStream out() throws IOException;
        /** Read timeout in ms (0 = none); a timed-out read throws SocketTimeoutException. */
        void setTimeout(int ms) throws IOException;
        boolean isClosed();
        void close();
    }

    private static final class SocketTransport implements Transport {
        final Socket s;
        final InputStream in;
        final OutputStream out;
        SocketTransport(Socket s) throws IOException {
            this.s = s;
            in = new java.io.BufferedInputStream(s.getInputStream(), 1 << 16);
            out = new java.io.BufferedOutputStream(s.getOutputStream(), 1 << 16);
        }
        public InputStream in() { return in; }
        public OutputStream out() { return out; }
        public void setTimeout(int ms) throws IOException { s.setSoTimeout(ms); }
        public boolean isClosed() { return s.isClosed(); }
        public void close() { try { s.close(); } catch (IOException ignored) { } }
    }

    static final int A_SYNC = 0x434e5953, A_CNXN = 0x4e584e43, A_OPEN = 0x4e45504f, A_OKAY = 0x59414b4f,
            A_CLSE = 0x45534c43, A_WRTE = 0x45545257, A_AUTH = 0x48545541, A_STLS = 0x534c5453;
    private static final int VERSION = 0x01000001;
    private static final int MY_MAX = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final String RC_MARK = "__OWRT_ADB_RC=";

    final String host;
    final int port;
    private final Transport sock;
    private final InputStream in;
    private final OutputStream out;
    /** Longest wait for a protocol reply (OPEN/WRTE acknowledgement). */
    static int REPLY_MS = 120_000;
    /** Longest wait for adbd's status after the last bytes of a pushed file (it closes and syncs the file first). */
    static int SYNC_DONE_MS = 300_000;
    private int maxData = 4096;
    private int nextLocal = 1;
    private int curTimeout = -1;
    private volatile boolean closed;
    /** Packets of an earlier session discarded during the handshake (USB keeps them queued in the device). */
    int staleDropped;
    /** Bytes of earlier-session data drained from the USB IN endpoint before the handshake. */
    long staleBytes;
    final Map<String, String> banner = new LinkedHashMap<>();
    String bannerRaw = "";

    private static final class Packet {
        int cmd, arg0, arg1;
        byte[] data;
    }

    /** A header whose magic or length is wrong: the stream is out of step (e.g. half a packet left over). */
    static final class BadPacket extends IOException { BadPacket(String m) { super(m); } }
    /** No reply in time; the connection has been closed because its state is unknown. */
    static final class Timeout extends IOException { Timeout(String m) { super(m); } }

    private AdbConnection(String host, int port, Transport t) throws IOException {
        this.host = host;
        this.port = port;
        this.sock = t;
        this.in = t.in();
        this.out = t.out();
    }

    String endpoint() {
        if (port <= 0) return host;
        return host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
    }

    /** Parses "host", "host:port", "[v6]:port" (default port 5555). Rejects anything else. */
    static String[] parseEndpoint(String text) throws IOException {
        String t = text == null ? "" : text.trim();
        String host;
        int port = 5555;
        if (t.startsWith("[")) {
            int e = t.indexOf(']');
            if (e < 0) throw new IOException("地址格式不正确");
            host = t.substring(1, e);
            String rest = t.substring(e + 1);
            if (rest.startsWith(":")) port = parsePort(rest.substring(1));
            else if (!rest.isEmpty()) throw new IOException("地址格式不正确");
        } else {
            int c = t.lastIndexOf(':');
            if (c >= 0 && t.indexOf(':') == c) { host = t.substring(0, c); port = parsePort(t.substring(c + 1)); }
            else host = t;
        }
        if (!host.matches("[A-Za-z0-9.\\-:%_]{1,253}") || host.startsWith("-"))
            throw new IOException("地址格式不正确：" + t);
        return new String[]{host, String.valueOf(port)};
    }

    private static int parsePort(String s) throws IOException {
        try {
            int p = Integer.parseInt(s);
            if (p < 1 || p > 65535) throw new NumberFormatException();
            return p;
        } catch (NumberFormatException e) {
            throw new IOException("端口不正确：" + s);
        }
    }

    static AdbConnection connect(String host, int port, AdbKey key, String keyName, AuthListener l) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            AdbConnection c = new AdbConnection(host, port, new SocketTransport(s));
            c.handshake(key, keyName, l);
            return c;
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    /** Connects over an already opened transport (USB); label is shown as the endpoint. */
    static AdbConnection connect(Transport t, String label, AdbKey key, String keyName, AuthListener l) throws IOException {
        try {
            AdbConnection c = new AdbConnection(label, 0, t);
            c.handshake(key, keyName, l);
            return c;
        } catch (IOException e) {
            t.close();
            throw e;
        }
    }

    private void handshake(AdbKey key, String keyName, AuthListener l) throws IOException {
        // Over USB the device may still hold packets of an earlier session (one that was force-stopped or
        // whose cable was pulled) in its IN queue: read them away before saying hello.
        if (port <= 0) drain(250, 3000);
        setTimeout(10000);
        send(A_CNXN, VERSION, MY_MAX, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false, sentKey = false;
        int resent = 0;
        while (true) {
            Packet p;
            try {
                p = read(port <= 0);
            } catch (BadPacket e) {
                // USB only (TCP closes in read): out of step with a leftover half packet; flush and start over.
                if (resent >= 2) { close(); throw new IOException("USB ADB 握手失败：设备持续发送无法识别的数据，请拔插 OTG 线后重试"); }
                resent++;
                drain(250, 3000);
                setTimeout(10000);
                send(A_CNXN, VERSION, MY_MAX, "host::\0".getBytes(StandardCharsets.UTF_8));
                continue;
            } catch (SocketTimeoutException e) {
                throw new IOException(sentKey ? "等待设备授权超时：请在目标设备屏幕上点“允许”后重试"
                        : (port > 0 ? "设备没有响应 ADB 握手（端口可能不是无线 ADB）" : "设备没有响应 USB ADB 握手（请确认目标设备已开启 USB 调试）"));
            }
            if (p.cmd == A_CNXN) {
                maxData = Math.max(1024, Math.min(p.arg1, MY_MAX));
                parseBanner(p.data);
                setTimeout(0);
                return;
            } else if (p.cmd == A_STLS) {
                throw new IOException("该端口要求 Android 11+ 无线调试配对（TLS），本应用暂不支持；"
                        + "请改用经典无线 ADB（5555 端口，例如目标设备 root 后执行 setprop service.adb.tcp.port 5555 并重启 adbd）");
            } else if (p.cmd == A_AUTH && p.arg0 == AUTH_TOKEN) {
                if (!signed) {
                    signed = true;
                    send(A_AUTH, AUTH_SIGNATURE, 0, key.signToken(p.data));
                } else if (!sentKey) {
                    sentKey = true;
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload(keyName));
                    if (l != null) l.waitingForUser(key.fingerprint());
                    setTimeout(120000);
                } else {
                    throw new IOException("设备拒绝了授权");
                }
            } else if (p.cmd == A_CLSE || p.cmd == A_OKAY || p.cmd == A_WRTE || p.cmd == A_OPEN || p.cmd == A_SYNC) {
                // Stream traffic of an earlier session (e.g. CLSE of a stream it never closed): not for us.
                if (++staleDropped > 256) { close(); throw new IOException("握手时持续收到上一次会话的残留报文，请拔插 OTG 线后重试"); }
            } else {
                close();
                throw new IOException("意外的握手报文 0x" + Integer.toHexString(p.cmd));
            }
        }
    }

    /** Discards whatever arrives until the line is quiet for quietMs (at most maxMs in total). */
    private void drain(int quietMs, int maxMs) throws IOException {
        setTimeout(quietMs);
        byte[] junk = new byte[16384];
        long end = System.currentTimeMillis() + maxMs;
        try {
            while (System.currentTimeMillis() < end) {
                int r = in.read(junk, 0, junk.length);
                if (r < 0) { close(); throw new EOFException("与设备的 ADB 连接已断开"); }
                staleBytes += r;
            }
        } catch (SocketTimeoutException quiet) {
            // nothing more queued
        }
    }

    private void setTimeout(int ms) throws IOException {
        if (ms == curTimeout) return;
        sock.setTimeout(ms);
        curTimeout = ms;
    }

    private void parseBanner(byte[] d) {
        String s = new String(d, StandardCharsets.UTF_8).replace("\0", "");
        bannerRaw = s;
        int i = s.indexOf("::");
        if (i < 0) return;
        for (String kv : s.substring(i + 2).split(";")) {
            int e = kv.indexOf('=');
            if (e > 0) banner.put(kv.substring(0, e), kv.substring(e + 1));
        }
    }

    boolean isClosed() { return closed || sock.isClosed(); }

    @Override public void close() {
        closed = true;
        sock.close();
    }

    // ------------------------------------------------------------ packets
    private synchronized void send(int cmd, int a0, int a1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        long sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt((int) sum).putInt(cmd ^ 0xffffffff);
        try {
            out.write(h.array());
            if (len > 0) out.write(data);
            out.flush();
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    private Packet read() throws IOException { return read(false); }

    /** Reads the next packet, waiting at most ms (0 = forever); on timeout closes and names what was awaited. */
    private Packet read(int ms, String what) throws IOException {
        setTimeout(ms);
        try {
            return read(false);
        } catch (SocketTimeoutException e) {
            close();
            throw new Timeout("等待设备应答超时：" + what + "（" + (ms / 1000) + " 秒无响应）。连接已断开，未继续后续步骤");
        }
    }

    private Packet read(boolean lenient) throws IOException {
        byte[] h = new byte[24];
        readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Packet p = new Packet();
        p.cmd = b.getInt();
        p.arg0 = b.getInt();
        p.arg1 = b.getInt();
        int len = b.getInt();
        b.getInt(); // checksum: not required from protocol 0x01000001 peers
        int magic = b.getInt();
        if (magic != (p.cmd ^ 0xffffffff)) { if (lenient) throw new BadPacket("ADB 报文损坏"); close(); throw new IOException("ADB 报文损坏"); }
        if (len < 0 || len > 16 * 1024 * 1024) { if (lenient) throw new BadPacket("ADB 报文长度异常"); close(); throw new IOException("ADB 报文长度异常"); }
        p.data = new byte[len];
        readFully(p.data);
        return p;
    }

    private void readFully(byte[] b) throws IOException {
        int o = 0;
        while (o < b.length) {
            int r;
            try {
                r = in.read(b, o, b.length - o);
            } catch (SocketTimeoutException e) {
                throw e;
            } catch (IOException e) {
                close();
                throw e;
            }
            if (r < 0) { close(); throw new EOFException("与设备的 ADB 连接已断开"); }
            o += r;
        }
    }

    // ------------------------------------------------------------ streams
    private final class Stream {
        final int local;
        int remote;
        boolean remoteClosed;
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        final String what;

        Stream(int local, String what) { this.local = local; this.what = what; }

        byte[] next() throws IOException { return next(0); }

        /** Reads the next packet for this stream; returns data, or null when the stream closed. ms: longest silence, 0 = forever. */
        byte[] next(int ms) throws IOException {
            while (true) {
                Packet p = read(ms, what);
                if (p.arg1 != local) {
                    // Stale packet of an earlier stream: close it politely, otherwise ignore.
                    if (p.cmd == A_WRTE) send(A_CLSE, 0, p.arg0, null);
                    continue;
                }
                if (p.cmd == A_WRTE) {
                    send(A_OKAY, local, remote, null);
                    return p.data;
                }
                if (p.cmd == A_CLSE) { remoteClosed = true; return null; }
                // A_OKAY while not writing: ignore.
            }
        }

        void write(byte[] data, int off, int len) throws IOException {
            while (len > 0) {
                int n = Math.min(len, maxData);
                byte[] chunk = new byte[n];
                System.arraycopy(data, off, chunk, 0, n);
                send(A_WRTE, local, remote, chunk);
                // Flow control: wait for OKAY before the next WRTE; buffer any data arriving meanwhile.
                while (true) {
                    Packet p = read(REPLY_MS, "发送数据后等待确认（" + what + "）");
                    if (p.arg1 != local) continue;
                    if (p.cmd == A_OKAY) break;
                    if (p.cmd == A_WRTE) { pending.write(p.data, 0, p.data.length); send(A_OKAY, local, remote, null); continue; }
                    if (p.cmd == A_CLSE) { remoteClosed = true; throw new IOException("设备关闭了数据流"); }
                }
                off += n;
                len -= n;
            }
        }

        void close() {
            if (!remoteClosed) {
                try { send(A_CLSE, local, remote, null); } catch (IOException ignored) { }
            }
        }
    }

    private Stream open(String service, String what) throws IOException {
        if (isClosed()) throw new IOException("与设备的 ADB 连接已断开");
        Stream s = new Stream(nextLocal++, what);
        send(A_OPEN, s.local, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
        while (true) {
            Packet p = read(REPLY_MS, "打开 " + service.split(":")[0] + " 服务（" + what + "）");
            if (p.arg1 != s.local) continue;
            if (p.cmd == A_OKAY) { s.remote = p.arg0; return s; }
            if (p.cmd == A_CLSE) throw new IOException("设备拒绝了服务请求：" + service.split(":")[0]);
        }
    }

    /**
     * Runs a command through "shell:" (raw, no PTY) and streams its output lines.
     * Returns the command's exit status (appended marker), or -1 if unknown.
     */
    int shell(String command, LineSink sink) throws IOException { return shell(command, sink, 0); }

    /** Short label of a command for error messages. */
    static String brief(String command) {
        String c = command.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return c.length() > 80 ? c.substring(0, 77) + "…" : c;
    }

    /** silenceMs: longest wait without any output before giving up (0 = forever). */
    int shell(String command, LineSink sink, int silenceMs) throws IOException {
        // Subshell: an "exit" inside the command still reaches the marker; the newline
        // ends a trailing comment. Output without a final newline puts the marker mid-line.
        Stream s = open("shell:(" + command + "\n); echo " + RC_MARK + "$?", "执行命令 " + brief(command));
        int rc = -1;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        try {
            byte[] d;
            while ((d = s.next(silenceMs)) != null) {
                for (byte b : d) {
                    if (b == '\n') {
                        rc = emit(line, sink, rc);
                    } else if (b != '\r') {
                        line.write(b);
                    }
                }
            }
            if (line.size() > 0) rc = emit(line, sink, rc);
        } finally {
            s.close();
        }
        return rc;
    }

    private static int emit(ByteArrayOutputStream line, LineSink sink, int rc) {
        String l = new String(line.toByteArray(), StandardCharsets.UTF_8);
        line.reset();
        int m = l.lastIndexOf(RC_MARK);
        if (m >= 0 && l.substring(m + RC_MARK.length()).trim().matches("\\d{1,3}")) {
            if (m > 0 && sink != null) sink.line(l.substring(0, m));
            return Integer.parseInt(l.substring(m + RC_MARK.length()).trim());
        }
        if (sink != null) sink.line(l);
        return rc;
    }

    /** Runs a command through "exec:" (raw, binary-safe, no stdin) and copies its stdout to out. */
    long execRaw(String command, OutputStream out) throws IOException { return execRaw(command, out, 0); }

    long execRaw(String command, OutputStream out, int silenceMs) throws IOException {
        Stream s = open("exec:" + command, "读取 " + brief(command));
        long n = 0;
        try {
            byte[] d;
            while ((d = s.next(silenceMs)) != null) { out.write(d); n += d.length; }
        } finally {
            s.close();
        }
        return n;
    }

    /** adb reboot: the service may drop the link before acknowledging. */
    void reboot() {
        try {
            Stream s = open("reboot:", "重启设备");
            try { while (s.next(30000) != null) { /* drain */ } } finally { s.close(); }
        } catch (IOException ignored) {
            // the device is going down
        }
    }

    /** Runs a command and returns its whole output (for short queries). */
    String shellText(String command) throws IOException {
        StringBuilder b = new StringBuilder();
        shell(command, l -> b.append(l).append('\n'));
        return b.toString();
    }

    // ------------------------------------------------------------ sync push
    private static final int ID_SEND = 0x444e4553, ID_DATA = 0x41544144, ID_DONE = 0x454e4f44,
            ID_OKAY = 0x59414b4f, ID_FAIL = 0x4c494146, ID_QUIT = 0x54495551;
    private static final int SYNC_DATA_MAX = 64 * 1024;

    /** Pushes local files (in order) to remote paths with the given modes over one sync stream. */
    void push(File[] locals, String[] remotes, int[] modes, Progress p) throws IOException {
        Stream s = open("sync:", "推送 " + (remotes.length == 1 ? remotes[0] : remotes.length + " 个文件"));
        try {
            long done = 0;
            byte[] buf = new byte[SYNC_DATA_MAX];
            for (int i = 0; i < locals.length; i++) {
                String spec = remotes[i] + "," + (0100000 | modes[i]);
                byte[] sb = spec.getBytes(StandardCharsets.UTF_8);
                if (sb.length > 1024) throw new IOException("远程路径过长");
                ByteArrayOutputStream req = new ByteArrayOutputStream();
                req.write(syncHeader(ID_SEND, sb.length));
                req.write(sb);
                try (InputStream f = new FileInputStream(locals[i])) {
                    int r;
                    while ((r = f.read(buf)) > 0) {
                        if (p != null && p.cancelled()) throw new IOException("已取消");
                        req.write(syncHeader(ID_DATA, r));
                        req.write(buf, 0, r);
                        if (req.size() >= maxData) {
                            byte[] a = req.toByteArray();
                            int full = a.length - a.length % maxData;
                            s.write(a, 0, full);
                            req.reset();
                            req.write(a, full, a.length - full);
                        }
                        done += r;
                        if (p != null) p.bytes(done);
                    }
                }
                req.write(syncHeader(ID_DONE, (int) (System.currentTimeMillis() / 1000)));
                byte[] a = req.toByteArray();
                s.write(a, 0, a.length);
                byte[] resp;
                try {
                    resp = syncRead(s, 8, SYNC_DONE_MS);
                } catch (Timeout e) {
                    throw new Timeout("推送 " + remotes[i] + "：数据已全部发送，等待目标设备确认写入完成超时（" + SYNC_DONE_MS / 1000
                            + " 秒无响应）。连接已断开，未继续后续步骤");
                }
                ByteBuffer rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
                int id = rb.getInt(), len = rb.getInt();
                if (id == ID_FAIL) {
                    String msg = len > 0 && len < 4096 ? new String(syncRead(s, len, REPLY_MS), StandardCharsets.UTF_8) : "";
                    throw new IOException("推送 " + remotes[i] + " 失败：" + msg);
                }
                if (id != ID_OKAY) throw new IOException("推送应答异常");
            }
            byte[] q = syncHeader(ID_QUIT, 0);
            s.write(q, 0, q.length);
        } finally {
            s.close();
        }
    }

    private static byte[] syncHeader(int id, int v) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(id).putInt(v).array();
    }

    private byte[] syncRead(Stream s, int n, int ms) throws IOException {
        while (s.pending.size() < n) {
            byte[] d = s.next(ms);
            if (d == null) throw new IOException("设备提前关闭了推送通道");
            s.pending.write(d, 0, d.length);
        }
        byte[] all = s.pending.toByteArray();
        byte[] r = new byte[n];
        System.arraycopy(all, 0, r, 0, n);
        s.pending.reset();
        s.pending.write(all, n, all.length - n);
        return r;
    }
}
