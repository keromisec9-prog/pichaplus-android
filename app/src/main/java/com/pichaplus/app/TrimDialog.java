package com.pichaplus.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

public class TrimDialog {
    interface Result { void onPick(long startMs, long endMs); void onCancel(); }

    static final long MAX_CLIP_MS = 30000L;
    static final long MIN_CLIP_MS = 3000L;
    static final int GOLD = 0xFFF5C518;

    static int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    static String fmt(long ms) {
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    static class TrimBar extends View {
        interface L { void onChange(boolean isStart, long s, long e); }
        long dur, s, e, max, min, play = -1;
        L l;
        int drag = 0, hw;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        TrimBar(Context c) { super(c); hw = dp(c, 14); }

        float pos(long t) { float w = getWidth() - 2f * hw; return hw + w * t / (float) dur; }
        long timeAt(float x) { float w = getWidth() - 2f * hw; return (long) (Math.max(0f, Math.min(1f, (x - hw) / w)) * dur); }

        @Override protected void onDraw(Canvas c) {
            float H = getHeight(), W = getWidth();
            float xs = pos(s), xe = pos(e);
            float b = dp(getContext(), 3);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xAA000000);
            c.drawRect(hw, 0, xs, H, p);
            c.drawRect(xe, 0, W - hw, H, p);
            p.setColor(GOLD);
            c.drawRect(xs, 0, xe, b, p);
            c.drawRect(xs, H - b, xe, H, p);
            float r = dp(getContext(), 8);
            c.drawRoundRect(new RectF(xs - hw, 0, xs, H), r, r, p);
            c.drawRoundRect(new RectF(xe, 0, xe + hw, H), r, r, p);
            p.setColor(Color.BLACK);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(getContext(), 2));
            p.setStrokeCap(Paint.Cap.ROUND);
            float cy = H / 2f, a = dp(getContext(), 4);
            float cs = xs - hw / 2f, ce = xe + hw / 2f;
            c.drawLine(cs + a / 2, cy - a, cs - a / 2, cy, p);
            c.drawLine(cs - a / 2, cy, cs + a / 2, cy + a, p);
            c.drawLine(ce - a / 2, cy - a, ce + a / 2, cy, p);
            c.drawLine(ce + a / 2, cy, ce - a / 2, cy + a, p);
            if (play >= s && play <= e) {
                p.setColor(Color.WHITE);
                p.setStrokeWidth(dp(getContext(), 2));
                float px = pos(play);
                c.drawLine(px, 0, px, H, p);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            float x = ev.getX();
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    float ds = Math.abs(x - (pos(s) - hw / 2f)), de = Math.abs(x - (pos(e) + hw / 2f));
                    float slop = dp(getContext(), 30);
                    if (ds <= de && ds < slop) drag = 1; else if (de < slop) drag = 2; else drag = 0;
                    if (drag != 0 && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    if (drag == 1) {
                        long ns = timeAt(x + hw / 2f);
                        s = Math.max(Math.max(0L, e - max), Math.min(ns, e - min));
                        if (l != null) l.onChange(true, s, e);
                    } else if (drag == 2) {
                        long ne = timeAt(x - hw / 2f);
                        e = Math.min(Math.min(dur, s + max), Math.max(ne, s + min));
                        if (l != null) l.onChange(false, s, e);
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    drag = 0;
                    return true;
            }
            return true;
        }
    }

    static void show(final Activity act, final Uri uri, final Result cb) {
        long d = 0;
        try {
            MediaMetadataRetriever m = new MediaMetadataRetriever();
            m.setDataSource(act, uri);
            d = Long.parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            m.release();
        } catch (Exception e) {}
        if (d < 2 * MIN_CLIP_MS) { cb.onPick(-1, -1); return; }
        final long dur = d;

        long s0 = Math.max(0L, Math.min((long) (dur * 0.27), dur - MAX_CLIP_MS));
        long e0 = Math.min(dur, s0 + MAX_CLIP_MS);
        final long[] sel = {s0, e0};

        final Dialog dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar);
        final Handler hd = new Handler(Looper.getMainLooper());
        final boolean[] playing = {false};

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(act, 16), dp(act, 16), dp(act, 16), dp(act, 16));

