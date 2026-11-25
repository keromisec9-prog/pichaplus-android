package com.pichaplus.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** Saves a short to the gallery with the hopping MyPicha+ lockup, fully on device. */
final class ShortSaver {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean busy = false, cancelled = false;
    private static volatile float pFetch = 0f, pExport = 0f;
    private static volatile int lastPct = -1;
    private static Transformer cur;
    private static File curSrc, curOut;
    private static Context curApp;
    private static final long HOP_US = 3500000L;
    private static final int CAP_H = 1280, PAD_W = 720;
    private static ViewGroup barParent;
    private static View barRoot, barFill;
    private static FrameLayout barTrack;
    private static TextView barLabel, barCancel;
    private static int barGen = 0;

    static void save(final Context ctx, final String id, final String url, final String user) {
        final Context app = ctx.getApplicationContext();
        if (busy) { toast(app, "Still saving the last video"); return; }
        try { if (existing(app, id) != null) { toast(app, "Already saved in Movies > Picha+"); return; } } catch (Throwable t) { }
        busy = true; cancelled = false; pFetch = 0f; pExport = 0f; lastPct = -1;
        if (ctx instanceof Activity) showBar((Activity) ctx); else toast(app, "Saving video...");
        new Thread(() -> {
            File src = new File(app.getCacheDir(), "dl_src.mp4");
            try {
                fetch(app, url, src);
                if (cancelled) throw new InterruptedException("canceled");
                int[] wh = size(src);
                start(app, src, id, user, wh[0], wh[1]);
            } catch (Throwable t) { src.delete(); end(app, cancelled ? "Canceled" : "Could not download this video"); }
        }, "short-save").start();
    }

