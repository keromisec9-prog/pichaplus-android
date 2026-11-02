package com.pichaplus.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

final class UploadStore {
    private static final String WORKER = "https://picha-plus-worker.kerosoftz522.workers.dev";
    private static final Object LOCK = new Object();
    private static boolean flushing = false;

    private static JSONObject load(Context c) {
        JSONObject o = null;
        try (BufferedReader r = new BufferedReader(new FileReader(new File(c.getFilesDir(), "uploads.json")))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            o = new JSONObject(sb.toString());
        } catch (Exception e) {}
        if (o == null) o = new JSONObject();
        try {
            if (o.optJSONArray("uploads") == null) o.put("uploads", new JSONArray());
            if (o.optJSONArray("cancels") == null) o.put("cancels", new JSONArray());
        } catch (Exception e) {}
        return o;
    }

    private static void save(Context c, JSONObject o) {
        File tmp = new File(c.getFilesDir(), "uploads.json.tmp");
        try (FileWriter w = new FileWriter(tmp)) { w.write(o.toString()); }
        catch (Exception e) { return; }
        tmp.renameTo(new File(c.getFilesDir(), "uploads.json"));
    }

    private static JSONObject take(JSONObject o, String key, String uid) throws Exception {
        JSONArray a = o.getJSONArray("uploads"), out = new JSONArray();
        JSONObject hit = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i);
            if (key.equals(x.optString("key")) && uid.equals(x.optString("uploadId"))) hit = x; else out.put(x);
        }
        o.put("uploads", out);
        return hit;
    }

    private static void queueCancel(JSONObject o, String key, String uid) throws Exception {
        JSONArray q = o.getJSONArray("cancels");
        for (int i = 0; i < q.length(); i++) {
            JSONObject x = q.getJSONObject(i);
            if (key.equals(x.optString("key")) && uid.equals(x.optString("uploadId"))) return;
        }
        q.put(new JSONObject().put("key", key).put("uploadId", uid).put("t", System.currentTimeMillis()));
    }

    static void saveUpload(Context c, String json) {
        try {
            JSONObject n = new JSONObject(json);
            String key = n.optString("key"), uid = n.optString("uploadId");
            if (key.isEmpty() || uid.isEmpty()) return;
            synchronized (LOCK) {
                JSONObject o = load(c);
                long started = n.optLong("t", System.currentTimeMillis());
                JSONObject old = take(o, key, uid);
                if (old != null) started = old.optLong("started", started);
                n.put("started", started);
                n.put("t", System.currentTimeMillis());
                o.getJSONArray("uploads").put(n);
                save(c, o);
            }
        } catch (Exception e) {}
    }

    static void dropRecord(Context c, String key, String uid) {
        synchronized (LOCK) {
            try { JSONObject o = load(c); take(o, key, uid); save(c, o); } catch (Exception e) {}
        }
    }

    static void finishUpload(Context c, String key, String uid) {
        synchronized (LOCK) {
            try {
                JSONObject o = load(c);
                JSONObject hit = take(o, key, uid);
                save(c, o);
                if (hit != null) VideoCompressor.deleteCopy(c, hit.optString("name"), hit.optLong("size"));
            } catch (Exception e) {}
        }
    }

    static void cancelUpload(Context c, String key, String uid) {
        synchronized (LOCK) {
            try {
                JSONObject o = load(c);
                JSONObject hit = take(o, key, uid);
                queueCancel(o, key, uid);
                save(c, o);
                if (hit != null) VideoCompressor.deleteCopy(c, hit.optString("name"), hit.optLong("size"));
            } catch (Exception e) {}
        }
        flush(c);
    }

    static long deepClean(Context c) {
        synchronized (LOCK) {
            try {
                JSONObject o = load(c);
                JSONArray a = o.getJSONArray("uploads");
                for (int i = 0; i < a.length(); i++) {
                    JSONObject x = a.getJSONObject(i);
                    queueCancel(o, x.optString("key"), x.optString("uploadId"));
                }
                o.put("uploads", new JSONArray());
                o.put("cancelAll", true);
                o.put("cancelAllAt", System.currentTimeMillis());
                save(c, o);
            } catch (Exception e) {}
        }
        long freed = VideoCompressor.clearTemp(c);
        flush(c);
        return freed;
    }

    static String list(Context c) {
        synchronized (LOCK) {
            try {
                JSONObject o = load(c);
                JSONObject r = new JSONObject();
                r.put("uploads", o.getJSONArray("uploads"));
                r.put("queued", o.getJSONArray("cancels").length() + (o.optBoolean("cancelAll", false) ? 1 : 0));
                return r.toString();
            } catch (Exception e) { return "{\"uploads\":[],\"queued\":0}"; }
        }
    }

    static void sweepOrphans(Context c) {
        Set<String> keep = new HashSet<>();
        synchronized (LOCK) {
            try {
                JSONArray a = load(c).getJSONArray("uploads");
                for (int i = 0; i < a.length(); i++) {
                    JSONObject x = a.getJSONObject(i);
                    keep.add(x.optString("name") + "|" + x.optLong("size"));
                }
            } catch (Exception e) {}
        }
        VideoCompressor.sweepOrphans(c, keep);
    }

    static String getNet(Context c) {
        return c.getSharedPreferences("pp_prefs", Context.MODE_PRIVATE).getString("up_net", "both");
    }

    static void setNet(Context c, String v) {
        if (!"wifi".equals(v) && !"mobile".equals(v)) v = "both";
        c.getSharedPreferences("pp_prefs", Context.MODE_PRIVATE).edit().putString("up_net", v).apply();
    }

    static boolean allowedNow(Context c) {
        String n = VideoCompressor.networkType(c), s = getNet(c);
        if ("none".equals(n)) return false;
        if ("both".equals(s)) return true;
        return s.equals(n);
    }

    static void flush(final Context ctx) {
        final Context c = ctx.getApplicationContext();
        synchronized (LOCK) { if (flushing) return; flushing = true; }
        new Thread(() -> {
            try { run(c); } catch (Exception e) {}
            finally { synchronized (LOCK) { flushing = false; } }
        }).start();
    }

    private static void run(Context c) throws Exception {
        String token = c.getSharedPreferences("picha_prefs", Context.MODE_PRIVATE).getString("session_token", null);
        if (token == null) return;
        for (int guard = 0; guard < 20; guard++) {
            JSONArray items = new JSONArray();
            boolean all;
            long at;
            int queued;
            synchronized (LOCK) {
                JSONObject o = load(c);
                JSONArray q = o.getJSONArray("cancels");
                queued = q.length();
                for (int i = 0; i < q.length() && i < 15; i++) {
                    JSONObject x = q.getJSONObject(i);
                    items.put(new JSONObject().put("key", x.optString("key")).put("uploadId", x.optString("uploadId")));
                }
                all = o.optBoolean("cancelAll", false);
                at = o.optLong("cancelAllAt", System.currentTimeMillis());
            }
            if (items.length() == 0 && !all) return;
            if (items.length() > 0) {
                String res = post(token, "/studio/upload/cancel-batch", new JSONObject().put("items", items).toString());
                if (res == null) return;
                JSONObject j = new JSONObject(res);
                JSONArray rs = j.optJSONArray("results");
                if (!j.optBoolean("ok") || rs == null) return;
                int left;
                synchronized (LOCK) {
                    JSONObject o = load(c);
                    JSONArray q = o.getJSONArray("cancels"), keep = new JSONArray();
                    for (int i = 0; i < q.length(); i++) {
                        JSONObject x = q.getJSONObject(i);
                        boolean done = false;
                        for (int k = 0; k < rs.length(); k++) {
                            JSONObject r = rs.getJSONObject(k);
                            if (x.optString("key").equals(r.optString("key")) && x.optString("uploadId").equals(r.optString("uploadId"))
                                && (r.optBoolean("ok") || "invalid".equals(r.optString("error")))) { done = true; break; }
                        }
                        if (!done) keep.put(x);
                    }
                    o.put("cancels", keep);
                    save(c, o);
                    left = keep.length();
                }
                if (left >= queued) return;
            } else {
                String res = post(token, "/studio/upload/cancel-all", new JSONObject().put("before", at).toString());
                if (res == null || !new JSONObject(res).optBoolean("ok")) return;
                synchronized (LOCK) {
                    JSONObject o = load(c);
                    if (o.optLong("cancelAllAt", 0) <= at) { o.put("cancelAll", false); save(c, o); }
                }
            }
        }
    }

    private static String post(String token, String path, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(WORKER + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Session-Token", token);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes("UTF-8")); }
            if (conn.getResponseCode() != 200) return null;
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
