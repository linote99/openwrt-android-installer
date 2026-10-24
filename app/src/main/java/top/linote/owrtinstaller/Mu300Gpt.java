package top.linote.owrtinstaller;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pure functions ported from the MU300 package's desktop/installer.py:
 * gpt_backup_layout, gpt_space, gpt_add_root, free_root_range, encrypted,
 * allocation, image_size, android_profile, env and settings. Sizes are 512-byte sectors.
 */
final class Mu300Gpt {
    private Mu300Gpt() {}

    static final long MIN_SECTORS = 4194304L;           // 2 GiB
    static final long LEGACY_ROOT_SECTOR = 27762098176L / 512;
    static final String ROOT_NAME = "mu300root";

    static final class Part {
        final int n; final long start, end; final String name;
        Part(int n, long s, long e, String name) { this.n = n; start = s; end = e; this.name = name; }
    }

    static final class Space {
        long front, tail;
        final List<Part> parts = new ArrayList<>();
        final List<Integer> empty = new ArrayList<>();
        final List<long[]> gaps = new ArrayList<>();       // {start, sectors}
        List<long[]> safeGaps;                              // after signature screening
        long largest;
        byte[] first, last;
        Part find(String name) { for (Part p : parts) if (p.name.equals(name)) return p; return null; }
        List<long[]> usableGaps() { return safeGaps != null ? safeGaps : gaps; }
    }

