package top.linote.owrtinstaller;

import java.io.ByteArrayOutputStream;

/**
 * LZ4 "legacy" frame writer (magic 0x184C2102, then per 8 MiB of input a little-endian
 * compressed length and one LZ4 block), the framing the MU300 boot ramdisk uses.
 * Any valid LZ4 block decodes identically, so this is a compact hash-chain compressor
 * that follows the block format end rules (last 5 bytes literal, no match within the
 * last 12 bytes).
 */
final class Lz4 {
    private Lz4() {}

    static final int CHUNK = 8 << 20;
    private static final int MIN_MATCH = 4;
    private static final int LAST_LITERALS = 5;
    private static final int MF_LIMIT = 12;
    private static final int HASH_LOG = 16;
    private static final int WINDOW = 65535;
    private static final int MAX_CHAIN = 64;

    static byte[] legacy(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2 + 64);
        out.write(0x02); out.write(0x21); out.write(0x4c); out.write(0x18);
        for (int i = 0; i < data.length; i += CHUNK) {
            int n = Math.min(CHUNK, data.length - i);
            byte[] block = compressBlock(data, i, n);
            int len = block.length;
            out.write(len); out.write(len >>> 8); out.write(len >>> 16); out.write(len >>> 24);
            out.write(block, 0, block.length);
        }
        return out.toByteArray();
    }

    private static int read32(byte[] b, int p) {
        return (b[p] & 0xff) | (b[p + 1] & 0xff) << 8 | (b[p + 2] & 0xff) << 16 | (b[p + 3] & 0xff) << 24;
    }

    private static int hash(int v) { return (v * -1640531535) >>> (32 - HASH_LOG); }

    static byte[] compressBlock(byte[] src, int off, int len) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(len / 2 + 16);
        int end = off + len;
        int anchor = off;
        if (len >= MF_LIMIT + 1) {
            int[] head = new int[1 << HASH_LOG];
            java.util.Arrays.fill(head, -1);
            int[] chain = new int[len];
            int matchLimit = end - LAST_LITERALS;   // matches must end before this
            int lastStart = end - MF_LIMIT;         // last position where a match may start
            int p = off;
            while (p < lastStart) {
                int h = hash(read32(src, p));
                int cand = head[h];
                chain[p - off] = cand;
                head[h] = p;
                int bestLen = 0, bestPos = -1, tries = MAX_CHAIN;
                while (cand >= 0 && p - cand <= WINDOW && tries-- > 0) {
                    if (read32(src, cand) == read32(src, p)) {
                        int l = MIN_MATCH;
                        while (p + l < matchLimit && src[cand + l] == src[p + l]) l++;
                        if (l > bestLen) { bestLen = l; bestPos = cand; }
                    }
                    cand = chain[cand - off];
                }
                if (bestLen < MIN_MATCH) { p++; continue; }
                // extend backwards over pending literals
                while (p > anchor && bestPos > off && src[p - 1] == src[bestPos - 1]) { p--; bestPos--; bestLen++; }
                writeSequence(out, src, anchor, p - anchor, p - bestPos, bestLen);
                int next = p + bestLen;
                // index the positions inside the match so later data can refer to them
                for (int q = p + 1; q < next && q < lastStart; q++) {
                    int hq = hash(read32(src, q));
                    chain[q - off] = head[hq];
                    head[hq] = q;
                }
                p = next;
                anchor = p;
            }
        }
        // final literals-only sequence
        int lit = end - anchor;
        int token = Math.min(lit, 15) << 4;
        out.write(token);
        if (lit >= 15) writeLen(out, lit - 15);
        out.write(src, anchor, lit);
        return out.toByteArray();
    }

    private static void writeSequence(ByteArrayOutputStream out, byte[] src, int litStart, int lit, int dist, int matchLen) {
        int ml = matchLen - MIN_MATCH;
        int token = (Math.min(lit, 15) << 4) | Math.min(ml, 15);
        out.write(token);
        if (lit >= 15) writeLen(out, lit - 15);
        out.write(src, litStart, lit);
        out.write(dist & 0xff);
        out.write((dist >>> 8) & 0xff);
        if (ml >= 15) writeLen(out, ml - 15);
    }

    private static void writeLen(ByteArrayOutputStream out, int n) {
        while (n >= 255) { out.write(255); n -= 255; }
        out.write(n);
    }

    /** Reference decoder (tests and self-check after compression). */
    static byte[] decodeLegacy(byte[] in, int off, int len) {
        if (len < 4 || read32(in, off) != 0x184C2102) throw new IllegalArgumentException("not LZ4 legacy");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int p = off + 4, end = off + len;
        while (p < end) {
            if (end - p < 4) throw new IllegalArgumentException("truncated chunk header");
            int n = read32(in, p); p += 4;
            if (n == 0x184C2102) continue;
            if (n < 0 || n > end - p) throw new IllegalArgumentException("bad chunk size");
            byte[] blk = decodeBlock(in, p, n);
            out.write(blk, 0, blk.length);
            p += n;
        }
        return out.toByteArray();
    }

    static byte[] decodeBlock(byte[] in, int off, int len) {
        byte[] o = new byte[Math.max(64, len * 4)];
        int op = 0, p = off, end = off + len;
        while (true) {
            int token = in[p++] & 0xff;
            int lit = token >>> 4;
            if (lit == 15) { int b; do { b = in[p++] & 0xff; lit += b; } while (b == 255); }
            if (op + lit > o.length) o = java.util.Arrays.copyOf(o, Math.max(o.length * 2, op + lit + 64));
            System.arraycopy(in, p, o, op, lit);
            p += lit; op += lit;
            if (p >= end) break;
            int dist = (in[p] & 0xff) | (in[p + 1] & 0xff) << 8; p += 2;
            if (dist == 0 || dist > op) throw new IllegalArgumentException("bad match offset");
            int ml = token & 15;
            if (ml == 15) { int b; do { b = in[p++] & 0xff; ml += b; } while (b == 255); }
            ml += MIN_MATCH;
            if (op + ml > o.length) o = java.util.Arrays.copyOf(o, Math.max(o.length * 2, op + ml + 64));
            for (int i = 0; i < ml; i++) { o[op] = o[op - dist]; op++; }
        }
        return java.util.Arrays.copyOf(o, op);
    }
}
