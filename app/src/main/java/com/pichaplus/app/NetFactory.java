package com.pichaplus.app;

import android.content.Context;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cronet.CronetDataSource;
import java.util.concurrent.Executors;
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
                .enableHttp2(true).enableQuic(true).enableBrotli(true).build();
            factory = new CronetDataSource.Factory(engine, Executors.newFixedThreadPool(4))
                .setUserAgent(UA).setConnectionTimeoutMs(8000).setReadTimeoutMs(8000)
                .setResetTimeoutOnRedirects(true).setFallbackFactory(fallback);
            kind = "cronet";
        } catch (Throwable t) {
            factory = fallback;
        }
        return factory;
    }
}
