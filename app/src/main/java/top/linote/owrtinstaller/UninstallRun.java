package top.linote.owrtinstaller;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Port of backend/uninstall.py Uninstaller (arm, run, rearm_growth, recover_arm, finish, finish_boot).
 * The record (record.json + snapshots + backup/) lives in an app folder per uninstall; every write
 * on the target is wrapped in an "effect" saved as pending before and done after, so an interrupted
 * step is re-verified by readback instead of being replayed blindly.
 */
final class UninstallRun {
    private UninstallRun() { }

    static final String RECORD = "record.json";
    static final String TMP = "/data/local/tmp/openwrt-uninstall-";
    static final String[] SNAPSHOTS = {"before-first", "before-last", "after-first", "after-last"};
    static final String BOOTCHAIN_MU300[] = {"boot", "uboot", "sml", "teecfg", "trustos", "vbmeta"};
    static final int ARM_SILENCE_S = 900;

    /** One-line result for the UI: pending = more work after the target reboots / reconnects. */
    static final class Outcome {
        final boolean pending, complete; final String message, next;
        Outcome(boolean pending, boolean complete, String message, String next) {
            this.pending = pending; this.complete = complete; this.message = message; this.next = next;
        }
    }

    // ================================================================== record store

    static final class Rec {
        final File dir; final Map<String, Object> r;
        Rec(File dir, Map<String, Object> r) { this.dir = dir; this.r = r; }
        @SuppressWarnings("unchecked") Map<String, Object> plan() { return (Map<String, Object>) r.get("plan"); }
        @SuppressWarnings("unchecked") Map<String, Object> meta() { return (Map<String, Object>) r.get("uninstall"); }
        @SuppressWarnings("unchecked") Map<String, Object> effects() {
            Object e = r.get("effects");
            if (!(e instanceof Map)) { e = new LinkedHashMap<String, Object>(); r.put("effects", e); }
            return (Map<String, Object>) e;
        }
        String s(String k) { return Uninstall.str(r.get(k)); }
        boolean b(String k) { return Boolean.TRUE.equals(r.get(k)); }
        File backup() { return new File(dir, "backup"); }
        void save() throws IOException {
            r.put("updated", System.currentTimeMillis() / 1000);
            File tmp = new File(dir, RECORD + ".tmp");
            Mu300Internal.writeAll(tmp, Uninstall.Json.write(r, false).getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(new File(dir, RECORD))) throw new IOException("无法保存卸载记录 " + dir);
        }
        void stage(String stage, Mu300Internal.Io io, String what) throws IOException {
            r.put("stage", stage);
            save();
            if (what != null) io.log(what);
        }
    }

    @SuppressWarnings("unchecked")
    static Rec load(File dir) throws IOException {
        Object o = MiniJson.parse(new String(Mu300Internal.readAll(new File(dir, RECORD)), StandardCharsets.UTF_8));
        if (!(o instanceof Map)) throw new IOException("卸载记录已损坏：" + dir);
        return new Rec(dir, (Map<String, Object>) o);
    }
    /** The newest unfinished uninstall record under root, or null. */
    static File pending(File root) {
        File[] all = root == null ? null : root.listFiles();
        if (all == null) return null;
        File best = null;
        for (File d : all) {
            if (!new File(d, RECORD).isFile()) continue;
            try {
                Rec rec = load(d);
                if (rec.b("complete") || rec.b("abandoned")) continue;
                if (best == null || d.getName().compareTo(best.getName()) > 0) best = d;
            } catch (IOException | RuntimeException ignored) { }
        }
        return best;
    }

    // ================================================================== start (persist + arm)

