package com.pichaplus.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

public class TrimDialog {
    interface Result { void onPick(long startMs, long endMs); void onCancel(); }

    static final long MAX_CLIP_MS = 30000L;     // default selection length
    static final long MAX_SELECT_MS = 45000L;   // hard cap enforced by the selector box
    static final long MIN_CLIP_MS = 3000L;
    static final int GOLD = 0xFFF5C518;
    static final int BG = 0xFF0A0A0A;
    static final int LINE = 0xFF3A3A3A;
    static final int MUTED = 0xFF9A9A9A;

    static int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    static String fmt(long ms) {
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    static ImageView icon(Context c, int res, int color) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setColorFilter(color);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    static GradientDrawable shape(Context c, int fill, int radiusDp, int strokePx, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    static RippleDrawable ripple(GradientDrawable g) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null);
    }

    // ---------- step indicator: Media / Trim / Details / Review ----------
    static class Stepper extends View {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        static final String[] LB = {"Media", "Trim", "Details", "Review"};
        Stepper(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            Context cx = getContext();
            float W = getWidth(), r = dp(cx, 16), cy = r + dp(cx, 2);
            for (int i = 0; i < 4; i++) {
                float x = W * (0.125f + 0.25f * i);
                if (i < 3) {
                    float x2 = W * (0.125f + 0.25f * (i + 1));
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dp(cx, 2));
                    p.setColor(i == 0 ? GOLD : LINE);
                    c.drawLine(x + r + dp(cx, 8), cy, x2 - r - dp(cx, 8), cy, p);
                }
                boolean on = i < 2;
                p.setStyle(Paint.Style.FILL);
                p.setColor(on ? GOLD : LINE);
                c.drawCircle(x, cy, r, p);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTypeface(Typeface.DEFAULT_BOLD);
                p.setTextSize(dp(cx, 16));
                p.setColor(on ? Color.BLACK : 0xFFCCCCCC);
                Paint.FontMetrics fm = p.getFontMetrics();
                c.drawText(String.valueOf(i + 1), x, cy - (fm.ascent + fm.descent) / 2f, p);
                p.setTypeface(Typeface.DEFAULT);
                p.setTextSize(dp(cx, 14));
                p.setColor(i == 1 ? GOLD : (i == 0 ? 0xFFBBBBBB : MUTED));
                c.drawText(LB[i], x, cy + r + dp(cx, 22), p);
            }
        }
    }

    // ---------- rounded video card border + corner masks ----------
    static class CardFrame extends View {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path path = new Path();
        RectF rf = new RectF();
        CardFrame(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight(), R = dp(getContext(), 16);
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0, 0, W, H, Path.Direction.CW);
            rf.set(0, 0, W, H);
            path.addRoundRect(rf, R, R, Path.Direction.CW);
            p.setStyle(Paint.Style.FILL);
            p.setColor(BG);
            c.drawPath(path, p);
            float sw = dp(getContext(), 1);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(sw);
            p.setColor(LINE);
            rf.set(sw / 2f, sw / 2f, W - sw / 2f, H - sw / 2f);
            c.drawRoundRect(rf, R, R, p);
        }
    }

    // ---------- selector: zoomed, scrollable timeline; drag the box, resize with handles (hard max) ----------
    static class TrimBar extends View {
        interface L { void onChange(int mode, long s, long e); }   // 1 start handle, 2 end handle, 3 box, 4 box pushed by scrolling, 5 scrolling settled
        static final long SPAN_MS = 120000L;                       // time visible across the strip at once
        long dur, s, e, max, min, play = -1, interval;
        volatile long vStart, span;
        volatile boolean closed;
        L l;
        Uri uri;
        MediaMetadataRetriever mmr;
        int drag = 0, hw, bubbleH, bt;
        long ds0, de0;
        float downX, lastX;
        boolean blocked, shifted, flinging;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        Path path = new Path();
        RectF rf = new RectF(), dstF = new RectF();
        Rect src = new Rect();
        android.util.LruCache<Integer, Bitmap> cache = new android.util.LruCache<Integer, Bitmap>(48);
        final java.util.HashSet<Integer> pending = new java.util.HashSet<Integer>();
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newSingleThreadExecutor();
        android.widget.OverScroller sc;
        android.view.VelocityTracker vt;

        final Runnable flingStep = new Runnable() { public void run() {
            if (!flinging) return;
            if (sc.computeScrollOffset()) {
                setView((long) (sc.getCurrX() / ((getWidth() - 2f * hw) / (float) span)));
                postOnAnimation(this);
            } else { flinging = false; settle(); }
        }};

        TrimBar(Context c) {
            super(c);
            hw = dp(c, 16);
            bubbleH = dp(c, 30);
            bt = dp(c, 4);
            sc = new android.widget.OverScroller(c);
        }

        void init(Uri u, long d, long s0, long e0) {
            uri = u; dur = d; s = s0; e = e0;
            span = Math.min(d, SPAN_MS);
            interval = Math.max(500L, span / 8);
            vStart = Math.max(0L, Math.min(s0 - (span - (e0 - s0)) / 2, d - span));
        }

        int ovArea() { return dur > SPAN_MS ? dp(getContext(), 14) : 0; }
        float tw() { return getWidth() - 2f * hw; }
        float pos(long t) { return hw + tw() * (t - vStart) / (float) span; }
        long timeAt(float x) { return vStart + (long) ((x - hw) / tw() * span); }

        void setView(long v) {
            vStart = Math.max(0L, Math.min(v, dur - span));
            long len = e - s;
            long ns = Math.max(vStart, Math.min(s, vStart + span - len));
            if (ns != s) {
                s = ns; e = ns + len; shifted = true;
                if (l != null) l.onChange(4, s, e);
            }
            invalidate();
        }

        void settle() {
            if (shifted) { shifted = false; if (l != null) l.onChange(5, s, e); }
        }

        void seekOverview(float x) {
            long t = (long) ((x - hw) / tw() * dur);
            setView(t - span / 2);
        }

        void want(final int k) {
            if (closed || k < 0 || k * interval >= dur || cache.get(k) != null) return;
            synchronized (pending) { if (!pending.add(k)) return; }
            ex.execute(() -> {
                try {
                    if (closed) return;
                    long first = vStart / interval, last = (vStart + span) / interval;
                    if (k < first - 2 || k > last + 2) return;
                    if (mmr == null) { mmr = new MediaMetadataRetriever(); mmr.setDataSource(getContext(), uri); }
                    long t = Math.min(dur - 1, k * interval + interval / 2) * 1000L;
                    Bitmap b = mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (b != null) {
                        int th = 128, tw2 = Math.max(1, b.getWidth() * th / Math.max(1, b.getHeight()));
                        Bitmap sb = Bitmap.createScaledBitmap(b, tw2, th, true);
                        if (sb != b) b.recycle();
                        cache.put(k, sb);
                        postInvalidate();
                    }
                } catch (Exception x) {
                } finally {
                    synchronized (pending) { pending.remove(k); }
                }
            });
        }

        void close() {
            closed = true;
            ex.execute(() -> { try { if (mmr != null) mmr.release(); } catch (Exception x) {} });
            ex.shutdown();
        }

        void bubble(Canvas c, float cxB, float px, String text) {
            Context cx = getContext();
            float bw = dp(cx, 50), bh = dp(cx, 22), ar = dp(cx, 5);
            float lim = bw / 2f - bh / 2f - ar;
            px = Math.max(cxB - lim, Math.min(px, cxB + lim));
            p.setStyle(Paint.Style.FILL);
            p.setColor(GOLD);
            rf.set(cxB - bw / 2f, 0, cxB + bw / 2f, bh);
            c.drawRoundRect(rf, bh / 2f, bh / 2f, p);
            path.reset();
            path.moveTo(px - ar, bh - 1);
            path.lineTo(px + ar, bh - 1);
            path.lineTo(px, bh + ar);
            path.close();
            c.drawPath(path, p);
            p.setColor(Color.BLACK);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(dp(cx, 12));
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(text, cxB, bh / 2f - (fm.ascent + fm.descent) / 2f, p);
        }

        @Override protected void onDraw(Canvas c) {
            if (dur <= 0 || span <= 0 || getWidth() == 0) return;
            Context cx = getContext();
            float W = getWidth(), H = getHeight(), ov = ovArea();
            float hB = H - ov, ft = bubbleH;
            float xs = pos(s), xe = pos(e);
            float sTop = ft + bt, sBot = hB - bt;
            float R = dp(cx, 8);

            c.save();
            path.reset();
            rf.set(hw, sTop, W - hw, sBot);
            path.addRoundRect(rf, R, R, Path.Direction.CW);
            c.clipPath(path);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF222222);
            c.drawRect(hw, sTop, W - hw, sBot, p);
            int k0 = (int) (vStart / interval), k1 = (int) ((vStart + span) / interval);
            for (int k = k0; k <= k1; k++) {
                if (k * interval >= dur) break;
                Bitmap b = cache.get(k);
                if (b == null) { want(k); continue; }
                float l0 = pos(k * interval), r0 = pos((k + 1) * interval);
                dstF.set(l0, sTop, r0, sBot);
                float da = (r0 - l0) / (sBot - sTop), sa = b.getWidth() / (float) b.getHeight();
                if (sa > da) {
                    int w = Math.round(b.getHeight() * da);
                    int x0 = (b.getWidth() - w) / 2;
                    src.set(x0, 0, x0 + w, b.getHeight());
                } else {
                    int h = Math.round(b.getWidth() / da);
                    int y0 = (b.getHeight() - h) / 2;
                    src.set(0, y0, b.getWidth(), y0 + h);
                }
                c.drawBitmap(b, src, dstF, bp);
            }
            want(k0 - 1);
            want(k1 + 1);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xB3000000);
            c.drawRect(hw, sTop, xs, sBot, p);
            c.drawRect(xe, sTop, W - hw, sBot, p);
            c.restore();

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(cx, 1));
            p.setColor(LINE);
            rf.set(hw, sTop, W - hw, sBot);
            c.drawRoundRect(rf, R, R, p);

            p.setStyle(Paint.Style.FILL);
            p.setColor(GOLD);
            c.drawRect(xs, ft, xe, ft + bt, p);
            c.drawRect(xs, hB - bt, xe, hB, p);
            path.reset();
            rf.set(xs - hw, ft, xs, hB);
            path.addRoundRect(rf, new float[]{R, R, 0, 0, 0, 0, R, R}, Path.Direction.CW);
            c.drawPath(path, p);
            path.reset();
            rf.set(xe, ft, xe + hw, hB);
            path.addRoundRect(rf, new float[]{0, 0, R, R, R, R, 0, 0}, Path.Direction.CW);
            c.drawPath(path, p);

            p.setColor(Color.BLACK);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(cx, 2));
            p.setStrokeCap(Paint.Cap.ROUND);
            float cy = (ft + hB) / 2f, a = dp(cx, 5);
            float cs = xs - hw / 2f, ce = xe + hw / 2f;
            c.drawLine(cs + a / 2, cy - a, cs - a / 2, cy, p);
            c.drawLine(cs - a / 2, cy, cs + a / 2, cy + a, p);
            c.drawLine(ce - a / 2, cy - a, ce + a / 2, cy, p);
            c.drawLine(ce + a / 2, cy, ce - a / 2, cy + a, p);

            if (play >= s && play <= e) {
                p.setColor(Color.WHITE);
                p.setStrokeWidth(dp(cx, 2));
                float px = pos(play);
                c.drawLine(px, ft + bt, px, hB - bt, p);
            }

            float bw = dp(cx, 50), gap = bw + dp(cx, 4);
            float bs = cs, be = ce;
            if (be - bs < gap) {
                float mid = (bs + be) / 2f;
                bs = mid - gap / 2f;
                be = mid + gap / 2f;
            }
            bs = Math.max(bw / 2f, bs);
            be = Math.max(be, bs + gap);
            be = Math.min(be, W - bw / 2f);
            bs = Math.min(bs, be - gap);
            bubble(c, bs, cs, fmt(s));
            bubble(c, be, ce, fmt(e));

            if (ov > 0) {
                float oh = dp(cx, 4), oy = H - dp(cx, 6), tw0 = W - 2f * hw;
                p.setStyle(Paint.Style.FILL);
                p.setColor(LINE);
                rf.set(hw, oy, W - hw, oy + oh);
                c.drawRoundRect(rf, oh / 2f, oh / 2f, p);
                float a0 = hw + tw0 * vStart / dur;
                float a1 = Math.max(a0 + dp(cx, 12), hw + tw0 * (vStart + span) / dur);
                p.setColor(0xFFCCCCCC);
                rf.set(a0, oy, Math.min(a1, W - hw), oy + oh);
                c.drawRoundRect(rf, oh / 2f, oh / 2f, p);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            float x = ev.getX(), y = ev.getY();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    if (flinging) { sc.forceFinished(true); flinging = false; settle(); }
                    if (vt != null) vt.recycle();
                    vt = android.view.VelocityTracker.obtain();
                    vt.addMovement(ev);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    downX = x; lastX = x; ds0 = s; de0 = e; blocked = false; shifted = false;
                    if (ovArea() > 0 && y >= getHeight() - ovArea() + dp(getContext(), 2)) {
                        drag = 6;
                        seekOverview(x);
                        return true;
                    }
                    float ds = Math.abs(x - (pos(s) - hw / 2f)), de = Math.abs(x - (pos(e) + hw / 2f));
                    float slop = dp(getContext(), 22);
                    if (ds <= de && ds < slop) drag = 1;
                    else if (de < slop) drag = 2;
                    else if (x > pos(s) && x < pos(e)) drag = 3;
                    else drag = 4;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (vt != null) vt.addMovement(ev);
                    boolean hit = false;
                    if (drag == 1) {
                        long ns = timeAt(x + hw / 2f);
                        long lo = Math.max(vStart, e - max);
                        hit = ns < e - max;
                        s = Math.max(lo, Math.min(ns, e - min));
                    } else if (drag == 2) {
                        long ne = timeAt(x - hw / 2f);
                        long hi = Math.min(vStart + span, s + max);
                        hit = ne > s + max;
                        e = Math.max(s + min, Math.min(ne, hi));
                    } else if (drag == 3) {
                        long len = de0 - ds0;
                        long dt = (long) ((x - downX) / tw() * span);
                        long ns = Math.max(vStart, Math.min(ds0 + dt, vStart + span - len));
                        s = ns;
                        e = ns + len;
                    } else if (drag == 4) {
                        setView(vStart + (long) ((lastX - x) / tw() * span));
                        lastX = x;
                        return true;
                    } else if (drag == 6) {
                        seekOverview(x);
                        return true;
                    } else {
                        return true;
                    }
                    if (hit && !blocked) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    blocked = hit;
                    if (l != null) l.onChange(drag, s, e);
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean up = ev.getActionMasked() == MotionEvent.ACTION_UP;
                    float vx = 0f;
                    if (vt != null) {
                        vt.addMovement(ev);
                        vt.computeCurrentVelocity(1000);
                        vx = vt.getXVelocity();
                        vt.recycle();
                        vt = null;
                    }
                    if (drag == 4 && up && dur > span && Math.abs(vx) > dp(getContext(), 250)) {
                        float ppm = tw() / span;
                        sc.fling((int) (vStart * ppm), 0, (int) -vx, 0, 0, Math.max(0, (int) ((dur - span) * ppm)), 0, 0);
                        flinging = true;
                        postOnAnimation(flingStep);
                    } else if (drag == 4 || drag == 6) {
                        settle();
                    }
                    drag = 0;
                    return true;
                }
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
        final long maxSel = Math.min(MAX_SELECT_MS, dur);
        final long len0 = Math.min(MAX_CLIP_MS, dur);
        long s0 = Math.max(0L, Math.min((long) (dur * 0.27), dur - len0));
        final long[] sel = {s0, s0 + len0};
        final long[] cur = {s0};

        final Dialog dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar);
        final Handler hd = new Handler(Looper.getMainLooper());
        final boolean[] playing = {false};
        final boolean[] muted = {false};
        final boolean[] fs = {false};
        final MediaPlayer[] mpRef = {null};

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 14));

        // ----- top group: header, stepper, section title -----
        final LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.VERTICAL);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout back = new FrameLayout(act);
        back.setBackground(ripple(shape(act, 0xFF1C1C1C, 22, 0, 0)));
        back.setClickable(true);
        FrameLayout.LayoutParams bilp = new FrameLayout.LayoutParams(dp(act, 24), dp(act, 24));
        bilp.gravity = Gravity.CENTER;
        back.addView(icon(act, R.drawable.ic_arrow_back, Color.WHITE), bilp);
        head.addView(back, new LinearLayout.LayoutParams(dp(act, 44), dp(act, 44)));
        TextView ht = new TextView(act);
        ht.setText("Upload Movie");
        ht.setTextColor(Color.WHITE);
        ht.setTextSize(20);
        ht.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams htlp = new LinearLayout.LayoutParams(-2, -2);
        htlp.leftMargin = dp(act, 14);
        head.addView(ht, htlp);
        top.addView(head, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(-1, dp(act, 70));
        stlp.topMargin = dp(act, 6);
        top.addView(new Stepper(act), stlp);

        LinearLayout sec = new LinearLayout(act);
        sec.setOrientation(LinearLayout.HORIZONTAL);
        sec.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout badge = new FrameLayout(act);
        badge.setBackground(shape(act, Color.TRANSPARENT, 12, dp(act, 2), GOLD));
        FrameLayout.LayoutParams sclp = new FrameLayout.LayoutParams(dp(act, 26), dp(act, 26));
        sclp.gravity = Gravity.CENTER;
        badge.addView(icon(act, R.drawable.ic_content_cut, GOLD), sclp);
        sec.addView(badge, new LinearLayout.LayoutParams(dp(act, 52), dp(act, 52)));
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(act);
        title.setText("Choose your favorite scene");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        col.addView(title);
        TextView sub = new TextView(act);
        sub.setText("Trim the video to the moment you love most.");
        sub.setTextColor(0xFFBBBBBB);
        sub.setTextSize(14);
        col.addView(sub);
        LinearLayout.LayoutParams collp = new LinearLayout.LayoutParams(-1, -2);
        collp.leftMargin = dp(act, 12);
        sec.addView(col, collp);
        LinearLayout.LayoutParams seclp = new LinearLayout.LayoutParams(-1, -2);
        seclp.topMargin = dp(act, 10);
        top.addView(sec, seclp);

        // ----- video card -----
        FrameLayout vf = new FrameLayout(act);
        vf.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        vlp.topMargin = dp(act, 14);
        vlp.bottomMargin = dp(act, 14);
        root.addView(vf, vlp);

        final VideoView vv = new VideoView(act);
        FrameLayout.LayoutParams vvlp = new FrameLayout.LayoutParams(-1, -1);
        vvlp.gravity = Gravity.CENTER;
        vf.addView(vv, vvlp);

        View grad = new View(act);
        grad.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xCC000000}));
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(-1, dp(act, 64));
        glp.gravity = Gravity.BOTTOM;
        vf.addView(grad, glp);

        vf.addView(new CardFrame(act), new FrameLayout.LayoutParams(-1, -1));

        final FrameLayout centerPlay = new FrameLayout(act);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setShape(GradientDrawable.OVAL);
        pbg.setColor(0x99000000);
        pbg.setStroke(dp(act, 1), 0x88FFFFFF);
        centerPlay.setBackground(pbg);
        centerPlay.setClickable(true);
        FrameLayout.LayoutParams cpi = new FrameLayout.LayoutParams(dp(act, 36), dp(act, 36));
        cpi.gravity = Gravity.CENTER;
        centerPlay.addView(icon(act, R.drawable.ic_play_arrow, Color.WHITE), cpi);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(act, 64), dp(act, 64));
        plp.gravity = Gravity.CENTER;
        vf.addView(centerPlay, plp);

        LinearLayout ctl = new LinearLayout(act);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER_VERTICAL);
        ctl.setPadding(dp(act, 12), 0, dp(act, 12), dp(act, 10));
        final ImageView smallPlay = icon(act, R.drawable.ic_play_arrow, Color.WHITE);
        ctl.addView(smallPlay, new LinearLayout.LayoutParams(dp(act, 28), dp(act, 28)));
        final TextView timeTv = new TextView(act);
        timeTv.setTextColor(Color.WHITE);
        timeTv.setTextSize(14);
        LinearLayout.LayoutParams ttlp = new LinearLayout.LayoutParams(-2, -2);
        ttlp.leftMargin = dp(act, 10);
        ctl.addView(timeTv, ttlp);
        ctl.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        final ImageView muteIv = icon(act, R.drawable.ic_volume_up, Color.WHITE);
        ctl.addView(muteIv, new LinearLayout.LayoutParams(dp(act, 26), dp(act, 26)));
        final ImageView fsBot = icon(act, R.drawable.ic_fullscreen, Color.WHITE);
        LinearLayout.LayoutParams fblp = new LinearLayout.LayoutParams(dp(act, 26), dp(act, 26));
        fblp.leftMargin = dp(act, 16);
        ctl.addView(fsBot, fblp);
        FrameLayout.LayoutParams ctllp = new FrameLayout.LayoutParams(-1, -2);
        ctllp.gravity = Gravity.BOTTOM;
        vf.addView(ctl, ctllp);

        // ----- bottom group: selector, selected time, buttons -----
        final LinearLayout bottom = new LinearLayout(act);
        bottom.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams botlp = new LinearLayout.LayoutParams(-1, -2);
        botlp.leftMargin = -dp(act, 16);
        botlp.rightMargin = -dp(act, 16);
        root.addView(bottom, botlp);

        final TrimBar bar = new TrimBar(act);
        bar.max = maxSel; bar.min = MIN_CLIP_MS;
        bar.init(uri, dur, sel[0], sel[1]);
        bottom.addView(bar, new LinearLayout.LayoutParams(-1, dp(act, 94) + (dur > TrimBar.SPAN_MS ? dp(act, 14) : 0)));

        LinearLayout selRow = new LinearLayout(act);
        selRow.setOrientation(LinearLayout.HORIZONTAL);
        selRow.setGravity(Gravity.CENTER);
        selRow.addView(icon(act, R.drawable.ic_schedule, 0xFFCCCCCC), new LinearLayout.LayoutParams(dp(act, 22), dp(act, 22)));
        final TextView selected = new TextView(act);
        selected.setTextSize(17);
        LinearLayout.LayoutParams sellp = new LinearLayout.LayoutParams(-2, -2);
        sellp.leftMargin = dp(act, 8);
        selRow.addView(selected, sellp);
        LinearLayout.LayoutParams selrowlp = new LinearLayout.LayoutParams(-1, -2);
        selrowlp.topMargin = dp(act, 8);
        bottom.addView(selRow, selrowlp);

        final Runnable refresh = () -> {
            SpannableStringBuilder sb = new SpannableStringBuilder("Selected: " + fmt(sel[1] - sel[0]));
            sb.setSpan(new ForegroundColorSpan(Color.WHITE), 0, 10, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(GOLD), 10, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            selected.setText(sb);
        };
        refresh.run();

        TextView cont = new TextView(act);
        cont.setText("Continue");
        cont.setTextSize(15);
        cont.setTypeface(null, Typeface.BOLD);
        cont.setTextColor(Color.BLACK);
        cont.setGravity(Gravity.CENTER);
        cont.setBackground(ripple(shape(act, GOLD, 20, 0, 0)));
        cont.setClickable(true);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, dp(act, 40));
        clp.topMargin = dp(act, 12); clp.leftMargin = dp(act, 16); clp.rightMargin = dp(act, 16);
        bottom.addView(cont, clp);

        TextView skip = new TextView(act);
        skip.setText("Skip");
        skip.setTextSize(14);
        skip.setTextColor(0xFFCCCCCC);
        skip.setGravity(Gravity.CENTER);
        skip.setBackground(ripple(shape(act, Color.TRANSPARENT, 18, dp(act, 1), LINE)));
        skip.setClickable(true);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-1, dp(act, 36));
        klp.topMargin = dp(act, 10); klp.leftMargin = dp(act, 16); klp.rightMargin = dp(act, 16);
        bottom.addView(skip, klp);

        LinearLayout hintRow = new LinearLayout(act);
        hintRow.setOrientation(LinearLayout.HORIZONTAL);
        hintRow.setGravity(Gravity.CENTER);
        hintRow.addView(icon(act, R.drawable.ic_info, MUTED), new LinearLayout.LayoutParams(dp(act, 16), dp(act, 16)));
        TextView hint = new TextView(act);
        hint.setText("Skip trimming and use automatic preview");
        hint.setTextColor(0xFF8A8A8A);
        hint.setTextSize(13);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.leftMargin = dp(act, 6);
        hintRow.addView(hint, hlp);
        LinearLayout.LayoutParams hrlp = new LinearLayout.LayoutParams(-1, -2);
        hrlp.topMargin = dp(act, 12);
        bottom.addView(hintRow, hrlp);

        // ----- playback -----
        vv.setVideoURI(uri);
        vv.setOnPreparedListener(mp -> { mpRef[0] = mp; vv.seekTo((int) sel[0]); });
        vv.setOnErrorListener((mp, w, x) -> true);

        final Runnable uiPaused = () -> {
            centerPlay.setVisibility(View.VISIBLE);
            smallPlay.setImageResource(R.drawable.ic_play_arrow);
        };
        final Runnable uiPlaying = () -> {
            centerPlay.setVisibility(View.GONE);
            smallPlay.setImageResource(R.drawable.ic_pause);
        };

        final View.OnClickListener toggle = v -> {
            try {
                if (playing[0]) {
                    vv.pause();
                    playing[0] = false;
                    uiPaused.run();
                } else {
                    int pos = vv.getCurrentPosition();
                    if (pos < sel[0] || pos >= sel[1] - 100) vv.seekTo((int) sel[0]);
                    vv.start();
                    playing[0] = true;
                    uiPlaying.run();
                }
            } catch (Exception ex) {}
        };
        centerPlay.setOnClickListener(toggle);
        smallPlay.setOnClickListener(toggle);
        vv.setOnClickListener(toggle);

        muteIv.setOnClickListener(v -> {
            muted[0] = !muted[0];
            muteIv.setImageResource(muted[0] ? R.drawable.ic_volume_off : R.drawable.ic_volume_up);
            try { if (mpRef[0] != null) mpRef[0].setVolume(muted[0] ? 0f : 1f, muted[0] ? 0f : 1f); } catch (Exception ex) {}
        });

        final Runnable toggleFs = () -> {
            fs[0] = !fs[0];
            int vis = fs[0] ? View.GONE : View.VISIBLE;
            top.setVisibility(vis);
            bottom.setVisibility(vis);
            int ic = fs[0] ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen;
            fsBot.setImageResource(ic);
        };
        fsBot.setOnClickListener(v -> toggleFs.run());

        bar.l = (mode, ns, ne) -> {
            sel[0] = ns; sel[1] = ne;
            long t = (mode == 2) ? Math.max(ns, ne - 500) : ns;
            try { vv.pause(); if (mode != 4) vv.seekTo((int) t); } catch (Exception ex) {}
            playing[0] = false;
            cur[0] = t;
            bar.play = -1;
            uiPaused.run();
            refresh.run();
        };

        final String[] lastT = {""};
        hd.post(new Runnable() { public void run() {
            if (!dlg.isShowing()) return;
            try {
                if (playing[0]) {
                    int pos = vv.getCurrentPosition();
                    if (pos >= sel[1] - 50) {
                        vv.pause();
                        vv.seekTo((int) sel[0]);
                        playing[0] = false;
                        cur[0] = sel[0];
                        bar.play = sel[0];
                        uiPaused.run();
                    } else {
                        cur[0] = pos;
                        bar.play = pos;
                    }
                    bar.invalidate();
                }
                String tt = fmt(cur[0]) + " / " + fmt(dur);
                if (!tt.equals(lastT[0])) { lastT[0] = tt; timeTv.setText(tt); }
            } catch (Exception ex) {}
            hd.postDelayed(this, 80);
        }});

        // ----- results -----
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
        back.setOnClickListener(v -> dlg.cancel());
        dlg.setOnCancelListener(d2 -> {
            if (fin[0]) return;
            fin[0] = true;
            cb.onCancel();
        });
        dlg.setOnDismissListener(d2 -> {
            hd.removeCallbacksAndMessages(null); bar.close();
            try { vv.stopPlayback(); } catch (Exception ex) {}
        });
        dlg.setOnKeyListener((di, key, ev) -> {
            if (key == KeyEvent.KEYCODE_BACK && fs[0]) {
                if (ev.getAction() == KeyEvent.ACTION_UP) toggleFs.run();
                return true;
            }
            return false;
        });
        dlg.setCancelable(true);
        dlg.setCanceledOnTouchOutside(false);
        dlg.setContentView(root);
        dlg.show();
    }
}
