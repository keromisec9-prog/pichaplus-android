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
                cur = new Transformer.Builder(app).setEncoderFactory(new androidx.media3.transformer.DefaultEncoderFactory.Builder(app).setRequestedVideoEncoderSettings(new androidx.media3.transformer.VideoEncoderSettings.Builder().setBitrate(6_000_000).build()).build()).addListener(new Transformer.Listener() {
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

    private static final class WmAssets {
        static final String[] Y = {"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"};
        static final String[] P = {"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"};
        static final String[] WORD = {"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"};
    }

    /** Animated MyPicha+ watermark: plays once from video start (fade in, M/y slide out, wordmark in, hold, retract), then stays MP + @name. */
    private static final class HopOverlay extends BitmapOverlay {
        private static final float FPS = 30f;
        private final int W, H;
        private final String name;
        private final float s, boxW, boxH, back, nameW, fs, ny0, ny1;
        private final Bitmap pB, wB, yB;
        private final Bitmap[] stat = new Bitmap[9];
        private Bitmap anim;
        private int animSpot = -1, animKey = -1;
        private final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint yp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path mPath = new android.graphics.Path();
        private final android.graphics.Shader hClean, hEdge, vOver;

        HopOverlay(int w, int h, Bitmap mark, Bitmap word, String name) {
            W = w; H = h; this.name = name == null ? "" : name;
            boxW = w * 0.26f; s = boxW / 299f; boxH = 87f * s;
            float hs = HOP_US / 1000000f;
            back = Math.max(3.9f, Math.min(5.5f, hs - 0.1f));
            pB = fit(b64(WmAssets.P), Math.round(130f * s), Math.round(130f * s));
            wB = fit(b64(WmAssets.WORD), Math.round(111f * s), Math.round(74f * s));
            yB = fit(b64(WmAssets.Y), Math.max(2, Math.round(17f * s)), Math.max(2, Math.round(16f * s)));
            yp.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFF5F5F5, android.graphics.PorterDuff.Mode.SRC_IN));
            tp.setColor(Color.WHITE);
            tp.setTypeface(Typeface.DEFAULT_BOLD);
            tp.setTextSize(20f);
            float w0 = this.name.isEmpty() ? 0f : tp.measureText(this.name);
            fs = (w0 > 102f) ? 20f * 102f / w0 : 20f;
            tp.setTextSize(fs);
            tp.setShadowLayer(fs * 0.12f, 0f, fs * 0.06f, 0xB0000000);
            nameW = this.name.isEmpty() ? 0f : tp.measureText(this.name);
            ny0 = 65f + fs * 0.36f;
            ny1 = 74f + 5f + fs * 0.72f;
            float sx = 84.6f / 602f, sy = 73f / 517f;
            float[] pts = {277,309, 431,309, 578,497, 725,309, 879,309, 879,826, 726,826, 726,570, 578,755, 436,570, 436,826, 277,826};
            for (int i = 0; i < pts.length; i += 2) {
                float px = (pts[i] - 277f) * sx, py = (pts[i + 1] - 309f) * sy;
                if (i == 0) mPath.moveTo(px, py); else mPath.lineTo(px, py);
            }
            mPath.close();
            hClean = new android.graphics.LinearGradient(0f, 0f, 84.6f, 0f,
                new int[]{0xFFE6000F, 0xFFE4000F, 0xFFF5111A, 0xFFFA1A18, 0xFFE8061A},
                new float[]{0f, 0.256f, 0.404f, 0.575f, 1f}, android.graphics.Shader.TileMode.CLAMP);
            hEdge = new android.graphics.LinearGradient(0f, 0f, 84.6f, 0f,
                new int[]{0xFFE6000F, 0xFFE4000F, 0xFFF5111A, 0xFFFA1A18, 0xFFB80008, 0xFF8A0006, 0xFF8A0006},
                new float[]{0f, 0.256f, 0.404f, 0.575f, 0.70f, 0.777f, 1f}, android.graphics.Shader.TileMode.CLAMP);
            vOver = new android.graphics.LinearGradient(0f, 0f, 0f, 73f, 0x00400000, 0x38400000,
                android.graphics.Shader.TileMode.CLAMP);
        }

        private static Bitmap b64(String[] parts) {
            StringBuilder sb = new StringBuilder();
            for (String p : parts) sb.append(p);
            byte[] d = android.util.Base64.decode(sb.toString(), android.util.Base64.DEFAULT);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(d, 0, d.length, o);
        }

        private static Bitmap fit(Bitmap src, int tw, int th) {
            Bitmap b = src;
            while (b.getWidth() > tw * 2 && b.getHeight() > th * 2) {
                b = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            }
            return Bitmap.createScaledBitmap(b, tw, th, true);
        }

        private static float bz(float x1, float y1, float x2, float y2, float t) {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            float lo = 0f, hi = 1f, u = t;
            for (int i = 0; i < 24; i++) {
                u = (lo + hi) / 2f;
                float x = 3f * (1 - u) * (1 - u) * u * x1 + 3f * (1 - u) * u * u * x2 + u * u * u;
                if (x < t) lo = u; else hi = u;
            }
            return 3f * (1 - u) * (1 - u) * u * y1 + 3f * (1 - u) * u * u * y2 + u * u * u;
        }

        private static void tf(Canvas c, float p) {
            c.translate(65f, 65f);
            c.skew(0f, (float) Math.tan(Math.toRadians(-4f * (1f - p))));
            float sc = 0.97f + 0.03f * p;
            c.scale(sc, sc);
            c.translate(0f, 4f * (1f - p));
            c.translate(-65f, -65f);
        }

        @Override public Bitmap getBitmap(long presentationTimeUs) throws VideoFrameProcessingException {
            long t = Math.max(0L, presentationTimeUs);
            int k = (int) ((t / HOP_US) % 3);
            float lt = t / 1000000f + 0.9f;
            int key;
            if (lt >= back + 0.7f) key = 2;
            else if (lt >= 2.65f && lt < back) key = 1;
            else key = 3 + (int) (lt * FPS);
            if (key < 3) {
                int i = k * 3 + key;
                if (stat[i] == null) stat[i] = draw(k, key == 1 ? 2.65f : back + 0.7f);
                return stat[i];
            }
            if (anim == null || animSpot != k || animKey != key) {
                anim = draw(k, (key - 3) / FPS);
                animSpot = k; animKey = key;
            }
            return anim;
        }

        private Bitmap draw(int spot, float tt) {
            Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            float m = W * 0.045f;
            float left, top;
            if (spot == 0) { left = m; top = H * 0.11f; }
            else if (spot == 1) { left = m; top = H * 0.54f; }
            else { left = W - m - boxW; top = H * 0.77f; }

            float fill = tt < 0.9f ? 0f : bz(0f, 0f, 0.58f, 1f, (tt - 0.9f) / 0.5f);
            float sp = tt < 1.55f ? 0f : bz(0.16f, 1f, 0.3f, 1f, (tt - 1.55f) / 0.7f);
            float mS = 0f, yS = 0f, edge;
            if (tt >= 1.95f && tt < back) {
                float q = bz(0.16f, 1f, 0.3f, 1f, (tt - 1.95f) / 0.7f);
                mS = 47.2f * q; yS = 49.3f * q;
            } else if (tt >= back) {
                float q = bz(0.65f, 0f, 0.35f, 1f, (tt - back) / 0.7f);
                mS = 47.2f * (1f - q); yS = 49.3f * (1f - q);
            }
            if (tt < 1.95f) edge = 1f;
            else if (tt < back) edge = 1f - bz(0f, 0f, 0.58f, 1f, (tt - 1.95f) / 0.7f);
            else edge = bz(0.42f, 0f, 1f, 1f, (tt - back) / 0.7f);

            c.save();
            c.translate(left, top);
            c.scale(s, s);
            c.translate(74.6f, -21.8f);

            if (yS > 0.01f) {
                c.save();
                c.clipRect(-130f, 0f, 40f, 130f);
                c.drawBitmap(yB, null, new RectF(64.8f - yS, 80.8f, 64.8f - yS + 17f, 80.8f + 16f), yp);
                c.restore();
            }

            if (fill > 0.001f) {
                c.save();
                tf(c, sp);
                android.graphics.Path cp = new android.graphics.Path();
                cp.addRect(-130f, -40f, 36.6f, 170f, android.graphics.Path.Direction.CW);
                c.clipPath(cp);
                int lay = c.saveLayerAlpha(null, Math.round(fill * 255f));
                c.save();
                c.translate(-25.4f - mS, 23.8f);
                fp.setShader(hClean); c.drawPath(mPath, fp);
                fp.setShader(vOver); c.drawPath(mPath, fp);
                if (edge > 0.003f) {
                    c.saveLayerAlpha(null, Math.round(edge * 255f));
                    fp.setShader(hEdge); c.drawPath(mPath, fp);
                    fp.setShader(vOver); c.drawPath(mPath, fp);
                    c.restore();
                }
                c.restore();
                c.restoreToCount(lay);
                c.restore();

                c.save();
                tf(c, sp);
                bp.setAlpha(Math.round(fill * 255f));
                c.drawBitmap(pB, null, new RectF(0f, 0f, 130f, 130f), bp);
                c.restore();
            }

            float wa, wtx;
            if (tt < 1.95f) { wa = 0f; wtx = -16f; }
            else if (tt < back) { float u = bz(0.16f, 1f, 0.3f, 1f, (tt - 1.95f) / 0.6f); wa = u; wtx = -16f * (1f - u); }
            else { float v = bz(0.65f, 0f, 0.35f, 1f, (tt - back) / 0.6f); wa = 1f - v; wtx = -16f * v; }
            if (wa > 0.003f) {
                bp.setAlpha(Math.round(wa * 255f));
                c.drawBitmap(wB, null, new RectF(111f + wtx, 28f, 111f + wtx + 111f, 28f + 74f), bp);
            }

            if (nameW > 0f && fill > 0.003f) {
                float q = mS / 47.2f;
                tp.setAlpha(Math.round(fill * 255f));
                c.drawText(name, 105f + 14f * q, ny0 + (ny1 - ny0) * q, tp);
            }
            c.restore();
            return b;
        }
    }
}
