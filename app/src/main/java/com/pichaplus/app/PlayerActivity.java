package com.pichaplus.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import androidx.core.graphics.PathParser;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerActivity extends Activity {
    private static final int GOLD = 0xFFE5B93C;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String P_BACK = "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z";
    private static final String P_PLAY = "M8,5v14l11,-7z";
    private static final String P_PAUSE = "M6,19h4V5H6v14zm8,-14v14h4V5h-4z";
    private static final String P_REPLAY = "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z";

    static class IconView extends View {
        private Path path;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        IconView(Context c, String d, int color) {
            super(c);
            path = PathParser.createPathFromPathData(d);
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
        }
        void setPath(String d) { path = PathParser.createPathFromPathData(d); invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            if (path == null) return;
            float s = Math.min(getWidth(), getHeight()) / 24f;
            cv.save();
            cv.translate((getWidth() - 24 * s) / 2f, (getHeight() - 24 * s) / 2f);
            cv.scale(s, s);
            cv.drawPath(path, paint);
            cv.restore();
        }
    }

    private VideoView video;
    private MediaPlayer mp;
    private long dlId, nextId;
    private FrameLayout controls;
    private IconView playIcon;
    private SeekBar seek;
    private TextView curTv, durTv, speedTv;
    private boolean dragging = false, prepared = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float[] speeds = {0.75f, 1f, 1.25f, 1.5f, 2f};
    private int speedIdx = 1;
    private SharedPreferences sp;

    private final Runnable ticker = new Runnable() {
        @Override public void run() { tick(); handler.postDelayed(this, 500); }
    };
    private final Runnable hider = new Runnable() {
        @Override public void run() {
            if (video != null && video.isPlaying()) controls.setVisibility(View.GONE);
        }
    };

    // ---- swipe gestures: left half = brightness, right half = volume up to 200% ----
    private android.media.audiofx.LoudnessEnhancer ppBoost;
    private float ppVolLevel = -1f, ppStart, ppDownX, ppDownY;
    private int ppMode = 0;
    private boolean ppActive = false, ppDecided = false;
    private LinearLayout ppHud;
    private PpHudIcon ppIcon;
    private View ppFill;
    private GradientDrawable ppFillBg;
    private TextView ppPct;
    private final Runnable ppHudHider = new Runnable() {
        @Override public void run() { if (ppHud != null) ppHud.setVisibility(View.GONE); }
    };

    private static class PpHudIcon extends View {
        private int kind = 0;
        private float lvl = 0f;
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint line = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        PpHudIcon(android.content.Context c) {
            super(c);
            fill.setColor(Color.WHITE);
            fill.setStyle(android.graphics.Paint.Style.FILL);
            line.setColor(Color.WHITE);
            line.setStyle(android.graphics.Paint.Style.STROKE);
            line.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        }
        void set(int k, float l) { kind = k; lvl = l; invalidate(); }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), h = getHeight();
            line.setStrokeWidth(w * 0.09f);
            if (kind == 0) {
                cv.drawCircle(w / 2f, h / 2f, w * 0.18f, fill);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    cv.drawLine((float) (w / 2f + Math.cos(a) * w * 0.30f), (float) (h / 2f + Math.sin(a) * h * 0.30f),
                                (float) (w / 2f + Math.cos(a) * w * 0.44f), (float) (h / 2f + Math.sin(a) * h * 0.44f), line);
                }
                return;
            }
            android.graphics.Path sp = new android.graphics.Path();
            sp.moveTo(w * 0.08f, h * 0.38f);
            sp.lineTo(w * 0.26f, h * 0.38f);
            sp.lineTo(w * 0.46f, h * 0.20f);
            sp.lineTo(w * 0.46f, h * 0.80f);
            sp.lineTo(w * 0.26f, h * 0.62f);
            sp.lineTo(w * 0.08f, h * 0.62f);
            sp.close();
            cv.drawPath(sp, fill);
            if (kind == 2) {
                cv.drawLine(w * 0.62f, h * 0.36f, w * 0.90f, h * 0.64f, line);
                cv.drawLine(w * 0.90f, h * 0.36f, w * 0.62f, h * 0.64f, line);
                return;
            }
            cv.drawArc(new android.graphics.RectF(w * 0.34f, h * 0.30f, w * 0.70f, h * 0.70f), -45, 90, false, line);
            if (lvl > 0.5f) cv.drawArc(new android.graphics.RectF(w * 0.26f, h * 0.16f, w * 0.90f, h * 0.84f), -45, 90, false, line);
        }
    }

    private void ppBuildHud(FrameLayout root) {
        LinearLayout hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.HORIZONTAL);
        hud.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0xDD222222);
        hud.setBackground(bg);
        hud.setPadding(dp(16), dp(10), dp(16), dp(10));
        hud.setVisibility(View.GONE);
        ppIcon = new PpHudIcon(this);
        hud.addView(ppIcon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        FrameLayout track = new FrameLayout(this);
        GradientDrawable tb = new GradientDrawable();
        tb.setCornerRadius(dp(4));
        tb.setColor(0x33FFFFFF);
        track.setBackground(tb);
        ppFill = new View(this);
        ppFillBg = new GradientDrawable();
        ppFillBg.setCornerRadius(dp(4));
        ppFillBg.setColor(Color.WHITE);
        ppFill.setBackground(ppFillBg);
        track.addView(ppFill, new FrameLayout.LayoutParams(0, MATCH));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(150), dp(8));
        tl.leftMargin = dp(12);
        hud.addView(track, tl);
        ppPct = text("0%", 13, Color.WHITE, true);
        ppPct.setGravity(Gravity.END);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(48), WRAP);
        pl.leftMargin = dp(10);
        hud.addView(ppPct, pl);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hl.topMargin = dp(20);
        root.addView(hud, hl);
        ppHud = hud;
    }

    private void ppShowHud(int mode, float level) {
        if (ppHud == null) return;
        float frac = (mode == 2 && ppBoost != null) ? level / 2f : level;
        ppIcon.set(mode == 1 ? 0 : (level <= 0.001f ? 2 : 1), level);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) ppFill.getLayoutParams();
        lp.width = (int) (dp(150) * Math.max(0f, Math.min(1f, frac)));
        ppFill.setLayoutParams(lp);
        ppFillBg.setColor(mode == 2 && level > 1.001f ? 0xFFE9BE42 : Color.WHITE);
        ppPct.setText(Math.round(level * 100f) + "%");
        handler.removeCallbacks(ppHudHider);
        ppHud.setVisibility(View.VISIBLE);
    }

    private float ppCurBrightness() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b >= 0f) return b;
        try {
            return android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Exception e) { return 0.5f; }
    }

    private void ppSetBrightness(float b) {
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b;
        getWindow().setAttributes(lp);
    }

    private float ppCurVolume() {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        int max = Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
        float sys = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) / (float) max;
        return (ppVolLevel > 1f && sys >= 0.999f) ? ppVolLevel : sys;
    }

    private int ppGain(float level) { return level > 1f ? (int) (2000 * Math.log10(level)) : 0; }

    private void ppSetVolume(float level) {
        ppVolLevel = level;
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        try { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(Math.min(level, 1f) * max), 0); } catch (Exception e) { }
        if (ppBoost != null) {
            try {
                int g = ppGain(level);
                ppBoost.setTargetGain(g);
                ppBoost.setEnabled(g > 0);
            } catch (Exception e) { }
        }
    }

    private void ppAttachBoost(int session) {
        try {
            if (ppBoost != null) ppBoost.release();
            ppBoost = new android.media.audiofx.LoudnessEnhancer(session);
            int g = ppGain(ppVolLevel);
            ppBoost.setTargetGain(g);
            ppBoost.setEnabled(g > 0);
        } catch (Throwable t) { ppBoost = null; }
    }

    private void ppRelease() {
        try { if (ppBoost != null) ppBoost.release(); } catch (Throwable t) { }
        ppBoost = null;
    }

    private int ppSwipe(MotionEvent ev, int w, int h) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                ppDownX = ev.getX();
                ppDownY = ev.getY();
                ppActive = false;
                ppDecided = false;
                ppMode = ppDownX < w / 2f ? 1 : 2;
                return 0;
            case MotionEvent.ACTION_MOVE: {
                if (!ppDecided) {
                    float dx = ev.getX() - ppDownX, dy = ev.getY() - ppDownY;
                    if (Math.abs(dx) < dp(12) && Math.abs(dy) < dp(12)) return 0;
                    ppDecided = true;
                    if (Math.abs(dy) > Math.abs(dx) * 1.5f) {
                        ppActive = true;
                        ppStart = ppMode == 1 ? ppCurBrightness() : ppCurVolume();
                        ppDownY = ev.getY();
                        return 1;
                    }
                    return 0;
                }
                if (!ppActive) return 0;
                float cap = (ppMode == 2 && ppBoost != null) ? 2f : 1f;
                float frac = (ppDownY - ev.getY()) / (h * 0.8f);
                if (ppMode == 1) {
                    float b = Math.max(0.02f, Math.min(1f, ppStart + frac));
                    ppSetBrightness(b);
                    ppShowHud(1, b);
                } else {
                    float vol = Math.max(0f, Math.min(cap, ppStart + frac * cap));
                    ppSetVolume(vol);
                    ppShowHud(2, vol);
                }
                return 2;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean was = ppActive;
                ppActive = false;
                ppDecided = false;
                if (was) {
                    handler.removeCallbacks(ppHudHider);
                    handler.postDelayed(ppHudHider, 900);
                    return 2;
                }
                return 0;
            }
        }
        return 0;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private String fmt(int ms) {
        int s = Math.max(0, ms / 1000);
        int h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                     : String.format(Locale.US, "%02d:%02d", m, sec);
    }

    private FrameLayout skipBtn(final boolean fwd) {
        FrameLayout f = new FrameLayout(this);
        IconView ic = new IconView(this, P_REPLAY, Color.WHITE);
        if (fwd) ic.setScaleX(-1f);
        f.addView(ic, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER));
        TextView t = text("10", 11, Color.WHITE, true);
        t.setTranslationY(dp(2));
        f.addView(t, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        f.setOnClickListener(v -> seekBy(fwd ? 10000 : -10000));
        return f;
    }

    private void seekBy(int ms) {
        if (!prepared) return;
        int p = Math.max(0, Math.min(video.getDuration(), video.getCurrentPosition() + ms));
        video.seekTo(p);
        tick();
        showControls();
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hider);
        handler.postDelayed(hider, 3500);
    }

    private void toggleControls() {
        if (controls.getVisibility() == View.VISIBLE) controls.setVisibility(View.GONE);
        else showControls();
    }

    private void togglePlay() {
        if (!prepared) return;
        if (video.isPlaying()) video.pause(); else video.start();
        tick();
        showControls();
    }

    private void tick() {
        if (!prepared || dragging) return;
        int d = video.getDuration(), p = video.getCurrentPosition();
        if (d > 0) { seek.setMax(d); seek.setProgress(p); durTv.setText(fmt(d)); }
        curTv.setText(fmt(p));
        playIcon.setPath(video.isPlaying() ? P_PAUSE : P_PLAY);
    }

    private void applySpeed() {
        speedTv.setText(String.format(Locale.US, "Playback speed  %s\u00d7", speeds[speedIdx] == (int) speeds[speedIdx]
            ? String.format(Locale.US, "%.1f", speeds[speedIdx]) : String.valueOf(speeds[speedIdx])));
        if (mp == null || Build.VERSION.SDK_INT < 23) return;
        try {
            boolean was = video.isPlaying();
            PlaybackParams pp = new PlaybackParams();
            pp.setSpeed(speeds[speedIdx]);
            mp.setPlaybackParams(pp);
            if (!was) video.pause();
        } catch (Exception e) {}
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        dlId = getIntent().getLongExtra("id", -1);
        nextId = getIntent().getLongExtra("nextId", -1);
        String uri = getIntent().getStringExtra("uri");
        if (uri == null) { finish(); return; }
        sp = getSharedPreferences("picha_pos", MODE_PRIVATE);

        String full = getIntent().getStringExtra("title");
        if (full == null) full = "";
        String mainTitle = full, subTitle = "";
        Matcher m = Pattern.compile("^(.*?)\\s+E(\\d+)$").matcher(full);
        if (m.matches()) { mainTitle = m.group(1); subTitle = "Episode " + m.group(2); }

        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));

        controls = new FrameLayout(this);
        controls.setBackgroundColor(0x88000000);
        root.addView(controls, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(12), dp(16), dp(8));
        FrameLayout back = new FrameLayout(this);
        back.addView(new IconView(this, P_BACK, GOLD), new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.addView(text(mainTitle, 18, Color.WHITE, true));
        if (!subTitle.isEmpty()) tcol.addView(text(subTitle, 13, 0xFFB0B0B0, false));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(WRAP, WRAP);
        tl.leftMargin = dp(10);
        top.addView(tcol, tl);
        controls.addView(top, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout pp = new FrameLayout(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0x66000000);
        ring.setStroke(dp(2), GOLD);
        pp.setBackground(ring);
        playIcon = new IconView(this, P_PLAY, GOLD);
        pp.addView(playIcon, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        pp.setOnClickListener(v -> togglePlay());
        mid.addView(skipBtn(false), new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout.LayoutParams ppLp = new LinearLayout.LayoutParams(dp(76), dp(76));
        ppLp.leftMargin = dp(36); ppLp.rightMargin = dp(36);
        mid.addView(pp, ppLp);
        mid.addView(skipBtn(true), new LinearLayout.LayoutParams(dp(56), dp(56)));
        controls.addView(mid, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(24), 0, dp(24), dp(14));

        if (nextId > 0) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(0xDD111111);
            bg.setStroke(dp(1), GOLD);
            card.setBackground(bg);
            card.setPadding(dp(12), dp(8), dp(8), dp(8));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView nl = text("NEXT EPISODE", 10, GOLD, true);
            nl.setLetterSpacing(0.08f);
            col.addView(nl);
            String nt = getIntent().getStringExtra("nextTitle");
            Matcher nm = Pattern.compile("^(.*?)\\s+E(\\d+)$").matcher(nt == null ? "" : nt);
            col.addView(text(nm.matches() ? "Episode " + nm.group(2) : (nt == null ? "" : nt), 14, Color.WHITE, true));
            card.addView(col);
            String np = getIntent().getStringExtra("nextPoster");
            if (np != null) {
                try {
                    Bitmap bm = BitmapFactory.decodeFile(np);
                    if (bm != null) {
                        ImageView iv = new ImageView(this);
                        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        iv.setImageBitmap(bm);
                        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(42), dp(58));
                        il.leftMargin = dp(12);
                        card.addView(iv, il);
                    }
                } catch (Exception e) {}
            }
            card.setOnClickListener(v -> { if (PichaDownloads.play(this, nextId)) finish(); });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(WRAP, WRAP);
            cl.gravity = Gravity.END;
            cl.bottomMargin = dp(6);
            bottom.addView(card, cl);
        }

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        curTv = text("00:00", 13, Color.WHITE, false);
        durTv = text("00:00", 13, Color.WHITE, false);
        seek = new SeekBar(this);
        seek.setProgressTintList(ColorStateList.valueOf(GOLD));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x66FFFFFF));
        seek.setThumbTintList(ColorStateList.valueOf(GOLD));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) curTv.setText(fmt(p)); }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; handler.removeCallbacks(hider); }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (prepared) video.seekTo(s.getProgress());
                showControls();
            }
        });
        seekRow.addView(curTv, new LinearLayout.LayoutParams(dp(54), WRAP));
        seekRow.addView(seek, new LinearLayout.LayoutParams(0, WRAP, 1f));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(WRAP, WRAP);
        dl.leftMargin = dp(8);
        seekRow.addView(durTv, dl);
        bottom.addView(seekRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        speedTv = text("Playback speed  1.0\u00d7", 13, Color.WHITE, false);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(20));
        pill.setColor(0x44FFFFFF);
        speedTv.setBackground(pill);
        speedTv.setPadding(dp(14), dp(6), dp(14), dp(6));
        speedTv.setOnClickListener(v -> { speedIdx = (speedIdx + 1) % speeds.length; applySpeed(); showControls(); });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(WRAP, WRAP);
        sl.topMargin = dp(8);
        bottom.addView(speedTv, sl);
        controls.addView(bottom, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));

        ppBuildHud(root);
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleControls(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                seekBy(e.getX() < root.getWidth() / 2f ? -10000 : 10000);
                return true;
            }
        });
        root.setOnTouchListener((v, ev) -> {
            int ppst = ppSwipe(ev, root.getWidth(), root.getHeight());
            if (ppst == 1) {
                MotionEvent ppc = MotionEvent.obtain(ev);
                ppc.setAction(MotionEvent.ACTION_CANCEL);
                gd.onTouchEvent(ppc);
                ppc.recycle();
                return true;
            }
            if (ppst == 2) return true;
            return gd.onTouchEvent(ev);
        });
        setContentView(root);

        video.setVideoURI(Uri.parse(uri));
        final int resume = sp.getInt("pos_" + dlId, 0);
        video.setOnPreparedListener(player -> {
            mp = player;
            ppAttachBoost(player.getAudioSessionId());
            prepared = true;
            sp.edit().putInt("dur_" + dlId, video.getDuration()).apply();
            if (resume > 0 && resume < video.getDuration() - 3000) video.seekTo(resume);
            video.start();
            applySpeed();
            tick();
            showControls();
        });
        video.setOnCompletionListener(player -> {
            sp.edit().remove("pos_" + dlId).putLong("seen_" + dlId, System.currentTimeMillis()).putBoolean("watched_" + dlId, true).apply();
            if (nextId > 0 && PichaDownloads.play(this, nextId)) { finish(); return; }
            finish();
        });
        video.setOnErrorListener((player, w, e) -> {
            Toast.makeText(this, "Can't play this file", Toast.LENGTH_LONG).show();
            finish();
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        if (video != null && dlId >= 0 && prepared) {
            sp.edit().putInt("pos_" + dlId, video.getCurrentPosition())
                .putInt("dur_" + dlId, video.getDuration())
                .putLong("seen_" + dlId, System.currentTimeMillis()).apply();
            video.pause();
            controls.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ppRelease();
        handler.removeCallbacksAndMessages(null);
    }
}
