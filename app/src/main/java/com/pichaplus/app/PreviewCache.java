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
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@OptIn(markerClass = UnstableApi.class)
public final class PreviewCache {
    private static final long MAX_BYTES = 300L * 1024 * 1024;
    private static final String[] ALLOWED_HOST_SUFFIXES = {".backblazeb2.com"};
    private static SimpleCache cache;
    private static volatile Boolean genuine;
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
            for (String suf : ALLOWED_HOST_SUFFIXES) {
                if (h.endsWith(suf)) return true;
            }
            return false;
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

    public static void register(String url) {
        if (url == null || !allowed(url)) return;
        if (registered.size() > 500) registered.clear();
        registered.add(keyOf(url));
    }

    public static WebResourceResponse intercept(Context ctx, WebResourceRequest req) {
        try {
            if (!"GET".equals(req.getMethod())) return null;
            String url = req.getUrl().toString();
            if (!allowed(url)) return null;
            String key = keyOf(url);
            if (!registered.contains(key)) return null;
            if (!isGenuine(ctx)) return null;

            String range = null;
            for (Map.Entry<String, String> e : req.getRequestHeaders().entrySet()) {
                if (e.getKey().equalsIgnoreCase("Range")) range = e.getValue();
            }
            long start = 0;
            long end = -1;
            boolean ranged = false;
            if (range != null) {
                if (!range.startsWith("bytes=")) return null;
                String[] p = range.substring(6).split("-", -1);
                if (p.length != 2 || p[0].trim().isEmpty()) return null;
                start = Long.parseLong(p[0].trim());
                if (!p[1].trim().isEmpty()) end = Long.parseLong(p[1].trim());
                ranged = true;
            }

            long len = end >= 0 ? (end - start + 1) : C.LENGTH_UNSET;
            DataSpec spec = new DataSpec.Builder()
                .setUri(Uri.parse(url))
                .setPosition(start)
                .setLength(len)
                .setKey(key)
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                .build();

            final CacheDataSource ds = new CacheDataSource.Factory()
                .setCache(get(ctx))
                .setUpstreamDataSourceFactory(
                    new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource();

            long opened = ds.open(spec);
            long total = ContentMetadata.getContentLength(get(ctx).getContentMetadata(key));
            if (total <= 0 && opened != C.LENGTH_UNSET) total = start + opened;
            if (total <= 0) {
                try { ds.close(); } catch (IOException ignore) {}
                return null;
            }
            if (end < 0 || end >= total) end = total - 1;

            InputStream in = new InputStream() {
                final byte[] one = new byte[1];
                @Override public int read() throws IOException {
                    int n = read(one, 0, 1);
                    return n == -1 ? -1 : (one[0] & 0xFF);
                }
                @Override public int read(byte[] b, int o, int l) throws IOException {
                    if (l == 0) return 0;
                    int n = ds.read(b, o, l);
                    return n == C.RESULT_END_OF_INPUT ? -1 : n;
                }
                @Override public void close() {
                    try { ds.close(); } catch (IOException ignore) {}
                }
            };

            Map<String, String> h = new HashMap<>();
            h.put("Accept-Ranges", "bytes");
            h.put("Access-Control-Allow-Origin", "*");
            h.put("Content-Length", String.valueOf(end - start + 1));
            int code = 200;
            String reason = "OK";
            if (ranged) {
                code = 206;
                reason = "Partial Content";
                h.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
            }
            return new WebResourceResponse("video/mp4", null, code, reason, h, in);
        } catch (Exception e) {
            return null;
        }
    }
}
