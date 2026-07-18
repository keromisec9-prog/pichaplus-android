# Run in Termux:
#   cd ~/pichaplus-android
#   python3 patch_intercept_download.py

path = "app/src/main/java/com/pichaplus/app/MainActivity.java"
src = open(path, encoding="utf-8").read()

old = """                // Google OAuth must open in external browser (WebView blocked by Google)
                // Check connectivity before loading anything
                if (!isConnected()) {
                    view.loadUrl(OFFLINE_URL);
                    return true;
                }
                view.loadUrl(url);
                return true;
            }"""

new = """                // Force downloads through DownloadManager instead of letting WebView
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
            }"""

if old not in src:
    print("❌ Pattern not found — file may differ from expected. No changes made.")
else:
    src = src.replace(old, new)
    open(path, "w", encoding="utf-8").write(src)
    print("✅ Patched shouldOverrideUrlLoading to intercept download URLs.")
