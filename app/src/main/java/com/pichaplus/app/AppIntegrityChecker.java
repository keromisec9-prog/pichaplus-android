package com.pichaplus.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.security.MessageDigest;

public class AppIntegrityChecker {

    // SHA-256 of the real pichaplus release signing certificate, lowercase, no separators.
    private static final String EXPECTED_FINGERPRINT =
        "3c4f9470600ba99937f8d27da9c8d697ec65971ccb6ed83f60efdd3e31c5371a";

    public static boolean isGenuine(Context context) {
        try {
            String actual = getSigningFingerprint(context);
            return actual != null && actual.equalsIgnoreCase(EXPECTED_FINGERPRINT);
        } catch (Exception e) {
            // If we can't verify, treat as not genuine (fail closed)
            return false;
        }
    }

    private static String getSigningFingerprint(Context context) throws Exception {
        PackageManager pm = context.getPackageManager();
        String packageName = context.getPackageName();
        Signature[] signatures;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            signatures = info.signingInfo.getApkContentsSigners();
        } else {
            PackageInfo info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
            signatures = info.signatures;
        }

        if (signatures == null || signatures.length == 0) return null;

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(signatures[0].toByteArray());

        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
