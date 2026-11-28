package com.yinyangeq.qudelixprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.net.Uri;
import android.text.InputFilter;
import android.util.Base64;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Keeps credentials and the private receipt separate from the public issue workflow. */
public final class ReportClient {
    private static final String ENDPOINT = "com.yinyangeq.REPORT_ENDPOINT";
    private static final String PRIVACY_NOTICE = "https://yinyangeq.com/privacy#privacy-android-reporting";
    private static final String RECEIPT = "receipt/";
    private static final String ALREADY_SENT = "You already sent these diagnostics from this build.";
    private static final ExecutorService NETWORK = Executors.newFixedThreadPool(2);
    private final Activity activity;
    private final URI endpoint;
    private final String app;
    private final JSONObject diagnostics;
    private final SharedPreferences library;

    private ReportClient(Activity activity, URI endpoint, String app, JSONObject diagnostics) {
        this.activity = activity;
        this.endpoint = endpoint;
        this.app = app;
        this.diagnostics = diagnostics;
        int port = endpoint.getPort();
        if (port == -1) port = "https".equals(endpoint.getScheme()) ? 443 : 80;
        String store = "helper-reports-" + endpoint.getScheme() + "-"
            + endpoint.getHost().toLowerCase(java.util.Locale.ROOT) + "-" + port;
        library = activity.getSharedPreferences(store, Context.MODE_PRIVATE);
    }

