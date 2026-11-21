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

    // ---------- TikTok-style selector: drag the box, or resize with the handles (hard max) ----------
    static class TrimBar extends View {
        interface L { void onChange(int mode, long s, long e); }   // mode 1 = start handle, 2 = end handle, 3 = whole box
        long dur, s, e, max, min, play = -1;
        L l;
        Bitmap[] thumbs;
        int drag = 0, hw, bubbleH, bt;
        long ds0, de0;
        float downX;
        boolean blocked;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        Path path = new Path();
        RectF rf = new RectF(), dstF = new RectF();
        Rect src = new Rect();

        TrimBar(Context c) {
            super(c);
            hw = dp(c, 16);
            bubbleH = dp(c, 30);
            bt = dp(c, 4);
        }

        float pos(long t) { float w = getWidth() - 2f * hw; return hw + w * t / (float) dur; }
        long timeAt(float x) { float w = getWidth() - 2f * hw; return (long) (Math.max(0f, Math.min(1f, (x - hw) / w)) * dur); }

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
            Context cx = getContext();
            float W = getWidth(), H = getHeight();
            float ft = bubbleH, tw = W - 2f * hw;
            float xs = pos(s), xe = pos(e);
            float sTop = ft + bt, sBot = H - bt;
            float R = dp(cx, 8);

            // thumbnail strip (rounded), dimmed outside the selection
            c.save();
            path.reset();
            rf.set(hw, sTop, W - hw, sBot);
            path.addRoundRect(rf, R, R, Path.Direction.CW);
            c.clipPath(path);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF222222);
            c.drawRect(hw, sTop, W - hw, sBot, p);
            if (thumbs != null) {
                for (int i = 0; i < thumbs.length; i++) {
                    Bitmap b = thumbs[i];
                    if (b == null) continue;
                    float l0 = hw + tw * i / thumbs.length, r0 = hw + tw * (i + 1) / thumbs.length;
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
            }
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

            // gold frame: top/bottom bars + two handles
            p.setStyle(Paint.Style.FILL);
            p.setColor(GOLD);
            c.drawRect(xs, ft, xe, ft + bt, p);
            c.drawRect(xs, H - bt, xe, H, p);
            path.reset();
            rf.set(xs - hw, ft, xs, H);
            path.addRoundRect(rf, new float[]{R, R, 0, 0, 0, 0, R, R}, Path.Direction.CW);
            c.drawPath(path, p);
            path.reset();
            rf.set(xe, ft, xe + hw, H);
            path.addRoundRect(rf, new float[]{0, 0, R, R, R, R, 0, 0}, Path.Direction.CW);
            c.drawPath(path, p);

            // chevrons
            p.setColor(Color.BLACK);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(cx, 2));
            p.setStrokeCap(Paint.Cap.ROUND);
            float cy = (ft + H) / 2f, a = dp(cx, 5);
            float cs = xs - hw / 2f, ce = xe + hw / 2f;
            c.drawLine(cs + a / 2, cy - a, cs - a / 2, cy, p);
            c.drawLine(cs - a / 2, cy, cs + a / 2, cy + a, p);
            c.drawLine(ce - a / 2, cy - a, ce + a / 2, cy, p);
            c.drawLine(ce + a / 2, cy, ce - a / 2, cy + a, p);

            // playhead
            if (play >= s && play <= e) {
                p.setColor(Color.WHITE);
                p.setStrokeWidth(dp(cx, 2));
                float px = pos(play);
                c.drawLine(px, ft + bt, px, H - bt, p);
            }

            // time bubbles above the handles
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
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            float x = ev.getX();
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    float ds = Math.abs(x - (pos(s) - hw / 2f)), de = Math.abs(x - (pos(e) + hw / 2f));
                    float slop = dp(getContext(), 26);
                    if (ds <= de && ds < slop) drag = 1;
                    else if (de < slop) drag = 2;
                    else if (x >= pos(s) && x <= pos(e)) drag = 3;
                    else drag = 0;
                    downX = x; ds0 = s; de0 = e; blocked = false;
                    if (drag != 0 && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    boolean hit = false;
                    if (drag == 1) {
                        long ns = timeAt(x + hw / 2f);
                        long lo = Math.max(0L, e - max);
                        hit = ns < e - max;
                        s = Math.max(lo, Math.min(ns, e - min));
                    } else if (drag == 2) {
                        long ne = timeAt(x - hw / 2f);
                        long hi = Math.min(dur, s + max);
                        hit = ne > s + max;
                        e = Math.max(s + min, Math.min(ne, hi));
                    } else if (drag == 3) {
                        long len = de0 - ds0;
                        long dt = (long) ((x - downX) / (getWidth() - 2f * hw) * dur);
                        long ns = Math.max(0L, Math.min(ds0 + dt, dur - len));
                        s = ns;
                        e = ns + len;
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
        sub.setText("Trim the movie to the moment you love most.");
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

        final ImageView fsTop = icon(act, R.drawable.ic_fullscreen, Color.WHITE);
        FrameLayout.LayoutParams ftlp = new FrameLayout.LayoutParams(dp(act, 26), dp(act, 26));
        ftlp.gravity = Gravity.TOP | Gravity.END;
        ftlp.topMargin = dp(act, 12);
        ftlp.rightMargin = dp(act, 12);
        vf.addView(fsTop, ftlp);

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
        root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

        final TrimBar bar = new TrimBar(act);
        final int N = 8;
        bar.thumbs = new Bitmap[N];
        bar.dur = dur; bar.s = sel[0]; bar.e = sel[1]; bar.max = maxSel; bar.min = MIN_CLIP_MS;
        bottom.addView(bar, new LinearLayout.LayoutParams(-1, dp(act, 94)));

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
        clp.topMargin = dp(act, 12);
        bottom.addView(cont, clp);

        TextView skip = new TextView(act);
        skip.setText("Skip");
        skip.setTextSize(14);
        skip.setTextColor(0xFFCCCCCC);
        skip.setGravity(Gravity.CENTER);
        skip.setBackground(ripple(shape(act, Color.TRANSPARENT, 18, dp(act, 1), LINE)));
        skip.setClickable(true);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-1, dp(act, 36));
        klp.topMargin = dp(act, 10);
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
            fsTop.setImageResource(ic);
            fsBot.setImageResource(ic);
        };
        fsTop.setOnClickListener(v -> toggleFs.run());
        fsBot.setOnClickListener(v -> toggleFs.run());

        bar.l = (mode, ns, ne) -> {
            sel[0] = ns; sel[1] = ne;
            long t = (mode == 2) ? Math.max(ns, ne - 500) : ns;
            try { vv.pause(); vv.seekTo((int) t); } catch (Exception ex) {}
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

        // ----- thumbnails -----
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
                    final Bitmap bmp = Bitmap.createScaledBitmap(b, tw, th, true);
                    if (bmp != b) b.recycle();
                    act.runOnUiThread(() -> { bar.thumbs[idx] = bmp; bar.invalidate(); });
                }
            } catch (Exception ex) {}
            try { m.release(); } catch (Exception ex) {}
        }).start();

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
            hd.removeCallbacksAndMessages(null);
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