        TextView title = new TextView(act);
        title.setText("Choose your favorite scene");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(act);
        sub.setText("Trim the movie to the moment you love most.");
        sub.setTextColor(0xFFBBBBBB);
        sub.setTextSize(14);
        root.addView(sub);

        FrameLayout vf = new FrameLayout(act);
        vf.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        vlp.topMargin = dp(act, 12);
        root.addView(vf, vlp);

        final VideoView vv = new VideoView(act);
        FrameLayout.LayoutParams vvlp = new FrameLayout.LayoutParams(-1, -1);
        vvlp.gravity = Gravity.CENTER;
        vf.addView(vv, vvlp);

        final TextView playIcon = new TextView(act);
        playIcon.setText("\u25B6");
        playIcon.setTextColor(Color.WHITE);
        playIcon.setTextSize(30);
        playIcon.setGravity(Gravity.CENTER);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setShape(GradientDrawable.OVAL);
        pbg.setColor(0x99000000);
        playIcon.setBackground(pbg);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(act, 64), dp(act, 64));
        plp.gravity = Gravity.CENTER;
        vf.addView(playIcon, plp);

        final TextView range = new TextView(act);
        range.setTextColor(0xFFBBBBBB);
        range.setTextSize(13);
        range.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(act, 10);
        root.addView(range, rlp);

        int hw = dp(act, 14);
        FrameLayout strip = new FrameLayout(act);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(act, 64));
        slp.topMargin = dp(act, 8);
        root.addView(strip, slp);

        final int N = 8;
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(hw, 0, hw, 0);
        final ImageView[] thumbs = new ImageView[N];
        for (int i = 0; i < N; i++) {
            ImageView iv = new ImageView(act);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFF222222);
            row.addView(iv, new LinearLayout.LayoutParams(0, -1, 1f));
            thumbs[i] = iv;
        }
        strip.addView(row, new FrameLayout.LayoutParams(-1, -1));

        final TrimBar bar = new TrimBar(act);
        bar.dur = dur; bar.s = sel[0]; bar.e = sel[1]; bar.max = MAX_CLIP_MS; bar.min = MIN_CLIP_MS;
        strip.addView(bar, new FrameLayout.LayoutParams(-1, -1));

        final TextView selected = new TextView(act);
        selected.setTextSize(16);
        selected.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sellp = new LinearLayout.LayoutParams(-1, -2);
        sellp.topMargin = dp(act, 10);
        root.addView(selected, sellp);

        final Runnable refresh = new Runnable() { public void run() {
            range.setText(fmt(sel[0]) + "  \u2013  " + fmt(sel[1]) + "   (movie " + fmt(dur) + ")");
            SpannableStringBuilder sb = new SpannableStringBuilder("Selected: " + fmt(sel[1] - sel[0]));
            sb.setSpan(new ForegroundColorSpan(Color.WHITE), 0, 10, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(GOLD), 10, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            selected.setText(sb);
        }};
        refresh.run();

        Button cont = new Button(act);
        cont.setText("Continue");
        cont.setAllCaps(false);
        cont.setTextSize(17);
        cont.setTypeface(null, android.graphics.Typeface.BOLD);
        cont.setTextColor(Color.BLACK);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(GOLD);
        cbg.setCornerRadius(dp(act, 28));
        cont.setBackground(cbg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, dp(act, 54));
        clp.topMargin = dp(act, 14);
        root.addView(cont, clp);

        Button skip = new Button(act);
        skip.setText("Skip");
        skip.setAllCaps(false);
        skip.setTextSize(16);
        skip.setTextColor(Color.WHITE);
        GradientDrawable kbg = new GradientDrawable();
        kbg.setColor(Color.TRANSPARENT);
        kbg.setStroke(dp(act, 1), 0xFFDDDDDD);
        kbg.setCornerRadius(dp(act, 28));
        skip.setBackground(kbg);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-1, dp(act, 54));
        klp.topMargin = dp(act, 10);
        root.addView(skip, klp);

        TextView hint = new TextView(act);
        hint.setText("Skip trimming and use automatic preview");
        hint.setTextColor(0xFF999999);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(act, 8);
        root.addView(hint, hlp);

        // playback
        vv.setVideoURI(uri);
        vv.setOnPreparedListener(mp -> vv.seekTo((int) sel[0]));
        vv.setOnErrorListener((mp, w, x) -> true);

        final View.OnClickListener toggle = v -> {
            try {
                if (playing[0]) {
                    vv.pause();
                    playing[0] = false;
                    playIcon.setVisibility(View.VISIBLE);
                } else {
                    int p = vv.getCurrentPosition();
                    if (p < sel[0] || p >= sel[1] - 100) vv.seekTo((int) sel[0]);
                    vv.start();
                    playing[0] = true;
                    playIcon.setVisibility(View.GONE);
                }
            } catch (Exception e) {}
        };
        playIcon.setOnClickListener(toggle);
        vv.setOnClickListener(toggle);

        bar.l = (isStart, s, e) -> {
            sel[0] = s; sel[1] = e;
            try { vv.pause(); vv.seekTo((int) (isStart ? s : Math.max(s, e - 500))); } catch (Exception ex) {}
            playing[0] = false;
            playIcon.setVisibility(View.VISIBLE);
            refresh.run();
        };

        hd.post(new Runnable() { public void run() {
            if (!dlg.isShowing()) return;
            try {
                int p = vv.getCurrentPosition();
                if (playing[0]) {
                    if (p >= sel[1] - 50) {
                        vv.pause();
                        vv.seekTo((int) sel[0]);
                        playing[0] = false;
                        playIcon.setVisibility(View.VISIBLE);
                        bar.play = sel[0];
                    } else bar.play = p;
                    bar.invalidate();
                }
            } catch (Exception e) {}
            hd.postDelayed(this, 80);
        }});

        // thumbnails
        new Thread(() -> {
            MediaMetadataRetriever m = new MediaMetadataRetriever();
            try {
                m.setDataSource(act, uri);
                for (int i = 0; i < N; i++) {
                    final int idx = i;
                    long t = (dur * (2L * i + 1) / (2L * N)) * 1000L;
                    Bitmap b = m.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (b == null) continue;
                    int th = 128, tw = Math.max(1, b.getWidth() * th / Math.max(1, b.getHeight()));
                    final Bitmap sb = Bitmap.createScaledBitmap(b, tw, th, true);
                    if (sb != b) b.recycle();
                    act.runOnUiThread(() -> thumbs[idx].setImageBitmap(sb));
                }
            } catch (Exception e) {}
            try { m.release(); } catch (Exception e) {}
        }).start();

        final boolean[] fin = {false};
        cont.setOnClickListener(v -> {
            if (fin[0]) return;
            fin[0] = true;
            dlg.dismiss();
            cb.onPick(sel[0], sel[1]);
        });
        skip.setOnClickListener(v -> {
            if (fin[0]) return;
            fin[0] = true;
            dlg.dismiss();
            cb.onPick(-1, -1);
        });
        dlg.setOnCancelListener(d2 -> {
            if (fin[0]) return;
            fin[0] = true;
            cb.onCancel();
        });
        dlg.setOnDismissListener(d2 -> {
            hd.removeCallbacksAndMessages(null);
            try { vv.stopPlayback(); } catch (Exception e) {}
        });
        dlg.setCancelable(true);
        dlg.setCanceledOnTouchOutside(false);
        dlg.setContentView(root);
        dlg.show();
    }
}
