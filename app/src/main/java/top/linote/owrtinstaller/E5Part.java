package top.linote.owrtinstaller;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * E5-family internal partition installs that the desktop installer runs from
 * scripts/installer/part-two-stage.py (with boot-slots.py for the boot image):
 *
 *  - two-stage install with userdata formatted (always used when userdata is encrypted):
 *    start() backs up boot/GPT/misc/boot control, writes a boot image whose ramdisk carries the
 *    e5.part_prepare request, arms the Linux slot and reboots; the package's initramfs carves e5root
 *    and hands over to stock recovery, which formats userdata. After Android setup the user
 *    reconnects and resume() writes OpenWrt into the prepared partition (finish()).
 *  - reinstall(): rewrite an existing e5root, keeping userdata and the partition table.
 *
 * The transaction (transaction.json and the backups) lives on the controlling phone, as it lives on
 * the PC for the desktop installer: it has to survive the factory reset of the target.
 * Device steps that need root on the target run through owrt-apk.sh e5-tool (userdata-lib.sh tool).
 */
final class E5Part {
    private E5Part() { }

    static final String P = JobManager.BASE + "/pkg";
    static final String TMP = "/data/local/tmp";
    static final int PERSIST = 56 << 20;
    static final Pattern NODE = Pattern.compile("mmcblk\\dp\\d+");
    static final Pattern CID = Pattern.compile("[0-9a-f]{32}");
    static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");
    static final Pattern OPTION = Pattern.compile(
            "e5\\.(?:root=part|part_prepare=[0-9a-f]{32}:[0-9a-f]{32}:mmcblk[0-9]p[0-9]+:(?:[0-9]+:){4}[0-9a-f]{64})");
    static final String[] PHASES = {"prepared", "rearming", "awaiting-android", "writing", "installed", "complete"};
    private static final SecureRandom RNG = new SecureRandom();

    /** Mu300Internal.Outcome is shared so the UI handles both families the same way. */
    static Mu300Internal.Outcome outcome(boolean pending, String message, String next) {
        return new Mu300Internal.Outcome(pending, message, next);
    }

    // ================================================================== boot-slots.py
    static int u32(byte[] b, int p) { return Mu300Boot.u32le(b, p); }

    /** boot-slots.py validate_control: returns the Android slot ("a" / "b"). */
    static String validateControl(byte[] d, String androidSlot) {
        if (d == null || d.length != 32 || d[4] != 'B' || d[5] != 'C' || d[6] != 'A' || d[7] != 'B' || d[8] != 1
                || (d[9] & 7) != 2 || Mu300Boot.crc32(d, 0, 28) != u32(d, 28))
            throw new IllegalArgumentException("invalid BCAB format/version/CRC");
        if (androidSlot == null) {
            if (d[0] == '_' && d[1] == 'a') androidSlot = "a";
            else if (d[0] == '_' && d[1] == 'b') androidSlot = "b";
            else throw new IllegalArgumentException("unknown Android slot");
        }
        int index = "a".equals(androidSlot) ? 12 : 14, other = "a".equals(androidSlot) ? 14 : 12;
        if ((d[index] & 0x80) == 0 || (d[index] & 15) < 2 || (d[13] & 1) != 0 || (d[15] & 1) != 0)
            throw new IllegalArgumentException("selected Android slot is not successful/bootable or has a verity error");
        if ((d[other] & 15) > (d[index] & 15))
            throw new IllegalArgumentException("other slot has higher priority: finish or cancel pending Android OTA first");
        return androidSlot;
    }

    private static byte[] block(byte[] control, String suffix, int index, int other, int android, int target) {
        byte[] r = control.clone();
        r[0] = '_'; r[1] = (byte) suffix.charAt(0); r[2] = 0; r[3] = 0;
        r[index] = (byte) android;
        r[other] = (byte) target;
        Mu300Boot.put32le(r, 28, Mu300Boot.crc32(r, 0, 28));
        return r;
    }

    /** The early files bind_boot puts in the ramdisk (boot-slots.py role_files, filtered). */
    static LinkedHashMap<String, byte[]> earlyFiles(byte[] control, String androidSlot) {
        validateControl(control, androidSlot);
        String linux = "a".equals(androidSlot) ? "b" : "a";
        int index = "a".equals(androidSlot) ? 12 : 14, other = "a".equals(androidSlot) ? 14 : 12;
        int priority = control[index] & 15;
        byte[] fallback = block(control, androidSlot, index, other, 0x90 | priority, 0);
        byte[] trial = block(control, linux, index, other, 0x90 | (priority - 1), 0x2f);
        LinkedHashMap<String, byte[]> m = new LinkedHashMap<>();
        m.put("etc/e5/linux-slot", (linux + "\n").getBytes(StandardCharsets.US_ASCII));
        m.put("etc/misc-bc-slot-a.bin", fallback);
        m.put("etc/misc-bc-slot-b-trial.bin", trial);
        return m;
    }

    /** boot-slots.py role_ramdisk: newc cpio (fixed etc, etc/e5, etc/e5linux dirs) as one literal LZ4-legacy block. */
    static byte[] roleRamdisk(LinkedHashMap<String, byte[]> files) {
        // sorted by (count('/'), name)
        String[] dirs = {"etc", "etc/e5", "etc/e5linux"};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int ino = 1;
        for (String d : dirs) Mu300Boot.cpioRecord(out, d, new byte[0], 040755, ino++, 0, 0);
        for (Map.Entry<String, byte[]> e : files.entrySet()) Mu300Boot.cpioRecord(out, e.getKey(), e.getValue(), 0100644, ino++, 0, 0);
        Mu300Boot.cpioRecord(out, "TRAILER!!!", new byte[0], 0, ino, 0, 0);
        byte[] raw = out.toByteArray();
        int length = raw.length;
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        z.write(0x02); z.write(0x21); z.write(0x4c); z.write(0x18);
        ByteArrayOutputStream blk = new ByteArrayOutputStream();
        blk.write(Math.min(length, 15) << 4);
        if (length >= 15) {
            int extra = length - 15;
            for (int i = 0; i < extra / 255; i++) blk.write(0xff);
            blk.write(extra % 255);
        }
        blk.write(raw, 0, raw.length);
        byte[] b = blk.toByteArray();
        byte[] len = new byte[4];
        Mu300Boot.put32le(len, 0, b.length);
        z.write(len, 0, 4);
        z.write(b, 0, b.length);
        return z.toByteArray();
    }

