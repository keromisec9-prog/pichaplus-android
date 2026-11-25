package com.pichaplus.app;

import android.content.Context;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cronet.CronetDataSource;
import java.util.concurrent.Executors;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import java.io.File;
import org.chromium.net.CronetEngine;

final class NetFactory {
    static String kind = "default";
    private static DataSource.Factory factory;
    private static final String UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36";

    static synchronized DataSource.Factory get(Context c) {
        if (factory != null) return factory;
        DefaultHttpDataSource.Factory fallback = new DefaultHttpDataSource.Factory()
            .setUserAgent(UA).setConnectTimeoutMs(8000).setReadTimeoutMs(8000).setAllowCrossProtocolRedirects(true);
        try {
            CronetEngine engine = new CronetEngine.Builder(c.getApplicationContext())
                .enableHttp2(true).enableQuic(false).enableBrotli(true).build();
            factory = new CronetDataSource.Factory(engine, Executors.newFixedThreadPool(4))
                .setUserAgent(UA).setConnectionTimeoutMs(8000).setReadTimeoutMs(8000)
                .setResetTimeoutOnRedirects(true).setFallbackFactory(fallback);
            kind = "cronet";
        } catch (Throwable t) {
            factory = fallback;
        }
        return factory;
    }

    private static SimpleCache shortsCache;
    private static DataSource.Factory cachedFactory;

    static synchronized DataSource.Factory getCached(Context c) {
        if (cachedFactory != null) return cachedFactory;
        DataSource.Factory up = get(c);
        try {
            if (shortsCache == null) {
                File dir = new File(c.getApplicationContext().getCacheDir(), "shorts_cache");
                shortsCache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(200L * 1024 * 1024), new StandaloneDatabaseProvider(c.getApplicationContext()));
            }
            cachedFactory = new CacheDataSource.Factory()
                .setCache(shortsCache)
                .setUpstreamDataSourceFactory(up)
                .setCacheKeyFactory(new androidx.media3.datasource.cache.CacheKeyFactory() {
                    @Override public String buildCacheKey(androidx.media3.datasource.DataSpec spec) {
                        if (spec.key != null) return spec.key;
                        String u = spec.uri.toString(), h = spec.uri.getHost();
                        int q = u.indexOf('?');
                        boolean b2 = h != null && (h.equals("media.mypicha.uk") || h.endsWith("backblazeb2.com"));
                        return (b2 && q > 0) ? u.substring(0, q) : u;
                    }
                })
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        } catch (Throwable t) {
            cachedFactory = up;
        }
        return cachedFactory;
    }
}
