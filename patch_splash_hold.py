import re
base = "app/src/main/"

# ---------- 1) SplashActivity: hand off immediately ----------
open(base + "java/com/pichaplus/app/SplashActivity.java", "w", encoding="utf-8").write('''package com.pichaplus.app;

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
''')

# ---------- 2) splash.html: add the dots ----------
p = base + "assets/splash.html"
s = open(p, encoding="utf-8").read()
if "pp-dots" not in s:
    css = '''
  .pp-dots{position:fixed;left:50%;top:calc(50% + 108px);transform:translateX(-50%);display:flex;gap:10px;opacity:0;animation:ppDotsIn .5s ease-out .3s forwards}
  .pp-dots i{width:8px;height:8px;border-radius:50%;background:rgba(255,255,255,.22);animation:ppDot 1.6s ease-in-out infinite}
  .pp-dots i:nth-child(2){animation-delay:.16s}.pp-dots i:nth-child(3){animation-delay:.32s}.pp-dots i:nth-child(4){animation-delay:.48s}.pp-dots i:nth-child(5){animation-delay:.64s}
  @keyframes ppDotsIn{to{opacity:1}}
  @keyframes ppDot{0%,8%{background:rgba(255,255,255,.22)}22%,68%{background:rgba(255,255,255,.92)}88%,100%{background:rgba(255,255,255,.22)}}
'''
    assert "</style>" in s and "</body>" in s, "splash.html: no </style> or </body>"
    s = s.replace("</style>", css + "</style>", 1)
    i = s.rfind("</body>")
    s = s[:i] + '<div class="pp-dots"><i></i><i></i><i></i><i></i><i></i></div>\n' + s[i:]
    open(p, "w", encoding="utf-8").write(s)

# ---------- 3) MainActivity ----------
p = base + "java/com/pichaplus/app/MainActivity.java"
s = open(p, encoding="utf-8").read()

overlay = '''frame.addView(fullscreenContainer);

        // --- splash overlay: stays up until the web app has painted ---
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF0F0F0F));
        ppSplashWv = new android.webkit.WebView(this);
        ppSplashWv.setBackgroundColor(0xFF0F0F0F);
        ppSplashWv.getSettings().setJavaScriptEnabled(true);
        ppSplashWv.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ppSplashWv.setOnTouchListener((ppV, ppE) -> true);
        ppSplashWv.loadUrl("file:///android_asset/splash.html");
        frame.addView(ppSplashWv);

        ppSlowPill = new android.widget.TextView(this);
        ppSlowPill.setText("Slow connection");
        ppSlowPill.setTextColor(0xFFD9D9D9);
        ppSlowPill.setTextSize(12.5f);
        ppSlowPill.setTypeface(null, android.graphics.Typeface.BOLD);
        ppSlowPill.setPadding(ppDp(14), ppDp(6), ppDp(14), ppDp(6));
        android.graphics.drawable.GradientDrawable ppG = new android.graphics.drawable.GradientDrawable();
        ppG.setColor(0xF51C1C1C);
        ppG.setCornerRadius(ppDp(6));
        ppG.setStroke(1, 0x1AFFFFFF);
        ppSlowPill.setBackground(ppG);
        ppSlowPill.setAlpha(0f);
        ppSlowPill.setVisibility(android.view.View.GONE);
        FrameLayout.LayoutParams ppLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        ppLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        ppLp.bottomMargin = ppDp(88);
        frame.addView(ppSlowPill, ppLp);
        ppSplashStart();'''

methods = '''private android.webkit.WebView ppSplashWv;
    private android.widget.TextView ppSlowPill;
    private boolean ppSplashGone = false, ppReady = false, ppHomeLoaded = false;
    private long ppSplashT0 = 0;
    private final android.os.Handler ppH = new android.os.Handler(android.os.Looper.getMainLooper());

    private int ppDp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private boolean ppIsSlow() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 23) return false;
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            android.net.NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            if (c != null) {
                int kbps = c.getLinkDownstreamBandwidthKbps();
                if (kbps > 0 && kbps < 1500) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void ppShowSlow() {
        if (ppSlowPill == null) return;
        ppSlowPill.animate().cancel();
        ppSlowPill.setVisibility(android.view.View.VISIBLE);
        ppSlowPill.setAlpha(0f);
        ppSlowPill.animate().alpha(1f).setDuration(200).start();
        ppH.postDelayed(() -> {
            if (ppSlowPill == null) return;
            ppSlowPill.animate().alpha(0f).setDuration(250)
                .withEndAction(() -> { if (ppSlowPill != null) ppSlowPill.setVisibility(android.view.View.GONE); }).start();
        }, 3200);
    }

    private void ppLoadHome() {
        if (ppHomeLoaded || isFinishing()) return;
        ppHomeLoaded = true;
        webView.loadUrl(HOME_URL);
    }

    private void ppSplashStart() {
        ppSplashT0 = android.os.SystemClock.uptimeMillis();
        if (ppIsSlow()) ppShowSlow();
        ppH.postDelayed(() -> { if (!ppSplashGone && !ppReady) ppShowSlow(); }, 1500);
        ppH.postDelayed(() -> { if (isConnected()) ppLoadHome(); }, 3000);
        ppH.postDelayed(this::ppPoll, 300);
    }

    private void ppPoll() {
        if (ppSplashGone || isFinishing()) return;
        long age = android.os.SystemClock.uptimeMillis() - ppSplashT0;
        if (age >= 10000) { ppHideSplash(); return; }
        String u = webView != null ? webView.getUrl() : null;
        if (u != null && u.startsWith(OFFLINE_URL)) ppReady = true;
        if (!ppReady && u != null && webView != null) {
            webView.evaluateJavascript(
                "(function(){try{return !document.getElementById('app-splash')&&!!document.querySelector('.skeleton-card,.card')}catch(e){return false}})()",
                ppVal -> { if ("true".equals(ppVal)) ppReady = true; });
        }
        if (ppReady && age >= 2800) { ppHideSplash(); return; }
        ppH.postDelayed(this::ppPoll, 150);
    }

    private void ppHideSplash() {
        if (ppSplashGone) return;
        ppSplashGone = true;
        final android.webkit.WebView w = ppSplashWv;
        if (w == null) return;
        w.animate().alpha(0f).setDuration(350).withEndAction(() -> {
            try { ((android.view.ViewGroup) w.getParent()).removeView(w); w.destroy(); } catch (Throwable ignored) {}
            ppSplashWv = null;
        }).start();
    }

    private volatile boolean pp_wantKb = false;'''

reps = [
    ("frame.addView(fullscreenContainer);", overlay),
    ("webView.loadUrl(HOME_URL);", "ppLoadHome();"),
    ("private volatile boolean pp_wantKb = false;", methods),
]
for a, b in reps:
    assert s.count(a) == 1, "match count %d for: %s" % (s.count(a), a)
    s = s.replace(a, b)
open(p, "w", encoding="utf-8").write(s)
print("patched")
