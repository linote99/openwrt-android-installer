package top.linote.owrtinstaller;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Java port of the MU300 package's desktop/boot_image.py (build, boot_control, desktop_init)
 * and the helpers it loads from desktop/files/boot/build-boot-image.py (cpio_record,
 * cpio_archive, bootloader_control, lz4_legacy). The package's boot-linux.img is kept
 * unchanged except for one extra ramdisk segment, the ramdisk size field and the AVB
 * footer offsets, exactly as the original computer-side installer does.
 */
final class Mu300Boot {
    private Mu300Boot() {}

    static final int PERSIST = 48 << 20;
    static final int S_IFREG = 0100000, S_IFDIR = 0040000, S_IFLNK = 0120000, S_IFCHR = 0020000;

    /** Files of desktop/files/boot the builder reads (all checked against the package manifest). */
    static final String[] ASSETS = {"original-init", "storage.sh", "storage-lib.sh", "internal-ext4.sh", "part-prepare.sh",
            "part-progress.sh", "install-part.sh", "ext4-geometry.sh", "mu300-gpt", "repair/manifest.json"};

    interface Assets { byte[] get(String rel) throws java.io.IOException; }

    static final class Entry {
        final byte[] data; final int mode;
        Entry(byte[] d, int m) { data = d; mode = m; }
    }

    static int crc32(byte[] b, int off, int len) {
        CRC32 c = new CRC32(); c.update(b, off, len); return (int) c.getValue();
    }

    static int u32le(byte[] b, int p) {
        return (b[p] & 0xff) | (b[p + 1] & 0xff) << 8 | (b[p + 2] & 0xff) << 16 | (b[p + 3] & 0xff) << 24;
    }

    static void put32le(byte[] b, int p, int v) {
        b[p] = (byte) v; b[p + 1] = (byte) (v >>> 8); b[p + 2] = (byte) (v >>> 16); b[p + 3] = (byte) (v >>> 24);
    }

    static boolean validLive(byte[] live) {
        return live != null && live.length == 32 && live[4] == 'B' && live[5] == 'C' && live[6] == 'A' && live[7] == 'B'
                && live[8] == 1 && (live[9] & 7) == 2 && crc32(live, 0, 28) == u32le(live, 28);
    }

    /** boot_image.boot_control(): one-shot Linux trial on the opposite slot. */
    static byte[] bootControl(byte[] live, String androidSlot) {
        if (!validLive(live)) throw new IllegalArgumentException("invalid live A/B boot control");
        if (!"a".equals(androidSlot) && !"b".equals(androidSlot)) throw new IllegalArgumentException("unknown Android slot");
        byte[] r = live.clone();
        String linux = "a".equals(androidSlot) ? "b" : "a";
        r[0] = '_'; r[1] = (byte) linux.charAt(0); r[2] = 0; r[3] = 0;
        if ("a".equals(androidSlot)) { r[12] = (byte) 0x9e; r[14] = (byte) 0x2f; }
        else { r[12] = (byte) 0x2f; r[14] = (byte) 0x9e; }
        put32le(r, 28, crc32(r, 0, 28));
        return r;
    }

    /** build-boot-image.bootloader_control() on 2048 zero bytes + live: (android_a, linux_b, android_b, linux_a). */
    static byte[][] bootloaderControl(byte[] bc) {
        if (!(bc[4] == 'B' && bc[5] == 'C' && bc[6] == 'A' && bc[7] == 'B'))
            throw new IllegalArgumentException("misc head has no bootloader_control magic at 0x800");
        if (crc32(bc, 0, 28) != u32le(bc, 28)) throw new IllegalArgumentException("misc bootloader_control CRC mismatch");
        return new byte[][]{withSlots(bc, 'a', 0x9f, 0x1e), withSlots(bc, 'b', 0x9e, 0x2f),
                withSlots(bc, 'b', 0x1e, 0x9f), withSlots(bc, 'a', 0x2f, 0x9e)};
    }

