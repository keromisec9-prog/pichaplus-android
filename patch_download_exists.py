import re
base = 'app/src/main/java/com/pichaplus/app/'
# PichaDownloads.java
p = base + 'PichaDownloads.java'
t = open(p).read()
if 'static boolean exists(' not in t:
    anchor = '    public static void remove(Context c, long id) {'
    assert t.count(anchor) == 1
    new = '''    public static boolean exists(Context c, long id) {
        try {
            JSONArray a = load(c);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || o.optLong("id") != id) continue;
                if (o.optBoolean("own")) {
                    SharedPreferences pp = c.getSharedPreferences("picha_pos", Context.MODE_PRIVATE);
                    return !"gone".equals(ownJson(c, o, pp).optString("status"));
                }
                DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
                Cursor cur = dm.query(new DownloadManager.Query().setFilterById(id));
                if (cur == null) return false;
                try {
                    if (!cur.moveToFirst()) return false;
                    int st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (st == DownloadManager.STATUS_SUCCESSFUL) return fileOk(dm, id);
                    return st != DownloadManager.STATUS_FAILED;
                } finally { cur.close(); }
            }
            return false;
        } catch (Exception e) { return false; }
    }

'''
    t = t.replace(anchor, new + anchor)
    open(p, 'w').write(t)
# MainActivity.java bridge
p = base + 'MainActivity.java'
t = open(p).read()
if 'downloadExists' not in t:
    anchor = '        @JavascriptInterface\n        public void removeDownload(long id) {'
    assert t.count(anchor) == 1
    new = '''        @JavascriptInterface
        public boolean downloadExists(long id) {
            return PichaDownloads.exists(MainActivity.this, id);
        }

'''
    t = t.replace(anchor, new + anchor)
    open(p, 'w').write(t)
print("ok")