    private static ByteBuffer le(byte[] b) { return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); }

    private static long u32(ByteBuffer b, int p) { return b.getInt(p) & 0xffffffffL; }

    private static int crc(byte[] b, int off, int len) { return Mu300Boot.crc32(b, off, len); }

    /** header(): returns {alternate, table, tableSectors} after validation; ids filled with guid+fields. */
    private static long[] header(byte[] raw, long at, byte[][] idOut) {
        if (raw.length != 512 || !new String(raw, 0, 8, StandardCharsets.US_ASCII).equals("EFI PART"))
            throw new IllegalArgumentException("Invalid GPT header");
        ByteBuffer b = le(raw);
        long size = u32(b, 12);
        if (size < 92 || size > 512) throw new IllegalArgumentException("Invalid GPT header size");
        byte[] check = Arrays.copyOf(raw, (int) size);
        long expected = u32(b, 16);
        check[16] = check[17] = check[18] = check[19] = 0;
        if ((crc(check, 0, check.length) & 0xffffffffL) != expected) throw new IllegalArgumentException("GPT header checksum failed");
        long current = b.getLong(24), alternate = b.getLong(32);
        long table = b.getLong(72);
        long count = u32(b, 80), entrySize = u32(b, 84);
        if (current != at || count < 1 || count > 1024 || entrySize != 128) throw new IllegalArgumentException("Unsupported GPT geometry");
        idOut[0] = Arrays.copyOfRange(raw, 56, 72);
        idOut[1] = Arrays.copyOfRange(raw, 80, 92);
        return new long[]{alternate, table, (count * entrySize + 511) / 512};
    }

    /** gpt_backup_layout(): {front, tail}. */
    static long[] backupLayout(byte[] primary, byte[] backup, long sectors) {
        byte[][] ia = new byte[2][], ib = new byte[2][];
        long[] a = header(primary, 1, ia);
        long[] b = header(backup, sectors - 1, ib);
        if (a[0] != sectors - 1 || b[0] != 1 || !Arrays.equals(ia[0], ib[0]) || !Arrays.equals(ia[1], ib[1]))
            throw new IllegalArgumentException("GPT copies disagree");
        long front = a[1] + a[2], tail = b[1];
        if (!(2 <= a[1] && a[1] < front && front <= 4096 && sectors - 4096 <= tail && tail + b[2] == sectors - 1))
            throw new IllegalArgumentException("GPT backup range is invalid");
        return new long[]{front, tail};
    }

    /** gpt_space(): validate both on-disk copies and return aligned, genuinely unallocated ranges. */
    static Space space(byte[] first, byte[] last, long sectors) {
        if (first.length < 1024 || last.length < 512) throw new IllegalArgumentException("Incomplete GPT snapshot");
        byte[] primary = Arrays.copyOfRange(first, 512, 1024);
        byte[] backup = Arrays.copyOfRange(last, last.length - 512, last.length);
        long[] ft = backupLayout(primary, backup, sectors);
        long front = ft[0], tail = ft[1];
        if (first.length != front * 512 || last.length != (sectors - tail) * 512) throw new IllegalArgumentException("Incomplete GPT snapshot");
        ByteBuffer p = le(primary), q = le(backup);
        long start = p.getLong(40), end = p.getLong(48);
        if (start != q.getLong(40) || end != q.getLong(48) || !(front <= start && start <= end && end < tail))
            throw new IllegalArgumentException("GPT usable bounds disagree or overlap metadata");
        long table = p.getLong(72);
        int count = (int) u32(p, 80), entry = (int) u32(p, 84);
        int len = count * entry;
        if (table * 512 + len > first.length || len > last.length) throw new IllegalArgumentException("GPT table checksum/copy mismatch");
        byte[] data = Arrays.copyOfRange(first, (int) (table * 512), (int) (table * 512) + len);
        byte[] lastTable = Arrays.copyOfRange(last, 0, len);
        if (!Arrays.equals(data, lastTable) || (crc(data, 0, len) & 0xffffffffL) != u32(p, 88))
            throw new IllegalArgumentException("GPT table checksum/copy mismatch");
        Space s = new Space();
        s.front = front; s.tail = tail;
        for (int i = 0; i < count; i++) {
            int o = i * entry;
            boolean zero = true;
            for (int k = 0; k < 16; k++) if (data[o + k] != 0) { zero = false; break; }
            if (zero) { s.empty.add(i); continue; }
            ByteBuffer e = le(data);
            long a = e.getLong(o + 32), b = e.getLong(o + 40);
            String name = new String(data, o + 56, 72, StandardCharsets.UTF_16LE);
            int nul = name.indexOf('\0');
            if (nul >= 0) name = name.substring(0, nul);
            if (!(start <= a && a <= b && b <= end)) throw new IllegalArgumentException("GPT partition outside usable bounds: " + name);
            s.parts.add(new Part(i + 1, a, b, name));
        }
        List<Part> sorted = new ArrayList<>(s.parts);
        Collections.sort(sorted, (x, y) -> Long.compare(x.start, y.start));
        sorted.add(new Part(0, end + 1, end, ""));
        long at = start;
        for (Part part : sorted) {
            if (part.start < at) throw new IllegalArgumentException("Overlapping GPT partitions");
            long aligned = (at + 2047) / 2048 * 2048;
            long limit = (part.start / 2048) * 2048;
            if (limit > aligned) s.gaps.add(new long[]{aligned, limit - aligned});
            at = part.end + 1;
        }
        long largest = 0;
        for (long[] g : s.gaps) largest = Math.max(largest, g[1]);
        s.largest = s.empty.isEmpty() ? 0 : largest;
        return s;
    }

    /** gpt_add_root(): both GPT copies with one new Linux partition named mu300root. */
    static byte[][] addRoot(byte[] first, byte[] last, long sectors, long offset, long need, UUID unique) {
        Space info = space(first, last, sectors);
        if (info.find(ROOT_NAME) != null) throw new IllegalArgumentException("mu300root already exists");
        boolean inGap = false;
        for (long[] g : info.gaps) if (offset >= g[0] && offset + need <= g[0] + g[1]) inGap = true;
        if (info.empty.isEmpty() || need < MIN_SECTORS || offset % 2048 != 0 || need % 2048 != 0 || !inGap)
            throw new IllegalArgumentException("Requested root is not in an aligned GPT free range");
        int idx = info.empty.get(0);
        byte[] entry = new byte[128];
        System.arraycopy(guidLe(UUID.fromString("0fc63daf-8483-4772-8e79-3d69d8477de4")), 0, entry, 0, 16);
        System.arraycopy(guidLe(unique), 0, entry, 16, 16);
        ByteBuffer eb = le(entry);
        eb.putLong(32, offset); eb.putLong(40, offset + need - 1);
        byte[] nm = ROOT_NAME.getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(nm, 0, entry, 56, nm.length);
        byte[][] copies = new byte[2][];
        long primaryTable = le(first).getLong(584) * 512;
        Object[][] targets = {{first, 512, primaryTable}, {last, last.length - 512, 0L}};
        for (int c = 0; c < 2; c++) {
            byte[] raw = (byte[]) targets[c][0];
            int ho = (Integer) targets[c][1];
            int to = (int) (long) (Long) targets[c][2];
            byte[] nw = raw.clone();
            System.arraycopy(entry, 0, nw, to + idx * 128, 128);
            ByteBuffer nb = le(nw);
            int count = nb.getInt(ho + 80);
            nb.putInt(ho + 88, crc(nw, to, count * 128));
            nb.putInt(ho + 16, 0);
            int hsize = nb.getInt(ho + 12);
            nb.putInt(ho + 16, crc(nw, ho, hsize));
            copies[c] = nw;
        }
        space(copies[0], copies[1], sectors);
        return copies;
    }

    /** UUID.bytes_le (mixed-endian GUID layout). */
    static byte[] guidLe(UUID u) {
        ByteBuffer be = ByteBuffer.allocate(16);
        be.putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits());
        byte[] b = be.array();
        return new byte[]{b[3], b[2], b[1], b[0], b[5], b[4], b[7], b[6], b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]};
    }

    /** free_root_range(): start sector of the largest safe gap that fits, or -1. */
    static long freeRootRange(Space info, long need) {
        if (info.empty.isEmpty()) return -1;
        long bestN = -1, bestA = -1;
        for (long[] g : info.usableGaps()) {
            if (g[1] >= need && (g[1] > bestN || (g[1] == bestN && g[0] > bestA))) { bestN = g[1]; bestA = g[0]; }
        }
        return bestA;
    }

    /** encrypted(): true / false, or IllegalStateException when the state is unknown. */
    static boolean encrypted(String state, String type, String source, String rawMagic) {
        if ("encrypted".equals(state) || "file".equals(type) || "block".equals(type)
                || source.contains("/dm-") || source.contains("/mapper/")) return true;
        if ("unencrypted".equals(state) && ("1020f5f2".equals(rawMagic) || "53ef".equals(rawMagic))) return false;
        throw new IllegalStateException("Encryption state is unknown; refusing a preserving shrink");
    }

    /** allocation(): {userdata target sectors, root sectors}. */
    static long[] allocation(long before, long partMib) {
        long need = partMib * 2048;
        long target = Math.floorDiv(before - need, 2048) * 2048;
        if (target < MIN_SECTORS || need < MIN_SECTORS) throw new IllegalArgumentException("At least 2 GiB is required for each system");
        return new long[]{target, need};
    }

    /** image_size() on a MiB value already chosen in the UI: 2048..maximum, rounded down to 4 MiB. */
    static long imageMib(long mib, long maximum) {
        if (mib < 2048 || mib > maximum) throw new IllegalArgumentException("Choose 2048–" + maximum + " MiB");
        return mib / 4 * 4;
    }

    private static final Pattern U30 = Pattern.compile("U30.?Air", Pattern.CASE_INSENSITIVE);
    private static final Pattern F50 = Pattern.compile("MU300|F50", Pattern.CASE_INSENSITIVE);

    /** android_profile(model "ro.product.model\nro.product.device", platform). */
    static String androidProfile(String model, String platform) {
        List<String> names = new ArrayList<>();
        for (String s : model.split("\n", -1)) names.add(s.trim().toUpperCase(java.util.Locale.ROOT));
        if (names.contains("MU3351") || names.contains("V50")) {
            if (!names.contains("MU3351") || !names.contains("V50") || !"ums9620".equals(platform.trim()))
                throw new IllegalArgumentException("V50 requires product MU3351 and the UMS9620 platform");
            return "v50";
        }
        if (U30.matcher(model).find()) return "u30air";
        if (F50.matcher(model).find()) return "f50";
        throw new IllegalArgumentException("Not a supported MU300/F50/V50/U30Air: " + model.trim());
    }

    private static final Pattern SAFE = Pattern.compile("^[\\w@%+=:,./-]+$", Pattern.UNICODE_CHARACTER_CLASS);

    /** shlex.quote(). */
    static String quote(String s) {
        if (s.isEmpty()) return "''";
        if (SAFE.matcher(s).matches() && isAsciiSafe(s)) return s;
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static boolean isAsciiSafe(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c < 128 && (Character.isLetterOrDigit(c) || "_@%+=:,./-".indexOf(c) >= 0))) return false;
        }
        return true;
    }

    static String env(Map<String, Object> values) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Object> e : values.entrySet()) b.append(e.getKey()).append('=').append(quote(String.valueOf(e.getValue()))).append('\n');
        return b.toString();
    }

    /** settings() as the GUI build writes it: SSID/key/APN empty (import Android hotspot, SIM default APN). */
    static String settings(long imageMib, String mode, String kernel, boolean growFs) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("IMAGE_MIB", imageMib); v.put("DESKTOP_MODE", mode); v.put("KERNEL", kernel);
        v.put("SSID", ""); v.put("WIFI_KEY", ""); v.put("APN", ""); v.put("PWHASH", ""); v.put("GROW_FS", growFs ? 1 : 0);
        return env(v);
    }

    /** env_args(). */
    static String envArgs(Object... values) {
        StringBuilder b = new StringBuilder();
        for (Object o : values) { if (b.length() > 0) b.append(' '); b.append(quote(String.valueOf(o))); }
        return b.toString();
    }
}
