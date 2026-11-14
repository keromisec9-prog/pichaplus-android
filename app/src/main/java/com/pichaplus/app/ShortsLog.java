package com.pichaplus.app;

import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.Log;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class ShortsLog {
    private static final ArrayDeque<String> L = new ArrayDeque<>();
    private static final long T0 = SystemClock.elapsedRealtime();
    private static TextView tv;
    private static ScrollView sv;
    private static View panel;
    private static boolean installed, pending;
    private static String lastSnap = "";
    private static long lastDue;

    public static synchronized void d(int idx, String m) {
        String s = String.format(Locale.US, "%6d %s %s", SystemClock.elapsedRealtime() - T0, idx < 0 ? "PGR" : "#" + idx, m);
        L.addLast(s);
        while (L.size() > 3000) L.removeFirst();
        Log.d("ShortsLog", s);
        refresh();
    }

    public static void snap(String s) {
        if (s.equals(lastSnap)) return;
        lastSnap = s;
        d(-1, s);
    }

    public static boolean due() {
        long n = SystemClock.elapsedRealtime();
        if (n - lastDue < 1000) return false;
        lastDue = n;
        return true;
    }

    private static synchronized String dump() {
        StringBuilder b = new StringBuilder();
        for (String x : L) b.append(x).append('\n');
        return b.toString();
    }

    private static void refresh() {
        if (tv == null || panel == null || panel.getVisibility() != View.VISIBLE || pending) return;
        pending = true;
        tv.postDelayed(new Runnable() {
            @Override public void run() {
                pending = false;
                tv.setText(dump());
                sv.fullScroll(View.FOCUS_DOWN);
            }
        }, 300);
    }

    private static Button btn(final Context c, String label, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextSize(11);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0xFF333333);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 110, 1f);
        lp.setMargins(4, 4, 4, 4);
        b.setLayoutParams(lp);
        return b;
    }

    public static void install(Object o) {
        if (installed || !(o instanceof View)) return;
        View v = (View) o;
        if (!v.isAttachedToWindow()) return;
        View r = v.getRootView();
        if (!(r instanceof ViewGroup)) return;
        installed = true;
        final ViewGroup root = (ViewGroup) r;
        final Context c = v.getContext();
        int h = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.55f);

        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(0xEE000000);
        p.setClickable(true);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(btn(c, "COPY", new View.OnClickListener() {
            @Override public void onClick(View x) {
                ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("shorts log", dump()));
                Toast.makeText(c, "Copied " + L.size() + " lines", Toast.LENGTH_SHORT).show();
            }
        }));
        row.addView(btn(c, "CLEAR", new View.OnClickListener() {
            @Override public void onClick(View x) {
                synchronized (ShortsLog.class) { L.clear(); }
                tv.setText("");
            }
        }));
        row.addView(btn(c, "HIDE", new View.OnClickListener() {
            @Override public void onClick(View x) { panel.setVisibility(View.GONE); }
        }));
        p.addView(row);
        sv = new ScrollView(c);
        tv = new TextView(c);
        tv.setTextSize(9);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(0xFF00FF88);
        tv.setTextIsSelectable(true);
        sv.addView(tv);
        p.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        panel = p;
        p.setVisibility(View.GONE);
        root.addView(p, new FrameLayout.LayoutParams(-1, h, Gravity.BOTTOM));

        Button t = new Button(c);
        t.setText("LOG");
        t.setTextSize(10);
        t.setTextColor(Color.WHITE);
        t.setBackgroundColor(0xAA000000);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                panel.setVisibility(View.VISIBLE);
                tv.setText(dump());
                sv.post(new Runnable() { @Override public void run() { sv.fullScroll(View.FOCUS_DOWN); } });
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(150, 90, Gravity.START | Gravity.CENTER_VERTICAL);
        root.addView(t, lp);
        d(-1, "logger installed");
    }
}
