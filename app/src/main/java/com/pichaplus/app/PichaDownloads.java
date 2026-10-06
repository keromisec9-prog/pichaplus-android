package com.pichaplus.app;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PichaDownloads {
    private static final String PREFS = "picha_downloads";
    private static final String KEY = "list";

    private static synchronized JSONArray load(Context c) {
        try {
            return new JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"));
        } catch (Exception e) { return new JSONArray(); }
    }

    private static synchronized void save(Context c, JSONArray a) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply();
    }

    private static File posterFile(Context c, long id) {
        File d = new File(c.getFilesDir(), "posters");
        if (!d.exists()) d.mkdirs();
        return new File(d, id + ".jpg");
    }

    private static void cachePoster(final Context c, final long id, final String url) {
        if (url == null || !url.startsWith("http")) return;
        new Thread(() -> {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(10000);
                con.setReadTimeout(15000);
                InputStream in = con.getInputStream();
                FileOutputStream out = new FileOutputStream(posterFile(c, id));
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
            } catch (Exception e) {}
        }).start();
    }

    private static String posterData(Context c, long id) {
        try {
            File f = posterFile(c, id);
            if (!f.exists()) return "";
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { return ""; }
    }

    private static boolean fileOk(DownloadManager dm, long id) {
        try {
            ParcelFileDescriptor p = dm.openDownloadedFile(id);
            p.close();
            return true;
        } catch (Exception e) { return false; }
    }

    public static long freeBytes() {
        try {
            StatFs s = new StatFs(Environment.getExternalStorageDirectory().getPath());
            return s.getAvailableBytes();
        } catch (Exception e) { return 0; }
    }

    public static long queue(Context c, String url, String filename, String title, String posterUrl) {
        try {
            if (title == null) title = "";
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setMimeType("video/mp4");
            r.setTitle(title.isEmpty() ? "Picha+" : title);
            r.setDescription("Downloading via Picha+...");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setAllowedOverMetered(true);
            r.setAllowedOverRoaming(true);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                "picha_" + System.currentTimeMillis() + ".mp4");
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            long id = dm.enqueue(r);
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            o.put("ts", System.currentTimeMillis());
            JSONArray a = load(c);
            a.put(o);
            save(c, a);
            cachePoster(c, id, posterUrl);
            return id;
        } catch (Exception e) { return -1; }
    }

    public static String list(Context c) {
        try {
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            SharedPreferences pp = c.getSharedPreferences("picha_pos", Context.MODE_PRIVATE);
            JSONArray a = load(c);
            JSONArray keep = new JSONArray();
            JSONArray out = new JSONArray();
            boolean changed = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                long id = o.optLong("id");
                String status = "gone";
                long bytes = 0;
                Cursor cur = dm.query(new DownloadManager.Query().setFilterById(id));
                if (cur != null) {
                    try {
                        if (cur.moveToFirst()) {
                            int st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                            bytes = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                            if (st == DownloadManager.STATUS_SUCCESSFUL) status = fileOk(dm, id) ? "done" : "gone";
                            else if (st == DownloadManager.STATUS_FAILED) status = "failed";
                            else status = "running";
                        }
                    } finally { cur.close(); }
                }
                if (status.equals("gone") || status.equals("failed")) {
                    posterFile(c, id).delete();
                    changed = true;
                    continue;
                }
                keep.put(o);
                JSONObject j = new JSONObject();
                j.put("id", id);
                j.put("title", o.optString("title"));
                j.put("status", status);
                j.put("bytes", bytes);
                j.put("ts", o.optLong("ts"));
                j.put("pos", pp.getInt("pos_" + id, 0));
                j.put("dur", pp.getInt("dur_" + id, 0));
                j.put("seen", pp.getLong("seen_" + id, 0));
                j.put("poster", posterData(c, id));
                out.put(j);
            }
            if (changed) save(c, keep);
            return out.toString();
        } catch (Exception e) { return "[]"; }
    }

    public static boolean play(Context c, long id) {
        try {
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            if (!fileOk(dm, id)) return false;
            Uri u = dm.getUriForDownloadedFile(id);
            if (u == null) return false;
            String title = "";
            JSONArray a = load(c);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && o.optLong("id") == id) title = o.optString("title");
            }
            Intent it = new Intent(c, PlayerActivity.class);
            it.putExtra("uri", u.toString());
            it.putExtra("id", id);
            it.putExtra("title", title);
            Matcher m = Pattern.compile("^(.*?)\\s+E(\\d+)$").matcher(title);
            if (m.matches()) {
                String want = m.group(1) + " E" + (Integer.parseInt(m.group(2)) + 1);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && want.equals(o.optString("title"))) {
                        long nid = o.optLong("id");
                        if (fileOk(dm, nid)) {
                            it.putExtra("nextId", nid);
                            it.putExtra("nextTitle", want);
                            File pf = posterFile(c, nid);
                            if (pf.exists()) it.putExtra("nextPoster", pf.getAbsolutePath());
                        }
                    }
                }
            }
            c.startActivity(it);
            return true;
        } catch (Exception e) { return false; }
    }

    public static void remove(Context c, long id) {
        try {
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            dm.remove(id);
        } catch (Exception e) {}
        posterFile(c, id).delete();
        c.getSharedPreferences("picha_pos", Context.MODE_PRIVATE).edit()
            .remove("pos_" + id).remove("dur_" + id).remove("seen_" + id).apply();
        JSONArray a = load(c);
        JSONArray keep = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optLong("id") != id) keep.put(o);
        }
        save(c, keep);
    }
}
