package com.pichaplus.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public class UploadService extends Service {
    private static final String WORKER = "https://picha-plus-worker.kerosoftz522.workers.dev";
    private static final String CH = "picha_uploads";
    private static final int NID = 1701;

    static class St { volatile String state = "starting"; volatile long sent = 0; volatile long size = 0; volatile String title = ""; }
    private static final Map<String, St> LIVE = new ConcurrentHashMap<>();

    static void begin(Context c, String key, String uid) {
        Intent i = new Intent(c, UploadService.class).putExtra("key", key).putExtra("uid", uid);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static boolean isRunning(String key, String uid) { return LIVE.containsKey(key + "|" + uid); }

    static String liveJson() {
        JSONArray a = new JSONArray();
        for (Map.Entry<String, St> e : LIVE.entrySet()) {
            try {
                String[] k = e.getKey().split("\\|", 2);
                St s = e.getValue();
                a.put(new JSONObject().put("key", k[0]).put("uploadId", k[1]).put("state", s.state).put("sent", s.sent).put("size", s.size));
            } catch (Exception ex) {}
        }
        return a.toString();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent in, int flags, int startId) {
        fg("MyPicha+ Studio upload", "Starting...", -1);
        if (in != null) {
            final String key = in.getStringExtra("key"), uid = in.getStringExtra("uid");
            if (key != null && uid != null && !LIVE.containsKey(key + "|" + uid)) {
                final St st = new St();
                LIVE.put(key + "|" + uid, st);
                final Context c = getApplicationContext();
                new Thread(() -> {
                    try { work(c, key, uid, st); } catch (Throwable t) { st.state = "error"; }
                    finally {
                        if ("error".equals(st.state) || "signin".equals(st.state)) sleep(20000);
                        LIVE.remove(key + "|" + uid);
                        if (LIVE.isEmpty()) { stopForeground(true); stopSelf(); }
                    }
                }).start();
            }
        }
        return START_REDELIVER_INTENT;
    }

    private void fg(String title, String text, int pct) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CH, "Uploads", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true);
        if (pct >= 0) b.setProgress(100, pct, false);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); else startForeground(NID, n);
    }

    private void note(St st) {
        int pct = st.size > 0 ? (int) (st.sent * 100 / st.size) : 0;
        String t = st.title.isEmpty() ? "Uploading to Studio" : st.title;
        String s = "waiting".equals(st.state) ? "Waiting for connection" : ("retrying".equals(st.state) ? "Reconnecting" : pct + "%");
        fg(t, s, pct);
    }

    private static JSONObject find(Context c, String key, String uid) {
        try {
            JSONArray a = new JSONObject(UploadStore.list(c)).getJSONArray("uploads");
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.getJSONObject(i);
                if (key.equals(x.optString("key")) && uid.equals(x.optString("uploadId"))) return x;
            }
        } catch (Exception e) {}
        return null;
    }

    private static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && UploadStore.allowedNow(c);
        } catch (Exception e) { return false; }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (Exception e) {} }

    private static String read(InputStream is) throws Exception {
        if (is == null) return "";
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = is.read(b)) > 0) bo.write(b, 0, n);
        is.close();
        return bo.toString("UTF-8");
    }

    private static JSONObject http(String token, String path, String body) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(WORKER + path).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(45000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-Session-Token", token);
            c.getOutputStream().write(body.getBytes("UTF-8"));
            int code = c.getResponseCode();
            String t = read(code < 400 ? c.getInputStream() : c.getErrorStream());
            JSONObject j;
            try { j = new JSONObject(t); } catch (Exception e) { j = new JSONObject(); }
            j.put("_code", code);
            return j;
        } catch (Exception e) { return null; }
    }

    private String putPart(File f, long off, long len, String url, St st, long base) throws Exception {
        HttpURLConnection p = (HttpURLConnection) new URL(url).openConnection();
        p.setRequestMethod("PUT");
        p.setDoOutput(true);
        p.setConnectTimeout(20000);
        p.setReadTimeout(90000);
        p.setRequestProperty("Content-Type", "application/octet-stream");
        p.setFixedLengthStreamingMode(len);
        long lastNote = 0;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r"); OutputStream os = p.getOutputStream()) {
            raf.seek(off);
            byte[] buf = new byte[1 << 16];
            long left = len;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) throw new Exception("short read");
                os.write(buf, 0, n);
                left -= n;
                st.sent = base + (len - left);
                long now = System.currentTimeMillis();
                if (now - lastNote > 1500) { lastNote = now; note(st); }
            }
        }
        int code = p.getResponseCode();
        String et = p.getHeaderField("ETag");
        p.disconnect();
        if (code < 200 || code >= 300 || et == null) throw new Exception("part " + code);
        return et.replace("\"", "");
    }

    private void work(Context c, String key, String uid, St st) throws Exception {
        JSONObject rec = find(c, key, uid);
        if (rec == null) return;
        final String name = rec.optString("name");
        final long size = rec.optLong("size");
        final long ps = rec.optLong("partSize", 10L * 1024 * 1024);
        st.size = size;
        st.title = rec.optString("title", "");
        File f = VideoCompressor.findCopy(c, name, size);
        String token = c.getSharedPreferences("picha_prefs", Context.MODE_PRIVATE).getString("session_token", null);
        if (f == null || token == null) { st.state = f == null ? "error" : "signin"; return; }
        int fails = 0;
        while (true) {
            if (find(c, key, uid) == null) return;
            if (!online(c)) { st.state = "waiting"; note(st); sleep(3000); continue; }
            st.state = "uploading";
            JSONObject r = http(token, "/studio/upload/resume", new JSONObject().put("key", key).put("uploadId", uid).put("size", size).put("partSize", ps).toString());
            if (r != null && r.optInt("_code") == 401) { st.state = "signin"; return; }
            if (r == null || !r.optBoolean("ok")) { st.state = "retrying"; note(st); sleep(Math.min(30000, ++fails * 3000L)); if (fails > 40) { st.state = "error"; return; } continue; }
            if (r.optBoolean("gone")) {
                JSONObject h = http(token, "/studio/upload/head", new JSONObject().put("key", key).toString());
                if (h != null && h.optBoolean("ok") && h.optBoolean("exists") && h.optLong("size") == size) break;
                UploadStore.dropRecord(c, key, uid);
                st.state = "error";
                return;
            }
            TreeMap<Integer, String> parts = new TreeMap<>();
            long sent = 0;
            JSONArray done = r.optJSONArray("done");
            if (done != null) for (int i = 0; i < done.length(); i++) {
                JSONObject d = done.getJSONObject(i);
                int pn = d.optInt("partNumber");
                parts.put(pn, d.optString("etag"));
                sent += Math.min(ps, size - (pn - 1) * ps);
            }
            st.sent = sent;
            JSONObject urls = r.optJSONObject("urls");
            int n = (int) Math.ceil((double) size / ps);
            boolean broke = false;
            for (int k = 1; k <= n && !broke; k++) {
                if (parts.containsKey(k)) continue;
                String url = urls == null ? "" : urls.optString("" + k, "");
                if (url.isEmpty()) { broke = true; break; }
                long len = Math.min(ps, size - (k - 1) * ps);
                try {
                    String et = putPart(f, (k - 1) * ps, len, url, st, sent);
                    parts.put(k, et);
                    sent += len;
                    fails = 0;
                    JSONObject cur = find(c, key, uid);
                    if (cur == null) return;
                    cur.put("pct", (int) (sent * 100 / size));
                    UploadStore.saveUpload(c, cur.toString());
                } catch (Exception e) {
                    st.state = "retrying";
                    note(st);
                    sleep(Math.min(30000, ++fails * 3000L));
                    broke = true;
                }
            }
            if (broke) { if (fails > 40) { st.state = "error"; return; } continue; }
            JSONArray pa = new JSONArray();
            for (Map.Entry<Integer, String> e : parts.entrySet()) pa.put(new JSONObject().put("partNumber", e.getKey()).put("etag", e.getValue()));
            JSONObject fin = http(token, "/studio/upload/complete", new JSONObject().put("key", key).put("uploadId", uid).put("parts", pa).toString());
            if (fin != null && fin.optBoolean("ok")) break;
            st.state = "retrying";
            note(st);
            sleep(Math.min(30000, ++fails * 3000L));
            if (fails > 10) { st.state = "error"; return; }
        }
        JSONObject cur = find(c, key, uid);
        JSONObject sub = cur == null ? null : cur.optJSONObject("submit");
        if (sub != null && sub.optJSONObject("body") != null) {
            boolean ok = false;
            for (int t = 0; t < 20 && !ok; t++) {
                if (!online(c)) { st.state = "waiting"; note(st); sleep(3000); continue; }
                JSONObject s = http(token, sub.optString("path"), sub.optJSONObject("body").toString());
                int code = s == null ? 0 : s.optInt("_code");
                if (code >= 200 && code < 300) ok = true; else sleep(3000L * (t + 1));
            }
            if (!ok) { st.state = "error"; return; }
        }
        st.sent = size;
        st.state = "done";
        if (sub != null && sub.optJSONObject("body") != null) { UploadStore.finishUpload(c, key, uid); } else { try { if (cur != null) { cur.put("done", true); cur.put("pct", 100); UploadStore.saveUpload(c, cur.toString()); } } catch (Exception e) {} }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        nm.notify(NID + 1, b.setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle(sub != null && sub.optJSONObject("body") != null ? "Upload complete" : "Uploaded, details needed").setContentText(st.title.isEmpty() ? name : st.title).build());
    }
}
