package com.mkr.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

// Download chalte waqt app band hone par bhi kaam jaari rakhta hai (notification ke saath)
public class GrabService extends Service {
    static final int ID = 77;
    final Handler h = new Handler(Looper.getMainLooper());
    final Runnable tick = new Runnable() {
        public void run() {
            if (Grab.active() == 0) {
                stopForeground(true);
                stopSelf();
                return;
            }
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID, build());
            h.postDelayed(this, 1000);
        }
    };

    static Notification.Builder nb(Context c, String ch, String name) {
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(ch) == null)
                nm.createNotificationChannel(new NotificationChannel(ch, name, NotificationManager.IMPORTANCE_LOW));
            return new Notification.Builder(c, ch);
        }
        return new Notification.Builder(c);
    }

    Notification build() {
        int n = Grab.active();
        Notification.Builder b = nb(this, "mkr_dl", "Downloads");
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int p = Grab.avgPct();
        b.setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).setContentIntent(pi)
                .setContentTitle("MKR Grabber")
                .setContentText(n + " downloading  \u2022  " + String.format("%.1f MB/s", Grab.speedSum() / 1e6))
                .setProgress(100, p, p <= 0);
        return b.build();
    }

    @Override
    public int onStartCommand(Intent in, int flags, int id) {
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID, n);
        h.removeCallbacks(tick);
        h.postDelayed(tick, 1000);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    static void notifyDone(Context c, String text) {
        try {
            Notification.Builder b = nb(c, "mkr_done", "Download done");
            b.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("MKR Grabber")
                    .setContentText(text).setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, 1, new Intent(c, MainActivity.class),
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE))
                    .notify(1000 + (int) (System.nanoTime() % 1000), b.build());
        } catch (Exception ignored) {
        }
    }
}
