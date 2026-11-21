package com.pichaplus.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

public class PichaFirebaseService extends FirebaseMessagingService {
    private static final String CHANNEL_ID = "picha_notifications";

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        PichaTokenManager.sendTokenToServer(this, token);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);

        String title = "Picha+";
        String body = "";
        String imageUrl = null;
        String channelId = CHANNEL_ID;

        // Try notification payload first
        if (message.getNotification() != null) {
            title = message.getNotification().getTitle() != null ? message.getNotification().getTitle() : title;
            body = message.getNotification().getBody() != null ? message.getNotification().getBody() : body;
            if (message.getNotification().getChannelId() != null) channelId = message.getNotification().getChannelId();
            if (message.getNotification().getImageUrl() != null) {
                imageUrl = message.getNotification().getImageUrl().toString();
            }
        }

        // Override with data payload if present
        Map<String, String> data = message.getData();
        if (data != null && "digest".equals(data.get("type"))) { showDigest(data); return; }
        if (data != null) {
            if (data.containsKey("title")) title = data.get("title");
            if (data.containsKey("body")) body = data.get("body");
            if (data.containsKey("imageUrl") && !data.get("imageUrl").isEmpty()) {
                imageUrl = data.get("imageUrl");
            }
        }

        showNotification(title, body, imageUrl, channelId);
    }

    private void showDigest(Map<String, String> data) {
        String title = data.get("title") != null ? data.get("title") : "Picha+";
        String body = data.get("body") != null ? data.get("body") : "";
        final java.util.List<String> urls = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(data.get("posters"));
            for (int i = 0; i < a.length() && i < 10; i++) urls.add(a.getString(i));
        } catch (Exception e) { }
        final Bitmap[] got = new Bitmap[10];
        if (!urls.isEmpty()) {
            java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(Math.min(urls.size(), 5));
            java.util.List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < urls.size(); i++) {
                final int idx = i; final String u = urls.get(i);
                fs.add(ex.submit(new Runnable() { public void run() {
                    try {
                        Bitmap b = getBitmapFromUrl(u);
                        if (b != null) got[idx] = Bitmap.createScaledBitmap(b, 110, 165, true).copy(Bitmap.Config.RGB_565, false);
                    } catch (Throwable t) { }
                } }));
            }
            long end = System.currentTimeMillis() + 8000;
            for (java.util.concurrent.Future<?> f : fs) {
                try { f.get(Math.max(1, end - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Exception e) { }
            }
            ex.shutdownNow();
        }
        java.util.List<Bitmap> ok = new java.util.ArrayList<>();
        for (Bitmap b : got) if (b != null) ok.add(b);
        if (ok.isEmpty()) { showNotification(title, body, data.get("imageUrl"), CHANNEL_ID); return; }

        android.widget.RemoteViews rv = new android.widget.RemoteViews(getPackageName(), R.layout.notification_digest);
        rv.setTextViewText(R.id.digest_title, title);
        rv.setTextViewText(R.id.digest_body, body);
        int[] ids = { R.id.digest_p0, R.id.digest_p1, R.id.digest_p2, R.id.digest_p3, R.id.digest_p4,
                      R.id.digest_p5, R.id.digest_p6, R.id.digest_p7, R.id.digest_p8, R.id.digest_p9 };
        for (int i = 0; i < ids.length; i++) {
            if (i < ok.size()) { rv.setImageViewBitmap(ids[i], ok.get(i)); rv.setViewVisibility(ids[i], android.view.View.VISIBLE); }
            else rv.setViewVisibility(ids[i], android.view.View.INVISIBLE);
        }
        rv.setViewVisibility(R.id.digest_row2, ok.size() > 5 ? android.view.View.VISIBLE : android.view.View.GONE);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String channelId = "picha_new_content";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Picha+ Notifications", NotificationManager.IMPORTANCE_HIGH));
            if (nm.getNotificationChannel(channelId) == null) channelId = CHANNEL_ID;
        }
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_picha).setColor(0xFFEEB32A)
            .setContentTitle(title).setContentText(body)
            .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
            .setCustomBigContentView(rv)
            .setAutoCancel(true).setContentIntent(pi);
        nm.notify(7001, nb.build());
    }

    private Bitmap getBitmapFromUrl(String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setDoInput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.connect();
            InputStream input = conn.getInputStream();
            return BitmapFactory.decodeStream(input);
        } catch (Exception e) {
            return null;
        }
    }

    private void showNotification(String title, String body, String imageUrl, String channelId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Picha+ Notifications", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(channel);
        }
        SecurityChannel.create(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(channelId) == null) channelId = CHANNEL_ID;
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_picha).setColor(0xFFEEB32A)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi);

        if (imageUrl != null && !imageUrl.isEmpty()) {
            Bitmap bitmap = getBitmapFromUrl(imageUrl);
            if (bitmap != null) {
                builder.setLargeIcon(bitmap)
                    .setStyle(new NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap)
                        .bigLargeIcon((Bitmap) null));
            }
        }

        nm.notify((int) System.currentTimeMillis(), builder.build());
    }
}
