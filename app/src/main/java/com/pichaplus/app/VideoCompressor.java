package com.pichaplus.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import com.google.common.collect.ImmutableList;

import java.io.File;

@OptIn(markerClass = UnstableApi.class)
final class VideoCompressor {
    // Tune these three to trade size against quality
    static final int MAX_SHORT_SIDE = 720;      // compress if shorter side is above this
    static final int MAX_BITRATE = 2_500_000;   // ...or bitrate above this (bits/s)
    static final int TARGET_BITRATE = 1_800_000;
    static final double BITS_PER_PIXEL = 0.085;   // quality knob: higher = bigger, cleaner
    static final long MIN_BITRATE = 1_200_000;
    static final long MAX_TARGET_BITRATE = 3_000_000;

    interface Done { void onDone(Uri result); }

    static boolean isVideo(Activity a, Uri u) {
        String t = a.getContentResolver().getType(u);
        return t != null && t.startsWith("video/");
    }

    private static long num(MediaMetadataRetriever m, int key) {
        try { String s = m.extractMetadata(key); return s == null ? 0 : Long.parseLong(s); }
        catch (Exception e) { return 0; }
    }

    private static long sizeOf(Activity a, Uri u) {
        try (android.content.res.AssetFileDescriptor fd = a.getContentResolver().openAssetFileDescriptor(u, "r")) {
            return fd == null ? -1 : fd.getLength();
        } catch (Exception e) { return -1; }
    }

    static void process(final Activity act, final Uri in, final Done done) {
        long w, h, rot, br, dur;
        try {
            MediaMetadataRetriever m = new MediaMetadataRetriever();
            m.setDataSource(act, in);
            w = num(m, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            h = num(m, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            rot = num(m, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            br = num(m, MediaMetadataRetriever.METADATA_KEY_BITRATE);
            dur = num(m, MediaMetadataRetriever.METADATA_KEY_DURATION);
            m.release();
        } catch (Exception e) { done.onDone(in); return; }

        double fps = 30;
        try {
            MediaMetadataRetriever m2 = new MediaMetadataRetriever();
            m2.setDataSource(act, in);
            String fs = m2.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            String fc = m2.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
            m2.release();
            if (fs != null) fps = Double.parseDouble(fs);
            else if (fc != null && dur > 0) fps = Long.parseLong(fc) * 1000.0 / dur;
        } catch (Exception e) {}
        if (fps < 15 || fps > 60) fps = 30;

        long dw = (rot == 90 || rot == 270) ? h : w;
        long dh = (rot == 90 || rot == 270) ? w : h;
        if (dw <= 0 || dh <= 0) { done.onDone(in); return; }
        boolean landscape = dh <= dw;
        long shortSide = Math.min(dw, dh);
        boolean tooBig = shortSide > MAX_SHORT_SIDE;
        int th0 = landscape ? (int) Math.min(dh, MAX_SHORT_SIDE) : (int) Math.min(dh, 1280);
        double ow0 = dw * (double) th0 / dh;
        final long targetBr = Math.max(MIN_BITRATE, Math.min(MAX_TARGET_BITRATE, (long) (th0 * ow0 * fps * BITS_PER_PIXEL)));
        boolean tooHeavy = br > targetBr * 1.5;
        if (!tooBig && !tooHeavy) { done.onDone(in); return; }

        int targetH = landscape ? (int) Math.min(dh, MAX_SHORT_SIDE) : (int) Math.min(dh, 1280);

        File dir = act.getCacheDir();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (f.getName().startsWith("cmp_")) f.delete();
        long est = (long) (dur / 1000.0 * targetBr / 8.0 * 1.3);
        if (dir.getUsableSpace() < est) {
            Toast.makeText(act, "Not enough free space to optimise, uploading original", Toast.LENGTH_LONG).show();
            done.onDone(in);
            return;
        }
        final File out = new File(dir, "cmp_" + System.currentTimeMillis() + ".mp4");
        final long inSize = sizeOf(act, in);
        final boolean[] finished = {false};
        final Handler hd = new Handler(Looper.getMainLooper());
        final Transformer[] tr = new Transformer[1];

        final AlertDialog dlg = new AlertDialog.Builder(act)
            .setTitle("Optimising video")
            .setMessage("Starting... keep the app open")
            .setCancelable(false)
            .setNegativeButton("Skip", null)
            .create();
        dlg.show();
        act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final Runnable finish = new Runnable() { public void run() {
            finished[0] = true;
            try { dlg.dismiss(); } catch (Exception e) {}
            act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }};

        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                if (finished[0]) return;
                try { tr[0].cancel(); } catch (Exception e) {}
                finish.run(); out.delete(); done.onDone(in);
            }
        });

        tr[0] = new Transformer.Builder(act)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(new DefaultEncoderFactory.Builder(act)
                .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate((int) targetBr).build())
                .build())
            .addListener(new Transformer.Listener() {
                @Override public void onCompleted(Composition c, ExportResult r) {
                    if (finished[0]) return;
                    finish.run();
                    if (inSize > 0 && out.length() >= inSize * 0.9) {
                        out.delete();
                        done.onDone(in);
                    } else {
                        done.onDone(Uri.fromFile(out));
                    }
                }
                @Override public void onError(Composition c, ExportResult r, ExportException e) {
                    if (finished[0]) return;
                    finish.run(); out.delete();
                    Toast.makeText(act, "Couldn't optimise, uploading original", Toast.LENGTH_LONG).show();
                    done.onDone(in);
                }
            })
            .build();

        EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(in))
            .setEffects(new Effects(ImmutableList.of(), ImmutableList.of(Presentation.createForHeight(targetH))))
            .build();
        tr[0].start(item, out.getAbsolutePath());

        final ProgressHolder ph = new ProgressHolder();
        hd.post(new Runnable() { public void run() {
            if (finished[0]) return;
            if (tr[0].getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE)
                dlg.setMessage("Optimising... " + ph.progress + "%\nKeep the app open");
            hd.postDelayed(this, 500);
        }});
    }
}
