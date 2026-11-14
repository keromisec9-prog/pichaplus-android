package com.pichaplus.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Looper;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.core.graphics.PathParser;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NativeShortsView extends FrameLayout {
    public interface Host { void js(String code); }

    private static final int GOLD = 0xFFE5B93C;
    private static final int RED = 0xFFFF3B5C;
    private static final String P_HEART = "M12 21.35 l-1.45-1.32 C5.4 15.36 2 12.28 2 8.5 C2 5.42 4.42 3 7.5 3 c1.74 0 3.41 0.81 4.5 2.09 C13.09 3.81 14.76 3 16.5 3 C19.58 3 22 5.42 22 8.5 c0 3.78-3.4 6.86-8.55 11.54 L12 21.35 z";
    private static final String P_SHARE = "M18 16.08 c-0.76 0-1.44 0.3-1.96 0.77 L8.91 12.7 c0.05-0.23 0.09-0.46 0.09-0.7 s-0.04-0.47-0.09-0.7 l7.05-4.11 c0.54 0.5 1.25 0.81 2.04 0.81 1.66 0 3-1.34 3-3 s-1.34-3-3-3-3 1.34-3 3 c0 0.24 0.04 0.47 0.09 0.7 L8.04 9.81 C7.5 9.31 6.79 9 6 9 c-1.66 0-3 1.34-3 3 s1.34 3 3 3 c0.79 0 1.5-0.31 2.04-0.81 l7.12 4.16 c-0.05 0.21-0.08 0.43-0.08 0.65 0 1.61 1.31 2.92 2.92 2.92 1.61 0 2.92-1.31 2.92-2.92 s-1.31-2.92-2.92-2.92 z";
    private static final String P_SAVE = "M17 3H7c-1.1 0-1.99 0.9-1.99 2L5 21l7-3 7 3V5c0-1.1-0.9-2-2-2z";
    private static final String P_BACK = "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z";
    private static final String P_PLAY = "M8 5v14l11-7z";
    private static final String P_AUTO_ON = "M12 3.5L4 11.5H8V15.5H16V11.5H20L12 3.5Z M9 17.5H15V19.5H9V17.5Z M10 21H14V22.5H10V21Z";
    private static final String P_AUTO_OFF = "M12 3.5L7 8.5H10V14H14V8.5H17L12 3.5Z M8.5 16H15.5V18H8.5V16Z M4.2 5L18.8 19.6L20.2 18.2L5.6 3.6L4.2 5Z";
    private static final String P_FULL_IN = "M7 14H5v5h5v-2H7v-3z M5 10h2V7h3V5H5v5z M17 17h-3v2h5v-5h-2v3z M14 5v2h3v3h2V5h-5z";
    private static final String P_FULL_OUT = "M5 16h3v3h2v-5H5v2z M8 8H5v2h5V5H8v3z M14 19h2v-3h3v-2h-5v5z M16 8V5h-2v5h5V8h-3z";

    static final class Item {
        String id = "", meta = "", title = "", desc = "", cta = "", likeCount = "Like", saveLabel = "Save", saveCount = "";
        boolean isShort, liked, saved;
        long preStartMs;
        String url;
    }

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();
    private final Map<String, String> urlById = new HashMap<>();
    private final SparseArray<SP> players = new SparseArray<>();
    private final SparseArray<Slide> slides = new SparseArray<>();
    private ViewPager2 pager;
    private TextView dbgTv;
    private final ArrayList<String> dbgLines = new ArrayList<>();
    private long tOpen;
    void dbg(String s) {
        dbgLines.add(s);
        while (dbgLines.size() > 6) dbgLines.remove(0);
        if (dbgTv != null) dbgTv.setText(TextUtils.join("\n", dbgLines));
    }
    private int cur = 0;
    private long clipMs = 30000L;
    private boolean auto = true, full = false, overlay = false, shown = false, hostPaused = false;

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
    private void act(String a, int i) { host.js("window._mpfNatAct&&window._mpfNatAct('" + a + "'," + i + ")"); }

    public NativeShortsView(Context c, Host host) {
        super(c);
        this.host = host;
        setBackgroundColor(Color.BLACK);
        setVisibility(GONE);
        pager = new ViewPager2(c);
        pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        pager.setOffscreenPageLimit(1);
        pager.setAdapter(new Adapter());
        View rv = pager.getChildAt(0);
        if (rv instanceof RecyclerView) rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int pos) { activate(pos); }
        });
        addView(pager, new LayoutParams(-1, -1));
        dbgTv = new TextView(c);
        dbgTv.setTextColor(0xFF00FF88);
        dbgTv.setTextSize(10);
        dbgTv.setBackgroundColor(0x99000000);
        dbgTv.setPadding(dp(c, 6), dp(c, 3), dp(c, 6), dp(c, 3));
        LayoutParams dl = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        dl.topMargin = dp(c, 70);
        dl.rightMargin = dp(c, 8);
        addView(dbgTv, dl);
    }

    // ---------------- public API (UI thread) ----------------
    void open(JSONObject cfg) {
        releaseAll();
        overlay = cfg.optBoolean("overlay");
        auto = cfg.optBoolean("auto", true);
        full = false;
        clipMs = Math.max(5, cfg.optInt("clip", 30)) * 1000L;
        tOpen = SystemClock.elapsedRealtime();
        dbgLines.clear();
        setItems(cfg.optJSONArray("items"));
        cur = 0;
        pager.setCurrentItem(0, false);
        applyFlagsAll();
        main.removeCallbacks(tick);
        main.postDelayed(tick, 200);
        activate(0);
    }

    void setItems(JSONArray a) {
        Map<String, Item> old = new HashMap<>();
        for (Item it : items) old.put(it.id, it);
        items.clear();
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id");
                Item it = old.get(id);
                if (it == null) {
                    it = new Item();
                    it.id = id;
                    it.title = o.optString("t");
                    it.meta = o.optString("m");
                    it.desc = o.optString("d");
                    it.url = urlById.get(id);
                }
                it.isShort = o.optInt("s") == 1;
                it.preStartMs = o.optLong("st") * 1000L;
                items.add(it);
            }
        }
        pager.getAdapter().notifyDataSetChanged();
        trimAndEnsure();
    }

    void setUrl(int idx, String id, String url) {
        urlById.put(id, url);
        dbg("url #" + idx + " +" + (SystemClock.elapsedRealtime() - tOpen) + "ms");
        for (Item it : items) if (it.id.equals(id)) it.url = url;
        trimAndEnsure();
        updateSpinner(cur);
    }

    void snap(JSONArray a) {
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            int i = o.optInt("i", -1);
            if (i < 0 || i >= items.size()) continue;
            Item it = items.get(i);
            it.meta = o.optString("m");
            it.title = o.optString("t", it.title);
            it.desc = o.optString("d");
            it.cta = o.optString("c");
            it.liked = o.optInt("l") == 1;
            it.likeCount = o.optString("lc", "Like");
            it.saved = o.optInt("s") == 1;
            it.saveLabel = o.optString("sl", "Save");
            it.saveCount = o.optString("sc");
            pager.getAdapter().notifyItemChanged(i, "ui");
        }
    }

    void applyState(JSONObject s) {
        boolean v = s.optInt("v") == 1;
        if (v) {
            float d = getResources().getDisplayMetrics().density;
            int x = Math.round(s.optInt("x") * d), y = Math.round(s.optInt("y") * d);
            int w = Math.round(s.optInt("w") * d), hh = Math.round(s.optInt("h") * d);
            ViewGroup.LayoutParams cp = getLayoutParams();
            boolean same = false;
            if (cp instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cp;
                same = lp.width == w && lp.height == hh && lp.leftMargin == x && lp.topMargin == y;
            }
            if (!same) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, hh);
                lp.leftMargin = x;
                lp.topMargin = y;
                setLayoutParams(lp);
            }
        }
        overlay = s.optInt("o") == 1;
        full = s.optInt("f") == 1;
        auto = s.optInt("a") == 1;
        applyFlagsAll();
        setShown(v);
    }

    boolean isOverlayShown() { return shown && overlay && getVisibility() == VISIBLE; }

    void hostPause() {
        hostPaused = true;
        for (int i = 0; i < players.size(); i++) players.valueAt(i).p.setPlayWhenReady(false);
    }

    void hostResume() {
        hostPaused = false;
        if (shown) { SP sp = players.get(cur); if (sp != null) sp.play(); }
    }

    void close() {
        setShown(false);
        releaseAll();
        main.removeCallbacksAndMessages(null);
        items.clear();
        urlById.clear();
    }

    // ---------------- internals ----------------
    private void setShown(boolean v) {
        if (v == shown && (getVisibility() == VISIBLE) == v) return;
        shown = v;
        setVisibility(v ? VISIBLE : GONE);
        if (v) {
            SP sp = players.get(cur);
            if (sp != null) sp.play();
        } else {
            for (int i = 0; i < players.size(); i++) players.valueAt(i).p.setPlayWhenReady(false);
        }
    }

    private void applyFlagsAll() {
        for (int i = 0; i < slides.size(); i++) slides.valueAt(i).applyFlags();
    }

    private void releaseAll() {
        for (int i = 0; i < players.size(); i++) players.valueAt(i).release();
        players.clear();
    }

    private void activate(int pos) {
        if (pos < 0 || pos >= items.size()) return;
        cur = pos;
        for (int i = 0; i < players.size(); i++) {
            SP sp = players.valueAt(i);
            if (sp.idx != pos) sp.pause();
        }
        for (int i = 0; i < slides.size(); i++) slides.valueAt(i).pauseCircle.setVisibility(View.GONE);
        SP sp = players.get(pos);
        if (sp != null) {
            sp.startPrepare();
            if (sp.played && sp.startApplied) sp.p.seekTo(sp.startMs);
            sp.played = true;
            sp.play();
        }
        trimAndEnsure();
        updateSpinner(pos);
        host.js("window._mpfNatPage&&window._mpfNatPage(" + pos + ")");
    }

    private void trimAndEnsure() {
        for (int i = players.size() - 1; i >= 0; i--) {
            SP sp = players.valueAt(i);
            boolean stale = sp.idx < cur - 1 || sp.idx > cur + 1 || sp.idx >= items.size() || !items.get(sp.idx).id.equals(sp.itemId);
            if (stale) { sp.release(); players.removeAt(i); }
        }
        ensure(cur);
        ensure(cur + 1);
        ensure(cur - 1);
    }

    private void ensure(int i) {
        if (i < 0 || i >= items.size()) return;
        Item it = items.get(i);
        if (it.url == null) return;
        SP sp = players.get(i);
        if (sp != null) return;
        sp = new SP(i, it.id, it.url);
        players.put(i, sp);
        attach(i);
        if (i == cur) { sp.startPrepare(); sp.played = true; sp.play(); }
    }

    private void attach(int i) {
        SP sp = players.get(i);
        Slide s = slides.get(i);
        if (sp == null || s == null || sp.dead) return;
        sp.p.setVideoTextureView(s.box.tv);
        if (sp.vw > 0) s.box.setVideo(sp.vw, sp.vh);
    }

    private void updateSpinner(int i) {
        Slide s = slides.get(i);
        if (s == null) return;
        SP sp = players.get(i);
        boolean busy = i == cur && (sp == null || sp.p.getPlaybackState() != Player.STATE_READY);
        s.spin.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void togglePause(int i) {
        SP sp = players.get(i);
        Slide s = slides.get(i);
        if (sp == null) return;
        if (sp.p.getPlayWhenReady()) {
            sp.pause();
            if (s != null) s.pauseCircle.setVisibility(View.VISIBLE);
        } else {
            sp.play();
            if (s != null) s.pauseCircle.setVisibility(View.GONE);
        }
    }

    private void seekFrac(int i, float f) {
        SP sp = players.get(i);
        if (sp != null && sp.startApplied) sp.p.seekTo(sp.startMs + (long) (f * clipMs));
    }

    private void stagger() {
        if (!shown) return;
        SP c = players.get(cur);
        if (c == null || c.dead || !c.prepared) return;
        long dur = c.p.getDuration();
        long ahead = c.p.getBufferedPosition() - c.p.getCurrentPosition();
        boolean cushion = c.p.getPlaybackState() == Player.STATE_READY && c.startApplied
            && (ahead >= 3000 || (dur != C.TIME_UNSET && c.p.getBufferedPosition() >= dur - 300));
        if (!cushion) return;
        SP n = players.get(cur + 1);
        if (n != null && !n.prepared) { n.startPrepare(); return; }
        if (n == null || n.p.getPlaybackState() == Player.STATE_READY) {
            SP pv = players.get(cur - 1);
            if (pv != null && !pv.prepared) pv.startPrepare();
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (shown) {
                SP sp = players.get(cur);
                if (sp != null && sp.startApplied) {
                    long el = sp.p.getCurrentPosition() - sp.startMs;
                    Slide s = slides.get(cur);
                    if (s != null) s.seek.setProgress(Math.max(0f, Math.min(1f, el / (float) clipMs)));
                    if (el >= clipMs && sp.p.getPlayWhenReady()) {
                        if (auto && cur + 1 < items.size()) pager.setCurrentItem(cur + 1, true);
                        else sp.p.seekTo(sp.startMs);
                    }
                }
            }
            stagger();
            main.postDelayed(this, 200);
        }
    };

    // ---------------- one ExoPlayer per nearby slide ----------------
    final class SP implements Player.Listener {
        final int idx;
        final String itemId;
        final ExoPlayer p;
        boolean startApplied, played, want, dead, prepared;
        boolean preSet;
        long tPrep, tReady;
        long startMs;
        int vw, vh;

        SP(int idx, String itemId, String url) {
            this.idx = idx;
            this.itemId = itemId;
            DefaultLoadControl lc = new DefaultLoadControl.Builder().setBufferDurationsMs(2000, 8000, 300, 800).build();
            p = new ExoPlayer.Builder(getContext()).setLoadControl(lc).build();
            p.addListener(this);
            Item it0 = idx < items.size() ? items.get(idx) : null;
            long pre = (it0 != null && !it0.isShort) ? it0.preStartMs : 0L;
            if (pre > 0) { startMs = pre; preSet = true; p.setMediaItem(MediaItem.fromUri(url), pre); }
            else p.setMediaItem(MediaItem.fromUri(url));
            p.setPlayWhenReady(false);
        }

        void startPrepare() {
            if (prepared || dead) return;
            prepared = true;
            tPrep = SystemClock.elapsedRealtime();
            p.prepare();
        }

        private void applyStart() {
            if (startApplied || dead) return;
            if (preSet) { startApplied = true; return; }
            long dur = p.getDuration();
            if (dur == C.TIME_UNSET || dur <= 0) return;
            startApplied = true;
            Item it = idx < items.size() ? items.get(idx) : null;
            if (it != null && !it.isShort) {
                startMs = Math.min((long) (dur * 0.27), Math.max(0L, dur - clipMs));
            }
            if (startMs > 0) p.seekTo(startMs);
        }

        @Override public void onTimelineChanged(Timeline t, int reason) { applyStart(); }

        @Override public void onPlaybackStateChanged(int st) {
            if (dead) return;
            if (st == Player.STATE_READY) {
                applyStart();
                startApplied = true;
                if (tReady == 0 && tPrep != 0) {
                    tReady = SystemClock.elapsedRealtime();
                    dbg("#" + idx + (idx == cur ? " cur" : " nbr") + " ready " + (tReady - tPrep) + "ms @" + (startMs / 1000) + "s");
                }
            }
            if (st == Player.STATE_READY && want && shown && !hostPaused) p.setPlayWhenReady(true);
            if (st == Player.STATE_ENDED && idx == cur) { p.seekTo(startMs); p.setPlayWhenReady(true); }
            updateSpinner(idx);
        }

        @Override public void onRenderedFirstFrame() {
            if (tReady != 0) dbg("#" + idx + " frame +" + (SystemClock.elapsedRealtime() - tReady) + "ms");
        }

        @Override public void onVideoSizeChanged(VideoSize v) {
            if (v.width <= 0 || v.height <= 0) return;
            int w = Math.round(v.width * v.pixelWidthHeightRatio), hh = v.height;
            if (v.unappliedRotationDegrees == 90 || v.unappliedRotationDegrees == 270) { int t = w; w = hh; hh = t; }
            vw = w;
            vh = hh;
            Slide s = slides.get(idx);
            if (s != null) s.box.setVideo(vw, vh);
        }

        @Override public void onPlayerError(PlaybackException e) {
            main.postDelayed(new Runnable() {
                @Override public void run() { if (!dead) { try { prepared = true; p.prepare(); } catch (Throwable t) { } } }
            }, 1500);
        }

        void play() { want = true; if (startApplied && shown && !hostPaused) p.setPlayWhenReady(true); }
        void pause() { want = false; p.setPlayWhenReady(false); }
        void release() { dead = true; try { p.removeListener(this); p.release(); } catch (Throwable t) { } }
    }

    // ---------------- pager plumbing ----------------
    final class Holder extends RecyclerView.ViewHolder {
        final Slide slide;
        int pos = -1;
        Holder(Slide s) { super(s); slide = s; }
    }

    final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            Slide s = new Slide(parent.getContext());
            s.setLayoutParams(new RecyclerView.LayoutParams(-1, -1));
            return new Holder(s);
        }
        @Override public void onBindViewHolder(Holder h, int pos) {
            h.pos = pos;
            slides.put(pos, h.slide);
            h.slide.bind(pos, items.get(pos));
            attach(pos);
            updateSpinner(pos);
        }
        @Override public void onBindViewHolder(Holder h, int pos, List<Object> payloads) {
            if (payloads == null || payloads.isEmpty()) { onBindViewHolder(h, pos); return; }
            if (pos < items.size()) h.slide.refresh(items.get(pos));
        }
        @Override public void onViewRecycled(Holder h) {
            if (slides.get(h.pos) == h.slide) {
                slides.remove(h.pos);
                SP sp = players.get(h.pos);
                if (sp != null && !sp.dead) sp.p.clearVideoTextureView(h.slide.box.tv);
            }
        }
        @Override public int getItemCount() { return items.size(); }
    }

    // ---------------- views ----------------
    static final class Ico extends View {
        private Path path;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean filled = true;
        Ico(Context c, String d) {
            super(c);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Color.WHITE);
            setPathData(d);
        }
        void setPathData(String d) {
            try { path = PathParser.createPathFromPathData(d); } catch (Throwable t) { path = null; }
            invalidate();
        }
        void set(boolean filled, int color) { this.filled = filled; paint.setColor(color); invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            if (path == null) return;
            float s = Math.min(getWidth(), getHeight()) / 24f;
            cv.save();
            cv.scale(s, s);
            paint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            cv.drawPath(path, paint);
            cv.restore();
        }
    }

    final class VideoBox extends FrameLayout {
        TextureView tv;
        int vw = 16, vh = 9, cw = 1, ch = 1;
        VideoBox(Context c) {
            super(c);
            tv = new TextureView(c);
            tv.setAlpha(0f);
            addView(tv, new LayoutParams(1, 1));
        }
        void setVideo(int w, int h2) {
            if (w <= 0 || h2 <= 0) return;
            vw = w;
            vh = h2;
            tv.setAlpha(1f);
            requestLayout();
        }
        @Override protected void onMeasure(int wm, int hm) {
            int W = MeasureSpec.getSize(wm), H = MeasureSpec.getSize(hm);
            setMeasuredDimension(W, H);
            float s = Math.min(W / (float) vw, H / (float) vh);
            cw = Math.max(1, Math.round(vw * s));
            ch = Math.max(1, Math.round(vh * s));
            tv.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int W = r - l, H = b - t;
            int x = (W - cw) / 2, y = (H - ch) / 2;
            tv.layout(x, y, x + cw, y + ch);
        }
    }

    final class SeekLine extends View {
        int idx = -1;
        float prog = 0f;
        Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
        SeekLine(Context c) {
            super(c);
            track.setColor(0x40FFFFFF);
            fillP.setColor(GOLD);
        }
        void setProgress(float p) { prog = p; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            float cy = getHeight() / 2f, th = dp(getContext(), 2) / 2f, w = getWidth();
            cv.drawRect(0, cy - th, w, cy + th, track);
            cv.drawRect(0, cy - th, w * prog, cy + th, fillP);
            cv.drawCircle(w * prog, cy, dp(getContext(), 4.5f), fillP);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            if (getWidth() <= 0) return true;
            float f = Math.max(0f, Math.min(1f, e.getX() / getWidth()));
            setProgress(f);
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_MOVE) seekFrac(idx, f);
            return true;
        }
    }

    final class Slide extends FrameLayout {
        int idx = -1;
        VideoBox box;
        View grad;
        TextView tvMeta, tvTitle, tvDesc, tvCta, tvLike, tvSave, tvSaveCnt;
        Ico icLike, icSave, icAuto, icFull;
        LinearLayout ctaBtn, fullCol;
        FrameLayout backBtn, pauseCircle;
        ProgressBar spin;
        SeekLine seek;

        private TextView label(Context c, float sp) {
            TextView t = new TextView(c);
            t.setTextColor(Color.WHITE);
            t.setTextSize(sp);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            return t;
        }

        private LinearLayout col(Context c, Ico ic, int size, View.OnClickListener l, TextView... ls) {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(dp(c, 6), dp(c, 8), dp(c, 6), dp(c, 8));
            col.addView(ic, new LinearLayout.LayoutParams(dp(c, size), dp(c, size)));
            for (TextView t : ls) col.addView(t, new LinearLayout.LayoutParams(-2, -2));
            col.setOnClickListener(l);
            return col;
        }

        Slide(Context c) {
            super(c);
            setBackgroundColor(Color.BLACK);
            box = new VideoBox(c);
            addView(box, new LayoutParams(-1, -1));

            grad = new View(c);
            grad.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xD9000000, 0x00000000}));
            addView(grad, new LayoutParams(-1, dp(c, 220), Gravity.BOTTOM));

            spin = new ProgressBar(c);
            spin.setIndeterminateTintList(ColorStateList.valueOf(GOLD));
            spin.setVisibility(GONE);
            addView(spin, new LayoutParams(dp(c, 40), dp(c, 40), Gravity.CENTER));

            pauseCircle = new FrameLayout(c);
            GradientDrawable pc = new GradientDrawable();
            pc.setShape(GradientDrawable.OVAL);
            pc.setColor(0x66000000);
            pauseCircle.setBackground(pc);
            Ico pi = new Ico(c, P_PLAY);
            pauseCircle.addView(pi, new LayoutParams(dp(c, 38), dp(c, 38), Gravity.CENTER));
            pauseCircle.setVisibility(GONE);
            addView(pauseCircle, new LayoutParams(dp(c, 72), dp(c, 72), Gravity.CENTER));

            LinearLayout right = new LinearLayout(c);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setGravity(Gravity.CENTER_HORIZONTAL);
            LayoutParams rl = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            rl.rightMargin = dp(c, 8);
            rl.bottomMargin = dp(c, 200);
            addView(right, rl);

            icLike = new Ico(c, P_HEART);
            tvLike = label(c, 11);
            right.addView(col(c, icLike, 28, new OnClickListener() { @Override public void onClick(View v) { act("like", idx); } }, tvLike));

            Ico icShare = new Ico(c, P_SHARE);
            TextView tvShare = label(c, 11);
            tvShare.setText("Share");
            right.addView(col(c, icShare, 26, new OnClickListener() { @Override public void onClick(View v) { act("share", idx); } }, tvShare));

            icSave = new Ico(c, P_SAVE);
            tvSave = label(c, 11);
            tvSaveCnt = label(c, 10);
            tvSaveCnt.setAlpha(0.85f);
            right.addView(col(c, icSave, 26, new OnClickListener() { @Override public void onClick(View v) { act("save", idx); } }, tvSave, tvSaveCnt));

            icAuto = new Ico(c, P_AUTO_ON);
            TextView tvAuto = label(c, 11);
            tvAuto.setText("Auto");
            right.addView(col(c, icAuto, 26, new OnClickListener() { @Override public void onClick(View v) { act("auto", idx); } }, tvAuto));

            icFull = new Ico(c, P_FULL_IN);
            TextView tvFull = label(c, 11);
            tvFull.setText("Full");
            fullCol = col(c, icFull, 26, new OnClickListener() { @Override public void onClick(View v) { act("full", idx); } }, tvFull);
            right.addView(fullCol);

            LinearLayout info = new LinearLayout(c);
            info.setOrientation(LinearLayout.VERTICAL);
            LayoutParams il = new LayoutParams(-1, -2, Gravity.BOTTOM);
            il.leftMargin = dp(c, 16);
            il.rightMargin = dp(c, 64);
            il.bottomMargin = dp(c, 22);
            addView(info, il);

            tvMeta = label(c, 11);
            tvMeta.setTextColor(GOLD);
            tvMeta.setLetterSpacing(0.06f);
            info.addView(tvMeta, new LinearLayout.LayoutParams(-2, -2));

            tvTitle = label(c, 18);
            tvTitle.setShadowLayer(6f, 0f, 2f, 0x99000000);
            info.addView(tvTitle, new LinearLayout.LayoutParams(-2, -2));

            tvDesc = new TextView(c);
            tvDesc.setTextColor(0xD9FFFFFF);
            tvDesc.setTextSize(13);
            tvDesc.setMaxLines(2);
            tvDesc.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(tvDesc, new LinearLayout.LayoutParams(-2, -2));

            ctaBtn = new LinearLayout(c);
            ctaBtn.setOrientation(LinearLayout.HORIZONTAL);
            ctaBtn.setGravity(Gravity.CENTER);
            GradientDrawable cb = new GradientDrawable();
            cb.setCornerRadius(dp(c, 10));
            cb.setColor(0x66282828);
            ctaBtn.setBackground(cb);
            ctaBtn.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
            Ico icCta = new Ico(c, P_PLAY);
            ctaBtn.addView(icCta, new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)));
            tvCta = label(c, 14);
            LinearLayout.LayoutParams tcl = new LinearLayout.LayoutParams(-2, -2);
            tcl.leftMargin = dp(c, 6);
            ctaBtn.addView(tvCta, tcl);
            ctaBtn.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { act("watch", idx); } });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
            cl.topMargin = dp(c, 8);
            info.addView(ctaBtn, cl);

            seek = new SeekLine(c);
            LinearLayout.LayoutParams sk = new LinearLayout.LayoutParams(-1, dp(c, 14));
            sk.topMargin = dp(c, 6);
            info.addView(seek, sk);

            backBtn = new FrameLayout(c);
            GradientDrawable bb = new GradientDrawable();
            bb.setShape(GradientDrawable.OVAL);
            bb.setColor(0x80000000);
            backBtn.setBackground(bb);
            backBtn.addView(new Ico(c, P_BACK), new LayoutParams(dp(c, 26), dp(c, 26), Gravity.CENTER));
            LayoutParams bl = new LayoutParams(dp(c, 42), dp(c, 42), Gravity.TOP | Gravity.START);
            bl.topMargin = dp(c, 12);
            bl.leftMargin = dp(c, 12);
            backBtn.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { act("back", idx); } });
            backBtn.setVisibility(GONE);
            addView(backBtn, bl);

            final GestureDetector g = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onSingleTapConfirmed(MotionEvent e) { togglePause(idx); return true; }
                @Override public boolean onDoubleTap(MotionEvent e) { act("dbl", idx); return true; }
            });
            setOnTouchListener(new OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) { g.onTouchEvent(e); return true; }
            });
        }

        @Override protected void onSizeChanged(int w, int h2, int ow, int oh) {
            super.onSizeChanged(w, h2, ow, oh);
            ViewGroup.LayoutParams lp = grad.getLayoutParams();
            lp.height = Math.round(h2 * 0.4f);
            grad.setLayoutParams(lp);
        }

        void bind(int i, Item it) {
            idx = i;
            seek.idx = i;
            seek.setProgress(0f);
            box.tv.setAlpha(0f);
            pauseCircle.setVisibility(GONE);
            refresh(it);
            applyFlags();
        }

        void refresh(Item it) {
            tvMeta.setText(it.meta.toUpperCase());
            tvMeta.setVisibility(it.meta.isEmpty() ? GONE : VISIBLE);
            tvTitle.setText(it.title);
            tvDesc.setText(it.desc);
            tvDesc.setVisibility(it.desc.isEmpty() ? GONE : VISIBLE);
            tvCta.setText(it.cta);
            ctaBtn.setVisibility(it.cta.isEmpty() ? GONE : VISIBLE);
            icLike.set(it.liked, it.liked ? RED : Color.WHITE);
            tvLike.setText(it.likeCount);
            icSave.set(it.saved, it.saved ? GOLD : Color.WHITE);
            tvSave.setText(it.saveLabel);
            tvSave.setTextColor(it.saved ? GOLD : Color.WHITE);
            tvSaveCnt.setText(it.saveCount);
        }

        void applyFlags() {
            icAuto.setPathData(auto ? P_AUTO_ON : P_AUTO_OFF);
            icFull.setPathData(full ? P_FULL_OUT : P_FULL_IN);
            fullCol.setVisibility((!overlay || full) ? VISIBLE : GONE);
            backBtn.setVisibility(overlay ? VISIBLE : GONE);
        }
    }
}
