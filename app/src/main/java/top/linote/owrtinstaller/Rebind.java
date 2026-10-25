package top.linote.owrtinstaller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * service.rebind_uninstall / adb_connect, ported. The desktop record keeps device.serial (the ADB
 * transport serial) and, when a waiting task continues over another connection, it
 *   1. requires the target to be ready (Android set up, debugging authorised, root),
 *   2. requires every identity field of the record to match the probe (cid, profile, slot there),
 *      otherwise "Selected connection belongs to another device or Android slot",
 *   3. appends {serial: old, changed_at: now} to transport_history, stores the new serial and saves.
 * Android records keep ro.serialno as a device identity, so the connection lives in its own
 * field "transport" ("usb:&lt;ro.serialno&gt;" or "tcp:&lt;host:port&gt;"), with the same history entries.
 */
final class Rebind {
    static final String NOT_READY = "请先完成 Android 初始化、调试授权和 root，再选择新连接";
    static final String MISMATCH = "所选连接并非原设备或 Android 槽位不符";
    static final String PAUSE_FIRST = "请先暂停任务，再更改 ADB 连接";

    /** Desktop stages in which a waiting task may change its ADB connection. */
    static final String[] WAIT_STAGES = {"uninstall_offline", "android_boot", "android_setup", "usb_debugging",
            "debug_authorization", "magisk", "root_permission"};

    static boolean waitStage(String stage) {
        for (String s : WAIT_STAGES) if (s.equals(stage)) return true;
        return false;
    }

    /**
     * Binds record r to the connection now in use. transport "" (unknown, e.g. tests or local
     * mode) changes nothing. Returns true when r was changed and must be saved.
     * mismatch: null when every identity field matched, else a short description of what differs.
     */
    @SuppressWarnings("unchecked")
    static boolean apply(Map<String, Object> r, String transport, boolean ready, String mismatch, long nowSec)
            throws IOException {
        if (transport == null || transport.isEmpty()) return false;
        Object old = r.get("transport");
        if (old == null || String.valueOf(old).isEmpty()) {   // first binding (records written before 1.6.0)
            if (mismatch != null) return false;              // the caller's own identity checks report it
            r.put("transport", transport);
            return true;
        }
        if (transport.equals(old)) return false;
        if (!ready) throw new IOException(NOT_READY);
        if (mismatch != null) throw new Uninstall.Refused(MISMATCH + "（" + mismatch + "）；未做任何修改");
        Object h = r.get("transport_history");
        List<Object> hist = h instanceof List ? (List<Object>) h : new ArrayList<>();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("transport", String.valueOf(old));
        e.put("changed_at", nowSec);
        hist.add(e);
        r.put("transport_history", hist);
        r.put("transport", transport);
        return true;
    }

    /** The record is bound to another connection than the one in use. */
    static boolean switching(Map<String, Object> r, String transport) {
        Object old = r.get("transport");
        return transport != null && !transport.isEmpty() && old != null && !String.valueOf(old).isEmpty() && !transport.equals(old);
    }

    /** "a ≠ b" pieces of the fields that differ, or null when all are equal. */
    static String diff(String... nameExpectedActual) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i + 2 < nameExpectedActual.length; i += 3) {
            String n = nameExpectedActual[i], x = nameExpectedActual[i + 1], a = nameExpectedActual[i + 2];
            if (x == null ? a == null : x.equals(a)) continue;
            if (b.length() > 0) b.append("，");
            b.append(n).append(" 记录 ").append(x).append(" / 现在 ").append(a);
        }
        return b.length() == 0 ? null : b.toString();
    }
}
