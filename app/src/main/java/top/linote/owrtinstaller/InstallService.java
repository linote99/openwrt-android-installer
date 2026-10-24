package top.linote.owrtinstaller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps the app process (and the CPU) alive while a long task runs. The actual
 * TF install runs detached under root and survives even if this service dies.
 */
public class InstallService extends Service {
    private static final String CHANNEL = "install";
    private static final String EXTRA = "what";
    private PowerManager.WakeLock wake;

    static void start(Context c, String what) {
        Intent i = new Intent(c, InstallService.class).putExtra(EXTRA, what);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (RuntimeException ignored) {
            // Background start restrictions: the task still runs, only without the notification.
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, InstallService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String what = intent != null && intent.getStringExtra(EXTRA) != null ? intent.getStringExtra(EXTRA) : "处理中";
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "安装进度", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("OpenWrt 安装器")
                .setContentText(what + "：请保持供电，不要关机")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, n);
        if (wake == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "owrtinstaller:task");
                wake.acquire(3 * 60 * 60 * 1000L);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
