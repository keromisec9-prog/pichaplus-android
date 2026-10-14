package com.pichaplus.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import java.util.List;

public class CastHelper {
    private static final int THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert;
    private final Activity act;
    private final WebView web;
    private final CastContext cc;
    private final DlnaBridge dlna;
    private View nativeBtn;
    private volatile String mediaUrl = "";
    private volatile String mediaTitle = "";
    private boolean pendingChromecast = false;
    private AlertDialog searching;

    public CastHelper(Activity a, WebView w, CastContext c) {
        act = a;
        web = w;
        cc = c;
        dlna = new DlnaBridge(a, w);
        dlna.listener = new DlnaBridge.Listener() {
            public void devices(List<String[]> list) { onDevices(list); }
            public void result(boolean ok, String msg) {
                Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
                if (ok) pausePhone();
            }
        };
        if (c == null) return;
        try {
            c.getSessionManager().addSessionManagerListener(new SessionManagerListener<CastSession>() {
                public void onSessionStarting(CastSession s) {}
                public void onSessionStarted(CastSession s, String id) {
                    if (pendingChromecast) {
                        pendingChromecast = false;
                        sendToChromecast();
                    }
                }
                public void onSessionStartFailed(CastSession s, int e) { pendingChromecast = false; }
                public void onSessionEnding(CastSession s) {}
                public void onSessionEnded(CastSession s, int e) { pendingChromecast = false; }
                public void onSessionResuming(CastSession s, String id) {}
                public void onSessionResumed(CastSession s, boolean w) {}
                public void onSessionResumeFailed(CastSession s, int e) {}
                public void onSessionSuspended(CastSession s, int r) {}
            }, CastSession.class);
        } catch (Exception ignore) {
        }
    }

    // Called by the page whenever a movie starts or the player closes
    @JavascriptInterface
    public void setMedia(String url, String title) {
        mediaUrl = url == null ? "" : url;
        mediaTitle = title == null ? "" : title;
    }

    public void attach(View b) {
        nativeBtn = b;
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                if (mediaUrl == null || mediaUrl.isEmpty()) {
                    nativeBtn.performClick();
                } else {
                    showChooser();
                }
            }
            return true;
        });
    }

    private void showChooser() {
        String[] items = {
            "Chromecast  (Google TV, Android TV)",
            "Smart TV  (Samsung, LG, Hisense, TCL...)",
            "Stop casting"
        };
        new AlertDialog.Builder(act, THEME)
            .setTitle("Cast to TV")
            .setItems(items, (d, which) -> {
                if (which == 0) startChromecast();
                else if (which == 1) startSmartTv();
                else stopAll();
            })
            .show();
    }

    private void startChromecast() {
        if (isConnected()) {
            sendToChromecast();
        } else {
            pendingChromecast = true;
            if (nativeBtn != null) nativeBtn.performClick();
        }
    }

    private void startSmartTv() {
        searching = new AlertDialog.Builder(act, THEME)
            .setTitle("Smart TV")
            .setMessage("Searching for TVs...")
            .setNegativeButton("Cancel", (d, w) -> searching = null)
            .show();
        dlna.discover();
    }

    private void onDevices(List<String[]> list) {
        if (searching == null) return;
        try { searching.dismiss(); } catch (Exception ignore) {}
        searching = null;
        if (list.isEmpty()) {
            new AlertDialog.Builder(act, THEME)
                .setTitle("No TVs found")
                .setMessage("Make sure your phone and TV are on the same Wi-Fi and the TV's media streaming (DLNA) is turned on.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        final String[] ids = new String[list.size()];
        final String[] names = new String[list.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = list.get(i)[0];
            names[i] = list.get(i)[1];
        }
        new AlertDialog.Builder(act, THEME)
            .setTitle("Choose your TV")
            .setItems(names, (d, which) -> {
                Toast.makeText(act, "Sending to " + names[which] + "...", Toast.LENGTH_SHORT).show();
                dlna.play(ids[which], mediaUrl, mediaTitle);
            })
            .show();
    }

    private void stopAll() {
        try { dlna.stop(); } catch (Exception ignore) {}
        stop();
    }

    private void pausePhone() {
        web.post(() -> web.evaluateJavascript(
            "var v=document.getElementById('player');if(v){try{v.pause()}catch(e){}}", null));
    }

    private void sendToChromecast() {
        final String url = mediaUrl;
        final String title = mediaTitle;
        web.evaluateJavascript(
            "(function(){var v=document.getElementById('player');return v?v.currentTime:0})()",
            r -> {
                double p = 0;
                try { p = Double.parseDouble(r); } catch (Exception ignore) {}
                loadOnChromecast(url, title, p > 2 ? p : 0);
                pausePhone();
            });
    }

    private void loadOnChromecast(String url, String title, double posSecs) {
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
            Toast.makeText(act, "Playing on TV", Toast.LENGTH_SHORT).show();
        } catch (Exception ignore) {
        }
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
    public void stop() {
        act.runOnUiThread(() -> {
            try { cc.getSessionManager().endCurrentSession(true); } catch (Exception ignore) {}
        });
    }
}
