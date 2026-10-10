package com.pichaplus.app;

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

public class PichaDownloadService extends Service {
    private static final String CH = "picha_dl";
    private static final int NID = 4711;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (PichaEngine.LIVE.isEmpty()) {
                stopForeground(true);
                stopSelf();
                return;
            }
            try {
                ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NID, build(PichaDownloadService.this));
            } catch (Exception e) {}
            h.postDelayed(this, 1000);
        }
    };

    static void ensure(Context c) {
        try {
            Intent i = new Intent(c, PichaDownloadService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) {}
    }

    static Notification build(Context c) {
        int n = 0;
        long got = 0, tot = 0;
        for (long[] v : PichaEngine.LIVE.values()) { n++; got += v[0]; tot += v[1]; }
        int pct = tot > 0 ? (int) Math.min(100, got * 100 / tot) : 0;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
         .setContentTitle("Picha+ downloads")
         .setContentText(n + (n == 1 ? " video" : " videos") + (tot > 0 ? " \u00b7 " + pct + "%" : ""))
         .setOngoing(true)
         .setOnlyAlertOnce(true);
        if (tot > 0) b.setProgress(100, pct, false); else b.setProgress(0, 0, true);
        try {
            Intent li = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
            if (li != null) {
                int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
                b.setContentIntent(PendingIntent.getActivity(c, 0, li, fl));
            }
        } catch (Exception e) {}
        return b.build();
    }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CH, "Downloads", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent i, int f, int s) {
        Notification nt = build(this);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, nt, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NID, nt);
        h.removeCallbacks(tick);
        h.postDelayed(tick, 1000);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        h.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