    private static byte[] withSlots(byte[] bc, char suffix, int a, int b) {
        byte[] x = java.util.Arrays.copyOf(bc, 32);
        x[0] = '_'; x[1] = (byte) suffix; x[2] = 0; x[3] = 0;
        x[12] = (byte) a; x[14] = (byte) b;
        put32le(x, 28, crc32(x, 0, 28));
        return x;
    }

    /** boot_image.desktop_init(): the stock TF init stays unchanged around the inserted block. */
    static byte[] desktopInit(String original) {
        String startMark = "# A TF-only image must never fall through";
        int start = original.indexOf(startMark);
        if (start < 0) throw new IllegalArgumentException("original-init: start marker missing");
        int end = original.indexOf("mkdir -p /newroot", start);
        if (end < 0) throw new IllegalArgumentException("original-init: end marker missing");
        String normal = original.substring(start, end);
        String addon = "# Desktop boot only: native MU300 container layout and explicit target.\n"
                + ". /etc/mu300-desktop-storage.sh\n"
                + "DESKTOP_MODE=$(cat /etc/mu300-desktop-root-mode)\n"
                + "if [ \"$DESKTOP_MODE\" = legacy ]; then\n"
                + normal.replace("root_target=$(cat /etc/mu300-root-target 2>/dev/null)", "root_target=auto")
                + "\nelse\n"
                + "root_target=sd\n"
                + "sd_root= rootloop= internal_root= root_mounted=0\n"
                + "mkdir -p /disk\n"
                + "case \"$DESKTOP_MODE\" in prepare|install-part) setup_usb_gadget; DESKTOP_USB_READY=1 ;; esac\n"
                + "desktop_storage_start || { restore_android; reboot -f; while :; do sleep 10; done; }\n"
                + "desktop_mount_root && root_mounted=1\n"
                + "[ \"$root_mounted\" != 1 ] || read_usb_boot_policy\n"
                + "[ \"${DESKTOP_USB_READY:-}\" = 1 ] || setup_usb_gadget\n"
                + "fi\n";
        return (original.substring(0, start) + addon + original.substring(end)).getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ cpio (newc)

    static void cpioRecord(ByteArrayOutputStream out, String name, byte[] data, int mode, int ino, int rmaj, int rmin) {
        byte[] nb = (name + "\0").getBytes(StandardCharsets.UTF_8);
        int[] fields = {ino, mode, 0, 0, 1, 0, data.length, 0, 0, rmaj, rmin, nb.length, 0};
        StringBuilder h = new StringBuilder("070701");
        for (int v : fields) h.append(String.format("%08x", v));
        byte[] hb = h.toString().getBytes(StandardCharsets.US_ASCII);
        out.write(hb, 0, hb.length);
        out.write(nb, 0, nb.length);
        int x = hb.length + nb.length;
        for (int i = 0; i < ((-x) & 3); i++) out.write(0);
        out.write(data, 0, data.length);
        for (int i = 0; i < ((-data.length) & 3); i++) out.write(0);
    }

    private static void addParents(Set<String> dirs, String name) {
        int i = name.lastIndexOf('/');
        while (i > 0) {
            String p = name.substring(0, i);
            dirs.add(p);
            i = p.lastIndexOf('/');
        }
    }

    static byte[] cpioArchive(Set<String> dirsIn, LinkedHashMap<String, Entry> files) {
        Set<String> dirs = new HashSet<>(dirsIn);
        for (String n : files.keySet()) addParents(dirs, n);
        for (String n : new ArrayList<>(dirs)) addParents(dirs, n);
        List<String> sorted = new ArrayList<>(dirs);
        Collections.sort(sorted, (x, y) -> {
            int cx = count(x), cy = count(y);
            return cx != cy ? Integer.compare(cx, cy) : x.compareTo(y);
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int ino = 1;
        for (String d : sorted) cpioRecord(out, d, new byte[0], S_IFDIR | 0755, ino++, 0, 0);
        for (Map.Entry<String, Entry> e : files.entrySet())
            cpioRecord(out, e.getKey(), e.getValue().data, e.getValue().mode, ino++, 0, 0);
        cpioRecord(out, "dev/console", new byte[0], S_IFCHR | 0600, ino++, 5, 1);
        cpioRecord(out, "TRAILER!!!", new byte[0], 0, ino, 0, 0);
        return out.toByteArray();
    }

    /** repair/manifest.json names become ramdisk paths: relative, no "." / ".." / empty parts. */
    static void safeName(String name) {
        if (name.isEmpty() || name.length() > 255 || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0
                || name.indexOf('\n') >= 0)
            throw new IllegalArgumentException("unsafe repair tool name");
        for (String part : name.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("unsafe repair tool name");
    }

    private static int count(String s) { int n = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '/') n++; return n; }

    static String sha256(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder s = new StringBuilder();
            for (byte x : d) s.append(String.format("%02x", x & 0xff));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Only the files the ramdisk receives: the build() file table, in the original order. */
    static LinkedHashMap<String, Entry> files(Assets assets, String mode, byte[] live, String prepare, String identity,
                                              Set<String> dirsOut) throws java.io.IOException {
        LinkedHashMap<String, Entry> files = new LinkedHashMap<>();
        files.put("init", new Entry(desktopInit(new String(assets.get("original-init"), StandardCharsets.UTF_8)), S_IFREG | 0755));
        files.put("etc/mu300-desktop-root-mode", new Entry((mode + "\n").getBytes(StandardCharsets.UTF_8), S_IFREG | 0644));
        files.put("etc/mu300-desktop-storage.sh", new Entry(assets.get("storage.sh"), S_IFREG | 0644));
        files.put("etc/mu300-storage.sh", new Entry(assets.get("storage-lib.sh"), S_IFREG | 0644));
        files.put("etc/mu300-internal-ext4.sh", new Entry(assets.get("internal-ext4.sh"), S_IFREG | 0644));
        for (String n : new String[]{"part-prepare.sh", "part-progress.sh", "install-part.sh", "ext4-geometry.sh"})
            files.put("etc/" + n, new Entry(assets.get(n), S_IFREG | 0644));
        files.put("usr/share/mu300-gpt", new Entry(assets.get("mu300-gpt"), S_IFREG | 0755));
        Object parsed = MiniJson.parse(new String(assets.get("repair/manifest.json"), StandardCharsets.UTF_8));
        if (!(parsed instanceof Map) || !(((Map<?, ?>) parsed).get("sha256") instanceof Map))
            throw new IllegalArgumentException("repair/manifest.json invalid");
        Map<?, ?> expected = (Map<?, ?>) ((Map<?, ?>) parsed).get("sha256");
        Set<String> dirs = dirsOut;
        Collections.addAll(dirs, "etc", "usr", "usr/bin", "usr/share", "usr/lib", "usr/lib/ucode", "lib", "repair");
        for (Map.Entry<?, ?> it : expected.entrySet()) {
            String name = String.valueOf(it.getKey());
            safeName(name);
            byte[] data = assets.get("repair/" + name);
            if (!sha256(data).equals(String.valueOf(it.getValue())))
                throw new IllegalArgumentException("repair tool checksum: " + name);
            files.put("repair/" + name, new Entry(data, S_IFREG | 0755));
            files.put(name, new Entry(data, S_IFREG | 0755));
            for (String entry : new String[]{name, "repair/" + name}) addParents(dirs, entry);
        }
        byte[] link = "ld-musl-aarch64.so.1".getBytes(StandardCharsets.US_ASCII);
        files.put("repair/lib/libc.so", new Entry(link, S_IFLNK | 0777));
        files.put("lib/libc.so", new Entry(link, S_IFLNK | 0777));
        if (prepare != null && !prepare.isEmpty())
            files.put("etc/mu300-desktop-prepare", new Entry((prepare + "\n").getBytes(StandardCharsets.UTF_8), S_IFREG | 0600));
        if (identity != null && !identity.isEmpty())
            files.put("etc/mu300-desktop-identity", new Entry((identity + "\n").getBytes(StandardCharsets.UTF_8), S_IFREG | 0600));
        byte[][] bc = bootloaderControl(live);
        String[] names = {"a", "b", "a-trial", "b-trial"};
        byte[][] order = {bc[0], bc[2], bc[3], bc[1]};   // [('a',aa),('b',ab),('a-trial',la),('b-trial',lb)]
        for (int i = 0; i < 4; i++) files.put("etc/misc-bc-slot-" + names[i] + ".bin", new Entry(order[i], S_IFREG | 0644));
        return files;
    }

    /** boot_image.build(). */
    static byte[] build(byte[] base, Assets assets, String mode, byte[] live, String androidSlot,
                        String prepare, String identity) throws java.io.IOException {
        if (!java.util.Arrays.asList("sd", "part", "data-image", "prepare", "install-part", "legacy").contains(mode))
            throw new IllegalArgumentException("unsupported desktop target");
        if (base.length != (64 << 20) || !new String(base, 0, 8, StandardCharsets.US_ASCII).equals("ANDROID!")
                || !new String(base, base.length - 64, 4, StandardCharsets.US_ASCII).equals("AVBf") || u32le(base, 40) != 4)
            throw new IllegalArgumentException("64 MiB boot v4 with AVB footer required");
        if (!validLive(live)) throw new IllegalArgumentException("invalid live A/B boot control");
        Set<String> dirs = new HashSet<>();
        LinkedHashMap<String, Entry> files = files(assets, mode, live, prepare, identity, dirs);
        long ks = u32le(base, 8) & 0xffffffffL, rs = u32le(base, 12) & 0xffffffffL;
        long offsetL = 4096 + ((ks + 4095) & ~4095L);
        if (offsetL + rs > base.length - 64) throw new IllegalArgumentException("invalid boot ramdisk bounds");
        int offset = (int) offsetL;
        byte[] seg = Lz4.legacy(cpioArchive(dirs, files));
        int ramLen = (int) rs + seg.length;
        // One output buffer (64 MiB) instead of a staging copy: keeps peak memory low on phones.
        byte[] out = new byte[base.length];
        System.arraycopy(base, 0, out, 0, 4096);
        put32le(out, 12, ramLen);
        System.arraycopy(base, 4096, out, 4096, (int) (offsetL + rs - 4096));
        int len = (int) (offsetL + rs);
        if (out.length - len < seg.length) throw new IllegalArgumentException("desktop ramdisk would overlap persistent log; nothing flashed");
        System.arraycopy(seg, 0, out, len, seg.length);
        len += seg.length;
        int padded = (len + 4095) & ~4095;
        int originalSize = padded;
        ByteBuffer be = ByteBuffer.wrap(base).order(ByteOrder.BIG_ENDIAN);
        long avbOffset = be.getLong(base.length - 44), avbSize = be.getLong(base.length - 36);
        if (avbOffset < 0 || avbSize < 0 || avbOffset + avbSize > base.length - 64) throw new IllegalArgumentException("invalid AVB bounds");
        if ((long) padded + avbSize > PERSIST) throw new IllegalArgumentException("desktop ramdisk would overlap persistent log; nothing flashed");
        java.util.Arrays.fill(out, len, out.length, (byte) 0);
        System.arraycopy(base, (int) avbOffset, out, padded, (int) avbSize);
        byte[] footer = java.util.Arrays.copyOfRange(base, base.length - 64, base.length);
        ByteBuffer fb = ByteBuffer.wrap(footer).order(ByteOrder.BIG_ENDIAN);
        fb.putLong(12, originalSize); fb.putLong(20, originalSize); fb.putLong(28, avbSize);
        System.arraycopy(footer, 0, out, base.length - 64, 64);
        return out;
    }

    /** check_boot(): kernel bytes and the Linux sentinel command line stay unchanged. */
    static void selfCheck(byte[] base, byte[] image) {
        int ks = u32le(base, 8);
        for (int i = 4096; i < 4096 + ks; i++)
            if (image[i] != base[i]) throw new IllegalStateException("Desktop boot changed kernel");
        byte[] want = "loglevel=5\0".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < want.length; i++)
            if (image[44 + i] != want[i]) throw new IllegalStateException("Desktop boot changed Linux sentinel");
        if (image.length != base.length) throw new IllegalStateException("Desktop boot size changed");
    }
}
