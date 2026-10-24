package top.linote.owrtinstaller;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads an OpenWrt installer ZIP as a stream, hashes every entry, extracts only the
 * on-device installation files and verifies them against the package's own manifest:
 *  - E5 family layout (E5 / T551M / M8 / T512N / 41S / K1): files/SHA256SUMS;
 *  - MU300 layout (MU300 / F50 / U30 Air / V50, two kernel builds): desktop/manifest.json
 *    (magisk + desktop sections, as the package's desktop verify()) and payload/payload.sha256.
 *
 * Nothing from the package is executed here. Pure Java: also runs on a desktop JVM.
 */
public final class PackageInspector {

    /** Thrown when the ZIP is not a supported, intact installer package. */
    public static final class PackageException extends Exception {
        PackageException(String msg) { super(msg); }
    }

    public interface Progress {
        void onProgress(long bytesRead, long totalBytes, String entry);
        boolean isCancelled();
    }

    public static final class Result {
        public File packageDir;              // extracted installation subset (pkg/)
        public String prefix = "";           // top-level folder inside the ZIP, if any
        public String fingerprint;           // sha256 of the original SHA256SUMS
        public int listedFiles;              // entries in SHA256SUMS
        public int verifiedFiles;            // listed entries whose hash matched
        public int extractedFiles;           // installation subset written to pkg/
        public long extractedBytes;
        public String version = "";          // files/VERSION
        public String moduleName = "";
        public String moduleVersion = "";
        public String profile = "";
        public List<String> androidModels = new ArrayList<>();
        public String openwrtVersion = "";
        public String kernelRelease = "";
        public String buildTime = "";
        public String defaultSsid = "";
        public long rawBytes;
        public long bootBytes;
        public Map<String, String> env = new LinkedHashMap<>();
        public List<String> warnings = new ArrayList<>();

        public String title = "";            // friendly device name
        public String board;                 // required ro.product.device, or null
        public boolean unlockRequired = true;

        public String layout = "e5";         // "e5" (files/SHA256SUMS) or "mu300" (payload/ + desktop/manifest.json)
        public boolean tfOnly;               // MU300: only the package's own on-device TF worker is ported
        public String kernelFamily = "";     // MU300 manifest "kernel" (6.18 / 7.2)
        public String internalProblem;       // MU300: why the internal-storage methods are unavailable (null = available)
        public File internalSrc;             // MU300: extracted desktop/ files for the internal methods
        public Map<String, String> listedMap = new LinkedHashMap<>();   // MU300: every manifest entry and its hash
        public long requiredKib;             // MU300 rootfs.requirements (TF capacity needed)
        public String adminUrl = "http://192.168.9.1";
        public String logDir = "/data/adb/e5-tf-install";

        public boolean isMu300() { return "mu300".equals(layout); }

        public boolean supportsModel(String model) {
            return model != null && androidModels.contains(model);
        }

        public String targetProblem(String model, String device, String vbstate) {
            return targetProblem(model, device, vbstate, null);
        }

        /**
         * Same identity rules as the package's own preflight (magisk-tf-install.sh) and the desktop
         * installer's android_checks: model, then board for K1 / 41S, then unlocked bootloader except
         * for T512N / 41S. Returns null when the target matches, otherwise a reason.
         */
        public String targetProblem(String model, String device, String vbstate, String platform) {
            if (isMu300()) return mu300Problem(model, device, platform);
            if (!supportsModel(model))
                return "目标设备型号 " + model + " 不在安装包支持列表 " + androidModels + " 中";
            if (board != null && !board.equals(device))
                return "目标设备主板 " + device + " 与安装包要求的 " + board + " 不一致（" + title + "）";
            if (unlockRequired && vbstate != null && !"?".equals(vbstate) && !"orange".equals(vbstate))
                return "bootloader 未解锁（verifiedbootstate=" + vbstate + "，需要 orange）";
            return null;
        }

        /** Same rule as the package's payload/android-bootchain.sh check (case on "model / device"). */
        static String mu300Problem(String model, String device, String platform) {
            String m = (model == null ? "" : model) + " / " + (device == null ? "" : device);
            if (m.equals("V50 / MU3351")) {
                if (!"ums9620".equals(platform))
                    return "V50 / MU3351 需要 UMS9620 平台（当前 ro.board.platform=" + platform + "）";
                return null;
            }
            for (String k : new String[]{"U30Air", "U30_Air", "U30 Air", "MU300", "F50", "mu300"})
                if (m.contains(k)) return null;
            return "目标设备 " + m + " 不是 ZTE F50/MU300、V50/MU3351 或 U30 Air，MU300 安装包会拒绝";
        }
    }

    /** Device profiles known to the package's installers (OpenWrt-Installer.py package_profile / magisk-tf-install.sh preflight). */
    static final class DeviceRule {
        final String profile, title, model, board;
        final boolean unlockRequired;
        DeviceRule(String profile, String title, String model, String board, boolean unlockRequired) {
            this.profile = profile; this.title = title; this.model = model; this.board = board; this.unlockRequired = unlockRequired;
        }
    }

    static final DeviceRule[] RULES = {
            new DeviceRule("e5", "E5", "E5", null, true),
            new DeviceRule("t551m", "T551M", "T551M", null, true),
            new DeviceRule("m8", "VIPME M8", "VIPME M8", null, true),
            new DeviceRule("t512n", "T512N", "T512N", null, false),          // engineering firmware reports green/locked
            new DeviceRule("yuanhang41s", "中兴远航 41S（ZTE 7546N）", "ZTE 7546N", "P720S11", false),
            new DeviceRule("k1", "angelcare K1", "angelcare K1", "ums9157_2h10", true),
    };

    static DeviceRule rule(String profile) {
        for (DeviceRule r : RULES) if (r.profile.equals(profile)) return r;
        return null;
    }

    static String supportedTitles() {
        StringBuilder b = new StringBuilder();
        for (DeviceRule r : RULES) { if (b.length() > 0) b.append("、"); b.append(r.title); }
        return b.toString();
    }

    /** Files the APK extracts: what the on-device (Magisk-style) TF installer uses. */
    private static final Pattern SUBSET = Pattern.compile(
            "^(files/[^/]+|scripts/(installer|tools)/[^\\x00]+|module\\.prop|customize\\.sh|skip_mount)$");

    /** Minimum set that makes a ZIP an installable package for this APK. */
    static final String[] REQUIRED = {
            "files/magisk-tf-install.sh", "files/tf-install.env", "files/busybox",
            "files/SHA256SUMS", "files/openwrt.ext4.gz", "files/boot.img", "files/boot.json",
            "files/e5-bootctl", "files/android-tf.sh", "files/android-bootchain.sh",
            "files/android-loop.sh", "files/collect-device-files.sh", "files/magisk-firmware.py",
            "files/ext4-tools.tar", "scripts/installer/boot-slots.py",
    };

    /** MU300 layout: the Magisk payload the on-device TF worker uses, plus the manifests. */
    private static final Pattern MU_SUBSET = Pattern.compile(
            "^(payload/[^\\x00]+|module\\.prop|desktop/(manifest|release)\\.json)$");

    /** Runtime names the MU300 worker resolves relative to its payload directory. */
    static final String[] MU_REQUIRED = {
            "tf-install-worker.sh", "android-install.sh", "tf-storage.sh", "tf-stage.sh", "switch.sh",
            "bootchain.sh", "android-bootchain.sh", "mu300-vendor-from-device.sh", "locale.sh", "customize.sh",
            "busybox", "mu300-openwrt.tar.gz", "boot-linux.img", "boot-linux.sha256", "payload.sha256",
            "rootfs.requirements", "rootfs-critical.sha256",
    };

    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");
    private static final long MAX_ENTRIES = 20000;

    private PackageInspector() {}

    public static Result inspect(InputStream zipStream, long totalBytes, File workDir, Progress progress)
            throws IOException, PackageException {
        File raw = new File(workDir, "raw");
        File pkg = new File(workDir, "pkg");
        deleteTree(raw);
        deleteTree(pkg);
        if (!raw.mkdirs()) throw new IOException("cannot create " + raw);

        Map<String, String> hashes = new HashMap<>();
        Map<String, Long> sizes = new HashMap<>();
        TreeSet<String> extracted = new TreeSet<>();
        CountingStream counting = new CountingStream(zipStream);
        byte[] buf = new byte[1 << 16];
        long count = 0;
        try (ZipInputStream zin = new ZipInputStream(counting)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (progress != null && progress.isCancelled()) throw new PackageException("已取消");
                if (++count > MAX_ENTRIES) throw new PackageException("ZIP 条目过多，拒绝处理");
                String name = e.getName();
                if (e.isDirectory()) continue;
                checkSafeName(name);
                if (hashes.containsKey(name)) throw new PackageException("ZIP 内有重复条目：" + name);
                boolean keep = isCandidate(name);
                MessageDigest md = sha256();
                long n = 0;
                OutputStream out = null;
                File target = null;
                try {
                    if (keep) {
                        target = new File(raw, name);
                        File parent = target.getParentFile();
                        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                            throw new IOException("cannot create " + parent);
                        out = new FileOutputStream(target);
                    }
                    int r;
                    while ((r = zin.read(buf)) > 0) {
                        md.update(buf, 0, r);
                        if (out != null) out.write(buf, 0, r);
                        n += r;
                        if (progress != null && (n & 0xFFFFF) < r) {
                            progress.onProgress(counting.count, totalBytes, name);
                            if (progress.isCancelled()) throw new PackageException("已取消");
                        }
                    }
                } finally {
                    if (out != null) out.close();
                }
                hashes.put(name, hex(md.digest()));
                sizes.put(name, n);
                if (keep) extracted.add(name);
                if (progress != null) progress.onProgress(counting.count, totalBytes, name);
            }
        }

        // Locate the package root through files/SHA256SUMS (top level or one folder down),
        // or the MU300 layout through desktop/manifest.json + payload/tf-install-worker.sh.
        List<String> roots = new ArrayList<>();
        List<String> muRoots = new ArrayList<>();
        for (String name : hashes.keySet()) {
            if (name.equals("files/SHA256SUMS")) roots.add("");
            else if (name.matches("^[^/]+/files/SHA256SUMS$"))
                roots.add(name.substring(0, name.length() - "files/SHA256SUMS".length()));
            String mp = null;
            if (name.equals("desktop/manifest.json")) mp = "";
            else if (name.matches("^[^/]+/desktop/manifest\\.json$"))
                mp = name.substring(0, name.length() - "desktop/manifest.json".length());
            if (mp != null && hashes.containsKey(mp + "payload/tf-install-worker.sh")) muRoots.add(mp);
        }
        if (roots.size() + muRoots.size() > 1)
            throw new PackageException("ZIP 内含多个安装包目录，请只选择一个安装包");
        if (!muRoots.isEmpty())
            return inspectMu300(raw, pkg, muRoots.get(0), hashes, sizes, extracted);
        if (roots.isEmpty())
            throw new PackageException("这不是受支持的 OpenWrt 安装包：找不到 files/SHA256SUMS");
        Result res = new Result();
        res.prefix = roots.get(0);
        String px = res.prefix;

        // Parse the checksum manifest exactly as `sha256sum -c` would read it.
        File sumsFile = new File(raw, px + "files/SHA256SUMS");
        byte[] sumsBytes = readAll(sumsFile, 4 << 20);
        res.fingerprint = hex(sha256().digest(sumsBytes));
        String sumsText = new String(sumsBytes, StandardCharsets.UTF_8);
        LinkedHashMap<String, String> listed = new LinkedHashMap<>();
        List<String> listedLines = new ArrayList<>();
        for (String line : sumsText.split("\n")) {
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.isEmpty()) continue;
            int sp = line.indexOf(' ');
            if (sp != 64 || line.length() < 67) throw new PackageException("SHA256SUMS 格式异常：" + clip(line));
            String h = line.substring(0, 64).toLowerCase();
            char mode = line.charAt(65);
            if (!HEX64.matcher(h).matches() || (mode != ' ' && mode != '*'))
                throw new PackageException("SHA256SUMS 格式异常：" + clip(line));
            String file = line.substring(66);
            checkSafeName(file);
            if (listed.put(file, h) != null) throw new PackageException("SHA256SUMS 重复列出：" + file);
            listedLines.add(line); // original text: the device checks subset lines against the full list
        }
        res.listedFiles = listed.size();
        if (listed.isEmpty()) throw new PackageException("SHA256SUMS 为空");

        List<String> missing = new ArrayList<>();
        List<String> damaged = new ArrayList<>();
        for (Map.Entry<String, String> it : listed.entrySet()) {
            String actual = hashes.get(px + it.getKey());
            if (actual == null) missing.add(it.getKey());
            else if (!actual.equals(it.getValue())) damaged.add(it.getKey());
            else res.verifiedFiles++;
        }
        if (!damaged.isEmpty())
            throw new PackageException("文件校验失败（已损坏或被修改）：" + join(damaged, 5) + "；请重新下载安装包");
        if (!missing.isEmpty())
            throw new PackageException("安装包不完整，缺少：" + join(missing, 5));

        // Anything extra inside the installation subset is refused; elsewhere only reported.
        List<String> unlistedSubset = new ArrayList<>();
        int unlistedOther = 0;
        for (String name : hashes.keySet()) {
            if (!name.startsWith(px)) { unlistedOther++; continue; }
            String rel = name.substring(px.length());
            if (rel.equals("files/SHA256SUMS") || listed.containsKey(rel)) continue;
            if (SUBSET.matcher(rel).matches()) unlistedSubset.add(rel); else unlistedOther++;
        }
        if (!unlistedSubset.isEmpty())
            throw new PackageException("安装文件中有未被 SHA256SUMS 登记的文件，拒绝安装：" + join(unlistedSubset, 5));
        if (unlistedOther > 0)
            res.warnings.add(unlistedOther + " 个与安装无关的额外文件未登记校验（已忽略，不会提取）");

        // Move the verified installation subset into pkg/.
        if (!pkg.mkdirs()) throw new IOException("cannot create " + pkg);
        List<String> subsetNames = new ArrayList<>();
        for (String name : extracted) {
            if (!name.startsWith(px)) continue;
            String rel = name.substring(px.length());
            if (!SUBSET.matcher(rel).matches()) continue;
            File from = new File(raw, name);
            File to = new File(pkg, rel);
            File parent = to.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdir " + parent);
            if (!from.renameTo(to)) throw new IOException("cannot move " + rel);
            subsetNames.add(rel);
            res.extractedFiles++;
            res.extractedBytes += sizes.get(name);
        }
        deleteTree(raw);
        for (String req : REQUIRED) {
            if (!new File(pkg, req).isFile())
                throw new PackageException("不支持的安装包格式：缺少 " + req + "（仅支持带 Magisk/TF 设备端安装脚本的完整包）");
        }

        // The device-side `sha256sum -c files/SHA256SUMS` then checks exactly the
        // extracted subset, using the package's own (already verified) hashes.
        File sums = new File(pkg, "files/SHA256SUMS");
        writeFile(new File(pkg, "files/SHA256SUMS.full"), sumsBytes);
        StringBuilder subsetSums = new StringBuilder();
        for (String line : listedLines) {
            String file = line.substring(66);
            if (subsetNames.contains(file)) subsetSums.append(line).append('\n');
        }
        writeFile(sums, subsetSums.toString().getBytes(StandardCharsets.UTF_8));

        readMetadata(pkg, res, hashes, px);
        res.packageDir = pkg;
        return res;
    }

    private static void readMetadata(File pkg, Result res, Map<String, String> hashes, String px)
            throws IOException, PackageException {
        res.env = parseEnv(new String(readAll(new File(pkg, "files/tf-install.env"), 1 << 16), StandardCharsets.UTF_8));
        String model = res.env.get("ANDROID_MODEL");
        res.profile = res.env.containsKey("PROFILE") ? res.env.get("PROFILE") : "";
        res.defaultSsid = res.env.containsKey("DEFAULT_SSID") ? res.env.get("DEFAULT_SSID") : "";
        res.rawBytes = parseLong(res.env.get("RAW_BYTES"), "RAW_BYTES");
        res.bootBytes = parseLong(res.env.get("BOOT_BYTES"), "BOOT_BYTES");
        if (model == null || model.isEmpty()) throw new PackageException("tf-install.env 缺少 ANDROID_MODEL");

        File profileFile = new File(pkg, "files/device-profile.json");
        if (profileFile.isFile()) {
            try {
                Object o = MiniJson.parse(new String(readAll(profileFile, 1 << 16), StandardCharsets.UTF_8));
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                Object p = m.get("profile");
                Object models = m.get("android_models");
                if (!(p instanceof String) || !(models instanceof List) || ((List<?>) models).isEmpty())
                    throw new PackageException("device-profile.json 无效");
                if (!res.profile.isEmpty() && !res.profile.equals(p))
                    throw new PackageException("device-profile.json 与 tf-install.env 机型不一致");
                res.profile = (String) p;
                for (Object x : (List<?>) models) if (x instanceof String) res.androidModels.add((String) x);
            } catch (ClassCastException | IllegalArgumentException ex) {
                throw new PackageException("device-profile.json 无法解析：" + ex.getMessage());
            }
        }
        if (res.androidModels.isEmpty()) res.androidModels.add(model);
        if (!res.androidModels.contains(model))
            throw new PackageException("tf-install.env 的机型 " + model + " 不在 device-profile.json 中");
        // Desktop package_profile(): a K1 package must name exactly ['angelcare K1'].
        if ("k1".equals(res.profile) && !(res.androidModels.size() == 1 && "angelcare K1".equals(res.androidModels.get(0))))
            throw new PackageException("K1 安装包的机型标识不正确（应为 angelcare K1）");
        // The device installer compares getprop ro.product.model with ANDROID_MODEL only.
        if (res.androidModels.size() > 1) {
            res.warnings.add("设备端脚本只接受型号 " + model + "（device-profile.json 还列出了其他型号）");
            res.androidModels.clear();
            res.androidModels.add(model);
        }
        // Legacy E5 packages carried no profile manifest; the desktop installer treats them as e5.
        String prof = res.profile.isEmpty() ? "e5" : res.profile;
        DeviceRule rule = rule(prof);
        if (rule == null)
            throw new PackageException("不支持的机型 profile “" + prof + "”。本应用适配：" + supportedTitles());
        if (!rule.model.equals(model))
            res.warnings.add("安装包型号 " + model + " 与该 profile 常见型号 " + rule.model + " 不同；以安装包为准");
        res.title = rule.title;
        res.board = rule.board;
        res.unlockRequired = rule.unlockRequired;

        File version = new File(pkg, "files/VERSION");
        if (version.isFile()) res.version = new String(readAll(version, 4096), StandardCharsets.UTF_8).trim();
        File release = new File(pkg, "files/RELEASE.json");
        if (release.isFile()) {
            try {
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>)
                        MiniJson.parse(new String(readAll(release, 1 << 16), StandardCharsets.UTF_8));
                res.openwrtVersion = str(m.get("openwrt"));
                res.kernelRelease = str(m.get("kernel_release"));
                res.buildTime = str(m.get("build_time_utc"));
                if (res.version.isEmpty()) res.version = str(m.get("package_name"));
            } catch (RuntimeException ex) {
                res.warnings.add("RELEASE.json 无法解析");
            }
        }
        File prop = new File(pkg, "module.prop");
        if (prop.isFile()) {
            Map<String, String> m = parseEnv(new String(readAll(prop, 1 << 16), StandardCharsets.UTF_8));
            res.moduleName = m.containsKey("name") ? m.get("name") : "";
            res.moduleVersion = m.containsKey("version") ? m.get("version") : "";
        }

        // Same static checks the desktop installer performs before using the files.
        validateStaticArm64(new File(pkg, "files/busybox"));
        File boot = new File(pkg, "files/boot.img");
        if (boot.length() != res.bootBytes) throw new PackageException("boot.img 大小与 BOOT_BYTES 不一致");
        String bootSha = hashes.get(px + "files/boot.img");
        String envBoot = res.env.get("BOOT_SHA256");
        if (envBoot != null && !envBoot.equalsIgnoreCase(bootSha))
            throw new PackageException("boot.img 与 tf-install.env 的 BOOT_SHA256 不一致");
        try {
            @SuppressWarnings("unchecked") Map<String, Object> bj = (Map<String, Object>)
                    MiniJson.parse(new String(readAll(new File(pkg, "files/boot.json"), 1 << 20), StandardCharsets.UTF_8));
            if (!String.valueOf(bj.get("sha256")).equalsIgnoreCase(bootSha))
                throw new PackageException("boot.img 与 boot.json 不一致");
        } catch (ClassCastException | IllegalArgumentException ex) {
            throw new PackageException("boot.json 无法解析");
        }
        long isize = gzipTrailerSize(new File(pkg, "files/openwrt.ext4.gz"));
        if (isize != (res.rawBytes & 0xFFFFFFFFL))
            throw new PackageException("openwrt.ext4.gz 解压大小与 RAW_BYTES 不一致");
        if (res.rawBytes < (64L << 20)) throw new PackageException("RAW_BYTES 异常");
    }

    /** Port of OpenWrt-Installer.py validate_installer_busybox(). */
    static void validateStaticArm64(File f) throws IOException, PackageException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[64];
            r.readFully(h);
            byte[] magic = {0x7f, 'E', 'L', 'F', 2, 1, 1};
            if (!Arrays.equals(Arrays.copyOf(h, 7), magic) || u16(h, 16) != 2 || u16(h, 18) != 183)
                throw new PackageException("随包 BusyBox 不是静态 ARM64 可执行文件");
            long off = u64(h, 32);
            int esz = u16(h, 54), cnt = u16(h, 56);
            if (esz != 56 || cnt < 1 || cnt > 128 || off < 64 || off > r.length())
                throw new PackageException("随包 BusyBox 程序头无效");
            byte[] ph = new byte[esz * cnt];
            r.seek(off);
            r.readFully(ph);
            boolean load = false;
            for (int n = 0; n < cnt; n++) {
                long type = u32(ph, n * esz);
                if (type == 1) load = true;
                if (type == 2 || type == 3) throw new PackageException("随包 BusyBox 必须是静态链接");
            }
            if (!load) throw new PackageException("随包 BusyBox 无可加载段");
        }
    }

    static long gzipTrailerSize(File gz) throws IOException, PackageException {
        try (RandomAccessFile r = new RandomAccessFile(gz, "r")) {
            if (r.length() < 18) throw new PackageException("openwrt.ext4.gz 太小");
            byte[] head = new byte[2];
            r.readFully(head);
            if ((head[0] & 0xff) != 0x1f || (head[1] & 0xff) != 0x8b) throw new PackageException("openwrt.ext4.gz 不是 gzip");
            byte[] t = new byte[4];
            r.seek(r.length() - 4);
            r.readFully(t);
            return u32(t, 0);
        }
    }

    static Map<String, String> parseEnv(String text) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (v.length() >= 2 && ((v.startsWith("'") && v.endsWith("'")) || (v.startsWith("\"") && v.endsWith("\""))))
                v = v.substring(1, v.length() - 1);
            m.put(k, v);
        }
        return m;
    }

    private static boolean isCandidate(String name) {
        if (SUBSET.matcher(name).matches() || MU_SUBSET.matcher(name).matches() || Mu300Internal.wanted(name)) return true;
        int slash = name.indexOf('/');
        if (slash <= 0) return false;
        String rest = name.substring(slash + 1);
        return SUBSET.matcher(rest).matches() || MU_SUBSET.matcher(rest).matches() || Mu300Internal.wanted(rest);
    }

    // ------------------------------------------------------------------ MU300 layout

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o, String what) throws PackageException {
        if (!(o instanceof Map)) throw new PackageException("desktop/manifest.json 缺少 " + what);
        return (Map<String, Object>) o;
    }

    private static Result inspectMu300(File raw, File pkg, String px, Map<String, String> hashes,
                                       Map<String, Long> sizes, TreeSet<String> extracted)
            throws IOException, PackageException {
        Result res = new Result();
        res.prefix = px;
        res.layout = "mu300";
        res.tfOnly = true;
        res.unlockRequired = false;          // the MU300 scripts do not check verifiedbootstate
        res.adminUrl = "http://192.168.77.1";
        res.logDir = "/data/local/tmp/mu300-tf-install";
        byte[] mb = readAll(new File(raw, px + "desktop/manifest.json"), 4 << 20);
        res.fingerprint = hex(sha256().digest(mb));
        Map<String, Object> m;
        try {
            m = obj(MiniJson.parse(new String(mb, StandardCharsets.UTF_8)), "顶层对象");
        } catch (IllegalArgumentException ex) {
            throw new PackageException("desktop/manifest.json 无法解析：" + ex.getMessage());
        }
        if (!Long.valueOf(2).equals(m.get("format")))
            throw new PackageException("不支持的 MU300 清单格式（format=" + m.get("format") + "，需要 2）");
        Object k = m.get("kernel");
        if (!(k instanceof String) || !((String) k).matches("^[0-9]+\\.[0-9]+$"))
            throw new PackageException("desktop/manifest.json 的 kernel 无效");
        res.kernelFamily = (String) k;
        Map<String, Object> magisk = obj(m.get("magisk"), "magisk");
        Map<String, Object> desktop = obj(m.get("desktop"), "desktop");
        Map<String, Object> layout = obj(m.get("magisk_layout"), "magisk_layout");

        // Same as the desktop verify(): every magisk and desktop entry must exist and match.
        LinkedHashMap<String, String> listed = new LinkedHashMap<>();
        for (Map<String, Object> sec : java.util.Arrays.asList(magisk, desktop)) {
            for (Map.Entry<String, Object> it : sec.entrySet()) {
                String f = it.getKey();
                checkSafeName(f);
                if (!(it.getValue() instanceof String) || !HEX64.matcher((String) it.getValue()).matches())
                    throw new PackageException("desktop/manifest.json 校验值格式异常：" + clip(f));
                String h = (String) it.getValue();
                String old = listed.put(f, h);
                if (old != null && !old.equals(h)) throw new PackageException("desktop/manifest.json 对同一文件给出不同校验值：" + f);
            }
        }
        if (magisk.isEmpty()) throw new PackageException("desktop/manifest.json 的 magisk 部分为空");
        for (String f : magisk.keySet())
            if (!f.startsWith("payload/") && !f.equals("module.prop"))
                throw new PackageException("MU300 设备端文件不在 payload/ 中：" + f);
        res.listedFiles = listed.size();
        List<String> missing = new ArrayList<>();
        List<String> damaged = new ArrayList<>();
        for (Map.Entry<String, String> it : listed.entrySet()) {
            String actual = hashes.get(px + it.getKey());
            if (actual == null) missing.add(it.getKey());
            else if (!actual.equals(it.getValue())) damaged.add(it.getKey());
            else res.verifiedFiles++;
        }
        if (!damaged.isEmpty())
            throw new PackageException("文件校验失败（已损坏或被修改）：" + join(damaged, 5) + "；请重新下载安装包");
        if (!missing.isEmpty())
            throw new PackageException("安装包不完整，缺少：" + join(missing, 5));

        List<String> unlistedSubset = new ArrayList<>();
        int unlistedOther = 0;
        for (String name : hashes.keySet()) {
            if (!name.startsWith(px)) { unlistedOther++; continue; }
            String rel = name.substring(px.length());
            if (rel.equals("desktop/manifest.json") || listed.containsKey(rel)) continue;
            if (MU_SUBSET.matcher(rel).matches()) unlistedSubset.add(rel); else unlistedOther++;
        }
        if (!unlistedSubset.isEmpty())
            throw new PackageException("安装文件中有未被 desktop/manifest.json 登记的文件，拒绝安装：" + join(unlistedSubset, 5));
        if (unlistedOther > 0)
            res.warnings.add(unlistedOther + " 个与安装无关的额外文件未登记校验（已忽略，不会提取）");

        // The worker finds every file next to itself; the layout map must say so.
        for (String n : MU_REQUIRED) {
            if (!("payload/" + n).equals(layout.get(n)))
                throw new PackageException("不支持的 MU300 安装包布局：" + n + " 不在 payload/ 中");
            if (!magisk.containsKey("payload/" + n))
                throw new PackageException("不支持的 MU300 安装包：magisk 清单缺少 payload/" + n);
        }

        if (!pkg.mkdirs()) throw new IOException("cannot create " + pkg);
        StringBuilder sums = new StringBuilder();
        for (String name : extracted) {
            if (!name.startsWith(px)) continue;
            String rel = name.substring(px.length());
            if (!MU_SUBSET.matcher(rel).matches()) continue;
            File from = new File(raw, name);
            File to = new File(pkg, rel);
            File parent = to.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdir " + parent);
            if (!from.renameTo(to)) throw new IOException("cannot move " + rel);
            if (!rel.equals("desktop/manifest.json")) sums.append(listed.get(rel)).append("  ").append(rel).append('\n');
            res.extractedFiles++;
            res.extractedBytes += sizes.get(name);
        }
        // Internal-storage methods: the computer-side desktop/ files they run, exactly as pinned by this app.
        res.listedMap = listed;
        res.internalProblem = Mu300Internal.pinnedProblem(listed);
        if (res.internalProblem == null) {
            File src = new File(pkg.getParentFile(), "mu300src");
            deleteTree(src);
            for (String name : extracted) {
                if (!name.startsWith(px)) continue;
                String rel = name.substring(px.length());
                if (!Mu300Internal.wanted(rel) || !listed.containsKey(rel)) continue;   // only manifest-listed files
                File from = new File(raw, name);
                File to = new File(src, rel);
                File parent = to.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdir " + parent);
                if (!from.renameTo(to)) throw new IOException("cannot move " + rel);
            }
            res.internalSrc = src;
        }
        deleteTree(raw);
        for (String n : MU_REQUIRED)
            if (!new File(pkg, "payload/" + n).isFile()) throw new PackageException("不支持的 MU300 安装包：缺少 payload/" + n);
        // Device-side stage check: every line must appear in desktop/manifest.json, then sha256sum -c.
        writeFile(new File(pkg, "OWRT-APK-SUMS"), sums.toString().getBytes(StandardCharsets.UTF_8));
        writeFile(new File(pkg, "OWRT-APK-LAYOUT"), "mu300\n".getBytes(StandardCharsets.UTF_8));

        readMu300Metadata(pkg, res, hashes, px);
        res.packageDir = pkg;
        return res;
    }

    /** The exact deployment parameters payload/customize.sh writes; the APK helper writes the same file. */
    static Map<String, String> mu300ExpectedEnv(String kernel) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("SD_MODE", "1"); e.put("SD_DEV", "$R"); e.put("FORMAT", "1");
        e.put("SYNC_BOOTCHAIN", "${MU300_SYNC_BOOTCHAIN:-1}"); e.put("OSES", "\"openwrt\"");
        e.put("WIPE_LEGACY", "0"); e.put("UPDATE", "0"); e.put("BOOT_OS", "openwrt"); e.put("DEFAULT_LINUX", "1");
        e.put("BOOT_ATTEMPTS", "5"); e.put("IMPORT_HOTSPOT", "1"); e.put("KERNEL", kernel); e.put("PWHASH", "''");
        return e;
    }

    private static void readMu300Metadata(File pkg, Result res, Map<String, String> hashes, String px)
            throws IOException, PackageException {
        File release = new File(pkg, "desktop/release.json");
        if (!release.isFile()) throw new PackageException("MU300 安装包缺少 desktop/release.json");
        Map<String, Object> r;
        try {
            r = obj(MiniJson.parse(new String(readAll(release, 1 << 16), StandardCharsets.UTF_8)), "release");
        } catch (IllegalArgumentException ex) {
            throw new PackageException("desktop/release.json 无法解析");
        }
        res.profile = str(r.get("profile"));
        if (!"mu300".equals(res.profile))
            throw new PackageException("不支持的机型 profile “" + res.profile + "”（MU300 布局的安装包应为 mu300）");
        res.openwrtVersion = str(r.get("openwrt"));
        res.kernelRelease = str(r.get("kernel_release"));
        res.buildTime = str(r.get("build_time_utc"));
        res.version = str(r.get("package_name"));
        if (!res.kernelRelease.equals(res.kernelFamily) && !res.kernelRelease.startsWith(res.kernelFamily + "."))
            throw new PackageException("release.json 内核 " + res.kernelRelease + " 与清单内核 " + res.kernelFamily + " 不一致");
        res.title = "MU300 系列（F50 / MU300 / U30 Air / V50）· Linux " + res.kernelFamily;
        res.androidModels.addAll(java.util.Arrays.asList("F50", "MU300", "U30 Air", "V50 / MU3351"));
        File prop = new File(pkg, "module.prop");
        if (prop.isFile()) {
            Map<String, String> mp = parseEnv(new String(readAll(prop, 1 << 16), StandardCharsets.UTF_8));
            res.moduleName = mp.containsKey("name") ? mp.get("name") : "";
            res.moduleVersion = mp.containsKey("version") ? mp.get("version") : "";
        }

        // payload/customize.sh is what Magisk runs; the APK mirrors it, so it must be the adapted form.
        String cz = new String(readAll(new File(pkg, "payload/customize.sh"), 1 << 20), StandardCharsets.UTF_8);
        String open = "cat > \"$MODPATH/mu300-install.env\" <<EOF\n";
        int a = cz.indexOf(open);
        int b = a < 0 ? -1 : cz.indexOf("\nEOF\n", a + open.length());
        if (a < 0 || b < 0 || cz.indexOf(open, a + 1) >= 0)
            throw new PackageException("payload/customize.sh 与本应用已适配的 MU300 部署方式不同（找不到部署参数）");
        Map<String, String> got = new LinkedHashMap<>();
        for (String line : cz.substring(a + open.length(), b).split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) throw new PackageException("payload/customize.sh 部署参数格式异常：" + clip(line));
            got.put(line.substring(0, eq), line.substring(eq + 1));
        }
        if (!got.equals(mu300ExpectedEnv(res.kernelFamily)))
            throw new PackageException("payload/customize.sh 的部署参数与本应用已适配的 MU300 版本不同，拒绝代替执行：" + clip(got.toString()));
        for (String need : new String[]{"MU300_VENDOR_FROM_DEVICE=1 MU300_KEEP_PAYLOAD=1", "nsenter -t 1 -m", "\"$MODPATH/tf-install-worker.sh\"",
                "bootdev=/dev/block/by-name/boot_$linux_slot", "R=/dev/block/mmcblk1p1", "[ -b \"$R\" ] || R=/dev/block/mmcblk1"})
            if (!cz.contains(need))
                throw new PackageException("payload/customize.sh 与本应用已适配的 MU300 部署方式不同（" + need + "）");

        // payload.sha256 is re-checked by the worker; make sure it already agrees with the ZIP.
        String ps = new String(readAll(new File(pkg, "payload/payload.sha256"), 1 << 16), StandardCharsets.UTF_8);
        int lines = 0;
        for (String line : ps.split("\n")) {
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.isEmpty()) continue;
            if (line.length() < 67 || line.charAt(64) != ' ' || !HEX64.matcher(line.substring(0, 64)).matches())
                throw new PackageException("payload.sha256 格式异常：" + clip(line));
            String f = line.substring(66);
            checkSafeName(f);
            String actual = hashes.get(px + "payload/" + f);
            if (actual == null || !actual.equals(line.substring(0, 64)))
                throw new PackageException("payload.sha256 与安装包内容不一致：" + f);
            lines++;
        }
        if (lines == 0) throw new PackageException("payload.sha256 为空");
        String bootSha = hashes.get(px + "payload/boot-linux.img");
        String bs = new String(readAll(new File(pkg, "payload/boot-linux.sha256"), 4096), StandardCharsets.UTF_8).trim();
        if (!bs.equals(bootSha)) throw new PackageException("boot-linux.img 与 boot-linux.sha256 不一致");
        File boot = new File(pkg, "payload/boot-linux.img");
        res.bootBytes = boot.length();
        try (RandomAccessFile rf = new RandomAccessFile(boot, "r")) {
            byte[] h = new byte[8];
            rf.readFully(h);
            if (!new String(h, StandardCharsets.US_ASCII).equals("ANDROID!")) throw new PackageException("boot-linux.img 不是 Android 启动镜像");
        }
        String req = new String(readAll(new File(pkg, "payload/rootfs.requirements"), 4096), StandardCharsets.UTF_8).trim();
        if (!req.matches("^[0-9]+ [0-9]+$")) throw new PackageException("rootfs.requirements 格式异常");
        res.requiredKib = Long.parseLong(req.split(" ")[0]);
        if (res.requiredKib <= 0) throw new PackageException("rootfs.requirements 无效");
        validateStaticArm64(new File(pkg, "payload/busybox"));
        File tgz = new File(pkg, "payload/mu300-openwrt.tar.gz");
        try (RandomAccessFile rf = new RandomAccessFile(tgz, "r")) {
            if (rf.length() < 18 || rf.read() != 0x1f || rf.read() != 0x8b) throw new PackageException("mu300-openwrt.tar.gz 不是 gzip");
        }
        res.rawBytes = 0;
    }

    static void checkSafeName(String name) throws PackageException {
        if (name.isEmpty() || name.length() > 1024 || name.startsWith("/") || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0)
            throw new PackageException("ZIP 内有不安全的文件名，拒绝处理：" + clip(name));
        for (String part : name.split("/", -1)) {
            if (part.equals("..") || part.equals(".") || (part.isEmpty()))
                throw new PackageException("ZIP 内有不安全的路径，拒绝处理：" + clip(name));
        }
    }

    private static long parseLong(String v, String key) throws PackageException {
        try {
            long n = Long.parseLong(v == null ? "" : v.trim());
            if (n <= 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException ex) {
            throw new PackageException("tf-install.env 中 " + key + " 无效");
        }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static String clip(String s) { return s.length() > 80 ? s.substring(0, 80) + "…" : s; }

    private static String join(List<String> l, int max) {
        StringBuilder b = new StringBuilder();
        for (int n = 0; n < l.size() && n < max; n++) { if (n > 0) b.append(", "); b.append(l.get(n)); }
        if (l.size() > max) b.append(" 等 ").append(l.size()).append(" 个");
        return b.toString();
    }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String hex(byte[] d) {
        StringBuilder b = new StringBuilder(d.length * 2);
        for (byte x : d) b.append(String.format("%02x", x & 0xff));
        return b.toString();
    }

    private static int u16(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }
    private static long u32(byte[] b, int o) { return (u16(b, o) | (long) u16(b, o + 2) << 16) & 0xFFFFFFFFL; }
    private static long u64(byte[] b, int o) { return u32(b, o) | u32(b, o + 4) << 32; }

    static byte[] readAll(File f, int max) throws IOException, PackageException {
        if (f.length() > max) throw new PackageException(f.getName() + " 过大");
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r < 0) throw new IOException("short read " + f);
                off += r;
            }
        }
        return b;
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
    }

    public static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public static String readText(File f) throws IOException {
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
        }
        return b.toString();
    }

    private static final class CountingStream extends FilterInputStream {
        long count;
        CountingStream(InputStream in) { super(in); }
        @Override public int read() throws IOException { int r = super.read(); if (r >= 0) count++; return r; }
        @Override public int read(byte[] b, int o, int l) throws IOException {
            int r = super.read(b, o, l); if (r > 0) count += r; return r;
        }
        @Override public long skip(long n) throws IOException { long r = super.skip(n); count += r; return r; }
    }
}
