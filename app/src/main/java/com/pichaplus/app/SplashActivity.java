package com.pichaplus.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class SplashActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The animated splash now lives inside MainActivity so it can stay up until the app has painted.
        startActivity(new Intent(SplashActivity.this, MainActivity.class));
        overridePendingTransition(0, 0);
        finish();
    }
}
