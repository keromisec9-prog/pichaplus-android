package com.pichaplus.app;

import android.content.Context;
import android.net.Uri;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class PreviewCache {
    private static SimpleCache cache;
    private static CacheDataSource.Factory factory;
    private static final ExecutorService pool = Executors.newSingleThreadExecutor();
    private static final ConcurrentHashMap<String, Boolean> warmed = new ConcurrentHashMap<>();
    private static final AtomicInteger inflight = new AtomicInteger();
    private static final String UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36";

    static synchronized CacheDataSource.Factory factory(Context c) {
        if (factory == null) {
            File dir = new File(c.getCacheDir(), "shorts_media");
            cache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(400L * 1024 * 1024), new StandaloneDatabaseProvider(c));
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(UA).setConnectTimeoutMs(15000).setReadTimeoutMs(20000).setAllowCrossProtocolRedirects(true);
            factory = new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        }
        return factory;
    }

    static boolean busy() { return inflight.get() > 0; }
    static boolean isWarm(String url) { return warmed.containsKey(url); }

    static void warm(final Context c, final String url, final long bytes) {
        if (url == null || warmed.putIfAbsent(url, Boolean.TRUE) != null) return;
        inflight.incrementAndGet();
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    CacheDataSource ds = factory(c).createDataSource();
                    DataSpec spec = new DataSpec.Builder().setUri(Uri.parse(url)).setPosition(0).setLength(bytes).build();
                    new CacheWriter(ds, spec, null, null).cache();
                } catch (Throwable t) {
                } finally { inflight.decrementAndGet(); }
            }
        });
    }
}