    /** boot-slots.py append_ramdisk: append a ramdisk to a v4 boot image and rewrite its AVB footer. */
    static byte[] appendRamdisk(byte[] image, LinkedHashMap<String, byte[]> files) {
        int footer = image.length - 64;
        if (image.length < 4096 || !new String(image, 0, 8, StandardCharsets.ISO_8859_1).equals("ANDROID!")
                || !new String(image, footer, 4, StandardCharsets.ISO_8859_1).equals("AVBf") || u32(image, 40) != 4)
            throw new IllegalArgumentException("expected boot v4 with original AVB footer");
        long avbOffset = be64(image, footer + 20), avbSize = be64(image, footer + 28);
        long kernel = u32(image, 8) & 0xffffffffL, ramdisk = u32(image, 12) & 0xffffffffL;
        long kernelPad = (kernel + 4095) / 4096 * 4096;
        long offset = 4096 + kernelPad;
        if (ramdisk == 0 || avbOffset < 0 || avbSize < 0 || offset + ramdisk > avbOffset || avbOffset + avbSize > footer)
            throw new IllegalArgumentException("invalid boot payload bounds");
        byte[] extra = roleRamdisk(files);
        int newRamdisk = (int) (ramdisk + extra.length);
        int body = (int) (offset + (newRamdisk + 4095L) / 4096 * 4096);
        int payloadEnd = (int) ((body + 4095L) / 4096 * 4096);
        if (payloadEnd + avbSize > Math.min(PERSIST, footer))
            throw new IllegalArgumentException("slot metadata would overlap persistent log; nothing written");
        byte[] out = new byte[image.length];
        System.arraycopy(image, 0, out, 0, (int) (offset + ramdisk));
        System.arraycopy(extra, 0, out, (int) (offset + ramdisk), extra.length);
        Mu300Boot.put32le(out, 12, newRamdisk);
        System.arraycopy(image, (int) avbOffset, out, payloadEnd, (int) avbSize);
        System.arraycopy(image, footer, out, footer, 64);
        putBe64(out, footer + 12, payloadEnd);
        putBe64(out, footer + 20, payloadEnd);
        putBe64(out, footer + 28, avbSize);
        return out;
    }

