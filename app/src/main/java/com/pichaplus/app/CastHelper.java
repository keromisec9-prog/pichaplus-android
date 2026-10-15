package com.pichaplus.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CastHelper {
    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int BG = 0xFF0D0F14;
    private static final int PANEL = 0xFF12141A;
    private static final int CARD = 0xFF181B22;
    private static final int LINE = 0xFF2A2E38;
    private static final int RED = 0xFFE8344E;
    private static final int GOLD = 0xFFE5B93C;
    private static final int GREEN = 0xFF2ED47A;
    private static final int GREY = 0xFF9AA0AC;
    private static final int IC_PLAY = com.google.android.gms.cast.framework.R.drawable.cast_ic_mini_controller_play_large;
    private static final int IC_PAUSE = com.google.android.gms.cast.framework.R.drawable.cast_ic_mini_controller_pause_large;

    private final Activity act;
    private final WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final SharedPreferences prefs;

    // what the page is showing right now (a candidate to cast)
    private volatile String mediaUrl = "";
    private volatile String mediaTitle = "";
    private volatile int mediaClipSecs = 0;
    private volatile int mediaClipStart = -1;
    private volatile String artBackdrop = "";
    private volatile String artPoster = "";

    // what is on the TV right now
    private volatile String activeControl;
    private volatile String activeName = "";
    private volatile String castUrl = "";
    private volatile String castTitle = "";
    private volatile int castClipSecs = 0;
    private volatile int castClipStart = -1;
    private volatile String castBackdrop = "";
    private volatile String castPoster = "";
    private volatile boolean paused = false;
    private volatile long loopToken = 0;
    private volatile int loopStart = 0;

    // search sheet
    private Dialog sheet;
    private LinearLayout sheetList;
    private TextView sheetStatus;

    // controller screen
    private Dialog ctrl;
    private volatile boolean ctrlOpen = false;
    private boolean userSeeking = false;
    private int curDur = 0;
    private volatile int webDur = 0;
    private volatile boolean openCtrlAfter = false;
    private volatile int lastPos = 0;
    private TextView cElapsed, cRemain, cCardTime;
    private SeekBar cSeek;
    private ProgressBar cCardBar;
    private ImageView cPlay;

    public CastHelper(Activity a, WebView w) {
        act = a;
        web = w;
        prefs = a.getSharedPreferences("picha_cast", Context.MODE_PRIVATE);
        restore();
    }

    // ---------------------------------------------------------------- page bridge

    @JavascriptInterface
    public void setMedia(String url, String title) {
        mediaUrl = url == null ? "" : url;
        mediaTitle = title == null ? "" : title;
        mediaClipSecs = 0;
        mediaClipStart = -1;
        artBackdrop = "";
        artPoster = "";
    }

    @JavascriptInterface
    public void setArt(String backdrop, String poster) {
        artBackdrop = backdrop == null ? "" : backdrop;
        artPoster = poster == null ? "" : poster;
    }

    @JavascriptInterface
    public void setDuration(int secs) {
        webDur = secs > 0 ? secs : 0;
    }

    @JavascriptInterface
    public void castNow(String url, String title) {
        castClip(url, title, 0, -1);
    }

    @JavascriptInterface
    public void castClip(String url, String title, int clipSecs, int startSecs) {
        if (url != null && !url.isEmpty()) {
            mediaUrl = url;
            mediaTitle = title == null ? "" : title;
        }
        mediaClipSecs = clipSecs;
        mediaClipStart = startSecs;
        ui.post(() -> onTap());
    }

    @JavascriptInterface
    public boolean isCasting() {
        return activeControl != null;
    }

    @JavascriptInterface
    public boolean available() {
        return false;
    }

    @JavascriptInterface
    public void stop() {
        ui.post(() -> stopCasting());
    }

    @JavascriptInterface
    public boolean sendNext(String url, String title) {
        return sendClip(url, title, 0, -1);
    }

    @JavascriptInterface
    public boolean sendClip(String url, String title, int clipSecs, int startSecs) {
        final String c = activeControl;
        if (c == null || url == null || url.isEmpty()) return false;
        final String t = title == null ? "" : title;
        final String u = url;
        final int clip = clipSecs;
        final int st = startSecs;
        mediaUrl = u;
        mediaTitle = t;
        mediaClipSecs = clip;
        mediaClipStart = st;
        castUrl = u;
        castTitle = t;
        castClipSecs = clip;
        castClipStart = st;
        castBackdrop = artBackdrop;
        castPoster = artPoster;
        paused = false;
        loopToken++;
        save();
        pool.execute(() -> {
            try {
                pushMedia(c, u, t);
                if (clip > 0) startClipLoop(c, clip, st);
            } catch (Exception e) {
                toast("Could not send to TV");
            }
        });
        return true;
    }

    public void attach(View b) {
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) onTap();
            return true;
        });
    }

    // ---------------------------------------------------------------- session memory

    private void save() {
        SharedPreferences.Editor e = prefs.edit();
        if (activeControl == null) {
            e.clear();
        } else {
            e.putString("control", activeControl);
            e.putString("name", activeName);
            e.putString("url", castUrl);
            e.putString("title", castTitle);
            e.putInt("clip", castClipSecs);
            e.putInt("clipStart", castClipStart);
            e.putString("backdrop", castBackdrop);
            e.putString("poster", castPoster);
            e.putBoolean("paused", paused);
        }
        e.apply();
    }

    private void forget(String control) {
        if (control != null && control.equals(activeControl)) {
            activeControl = null;
            paused = false;
            loopToken++;
            save();
        }
    }

    private void restore() {
        final String c = prefs.getString("control", null);
        if (c == null) return;
        activeControl = c;
        activeName = prefs.getString("name", "");
        castUrl = prefs.getString("url", "");
        castTitle = prefs.getString("title", "");
        castClipSecs = prefs.getInt("clip", 0);
        castClipStart = prefs.getInt("clipStart", -1);
        castBackdrop = prefs.getString("backdrop", "");
        castPoster = prefs.getString("poster", "");
        paused = prefs.getBoolean("paused", false);
        pool.execute(() -> {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    String info = soap(c, "GetTransportInfo", "<InstanceID>0</InstanceID>");
                    Matcher m = Pattern.compile("(?s)<CurrentTransportState>(.*?)</CurrentTransportState>").matcher(info);
                    String st = m.find() ? m.group(1).trim() : "";
                    if (st.equals("PLAYING") || st.equals("TRANSITIONING")) {
                        paused = false;
                        if (castClipSecs > 0) startClipLoop(c, castClipSecs, castClipStart);
                    } else if (st.equals("PAUSED_PLAYBACK")) {
                        paused = true;
                        if (castClipSecs > 0) startClipLoop(c, castClipSecs, castClipStart);
                    } else {
                        forget(c);
                    }
                    return;
                } catch (Exception e) {
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
                }
            }
            forget(c);
        });
    }

    // ---------------------------------------------------------------- tap handling

    private void onTap() {
        if (activeControl != null) {
            if (!mediaUrl.isEmpty() && !mediaUrl.equals(castUrl)) {
                openCtrlAfter = true;
                play(new String[]{activeName, activeControl});
                return;
            }
            showController();
            return;
        }
        if (mediaUrl.isEmpty()) {
            toast("Start a movie first, then tap here to cast it to your TV");
            return;
        }
        startSearch();
    }

    private void castAnother() {
        if (!castUrl.isEmpty()) {
            mediaUrl = castUrl;
            mediaTitle = castTitle;
            mediaClipSecs = castClipSecs;
            mediaClipStart = castClipStart;
            artBackdrop = castBackdrop;
            artPoster = castPoster;
        }
        if (mediaUrl.isEmpty()) {
            toast("Nothing to cast");
            return;
        }
        startSearch();
    }

    // ---------------------------------------------------------------- small UI helpers

    private int dp(int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(final String m) {
        ui.post(() -> Toast.makeText(act, m, Toast.LENGTH_SHORT).show());
    }

    private GradientDrawable round(int color, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor);
        return g;
    }

    private TextView text(String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private static String prettyName(String n) {
        if (n == null || n.trim().isEmpty()) return "TV";
        return n.trim().replaceAll("(?i)\\btv\\b", "TV");
    }

    private static String capital(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String fmtShort(int sec) {
        if (sec < 0) sec = 0;
        int h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                     : String.format(Locale.US, "%d:%02d", m, s);
    }

    private void loadImage(final String url, final ImageView iv) {
        if (url == null || url.isEmpty()) return;
        pool.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                InputStream in = c.getInputStream();
                final Bitmap b = BitmapFactory.decodeStream(in);
                in.close();
                if (b != null) ui.post(() -> iv.setImageBitmap(b));
            } catch (Exception ignore) {
            }
        });
    }

    // ---------------------------------------------------------------- icons

    private static class Glyph extends View {
        static final int BACK = 0, CHEVRON = 1, CAST = 2, DEVICE = 3, DEVICE_ADD = 4, STOP = 5, PLAY = 6, REPLAY10 = 7, FORWARD10 = 8;
        final int kind;
        int color = 0xFFFFFFFF;
        int bgColor = 0xFF181B22;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Glyph(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        Glyph color(int c) {
            color = c;
            return this;
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h);
            cv.save();
            cv.translate((w - s) / 2f, (h - s) / 2f);
            cv.scale(s / 24f, s / 24f);
            p.setColor(color);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            if (kind == BACK || kind == CHEVRON) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.2f);
                Path a = new Path();
                if (kind == BACK) {
                    a.moveTo(15, 5); a.lineTo(8, 12); a.lineTo(15, 19);
                } else {
                    a.moveTo(9, 5); a.lineTo(16, 12); a.lineTo(9, 19);
                }
                cv.drawPath(a, p);
            } else if (kind == CAST) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f);
                Path a = new Path();
                a.moveTo(2, 8); a.lineTo(2, 4); a.lineTo(22, 4); a.lineTo(22, 20); a.lineTo(14, 20);
                cv.drawPath(a, p);
                p.setStrokeCap(Paint.Cap.BUTT);
                cv.drawArc(new RectF(-5, 15, 7, 27), -90, 90, false, p);
                cv.drawArc(new RectF(-9, 11, 11, 31), -90, 90, false, p);
                p.setStyle(Paint.Style.FILL);
                cv.drawArc(new RectF(-2, 18, 4, 24), -90, 90, true, p);
            } else if (kind == DEVICE) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.8f);
                cv.drawRoundRect(new RectF(2.5f, 4, 21.5f, 16), 2.5f, 2.5f, p);
                cv.drawLine(12, 16, 12, 19.5f, p);
                cv.drawLine(8, 19.5f, 16, 19.5f, p);
            } else if (kind == DEVICE_ADD) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.8f);
                cv.drawRoundRect(new RectF(2, 3.5f, 19, 14.5f), 2.5f, 2.5f, p);
                cv.drawLine(10.5f, 14.5f, 10.5f, 18f, p);
                cv.drawLine(7, 18f, 14, 18f, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(bgColor);
                cv.drawCircle(18, 17.5f, 5.6f, p);
                p.setColor(color);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.6f);
                cv.drawCircle(18, 17.5f, 4.4f, p);
                cv.drawLine(18, 15.3f, 18, 19.7f, p);
                cv.drawLine(15.8f, 17.5f, 20.2f, 17.5f, p);
            } else if (kind == STOP) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.8f);
                cv.drawCircle(12, 12, 9.5f, p);
                p.setStyle(Paint.Style.FILL);
                cv.drawRoundRect(new RectF(8.5f, 8.5f, 15.5f, 15.5f), 1f, 1f, p);
            } else if (kind == PLAY) {
                p.setStyle(Paint.Style.FILL);
                Path a = new Path();
                a.moveTo(7, 4); a.lineTo(7, 20); a.lineTo(20, 12); a.close();
                cv.drawPath(a, p);
            } else {
                cv.save();
                if (kind == FORWARD10) cv.scale(-1f, 1f, 12f, 12f);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.7f);
                p.setStrokeCap(Paint.Cap.BUTT);
                cv.drawArc(new RectF(12 - 8.2f, 12 - 8.2f, 12 + 8.2f, 12 + 8.2f), -90f, 270f, false, p);
                p.setStyle(Paint.Style.FILL);
                Path a = new Path();
                a.moveTo(12 - 3.1f, 12 - 8.2f);
                a.lineTo(12 + 1.2f, 12 - 8.2f - 2.9f);
                a.lineTo(12 + 1.2f, 12 - 8.2f + 2.9f);
                a.close();
                cv.drawPath(a, p);
                cv.restore();
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(6.4f);
                p.setFakeBoldText(true);
                cv.drawText("10", 12f, 14.2f, p);
            }
            cv.restore();
        }
    }

    // ---------------------------------------------------------------- TV search sheet

    private void closeSheet() {
        Dialog d = sheet;
        sheet = null;
        if (d != null) {
            try { d.dismiss(); } catch (Exception ignore) {}
        }
    }

    private void openSheet(String title, String status) {
        closeSheet();
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(28));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1A1A1A);
        float r = dp(16);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        box.setBackground(bg);

        box.addView(text(title, 17, 0xFFFFFFFF, true));
        sheetStatus = text(status, 13, 0xFFAAAAAA, false);
        sheetStatus.setPadding(0, dp(8), 0, 0);
        box.addView(sheetStatus);
        sheetList = new LinearLayout(act);
        sheetList.setOrientation(LinearLayout.VERTICAL);
        box.addView(sheetList);

        final Dialog d = new Dialog(act, android.R.style.Theme_Translucent_NoTitleBar);
        d.setContentView(box);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(MATCH, WRAP);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.6f);
        }
        d.setOnDismissListener(x -> { if (sheet == d) sheet = null; });
        sheet = d;
        d.show();
    }

    private View sheetBtn(String label, final Runnable action) {
        TextView t = text(label, 15, 0xFFFFFFFF, false);
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        t.setBackground(round(0xFF2A2A2A, 10, 0, 0));
        t.setLayoutParams(lp(MATCH, WRAP, 0, 10, 0, 0));
        t.setOnClickListener(v -> action.run());
        return t;
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
            sheetList.addView(sheetBtn(d[0], () -> { closeSheet(); play(d); }));
        }
    }

    // ---------------------------------------------------------------- controller screen

    private void closeController() {
        Dialog d = ctrl;
        ctrl = null;
        ctrlOpen = false;
        if (d != null) {
            try { d.dismiss(); } catch (Exception ignore) {}
        }
    }

    private void showController() {
        closeController();
        closeSheet();
        final Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        d.setCanceledOnTouchOutside(false);
        final String shownTitle = castTitle == null ? "" : castTitle.toUpperCase(Locale.getDefault());

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        FrameLayout top = new FrameLayout(act);
        root.addView(top, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        final ImageView backdrop = new ImageView(act);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        top.addView(backdrop, new FrameLayout.LayoutParams(MATCH, MATCH));
        View fade = new View(act);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0x99000000, 0x11000000, 0x00000000, 0x66000000}));
        top.addView(fade, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(30), dp(16), 0);
        Glyph back = new Glyph(act, Glyph.BACK);
        bar.addView(back, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView ttl = text(shownTitle, 14, 0xFFFFFFFF, true);
        ttl.setSingleLine(true);
        ttl.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        tl.setMargins(dp(14), 0, dp(10), 0);
        bar.addView(ttl, tl);
        bar.addView(new Glyph(act, Glyph.CAST), new LinearLayout.LayoutParams(dp(24), dp(24)));
        top.addView(bar, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP));
        back.setOnClickListener(v -> closeController());

        final LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(PANEL);
        float pr = dp(22);
        pbg.setCornerRadii(new float[]{pr, pr, pr, pr, 0, 0, 0, 0});
        pbg.setStroke(dp(1), 0xFF1E2128);
        panel.setBackground(pbg);
        panel.setPadding(dp(16), 0, dp(16), dp(12));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(MATCH, WRAP);
        plp.topMargin = -dp(18);
        root.addView(panel, plp);

        FrameLayout handleBox = new FrameLayout(act);
        View handle = new View(act);
        handle.setBackground(round(0xFF555966, 2, 0, 0));
        handleBox.addView(handle, new FrameLayout.LayoutParams(dp(34), dp(4), Gravity.CENTER));
        panel.addView(handleBox, new LinearLayout.LayoutParams(MATCH, dp(20)));
        handleBox.setOnTouchListener(new View.OnTouchListener() {
            float startY;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                int a = e.getAction();
                if (a == MotionEvent.ACTION_DOWN) {
                    startY = e.getRawY();
                    return true;
                } else if (a == MotionEvent.ACTION_MOVE) {
                    panel.setTranslationY(Math.max(0f, e.getRawY() - startY));
                    return true;
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    if (e.getRawY() - startY > dp(90)) closeController();
                    else panel.animate().translationY(0f).setDuration(150).start();
                    return true;
                }
                return false;
            }
        });

        panel.addView(new Glyph(act, Glyph.CAST).color(GREEN), lp(dp(18), dp(18), 0, 2, 0, 0));
        TextView head = text("Playing on " + prettyName(activeName), 16, 0xFFFFFFFF, true);
        head.setGravity(Gravity.CENTER);
        panel.addView(head, lp(MATCH, WRAP, 0, 6, 0, 0));
        TextView sub = text((shownTitle.isEmpty() ? "" : shownTitle + "  \u2022  ") + "Connected", 11, GREY, false);
        sub.setGravity(Gravity.CENTER);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(sub, lp(MATCH, WRAP, 0, 3, 0, 0));

        LinearLayout controls = new LinearLayout(act);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        Glyph rew = new Glyph(act, Glyph.REPLAY10);
        Glyph fwd = new Glyph(act, Glyph.FORWARD10);
        cPlay = new ImageView(act);
        cPlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cPlay.setPadding(dp(16), dp(16), dp(16), dp(16));
        cPlay.setBackground(round(0xFF1B1E26, 28, 1, LINE));
        cPlay.setImageResource(paused ? IC_PLAY : IC_PAUSE);
        cPlay.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        controls.addView(rew, lp(dp(34), dp(34), 22, 0, 22, 0));
        controls.addView(cPlay, lp(dp(56), dp(56), 22, 0, 22, 0));
        controls.addView(fwd, lp(dp(34), dp(34), 22, 0, 22, 0));
        panel.addView(controls, lp(MATCH, WRAP, 0, 10, 0, 0));
        rew.setOnClickListener(v -> seekBy(-10));
        fwd.setOnClickListener(v -> seekBy(10));
        cPlay.setOnClickListener(v -> {
            togglePause();
            cPlay.setImageResource(paused ? IC_PLAY : IC_PAUSE);
        });

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8), dp(8), dp(14), dp(8));
        card.setBackground(round(CARD, 14, 1, LINE));
        final ImageView poster = new ImageView(act);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(0xFF22252D);
        poster.setClipToOutline(true);
        poster.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(8));
            }
        });
        card.addView(poster, new LinearLayout.LayoutParams(dp(92), dp(50)));
        LinearLayout info = new LinearLayout(act);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, 0, 0);
        TextView cardTitle = text(shownTitle, 13, 0xFFFFFFFF, true);
        cardTitle.setSingleLine(true);
        cardTitle.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(cardTitle);
        cSeek = new SeekBar(act);
        cSeek.setMax(1000);
        cSeek.setThumb(null);
        cSeek.setPadding(0, 0, 0, 0);
        cSeek.setProgressTintList(ColorStateList.valueOf(GOLD));
        cSeek.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF3A3D46));
        cSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) showTimes(progress / 1000f);
            }
            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
            }
            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                seekToFraction(sb.getProgress() / 1000f);
            }
        });
        info.addView(cSeek, lp(MATCH, dp(16), 0, 4, 0, 0));
        cCardTime = text("--:-- / --:--", 11, 0xFFD5D8DE, false);
        cCardTime.setGravity(Gravity.END);
        info.addView(cCardTime, lp(MATCH, WRAP, 0, 0, 0, 0));
        card.addView(info, new LinearLayout.LayoutParams(0, WRAP, 1f));
        panel.addView(card, lp(MATCH, WRAP, 0, 12, 0, 0));

        LinearLayout devCard = new LinearLayout(act);
        devCard.setOrientation(LinearLayout.HORIZONTAL);
        devCard.setGravity(Gravity.CENTER_VERTICAL);
        devCard.setPadding(dp(14), dp(9), dp(14), dp(9));
        devCard.setBackground(round(CARD, 14, 1, LINE));
        devCard.addView(new Glyph(act, Glyph.DEVICE), new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout devInfo = new LinearLayout(act);
        devInfo.setOrientation(LinearLayout.VERTICAL);
        devInfo.setPadding(dp(12), 0, 0, 0);
        devInfo.addView(text(capital(prettyName(activeName)), 14, 0xFFFFFFFF, true));
        LinearLayout stRow = new LinearLayout(act);
        stRow.setOrientation(LinearLayout.HORIZONTAL);
        stRow.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(act);
        dot.setBackground(round(GREEN, 4, 0, 0));
        stRow.addView(dot, new LinearLayout.LayoutParams(dp(6), dp(6)));
        stRow.addView(text("Connected", 11, GREEN, false), lp(WRAP, WRAP, 6, 0, 0, 0));
        devInfo.addView(stRow, lp(WRAP, WRAP, 0, 1, 0, 0));
        devCard.addView(devInfo, new LinearLayout.LayoutParams(0, WRAP, 1f));
        devCard.addView(new Glyph(act, Glyph.CHEVRON).color(GREY), new LinearLayout.LayoutParams(dp(16), dp(16)));
        panel.addView(devCard, lp(MATCH, WRAP, 0, 8, 0, 0));
        devCard.setOnClickListener(v -> toast("Connected to " + prettyName(activeName)));

        LinearLayout stop = new LinearLayout(act);
        stop.setOrientation(LinearLayout.HORIZONTAL);
        stop.setGravity(Gravity.CENTER);
        stop.setPadding(dp(14), dp(10), dp(14), dp(10));
        stop.setBackground(round(0x00000000, 14, 2, 0xFF3A3E49));
        stop.addView(new Glyph(act, Glyph.STOP), new LinearLayout.LayoutParams(dp(20), dp(20)));
        stop.addView(text("Stop casting", 14, 0xFFFFFFFF, true), lp(WRAP, WRAP, 10, 0, 0, 0));
        LinearLayout inApp = new LinearLayout(act);
        inApp.setOrientation(LinearLayout.HORIZONTAL);
        inApp.setGravity(Gravity.CENTER);
        inApp.setPadding(dp(14), dp(10), dp(14), dp(10));
        inApp.setBackground(round(CARD, 14, 1, LINE));
        inApp.addView(text("Play in app instead", 14, 0xFFFFFFFF, true), lp(WRAP, WRAP, 0, 0, 0, 0));
        panel.addView(inApp, lp(MATCH, WRAP, 0, 8, 0, 0));
        inApp.setOnClickListener(v -> {
            final int at = lastPos;
            closeController();
            stopCasting();
            web.post(() -> web.evaluateJavascript(
                "window.__playInApp&&window.__playInApp(" + at + ")", null));
        });
        panel.addView(stop, lp(MATCH, WRAP, 0, 8, 0, 0));
        stop.setOnClickListener(v -> {
            closeController();
            stopCasting();
            toast("Stopped casting");
        });

        d.setContentView(root);
        Window win = d.getWindow();
        if (win != null) win.setLayout(MATCH, MATCH);
        d.setOnDismissListener(x -> {
            if (ctrl == d) {
                ctrl = null;
                ctrlOpen = false;
            }
        });
        ctrl = d;
        ctrlOpen = true;
        d.show();

        loadImage(castBackdrop.isEmpty() ? castPoster : castBackdrop, backdrop);
        loadImage(castPoster.isEmpty() ? castBackdrop : castPoster, poster);
        pollLoop();
    }

    private void pollLoop() {
        final String control = activeControl;
        pool.execute(() -> {
            while (ctrlOpen && control != null && control.equals(activeControl)) {
                try {
                    String info = soap(control, "GetPositionInfo", "<InstanceID>0</InstanceID>");
                    final int pos = readTime(info, "RelTime");
                    final int dur = readTime(info, "TrackDuration");
                    ui.post(() -> applyPosition(pos, dur));
                } catch (Exception ignore) {
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    private void applyPosition(int pos, int dur) {
        if (pos >= 0) lastPos = pos;
        if (!ctrlOpen || cSeek == null) return;
        if (dur > 0) curDur = dur;
        else if (webDur > 0) curDur = webDur;
        int total, elapsed;
        if (castClipSecs > 0) {
            total = castClipSecs;
            elapsed = pos < 0 ? 0 : Math.max(0, pos - loopStart);
        } else {
            total = curDur;
            elapsed = pos < 0 ? 0 : pos;
        }
        if (total <= 0) return;
        elapsed = Math.min(elapsed, total);
        if (!userSeeking) {
            cSeek.setProgress((int) (elapsed * 1000L / total));
            cCardTime.setText(fmtShort(elapsed) + " / " + fmtShort(total));
        }
    }

    private void showTimes(float f) {
        int total = castClipSecs > 0 ? castClipSecs : curDur;
        if (total <= 0) return;
        cCardTime.setText(fmtShort((int) (f * total)) + " / " + fmtShort(total));
    }

    private void seekToFraction(float f) {
        final String c = activeControl;
        if (c == null) return;
        int total = castClipSecs > 0 ? castClipSecs : curDur;
        if (total <= 0) return;
        final int target = (int) (f * total) + (castClipSecs > 0 ? loopStart : 0);
        pool.execute(() -> {
            try {
                seekTo(c, target);
            } catch (Exception e) {
                toast("This TV doesn't support seeking");
            }
        });
    }

    // ---------------------------------------------------------------- playback

    private void pausePhone() {
        web.post(() -> web.evaluateJavascript(
            "document.querySelectorAll('video').forEach(function(v){try{v.pause()}catch(e){}})", null));
    }

    private void play(final String[] dev) {
        final String url = mediaUrl;
        final String title = mediaTitle;
        final int clip = mediaClipSecs;
        final int cstart = mediaClipStart;
        final String bd = artBackdrop;
        final String po = artPoster;
        final String old = activeControl;
        toast("Sending to " + dev[0] + "...");
        pool.execute(() -> {
            try {
                if (old != null && !old.equals(dev[1])) {
                    try { soap(old, "Stop", "<InstanceID>0</InstanceID>"); } catch (Exception ignore) {}
                }
                pushMedia(dev[1], url, title);
                activeControl = dev[1];
                activeName = dev[0];
                paused = false;
                castUrl = url;
                castTitle = title;
                castClipSecs = clip;
                castClipStart = cstart;
                castBackdrop = bd;
                castPoster = po;
                save();
                if (clip > 0) startClipLoop(dev[1], clip, cstart);
                else loopToken++;
                ui.post(() -> {
                    toast("Playing on " + dev[0]);
                    pausePhone();
                    if (openCtrlAfter) {
                        openCtrlAfter = false;
                        showController();
                    }
                });
            } catch (Exception e) {
                openCtrlAfter = false;
                toast("Could not play on this TV");
            }
        });
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
        try { soap(control, "Stop", "<InstanceID>0</InstanceID>"); } catch (Exception ignore) {}
        Thread.sleep(600);
        soap(control, "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>" + esc(url) + "</CurrentURI>"
                + "<CurrentURIMetaData>" + esc(didl) + "</CurrentURIMetaData>");
        Thread.sleep(400);
        soap(control, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    private void stopCasting() {
        final String c = activeControl;
        activeControl = null;
        paused = false;
        loopToken++;
        castUrl = "";
        castTitle = "";
        castClipSecs = 0;
        castClipStart = -1;
        castBackdrop = "";
        castPoster = "";
        save();
        closeController();
        if (c == null) return;
        pool.execute(() -> {
            try { soap(c, "Stop", "<InstanceID>0</InstanceID>"); } catch (Exception ignore) {}
        });
    }

    private void togglePause() {
        final String c = activeControl;
        if (c == null) return;
        final boolean pause = !paused;
        paused = pause;
        save();
        pool.execute(() -> {
            try {
                soap(c, pause ? "Pause" : "Play",
                    pause ? "<InstanceID>0</InstanceID>" : "<InstanceID>0</InstanceID><Speed>1</Speed>");
            } catch (Exception e) {
                toast("The TV did not respond");
            }
        });
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
        return String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);
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

    private void seekBy(final int delta) {
        final String c = activeControl;
        if (c == null) return;
        pool.execute(() -> {
            try {
                String info = soap(c, "GetPositionInfo", "<InstanceID>0</InstanceID>");
                int pos = readTime(info, "RelTime");
                if (pos < 0) throw new Exception("no position");
                seekTo(c, Math.max(0, pos + delta));
            } catch (Exception e) {
                toast("This TV doesn't support skipping");
            }
        });
    }

    // Loops a short window of the file on the TV: same link, only seeks, no backend calls
    private void startClipLoop(final String control, final int clipSecs, final int startArg) {
        final long token = ++loopToken;
        pool.execute(() -> {
            try {
                int start = startArg;
                if (start < 0) {
                    int dur = -1;
                    for (int i = 0; i < 15 && token == loopToken; i++) {
                        Thread.sleep(1000);
                        try {
                            String info = soap(control, "GetPositionInfo", "<InstanceID>0</InstanceID>");
                            dur = readTime(info, "TrackDuration");
                        } catch (Exception ignore) {
                        }
                        if (dur > 0) break;
                    }
                    start = dur > 0 ? (int) (dur * 0.27) : 0;
                } else {
                    Thread.sleep(1500);
                }
                if (token != loopToken) return;
                loopStart = start;
                toast("Clip loop: start " + fmtTime(start) + ", " + clipSecs + "s");
                boolean ok = false;
                String err = "";
                for (int i = 0; i < 6 && token == loopToken && !ok; i++) {
                    try {
                        seekTo(control, start);
                        ok = true;
                    } catch (Exception e) {
                        err = String.valueOf(e.getMessage());
                        Thread.sleep(1000);
                    }
                }
                if (!ok) {
                    toast("TV can't seek: " + err);
                    return;
                }
                long lastSeek = System.currentTimeMillis();
                while (token == loopToken && control.equals(activeControl)) {
                    Thread.sleep(1000);
                    if (paused) continue;
                    int pos = -1;
                    try {
                        pos = readTime(soap(control, "GetPositionInfo", "<InstanceID>0</InstanceID>"), "RelTime");
                    } catch (Exception ignore) {
                    }
                    long since = System.currentTimeMillis() - lastSeek;
                    boolean due = pos >= 0 ? (pos >= start + clipSecs && since > 3000)
                                           : since >= (clipSecs + 1) * 1000L;
                    if (due) {
                        try {
                            seekTo(control, start);
                        } catch (Exception e) {
                            toast("Loop seek failed: " + e.getMessage());
                        }
                        lastSeek = System.currentTimeMillis();
                    }
                }
            } catch (Exception ignore) {
            }
        });
    }

    // ---------------------------------------------------------------- DLNA plumbing

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
