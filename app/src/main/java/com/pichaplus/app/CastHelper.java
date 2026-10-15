package com.pichaplus.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CastHelper {
    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final int THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert;
    private final Activity act;
    private final WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile String mediaUrl = "";
    private volatile String mediaTitle = "";
    private volatile String activeControl;
    private volatile String activeName = "";
    private volatile boolean paused = false;
    private android.app.Dialog sheet;
    private android.widget.LinearLayout sheetList;
    private android.widget.TextView sheetStatus;

    public CastHelper(Activity a, WebView w) {
        act = a;
        web = w;
    }

    // The page calls this when a movie starts (url) and when the player closes (empty)
    @JavascriptInterface
    public void setMedia(String url, String title) {
        mediaClipSecs = 0;
        mediaUrl = url == null ? "" : url;
        mediaTitle = title == null ? "" : title;
    }

    // One tap from a page button: opens the TV picker (or the remote sheet if already casting)
    @JavascriptInterface
    public void castNow(String url, String title) {
        mediaClipSecs = 0;
        if (url != null && !url.isEmpty()) {
            mediaUrl = url;
            mediaTitle = title == null ? "" : title;
        }
        ui.post(() -> onTap());
    }

    @JavascriptInterface
    public boolean isCasting() {
        return activeControl != null;
    }

    // Sends a new clip to the TV that is already chosen. No picker.
    @JavascriptInterface
    public boolean sendNext(String url, String title) {
        final String c = activeControl;
        if (c == null || url == null || url.isEmpty()) return false;
        final String t = title == null ? "" : title;
        mediaUrl = url;
        mediaTitle = t;
        paused = false;
        final String u = url;
        pool.execute(() -> {
            try {
                pushMedia(c, u, t);
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(act, "Could not send to TV", Toast.LENGTH_SHORT).show());
            }
        });
        return true;
    }

    private void pushMedia(String control, String url, String title) throws Exception {
        String mime = url.toLowerCase().contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/mp4";
        String didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
            + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
            + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
            + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
            + "<dc:title>" + esc(title) + "</dc:title>"
            + "<upnp:class>object.item.videoItem</upnp:class>"
            + "<res protocolInfo=\"http-get:*:" + mime + ":*\">" + esc(url) + "</res>"
            + "</item></DIDL-Lite>";
        soap(control, "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>" + esc(url) + "</CurrentURI>"
                + "<CurrentURIMetaData>" + esc(didl) + "</CurrentURIMetaData>");
        Thread.sleep(400);
        soap(control, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    private static class RemoteIcon extends View {
        final int kind; // 0 = back 10s, 1 = pause or play, 3 = forward 10s
        boolean paused;
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        RemoteIcon(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f, s = Math.min(w, h);
            p.setColor(0xFFFFFFFF);
            if (kind == 1 && !paused) {
                p.setStyle(android.graphics.Paint.Style.FILL);
                float bw = s * 0.11f, bh = s * 0.26f, gap = s * 0.07f;
                cv.drawRect(cx - gap - bw, cy - bh, cx - gap, cy + bh, p);
                cv.drawRect(cx + gap, cy - bh, cx + gap + bw, cy + bh, p);
            } else if (kind == 1) {
                p.setStyle(android.graphics.Paint.Style.FILL);
                android.graphics.Path t = new android.graphics.Path();
                t.moveTo(cx - s * 0.12f, cy - s * 0.26f);
                t.lineTo(cx - s * 0.12f, cy + s * 0.26f);
                t.lineTo(cx + s * 0.26f, cy);
                t.close();
                cv.drawPath(t, p);
            } else {
                cv.save();
                if (kind == 3) cv.scale(-1f, 1f, cx, cy);
                float r = s * 0.34f;
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.07f);
                cv.drawArc(new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r), -90f, 270f, false, p);
                p.setStyle(android.graphics.Paint.Style.FILL);
                android.graphics.Path a = new android.graphics.Path();
                a.moveTo(cx - s * 0.13f, cy - r);
                a.lineTo(cx + s * 0.05f, cy - r - s * 0.12f);
                a.lineTo(cx + s * 0.05f, cy - r + s * 0.12f);
                a.close();
                cv.drawPath(a, p);
                cv.restore();
                p.setTextAlign(android.graphics.Paint.Align.CENTER);
                p.setTextSize(s * 0.26f);
                p.setFakeBoldText(true);
                cv.drawText("10", cx, cy + s * 0.09f, p);
            }
        }
    }

    private RemoteIcon playIcon;

    private View remoteRow() {
        android.widget.LinearLayout row = new android.widget.LinearLayout(act);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams rl = new android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(16);
        row.setLayoutParams(rl);

        RemoteIcon back = new RemoteIcon(act, 0);
        RemoteIcon fwd = new RemoteIcon(act, 3);
        playIcon = new RemoteIcon(act, 1);
        playIcon.paused = paused;
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(0xFF2A2A2A);
        playIcon.setBackground(circle);

        android.widget.LinearLayout.LayoutParams sm1 = new android.widget.LinearLayout.LayoutParams(dp(60), dp(60));
        sm1.setMargins(dp(14), 0, dp(14), 0);
        android.widget.LinearLayout.LayoutParams sm2 = new android.widget.LinearLayout.LayoutParams(dp(60), dp(60));
        sm2.setMargins(dp(14), 0, dp(14), 0);
        android.widget.LinearLayout.LayoutParams big = new android.widget.LinearLayout.LayoutParams(dp(76), dp(76));
        big.setMargins(dp(14), 0, dp(14), 0);
        row.addView(back, sm1);
        row.addView(playIcon, big);
        row.addView(fwd, sm2);

        back.setOnClickListener(v -> seekBy(-10));
        fwd.setOnClickListener(v -> seekBy(10));
        playIcon.setOnClickListener(v -> {
            togglePause();
            playIcon.paused = paused;
            playIcon.invalidate();
        });
        return row;
    }

    private static int parseTime(String t) {
        try {
            int dot = t.indexOf('.');
            if (dot >= 0) t = t.substring(0, dot);
            String[] q = t.split(":");
            if (q.length != 3) return -1;
            return Integer.parseInt(q[0].trim()) * 3600 + Integer.parseInt(q[1].trim()) * 60 + Integer.parseInt(q[2].trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String fmtTime(int sec) {
        return String.format(java.util.Locale.US, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);
    }

    private void seekBy(final int delta) {
        final String c = activeControl;
        if (c == null) return;
        pool.execute(() -> {
            try {
                String info = soap(c, "GetPositionInfo", "<InstanceID>0</InstanceID>");
                Matcher m = Pattern.compile("(?s)<RelTime>(.*?)</RelTime>").matcher(info);
                int pos = m.find() ? parseTime(m.group(1).trim()) : -1;
                if (pos < 0) throw new Exception("no position");
                String target = fmtTime(Math.max(0, pos + delta));
                try {
                    soap(c, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>" + target + "</Target>");
                } catch (Exception e1) {
                    soap(c, "Seek", "<InstanceID>0</InstanceID><Unit>ABS_TIME</Unit><Target>" + target + "</Target>");
                }
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(act, "This TV doesn't support skipping", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private volatile int mediaClipSecs = 0;
    private volatile long loopToken = 0;

    // Preview clips from the shorts pager: the TV loops a short window of the file
    @JavascriptInterface
    public void castClip(String url, String title, int clipSecs) {
        if (url != null && !url.isEmpty()) {
            mediaUrl = url;
            mediaTitle = title == null ? "" : title;
        }
        mediaClipSecs = clipSecs;
        ui.post(() -> onTap());
    }

    @JavascriptInterface
    public boolean sendClip(String url, String title, int clipSecs) {
        final String c = activeControl;
        if (c == null || url == null || url.isEmpty()) return false;
        final String t = title == null ? "" : title;
        mediaUrl = url;
        mediaTitle = t;
        mediaClipSecs = clipSecs;
        paused = false;
        final String u = url;
        final int clip = clipSecs;
        loopToken++;
        pool.execute(() -> {
            try {
                pushMedia(c, u, t);
                if (clip > 0) startClipLoop(c, clip);
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(act, "Could not send to TV", Toast.LENGTH_SHORT).show());
            }
        });
        return true;
    }

    private static int readTime(String xml, String tag) {
        Matcher m = Pattern.compile("(?s)<" + tag + ">(.*?)</" + tag + ">").matcher(xml);
        return m.find() ? parseTime(m.group(1).trim()) : -1;
    }

    private void seekTo(String control, int secs) throws Exception {
        String t = fmtTime(secs);
        try {
            soap(control, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>" + t + "</Target>");
        } catch (Exception e1) {
            soap(control, "Seek", "<InstanceID>0</InstanceID><Unit>ABS_TIME</Unit><Target>" + t + "</Target>");
        }
    }

    private void startClipLoop(final String control, final int clipSecs) {
        final long token = ++loopToken;
        pool.execute(() -> {
            try {
                int dur = -1;
                for (int i = 0; i < 15 && token == loopToken; i++) {
                    Thread.sleep(1000);
                    String info = soap(control, "GetPositionInfo", "<InstanceID>0</InstanceID>");
                    dur = readTime(info, "TrackDuration");
                    if (dur > 0) break;
                }
                if (token != loopToken) return;
                final int start = dur > 0 ? (int) (dur * 0.27) : 0;
                if (start > 2) seekTo(control, start);
                long lastSeek = System.currentTimeMillis();
                while (token == loopToken && control.equals(activeControl)) {
                    Thread.sleep(1000);
                    if (paused) continue;
                    String info = soap(control, "GetPositionInfo", "<InstanceID>0</InstanceID>");
                    int pos = readTime(info, "RelTime");
                    if (pos >= 0 && pos >= start + clipSecs && System.currentTimeMillis() - lastSeek > 3000) {
                        seekTo(control, start);
                        lastSeek = System.currentTimeMillis();
                    }
                }
            } catch (Exception ignore) {
            }
        });
    }

    private void togglePause() {
        final String c = activeControl;
        if (c == null) return;
        final boolean pause = !paused;
        paused = pause;
        pool.execute(() -> {
            try {
                soap(c, pause ? "Pause" : "Play",
                    pause ? "<InstanceID>0</InstanceID>" : "<InstanceID>0</InstanceID><Speed>1</Speed>");
            } catch (Exception ignore) {
            }
        });
    }

    public void attach(View b) {
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) onTap();
            return true;
        });
    }

    private int dp(int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density);
    }

    private void closeSheet() {
        android.app.Dialog d = sheet;
        sheet = null;
        if (d != null) { try { d.dismiss(); } catch (Exception ignore) {} }
    }

    private void openSheet(String title, String status) {
        closeSheet();
        android.widget.LinearLayout box = new android.widget.LinearLayout(act);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(28));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF1A1A1A);
        float r = dp(16);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        box.setBackground(bg);

        android.widget.TextView t = new android.widget.TextView(act);
        t.setText(title);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(17);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(t);

        sheetStatus = new android.widget.TextView(act);
        sheetStatus.setText(status);
        sheetStatus.setTextColor(0xFFAAAAAA);
        sheetStatus.setTextSize(13);
        sheetStatus.setPadding(0, dp(8), 0, 0);
        box.addView(sheetStatus);

        sheetList = new android.widget.LinearLayout(act);
        sheetList.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.addView(sheetList);

        final android.app.Dialog d = new android.app.Dialog(act, android.R.style.Theme_Translucent_NoTitleBar);
        d.setContentView(box);
        d.setCanceledOnTouchOutside(true);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(android.view.Gravity.BOTTOM);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.6f);
        }
        d.setOnDismissListener(x -> { if (sheet == d) sheet = null; });
        sheet = d;
        d.show();
    }

    private android.view.View sheetBtn(String text, boolean outline, final Runnable action) {
        android.widget.TextView t = new android.widget.TextView(act);
        t.setText(text);
        t.setTextColor(outline ? 0xFFCCCCCC : 0xFFFFFFFF);
        t.setTextSize(outline ? 14 : 15);
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(10));
        if (outline) {
            g.setColor(0x00000000);
            g.setStroke(dp(1), 0xFF444444);
        } else {
            g.setColor(0xFF2A2A2A);
        }
        t.setBackground(g);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
            outline ? android.view.ViewGroup.LayoutParams.WRAP_CONTENT : android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> action.run());
        return t;
    }

    private void onTap() {
        if (activeControl != null) {
            openSheet("Casting to TV", activeName.isEmpty() ? "" : "Playing on " + activeName);
            sheetList.addView(remoteRow());
            if (!mediaUrl.isEmpty()) {
                sheetList.addView(sheetBtn("Cast to another TV", false, () -> startSearch()));
            }
            sheetList.addView(sheetBtn("Stop casting", true, () -> {
                closeSheet();
                stopCasting();
                Toast.makeText(act, "Stopped casting", Toast.LENGTH_SHORT).show();
            }));
            return;
        }
        if (mediaUrl.isEmpty()) {
            Toast.makeText(act, "Start a movie first, then tap here to cast it to your TV", Toast.LENGTH_LONG).show();
            return;
        }
        startSearch();
    }

    private void startSearch() {
        openSheet("Cast to TV", "Searching for TVs...");
        discover();
    }

    private void showDevices(final List<String[]> list) {
        if (sheet == null) return;
        sheetList.removeAllViews();
        if (list.isEmpty()) {
            sheetStatus.setText("No TVs found. Check that the phone and TV are on the same Wi-Fi and that media streaming (DLNA) is on.");
            return;
        }
        sheetStatus.setText("Tap your TV");
        for (final String[] d : list) {
            sheetList.addView(sheetBtn(d[0], false, () -> { closeSheet(); play(d); }));
        }
        if (activeControl != null) {
            sheetList.addView(sheetBtn("Stop casting", true, () -> { closeSheet(); stopCasting(); }));
        }
    }

    private void pausePhone() {
        web.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){try{v.pause()}catch(e){}})", null);
    }

    private void discover() {
        pool.execute(() -> {
            WifiManager wm = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiManager.MulticastLock lock = null;
            DatagramSocket sock = null;
            Set<String> locs = new HashSet<>();
            try {
                if (wm != null) {
                    lock = wm.createMulticastLock("picha-dlna");
                    lock.setReferenceCounted(false);
                    lock.acquire();
                }
                sock = new DatagramSocket();
                sock.setSoTimeout(1000);
                byte[] msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
                    + "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + AVT + "\r\n\r\n").getBytes("UTF-8");
                InetAddress group = InetAddress.getByName("239.255.255.250");
                long end = System.currentTimeMillis() + 4000;
                int sent = 0;
                Pattern loc = Pattern.compile("(?im)^location:\\s*(\\S+)");
                while (System.currentTimeMillis() < end) {
                    if (sent < 2) {
                        sock.send(new DatagramPacket(msg, msg.length, group, 1900));
                        sent++;
                    }
                    try {
                        byte[] buf = new byte[2048];
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        sock.receive(p);
                        Matcher m = loc.matcher(new String(p.getData(), 0, p.getLength(), "UTF-8"));
                        if (m.find()) locs.add(m.group(1));
                    } catch (SocketTimeoutException ignore) {
                    }
                }
            } catch (Exception ignore) {
            } finally {
                if (sock != null) sock.close();
                if (lock != null && lock.isHeld()) lock.release();
            }
            final List<String[]> found = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String l : locs) {
                try {
                    String[] d = describe(l);
                    if (d != null && seen.add(d[1])) found.add(d);
                } catch (Exception ignore) {
                }
            }
            ui.post(() -> showDevices(found));
        });
    }

    // returns {friendly name, AVTransport control URL}
    private String[] describe(String loc) throws Exception {
        String xml = http(loc, "GET", null, null);
        Matcher n = Pattern.compile("(?s)<friendlyName>(.*?)</friendlyName>").matcher(xml);
        String name = n.find() ? unesc(n.group(1).trim()) : "TV";
        Matcher s = Pattern.compile("(?s)<service>(.*?)</service>").matcher(xml);
        while (s.find()) {
            String blk = s.group(1);
            if (!blk.contains("AVTransport")) continue;
            Matcher c = Pattern.compile("(?s)<controlURL>(.*?)</controlURL>").matcher(blk);
            if (c.find()) {
                return new String[]{name, new URL(new URL(loc), c.group(1).trim()).toString()};
            }
        }
        return null;
    }

    private void play(final String[] dev) {
        final String url = mediaUrl;
        final String title = mediaTitle;
        final int clip = mediaClipSecs;
        Toast.makeText(act, "Sending to " + dev[0] + "...", Toast.LENGTH_SHORT).show();
        pool.execute(() -> {
            try {
                String mime = url.toLowerCase().contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/mp4";
                String didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                    + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                    + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                    + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                    + "<dc:title>" + esc(title) + "</dc:title>"
                    + "<upnp:class>object.item.videoItem</upnp:class>"
                    + "<res protocolInfo=\"http-get:*:" + mime + ":*\">" + esc(url) + "</res>"
                    + "</item></DIDL-Lite>";
                soap(dev[1], "SetAVTransportURI",
                    "<InstanceID>0</InstanceID><CurrentURI>" + esc(url) + "</CurrentURI>"
                        + "<CurrentURIMetaData>" + esc(didl) + "</CurrentURIMetaData>");
                Thread.sleep(500);
                soap(dev[1], "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
                activeControl = dev[1]; activeName = dev[0]; paused = false;
                if (clip > 0) startClipLoop(dev[1], clip); else loopToken++;
                ui.post(() -> {
                    Toast.makeText(act, "Playing on " + dev[0], Toast.LENGTH_SHORT).show();
                    pausePhone();
                });
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(act, "Could not play on this TV", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void stopCasting() {
        final String c = activeControl;
        activeControl = null;
        paused = false;
        loopToken++;
        if (c == null) return;
        pool.execute(() -> {
            try { soap(c, "Stop", "<InstanceID>0</InstanceID>"); } catch (Exception ignore) {}
        });
    }

    private String soap(String controlUrl, String action, String args) throws Exception {
        String body = "<?xml version=\"1.0\"?>"
            + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
            + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
            + "<u:" + action + " xmlns:u=\"" + AVT + "\">" + args + "</u:" + action + ">"
            + "</s:Body></s:Envelope>";
        return http(controlUrl, "POST", body, action);
    }

    private String http(String url, String method, String body, String action) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(8000);
        c.setRequestMethod(method);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPACTION", "\"" + AVT + "#" + action + "\"");
            OutputStream o = c.getOutputStream();
            o.write(body.getBytes("UTF-8"));
            o.close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            byte[] b = new byte[4096];
            int r;
            while ((r = in.read(b)) > 0) bo.write(b, 0, r);
            in.close();
        }
        if (code >= 400) throw new Exception("HTTP " + code);
        return bo.toString("UTF-8");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String unesc(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&");
    }
}
