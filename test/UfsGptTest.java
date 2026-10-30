package top.linote.owrtinstaller;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.CRC32;

/** Offline-only tests for UFS inventory parsing and 512/4096-byte logical-block GPT handling. */
public final class UfsGptTest {
    private static int checks;
    private static void ok(boolean value, String what) {
        checks++;
        if (!value) throw new AssertionError("FAIL " + what);
        System.out.println("ok   " + what);
    }
    private static void rejects(Runnable r, String what) {
        boolean rejected = false;
        try { r.run(); } catch (RuntimeException e) { rejected = true; }
        ok(rejected, what);
    }
    private static String syntheticCid() { char[] x = new char[32]; Arrays.fill(x, 'a'); return new String(x); }

    private static final class Probe implements Mu300Internal.Io {
        String response;
        int calls;
        Probe(String response) { this.response = response; }
        @Override public String storageLibrary() { return "# test fixture, never executed"; }
        @Override public String sh(String script, boolean root) {
            calls++;
            if (!script.contains("mu300_storage_parent") || !script.contains("mu300_storage_identity"))
                throw new AssertionError("UFS library was not prepended to the inventory probe");
            return response;
        }
        @Override public void pull(String remote, File dest) { throw new AssertionError("read-only storage probe must not pull"); }
        @Override public void push(File local, String remote) { throw new AssertionError("read-only storage probe must not push"); }
        @Override public void reboot() { throw new AssertionError("read-only storage probe must not reboot"); }
        @Override public void log(String line) { }
        @Override public void progress(int percent) { }
        @Override public File cacheDir() { return new File("."); }
    }

    private static String inventory(String disk, String kind, long sectorSize, long start) {
        return "NODE=" + disk + "12\nDISK=" + disk + "\nSTORAGE_KIND=" + kind + "\n"
                + "CID=" + syntheticCid() + "\nSECTOR_SIZE=" + sectorSize + "\n"
                + "START=" + start + "\nSIZE=8388608\nDISK_SIZE=16777216\nFREE=1000000\n"
                + "STATE=unencrypted\nTYPE=\nSOURCE=/dev/block/by-name/userdata\nMAGIC=1020f5f2\nEXT=0000\nROOT=" + disk + "38\n";
    }

    private static int crc(byte[] b, int off, int len) {
        CRC32 c = new CRC32(); c.update(b, off, len); return (int) c.getValue();
    }
    private static void makeHeader(byte[] out, int at, int block, long current, long alternate,
                                   long firstUsable, long lastUsable, long tableLba, int count, int entrySize, int tableCrc) {
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        byte[] sig = "EFI PART".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(sig, 0, out, at, sig.length);
        b.putInt(at + 8, 0x00010000); b.putInt(at + 12, 92); b.putInt(at + 16, 0);
        b.putInt(at + 20, 0); b.putLong(at + 24, current); b.putLong(at + 32, alternate);
        b.putLong(at + 40, firstUsable); b.putLong(at + 48, lastUsable);
        for (int i = 0; i < 16; i++) out[at + 56 + i] = (byte) (i + 1);
        b.putLong(at + 72, tableLba); b.putInt(at + 80, count); b.putInt(at + 84, entrySize); b.putInt(at + 88, tableCrc);
        b.putInt(at + 16, crc(out, at, 92));
    }
    private static byte[][] gpt(int block) {
        final int factor = block / 512, count = 128, entry = 128, tableBytes = count * entry;
        final long sectors = 16L * 1024 * 1024, logical = sectors / factor;
        final long tableBlocks = (tableBytes + block - 1) / block;
        final long front = (2 + tableBlocks) * factor, backupTable = logical - 1 - tableBlocks, tail = backupTable * factor;
        final long firstUsable = front / factor, lastUsable = backupTable - 1;
        byte[] table = new byte[tableBytes];
        ByteBuffer t = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
        table[0] = 1; table[16] = 2;
        long userStart512 = 2048, userEnd512 = (logical / 2) * factor - 1;
        t.putLong(32, userStart512 / factor); t.putLong(40, userEnd512 / factor);
        byte[] name = "userdata".getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(name, 0, table, 56, name.length);
        byte[] first = new byte[(int) (front * 512)];
        byte[] last = new byte[(int) ((sectors - tail) * 512)];
        System.arraycopy(table, 0, first, (int) (2 * block), tableBytes);
        System.arraycopy(table, 0, last, 0, tableBytes);
        int tableCrc = crc(table, 0, table.length);
        makeHeader(first, block, block, 1, logical - 1, firstUsable, lastUsable, 2, count, entry, tableCrc);
        makeHeader(last, last.length - block, block, logical - 1, 1, firstUsable, lastUsable,
                backupTable, count, entry, tableCrc);
        return new byte[][]{first, last};
    }

    private static void gptCase(int block) {
        byte[][] copies = gpt(block);
        long sectors = 16L * 1024 * 1024;
        Mu300Gpt.Space before = Mu300Gpt.space(copies[0], copies[1], sectors);
        ok(before.blockSize == block && before.factor == block / 512, block + "-byte GPT logical block detected");
        Mu300Gpt.Part data = before.find("userdata");
        ok(data != null && data.start == 2048, block + "-byte GPT partition LBAs convert to 512-byte sysfs sectors");
        long need = 4L * 1024 * 1024;
        long offset = Mu300Gpt.freeRootRange(before, need);
        ok(offset >= 0 && offset % 2048 == 0, block + "-byte GPT exposes a 1 MiB-aligned free range");
        byte[][] after = Mu300Gpt.addRoot(copies[0], copies[1], sectors, offset, need, UUID.randomUUID(), block);
        Mu300Gpt.Space result = Mu300Gpt.space(after[0], after[1], sectors);
        Mu300Gpt.Part root = result.find("mu300root");
        ok(root != null && root.start == offset && root.end - root.start + 1 == need,
                block + "-byte GPT adds a matching mu300root entry and validates both copies");
        ok(!Arrays.equals(copies[0], after[0]) && !Arrays.equals(copies[1], after[1]), block + "-byte primary and backup tables both changed");
    }

    public static void main(String[] args) throws Exception {
        Probe p = new Probe(inventory("sda", "ufs", 4096, 2048));
        Mu300Internal.Storage s = Mu300Internal.storage(p);
        ok(s.kind.equals("ufs") && s.diskNode.equals("sda") && s.node.equals("sda12"), "UFS disk and sda partition parsed");
        ok(s.disk().equals("/dev/block/sda") && s.sectorSize == 4096 && p.calls == 1, "UFS target path and logical block size retained");
        ok(s.root.equals("sda38"), "existing UFS mu300root stays bound to the same disk");
        rejects(() -> { try { Mu300Internal.storage(new Probe(inventory("sda", "ufs", 4096, 2049))); } catch (IOException e) { throw new RuntimeException(e); } },
                "rejects unaligned 4K userdata geometry");
        rejects(() -> { try { Mu300Internal.storage(new Probe(inventory("sda", "emmc", 4096, 2048))); } catch (IOException e) { throw new RuntimeException(e); } },
                "rejects eMMC/UFS topology mismatch");
        rejects(() -> { try { Mu300Internal.storage(new Probe(inventory("sda", "ufs", 4096, 2048).replace("ROOT=sda38", "ROOT=sdb38"))); } catch (IOException e) { throw new RuntimeException(e); } },
                "rejects root partition on a different UFS disk");
        gptCase(512);
        gptCase(4096);
        System.out.println("UfsGptTest: " + checks + " checks passed (offline synthetic data only)");
    }
}