    private static URI endpoint(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager()
                .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            if (info.metaData == null || !info.metaData.containsKey(ENDPOINT)) return null;
            URI uri = URI.create(info.metaData.getString(ENDPOINT));
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !uri.getPath().isEmpty()) return null;
            if ("https".equals(uri.getScheme())) return uri;
            if ("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                    && (info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) return uri;
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException ignored) {}
        return null;
    }

    public static boolean available(Context context) {
        return endpoint(context) != null;
    }

    public static void show(Activity activity, String app, JSONObject diagnostics) {
        URI url = endpoint(activity);
        if (url != null) new ReportClient(activity, url, app, diagnostics).review(null, "");
    }

    private boolean liveReporting() {
        return "reports.yinyangeq.com".equalsIgnoreCase(endpoint.getHost());
    }

    public static JSONObject deviceDiagnostics() {
        JSONObject data = new JSONObject();
        field(data, "device", Build.MANUFACTURER + " " + Build.MODEL);
        field(data, "android", Build.VERSION.RELEASE);
        field(data, "api", Build.VERSION.SDK_INT);
        return data;
    }

    public static void field(JSONObject target, String name, Object value) {
        if (value == null) return;
        String text = value.toString().replaceAll("[^A-Za-z0-9 .,_+()\\[\\]:-]", " ").trim();
        if (text.isEmpty() || text.matches("(?i).*(?:[a-f0-9]{2}[:-]){5}[a-f0-9]{2}.*")) return;
        try { target.put(name, text.substring(0, Math.min(text.length(), 500))); }
        catch (JSONException impossible) { throw new IllegalArgumentException(impossible); }
    }

    private void review(String receipt, String description) {
        if (activity.isDestroyed() || activity.isFinishing()) return;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding / 2, padding, padding / 2);
        TextView details = new TextView(activity);
        StringBuilder text = new StringBuilder("YinYang EQ Helper is an automated bot for Yin Yang EQ Lab. "
            + "Review the diagnostics before sending. The Lab receives your diagnostics and description privately. "
            + "The app name, build number, and status updates may appear in a public GitHub issue.\n\n");
        if (liveReporting()) {
            text.append("Private reports expire 90 days after submission.\n\n");
        }
        if ("http".equals(endpoint.getScheme())) text.append("This preview sends to the local test server.\n\n");
        for (Iterator<String> keys = diagnostics.keys(); keys.hasNext();) {
            String key = keys.next();
            text.append(key.replaceAll("([a-z])([A-Z])", "$1 $2"))
                .append("  ").append(diagnostics.optString(key)).append('\n');
        }
        details.setText(text);
        content.addView(details);
        if (liveReporting()) {
            Button privacy = new Button(activity, null, android.R.attr.borderlessButtonStyle);
            privacy.setText("Privacy Notice");
            privacy.setAllCaps(false);
            privacy.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            privacy.setOnClickListener(view -> {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_NOTICE)));
                } catch (ActivityNotFoundException unavailable) {
                    Toast.makeText(activity, "Open yinyangeq.com/privacy in a browser.", Toast.LENGTH_LONG).show();
                }
            });
            content.addView(privacy, new LinearLayout.LayoutParams(-1, -2));
        }
        EditText note = new EditText(activity);
        note.setHint("What happened? (optional)");
        note.setText(description);
        note.setFilters(new InputFilter[]{new InputFilter.LengthFilter(600)});
        content.addView(note);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
            .setTitle(receipt == null ? "Review report" : "Review updated report")
            .setView(scroll).setNegativeButton("Cancel", null).setPositiveButton("Send report", null);
        if (receipt == null && library.getAll().keySet().stream().anyMatch(key -> key.startsWith(RECEIPT)))
            dialog.setNeutralButton("My reports", (ignored, index) -> showReports(false));
        AlertDialog view = dialog.show();
        view.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            view.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            String message = note.getText().toString();
            request(() -> send(receipt, message), result -> {
                view.dismiss();
                String token = result.optString("receipt");
                library.edit().putString(RECEIPT + token, token)
                    .putBoolean(result.optString("clientSentKey"), true).apply();
                Toast.makeText(activity, result.optBoolean("duplicate") ? "Already sent" : "Report sent",
                    Toast.LENGTH_SHORT).show();
                loadStatus(token);
            }, () -> view.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true));
        });
    }

    private String installation() {
        synchronized (library) {
            String key = library.getString("installation", "");
            if (key.isEmpty()) {
                key = UUID.randomUUID().toString().replace("-", "");
                library.edit().putString("installation", key).apply();
            }
            return key;
        }
    }

    private JSONObject send(String receipt, String note) throws Exception {
        PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
        long build = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        String sentKey = sentKey(build);
        if (library.getBoolean(sentKey, false)) throw new IOException(ALREADY_SENT);
        JSONObject payload = new JSONObject().put("app", app).put("build", build)
            .put("installation", installation()).put("diagnostics", diagnostics).put("note", note);
        if (receipt != null) payload.put("receipt", receipt);
        JSONObject result = call("/reports", payload);
        if (!result.optString("receipt").matches("[A-Za-z0-9_-]{32}")) throw new IOException("Invalid receipt.");
        result.put("clientSentKey", sentKey);
        return result;
    }

    private String sentKey(long build) throws Exception {
        ArrayList<String> fields = new ArrayList<>();
        diagnostics.keys().forEachRemaining(fields::add);
        Collections.sort(fields);
        JSONObject snapshot = new JSONObject();
        for (String field : fields) {
            snapshot.put(field, diagnostics.getString(field).trim().replaceAll(" +", " "));
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(snapshot.toString().getBytes(StandardCharsets.UTF_8));
        return "sent/" + build + "/" + Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private void showReports(boolean deleting) {
        ArrayList<String> tokens = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        for (String key : library.getAll().keySet()) {
            if (!key.startsWith(RECEIPT)) continue;
            String token = key.substring(RECEIPT.length());
            tokens.add(token);
            labels.add(library.getString(key, "").startsWith("#") ? library.getString(key, "") : "Recent report");
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
            .setTitle(deleting ? "Choose a report to delete" : "My reports")
            .setItems(labels.toArray(new String[0]), (unused, index) -> {
                if (deleting) confirmDelete(tokens.get(index), labels.get(index));
                else loadStatus(tokens.get(index));
            })
            .setNegativeButton(deleting ? "Cancel" : "Close", null);
        if (!deleting && liveReporting()) {
            dialog.setNeutralButton("Delete a report", (unused, index) -> showReports(true));
        }
        dialog.show();
    }

    private void confirmDelete(String receipt, String label) {
        AlertDialog confirm = new AlertDialog.Builder(activity).setTitle("Delete " + label + "?")
            .setMessage("This deletes your private submission and ends access to its updates from this app. "
                + "Any public GitHub issue stays visible.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete report", null).show();
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            view.setEnabled(false);
            request(() -> {
                JSONObject result = call("/reports/" + receipt + "/delete",
                    new JSONObject().put("installation", installation()));
                if (!result.optBoolean("deleted")) throw new IOException("Deletion could not be verified.");
                return result;
            }, result -> {
                library.edit().remove(RECEIPT + receipt).apply();
                confirm.dismiss();
                Toast.makeText(activity, "Private report deleted.", Toast.LENGTH_LONG).show();
            }, () -> view.setEnabled(true));
        });
    }

    private void loadStatus(String receipt) {
        request(() -> call("/reports/" + receipt, null), result -> showStatus(receipt, result), () -> {});
    }

    private void showStatus(String receipt, JSONObject result) {
        if (result.optBoolean("unavailable")) {
            library.edit().remove(RECEIPT + receipt).apply();
            Toast.makeText(activity, "This report is no longer available.", Toast.LENGTH_LONG).show();
            return;
        }
        String title = "#" + result.optInt("issue") + " report";
        library.edit().putString(RECEIPT + receipt, title).apply();
        JSONArray updates = result.optJSONArray("updates");
        StringBuilder messages = new StringBuilder();
        for (int i = 0; updates != null && i < updates.length(); i++) {
            JSONObject update = updates.optJSONObject(i);
            if (update != null) messages.append(update.optString("body")).append("\n\n");
        }
        AlertDialog.Builder view = new AlertDialog.Builder(activity).setTitle(title)
            .setMessage(messages.toString().trim()).setNegativeButton("Close", null);
        String state = result.optString("state");
        JSONObject attempt = result.optJSONObject("attempt");
        if ("awaiting_confirmation".equals(state) && attempt != null && result.optBoolean("canConfirm")) {
            int id = attempt.optInt("id");
            view.setNeutralButton("Still broken", (dialog, index) -> feedback(receipt, id, false));
            view.setPositiveButton("It's fixed", (dialog, index) -> feedback(receipt, id, true));
        } else if ("awaiting_report".equals(state) || "manual_review".equals(state)) {
            view.setPositiveButton("Send updated report", (dialog, index) -> review(receipt, ""));
        }
        view.show();
    }

    private void feedback(String receipt, int attempt, boolean resolved) {
        request(() -> call("/reports/" + receipt + "/feedback",
            new JSONObject().put("attempt", attempt).put("resolved", resolved)),
            result -> loadStatus(receipt), () -> {});
    }

    private JSONObject call(String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) endpoint.resolve(path).toURL().openConnection();
        try {
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            connection.setInstanceFollowRedirects(false);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            String contentType = connection.getContentType();
            if (status == 404 && body == null && contentType != null && contentType.startsWith("application/json")) {
                return new JSONObject().put("unavailable", true);
            }
            if (status != 200 && status != 409) throw new IOException("Report service unavailable.");
            try (InputStream input = status == 200 ? connection.getInputStream() : connection.getErrorStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (output.size() + count > 65_536) throw new IOException("Report response is too large.");
                    output.write(buffer, 0, count);
                }
                JSONObject result = new JSONObject(output.toString("UTF-8"));
                if (status != 200) throw new IOException(result.optString("error", "Try the current build."));
                return result;
            }
        } finally {
            connection.disconnect();
        }
    }

    private interface Request { JSONObject run() throws Exception; }
    private interface Success { void run(JSONObject result); }

    private void request(Request action, Success success, Runnable retry) {
        NETWORK.execute(() -> {
            try {
                JSONObject response = action.run();
                activity.runOnUiThread(() -> {
                    if (!activity.isDestroyed() && !activity.isFinishing()) success.run(response);
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed() || activity.isFinishing()) return;
                    retry.run();
                    String message = error.getMessage();
                    Toast.makeText(activity, ALREADY_SENT.equals(message) || "Send the updated report from the build you tested.".equals(message)
                        ? message : "Couldn't reach the report service. Try again.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}
