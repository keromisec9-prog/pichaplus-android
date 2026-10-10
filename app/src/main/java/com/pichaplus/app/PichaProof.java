package com.pichaplus.app;

import android.net.Uri;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class PichaProof {
    // Replaced with the real value by the GitHub Actions build. Never commit the real one.
    private static final String K = "__APP_PROOF_SECRET__";

    static String sign(String token) {
        try {
            String ts = String.valueOf(System.currentTimeMillis());
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(K.getBytes("UTF-8"), "HmacSHA256"));
            byte[] d = m.doFinal((ts + "|" + token).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return ts + "." + sb;
        } catch (Exception e) { return ""; }
    }

    static String withProof(String url) {
        try {
            String t = Uri.parse(url).getQueryParameter("token");
            if (t == null) return url;
            return url + (url.contains("?") ? "&" : "?") + "app=" + sign(t);
        } catch (Exception e) { return url; }
    }
}
