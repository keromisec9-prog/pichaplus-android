path = "app/src/main/java/com/pichaplus/app/MainActivity.java"
src = open(path, encoding="utf-8").read()

# 1. Add fields for fullscreen tracking
old_fields = """public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private CastContext castContext;
    private MediaRouteButton castButton;
    private static final String HOME_URL = "https://keromisec9-prog.github.io/picha-plus/";
    private static final String OFFLINE_URL = "file:///android_asset/offline.html";"""

new_fields = """public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private CastContext castContext;
    private MediaRouteButton castButton;
    private FrameLayout fullscreenContainer;
    private android.view.View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private static final String HOME_URL = "https://keromisec9-prog.github.io/picha-plus/";
    private static final String OFFLINE_URL = "file:///android_asset/offline.html";"""

# 2. Add fullscreenContainer to the root frame, right after castButton is added
old_frame_setup = """        castButton.setVisibility(android.view.View.GONE);
        frame.addView(castButton);

        setContentView(frame);"""

new_frame_setup = """        castButton.setVisibility(android.view.View.GONE);
        frame.addView(castButton);

        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        fullscreenContainer.setBackgroundColor(Color.BLACK);
        fullscreenContainer.setVisibility(android.view.View.GONE);
        frame.addView(fullscreenContainer);

        setContentView(frame);"""

# 3. Replace bare WebChromeClient with a real fullscreen-capable one
old_chrome = """        webView.setWebChromeClient(new WebChromeClient());"""

new_chrome = """        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(android.view.View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
                fullscreenContainer.setVisibility(android.view.View.VISIBLE);
                fullscreenContainer.bringToFront();
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                hideSystemUI();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                fullscreenContainer.removeView(customView);
                fullscreenContainer.setVisibility(android.view.View.GONE);
                customView = null;
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                showSystemUI();
            }
        });"""

# 4. Add hideSystemUI/showSystemUI helper methods, right before onBackPressed
old_backpressed = """    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}"""

new_backpressed = """    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void showSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            webView.getWebChromeClient().onHideCustomView();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}"""

changes = [
    ("class fields", old_fields, new_fields),
    ("fullscreen container setup", old_frame_setup, new_frame_setup),
    ("WebChromeClient fullscreen handling", old_chrome, new_chrome),
    ("system UI helpers + back press", old_backpressed, new_backpressed),
]

for label, old, new in changes:
    if old not in src:
        print(f"❌ {label}: pattern not found.")
    else:
        src = src.replace(old, new)
        print(f"✅ {label}: patched.")

open(path, "w", encoding="utf-8").write(src)
