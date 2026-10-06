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

        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleControls(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                seekBy(e.getX() < root.getWidth() / 2f ? -10000 : 10000);
                return true;
            }
        });
        root.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));
        setContentView(root);

        video.setVideoURI(Uri.parse(uri));
        final int resume = sp.getInt("pos_" + dlId, 0);
        video.setOnPreparedListener(player -> {
            mp = player;
            prepared = true;
            sp.edit().putInt("dur_" + dlId, video.getDuration()).apply();
            if (resume > 0 && resume < video.getDuration() - 3000) video.seekTo(resume);
            video.start();
            applySpeed();
            tick();
            showControls();
        });
        video.setOnCompletionListener(player -> {
            sp.edit().remove("pos_" + dlId).putLong("seen_" + dlId, System.currentTimeMillis()).apply();
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
        handler.removeCallbacksAndMessages(null);
    }
}
