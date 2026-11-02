package com.dsh.ramwatch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** 通知栏常驻的剩余内存，默认不开。 */
public class MemNotifyService extends Service {

    private static final String CHANNEL_ID = "mem_status";
    private static final int NOTI_ID = 0x5201;
    private static final long INTERVAL = 5000L;

    private Handler handler;
    private NotificationManager nm;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                nm.notify(NOTI_ID, build());
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, INTERVAL);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "内存状态", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);

        Notification n = build();
        if (Build.VERSION.SDK_INT >= 34) {
            // FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            startForeground(NOTI_ID, n, 1073741824);
        } else {
            startForeground(NOTI_ID, n);
        }
        handler.postDelayed(tick, INTERVAL);
    }

    private Notification build() {
        MemoryUtils.Mem m = MemoryUtils.read(this);
        int pct = MemoryUtils.percent(m.used, m.total);

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("可用 " + MemoryUtils.size(m.avail)
                        + "　已用 " + pct + "%")
                .setContentText("已用 " + MemoryUtils.size(m.used)
                        + " / " + MemoryUtils.size(m.total))
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
