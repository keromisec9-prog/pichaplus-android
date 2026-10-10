package com.pichaplus.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class PichaEngine {
    static final ConcurrentHashMap<Long, long[]> LIVE = new ConcurrentHashMap<Long, long[]>();
    static final ConcurrentHashMap<Long, Boolean> STOP = new ConcurrentHashMap<Long, Boolean>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static long lastId = 0;

    static synchronized long newId() {
        long t = System.currentTimeMillis();
        if (t <= lastId) t = lastId + 1;
        lastId = t;
        return t;
    }

    static File dir(Context c) {
        File d = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (d == null) d = new File(c.getFilesDir(), "dl");
        d.mkdirs();
        return d;
    }

    static File part(Context c, long id) { return new File(dir(c), "picha_" + id + ".part"); }
    static File fin(Context c, long id) { return new File(dir(c), "picha_" + id + ".mp4"); }

    static JSONObject entry(Context c, long id) {
        JSONArray a = PichaDownloads.load(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optLong("id") == id) return o;
        }
        return null;
    }

    static void edit(Context c, long id, String key, Object val) {
        synchronized (PichaDownloads.class) {
            try {
                JSONArray a = PichaDownloads.load(c);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && o.optLong("id") == id) {
                        if (val == null) o.remove(key); else o.put(key, val);
                        PichaDownloads.save(c, a);
                        return;
                    }
                }
            } catch (Exception e) {}
        }
    }

    static boolean metered(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm.isActiveNetworkMetered();
        } catch (Exception e) { return false; }
    }

    static void start(Context ctx, final long id, final boolean checkWifi) {
        final Context c = ctx.getApplicationContext();
        long total = 0;
        JSONObject e = entry(c, id);
        if (e != null) total = e.optLong("total");
        if (LIVE.putIfAbsent(id, new long[]{part(c, id).length(), total}) != null) return;
        STOP.remove(id);
        edit(c, id, "err", null);
        PichaDownloadService.ensure(c);
        POOL.execute(() -> work(c, id, checkWifi));
    }

    static void pause(Context c, long id) {
        if (LIVE.containsKey(id)) STOP.put(id, true);
    }

    static void resume(Context c, long id) {
        JSONObject e = entry(c, id);
        if (e == null || !e.optBoolean("own") || e.optBoolean("done")) return;
        start(c, id, false);
    }

    static void cancel(Context c, long id) {
        if (LIVE.containsKey(id)) STOP.put(id, true);
        try { part(c, id).delete(); fin(c, id).delete(); } catch (Exception e) {}
    }

    private static void work(Context c, long id, boolean checkWifi) {
        String err = null;
        try {
            if (STOP.containsKey(id)) return;
            if (checkWifi && PichaDownloads.wifiOnly(c) && metered(c)) { err = "Waiting for Wi-Fi"; return; }
            int r = 2;
            for (int k = 0; k < 4 && r == 2; k++) {
                if (k > 0) { try { Thread.sleep(3000L * k); } catch (Exception e) {} }
                if (STOP.containsKey(id)) return;
                r = attempt(c, id);
            }
            if (r == 2) err = "Connection lost";
            else if (r == 3) err = "Link expired - delete and download again";
        } catch (Throwable t) {
            err = "Error";
        } finally {
            LIVE.remove(id);
            STOP.remove(id);
            edit(c, id, "err", err);
        }
    }

    // 0 done, 1 stopped, 2 retryable error, 3 fatal (link rejected)
    private static int attempt(Context c, long id) {
        HttpURLConnection con = null;
        try {
            JSONObject e = entry(c, id);
            if (e == null) return 1;
            File p = part(c, id);
            long have = p.length();
            con = (HttpURLConnection) new URL(e.optString("url")).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(20000);
            con.setRequestProperty("Accept-Encoding", "identity");
            if (have > 0) con.setRequestProperty("Range", "bytes=" + have + "-");
            int code = con.getResponseCode();
            if (code == 416) { p.delete(); return 2; }
            if (code != 200 && code != 206) return (code == 401 || code == 403 || code == 404) ? 3 : 2;
            long cl = -1;
            try { cl = Long.parseLong(con.getHeaderField("Content-Length")); } catch (Exception x) {}
            long total = -1;
            boolean append = false;
            if (code == 206) {
                append = true;
                String cr = con.getHeaderField("Content-Range");
                if (cr != null && cr.indexOf('/') > 0) {
                    try { total = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim()); } catch (Exception x) {}
                }
                if (total < 0 && cl >= 0) total = have + cl;
            } else {
                total = cl;
                have = 0;
            }
            long[] live = LIVE.get(id);
            if (live == null) return 1;
            live[0] = have;
            live[1] = total;
            if (total > 0) edit(c, id, "total", total);
            InputStream in = con.getInputStream();
            FileOutputStream out = new FileOutputStream(p, append);
            long got = have;
            try {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (STOP.containsKey(id)) return 1;
                    out.write(buf, 0, n);
                    got += n;
                    live[0] = got;
                }
            } finally {
                try { out.close(); } catch (Exception x) {}
                try { in.close(); } catch (Exception x) {}
            }
            if (STOP.containsKey(id)) return 1;
            if (total > 0 && got < total) return 2;
            File f = fin(c, id);
            if (f.exists()) f.delete();
            if (!p.renameTo(f)) return 2;
            edit(c, id, "total", f.length());
            edit(c, id, "done", true);
            return 0;
        } catch (Exception ex) {
            return 2;
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