    /**
     * Saves a new record from a fresh prepare() and arms the offline removal. For "reset" the
     * caller must pass the fingerprint the user approved; it must match this prepare() exactly.
     */
    static Outcome start(Mu300Internal.Io io, File root, Uninstall.Prepared p, Uninstall.Pkg pkg,
                         String approvedFingerprint, boolean local) throws IOException {
        if (p.wipe) {
            if (local) throw new Uninstall.Refused("本机模式不能执行需要恢复出厂的卸载：恢复出厂会清空本应用保存的备份与卸载记录，无法继续收尾。请改用另一台手机通过 USB 或局域网操作。");
            if (p.fingerprint == null || !p.fingerprint.equals(approvedFingerprint))
                throw new Uninstall.Refused("恢复出厂确认已失效：设备状态与检查时不同，请重新检查并重新确认。（Reset approval does not match this removal plan）");
        }
        String id = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date())
                + "-" + p.device.s.cid.substring(24);
        File dir = new File(root, id);
        if (dir.exists() || !dir.mkdirs()) throw new IOException("无法创建卸载记录目录 " + dir);
        LinkedHashMap<String, Object> r = new LinkedHashMap<>();
        r.put("version", 1L);
        r.put("id", id);
        r.put("family", pkg.family);
        r.put("profile", p.device.profile);
        r.put("model", p.device.model);
        r.put("cid", p.device.s.cid);
        r.put("slot", p.device.slot);
        r.put("transport", io.transport());
        r.put("boot_id", p.device.bootId);
        r.put("stage", "uninstall_plan");
        r.put("created", System.currentTimeMillis() / 1000);
        LinkedHashMap<String, Object> plan = new LinkedHashMap<>();
        plan.put("method", "uninstall");
        plan.put("target", p.device.s.root);
        plan.put("wipe", p.wipe);
        plan.put("two_stage", true);
        plan.put("offline", true);
        plan.put("data_action", p.action);
        plan.put("preservation_error", p.reason);
        plan.put("grow", false);
        plan.put("openwrt_bytes", p.openwrtBytes);
        plan.put("android_bytes", p.androidBytes);
        plan.put("previous_android_bytes", p.previousAndroidBytes);
        plan.put("reclaimed_bytes", p.reclaimed());
        plan.put("layout_source", "device");
        plan.put("released_unallocated_bytes", p.released());
        r.put("plan", plan);
        LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
        meta.put("version", 3L);
        meta.put("action", p.action);
        meta.put("reset_authorized", p.wipe);
        meta.put("total", p.total);
        meta.put("front", p.front);
        meta.put("tail", p.tail);
        meta.put("tool_hashes", new LinkedHashMap<String, Object>(p.toolHashes));
        meta.put("root_start", p.rootStart);
        meta.put("root_size", p.rootSize);
        meta.put("nodes", new LinkedHashMap<String, Object>(p.nodes));
        meta.put("volume", p.volume.map());
        meta.put("installation_evidence", p.proof);
        LinkedHashMap<String, Object> sums = new LinkedHashMap<>();
        byte[][] snaps = {p.before[0], p.before[1], p.after[0], p.after[1]};
        for (int i = 0; i < 4; i++) {
            Mu300Internal.writeAll(new File(dir, SNAPSHOTS[i] + ".bin"), snaps[i]);
            sums.put(SNAPSHOTS[i], Mu300Boot.sha256(snaps[i]));
        }
        meta.put("checksums", sums);
        r.put("uninstall", meta);
        r.put("effects", new LinkedHashMap<String, Object>());
        r.put("completed", new ArrayList<Object>());
        r.put("unsafe", false);
        r.put("uninstall_armed", false);
        Rec rec = new Rec(dir, r);
        rec.save();
        io.log("卸载记录：" + dir.getAbsolutePath());
        return run(io, rec, pkg);
    }

    // ================================================================== run (one pass per connection)

    /**
     * run(): one pass of the original loop. Returns pending while the target must reboot / come
     * back; the caller asks the user to reconnect and calls resume() again.
     */
    static Outcome resume(Mu300Internal.Io io, File dir, Uninstall.Pkg pkg) throws IOException {
        return run(io, load(dir), pkg);
    }

    @SuppressWarnings("unchecked")
    static Outcome run(Mu300Internal.Io io, Rec rec, Uninstall.Pkg pkg) throws IOException {
        if (rec.b("complete")) return new Outcome(false, true, "卸载已完成。", null);
        if (!pkg.family.equals(rec.s("family"))) throw new Uninstall.Refused("安装包机型系列与卸载记录不一致（" + rec.s("family") + "）");
        Map<String, Object> effect = (Map<String, Object>) rec.effects().get("uninstall-arm");
        if (effect != null && !"done".equals(Uninstall.str(effect.get("state")))) recoverArm(io, rec, pkg);
        if (!rec.b("uninstall_armed")) {
            Uninstall.Device d = ready(io, rec, pkg);
            if (!d.booted) throw new Uninstall.Refused("Android/root is not ready; no uninstall request written / Android 或 root 尚未就绪，未写入卸载请求");
            if (!"before".equals(layoutState(io, rec, d)))
                throw new Uninstall.Refused("Unconfirmed layout changed; create a new plan / 分区布局已变化，请重新生成卸载计划");
            arm(io, rec, pkg, d);
            return new Outcome(true, false, "已写入离线卸载请求，目标设备正在重启进入离线卸载。",
                    "等目标设备回到 Android（离线卸载完成后会自动重启；如选择了恢复出厂，还会先进入 Recovery 清除数据）后，重新连接目标设备，再到「高级功能」点「继续卸载」完成收尾检查。");
        }
        Uninstall.Device d = ready(io, rec, pkg);
        if (!d.booted) return new Outcome(true, false, "目标设备的 Android 尚未启动完成。", "等 Android 完全启动后再点「继续卸载」。");
        String wait = rec.s("uninstall_wait_boot_id");
        if (wait.isEmpty()) wait = rec.s("boot_id");
        if (d.bootId.equals(wait)) {
            rec.stage("uninstall_offline", io, null);
            return new Outcome(true, false, "目标设备还没有重启进入离线卸载。",
                    "请手动重启目标设备（不要进 Recovery），等它回到 Android 后重新连接，再点「继续卸载」。");
        }
        if ("before".equals(layoutState(io, rec, d)))
            throw new Uninstall.Refused("Android returned before offline removal completed. Keep backups and export diagnostics. / Android 已返回但离线卸载未完成，请保留备份并导出诊断。");
        if (rec.b("uninstall_growth_pending")) {
            if (rearmGrowth(io, rec, pkg, d))
                return new Outcome(true, false, "离线 data 扩容尚未完成，已重新写入扩容请求，目标设备正在重启。",
                        "等目标设备回到 Android 后重新连接，再点「继续卸载」。");
        }
        return finish(io, rec, pkg, d);
    }

    /** ready(): probe and bind to the record's device (eMMC CID, slot). */
    static Uninstall.Device ready(Mu300Internal.Io io, Rec rec, Uninstall.Pkg pkg) throws IOException {
        Uninstall.Device d = Uninstall.probe(io, pkg.rootName(), rec.s("profile"));
        // rebind_uninstall: another connection may continue only for the same cid, profile and slot.
        String mismatch = Rebind.diff("eMMC CID", rec.s("cid"), d.s.cid, "机型配置", rec.s("profile"), d.profile,
                "Android 槽位", "_" + rec.s("slot"), "_" + d.slot);
        if (Rebind.apply(rec.r, io.transport(), io.ready(), mismatch, System.currentTimeMillis() / 1000)) {
            rec.save();
            if (rec.r.get("transport_history") != null) io.log("已改用新的连接继续：" + io.transport() + "（设备身份核对一致，已记入连接历史）");
        }
        if (!d.s.cid.equals(rec.s("cid"))) throw new Uninstall.Refused("目标设备与卸载记录不是同一台（eMMC CID 不同），已停止。");
        if (!d.slot.equals(rec.s("slot"))) throw new Uninstall.Refused("目标设备当前 Android 槽位与卸载记录不同（记录 _" + rec.s("slot") + "，现在 _" + d.slot + "），已停止，请保留备份。");
        return d;
    }

    // ================================================================== layout state

    static byte[] snapshot(Rec rec, String name) throws IOException {
        byte[] b = Mu300Internal.readAll(new File(rec.dir, name + ".bin"));
        @SuppressWarnings("unchecked") Map<String, Object> sums = (Map<String, Object>) rec.meta().get("checksums");
        if (sums == null || !Mu300Boot.sha256(b).equals(Uninstall.str(sums.get(name))))
            throw new Uninstall.Refused("卸载记录中的分区表快照已损坏：" + name);
        return b;
    }
    /** current() + layout_state(): "before", "after" or refuse. */
    static String layoutState(Mu300Internal.Io io, Rec rec, Uninstall.Device d) throws IOException {
        Map<String, Object> m = rec.meta();
        long front = Uninstall.lng(m.get("front")), tail = Uninstall.lng(m.get("tail")), total = Uninstall.lng(m.get("total"));
        if (total != d.s.diskSize) throw new Uninstall.Refused("eMMC 容量与卸载记录不一致");
        String disk = "/dev/block/" + d.partitionNode();
        byte[] first = Mu300Internal.read(io, "dd if=" + disk + " bs=512 count=" + front + " 2>/dev/null", front * 512);
        byte[] last = Mu300Internal.read(io, "dd if=" + disk + " bs=512 skip=" + tail + " count=" + (total - tail) + " 2>/dev/null", (total - tail) * 512);
        if (Arrays.equals(first, snapshot(rec, "after-first")) && Arrays.equals(last, snapshot(rec, "after-last"))) return "after";
        if (Arrays.equals(first, snapshot(rec, "before-first")) && Arrays.equals(last, snapshot(rec, "before-last"))) return "before";
        throw new Uninstall.Refused("GPT is changed or partially written. Keep backups; offline recovery is required. / GPT 已变化或写入不完整，请保留备份并进行离线恢复；不会自动重跑卸载。");
    }

    // ================================================================== backup

    /** adapter.backup(): identity, misc head, both GPT copies, both boot slots (+ MU300 bootchain). */
    static void backup(Mu300Internal.Io io, Rec rec, Uninstall.Device d) throws IOException {
        File path = rec.backup();
        if (path.exists()) {
            File old = new File(rec.dir, "backup-" + System.currentTimeMillis());
            if (!path.renameTo(old)) throw new IOException("无法保留旧备份 " + path);
            io.log("保留上一次的备份：" + old.getName());
        }
        if (!path.mkdirs()) throw new IOException("无法创建备份目录 " + path);
        String disk = "/dev/block/" + d.partitionNode();
        Map<String, Object> m = rec.meta();
        long front = Uninstall.lng(m.get("front")), tail = Uninstall.lng(m.get("tail")), total = Uninstall.lng(m.get("total"));
        LinkedHashMap<String, String> items = new LinkedHashMap<>();
        LinkedHashMap<String, Long> sizes = new LinkedHashMap<>();
        items.put("misc-head.bin", "dd if=/dev/block/by-name/misc bs=4096 count=1"); sizes.put("misc-head.bin", 4096L);
        items.put("gpt-first.bin", "dd if=" + disk + " bs=512 count=" + front); sizes.put("gpt-first.bin", front * 512);
        items.put("gpt-last.bin", "dd if=" + disk + " bs=512 skip=" + tail + " count=" + (total - tail)); sizes.put("gpt-last.bin", (total - tail) * 512);
        List<String> parts = new ArrayList<>();
        if ("mu300".equals(rec.s("family"))) parts.addAll(Arrays.asList(BOOTCHAIN_MU300)); else parts.add("boot");
        for (String n : parts) for (String sl : new String[]{"a", "b"}) {
            String name = n + "_" + sl;
            String v = io.sh("blockdev --getsize64 /dev/block/by-name/" + name, true).trim();
            if (!Uninstall.NUM.matcher(v).matches()) throw new IOException("无法读取分区大小：" + name);
            items.put(name + ".img", "cat /dev/block/by-name/" + name);
            sizes.put(name + ".img", Long.parseLong(v));
        }
        long all = 0;
        for (long s : sizes.values()) all += s;
        io.log("备份 " + items.size() + " 项到本机，合计 " + Mu300Internal.human(all) + "：" + path);
        StringBuilder sums = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, String> it : items.entrySet()) {
            String n = it.getKey();
            i++;
            io.step("备份 " + n + "（第 " + i + "/" + items.size() + " 个）", false);
            io.log("正在备份 第 " + i + "/" + items.size() + " 个：" + n + "（" + Mu300Internal.human(sizes.get(n)) + "）");
            File f = new File(path, n);
            Mu300Internal.download(io, it.getValue() + " 2>/dev/null", f, sizes.get(n), n);
            sums.append(Mu300Internal.hashFile(f)).append("  ").append(n).append('\n');
        }
        Uninstall.space(Mu300Internal.readAll(new File(path, "gpt-first.bin")), Mu300Internal.readAll(new File(path, "gpt-last.bin")), total);
        Mu300Internal.writeAll(new File(path, "SHA256SUMS"), sums.toString().getBytes(StandardCharsets.UTF_8));
        String id = "{\n  \"cid\": \"" + d.s.cid + "\",\n  \"slot\": \"_" + d.slot + "\",\n  \"model\": \""
                + d.model.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\n  \"profile\": \"" + Uninstall.str(d.profile) + "\"\n}\n";
        Mu300Internal.writeAll(new File(path, "identity.json"), id.getBytes(StandardCharsets.UTF_8));
    }
    static Map<String, String> sums(File dir) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        File f = new File(dir, "SHA256SUMS");
        if (!f.isFile()) return out;
        for (String line : new String(Mu300Internal.readAll(f), StandardCharsets.UTF_8).split("\n")) {
            int sp = line.indexOf("  ");
            if (sp == 64) out.put(line.substring(66), line.substring(0, 64));
        }
        return out;
    }
    /** A backup file whose sha256 matches its SHA256SUMS row, or a refusal. */
    static byte[] backupFile(Rec rec, String name, String damaged) throws IOException {
        File f = new File(rec.backup(), name);
        String want = sums(rec.backup()).get(name);
        if (!f.isFile() || want == null) throw new Uninstall.Refused(damaged);
        byte[] b = Mu300Internal.readAll(f);
        if (!Mu300Boot.sha256(b).equals(want)) throw new Uninstall.Refused(damaged);
        return b;
    }

    // ================================================================== arm

    @SuppressWarnings("unchecked")
    static void arm(Mu300Internal.Io io, Rec rec, Uninstall.Pkg pkg, Uninstall.Device d) throws IOException {
        Map<String, Object> m = rec.meta();
        String slot = d.slot, other = d.other;
        boolean wipe = Boolean.TRUE.equals(rec.plan().get("wipe"));
        String action = Uninstall.str(m.get("action"));
        rec.stage("uninstall_check", io, "▶ 卸载前复核（只读）");
        Mu300Gpt.Space info = Uninstall.space(snapshot(rec, "before-first"), snapshot(rec, "before-last"), Uninstall.lng(m.get("total")));
        Mu300Gpt.Part root = Uninstall.partition(info, pkg.rootName());
        byte[] originalBoot = null;
        String retry = rec.s("uninstall_retry_linux_sha256");
        if (!retry.isEmpty()) {
            originalBoot = backupFile(rec, "boot_" + other + ".img", "Original Linux boot backup is damaged / 原 Linux 启动备份已损坏");
            if (originalBoot.length != UninstallBoot.BOOT_BYTES) throw new Uninstall.Refused("Original Linux boot backup is damaged / 原 Linux 启动备份已损坏");
        }
        Map<String, Object> proof = Uninstall.evidence(io, d, pkg, root, originalBoot);
        if (!Uninstall.Json.write(proof, true).equals(Uninstall.Json.write(m.get("installation_evidence"), true)))
            throw new Uninstall.Refused("Installed OpenWrt changed; create a new removal plan / 设备上的 OpenWrt 已变化，请重新生成卸载计划");
        LinkedHashMap<String, byte[]> tools = new LinkedHashMap<>();
        if (action.startsWith("grow-")) {
            Uninstall.Volume v = Uninstall.androidVolume(io, d, false);
            Uninstall.eligible(d, v);
            Uninstall.Volume saved = Uninstall.Volume.of((Map<String, Object>) m.get("volume"));
            if (!v.uuid.equals(saved.uuid) || !v.source.equals(saved.source) || !v.filesystem.equals(saved.filesystem) || v.filesystemBytes != saved.filesystemBytes)
                throw new Uninstall.Refused("Android data changed; create a new removal plan / Android data 已变化，请重新生成卸载计划");
            tools = Uninstall.growthTools(pkg, v.filesystem);
            TreeMap<String, Object> hashes = new TreeMap<>();
            for (Map.Entry<String, byte[]> e : tools.entrySet()) hashes.put(e.getKey(), Mu300Boot.sha256(e.getValue()));
            if (!Uninstall.Json.write(hashes, true).equals(Uninstall.Json.write(m.get("tool_hashes"), true)))
                throw new Uninstall.Refused("Preservation tools changed; review removal plan / 保数据工具已变化，需重新核对卸载计划");
        }
        if (retry.isEmpty()) {
            rec.stage("uninstall_backup", io, "▶ 备份分区表、misc 与启动分区（写入前）");
            backup(io, rec, d);
        }
        rec.stage("uninstall_prepare", io, "▶ 生成离线卸载请求");
        if (!"before".equals(layoutState(io, rec, d))) throw new Uninstall.Refused("Layout changed during backup / 备份期间分区布局发生变化");
        byte[] misc = backupFile(rec, "misc-head.bin", "Interrupted uninstall backups are damaged / 中断卸载的备份损坏");
        boolean pendingRecovery = false;
        for (int i = 0; i < 32; i++) if (misc[i] != 0) pendingRecovery = true;
        if (misc.length != 4096 || pendingRecovery) throw new Uninstall.Refused("A recovery operation is already pending / 设备已有待执行的 Recovery 操作");
        byte[] control = Arrays.copyOfRange(misc, 2048, 2080);
        byte[] trial, android;
        try {
            trial = UninstallBoot.trialControl(control, slot);
            android = UninstallBoot.androidControl(control, slot);
        } catch (IllegalArgumentException e) { throw new Uninstall.Refused(e.getMessage()); }
        byte[] bcb = new byte[2048];
        System.arraycopy("boot-recovery".getBytes(StandardCharsets.US_ASCII), 0, bcb, 0, 13);
        byte[] message = "recovery\n--wipe_data\n--reason=openwrt-uninstall\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(message, 0, bcb, 64, message.length);
        File bootS = new File(rec.backup(), "boot_" + slot + ".img"), bootO = new File(rec.backup(), "boot_" + other + ".img");
        long bootBytes = bootS.length();
        if (bootBytes != UninstallBoot.BOOT_BYTES || bootO.length() != bootBytes) throw new Uninstall.Refused("Unsupported boot partition sizes / 启动分区大小不受支持");
        byte[] androidBoot = backupFile(rec, bootS.getName(), "Interrupted uninstall backups are damaged / 中断卸载的备份损坏");
        String androidSha = Mu300Boot.sha256(androidBoot);
        byte[] androidHeader = Arrays.copyOf(androidBoot, 4096);
        androidBoot = null;
        byte[] linuxHeader = Arrays.copyOf(Mu300Internal.readAll(bootO), 4096);
        if (!new String(androidHeader, 0, 8, StandardCharsets.ISO_8859_1).equals("ANDROID!") || Arrays.equals(androidHeader, linuxHeader))
            throw new Uninstall.Refused("Current Android boot cannot be distinguished from Linux / 无法区分当前 Android 与 Linux 启动镜像");
        Map<String, Object> nodes = (Map<String, Object>) m.get("nodes");
        Uninstall.Volume volume = Uninstall.Volume.of((Map<String, Object>) m.get("volume"));
        List<Object> request = new ArrayList<>(Arrays.asList(d.s.cid, d.partitionNode(), nodes.get("userdata"), nodes.get(pkg.rootName()),
                nodes.get("misc"), nodes.get("boot_" + slot), nodes.get("boot_" + other)));
        for (String k : new String[]{"total", "front", "tail", "root_start", "root_size"}) request.add(Uninstall.lng(m.get(k)));
        request.add(wipe ? 1L : 0L);
        request.add(bootBytes);
        request.add(androidSha);
        request.add(action);
        request.add(d.s.start);
        request.add(Uninstall.lng(rec.plan().get("android_bytes")) / 512);
        request.add(volume.filesystemBytes);
        request.add(volume.uuid.isEmpty() ? "00000000000000000000000000000000" : volume.uuid);
        StringBuilder req = new StringBuilder();
        for (int i = 0; i < request.size(); i++) req.append(i > 0 ? ":" : "").append(request.get(i));
        req.append('\n');
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        for (String n : SNAPSHOTS) files.put("openwrt-uninstall/" + n, snapshot(rec, n));
        files.putAll(tools);
        files.put("openwrt-uninstall/request", req.toString().getBytes(StandardCharsets.US_ASCII));
        files.put("openwrt-uninstall/before-bcb", Arrays.copyOf(misc, 2048));
        files.put("openwrt-uninstall/recovery-bcb", wipe ? bcb : new byte[2048]);
        files.put("openwrt-uninstall/android-bc", android);
        StringBuilder sums = new StringBuilder();
        for (Map.Entry<String, byte[]> e : files.entrySet())
            sums.append(Mu300Boot.sha256(e.getValue())).append("  ").append(e.getKey().substring("openwrt-uninstall/".length())).append('\n');
        files.put("openwrt-uninstall/SHA256SUMS", sums.toString().getBytes(StandardCharsets.US_ASCII));
        byte[] image;
        try { image = UninstallBoot.build(pkg.base, pkg.family, files, pkg.worker); }
        catch (IllegalArgumentException e) { throw new Uninstall.Refused(e.getMessage()); }
        File path = new File(rec.dir, "boot-uninstall.img");
        Mu300Internal.writeAll(path, image);
        Mu300Internal.writeAll(new File(rec.dir, "trial.bin"), trial);
        String remote = TMP + rec.s("id");
        rec.r.put("uninstall_remote", remote);
        rec.r.put("uninstall_boot_sha256", Mu300Boot.sha256(image));
        rec.r.put("uninstall_android_sha256", androidSha);
        rec.r.put("uninstall_trial_sha256", Mu300Boot.sha256(trial));
        rec.save();
        io.log("离线卸载启动镜像已生成：" + Mu300Internal.human(image.length) + "，sha256 " + Mu300Boot.sha256(image).substring(0, 16) + "…");
        io.step("上传离线卸载启动镜像（" + Mu300Internal.human(image.length) + "）", false);
        io.sh("umask 077; mkdir -p " + remote + "; chown 2000:2000 " + remote + "; chmod 700 " + remote, true);
        io.push(path, remote + "/boot.img");
        io.push(new File(rec.dir, "trial.bin"), remote + "/trial.bin");
        String bootdev = "/dev/block/by-name/boot_" + other;
        String disk = "/dev/block/" + d.partitionNode();
        long front = Uninstall.lng(m.get("front")), tail = Uninstall.lng(m.get("tail")), total = Uninstall.lng(m.get("total"));
        String layoutChecks = "[ \"$(dd if=" + disk + " bs=512 count=" + front + " | sha256sum | cut -d\" \" -f1)\" = " + Mu300Boot.sha256(snapshot(rec, "before-first"))
                + " ]; [ \"$(dd if=" + disk + " bs=512 skip=" + tail + " count=" + (total - tail) + " | sha256sum | cut -d\" \" -f1)\" = " + Mu300Boot.sha256(snapshot(rec, "before-last")) + " ]; ";
        String linuxSha = retry.isEmpty() ? sums(rec.backup()).get("boot_" + other + ".img") : retry;
        String script = "set -eu; set -o pipefail; [ \"$(getprop ro.boot.slot_suffix)\" = \"_" + slot + "\" ]; " + layoutChecks
                + "[ \"$(sha256sum /dev/block/by-name/boot_" + slot + " | cut -d\" \" -f1)\" = " + androidSha + " ]; [ \"$(sha256sum " + bootdev + " | cut -d\" \" -f1)\" = " + linuxSha
                + " ]; dd if=" + bootdev + " of=" + remote + "/before-boot.img bs=1048576; dd if=/dev/block/by-name/misc of=" + remote
                + "/before-bc bs=1 skip=2048 count=32; [ \"$(sha256sum " + remote + "/before-bc | cut -d\" \" -f1)\" = " + Mu300Boot.sha256(control)
                + " ]; dd if=" + remote + "/boot.img of=" + bootdev + " bs=1048576 conv=fsync; [ \"$(sha256sum " + bootdev + " | cut -d\" \" -f1)\" = " + Mu300Boot.sha256(image)
                + " ]; if dd if=" + remote + "/trial.bin of=/dev/block/by-name/misc bs=1 seek=2048 count=32 conv=notrunc,fsync && [ \"$(dd if=/dev/block/by-name/misc bs=1 skip=2048 count=32 | sha256sum | cut -d\" \" -f1)\" = "
                + Mu300Boot.sha256(trial) + " ]; then :; else dd if=" + remote + "/before-bc of=/dev/block/by-name/misc bs=1 seek=2048 count=32 conv=notrunc,fsync; exit 1; fi";
        effect(io, rec, "uninstall-arm", script, "写入离线卸载启动镜像到 boot_" + other + " 并设置一次性试启动");
        rec.r.put("uninstall_armed", true);
        rec.r.put("uninstall_wait_boot_id", d.bootId);
        rec.stage("uninstall_offline", io, "✓ 离线卸载请求已写入并回读一致：boot_" + other + " 为卸载启动镜像，misc 已设为一次性试启动（失败会自动回到 Android）");
        io.log("重启目标设备，进入离线卸载（之后 ADB 会断开，属正常现象）");
        try { io.reboot(); }
        catch (IOException e) { io.log("Expected ADB loss after the verified offline uninstall request / 已写入离线卸载请求，ADB 断开属正常：" + e.getMessage()); }
    }

    /** ctx.effect(name, fn): pending -> run -> done, saved around the write. */
    @SuppressWarnings("unchecked")
    static void effect(Mu300Internal.Io io, Rec rec, String name, String script, String what) throws IOException {
        LinkedHashMap<String, Object> e = new LinkedHashMap<>();
        e.put("state", "pending");
        e.put("time", System.currentTimeMillis() / 1000);
        rec.effects().put(name, e);
        rec.r.put("unsafe", true);
        rec.save();
        io.step(what, true);
        io.log("写入：" + what);
        io.sh(script, true, ARM_SILENCE_S);
        e.put("state", "done");
        e.put("result", "ok");
        List<Object> done = (List<Object>) rec.r.get("completed");
        if (done == null) { done = new ArrayList<>(); rec.r.put("completed", done); }
        if (!done.contains(name)) done.add(name);
        rec.r.put("unsafe", false);
        rec.save();
    }

    // ================================================================== recover_arm

    static void recoverArm(Mu300Internal.Io io, Rec rec, Uninstall.Pkg pkg) throws IOException {
        io.log("▶ 上次写入离线卸载请求时中断，正在回读复核（不会盲目重写）");
        if (!Uninstall.HEX64.matcher(rec.s("uninstall_boot_sha256")).matches() || !new File(rec.dir, "trial.bin").isFile()
                || !new File(rec.backup(), "misc-head.bin").isFile())
            throw new Uninstall.Refused("Uninstall staging was interrupted without sufficient readback evidence / 卸载准备中断且回读证据不足，请保留备份恢复");
        Uninstall.Device d = ready(io, rec, pkg);
        if (!d.booted) throw new Uninstall.Refused("Reconnect rooted Android to inspect the interrupted boot write / 请连接有 root 的 Android，复核中断的启动写入");
        if (!"before".equals(layoutState(io, rec, d)))
            throw new Uninstall.Refused("Interrupted boot/misc readback is not recognized / 中断的 boot/misc 回读不符合已保存记录，请保留备份恢复");
        byte[] original = Arrays.copyOfRange(backupFile(rec, "misc-head.bin", "Interrupted uninstall backups are damaged / 中断卸载的备份损坏"), 2048, 2080);
        byte[] trial = Mu300Internal.readAll(new File(rec.dir, "trial.bin"));
        if (!Mu300Boot.sha256(trial).equals(rec.s("uninstall_trial_sha256")))
            throw new Uninstall.Refused("Interrupted uninstall backups are damaged / 中断卸载的备份损坏");
        byte[] bc = Mu300Internal.read(io, "dd if=/dev/block/by-name/misc bs=1 skip=2048 count=32 2>/dev/null", 32);
        String active = firstField(io.sh("sha256sum /dev/block/by-name/boot_" + d.slot, true));
        String target = firstField(io.sh("sha256sum /dev/block/by-name/boot_" + d.other, true));
        if (!active.equals(rec.s("uninstall_android_sha256")) || !Uninstall.HEX64.matcher(target).matches())
            throw new Uninstall.Refused("Android boot identity changed during interrupted uninstall / 中断卸载期间 Android 启动身份已变化");
        if (Arrays.equals(bc, original)) {
            rec.r.put("uninstall_retry_linux_sha256", target);
            rec.effects().remove("uninstall-arm");
            rec.r.put("unsafe", false);
            rec.save();
            io.log("Verified original GPT, misc and Android boot; retrying inactive boot staging only / 已确认分区表、misc 与 Android 启动均为原样，只重试非活动槽的卸载镜像写入");
            return;
        }
        if (!Arrays.equals(bc, trial) || !target.equals(rec.s("uninstall_boot_sha256")))
            throw new Uninstall.Refused("Interrupted boot/misc readback is not recognized / 中断的 boot/misc 回读不符合已保存记录，请保留备份恢复");
        @SuppressWarnings("unchecked") Map<String, Object> e = (Map<String, Object>) rec.effects().get("uninstall-arm");
        e.put("state", "done"); e.put("time", System.currentTimeMillis() / 1000); e.put("result", "verified by recovery readback");
        @SuppressWarnings("unchecked") List<Object> done = (List<Object>) rec.r.get("completed");
        if (done != null && !done.contains("uninstall-arm")) done.add("uninstall-arm");
        rec.r.put("unsafe", false);
        rec.r.put("uninstall_armed", true);
        rec.save();
        io.log("✓ 回读确认：离线卸载请求此前已完整写入");
    }
    static byte[] fromHex(String h) {
        if (h == null || h.length() % 2 != 0) return new byte[0];
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
    static String firstField(String out) {
        String t = out.trim();
        int sp = t.indexOf(' ');
        return sp < 0 ? t : t.substring(0, sp);
    }

    // ================================================================== rearm_growth

    @SuppressWarnings("unchecked")
    static boolean rearmGrowth(Mu300Internal.Io io, Rec rec, Uninstall.Pkg pkg, Uninstall.Device d) throws IOException {
        if (!Uninstall.str(rec.meta().get("action")).startsWith("grow-") || !"after".equals(layoutState(io, rec, d)))
            throw new Uninstall.Refused("No verified pending growth / 没有可复核的待完成扩容");
        Uninstall.Volume v = Uninstall.androidVolume(io, d, true);
        Uninstall.eligible(d, v);
        if (!v.uuid.equals(Uninstall.Volume.of((Map<String, Object>) rec.meta().get("volume")).uuid))
            throw new Uninstall.Refused("Android filesystem identity changed / Android 文件系统身份已变化");
        if (v.filesystemBytes >= Uninstall.lng(rec.plan().get("android_bytes")) - Uninstall.MIB4) {
            rec.r.put("uninstall_growth_pending", false);
            rec.save();
            return false;
        }
        String slot = d.slot, other = d.other;
        String[] rows = io.sh("sha256sum /dev/block/by-name/boot_" + slot + " /dev/block/by-name/boot_" + other, true).trim().split("\n");
        if (rows.length != 2 || !firstField(rows[0]).equals(rec.s("uninstall_android_sha256")) || !firstField(rows[1]).equals(rec.s("uninstall_boot_sha256")))
            throw new Uninstall.Refused("Pending growth boot identity differs / 待完成扩容的启动镜像身份不符");
        byte[] current = Mu300Internal.read(io, "dd if=/dev/block/by-name/misc bs=1 skip=2048 count=32 2>/dev/null", 32);
        Map<String, Object> effect = (Map<String, Object>) rec.effects().get("uninstall-grow-rearm");
        byte[] savedTrial = fromHex(rec.s("uninstall_growth_trial"));
        byte[] before, trial;
        if (effect != null && (!"done".equals(Uninstall.str(effect.get("state"))) || Arrays.equals(current, savedTrial))) {
            before = fromHex(rec.s("uninstall_growth_before_bc"));
            trial = fromHex(rec.s("uninstall_growth_trial"));
            if (Arrays.equals(current, trial)) {
                effect.put("state", "done"); effect.put("result", "verified by readback");
                rec.r.put("unsafe", false);
            } else if (!Arrays.equals(current, before)) {
                throw new Uninstall.Refused("Unconfirmed growth boot control; keep backups / 扩容启动控制写入尚未确认，请保留备份恢复");
            } else {
                rec.effects().remove("uninstall-grow-rearm");
                rec.r.put("unsafe", false);
                rec.save();
            }
        } else {
            before = current;
            try { trial = UninstallBoot.trialControl(before, slot); }
            catch (IllegalArgumentException e) { throw new Uninstall.Refused(e.getMessage()); }
            rec.r.put("uninstall_growth_before_bc", Mu300Internal.hex(before));
            rec.r.put("uninstall_growth_trial", Mu300Internal.hex(trial));
            rec.effects().remove("uninstall-grow-rearm");
            rec.save();
        }
        if (!Arrays.equals(current, trial)) {
            String remote = TMP + rec.s("id") + "-grow";
            io.sh("umask 077; mkdir -p " + remote + "; chown 2000:2000 " + remote + "; chmod 700 " + remote, true);
            File lb = new File(rec.dir, "grow-before-bc"), lt = new File(rec.dir, "grow-trial-bc");
            Mu300Internal.writeAll(lb, before);
            Mu300Internal.writeAll(lt, trial);
            io.push(lb, remote + "/before-bc");
            io.push(lt, remote + "/trial-bc");
            String script = "set -eu; set -o pipefail; [ \"$(getprop ro.boot.slot_suffix)\" = _" + slot + " ]; [ \"$(sha256sum /dev/block/by-name/boot_" + slot
                    + " | cut -d\" \" -f1)\" = " + rec.s("uninstall_android_sha256") + " ]; [ \"$(sha256sum /dev/block/by-name/boot_" + other + " | cut -d\" \" -f1)\" = "
                    + rec.s("uninstall_boot_sha256") + " ]; [ \"$(dd if=/dev/block/by-name/misc bs=1 skip=2048 count=32 | sha256sum | cut -d\" \" -f1)\" = " + Mu300Boot.sha256(before)
                    + " ]; if dd if=" + remote + "/trial-bc of=/dev/block/by-name/misc bs=1 seek=2048 count=32 conv=notrunc,fsync && [ \"$(dd if=/dev/block/by-name/misc bs=1 skip=2048 count=32 | sha256sum | cut -d\" \" -f1)\" = "
                    + Mu300Boot.sha256(trial) + " ]; then :; else dd if=" + remote + "/before-bc of=/dev/block/by-name/misc bs=1 seek=2048 count=32 conv=notrunc,fsync; exit 1; fi";
            effect(io, rec, "uninstall-grow-rearm", script, "重新设置一次性试启动，继续离线 data 扩容");
        }
        rec.r.put("uninstall_wait_boot_id", d.bootId);
        rec.stage("uninstall_offline", io, "重启目标设备，继续离线 data 扩容（只做检查与扩容，不会格式化）");
        try { io.reboot(); }
        catch (IOException e) { io.log("Expected reconnect after resuming verified offline growth / 已重新写入扩容请求，ADB 断开属正常"); }
        return true;
    }

    // ================================================================== finish / finish_boot

    @SuppressWarnings("unchecked")
    static Outcome finish(Mu300Internal.Io io, Rec rec, Uninstall.Pkg pkg, Uninstall.Device d) throws IOException {
        rec.stage("verify_android", io, "▶ 离线卸载后复核 Android（只读）");
        Map<String, Object> plan = rec.plan();
        long androidBytes = Uninstall.lng(plan.get("android_bytes"));
        long userdataBytes = d.s.size * 512;
        if (d.s.root != null || userdataBytes != androidBytes)
            throw new Uninstall.Refused("Android partition capacity was not restored / Android 分区容量未恢复");
        boolean preserving = Uninstall.str(rec.meta().get("action")).startsWith("grow-");
        Uninstall.Volume v = Uninstall.androidVolume(io, d, preserving);
        Uninstall.Volume saved = Uninstall.Volume.of((Map<String, Object>) rec.meta().get("volume"));
        long expected = userdataBytes - saved.mappingOverhead;
        io.log("Android data：文件系统 " + Mu300Internal.human(v.filesystemBytes) + "，映射 " + Mu300Internal.human(v.mappedBytes) + "，userdata " + Mu300Internal.human(userdataBytes));
        if (preserving) {
            Uninstall.eligible(d, v);
            if (!v.uuid.equals(saved.uuid)) throw new Uninstall.Refused("Preserved Android filesystem identity differs / 保留的 Android 文件系统身份不符");
            if (v.mappedBytes - v.filesystemBytes > Uninstall.MIB4) {
                rec.r.put("uninstall_growth_pending", true);
                rec.save();
                throw new Uninstall.Refused("Offline data growth is incomplete. Retry resumes checks/growth only; it never formats. / 离线 data 扩容尚未完成。重试仅继续检查与扩容，不会格式化。");
            }
        }
        if (Math.abs(v.mappedBytes - expected) > Uninstall.MIB4 || !(expected - Uninstall.MIB4 <= v.filesystemBytes && v.filesystemBytes <= expected))
            throw new Uninstall.Refused("Android filesystem has not reached the restored allocation / Android 文件系统尚未达到恢复后的分配容量: " + v.filesystemBytes + " != " + expected + " bytes");
        byte[] bcb = Mu300Internal.read(io, "dd if=/dev/block/by-name/misc bs=2048 count=1 2>/dev/null", 2048);
        for (int i = 0; i < 32; i++) if (bcb[i] != 0) throw new Uninstall.Refused("Recovery reset is still pending / 原厂 Recovery 重置尚未完成");
        finishBoot(io, rec, d);
        rec.r.put("uninstall_growth_pending", false);
        String remote = rec.s("uninstall_remote");
        if (!remote.isEmpty()) {
            try {
                io.sh("rm -f " + remote + "/boot.img " + remote + "/trial.bin " + remote + "/before-boot.img " + remote + "/before-bc " + remote + "-finish/android-bc "
                        + remote + "-grow/before-bc " + remote + "-grow/trial-bc; rmdir " + remote + " " + remote + "-finish " + remote + "-grow 2>/dev/null; true", true);
            } catch (IOException e) { io.log("提示：目标设备上的卸载暂存文件未能全部删除：" + e.getMessage()); }
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("uninstalled", true);
        result.put("filesystem_bytes", v.filesystemBytes);
        result.put("android_bytes", userdataBytes);
        result.put("reclaimed_bytes", Uninstall.lng(plan.get("reclaimed_bytes")));
        result.put("released_bytes", Uninstall.lng(plan.get("openwrt_bytes")));
        result.put("backup_directory", rec.backup().getAbsolutePath());
        rec.r.put("result", result);
        rec.r.put("complete", true);
        rec.stage("complete", io, "✓ 卸载完成");
        String msg = "OpenWrt 已卸载：userdata 现为 " + Mu300Internal.human(userdataBytes)
                + (Uninstall.lng(plan.get("reclaimed_bytes")) > 0 ? "（并入 " + Mu300Internal.human(Uninstall.lng(plan.get("reclaimed_bytes"))) + "）" : "，OpenWrt 原占用的 " + Mu300Internal.human(Uninstall.lng(plan.get("openwrt_bytes"))) + " 已归还为未分配空间")
                + "；Android 启动已恢复到双槽一致。";
        return new Outcome(false, true, msg, "备份保存在 " + rec.backup().getAbsolutePath() + "，确认 Android 一切正常后可在「使用说明 → 清理本机占用」删除。");
    }

    @SuppressWarnings("unchecked")
    static void finishBoot(Mu300Internal.Io io, Rec rec, Uninstall.Device d) throws IOException {
        String slot = d.slot, other = d.other;
        byte[] bc = Mu300Internal.read(io, "dd if=/dev/block/by-name/misc bs=1 skip=2048 count=32 2>/dev/null", 32);
        try { UninstallBoot.checkAndroidHealthy(bc, slot); }
        catch (IllegalArgumentException e) { throw new Uninstall.Refused(e.getMessage()); }
        byte[] desired = UninstallBoot.androidControl(bc, slot);
        String[] rows = io.sh("sha256sum /dev/block/by-name/boot_" + slot + " /dev/block/by-name/boot_" + other, true).trim().split("\n");
        if (rows.length != 2 || !Uninstall.HEX64.matcher(firstField(rows[0])).matches() || !Uninstall.HEX64.matcher(firstField(rows[1])).matches())
            throw new Uninstall.Refused("Cannot verify restored Android boot / 无法核对恢复后的 Android 启动");
        String source = firstField(rows[0]), target = firstField(rows[1]);
        if (!source.equals(rec.s("uninstall_android_sha256"))) {
            File extra = new File(rec.backup(), "boot-android-after-reset.img");
            if (!extra.isFile()) {
                io.log("当前 Android 启动镜像与卸载前不同（例如恢复出厂后 Magisk 修补已变化），先备份 boot_" + slot);
                String v = io.sh("blockdev --getsize64 /dev/block/by-name/boot_" + slot, true).trim();
                Mu300Internal.download(io, "cat /dev/block/by-name/boot_" + slot + " 2>/dev/null", extra, Long.parseLong(v), extra.getName());
                String line = Mu300Internal.hashFile(extra) + "  " + extra.getName() + "\n";
                File sums = new File(rec.backup(), "SHA256SUMS");
                byte[] old = sums.isFile() ? Mu300Internal.readAll(sums) : new byte[0];
                Mu300Internal.writeAll(sums, (new String(old, StandardCharsets.UTF_8) + line).getBytes(StandardCharsets.UTF_8));
            }
        }
        if (source.equals(target) && Arrays.equals(bc, desired)) {
            LinkedHashMap<String, Object> e = new LinkedHashMap<>();
            e.put("state", "done"); e.put("result", "verified");
            rec.effects().put("uninstall-final-boot", e);
            rec.r.put("unsafe", false);
            rec.save();
            io.log("✓ 两个槽位都是同一 Android 启动镜像，启动控制已只指向 Android");
            return;
        }
        rec.stage("uninstall_restore_boot", io, "▶ 把当前 Android 启动复制到另一槽并恢复启动控制");
        String remote = rec.s("uninstall_remote") + "-finish";
        io.sh("umask 077; mkdir -p " + remote + "; chown 2000:2000 " + remote + "; chmod 700 " + remote, true);
        File local = new File(rec.dir, "finish-android-bc");
        Mu300Internal.writeAll(local, desired);
        io.push(local, remote + "/android-bc");
        if (rec.effects().containsKey("uninstall-final-boot")) {
            rec.effects().remove("uninstall-final-boot");
            io.log("Retrying verified inactive Android boot restoration; no partition/reset replay / 重试非活动槽的 Android 启动恢复，不会重放分区或恢复出厂操作");
        }
        String script = "set -eu; set -o pipefail; [ \"$(getprop ro.boot.slot_suffix)\" = _" + slot + " ]; [ \"$(sha256sum /dev/block/by-name/boot_" + slot
                + " | cut -d\" \" -f1)\" = " + source + " ]; dd if=/dev/block/by-name/boot_" + slot + " of=/dev/block/by-name/boot_" + other
                + " bs=1048576 conv=fsync; [ \"$(sha256sum /dev/block/by-name/boot_" + other + " | cut -d\" \" -f1)\" = " + source + " ]; dd if=" + remote
                + "/android-bc of=/dev/block/by-name/misc bs=1 seek=2048 count=32 conv=notrunc,fsync; [ \"$(dd if=/dev/block/by-name/misc bs=1 skip=2048 count=32 | sha256sum | cut -d\" \" -f1)\" = "
                + Mu300Boot.sha256(desired) + " ]";
        effect(io, rec, "uninstall-final-boot", script, "复制 boot_" + slot + " → boot_" + other + "，启动控制只指向 Android");
    }
}
