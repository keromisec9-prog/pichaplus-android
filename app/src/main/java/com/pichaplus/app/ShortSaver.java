package com.pichaplus.app;

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
import androidx.media3.transformer.Transformer;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** Saves a short to the gallery with the hopping MyPicha+ lockup, fully on device. */
final class ShortSaver {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean busy = false;
    private static Transformer cur;
    private static final long HOP_US = 3500000L;
    private static final float[][] SPOTS = {{0f, 0.07f}, {1f, 0.50f}, {0f, 0.80f}};
    private static final int CAP_H = 1280;

    static void save(final Context ctx, final String id, final String url, final String user) {
        final Context app = ctx.getApplicationContext();
        if (busy) { toast(app, "Still saving the last video"); return; }
        try { if (existing(app, id) != null) { toast(app, "Already saved in Movies > Picha+"); return; } } catch (Throwable t) { }
        busy = true;
        toast(app, "Preparing video...");
        new Thread(() -> {
            File src = new File(app.getCacheDir(), "dl_src.mp4");
            try {
                fetch(app, url, src);
                int[] wh = size(src);
                int w = wh[0], h = wh[1];
                boolean scale = h > CAP_H;
                int oh = scale ? CAP_H : h;
                int ow = scale ? Math.round(w * (float) CAP_H / h) : w;
                start(app, src, ow, oh, scale, id, user);
            } catch (Throwable t) { src.delete(); end(app, "Could not download this video"); }
        }, "short-save").start();
    }

    private static void start(final Context app, final File src, final int ow, final int oh, final boolean scale, final String id, final String user) {
        MAIN.post(() -> {
            final File out = new File(app.getCacheDir(), "dl_out.mp4");
            try {
                out.delete();
                Bitmap mark = decode(app, R.drawable.wm_mark), word = decode(app, R.drawable.wm_word);
                if (mark == null || word == null) throw new IllegalStateException("logo");
                ImmutableList.Builder<Effect> fx = ImmutableList.builder();
                if (scale) fx.add(Presentation.createForHeight(oh));
                fx.add(new OverlayEffect(ImmutableList.<TextureOverlay>of(new HopOverlay(ow, oh, mark, word, tag(user)))));
                EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src)))
                    .setEffects(new Effects(ImmutableList.of(), fx.build())).build();
                cur = new Transformer.Builder(app).addListener(new Transformer.Listener() {
                    @Override public void onCompleted(Composition c, ExportResult r) { finish(app, src, out, id); }
                    @Override public void onError(Composition c, ExportResult r, ExportException e) { src.delete(); out.delete(); end(app, "Could not add the watermark"); }
                }).build();
                cur.start(item, out.getAbsolutePath());
            } catch (Throwable t) { src.delete(); end(app, "Could not add the watermark"); }
        });
    }

    private static void finish(final Context app, final File src, final File out, final String id) {
        new Thread(() -> {
            try {
                Uri u = publish(app, out, "MyPicha_" + (System.currentTimeMillis() / 1000) + ".mp4");
                app.getSharedPreferences("pp_short_saves", 0).edit().putString("s_" + id, u.toString()).apply();
                src.delete(); out.delete();
                end(app, "Saved to Movies > Picha+");
            } catch (Throwable t) { src.delete(); out.delete(); end(app, "Could not save the video"); }
        }, "short-save-pub").start();
    }

    private static void end(Context app, String msg) { busy = false; cur = null; toast(app, msg); }
    private static void toast(final Context app, final String s) { MAIN.post(() -> Toast.makeText(app, s, Toast.LENGTH_SHORT).show()); }

    private static String tag(String u) {
        if (u == null) return "";
        u = u.trim();
        if (u.isEmpty()) return "";
        if (u.length() > 24) u = u.substring(0, 24);
        return u.startsWith("@") ? u : "@" + u;
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
            ds.open(new DataSpec(Uri.parse(url)));
            int n;
            while ((n = ds.read(buf, 0, buf.length)) != C.RESULT_END_OF_INPUT) fo.write(buf, 0, n);
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

    /** Full-frame transparent bitmaps, one per hop spot, so sizing is identical however Media3 scales overlays. */
    private static final class HopOverlay extends BitmapOverlay {
        private final int W, H;
        private final Bitmap mark, word;
        private final String name;
        private final Bitmap[] cache = new Bitmap[SPOTS.length];

        HopOverlay(int w, int h, Bitmap mark, Bitmap word, String name) { W = w; H = h; this.mark = mark; this.word = word; this.name = name; }

        @Override public Bitmap getBitmap(long presentationTimeUs) throws VideoFrameProcessingException {
            int k = (int) ((Math.max(0L, presentationTimeUs) / HOP_US) % SPOTS.length);
            if (cache[k] == null) cache[k] = draw(k);
            return cache[k];
        }

        private Bitmap draw(int k) {
            Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            float mh = H * 0.045f;
            float mw = mh * mark.getWidth() / mark.getHeight();
            float ww = mh * word.getWidth() / word.getHeight();
            float gap = mh * 0.12f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            p.setAlpha(235);
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(Color.WHITE);
            tp.setAlpha(235);
            tp.setTypeface(Typeface.DEFAULT_BOLD);
            tp.setTextSize(mh * 0.36f);
            tp.setShadowLayer(mh * 0.08f, 0f, mh * 0.04f, 0xAA000000);
            float tw = name.isEmpty() ? 0f : tp.measureText(name);
            float boxW = Math.max(mw + gap + ww, tw);
            float boxH = mh + (name.isEmpty() ? 0f : mh * 0.46f);
            float m = W * 0.04f;
            float left = m + SPOTS[k][0] * Math.max(0f, W - boxW - 2 * m);
            float top = m + SPOTS[k][1] * Math.max(0f, H - boxH - 2 * m);
            c.drawBitmap(mark, null, new RectF(left, top, left + mw, top + mh), p);
            c.drawBitmap(word, null, new RectF(left + mw + gap, top, left + mw + gap + ww, top + mh), p);
            if (tw > 0f) c.drawText(name, left, top + mh + mh * 0.38f, tp);
            return b;
        }
    }
}
