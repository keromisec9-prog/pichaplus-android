package com.pichaplus.app;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

public class CastHelper {
    private final Activity act;
    private final WebView web;
    private final CastContext cc;
    private View nativeBtn;

    public CastHelper(Activity a, WebView w, CastContext c) {
        act = a;
        web = w;
        cc = c;
        if (c == null) return;
        try {
            c.getSessionManager().addSessionManagerListener(new SessionManagerListener<CastSession>() {
                public void onSessionStarting(CastSession s) {}
                public void onSessionStarted(CastSession s, String id) { js("window.onChromecastConnected&&window.onChromecastConnected()"); }
                public void onSessionStartFailed(CastSession s, int e) {}
                public void onSessionEnding(CastSession s) {}
                public void onSessionEnded(CastSession s, int e) { js("window.onChromecastEnded&&window.onChromecastEnded()"); }
                public void onSessionResuming(CastSession s, String id) {}
                public void onSessionResumed(CastSession s, boolean w) {}
                public void onSessionResumeFailed(CastSession s, int e) {}
                public void onSessionSuspended(CastSession s, int r) {}
            }, CastSession.class);
        } catch (Exception ignore) {
        }
    }

    private void js(String code) {
        web.post(() -> web.evaluateJavascript(code, null));
    }

    public void attach(View b) {
        nativeBtn = b;
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                js("window.openCastChooser&&window.openCastChooser()");
            }
            return true;
        });
    }

    @JavascriptInterface
    public boolean available() {
        return cc != null;
    }

    @JavascriptInterface
    public boolean isConnected() {
        try {
            CastSession s = cc.getSessionManager().getCurrentCastSession();
            return s != null && s.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void open() {
        act.runOnUiThread(() -> { if (nativeBtn != null) nativeBtn.performClick(); });
    }

    @JavascriptInterface
    public void load(String url, String title, double posSecs) {
        act.runOnUiThread(() -> {
            try {
                CastSession s = cc.getSessionManager().getCurrentCastSession();
                if (s == null) return;
                RemoteMediaClient rmc = s.getRemoteMediaClient();
                if (rmc == null) return;
                MediaMetadata md = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
                md.putString(MediaMetadata.KEY_TITLE, title);
                MediaInfo info = new MediaInfo.Builder(url)
                        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                        .setContentType("video/mp4")
                        .setMetadata(md)
                        .build();
                rmc.load(new MediaLoadRequestData.Builder()
                        .setMediaInfo(info)
                        .setAutoplay(true)
                        .setCurrentTime((long) (posSecs * 1000))
                        .build());
            } catch (Exception ignore) {
            }
        });
    }

    @JavascriptInterface
    public void stop() {
        act.runOnUiThread(() -> {
            try { cc.getSessionManager().endCurrentSession(true); } catch (Exception ignore) {}
        });
    }
}