    static long be64(byte[] b, int p) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = v << 8 | (b[p + i] & 0xff);
        return v;
    }
    static void putBe64(byte[] b, int p, long v) {
        for (int i = 7; i >= 0; i--) { b[p + i] = (byte) v; v >>>= 8; }
    }

    /** boot-slots.py bind_boot: early slot-role files for this device's current boot control. */
    static byte[] bindBoot(byte[] image, byte[] control, String androidSlot, long slotRolesVersion) {
        if ("b".equals(androidSlot) && slotRolesVersion < 1)
            throw new IllegalArgumentException("this package cannot install while Android runs from slot B");
        return appendRamdisk(image, earlyFiles(control, androidSlot));
    }

    /** part-two-stage.py boot_command. */
    static byte[] bootCommand(byte[] image, String option, byte[] bootArgs) {
        if (!OPTION.matcher(option).matches()) throw new IllegalArgumentException("invalid partition boot request");
        if (image.length < 1584 || !new String(image, 0, 8, StandardCharsets.US_ASCII).equals("ANDROID!") || u32(image, 40) != 4)
            throw new IllegalArgumentException("expected Android boot header v4");
        int end = 44;
        while (end < 1580 && image[end] != 0) end++;
        String command = new String(image, 44, end - 44, StandardCharsets.US_ASCII);
        List<String> words = Arrays.asList(command.trim().split("\\s+"));
        if (!words.contains("e5.part_v=2"))
            throw new IllegalArgumentException("boot image lacks two-stage installer v2; rebuild the package first");
        for (String w : words)
            if (w.startsWith("e5.part_prepare=") || w.startsWith("e5.root="))
                throw new IllegalArgumentException("boot already has a partition installation override");
        byte[] updated = (command + " " + option).getBytes(StandardCharsets.US_ASCII);
        if (updated.length >= 1536) throw new IllegalArgumentException("boot command line is full");
        if (bootArgs == null || bootArgs.length == 0)
            throw new IllegalArgumentException("Package is missing the embedded boot request reader; nothing written");
        byte[] result = image.clone();
        Arrays.fill(result, 44, 1580, (byte) 0);
        System.arraycopy(updated, 0, result, 44, updated.length);
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        files.put("etc/boot-args.sh", bootArgs);
        files.put("etc/e5/boot-request", (option + "\n").getBytes(StandardCharsets.US_ASCII));
        return appendRamdisk(result, files);
    }

    // ================================================================== part-two-stage.py helpers
    /** recovery_message_kind. */
    static String recoveryMessageKind(byte[] raw) {
        if (raw == null || raw.length != 2048) throw new IllegalArgumentException("Incomplete misc recovery message / misc 恢复消息读取不完整");
        boolean empty = true;
        for (byte b : raw) if (b != 0) { empty = false; break; }
        if (empty) return "empty";
        byte[] want = new byte[2048];
        byte[] s = "bootonce-bootloader".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(s, 0, want, 0, s.length);
        if (Arrays.equals(raw, want)) return "bootloader-only";
        throw new IllegalArgumentException("misc 中存在恢复、升级请求或未知内容，请先处理后再安装 / An existing recovery/update request or unknown misc content is present");
    }

    static String clearBootloaderCommandScript(byte[] raw, byte[] control) {
        if (!"bootloader-only".equals(recoveryMessageKind(raw)) || control.length != 32)
            throw new IllegalArgumentException("Invalid bootloader-command clearance evidence");
        String before = sha(raw), empty = sha(new byte[2048]), bc = sha(control);
        return "set -e; "
                + "bcb_hash() { dd if=/dev/block/by-name/misc bs=2048 count=1 2>/dev/null | sha256sum | cut -d \" \" -f1; }; "
                + "bc_hash() { dd if=/dev/block/by-name/misc bs=1 skip=2048 count=32 2>/dev/null | sha256sum | cut -d \" \" -f1; }; "
                + "[ \"$(bcb_hash)\" = " + before + " ] && [ \"$(bc_hash)\" = " + bc + " ] || "
                + "{ echo \"misc changed since backup; nothing cleared\" >&2; exit 1; }; "
                + "dd if=/dev/zero of=/dev/block/by-name/misc bs=32 count=1 conv=notrunc,fsync; "
                + "[ \"$(bcb_hash)\" = " + empty + " ] && [ \"$(bc_hash)\" = " + bc + " ] || "
                + "{ echo \"misc command clearance readback failed; do not reboot\" >&2; exit 1; }";
    }

    static String sha(byte[] b) { return Mu300Boot.sha256(b); }

    /** One userdata / e5root row of probe(). */
    static final class Row {
        final String node, cid; final long start, size;
        Row(String node, String cid, long start, long size) { this.node = node; this.cid = cid; this.start = start; this.size = size; }
        String disk() { return node.substring(0, node.lastIndexOf('p')); }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Row)) return false;
            Row r = (Row) o;
            return node.equals(r.node) && cid.equals(r.cid) && start == r.start && size == r.size;
        }
        @Override public int hashCode() { return node.hashCode(); }
        Map<String, Object> json() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("node", node); m.put("cid", cid); m.put("start", start); m.put("size", size);
            return m;
        }
        static Row of(Object o) {
            if (!(o instanceof Map)) throw new IllegalArgumentException("invalid transaction: data");
            Map<?, ?> m = (Map<?, ?>) o;
            Object n = m.get("node"), c = m.get("cid"), s = m.get("start"), z = m.get("size");
            if (!(n instanceof String) || !(c instanceof String) || !(s instanceof Long) || !(z instanceof Long))
                throw new IllegalArgumentException("invalid transaction: data");
            return new Row((String) n, (String) c, (Long) s, (Long) z);
        }
        @Override public String toString() { return node + " start " + start + " size " + size; }
    }

    /** probe(): userdata and e5root with the eMMC CID, start and size in 512-byte sectors. */
    static Map<String, Row> probe(Mu300Internal.Io io) throws IOException {
        String raw = io.sh("for u in /sys/class/block/mmcblk*p*/uevent; do "
                + "if grep -qx PARTNAME=userdata \"$u\" || grep -qx PARTNAME=e5root \"$u\"; then "
                + "n=$(basename \"$(dirname \"$u\")\"); d=${n%p*}; "
                + "printf \"%s \" \"$n\"; cat /sys/class/block/$d/device/cid | tr \"\\n\" \" \"; "
                + "cat /sys/class/block/$n/start /sys/class/block/$n/size | tr \"\\n\" \" \"; "
                + "grep ^PARTNAME= \"$u\"; fi; done", true);
        Map<String, Row> rows = new LinkedHashMap<>();
        for (String line : raw.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length != 5 || !f[4].startsWith("PARTNAME=")) continue;
            if (!NODE.matcher(f[0]).matches() || !CID.matcher(f[1]).matches())
                throw new IOException("unsupported device identity");
            if (!f[2].matches("\\d{1,15}") || !f[3].matches("\\d{1,15}")) throw new IOException("unsupported partition geometry");
            String name = f[4].substring(9);
            if (rows.containsKey(name)) throw new IOException("ambiguous partition name");
            rows.put(name, new Row(f[0], f[1], Long.parseLong(f[2]), Long.parseLong(f[3])));
        }
        return rows;
    }

    /** verify_geometry(): the e5root node to write. */
    static String verifyGeometry(Map<String, Object> state, Map<String, Row> rows) throws IOException {
        Row data = rows.get("userdata"), root = rows.get("e5root");
        Row expected = Row.of(state.get("data"));
        long target = num(state, "target"), need = num(state, "need");
        long rootStart = state.get("root_start") instanceof Long ? (Long) state.get("root_start") : expected.start + target;
        if (data == null || root == null || !data.cid.equals(expected.cid) || !data.node.equals(expected.node)
                || data.start != expected.start || data.size != target || !root.cid.equals(expected.cid)
                || root.start != rootStart || root.size != need || !root.disk().equals(expected.disk()))
            throw new IOException("device/partition geometry differs from the saved transaction; nothing written / 分区布局与事务记录不符，未写入");
        return "/dev/block/" + root.node;
    }

    static long num(Map<String, Object> state, String key) {
        Object v = state.get(key);
        if (!(v instanceof Long)) throw new IllegalArgumentException("invalid transaction size: " + key);
        return (Long) v;
    }
    static String str(Map<String, Object> state, String key) {
        Object v = state.get(key);
        return v instanceof String ? (String) v : null;
    }

    /** validate_state(). */
    static void validateState(Map<String, Object> s) {
        Object version = s.get("version");
        if (!(version instanceof Long) || (Long) version != 2 || !Arrays.asList(PHASES).contains(str(s, "phase")))
            throw new IllegalArgumentException("invalid transaction or unsupported state");
        String token = str(s, "token");
        if (token == null || !TOKEN.matcher(token).matches()) throw new IllegalArgumentException("invalid transaction token");
        if (s.containsKey("android_slot") || s.containsKey("linux_slot")) {
            String a = str(s, "android_slot"), l = str(s, "linux_slot");
            if (!("a".equals(a) || "b".equals(a)) || !(("a".equals(a) ? "b" : "a").equals(l)))
                throw new IllegalArgumentException("invalid transaction slot roles");
        }
        for (String k : new String[]{"need", "target", "raw_size"}) {
            long v = num(s, k);
            if (v <= 0 || v >= 1L << 40) throw new IllegalArgumentException("invalid transaction size");
        }
        Object grow = s.get("grow");
        if (num(s, "need") % 2048 != 0 || num(s, "target") % 2048 != 0 || !(grow instanceof Long) || ((Long) grow != 0 && (Long) grow != 1))
            throw new IllegalArgumentException("unaligned transaction size");
        for (String k : new String[]{"apn", "ssid", "key"}) {
            String v = str(s, k);
            if (v == null || v.indexOf('\'') >= 0 || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\0') >= 0)
                throw new IllegalArgumentException("invalid saved settings");
        }
    }

    // ------------------------------------------------------------------ transaction file
    static String toJson(Object v, String indent) {
        StringBuilder b = new StringBuilder();
        json(b, v, indent == null ? "" : indent, indent != null);
        return b.toString();
    }
    private static void json(StringBuilder b, Object v, String ind, boolean pretty) {
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                if (pretty) b.append('\n').append(ind).append("  ");
                quote(b, String.valueOf(e.getKey()));
                b.append(pretty ? ": " : ":");
                json(b, e.getValue(), ind + "  ", pretty);
            }
            if (pretty && !m.isEmpty()) b.append('\n').append(ind);
            b.append('}');
        } else if (v instanceof String) quote(b, (String) v);
        else if (v instanceof Long || v instanceof Integer) b.append(v);
        else if (v == null) b.append("null");
        else throw new IllegalArgumentException("unsupported JSON value");
    }
    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        b.append('"');
    }

    /** save(): write atomically (tmp + rename). */
    static void save(File path, Map<String, Object> state) throws IOException {
        File tmp = new File(path.getPath() + ".tmp");
        Mu300Internal.writeAll(tmp, toJson(state, "").getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(path)) throw new IOException("cannot save " + path);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> load(File path) throws IOException {
        Object o = MiniJson.parse(new String(Mu300Internal.readAll(path), StandardCharsets.UTF_8));
        if (!(o instanceof Map)) throw new IOException("invalid transaction");
        return (Map<String, Object>) o;
    }

    // ================================================================== package and device context
    /** What the flows need from the staged package and the target. */
    static final class Pkg {
        byte[] boot, bootArgs;
        String bootSha, imageSha, profile, hotspotDefault;
        long slotRoles, rawSize;
        boolean hasTools, hasPartWrite;
    }

    private static String q(String s) { return RootShell.q(s); }

    /** Reads the staged package's boot image, boot-args.sh, boot.json and hashes from the target. */
    static Pkg loadPkg(Mu300Internal.Io io, long rawSize) throws IOException {
        Pkg p = new Pkg();
        p.rawSize = rawSize;
        String files = P + "/files/";
        String info = io.sh("cd " + q(P) + " && for f in files/boot.img files/boot-args.sh files/boot.json files/ext4-tools.tar "
                + "files/part-write.sh files/openwrt.ext4.gz; do if [ -f \"$f\" ]; then echo \"F $f $(stat -c %s \"$f\")\"; fi; done; "
                + "grep '  files/openwrt.ext4.gz$' files/SHA256SUMS | cut -d ' ' -f1; "
                + "sed -n 's/.*\"profile\"[^\"]*\"\\([^\"]*\\)\".*/P \\1/p' files/device-profile.json 2>/dev/null | head -n1", true);
        Map<String, Long> sizes = new LinkedHashMap<>();
        for (String l : info.split("\n")) {
            String[] f = l.trim().split("\\s+");
            if (f.length == 3 && f[0].equals("F") && f[2].matches("\\d+")) sizes.put(f[1], Long.parseLong(f[2]));
            else if (f.length == 1 && f[0].matches("[0-9a-f]{64}")) p.imageSha = f[0];
            else if (f.length == 2 && f[0].equals("P")) p.profile = f[1];
        }
        p.hasTools = sizes.containsKey("files/ext4-tools.tar");
        p.hasPartWrite = sizes.containsKey("files/part-write.sh");
        Long bootSize = sizes.get("files/boot.img");
        if (bootSize == null || bootSize < 4096 || bootSize > (64L << 20)) throw new IOException("staged package has no usable files/boot.img");
        if (p.imageSha == null || !sizes.containsKey("files/openwrt.ext4.gz")) throw new IOException("staged package has no listed files/openwrt.ext4.gz");
        p.boot = Mu300Internal.read(io, "cat " + q(files + "boot.img"), bootSize);
        p.bootSha = sha(p.boot);
        Long argsSize = sizes.get("files/boot-args.sh");
        if (argsSize != null && argsSize > 0) p.bootArgs = Mu300Internal.read(io, "cat " + q(files + "boot-args.sh"), argsSize);
        Long jsonSize = sizes.get("files/boot.json");
        p.slotRoles = 0;
        if (jsonSize != null && jsonSize > 0) {
            Object o = MiniJson.parse(new String(Mu300Internal.read(io, "cat " + q(files + "boot.json"), jsonSize), StandardCharsets.UTF_8));
            if (o instanceof Map && ((Map<?, ?>) o).get("slot_roles_version") instanceof Long)
                p.slotRoles = (Long) ((Map<?, ?>) o).get("slot_roles_version");
        }
        p.hotspotDefault = defaultHotspotName(p.profile);
        return p;
    }

    /** The desktop installer's default_hotspot_name(). */
    static String defaultHotspotName(String profile) {
        if (profile == null) return "E5-OpenWrt";
        switch (profile) {
            case "t551m": return "T551M-OpenWrt";
            case "m8": return "M8-OpenWrt";
            case "t512n": return "T512N-OpenWrt";
            case "yuanhang41s": return "Yuanhang41S-OpenWrt";
            case "k1": return "K1-OpenWrt";
            default: return "E5-OpenWrt";
        }
    }

    /** The desktop installer's random hotspot key for a new install (install_part). */
    static String randomKey() {
        String a = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(a.charAt(RNG.nextInt(a.length())));
        return b.toString();
    }

    static String[] slots(Mu300Internal.Io io) throws IOException {
        String s = io.sh("getprop ro.boot.slot_suffix", false).trim();
        if (!s.equals("_a") && !s.equals("_b")) throw new IOException("Android slot is unavailable; nothing written");
        String a = s.substring(1);
        return new String[]{a, a.equals("a") ? "b" : "a"};
    }

    static String serial(Mu300Internal.Io io) throws IOException { return io.sh("getprop ro.serialno", false).trim(); }
    static String model(Mu300Internal.Io io) throws IOException { return io.sh("getprop ro.product.model", false).trim(); }

    /** read_boot_control(): the 32-byte BCAB record at misc+2048, validated for the running slot. */
    static byte[] readBootControl(Mu300Internal.Io io, String androidSlot) throws IOException {
        byte[] bc = unhex(io.sh("dd if=/dev/block/by-name/misc bs=1 skip=2048 count=32 2>/dev/null | od -An -tx1 -v", true));
        try { validateControl(bc, androidSlot); }
        catch (IllegalArgumentException e) { throw new IOException("boot control check failed: " + e.getMessage()); }
        return bc;
    }

    static byte[] readBcb(Mu300Internal.Io io) throws IOException {
        return unhex(io.sh("dd if=/dev/block/by-name/misc bs=1 count=2048 2>/dev/null | od -An -tx1 -v", true));
    }

    static byte[] unhex(String text) {
        String h = text.replaceAll("[^0-9a-fA-F]", "");
        byte[] r = new byte[h.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return r;
    }

    /** owrt-apk.sh e5-tool SUB: device steps that need root and the package's own scripts. */
    static String tool(Mu300Internal.Io io, String what, boolean writes, String args, int silence) throws IOException {
        io.step(what, writes);
        String out = io.sh("sh " + JobManager.BASE_HELPER + " e5-tool " + args, true, silence);
        boolean ok = false;
        String rc = null;
        for (String l : out.split("\n")) {
            if (l.startsWith("OWRT-APK-TOOL-RC=")) rc = l.substring(17).trim();
            else if (l.startsWith("OWRT-APK-TOOL-OK")) ok = true;
            else if (!l.startsWith("[TOOL]") && !l.trim().isEmpty() && !l.startsWith("OWRT-APK-")) io.log("  " + l);
        }
        String sub = args.split(" ")[0];
        if (!ok || !"0".equals(rc)) {
            String last = "";
            for (String l : out.split("\n")) if (l.contains("ERROR") || l.contains("FAILED") || l.contains("error")) last = l.trim();
            throw new IOException(what + "失败（" + sub + "，退出码 " + rc + "）" + (last.isEmpty() ? "" : "：" + last));
        }
        return out;
    }

    /** backup(): copy a device file/partition into the controller and compare checksums. */
    static void backup(Mu300Internal.Io io, String remote, File local) throws IOException { backup(io, remote, local, null); }
    static void backup(Mu300Internal.Io io, String remote, File local, String nth) throws IOException {
        String size = io.sh("blockdev --getsize64 " + q(remote) + " 2>/dev/null || stat -c %s " + q(remote), true).trim();
        if (!size.matches("\\d+")) throw new IOException("cannot size " + remote);
        long n = Long.parseLong(size);
        if (nth != null) io.log("正在备份 " + nth + "：" + local.getName() + "（" + Mu300Internal.human(n) + "）");
        Mu300Internal.download(io, "cat " + q(remote), local, n, local.getName());
    }

    /** backup_gpt(): primary (34 sectors) and backup (last 33 sectors) GPT of the userdata disk. */
    static void backupGpt(Mu300Internal.Io io, Row data, File directory) throws IOException {
        String disk = data.disk();
        long sectors = diskSectors(io, disk);
        if (sectors <= data.start + data.size) throw new IOException("invalid disk bounds");
        io.log("正在备份 第 2/3 个：gpt-primary.bin（" + Mu300Internal.human(34 * 512) + "）");
        Mu300Internal.download(io, "dd if=/dev/block/" + disk + " bs=512 skip=0 count=34 2>/dev/null", new File(directory, "gpt-primary.bin"), 34 * 512);
        io.log("正在备份 第 3/3 个：gpt-backup.bin（" + Mu300Internal.human(33 * 512) + "）");
        Mu300Internal.download(io, "dd if=/dev/block/" + disk + " bs=512 skip=" + (sectors - 33) + " count=33 2>/dev/null", new File(directory, "gpt-backup.bin"), 33 * 512);
    }

    static long diskSectors(Mu300Internal.Io io, String disk) throws IOException {
        String s = io.sh("cat /sys/class/block/" + disk + "/size", true).trim();
        if (!s.matches("\\d+")) throw new IOException("disk capacity unavailable");
        return Long.parseLong(s);
    }

    /** flash_boot(): bind to the current boot control, upload, dd into boot_<linux> and verify. */
    static void flashBoot(Mu300Internal.Io io, Pkg pkg, File path, String what) throws IOException {
        String[] sl = slots(io);
        byte[] payload;
        try { payload = bindBoot(Mu300Internal.readAll(path), readBootControl(io, sl[0]), sl[0], pkg.slotRoles); }
        catch (IllegalArgumentException e) { throw new IOException("启动镜像绑定失败：" + e.getMessage()); }
        String name = path.getName();
        File bound = new File(path.getParentFile(), name.substring(0, name.lastIndexOf('.')) + "-device.img");
        Mu300Internal.writeAll(bound, payload);
        String remote = TMP + "/e5-two-stage-boot.img";
        io.step("上传启动镜像（" + what + "）", false);
        io.push(bound, remote);
        io.step("写入并校验 " + sl[1].toUpperCase(Locale.ROOT) + " 槽启动镜像（" + what + "）", true);
        io.sh("set -e; dd if=" + remote + " of=/dev/block/by-name/boot_" + sl[1] + " bs=1048576 conv=fsync; "
                + "[ \"$(sha256sum /dev/block/by-name/boot_" + sl[1] + " | cut -d \" \" -f1)\" = " + sha(payload) + " ]; "
                + "rm -f " + remote, true, 300);
        io.log("  ✓ boot_" + sl[1] + " 已写入并回读校验（" + what + "）");
    }

    static String stamp() { return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()); }

    // ================================================================== read-only checks
    /** What check() found for the format-userdata two-stage path. */
    static final class WipeCheck {
        String bcbKind; boolean ok; String reason;
    }

    /** Read-only checks start() relies on, done before the user confirms anything. */
    static WipeCheck checkWipe(Mu300Internal.Io io, Pkg pkg, boolean localTarget) throws IOException {
        WipeCheck c = new WipeCheck();
        try {
            if (localTarget)
                throw new IOException("格式化 userdata 会清空这台手机自己的数据，包括本应用保存的事务记录和备份，安装无法续上；"
                        + "请用另一台手机通过 USB/局域网操作，或减小分区以保留数据缩容");
            try { bootCommand(pkg.boot, "e5.root=part", pkg.bootArgs); }
            catch (IllegalArgumentException e) { throw new IOException("安装包不支持两阶段安装：" + e.getMessage()); }
            if (!pkg.hasTools || !pkg.hasPartWrite) throw new IOException("new package required: missing ext4-tools.tar / part-write.sh");
            String[] sl = slots(io);
            readBootControl(io, sl[0]);
            if ("b".equals(sl[0]) && pkg.slotRoles < 1) throw new IOException("this package cannot install while Android runs from slot B");
            try { c.bcbKind = recoveryMessageKind(readBcb(io)); }
            catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
            if ("bootloader-only".equals(c.bcbKind) && !"1".equals(io.sh("getprop sys.boot_completed", false).trim()))
                throw new IOException("Wait for Android to finish booting before replacing the bootloader request / 请等待 Android 完成启动");
            Map<String, Row> rows = probe(io);
            if (rows.containsKey("e5root") || !rows.containsKey("userdata")) throw new IOException("unexpected existing root or userdata");
            if (rows.get("userdata").start % 2048 != 0) throw new IOException("userdata start is not 1 MiB aligned");
            c.ok = true;
        } catch (IOException e) {
            c.ok = false;
            c.reason = e.getMessage();
        }
        return c;
    }

    // ================================================================== start (stage one)
    /** part-two-stage.py start(): everything up to and including the reboot into the preparation boot. */
    static Mu300Internal.Outcome start(Mu300Internal.Io io, Pkg pkg, File base, String node, long partMib, long dataMib,
                                       int grow, String apn, String ssid, String key, boolean localTarget) throws IOException {
        if (localTarget) throw new IOException("格式化 userdata 的两阶段安装不能在本机进行（会清空本应用的事务记录）");
        byte[] normal = pkg.boot;
        try { bootCommand(normal, "e5.root=part", pkg.bootArgs); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
        if (!pkg.hasTools || !pkg.hasPartWrite) throw new IOException("new package required: missing ext4-tools.tar / part-write.sh");
        Map<String, Row> rows = probe(io);
        Row data = rows.get("userdata");
        if (rows.containsKey("e5root") || data == null || !("/dev/block/" + data.node).equals(node) && !data.node.equals(node))
            throw new IOException("unexpected existing root or userdata");
        if (data.start % 2048 != 0 || dataMib * 2048 + partMib * 2048 > data.size) throw new IOException("unaligned or oversized partition plan");
        byte[] bcb = readBcb(io);
        String bcbKind;
        try { bcbKind = recoveryMessageKind(bcb); } catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
        if ("bootloader-only".equals(bcbKind) && !"1".equals(io.sh("getprop sys.boot_completed", false).trim()))
            throw new IOException("Wait for Android to finish booting before replacing the bootloader request / 请等待 Android 完成启动");
        File directory = new File(new File(base, "backups"), "part-" + stamp() + "-" + Mu300Internal.token(4));
        if (!directory.mkdirs()) throw new IOException("cannot create " + directory);
        File path = new File(directory, "transaction.json");
        String[] sl = slots(io);
        byte[] bc = readBootControl(io, sl[0]);
        io.step("备份 boot_" + sl[1] + "、GPT、misc 到本机", false);
        io.log("备份到本机：" + directory);
        backup(io, "/dev/block/by-name/boot_" + sl[1], new File(directory, "boot-" + sl[1] + "-before.img"), "第 1/3 个");
        backupGpt(io, data, directory);
        Mu300Internal.writeAll(new File(directory, "boot-control-before.bin"), bc);
        Mu300Internal.writeAll(new File(directory, "bcb-before.bin"), bcb);
        String token = Mu300Internal.token(16);
        LinkedHashMap<String, Object> state = new LinkedHashMap<>();
        state.put("version", 2L); state.put("phase", "prepared"); state.put("token", token);
        state.put("serial", serial(io)); state.put("model", model(io)); state.put("transport", io.transport()); state.put("data", data.json());
        state.put("android_slot", sl[0]); state.put("linux_slot", sl[1]);
        state.put("target", dataMib * 2048); state.put("need", partMib * 2048); state.put("grow", (long) grow);
        state.put("boot_sha", pkg.bootSha); state.put("image_sha", pkg.imageSha); state.put("raw_size", pkg.rawSize);
        state.put("apn", apn); state.put("ssid", ssid); state.put("key", key); state.put("request_handoff", 1L);
        try { validateState(state); } catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
        save(path, state);
        io.log("事务记录（请保留，续装要用）：" + path);
        String request = String.join(":", token, data.cid, data.node, String.valueOf(data.start), String.valueOf(data.size),
                String.valueOf(dataMib * 2048), String.valueOf(partMib * 2048), Mu300Internal.hex(bc));
        File temporary = new File(directory, "boot-prepare.img");
        try { Mu300Internal.writeAll(temporary, bootCommand(normal, "e5.part_prepare=" + request, pkg.bootArgs)); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
        tool(io, "同步启动链备份", false, "bootchain-sync", 300);
        io.step("把启动链备份保存到本机", false);
        io.log("正在保存启动链备份到本机：bootchain.tar");
        Mu300Internal.download(io, "mkdir -p /data/adb/e5-bootchain; tar -cf - -C /data/adb e5-bootchain",
                new File(directory, "bootchain.tar"), -1, "bootchain.tar");
        flashBoot(io, pkg, temporary, "第一阶段：重建分区并请求清空数据");
        if ("bootloader-only".equals(bcbKind)) {
            io.step("清除已备份的 bootloader 单次请求并回读", true);
            io.sh(clearBootloaderCommandScript(bcb, bc), true, 60);
        }
        tool(io, "切换到 Linux 槽", true, "arm", 120);
        state.put("phase", "awaiting-android");
        save(path, state);
        io.step("重启目标设备", true);
        io.reboot();
        return outcome(true,
                "第一阶段已就绪，目标设备正在重启：重启后重建分区，进入原厂 recovery 清空数据，再回 Android。",
                "首次开机的离线缩容通常需要数分钟，数据较多或闪存较慢时更久；请保持供电，不要中断。缩容完成后会自动重启；"
                        + "原厂 recovery 清空数据还可能再次重启，然后回到 Android。如 recovery 要求确认清空数据，请在设备上确认。"
                        + Mu300Internal.RESUME_PREP_SHORT + "。"
                        + "事务记录保存在本机：" + path);
    }

    // ================================================================== resume
    /** The newest saved transaction (backups/ and reinstalls/) for this serial, or null. */
    static File findTransaction(File base, String serial) {
        File best = null;
        for (String sub : new String[]{"backups", "reinstalls"}) {
            File[] dirs = new File(base, sub).listFiles();
            if (dirs == null) continue;
            for (File d : dirs) {
                File t = new File(d, "transaction.json");
                if (!t.isFile()) continue;
                try {
                    Map<String, Object> s = load(t);
                    if (serial != null && !serial.equals(str(s, "serial"))) continue;
                    if ("complete".equals(str(s, "phase"))) continue;
                } catch (IOException | RuntimeException e) { continue; }
                if (best == null || t.lastModified() > best.lastModified()) best = t;
            }
        }
        return best;
    }

    /** Read-only resume check: describes the transaction and whether the device matches it. */
    static String checkResume(Mu300Internal.Io io, Pkg pkg, File base) throws IOException {
        File path = findTransaction(base, serial(io));
        if (path == null) throw new IOException("本机没有这台设备未完成的两阶段/重装事务记录（" + new File(base, "backups") + "）");
        Map<String, Object> state = load(path);
        try { validateState(state); } catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
        String[] sl = slots(io);
        if (!sl[0].equals(state.getOrDefault("android_slot", "a")) || !sl[1].equals(state.getOrDefault("linux_slot", "b"))) {
            if (Rebind.switching(state, io.transport()))
                throw new Uninstall.Refused(Rebind.MISMATCH + "（Android 槽位 记录 _" + state.getOrDefault("android_slot", "a") + " / 现在 _" + sl[0] + "）；未做任何修改");
            throw new IOException("Android/Linux slots changed since preparation; installation not resumed");
        }
        if (!model(io).equals(str(state, "model"))) throw new IOException("wrong device");
        if (!pkg.bootSha.equals(str(state, "boot_sha")) || !pkg.imageSha.equals(str(state, "image_sha")))
            throw new IOException("different installation package: 请载入第一阶段时用的同一个安装包");
        Map<String, Row> rows = probe(io);
        String where;
        if (!rows.containsKey("e5root") && Row.of(state.get("data")).equals(rows.get("userdata")))
            where = "分区还没有重建（第一阶段没有执行）；续装会在核对原始 GPT 与备份一致后重新写入第一阶段启动请求";
        else where = "分区已按事务重建：" + verifyGeometry(state, rows);
        return "事务 " + path.getParentFile().getName() + "，阶段 " + str(state, "phase") + "；" + where;
    }

    static Mu300Internal.Outcome resume(Mu300Internal.Io io, Pkg pkg, File base) throws IOException {
        File path = findTransaction(base, serial(io));
        if (path == null) throw new IOException("no unfinished transaction for this device");
        Map<String, Object> state = load(path);
        io.log("续装事务：" + path);
        return finish(io, pkg, path, state);
    }

    // ================================================================== retry_missing_request
    static Mu300Internal.Outcome retryMissingRequest(Mu300Internal.Io io, Pkg pkg, File path, Map<String, Object> state,
                                                     Map<String, Row> rows) throws IOException {
        String phase = str(state, "phase");
        Row data = Row.of(state.get("data"));
        boolean handoff = state.get("request_handoff") instanceof Long && (Long) state.get("request_handoff") != 0;
        if (!("awaiting-android".equals(phase) || "rearming".equals(phase)) || handoff && !"rearming".equals(phase)
                || rows.size() != 1 || !data.equals(rows.get("userdata")))
            throw new IOException("Partition preparation did not complete; keep backups and inspect the device / "
                    + "分区准备未完成，请保留备份并检查设备，不会重复缩容或格式化");
        File directory = path.getParentFile();
        File bootBefore = new File(directory, "boot-" + str(state, "linux_slot") + "-before.img");
        File chain = new File(directory, "bootchain.tar");
        if (!bootBefore.isFile() || bootBefore.length() != pkg.boot.length || !chain.isFile() || chain.length() == 0)
            throw new IOException("Original boot backups missing or truncated; preparation not retried");
        long sectors = diskSectors(io, data.disk());
        if (sectors <= data.start + data.size) throw new IOException("Invalid disk bounds; preparation not retried");
        String[][] gpts = {{"gpt-primary.bin", "0", "34"}, {"gpt-backup.bin", String.valueOf(sectors - 33), "33"}};
        for (String[] g : gpts) {
            File before = new File(directory, g[0]);
            int count = Integer.parseInt(g[2]);
            if (!before.isFile() || before.length() != count * 512L) throw new IOException("Original GPT backup missing or truncated; preparation not retried");
            byte[] now = Mu300Internal.read(io, "dd if=/dev/block/" + data.disk() + " bs=512 skip=" + g[1] + " count=" + count + " 2>/dev/null", count * 512L);
            if (!Arrays.equals(now, Mu300Internal.readAll(before)))
                throw new IOException("GPT differs from original backup; preparation not retried / GPT 与原始备份不符，禁止重新准备");
        }
        try {
            if (!"empty".equals(recoveryMessageKind(readBcb(io)))) throw new IOException("Pending bootloader/recovery request; preparation not retried");
            readBootControl(io, str(state, "android_slot"));
            byte[] beforeControl = Mu300Internal.readAll(new File(directory, "boot-control-before.bin"));
            validateControl(beforeControl, str(state, "android_slot"));
            tool(io, "安装前检查（与 TF 卡方式相同）", false, "preflight", 900);
            String request = String.join(":", str(state, "token"), data.cid, data.node, String.valueOf(data.start), String.valueOf(data.size),
                    String.valueOf(num(state, "target")), String.valueOf(num(state, "need")), Mu300Internal.hex(beforeControl));
            File temporary = new File(directory, "boot-prepare-embedded.img");
            Mu300Internal.writeAll(temporary, bootCommand(pkg.boot, "e5.part_prepare=" + request, pkg.bootArgs));
            state.put("phase", "rearming"); state.put("request_handoff", 1L);
            save(path, state);
            io.log("原始 GPT 与两份备份一致：修复第一阶段启动请求后重新准备；保留原备份与事务。");
            flashBoot(io, pkg, temporary, "重新写入第一阶段启动请求");
        } catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
        tool(io, "切换到 Linux 槽", true, "arm", 120);
        state.put("phase", "awaiting-android");
        save(path, state);
        io.step("重启目标设备", true);
        io.reboot();
        return outcome(true, "第一阶段启动请求已重新写入，目标设备正在重启。",
                "等待离线重建分区和原厂 recovery 清空数据完成；" + Mu300Internal.RESUME_PREP_SHORT + "。");
    }

    // ================================================================== reinstall
    static final class Reinstall { Row data, root; }

    /** Read-only part of reinstall(). */
    static Reinstall checkReinstall(Mu300Internal.Io io, Pkg pkg) throws IOException {
        if (!pkg.hasTools || !pkg.hasPartWrite) throw new IOException("new package required: missing ext4-tools.tar / part-write.sh");
        try { bootCommand(pkg.boot, "e5.root=part", pkg.bootArgs); }
        catch (IllegalArgumentException e) { throw new IOException("安装包不支持独立分区：" + e.getMessage()); }
        Map<String, Row> rows = probe(io);
        if (!rows.containsKey("e5root") || !rows.containsKey("userdata"))
            throw new IOException("this device has no internal partition installation to rewrite / 这台设备没有已安装的 e5root 独立分区");
        Reinstall r = new Reinstall();
        r.data = rows.get("userdata"); r.root = rows.get("e5root");
        if (!r.data.disk().equals(r.root.disk())) throw new IOException("userdata and e5root are not on the same disk");
        if (r.root.start != r.data.start + r.data.size)
            throw new IOException("e5root does not directly follow userdata; the desktop installer cannot verify this layout either");
        if (r.root.size % 2048 != 0 || r.data.size % 2048 != 0) throw new IOException("unaligned partitions");
        return r;
    }

    /** installed_settings(): best effort read of the installed system's install.conf. */
    static Map<String, String> installedSettings(Mu300Internal.Io io) {
        Map<String, String> out = new LinkedHashMap<>();
        String mount = "/data/local/tmp/e5-reinstall-mnt";
        String text;
        try {
            text = io.sh("rm -rf " + mount + "; mkdir -p " + mount + "; "
                    + "if mount -t ext4 -o ro /dev/block/by-name/e5root " + mount + " 2>/dev/null; then "
                    + "cat " + mount + "/etc/e5/install.conf 2>/dev/null; umount " + mount + " 2>/dev/null; "
                    + "else echo __E5_NO_OLD_SYSTEM__; fi; rmdir " + mount + " 2>/dev/null; true", true);
        } catch (IOException e) { text = "__E5_NO_OLD_SYSTEM__"; }
        if (text.contains("__E5_NO_OLD_SYSTEM__"))
            io.log("  现有系统的设置读不出来（e5root 挂载失败，多半文件系统已损坏）；沿用默认值");
        String[][] keys = {{"apn", "E5_APN"}, {"ssid", "E5_WIFI_SSID"}, {"key", "E5_WIFI_KEY"}};
        for (String[] k : keys) {
            java.util.regex.Matcher m = Pattern.compile("(?m)^" + k[1] + "='([^'\\n]*)'").matcher(text);
            if (m.find()) out.put(k[0], m.group(1));
        }
        return out;
    }

    static Mu300Internal.Outcome reinstall(Mu300Internal.Io io, Pkg pkg, File base, int grow) throws IOException {
        Reinstall r = checkReinstall(io, pkg);   // finish() runs the preflight once, as the desktop installer does
        Map<String, String> old = installedSettings(io);
        String apn = old.getOrDefault("apn", "");
        String ssid = old.getOrDefault("ssid", "");
        if (ssid.isEmpty()) ssid = pkg.hotspotDefault;
        String key = old.getOrDefault("key", "");
        if (key.isEmpty()) key = Mu300Internal.token(6);
        if (key.length() < 8 || key.length() > 63) throw new IOException("the hotspot key must have 8-63 characters");
        String[] sl = slots(io);
        String token = Mu300Internal.token(16);
        LinkedHashMap<String, Object> state = new LinkedHashMap<>();
        state.put("version", 2L); state.put("phase", "writing"); state.put("token", token);
        state.put("serial", serial(io)); state.put("model", model(io)); state.put("transport", io.transport());
        state.put("android_slot", sl[0]); state.put("linux_slot", sl[1]);
        state.put("data", r.data.json()); state.put("target", r.data.size); state.put("need", r.root.size); state.put("grow", (long) grow);
        state.put("boot_sha", pkg.bootSha); state.put("image_sha", pkg.imageSha); state.put("raw_size", pkg.rawSize);
        state.put("apn", apn); state.put("ssid", ssid); state.put("key", key);
        try { validateState(state); } catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
        File directory = new File(new File(base, "reinstalls"), stamp() + "-" + token.substring(0, 8));
        if (!directory.mkdirs()) throw new IOException("cannot create " + directory);
        File path = new File(directory, "transaction.json");
        save(path, state);
        io.log("覆盖重装：e5root " + r.root.node + "（" + r.root.size / 2048 + " MiB），userdata 不动；Android 用户数据、分区表、userdata 文件系统都保留；会重写 e5root 并刷新 boot_" + sl[1] + "。");
        io.log("  热点：" + ssid + "  密码：" + key);
        io.log("  事务记录：" + path);
        return finish(io, pkg, path, state);
    }

    // ================================================================== finish
    static Mu300Internal.Outcome finish(Mu300Internal.Io io, Pkg pkg, File path, Map<String, Object> state) throws IOException {
        if ("complete".equals(str(state, "phase")))
            return outcome(false, "这个事务已经完成，不需要重跑：" + path, "");
        try { validateState(state); } catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
        String[] sl = slots(io);
        if (!sl[0].equals(state.getOrDefault("android_slot", "a")) || !sl[1].equals(state.getOrDefault("linux_slot", "b"))) {
            if (Rebind.switching(state, io.transport()))
                throw new Uninstall.Refused(Rebind.MISMATCH + "（Android 槽位 记录 _" + state.getOrDefault("android_slot", "a") + " / 现在 _" + sl[0] + "）；未做任何修改");
            throw new IOException("Android/Linux slots changed since preparation; installation not resumed");
        }
        String mismatch = Rebind.diff("序列号", str(state, "serial"), serial(io), "型号", str(state, "model"), model(io));
        // adb_connect / rebind: continuing over another connection needs the same device and slots.
        if (Rebind.apply(state, io.transport(), io.ready(), mismatch, System.currentTimeMillis() / 1000)) {
            save(path, state);
            if (state.get("transport_history") != null) io.log("已改用新的连接继续：" + io.transport() + "（设备身份核对一致，已记入连接历史）");
        }
        if (mismatch != null
                || !pkg.bootSha.equals(str(state, "boot_sha")) || !pkg.imageSha.equals(str(state, "image_sha")))
            throw new IOException("wrong device or different installation package / 设备或安装包与事务记录不符");
        Map<String, Row> rows = probe(io);
        if (!rows.containsKey("e5root") && Row.of(state.get("data")).equals(rows.get("userdata")))
            return retryMissingRequest(io, pkg, path, state, rows);
        String target = verifyGeometry(state, rows);
        io.log("  分区布局与事务一致：" + target + "（" + num(state, "need") / 2048 + " MiB）");
        tool(io, "安装前检查（与 TF 卡方式相同）", false, "preflight", 900);
        if (!"installed".equals(str(state, "phase"))) {
            if (!"writing".equals(str(state, "phase"))) {
                String marker = new String(Mu300Internal.read(io, "head -c 128 " + target, 128), StandardCharsets.ISO_8859_1);
                if (!marker.startsWith("E5-PART-PREPARED-V2 " + str(state, "token") + "\n"))
                    throw new IOException("missing preparation marker; do not overwrite this partition / 分区准备标记不符，不写入");
            }
            tool(io, "提取并转换本机固件（安装包 prepare_firmware）", false, "firmware", 900);
            File settings = new File(io.cacheDir(), "e5-install.conf");
            Mu300Internal.writeAll(settings, String.format(Locale.ROOT,
                    "E5_APN='%s'\nE5_WIFI_SSID='%s'\nE5_WIFI_KEY='%s'\nE5_DEFAULT_BOOT='linux'\n",
                    str(state, "apn"), str(state, "ssid"), str(state, "key")).getBytes(StandardCharsets.UTF_8));
            try {
                io.step("上传安装设置", false);
                io.push(settings, TMP + "/e5-install.conf");
            } finally {
                //noinspection ResultOfMethodCallIgnored
                settings.delete();
            }
            state.put("phase", "writing");
            save(path, state);
            tool(io, "写入、扩容并校验独立分区（安装包 part-write.sh）", true, "part-write " + target + " " + num(state, "need") + " "
                    + num(state, "raw_size") + " " + num(state, "grow") + " " + str(state, "token"), 900);
            state.put("phase", "installed");
            save(path, state);
        }
        File finalBoot = new File(path.getParentFile(), "boot-part.img");
        try { Mu300Internal.writeAll(finalBoot, bootCommand(pkg.boot, "e5.root=part", pkg.bootArgs)); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
        tool(io, "同步启动链备份", false, "bootchain-sync", 300);
        flashBoot(io, pkg, finalBoot, "OpenWrt 独立分区启动");
        tool(io, "切换到 Linux 槽", true, "arm", 120);
        state.put("phase", "complete");
        save(path, state);
        return outcome(false, "安装及容量校验完成。请手动重启进入 OpenWrt；Android userdata 不再修改。",
                "热点：" + str(state, "ssid") + "  密码：" + str(state, "key"));
    }
}
