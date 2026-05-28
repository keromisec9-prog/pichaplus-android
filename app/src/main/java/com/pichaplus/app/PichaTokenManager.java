package com.pichaplus.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class PichaTokenManager {
    private static final String PREFS = "picha_prefs";
    private static final String KEY_SESSION = "session_token";

    public static void registerToken(Context ctx) {
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
            if (token != null) sendTokenToServer(ctx, token);
        });
    }

    public static void sendTokenToServer(Context ctx, String fcmToken) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String sessionToken = prefs.getString(KEY_SESSION, null);
        if (sessionToken == null) return;

        new Thread(() -> {
            try {
                URL url = new URL("https://picha-plus-worker.kerosoftz522.workers.dev/register-push");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Session-Token", sessionToken);
                conn.setDoOutput(true);
                String body = "{\"fcmToken\":\"" + fcmToken + "\"}";
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes());
                os.close();
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {
                Log.e("PichaToken", "Failed to send token", e);
            }
        }).start();
    }

    public static void saveSession(Context ctx, String sessionToken) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SESSION, sessionToken).apply();
        registerToken(ctx);
    }
}
