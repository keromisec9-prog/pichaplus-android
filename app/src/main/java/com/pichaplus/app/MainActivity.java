package com.pichaplus.app;

import android.app.DownloadManager;
import android.content.Context;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.mediarouter.app.MediaRouteButton;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private CastContext castContext;
    private MediaRouteButton castButton;
    private FrameLayout fullscreenContainer;
    private android.view.View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private static final String HOME_URL = "https://keromisec9-prog.github.io/picha-plus/";
    private static final String OFFLINE_URL = "file:///android_asset/offline.html";

    private boolean isConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    public class PichaJSBridge {
        @JavascriptInterface
        public void onSessionToken(String token) {
            if (token != null && !token.isEmpty() && !token.equals("null")) {
                PichaTokenManager.saveSession(MainActivity.this, token);
            }
        }

        @JavascriptInterface
        public void showCastButton(boolean show) {
            runOnUiThread(() -> {
                if (castButton != null) {
                    castButton.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
                }
            });
        }


    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.BLACK);

        try {
            castContext = CastContext.getSharedInstance(this);
        } catch (Exception e) {
            castContext = null;
        }

        FrameLayout frame = new FrameLayout(this);

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        webView.setBackgroundColor(Color.parseColor("#0f0f0f"));
        frame.addView(webView);

        // Cast button — top right, above the WebView
        castButton = new MediaRouteButton(this);
        CastButtonFactory.setUpMediaRouteButton(this, castButton);
        int size = (int) (40 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams castParams = new FrameLayout.LayoutParams(size, size);
        castParams.gravity = Gravity.TOP | Gravity.END;
        castParams.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
        castParams.rightMargin = (int) (12 * getResources().getDisplayMetrics().density);
        castButton.setLayoutParams(castParams);
        castButton.setTag("castBtn");
        castButton.setVisibility(android.view.View.GONE);
        frame.addView(castButton);

        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        fullscreenContainer.setBackgroundColor(Color.BLACK);
        fullscreenContainer.setVisibility(android.view.View.GONE);
        frame.addView(fullscreenContainer);

        setContentView(frame);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
        webView.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        settings.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
        );

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }

        webView.addJavascriptInterface(new PichaJSBridge(), "PichaApp");

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimeType,
                                        long contentLength) {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType("video/mp4");
                request.addRequestHeader("User-Agent", userAgent);
                request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                request.setDescription("Downloading via Picha+...");
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                );
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "picha_" + System.currentTimeMillis() + ".mp4"
                );
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                dm.enqueue(request);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // WhatsApp opens in app
                if (url.contains("wa.me") || url.startsWith("whatsapp://") ||
                    url.startsWith("fb://") || url.contains("facebook.com") ||
                    url.startsWith("snssdk") || url.startsWith("tiktok://") || url.contains("tiktok.com") ||
                    (!url.startsWith("http") && !url.startsWith("file"))) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {}
                    return true;
                }
                // Force downloads through DownloadManager instead of letting WebView
                // render video/mp4 URLs inline as a player.
                if (url.contains("/download?") || url.contains("/download-episode?")) {
                    DownloadManager.Request dmRequest = new DownloadManager.Request(Uri.parse(url));
                    dmRequest.setDescription("Downloading via Picha+...");
                    dmRequest.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    );
                    dmRequest.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        "picha_" + System.currentTimeMillis() + ".mp4"
                    );
                    dmRequest.setAllowedOverMetered(true);
                    dmRequest.setAllowedOverRoaming(true);
                    dmRequest.setAllowedNetworkTypes(
                        DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE
                    );
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    dm.enqueue(dmRequest);
                    android.widget.Toast.makeText(
                        MainActivity.this, "Download started", android.widget.Toast.LENGTH_SHORT
                    ).show();
                    return true;
                }
                // Google OAuth must open in external browser (WebView blocked by Google)
                // Check connectivity before loading anything
                if (!isConnected()) {
                    view.loadUrl(OFFLINE_URL);
                    return true;
                }
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(
                    "(function(){ return localStorage.getItem('pichaplus_session'); })()",
                    value -> {
                        if (value != null && !value.equals("null")) {
                            String token = value.replace("\"", "");
                            PichaTokenManager.saveSession(MainActivity.this, token);
                        }
                    }
                );
                // Only show cast button on main app page
                boolean isMainPage = url != null && url.contains("keromisec9-prog.github.io/picha-plus");
                if (castButton != null) {
                    castButton.setVisibility(isMainPage ? android.view.View.VISIBLE : android.view.View.GONE);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame() &&
                    request.getUrl().toString().contains("keromisec9-prog.github.io")) {
                    view.loadUrl(OFFLINE_URL);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
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
        });

        if (isConnected()) {
            webView.loadUrl(HOME_URL);
        } else {
            webView.loadUrl(OFFLINE_URL);
        }

        // Also intercept any navigation attempt when offline
        webView.setNetworkAvailable(isConnected());
    }

    private void hideSystemUI() {
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
}
