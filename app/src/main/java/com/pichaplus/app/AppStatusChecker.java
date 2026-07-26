package com.pichaplus.app;

import android.os.AsyncTask;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class AppStatusChecker {

    private static final String STATUS_URL =
    private static final String PV_BUILD_TOKEN = "230285ef7c5f5e229cb6295bc7053470";
        "https://picha-plus-worker.kerosoftz522.workers.dev/app-status";

    public interface Callback {
        void onResult(AppStatus status);
    }

    public static class AppStatus {
        public boolean maintenance;
        public String title;
        public String message;
        public String endTime;

        AppStatus(boolean maintenance, String title, String message, String endTime) {
            this.maintenance = maintenance;
            this.title = title;
            this.message = message;
            this.endTime = endTime;
        }
    }

    public static void fetch(Callback callback) {
        new AsyncTask<Void, Void, AppStatus>() {
            @Override
            protected AppStatus doInBackground(Void... voids) {
                try {
                    URL url = new URL(STATUS_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("X-Pv-Build", PV_BUILD_TOKEN);

                    int code = conn.getResponseCode();
                    if (code != 200) return new AppStatus(false, null, null, null);

                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    return new AppStatus(
                        json.optBoolean("maintenance", false),
                        json.optString("title", null),
                        json.optString("message", null),
                        json.optString("endTime", null)
                    );
                } catch (Exception e) {
                    // Fail open: if the check itself breaks, don't block the app
                    return new AppStatus(false, null, null, null);
                }
            }

            @Override
            protected void onPostExecute(AppStatus result) {
                callback.onResult(result);
            }
        }.execute();
    }
}