    private static void start(final Context app, final File src, final String id, final String user, final int sw, final int sh) {
        MAIN.post(() -> {
            final File out = new File(app.getCacheDir(), "dl_out.mp4");
            curSrc = src; curOut = out; curApp = app;
            try {
                if (cancelled) throw new IllegalStateException("canceled");
                out.delete();
                boolean pad = sw > 0 && sh > 0 && (float) sw / sh > 0.70f;
                int cw, ch;
                if (pad) { cw = Math.min(sw, PAD_W); cw -= cw % 2; ch = cw * 16 / 9; ch -= ch % 2; }
                else { ch = Math.min(sh, CAP_H); ch -= ch % 2; cw = Math.round(sw * (float) ch / sh); cw -= cw % 2; }
                Bitmap mark = decode(app, R.drawable.wm_mark), word = decode(app, R.drawable.wm_word);
                if (mark == null || word == null) throw new IllegalStateException("logo");
                ImmutableList.Builder<Effect> fx = ImmutableList.builder();
                if (pad) fx.add(Presentation.createForWidthAndHeight(cw, ch, Presentation.LAYOUT_SCALE_TO_FIT));
                else if (sh > CAP_H) fx.add(Presentation.createForHeight(ch));
                fx.add(new OverlayEffect(ImmutableList.<TextureOverlay>of(new HopOverlay(cw, ch, mark, word, tag(user)))));
                EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src)))
                    .setEffects(new Effects(ImmutableList.of(), fx.build())).build();
                cur = new Transformer.Builder(app).addListener(new Transformer.Listener() {
                    @Override public void onCompleted(Composition c, ExportResult r) { finish(app, src, out, id); }
                    @Override public void onError(Composition c, ExportResult r, ExportException e) { src.delete(); out.delete(); end(app, cancelled ? "Canceled" : "Could not add the watermark"); }
                }).build();
                cur.start(item, out.getAbsolutePath());
                final ProgressHolder ph = new ProgressHolder();
                MAIN.postDelayed(new Runnable() {
                    @Override public void run() {
                        Transformer tr = cur;
                        if (tr == null || cancelled) return;
                        try { if (tr.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) { pExport = ph.progress / 100f; render(null); } } catch (Throwable x) { }
                        MAIN.postDelayed(this, 200);
                    }
                }, 300);
            } catch (Throwable t) { src.delete(); out.delete(); end(app, cancelled ? "Canceled" : "Could not add the watermark"); }
        });
    }

    private static void finish(final Context app, final File src, final File out, final String id) {
        cur = null;
        pExport = 1f; render(null);
        MAIN.post(() -> { if (barCancel != null) barCancel.setVisibility(View.GONE); });
        new Thread(() -> {
            try {
                Uri u = publish(app, out, "MyPicha_" + (System.currentTimeMillis() / 1000) + ".mp4");
                app.getSharedPreferences("pp_short_saves", 0).edit().putString("s_" + id, u.toString()).apply();
                src.delete(); out.delete();
                done(app);
            } catch (Throwable t) { src.delete(); out.delete(); end(app, "Could not save the video"); }
        }, "short-save-pub").start();
    }

    private static void cancel() {
        cancelled = true;
        Transformer t = cur;
        if (t != null) {
            try { t.cancel(); } catch (Throwable e) { }
            if (curSrc != null) curSrc.delete();
            if (curOut != null) curOut.delete();
            end(curApp, "Canceled");
        }
    }

    private static void done(final Context app) {
        busy = false; cur = null;
        final int g = barGen;
        MAIN.post(() -> {
            if (barLabel != null) barLabel.setText("Saved to Movies > Picha+");
            if (barCancel != null) barCancel.setVisibility(View.GONE);
            if (barTrack != null && barFill != null) { ViewGroup.LayoutParams lp = barFill.getLayoutParams(); lp.width = barTrack.getWidth(); barFill.setLayoutParams(lp); }
            if (barRoot == null) toast(app, "Saved to Movies > Picha+");
        });
        MAIN.postDelayed(() -> { if (g == barGen) hideBarNow(); }, 2200);
    }

    private static void end(Context app, String msg) {
        busy = false; cur = null;
        MAIN.post(ShortSaver::hideBarNow);
        if (app != null) toast(app, msg);
    }

    private static void toast(final Context app, final String s) { MAIN.post(() -> Toast.makeText(app, s, Toast.LENGTH_SHORT).show()); }

    // ---- TikTok-style "NN% Saving... / Cancel" bar ----
    private static void showBar(final Activity a) {
        MAIN.post(() -> {
            hideBarNow();
            try {
                ViewGroup content = a.findViewById(android.R.id.content);
                float d = a.getResources().getDisplayMetrics().density;
                FrameLayout root = new FrameLayout(a);
                root.setBackgroundColor(0xF2161616);
                root.setClickable(true);
                barTrack = new FrameLayout(a);
                barTrack.setBackgroundColor(0x33FFFFFF);
                barFill = new View(a);
                barFill.setBackgroundColor(Color.WHITE);
                barTrack.addView(barFill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
                root.addView(barTrack, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(2 * d), Gravity.TOP));
                barLabel = new TextView(a);
                barLabel.setTextColor(Color.WHITE);
                barLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                barLabel.setTypeface(Typeface.DEFAULT_BOLD);
                barLabel.setText("0%  Saving...");
                FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.START);
                ll.leftMargin = Math.round(20 * d);
                root.addView(barLabel, ll);
                barCancel = new TextView(a);
                barCancel.setTextColor(Color.WHITE);
                barCancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                barCancel.setTypeface(Typeface.DEFAULT_BOLD);
                barCancel.setText("Cancel");
                barCancel.setPadding(Math.round(14 * d), Math.round(12 * d), Math.round(14 * d), Math.round(12 * d));
                barCancel.setOnClickListener(v -> cancel());
                FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.END);
                cl.rightMargin = Math.round(8 * d);
                root.addView(barCancel, cl);
                content.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(52 * d), Gravity.BOTTOM));
                barParent = content; barRoot = root; barGen++;
            } catch (Throwable t) { }
        });
    }

    private static void hideBarNow() {
        try { if (barParent != null && barRoot != null) barParent.removeView(barRoot); } catch (Throwable t) { }
        barRoot = null; barParent = null; barFill = null; barTrack = null; barLabel = null; barCancel = null;
    }

    private static void render(final String label) {
        final int pct = Math.round(100f * (0.2f * pFetch + 0.8f * pExport));
        if (label == null && pct == lastPct) return;
        lastPct = pct;
        MAIN.post(() -> {
            if (barLabel != null) barLabel.setText(label != null ? label : (pct + "%  Saving..."));
            if (barTrack != null && barFill != null) {
                ViewGroup.LayoutParams lp = barFill.getLayoutParams();
                lp.width = Math.round(barTrack.getWidth() * pct / 100f);
                barFill.setLayoutParams(lp);
            }
        });
    }

    private static String tag(String u) {
        if (u == null) return "";
        u = u.trim();
        while (u.startsWith("@")) u = u.substring(1).trim();
        if (u.isEmpty()) return "";
        if (u.length() > 24) u = u.substring(0, 24);
        return "@" + u;
    }

    private static Bitmap decode(Context app, int res) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeResource(app.getResources(), res, o);
    }

    private static Uri existing(Context app, String id) {
        String s = app.getSharedPreferences("pp_short_saves", 0).getString("s_" + id, null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        if ("file".equals(u.getScheme())) return new File(u.getPath()).exists() ? u : null;
        Cursor c = null;
        try { c = app.getContentResolver().query(u, new String[]{"_id"}, null, null, null); return (c != null && c.moveToFirst()) ? u : null; }
        catch (Throwable t) { return null; }
        finally { if (c != null) c.close(); }
    }

    // Reads through the shorts cache: zero network when the clip is already fully cached.
    private static void fetch(Context app, String url, File dst) throws Exception {
        DataSource ds = NetFactory.getCached(app).createDataSource();
        byte[] buf = new byte[65536];
        FileOutputStream fo = new FileOutputStream(dst);
        try {
            long len = ds.open(new DataSpec(Uri.parse(url)));
            long got = 0;
            int n;
            while ((n = ds.read(buf, 0, buf.length)) != C.RESULT_END_OF_INPUT) {
                if (cancelled) throw new InterruptedException("canceled");
                fo.write(buf, 0, n);
                got += n;
                if (len > 0) { pFetch = Math.min(1f, (float) got / len); render(null); }
            }
            pFetch = 1f; render(null);
        } finally { try { ds.close(); } catch (Throwable t) { } fo.close(); }
    }

    private static int[] size(File f) throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            int w = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if ("90".equals(rot) || "270".equals(rot)) { int t = w; w = h; h = t; }
            return new int[]{w, h};
        } finally { r.release(); }
    }

    private static void copy(File f, OutputStream os) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) os.write(b, 0, n); }
        finally { in.close(); os.close(); }
    }

    private static Uri publish(Context app, File f, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = app.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Picha+");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri u = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            copy(f, cr.openOutputStream(u));
            v.clear();
            v.put(MediaStore.Video.Media.IS_PENDING, 0);
            cr.update(u, v, null, null);
            return u;
        }
        File dir;
        try { dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Picha+"); dir.mkdirs(); if (!dir.canWrite()) throw new Exception("ro"); }
        catch (Throwable t) { dir = app.getExternalFilesDir(Environment.DIRECTORY_MOVIES); }
        File dst = new File(dir, name);
        copy(f, new FileOutputStream(dst));
        MediaScannerConnection.scanFile(app, new String[]{dst.getAbsolutePath()}, new String[]{"video/mp4"}, null);
        return Uri.fromFile(dst);
    }

    /** Full-canvas transparent bitmaps, one per hop spot, so sizing is identical however Media3 scales overlays. */
    private static final class HopOverlay extends BitmapOverlay {
        private final int W, H;
        private final Bitmap mark, word;
        private final String name;
        private final Bitmap[] cache = new Bitmap[3];

        HopOverlay(int w, int h, Bitmap mark, Bitmap word, String name) { W = w; H = h; this.mark = mark; this.word = word; this.name = name; }

        @Override public Bitmap getBitmap(long presentationTimeUs) throws VideoFrameProcessingException {
            int k = (int) ((Math.max(0L, presentationTimeUs) / HOP_US) % 3);
            if (cache[k] == null) cache[k] = draw(k);
            return cache[k];
        }

        private Bitmap draw(int k) {
            Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            float mh = W * 0.075f;
            float mw = mh * mark.getWidth() / mark.getHeight();
            float ww = mh * word.getWidth() / word.getHeight();
            float gap = mh * 0.14f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            p.setAlpha(240);
            float ts = W * 0.036f;
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(Color.WHITE);
            tp.setTypeface(Typeface.DEFAULT_BOLD);
            tp.setTextSize(ts);
            tp.setShadowLayer(ts * 0.12f, 0f, ts * 0.06f, 0xB0000000);
            float nameW = name.isEmpty() ? 0f : tp.measureText(name);
            float boxW = Math.max(mw + gap + ww, nameW);
            float m = W * 0.045f;
            float left, top;
            if (k == 0) { left = m; top = H * 0.11f; }
            else if (k == 1) { left = m; top = H * 0.54f; }
            else { left = W - m - boxW; top = H * 0.77f; }
            c.drawBitmap(mark, null, new RectF(left, top, left + mw, top + mh), p);
            c.drawBitmap(word, null, new RectF(left + mw + gap, top, left + mw + gap + ww, top + mh), p);
            if (nameW > 0f) c.drawText(name, left, top + mh + ts * 1.2f, tp);
            return b;
        }
    }
}
