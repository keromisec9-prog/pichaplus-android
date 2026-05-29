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
        String title = message.getNotification() != null ? message.getNotification().getTitle() : "Picha+";
        String body = message.getNotification() != null ? message.getNotification().getBody() : "";
        String imageUrl = message.getNotification() != null && message.getNotification().getImageUrl() != null
            ? message.getNotification().getImageUrl().toString() : null;
        showNotification(title, body, imageUrl);
    }

    private Bitmap getBitmapFromUrl(String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setDoInput(true);
            conn.connect();
            InputStream input = conn.getInputStream();
            return BitmapFactory.decodeStream(input);
        } catch (Exception e) {
            return null;
        }
    }

    private void showNotification(String title, String body, String imageUrl) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Picha+ Notifications", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(channel);
        }
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi);

        if (imageUrl != null) {
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
