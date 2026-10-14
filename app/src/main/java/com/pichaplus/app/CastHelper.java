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
    private AlertDialog searching;

    public CastHelper(Activity a, WebView w) {
        act = a;
        web = w;
    }

    // The page calls this when a movie starts (url) and when the player closes (empty)
    @JavascriptInterface
    public void setMedia(String url, String title) {
        mediaUrl = url == null ? "" : url;
        mediaTitle = title == null ? "" : title;
    }

    public void attach(View b) {
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) onTap();
            return true;
        });
    }

    private void onTap() {
        if (mediaUrl.isEmpty()) {
            Toast.makeText(act, "Start a movie first, then tap here to cast it to your TV", Toast.LENGTH_LONG).show();
            return;
        }
        if (activeControl != null) {
            new AlertDialog.Builder(act, THEME)
                .setTitle("Casting to TV")
                .setItems(new String[]{"Cast to another TV", "Stop casting"}, (d, which) -> {
                    if (which == 0) startSearch(); else stopCasting();
                })
                .show();
        } else {
            startSearch();
        }
    }

    private void startSearch() {
        searching = new AlertDialog.Builder(act, THEME)
            .setTitle("Cast to TV")
            .setMessage("Searching for TVs...")
            .setNegativeButton("Cancel", (d, w) -> searching = null)
            .setOnCancelListener(d -> searching = null)
            .show();
        discover();
    }

    private void showDevices(final List<String[]> list) {
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
        final String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i)[0];
        new AlertDialog.Builder(act, THEME)
            .setTitle("Choose your TV")
            .setItems(names, (d, which) -> play(list.get(which)))
            .show();
    }

    private void pausePhone() {
        web.evaluateJavascript("var v=document.getElementById('player');if(v){try{v.pause()}catch(e){}}", null);
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
                activeControl = dev[1];
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
        if (c == null) return;
        pool.execute(() -> {
            try { soap(c, "Stop", "<InstanceID>0</InstanceID>"); } catch (Exception ignore) {}
        });
    }

    private void soap(String controlUrl, String action, String args) throws Exception {
        String body = "<?xml version=\"1.0\"?>"
            + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
            + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
            + "<u:" + action + " xmlns:u=\"" + AVT + "\">" + args + "</u:" + action + ">"
            + "</s:Body></s:Envelope>";
        http(controlUrl, "POST", body, action);
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
