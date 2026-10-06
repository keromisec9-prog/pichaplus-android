package com.pichaplus.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

public class PlayerActivity extends Activity {
    private VideoView video;
    private long dlId;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        setContentView(root);

        dlId = getIntent().getLongExtra("id", -1);
        String uri = getIntent().getStringExtra("uri");
        if (uri == null) { finish(); return; }

        MediaController mc = new MediaController(this);
        mc.setAnchorView(video);
        video.setMediaController(mc);
        video.setVideoURI(Uri.parse(uri));

        final SharedPreferences sp = getSharedPreferences("picha_pos", MODE_PRIVATE);
        final int resume = sp.getInt("pos_" + dlId, 0);
        video.setOnPreparedListener(mp -> {
            if (resume > 0) video.seekTo(resume);
            video.start();
        });
        video.setOnCompletionListener(mp -> {
            sp.edit().remove("pos_" + dlId).apply();
            finish();
        });
        video.setOnErrorListener((mp, w, e) -> {
            Toast.makeText(this, "Can't play this file", Toast.LENGTH_LONG).show();
            finish();
            return true;
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (video != null && dlId >= 0) {
            getSharedPreferences("picha_pos", MODE_PRIVATE).edit()
                .putInt("pos_" + dlId, video.getCurrentPosition()).apply();
            video.pause();
        }
    }
}
