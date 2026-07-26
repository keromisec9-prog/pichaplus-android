package com.pichaplus.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class MaintenanceActivity extends AppCompatActivity {

    private CountDownTimer timer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maintenance);

        TextView titleView = findViewById(R.id.maintenanceTitle);
        TextView messageView = findViewById(R.id.maintenanceMessage);
        TextView countdownView = findViewById(R.id.countdownText);
        TextView lastCheckedView = findViewById(R.id.lastChecked);
        Button retryButton = findViewById(R.id.retryButton);

        String title = getIntent().getStringExtra("title");
        String message = getIntent().getStringExtra("message");
        String endTime = getIntent().getStringExtra("endTime");

        titleView.setText(title != null ? title : "Scheduled Maintenance");
        messageView.setText(message != null ? message : "We're upgrading our servers.");

        long endMillis = parseIso8601(endTime);
        if (endMillis <= 0) {
            countdownView.setText("Estimated completion: To be announced");
        } else {
            long remaining = endMillis - System.currentTimeMillis();
            if (remaining > 0) {
                startCountdown(countdownView, remaining);
            } else {
                countdownView.setText("Estimated completion: To be announced");
            }
        }

        lastCheckedView.setText("Last checked: " +
            new SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(new Date()));

        retryButton.setOnClickListener(v -> recheckStatus());

        schedulePoll();
    }

    private final android.os.Handler pollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable pollRunnable = this::pollStatus;

    private void schedulePoll() {
        pollHandler.postDelayed(pollRunnable, 20000);
    }

    private void pollStatus() {
        AppStatusChecker.fetch(status -> {
            if (!status.maintenance) {
                startActivity(new Intent(MaintenanceActivity.this, MainActivity.class));
                finish();
            } else {
                schedulePoll();
            }
        });
    }

    private long parseIso8601(String iso) {
        if (iso == null || iso.isEmpty() || iso.equals("null")) return -1;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return sdf.parse(iso).getTime();
        } catch (Exception e) {
            try {
                SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                sdf2.setTimeZone(TimeZone.getTimeZone("UTC"));
                return sdf2.parse(iso).getTime();
            } catch (Exception e2) {
                return -1;
            }
        }
    }

    private void startCountdown(TextView view, long millis) {
        if (timer != null) timer.cancel();
        timer = new CountDownTimer(millis, 1000) {
            @Override
            public void onTick(long ms) {
                long d = ms / (1000 * 60 * 60 * 24);
                long h = (ms / (1000 * 60 * 60)) % 24;
                long m = (ms / (1000 * 60)) % 60;
                long s = (ms / 1000) % 60;
                if (d > 0) {
                    view.setText(String.format(Locale.US, "%dd %02d:%02d:%02d", d, h, m, s));
                } else {
                    view.setText(String.format(Locale.US, "%02d:%02d:%02d", h, m, s));
                }
            }
            @Override
            public void onFinish() {
                recheckStatus();
            }
        }.start();
    }

    private void recheckStatus() {
        TextView lastCheckedView = findViewById(R.id.lastChecked);
        AppStatusChecker.fetch(status -> {
            if (!status.maintenance) {
                startActivity(new Intent(MaintenanceActivity.this, MainActivity.class));
                finish();
            } else {
                lastCheckedView.setText("Last checked: " +
                    new SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(new Date()));
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (timer != null) timer.cancel();
        pollHandler.removeCallbacks(pollRunnable);
        super.onDestroy();
    }
}
