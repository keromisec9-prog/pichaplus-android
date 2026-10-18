package com.pichaplus.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Process;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClipStore {
    // Flip to true to only save clips on unmetered (Wi-Fi) connections.
    private static final boolean WIFI_ONLY = false;
    private static final String HOST = "pichaplus-clips.invalid";
    private static final long MAX_BYTES = 300L * 1024 * 1024;
    private static final long WINDOW_US = 32_000_000L;
    private static final int MAX_QUEUE = 12;

    private static final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        });
        t.setDaemon(true);
        return t;
    });
    private static final Set<String> pending =
        Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Set<String> failed =
        Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static volatile Boolean genuine;

    private ClipStore() {}

    private static File dir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), "clips");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String name(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean allowed(String url) {
        try {
            Uri u = Uri.parse(url);
            if (!"https".equals(u.getScheme())) return false;
            String h = u.getHost();
            if (h == null) return false;
            h = h.toLowerCase(Locale.ROOT);
            if (h.equals("localhost") || h.contains(":") || h.matches("[0-9.]+")) return false;
            return h.endsWith(".backblazeb2.com");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isGenuine(Context c) {
        Boolean g = genuine;
        if (g == null) {
            try {
                g = AppIntegrityChecker.isGenuine(c);
            } catch (Throwable t) {
                g = false;
            }
            genuine = g;
        }
        return g;
    }

    private static boolean metered(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null && cm.isActiveNetworkMetered();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Returns a local playable URL if this clip is saved, otherwise "". */
    public static String get(Context ctx, String key) {
        try {
            if (key == null || key.isEmpty()) return "";
            String n = name(key);
            if (n == null) return "";
            File f = new File(dir(ctx), n + ".mp4");
            return (f.isFile() && f.length() > 0) ? "https://" + HOST + "/" + n + ".mp4" : "";
        } catch (Throwable t) {
            return "";
        }
    }

    public static void save(final Context ctx, final String key, final String url, final double startSec) {
        try {
            if (key == null || key.isEmpty() || key.length() > 200 || url == null || !allowed(url)) return;
            final Context c = ctx.getApplicationContext();
            if (!isGenuine(c)) return;
            final String n = name(key);
            if (n == null) return;
            final File fin = new File(dir(c), n + ".mp4");
            if (fin.isFile() || failed.contains(n)) return;
            if (WIFI_ONLY && metered(c)) return;
            if (pending.size() >= MAX_QUEUE || !pending.add(n)) return;
            pool.execute(() -> {
                File tmp = new File(dir(c), n + ".part");
                try {
                    cut(url, startSec, tmp);
                    if (!tmp.renameTo(fin)) throw new IOException("rename failed");
                    trim(c);
                } catch (Throwable t) {
                    failed.add(n);
                    tmp.delete();
                } finally {
                    pending.remove(n);
                }
            });
        } catch (Throwable ignore) {
        }
    }

    private static void cut(String url, double startSec, File out) throws Exception {
        long startUs = (long) (startSec * 1000000d);
        long base;
        MediaExtractor probe = new MediaExtractor();
        try {
            probe.setDataSource(url);
            int vt = -1;
            for (int i = 0; i < probe.getTrackCount(); i++) {
                String mime = probe.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { vt = i; break; }
            }
            if (vt < 0) throw new IOException("no video track");
            probe.selectTrack(vt);
            probe.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            base = probe.getSampleTime();
            if (base < 0) throw new IOException("seek failed");
        } finally {
            probe.release();
        }

        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        boolean started = false;
        try {
            ex.setDataSource(url);
            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Map<Integer, Integer> map = new HashMap<>();
            boolean hasV = false;
            boolean hasA = false;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") && !hasV) hasV = true;
                else if (mime.startsWith("audio/") && !hasA) hasA = true;
                else continue;
                map.put(i, mux.addTrack(f));
                ex.selectTrack(i);
            }
            if (!hasV) throw new IOException("no video");
            ex.seekTo(base, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            mux.start();
            started = true;

            ByteBuffer buf = ByteBuffer.allocate(4 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long endTime = base + WINDOW_US;
            long t0 = System.currentTimeMillis();
            long wrote = 0;
            while (true) {
                int track = ex.getSampleTrackIndex();
                if (track < 0) break;
                long t = ex.getSampleTime();
                if (t > endTime) break;
                Integer mt = map.get(track);
                if (mt != null && t >= base) {
                    long mediaMs = (t - base) / 1000L;
                    long wall = System.currentTimeMillis() - t0;
                    // pace at about 2x realtime so this never hogs the connection
                    if (mediaMs / 2 > wall + 50) Thread.sleep(mediaMs / 2 - wall);
                    buf.clear();
                    int size = ex.readSampleData(buf, 0);
                    if (size < 0) break;
                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = t - base;
                    info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    mux.writeSampleData(mt, buf, info);
                    wrote++;
                }
                if (!ex.advance()) break;
            }
            if (wrote < 10) throw new IOException("clip too short");
        } finally {
            try { if (started && mux != null) mux.stop(); } catch (Throwable ignore) {}
            try { if (mux != null) mux.release(); } catch (Throwable ignore) {}
            try { ex.release(); } catch (Throwable ignore) {}
        }
    }

    private static void trim(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs == null) return;
        long now = System.currentTimeMillis();
        long total = 0;
        List<File> clips = new ArrayList<>();
        for (File f : fs) {
            String n = f.getName();
            if (n.endsWith(".part")) {
                if (now - f.lastModified() > 600000L) f.delete();
            } else if (n.endsWith(".mp4")) {
                clips.add(f);
                total += f.length();
            }
        }
        if (total <= MAX_BYTES) return;
        Collections.sort(clips, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : clips) {
            if (total <= MAX_BYTES) break;
            total -= f.length();
            f.delete();
        }
    }

    /** Serves saved clips to the WebView from the app's own storage. No network involved. */
    public static WebResourceResponse serve(Context ctx, WebResourceRequest req) {
        try {
            Uri u = req.getUrl();
            if (u == null || !HOST.equals(u.getHost())) return null;
            String path = u.getPath();
            if (path == null || !path.matches("/[0-9a-f]{32}\\.mp4")) return null;
            File f = new File(dir(ctx), path.substring(1));
            if (!f.isFile()) return null;
            final long total = f.length();
            if (total <= 0) return null;
            long start = 0;
            long end = total - 1;
            boolean ranged = false;
            for (Map.Entry<String, String> e : req.getRequestHeaders().entrySet()) {
                String v = e.getValue();
                if (e.getKey().equalsIgnoreCase("Range") && v != null && v.startsWith("bytes=")) {
                    String[] p = v.substring(6).split("-", -1);
                    if (p.length == 2 && !p[0].trim().isEmpty()) {
                        start = Long.parseLong(p[0].trim());
                        if (!p[1].trim().isEmpty()) end = Math.min(end, Long.parseLong(p[1].trim()));
                        ranged = true;
                    }
                }
            }
            if (start > end) return null;
            if (start == 0) f.setLastModified(System.currentTimeMillis());

            final FileInputStream fis = new FileInputStream(f);
            fis.getChannel().position(start);
            final long len = end - start + 1;
            InputStream in = new InputStream() {
                long left = len;
                @Override public int read() throws IOException {
                    if (left <= 0) return -1;
                    int b = fis.read();
                    if (b >= 0) left--;
                    return b;
                }
                @Override public int read(byte[] b, int o, int l) throws IOException {
                    if (left <= 0) return -1;
                    int n = fis.read(b, o, (int) Math.min((long) l, left));
                    if (n > 0) left -= n;
                    return n;
                }
                @Override public void close() throws IOException {
                    fis.close();
                }
            };

            Map<String, String> h = new HashMap<>();
            h.put("Accept-Ranges", "bytes");
            h.put("Access-Control-Allow-Origin", "*");
            h.put("Content-Length", String.valueOf(len));
            if (ranged) {
                h.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
                return new WebResourceResponse("video/mp4", null, 206, "Partial Content", h, in);
            }
            return new WebResourceResponse("video/mp4", null, 200, "OK", h, in);
        } catch (Exception e) {
            return null;
        }
    }
}
