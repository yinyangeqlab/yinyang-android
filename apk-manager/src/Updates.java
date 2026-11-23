package com.yinyangeq.apkmanager;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.AtomicFile;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

final class Updates {
    static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final long MAX_APK = 256L * 1024 * 1024;
    final Context ctx;
    final Map<String, App> apps = new LinkedHashMap<>();
    private final UpdateChannel channel;
    interface Progress { void show(String text); }
    private final Progress progress;
    private final AtomicFile catalog;
    private final File dir;

    static class App {
        final String id, label, notificationLabel, downloadName, signer;
        final int minSdk;
        String version, hash, installed, problem;
        final Map<String, JSONObject> patches = new LinkedHashMap<>();
        String asset;
        long code, size;
        File baseApk;
        boolean patched;
        boolean current, blocked, ready;
        App(JSONObject pin) throws JSONException {
            id = pin.getString("package");
            label = pin.getString("label");
            notificationLabel = pin.optString("notificationLabel", label);
            downloadName = pin.optString("downloadName");
            signer = pin.getString("signer");
            minSdk = pin.optInt("minSdk", 0);
            asset = pin.optString("asset");
        }
        boolean wanted() {
            return hash != null && !current && !blocked;
        }
    }

    Updates(Context context) throws Exception {
        this(context, null);
    }

    Updates(Context context, Progress listener) throws Exception {
        progress = listener;
        ctx = context.getApplicationContext();
        dir = new File(ctx.getFilesDir(), "apks");
        if (UpdateChannel.PRIVATE && !dir.isDirectory() && !dir.mkdir())
            throw new IOException("Cannot open update storage.");
        catalog = new AtomicFile(new File(ctx.getFilesDir(), "catalog.json"));
        JSONObject config;
        try (InputStream in = ctx.getAssets().open(UpdateChannel.CONFIG)) {
            config = new JSONObject(new String(read(in,65536),StandardCharsets.UTF_8));
        }
        channel = new UpdateChannel(config);
        JSONArray list = config.getJSONArray("apps");
        for (int i = 0; i < list.length(); i++) {
            App app = new App(list.getJSONObject(i));
            apps.put(app.id, app);
        }
    }

    void load(boolean online) throws Exception {
        byte[] manifest = null;
        if (online) {
            HttpsURLConnection con = channel.open(UpdateChannel.CATALOG);
            try (InputStream in = con.getInputStream()) {
                manifest = read(in, 65536);
            } finally {
                con.disconnect();
            }
        } else if (UpdateChannel.PRIVATE) {
            throw new IOException("Connect your approved device to the private feed.");
        } else if (catalog.getBaseFile().exists()) {
            manifest = catalog.readFully();
        }
        if (manifest != null) {
            JSONObject object = new JSONObject(new String(manifest, StandardCharsets.UTF_8));
            if (object.getInt("schema") != 1)
                throw new IOException("Unsupported catalog format.");
            channel.select(object);
            readCatalogApps(object.getJSONArray("apps"), false);
            if (!UpdateChannel.PRIVATE && object.has("additionalApps"))
                readCatalogApps(object.getJSONArray("additionalApps"), true);
            if (online && !UpdateChannel.PRIVATE) {
                FileOutputStream f = catalog.startWrite();
                try {
                    f.write(manifest);
                    catalog.finishWrite(f);
                } catch (IOException e) {
                    catalog.failWrite(f);
                    throw e;
                }
            }
        }
        for (App a : apps.values()) {
            try {
                if (android.os.Build.VERSION.SDK_INT < a.minSdk) {
                    a.blocked = true;
                    a.problem = a.minSdk == 33 ? "Requires Android 13 or newer." : "Requires a newer version of Android.";
                    continue;
                }
                inspectInstalled(a);
                if (UpdateChannel.PRIVATE && a.wanted()) {
                    File path = file(a);
                    if (path.exists()) {
                        try {
                            verify(a, path);
                            a.ready = true;
                        } catch (IOException e) {
                            if (!path.delete())
                                throw e;
                        }
                    }

                }
            } catch (Exception e) {
                a.problem = "Could not verify this build. Check again.";
            }
        }
        if (UpdateChannel.PRIVATE)
            cleanStagedApks();
    }

    private void readCatalogApps(JSONArray list, boolean optional) throws IOException, JSONException {
        for (int i = 0; i < list.length(); i++) {
            JSONObject entry = list.getJSONObject(i);
            App app = apps.get(entry.getString("package"));
            // Older Manager versions skip optional apps that they do not have a release pin for.
            if(optional && app==null)continue;
            if (app == null || app.hash != null)
                throw new IOException("Unexpected catalog entry.");
            app.hash = entry.getString("sha256");
            app.version = entry.getString("version");
            app.size = entry.getLong("bytes");
            app.code = entry.getLong("code");
            if (!app.hash.matches("[a-f0-9]{64}") || app.size < 1 || app.size > MAX_APK || app.code < 1)
                throw new IOException("Invalid build metadata.");
            if (UpdateChannel.PRIVATE) {
                addPatch(app, entry.optJSONObject("patch"));
                JSONArray alternatives = entry.optJSONArray("patches");
                if (alternatives != null)
                    for (int n = 0; n < Math.min(2, alternatives.length()); n++)
                        addPatch(app, alternatives.optJSONObject(n));
            }
        }
    }

