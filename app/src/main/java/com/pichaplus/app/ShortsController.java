package com.pichaplus.app;

import android.app.Activity;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;

public class ShortsController {
    private final Activity act;
    private final WebView web;
    private NativeShortsView view;

    public ShortsController(Activity a, WebView w) { act = a; web = w; }

    private void js(final String code) { web.post(() -> web.evaluateJavascript(code, null)); }
    private void fail() { js("window._mpfNatFail&&window._mpfNatFail()"); }

    @JavascriptInterface public boolean available() { return true; }
    @JavascriptInterface public boolean startSupported() { return true; }

    @JavascriptInterface public void open(final String json) {
        act.runOnUiThread(() -> {
            try {
                if (view == null) {
                    view = new NativeShortsView(act, new NativeShortsView.Host() {
                        @Override public void js(String c) { ShortsController.this.js(c); }
                    });
                    ((FrameLayout) web.getParent()).addView(view, new FrameLayout.LayoutParams(1, 1));
                }
                view.open(new JSONObject(json));
            } catch (Throwable t) { fail(); }
        });
    }

    @JavascriptInterface public void setUrl(final int idx, final String id, final String url) {
        act.runOnUiThread(() -> { try { if (view != null) view.setUrl(idx, id, url); } catch (Throwable t) { } });
    }

    @JavascriptInterface public void snap(final String json) {
        act.runOnUiThread(() -> { try { if (view != null) view.snap(new JSONArray(json)); } catch (Throwable t) { } });
    }

    @JavascriptInterface public void list(final String json) {
        act.runOnUiThread(() -> { try { if (view != null) view.setItems(new JSONArray(json)); } catch (Throwable t) { } });
    }

    @JavascriptInterface public void state(final String json) {
        act.runOnUiThread(() -> { try { if (view != null) view.applyState(new JSONObject(json)); } catch (Throwable t) { } });
    }

    @JavascriptInterface public boolean canSave(final int idx) {
        try { NativeShortsView v = view; return v != null && v.canSave(idx); } catch (Throwable t) { return false; }
    }

    @JavascriptInterface public boolean saveShort(final int idx) {
        final NativeShortsView v = view;
        if (v == null) return false;
        act.runOnUiThread(() -> { try { v.saveShort(idx); } catch (Throwable t) { } });
        return true;
    }

    @JavascriptInterface public void close() {
        act.runOnUiThread(() -> {
            if (view == null) return;
            try {
                view.close();
                ViewGroup p = (ViewGroup) view.getParent();
                if (p != null) p.removeView(view);
            } catch (Throwable t) { }
            view = null;
        });
    }

    public boolean handleBack() {
        NativeShortsView v = view;
        if (v != null && v.isOverlayShown()) { js("window._mpfNatAct&&window._mpfNatAct('back',0)"); return true; }
        return false;
    }

    public void hostPause() { if (view != null) view.hostPause(); }
    public void hostResume() { if (view != null) view.hostResume(); }
    public void release() { if (view != null) { view.close(); view = null; } }
}
