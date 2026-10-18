package com.pichaplus.app;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.ContentMetadata;
import androidx.media3.datasource.cache.ContentMetadataMutations;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@OptIn(markerClass = UnstableApi.class)
public final class PreviewCache {
    private static final long MAX_BYTES = 300L * 1024 * 1024;
    private static final long CHUNK = 4L * 1024 * 1024;
    private static SimpleCache cache;
    private static final Set<String> registered =
        Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private PreviewCache() {}

    private static synchronized SimpleCache get(Context c) {
        if (cache == null) {
            File dir = new File(c.getApplicationContext().getCacheDir(), "preview_cache");
            cache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                new StandaloneDatabaseProvider(c.getApplicationContext()));
        }
        return cache;
    }

    private static String keyOf(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
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

    public static void register(String url) {
        if (url == null || !allowed(url)) return;
        if (registered.size() > 500) registered.clear();
        registered.add(keyOf(url));
    }

    private static String header(Map<String, List<String>> h, String name) {
        if (h == null) return null;
        for (Map.Entry<String, List<String>> e : h.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)
                && e.getValue() != null && !e.getValue().isEmpty()) return e.getValue().get(0);
        }
        return null;
    }

    public static WebResourceResponse intercept(Context ctx, WebResourceRequest req) {
        CacheDataSource ds = null;
        try {
            if (!"GET".equals(req.getMethod())) return null;
            String url = req.getUrl().toString();
            if (!allowed(url)) return null;
            String key = keyOf(url);
            if (!registered.contains(key)) return null;

            String range = null;
            for (Map.Entry<String, String> e : req.getRequestHeaders().entrySet()) {
                if (e.getKey().equalsIgnoreCase("Range")) range = e.getValue();
            }
            if (range == null || !range.startsWith("bytes=")) return null;
            String[] p = range.substring(6).split("-", -1);
            if (p.length != 2 || p[0].trim().isEmpty()) return null;
            final long start = Long.parseLong(p[0].trim());
            long reqEnd = p[1].trim().isEmpty() ? -1 : Long.parseLong(p[1].trim());
            long want = reqEnd >= 0 ? Math.min(reqEnd - start + 1, CHUNK) : CHUNK;
            if (want <= 0) return null;

            DataSpec spec = new DataSpec.Builder()
                .setUri(Uri.parse(url))
                .setPosition(start)
                .setLength(want)
                .setKey(key)
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                .build();

            ds = new CacheDataSource.Factory()
                .setCache(get(ctx))
                .setUpstreamDataSourceFactory(
                    new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource();
            ds.open(spec);

            long total = -1;
            String cr = header(ds.getResponseHeaders(), "Content-Range");
            if (cr != null) {
                int sl = cr.lastIndexOf('/');
                if (sl > 0) {
                    try { total = Long.parseLong(cr.substring(sl + 1).trim()); } catch (NumberFormatException ignore) {}
                }
                if (total > 0) {
                    try {
                        ContentMetadataMutations m = new ContentMetadataMutations();
                        ContentMetadataMutations.setContentLength(m, total);
                        get(ctx).applyContentMetadataMutations(key, m);
                    } catch (Exception ignore) {}
                }
            }
            if (total <= 0) total = ContentMetadata.getContentLength(get(ctx).getContentMetadata(key));
            final long serve = total > 0 ? Math.min(want, total - start) : -1;
            if (serve <= 0) {
                try { ds.close(); } catch (IOException ignore) {}
                return null;
            }
            final long end = start + serve - 1;
            final CacheDataSource fds = ds;

            InputStream in = new InputStream() {
                long left = serve;
                final byte[] one = new byte[1];
                @Override public int read() throws IOException {
                    int n = read(one, 0, 1);
                    return n == -1 ? -1 : (one[0] & 0xFF);
                }
                @Override public int read(byte[] b, int o, int l) throws IOException {
                    if (l == 0) return 0;
                    if (left <= 0) return -1;
                    int n = fds.read(b, o, (int) Math.min((long) l, left));
                    if (n == C.RESULT_END_OF_INPUT) return -1;
                    left -= n;
                    return n;
                }
                @Override public void close() {
                    try { fds.close(); } catch (IOException ignore) {}
                }
            };

            Map<String, String> h = new HashMap<>();
            h.put("Accept-Ranges", "bytes");
            h.put("Access-Control-Allow-Origin", "*");
            h.put("Content-Length", String.valueOf(serve));
            h.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
            return new WebResourceResponse("video/mp4", null, 206, "Partial Content", h, in);
        } catch (Exception e) {
            try { if (ds != null) ds.close(); } catch (Exception ignore) {}
            return null;
        }
    }
}
