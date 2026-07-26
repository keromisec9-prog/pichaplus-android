package com.pichaplus.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class MaintenanceActivity extends AppCompatActivity {

    private CountDownTimer timer;
    private final android.os.Handler pollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable pollRunnable = this::pollStatus;

    private TextView countDays, countHours, countMinutes, countSeconds;
    private LinearLayout countdownRow, statusBadgeRow;
    private TextView statusDot, statusLabel;
    private TextView lastCheckedView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maintenance);

        TextView titleView = findViewById(R.id.maintenanceTitle);
        TextView messageView = findViewById(R.id.maintenanceMessage);
        Button retryButton = findViewById(R.id.retryButton);
        lastCheckedView = findViewById(R.id.lastChecked);

        countdownRow = findViewById(R.id.countdownRow);
        countDays = findViewById(R.id.countDays);
        countHours = findViewById(R.id.countHours);
        countMinutes = findViewById(R.id.countMinutes);
        countSeconds = findViewById(R.id.countSeconds);

        statusBadgeRow = findViewById(R.id.statusBadgeRow);
        statusDot = findViewById(R.id.statusDot);
        statusLabel = findViewById(R.id.statusLabel);

        String title = getIntent().getStringExtra("title");
        String message = getIntent().getStringExtra("message");
        String endTime = getIntent().getStringExtra("endTime");
        String statusCode = getIntent().getStringExtra("statusCode");

        titleView.setText(title != null ? title : "Scheduled Maintenance");
        messageView.setText(message != null ? message : "We're upgrading our servers.");

        applyTimeOrStatus(endTime, statusCode);

        lastCheckedView.setText("Last checked: " +
            new SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(new Date()));

        retryButton.setOnClickListener(v -> recheckStatus());

        schedulePoll();
    }

    private void applyTimeOrStatus(String endTime, String statusCode) {
        long endMillis = parseIso8601(endTime);
        long remaining = endMillis > 0 ? endMillis - System.currentTimeMillis() : -1;

        if (remaining > 0) {
            countdownRow.setVisibility(View.VISIBLE);
            statusBadgeRow.setVisibility(View.GONE);
            startCountdown(remaining);
        } else {
            countdownRow.setVisibility(View.GONE);
            statusBadgeRow.setVisibility(View.VISIBLE);
            applyStatusBadge(statusCode);
        }
    }

    private void applyStatusBadge(String statusCode) {
        String code = statusCode != null ? statusCode : "investigating";
        String dot;
        String label;
        switch (code) {
            case "applying_fix":
                dot = "\uD83D\uDFE0"; label = "Applying a Fix"; break;
            case "verifying":
                dot = "\uD83D\uDD35"; label = "Verifying Systems"; break;
            case "almost_ready":
                dot = "\uD83D\uDFE2"; label = "Almost Ready"; break;
            case "investigating":
            default:
                dot = "\uD83D\uDFE1"; label = "Investigating"; break;
        }
        statusDot.setText(dot);
        statusLabel.setText(label);
    }

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

    private void startCountdown(long millis) {
        if (timer != null) timer.cancel();
        timer = new CountDownTimer(millis, 1000) {
            @Override
            public void onTick(long ms) {
                long d = ms / (1000 * 60 * 60 * 24);
                long h = (ms / (1000 * 60 * 60)) % 24;
                long m = (ms / (1000 * 60)) % 60;
                long s = (ms / 1000) % 60;
                countDays.setText(String.format(Locale.US, "%02d", d));
                countHours.setText(String.format(Locale.US, "%02d", h));
                countMinutes.setText(String.format(Locale.US, "%02d", m));
                countSeconds.setText(String.format(Locale.US, "%02d", s));
            }
            @Override
            public void onFinish() {
                recheckStatus();
            }
        }.start();
    }

    private void recheckStatus() {
        AppStatusChecker.fetch(status -> {
            if (!status.maintenance) {
                startActivity(new Intent(MaintenanceActivity.this, MainActivity.class));
                finish();
            } else {
                applyTimeOrStatus(status.endTime, status.statusCode);
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
