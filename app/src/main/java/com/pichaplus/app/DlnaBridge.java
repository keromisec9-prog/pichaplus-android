package com.pichaplus.app;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DlnaBridge {
    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private final Context ctx;
    private final WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Map<String, String[]> devices = new ConcurrentHashMap<>();
    private volatile String currentControl;
    public interface Listener {
        void devices(java.util.List<String[]> list);
        void result(boolean ok, String msg);
    }
    public volatile Listener listener;

    public DlnaBridge(Context ctx, WebView web) {
        this.ctx = ctx;
        this.web = web;
    }

    private void js(String code) {
        ui.post(() -> web.evaluateJavascript(code, null));
    }

    private void result(boolean ok, String msg) {
        js("window.onDlnaResult&&window.onDlnaResult(" + ok + "," + JSONObject.quote(msg) + ")");
        final Listener L2 = listener;
        if (L2 != null) ui.post(() -> L2.result(ok, msg));
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

    @JavascriptInterface
    public void discover() {
        pool.execute(() -> {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiManager.MulticastLock lock = null;
            DatagramSocket sock = null;
            Set<String> locations = new HashSet<>();
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
                        if (m.find()) locations.add(m.group(1));
                    } catch (SocketTimeoutException ignore) {
                    }
                }
            } catch (Exception ignore) {
            } finally {
                if (sock != null) sock.close();
                if (lock != null && lock.isHeld()) lock.release();
            }
            devices.clear();
            JSONArray out = new JSONArray();
            for (String l : locations) {
                try {
                    String[] d = describe(l);
                    if (d == null) continue;
                    String id = Integer.toHexString(d[1].hashCode());
                    if (devices.containsKey(id)) continue;
                    devices.put(id, d);
                    JSONObject o = new JSONObject();
                    o.put("id", id);
                    o.put("name", d[0]);
                    out.put(o);
                } catch (Exception ignore) {
                }
            }
            js("window.onDlnaDevices&&window.onDlnaDevices(" + out.toString() + ")");
            final Listener L1 = listener;
            if (L1 != null) {
                final java.util.List<String[]> dl = new java.util.ArrayList<>();
                for (Map.Entry<String, String[]> en : devices.entrySet()) dl.add(new String[]{en.getKey(), en.getValue()[0]});
                ui.post(() -> L1.devices(dl));
            }
        });
    }

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

    private void soap(String controlUrl, String action, String args) throws Exception {
        String body = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + AVT + "\">" + args + "</u:" + action + ">"
                + "</s:Body></s:Envelope>";
        http(controlUrl, "POST", body, action);
    }

    @JavascriptInterface
    public void play(String id, String url, String title) {
        pool.execute(() -> {
            String[] d = devices.get(id);
            if (d == null || url == null || !url.matches("(?i)^https?://.+")) {
                result(false, "TV not available");
                return;
            }
            try {
                String mime = url.toLowerCase().contains(".m3u8")
                        ? "application/vnd.apple.mpegurl" : "video/mp4";
                String didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                        + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                        + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                        + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                        + "<dc:title>" + esc(title) + "</dc:title>"
                        + "<upnp:class>object.item.videoItem</upnp:class>"
                        + "<res protocolInfo=\"http-get:*:" + mime + ":*\">" + esc(url) + "</res>"
                        + "</item></DIDL-Lite>";
                soap(d[1], "SetAVTransportURI",
                        "<InstanceID>0</InstanceID><CurrentURI>" + esc(url) + "</CurrentURI>"
                                + "<CurrentURIMetaData>" + esc(didl) + "</CurrentURIMetaData>");
                Thread.sleep(500);
                soap(d[1], "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
                currentControl = d[1];
                result(true, "Playing on " + d[0]);
            } catch (Exception e) {
                result(false, "Could not play on this TV");
            }
        });
    }

    @JavascriptInterface
    public void stop() {
        final String c = currentControl;
        if (c == null) return;
        currentControl = null;
        pool.execute(() -> {
            try {
                soap(c, "Stop", "<InstanceID>0</InstanceID>");
            } catch (Exception ignore) {
            }
        });
    }
}