    private void cleanStagedApks() {
        Set<String> keep = new HashSet<>();
        for (App a : apps.values())
            if (a.hash != null)
                keep.add(a.hash + ".apk");
        File[] files = dir.listFiles();
        if (files != null)
            for (File f : files)
                if (!keep.contains(f.getName())
                    && f.lastModified() < System.currentTimeMillis() - 2 * 86400000L)
                    f.delete();
    }

    private void inspectInstalled(App a) throws Exception {
        PackageInfo pi;
        try {
            pi = ctx.getPackageManager().getPackageInfo(a.id, PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException absent) {
            return;
        }
        a.installed = displayVersion(pi.versionName, pi.getLongVersionCode());
        if (!signed(a, pi)) {
            a.blocked = true;
            a.problem = "Installed app uses a different signing key.";
            return;
        }
        a.baseApk = new File(pi.applicationInfo.sourceDir);
        if (a.hash == null)
            return;
        if (pi.getLongVersionCode() > a.code) {
            a.blocked = true;
            a.problem = "Installed version is newer.";
            return;
        }
        if (pi.getLongVersionCode() == a.code)
            a.current = a.hash.equals(digest(new File(pi.applicationInfo.sourceDir)));
    }

    static String displayVersion(String version, long code) {
        return UpdateChannel.PRIVATE ? "v" + version + " (" + code + ")" : "v" + version + " · Build #" + code;
    }

    private void fetch(String path, File target, long expected) throws IOException {
        HttpsURLConnection c = channel.open(path);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(target)) {
            long remaining = expected;
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (n > remaining)
                    throw new IOException("Unexpected download size.");
                out.write(buffer, 0, n);
                remaining -= n;
            }
            if (remaining != 0)
                throw new IOException("Incomplete download.");
            out.getFD().sync();
        } finally {
            c.disconnect();
        }
    }

    private static void addPatch(App app, JSONObject patch) {
        if (patch != null && patch.optString("base").matches("[a-f0-9]{64}")
                && patch.optString("sha256").matches("[a-f0-9]{64}")
                && patch.optLong("bytes") > 0 && patch.optLong("bytes") < app.size)
            app.patches.put(patch.optString("base"), patch);
    }

    File downloadVerified(App app) throws Exception {
        if (!app.wanted() || app.problem != null || !app.asset.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk"))
            throw new IOException("Unavailable build");
        File temp = File.createTempFile("download-", ".apk", ctx.getCacheDir());
        try {
            fetch(channel.apk(app), temp, app.size);
            verify(app, temp);
            return temp;
        } catch (Exception e) {
            temp.delete();
            throw e;
        }
    }

    private void report(String text) {
        if (progress != null)
            progress.show(text);
    }

    private boolean tryPatch(App a, File staged) {
        if (a.patches.isEmpty() || a.baseApk == null)
            return false;
        File patch = null;
        try {
            JSONObject offer = a.patches.get(digest(a.baseApk));
            if (offer == null)
                return false;
            patch = File.createTempFile("incoming-", ".patch", dir);
            String hash = offer.getString("sha256");
            report("Downloading patch · " + a.label);
            fetch(channel.patch(hash), patch, offer.getLong("bytes"));
            if (!digest(patch).equals(hash))
                return false;
            report("Applying and verifying patch · " + a.label);
            ApkPatch.apply(a.baseApk, patch, staged, a.size);
            verify(a, staged);
            return true;
        } catch (Exception failedPatch) {
            return false;
        } finally {
            if (patch != null)
                patch.delete();
        }
    }

    void download(App a) throws Exception {
        if (!a.wanted() || a.problem!=null) throw new IOException("Unavailable build");
        File temp = File.createTempFile("incoming-", ".apk", dir);
        try {
            a.patched = tryPatch(a, temp);
            if (!a.patched) {
                report("Downloading full APK · " + a.label);
                fetch(channel.apk(a), temp, a.size);
                verify(a, temp);
            }
            if (!temp.renameTo(file(a)))
                throw new IOException("Cannot stage this update.");
        } finally {
            temp.delete();
        }
    }


    File file(App a) {
        return new File(dir, a.hash + ".apk");
    }
    void verify(App a, File f) throws Exception {
        if (f.length() != a.size || !digest(f).equals(a.hash))
            throw new IOException("Build checksum differs.");
        PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(
            f.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (pi == null || !pi.packageName.equals(a.id) || pi.getLongVersionCode() != a.code || !signed(a, pi))
            throw new IOException("APK identity differs.");
    }

    private static boolean signed(App a, PackageInfo pi) throws Exception {
        if (pi.signingInfo == null)
            return false;
        Signature[] certs = pi.signingInfo.getApkContentsSigners();
        return certs.length == 1
            && a.signer.equals(hex(MessageDigest.getInstance("SHA-256").digest(certs[0].toByteArray())));
    }
    private static String digest(File file) throws Exception {
        MessageDigest sum = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) sum.update(buf, 0, n);
        }
        return hex(sum.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(64);
        for (byte b : bytes)
            out.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return out.toString();
    }
    private static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > max)
                throw new IOException("Catalog too large.");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
