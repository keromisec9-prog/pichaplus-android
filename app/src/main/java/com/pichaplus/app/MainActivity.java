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
        // Real system WebView UA used (required for Google OAuth)

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
                if (url.contains("wa.me") || url.startsWith("whatsapp://")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {}
                    return true;
                }
                // Google OAuth must open in external browser (WebView blocked by Google)
                if (url.contains("accounts.google.com")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, request.getUrl());
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {}
                    return true;
                }
                // Everything else loads inside WebView
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

        webView.setWebChromeClient(new WebChromeClient());

        if (isConnected()) {
            webView.loadUrl(HOME_URL);
        } else {
            webView.loadUrl(OFFLINE_URL);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
