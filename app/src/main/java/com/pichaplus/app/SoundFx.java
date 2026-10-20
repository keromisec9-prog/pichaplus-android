package com.pichaplus.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

public final class SoundFx {
    private static SoundPool pool;
    private static int errId = 0;
    private static boolean errReady = false;
    private static long lastErr = 0;

    public static synchronized void init(Context c) {
        if (pool != null) return;
        try {
            AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
            pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(aa).build();
            pool.setOnLoadCompleteListener((sp, id, status) -> {
                if (id == errId && status == 0) errReady = true;
            });
            errId = pool.load(c.getApplicationContext(), R.raw.wrong_answer, 1);
        } catch (Exception ignore) {
        }
    }

    public static synchronized void play(String name) {
        try {
            if (pool == null || !"error".equals(name) || !errReady) return;
            long now = System.currentTimeMillis();
            if (now - lastErr < 400) return;
            lastErr = now;
            pool.play(errId, 1f, 1f, 1, 0, 1f);
        } catch (Exception ignore) {
        }
    }
}
