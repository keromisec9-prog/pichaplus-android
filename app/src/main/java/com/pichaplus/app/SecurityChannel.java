package com.pichaplus.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;

public final class SecurityChannel {
    public static final String ID = "picha_security";

    public static void create(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Uri snd = Uri.parse("android.resource://" + c.getPackageName() + "/" + R.raw.security_alert);
            AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
            NotificationChannel ch = new NotificationChannel(ID, "Security alerts", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Password resets and other account security alerts");
            ch.setSound(snd, aa);
            ch.enableVibration(true);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        } catch (Exception ignore) {
        }
    }
}
