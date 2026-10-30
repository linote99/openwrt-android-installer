package top.linote.owrtinstaller;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

/** App-private store for one verified historical GPT baseline; never current-device evidence. */
final class ImportedBaselineStore {
    private static final String FILE_NAME = "imported-gpt-baseline.bin";
    private static final byte[] MAGIC = new byte[]{'O','W','R','T','G','P','T','B'};
    private static final int VERSION = 1;
    private static final int MAX_COPY_BYTES = 8 * 1024 * 1024;

    private ImportedBaselineStore() { }

    static void save(File filesDir, PartitionPlanner.Snapshot snapshot) throws IOException {
        if (filesDir == null || snapshot == null || snapshot.origin != PartitionPlanner.Origin.BASELINE_BACKUP
                || snapshot.independentlyReadOnly() || !snapshot.identityDigest.isEmpty())
            throw new IOException("仅允许保存无设备绑定的已验证历史 GPT 基线");
        if (snapshot.first.length > MAX_COPY_BYTES || snapshot.last.length > MAX_COPY_BYTES)
            throw new IOException("GPT 快照超过本地保存上限");
        File target = new File(filesDir, FILE_NAME);
        File temporary = new File(filesDir, FILE_NAME + ".tmp");
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payload)) {
            out.write(MAGIC); out.writeInt(VERSION); out.writeLong(snapshot.sectors512);
            out.writeInt(snapshot.first.length); out.writeInt(snapshot.last.length);
            out.write(snapshot.first); out.write(snapshot.last); out.flush();
        }
        byte[] body = payload.toByteArray();
        byte[] digest = sha(body);
        try (FileOutputStream fos = new FileOutputStream(temporary);
             DataOutputStream out = new DataOutputStream(fos)) {
            out.write(body); out.write(digest); out.flush(); fos.getFD().sync();
        }
        temporary.setReadable(false, false); temporary.setWritable(false, false);
        temporary.setReadable(true, true); temporary.setWritable(true, true);
        if (!temporary.renameTo(target)) {
            // Android/Linux rename does not replace an existing file on all providers.
            if (target.exists() && !target.delete()) { temporary.delete(); throw new IOException("无法替换本地历史 GPT 基线"); }
            if (!temporary.renameTo(target)) { temporary.delete(); throw new IOException("无法保存本地历史 GPT 基线"); }
        }
        target.setReadable(false, false); target.setWritable(false, false);
        target.setReadable(true, true); target.setWritable(true, true);
    }

    static PartitionPlanner.Snapshot load(File filesDir) throws IOException {
        if (filesDir == null) return null;
        File source = new File(filesDir, FILE_NAME);
        if (!source.isFile()) return null;
        long length = source.length();
        if (length < 8 + 4 + 8 + 4 + 4 + 32 || length > 2L * MAX_COPY_BYTES + 128)
            throw new IOException("本地历史 GPT 基线文件长度无效");
        byte[] all = new byte[(int) length];
        try (DataInputStream in = new DataInputStream(new FileInputStream(source))) {
            in.readFully(all);
        }
        int bodyLength = all.length - 32;
        byte[] body = Arrays.copyOf(all, bodyLength);
        byte[] stored = Arrays.copyOfRange(all, bodyLength, all.length);
        if (!MessageDigest.isEqual(stored, sha(body))) throw new IOException("本地历史 GPT 基线 SHA-256 校验失败");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            byte[] magic = new byte[MAGIC.length]; in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC) || in.readInt() != VERSION) throw new IOException("本地历史 GPT 基线格式/版本不受支持");
            long sectors = in.readLong(); int firstLen = in.readInt(), lastLen = in.readInt();
            if (sectors <= 0 || firstLen <= 0 || lastLen <= 0 || firstLen > MAX_COPY_BYTES || lastLen > MAX_COPY_BYTES
                    || firstLen + (long) lastLen != in.available()) throw new IOException("本地历史 GPT 基线几何无效");
            byte[] first = new byte[firstLen], last = new byte[lastLen]; in.readFully(first); in.readFully(last);
            if (in.available() != 0) throw new IOException("本地历史 GPT 基线含多余数据");
            return PartitionPlanner.Snapshot.verified(first, last, sectors, PartitionPlanner.Origin.BASELINE_BACKUP, "");
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new IOException("本地历史 GPT 基线未通过 GPT CRC/几何校验", e);
        }
    }

    static String compare(File filesDir, byte[] currentFirst, byte[] currentLast, long currentSectors) {
        try {
            PartitionPlanner.Snapshot baseline = load(filesDir);
            if (baseline == null) return "未导入历史 GPT 基线；卸载只依据独立取得的当前设备只读证据。";
            boolean same = baseline.sectors512 == currentSectors
                    && Arrays.equals(baseline.first, currentFirst) && Arrays.equals(baseline.last, currentLast);
            return "历史 GPT 基线与本次当前 GPT 字节" + (same ? "相同" : "不同")
                    + "（仅布局对照，未验证设备身份；不会改变卸载判定、计划或写入授权）。";
        } catch (IOException e) {
            return "已导入历史 GPT 基线不可用或损坏（已忽略）；当前设备只读检查独立执行，不会因此改变判定。";
        }
    }

    static String description(File filesDir) {
        try {
            PartitionPlanner.Snapshot s = load(filesDir);
            return s == null ? "尚无已导入的历史 GPT 基线。导入后仅保存 GPT 主/备快照供分区与卸载检查做布局比较；它不是当前设备快照。"
                    : "已有 1 份历史 GPT 基线（" + s.entries.size() + " 个分区，" + s.logicalBlockSize
                    + " B 逻辑块）。仅供布局比较；不含身份 JSON、续装记录或其它镜像，也不是当前设备证据。";
        } catch (IOException e) {
            return "本地历史 GPT 基线无法校验；不会用于任何判定。可从可信备份 ZIP 重新导入替换。";
        }
    }

    private static byte[] sha(byte[] b) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(b); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IOException("SHA-256 不可用", e); }
    }
}
