package com.yinyangeq.apkmanager;

import java.io.IOException;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Transport compiled exclusively into this edition. */
final class UpdateChannel {
    static final String CONFIG = "release-feed.json", CATALOG = "/latest/download/catalog.json";
    static final String OFFLINE = "Cannot reach GitHub. Showing the last checked release when available.";
    private static final String ORIGIN = "https://github.com/yinyangeqlab/yinyang-android/releases";
    static final boolean PRIVATE = false;
    static final String CAPTION = "Android app updates";
    private String releasePath;

    UpdateChannel(JSONObject config) throws Exception {
        if (!ORIGIN.equals(config.getString("endpoint"))) throw new IOException("Unexpected release origin.");
    }
    void select(JSONObject catalog) throws Exception {
        String tag = catalog.getString("tag");
        if (!tag.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) throw new IOException("Invalid release tag.");
        releasePath = "/download/" + tag + "/";
    }
    String patch(String hash) { return releasePath + hash + ".patch"; }
    String apk(Updates.App a) { return releasePath + a.asset; }
    HttpsURLConnection open(String path) throws IOException {
        URL url = new URL(ORIGIN + path);
        for (int attempt = 0; attempt < 6; attempt++) {
            HttpsURLConnection c = (HttpsURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            int status;
            try { status=c.getResponseCode(); }
            catch(IOException ex) { c.disconnect(); throw ex; }
            if (status == 200)
                return c;
            String location = c.getHeaderField("Location");
            c.disconnect();
            if (status < 300 || status >= 400 || location == null)
                break;
            url = new URL(url, location);
            String host = url.getHost();
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null
                    || (url.getPort() != -1 && url.getPort() != 443)
                    || !(host.equals("github.com") || host.equals("release-assets.githubusercontent.com")
                         || host.equals("objects.githubusercontent.com")))
                break;
        }
        throw new IOException("Update feed unavailable.");
    }

}
