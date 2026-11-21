package com.yinyangeq.apkmanager;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MainActivity extends Activity {
    private LinearLayout rows;
    private TextView status;
    private Button refresh;
    private Button updateAll;
    private CheckBox updateAlerts;
    private boolean showingAlertSettings;
    private long generation;
    private boolean checking;
    private boolean first = true;
    private boolean resumed;
    private String pending;
    private String pendingExport;
    private ArrayList<String> pendingBatchExport;
    private static final int SAVE_APK = 24;
    private static final int SAVE_APKS = 25;
    private static final int INK = 0xffedf3f7, AQUA = 0xff58d8c9, MUTED = 0xffb5c7ca;

    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            pending=saved.getString("permission_build");
            pendingExport=saved.getString("export_build");
            pendingBatchExport=saved.getStringArrayList("export_batch");
        }
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(0xff080d0f);
        screen.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        addBuildLabel(screen);
        ScrollView scroll = new ScrollView(this);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(4), dp(20), dp(24));
        scroll.addView(page);
        setContentView(screen);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.brand_header);
        header.addView(logo, dp(41), dp(41));
        TextView brand = label(getApplicationInfo().loadLabel(getPackageManager()).toString(), 27, INK, true);
        brand.setPadding(dp(10), 0, 0, 0);
        header.addView(brand);
        page.addView(header);
        addAlertControls(page);
        status = label("Reading available builds…", 15, MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status);
        refresh = new Button(this);
        refresh.setText("Check for updates");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(v -> check(true));
        if (UpdateChannel.PRIVATE) {
            page.addView(refresh);
        } else {
            LinearLayout actions = new LinearLayout(this);
            actions.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));
            updateAll = new Button(this);
            updateAll.setText("Update all");
            updateAll.setAllCaps(false);
            updateAll.setEnabled(false);
            actions.addView(updateAll, new LinearLayout.LayoutParams(0, -2, 1));
            page.addView(actions);
        }
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        page.addView(rows);
        if (!UpdateChannel.PRIVATE) {
            TextView installNote = label(
                "To reduce the risk of Google Play Protect flagging App Manager as unsafe, "
                + "we leave installation to Android Files. Tap Install… to open the folder, "
                + "then tap the verified APK to start Android’s installer. "
                + "Play Protect may still ask to scan the APK. Thanks for understanding.",
                14, MUTED, false);
            installNote.setPadding(0, dp(16), 0, 0);
            page.addView(installNote);
        }
        Checks.schedule(this);
    }

    private void addBuildLabel(LinearLayout screen) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            TextView version = label("v" + info.versionName + " · Build " + info.getLongVersionCode(), 12, MUTED, false);
            version.setGravity(Gravity.END);
            version.setPaddingRelative(dp(20), dp(14), dp(20), 0);
            screen.addView(version, -1, -2);
        } catch (PackageManager.NameNotFoundException ignored) {}
    }

    private void addAlertControls(LinearLayout page) {
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        CheckBox alerts = new CheckBox(this);
        alerts.setText(UpdateChannel.PRIVATE ? "Update alerts" : "Enable/Disable Notifications for Update Alerts");
        alerts.setMinHeight(dp(48));
        updateAlerts = alerts;
        showAlertSetting();
        alerts.setOnCheckedChangeListener((button, enabled) -> {
            if (showingAlertSettings) return;
            Set<String> selected = Checks.selectedApps(this);
            saveAlerts(enabled, enabled && selected != null && selected.isEmpty() ? null : selected);
        });
        controls.addView(alerts);
        if (!UpdateChannel.PRIVATE) {
            Button apps = new Button(this, null, android.R.attr.borderlessButtonStyle);
            apps.setAllCaps(false);
            apps.setTextColor(AQUA);
            apps.setText("Choose Which Apps Receive Update Notifications");
            apps.setOnClickListener(v -> chooseAlertApps());
            controls.addView(apps);
        }
        page.addView(controls);
    }

    private void showAlertSetting() {
        showingAlertSettings = true;
        updateAlerts.setChecked(Checks.alertsEnabled(this));
        showingAlertSettings = false;
    }

    private void saveAlerts(boolean enabled, Set<String> apps) {
        Checks.setAlerts(this, enabled, apps);
        showAlertSetting();
        if (enabled && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9);
    }

    private void chooseAlertApps() {
        List<Updates.App> apps;
        try {
            apps = new ArrayList<>(new Updates(this).apps.values());
        } catch (Exception e) {
            status.setText("Could not open update alert settings.");
            return;
        }
        Set<String> saved = Checks.selectedApps(this);
        Set<String> choices = new HashSet<>();
        CharSequence[] labels = new CharSequence[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            Updates.App app = apps.get(i);
            labels[i] = app.label;
            checked[i] = saved == null ? Checks.alertsEnabled(this) : saved.contains(app.id);
            if (checked[i]) choices.add(app.id);
        }
        new AlertDialog.Builder(this)
            .setTitle("Notify me about")
            .setMultiChoiceItems(labels, checked, (dialog, index, enabled) -> {
                String id = apps.get(index).id;
                if (enabled) choices.add(id);
                else choices.remove(id);
            })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", (dialog, button) ->
                saveAlerts(!choices.isEmpty(), choices.size() == apps.size() ? null : choices))
            .show();
    }
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("permission_build",pending);
        out.putString("export_build",pendingExport);
        out.putStringArrayList("export_batch", pendingBatchExport);
    }
    protected void onResume() {
        super.onResume();
        resumed=true;
        long last=getPreferences(MODE_PRIVATE).getLong("checked",0);
        check(UpdateChannel.PRIVATE || first || System.currentTimeMillis()-last>60000);
        first=false;
    }
    protected void onPause() {
        resumed=false;
        if(UpdateChannel.PRIVATE) {
            generation++;
            checking=false;
            rows.removeAllViews();
        }
        super.onPause();
    }
    private void setBusy(boolean busy) {
        checking=busy;
        refresh.setEnabled(!busy);
        if (!UpdateChannel.PRIVATE && busy) updateAll.setEnabled(false);
    }
    private void check(boolean online) {
        if(checking)return;
        long sequence=++generation;
        setBusy(true);
        rows.removeAllViews();
        status.setText("Checking for updates…");
        Updates.worker.execute(()->{
            try {
                Updates u=new Updates(this);
                String summary=UpdateChannel.CAPTION;
                try {u.load(UpdateChannel.PRIVATE || online);}
                catch(Exception offline) {
                    if(UpdateChannel.PRIVATE)throw offline;
                    u=new Updates(this);u.load(false);summary=UpdateChannel.OFFLINE;
                }
                Updates ready=u;
                String note=summary;
                runOnUiThread(()->{
                    if(isDestroyed() || sequence!=generation)return;
                    setBusy(false);status.setText(note);display(ready,true);
                    if(online)getPreferences(MODE_PRIVATE).edit().putLong("checked",System.currentTimeMillis()).apply();
                    if(UpdateChannel.PRIVATE && pending!=null && resumed)resumeInstall(ready);
                });
            }catch(Exception e){runOnUiThread(()->{
                if(isDestroyed() || sequence!=generation)return;
                pending=null;setBusy(false);rows.removeAllViews();
                status.setText(UpdateChannel.OFFLINE);
            });}
        });
    }
    private void prepare(Updates.App selected) {
        long sequence=++generation;
        setBusy(true);rows.removeAllViews();
        status.setText("Verifying "+selected.label+"…");
        Updates.worker.execute(()->{
            try {
                Updates u=new Updates(this);
                u.load(UpdateChannel.PRIVATE || !selected.ready);
                Updates.App current=u.apps.get(selected.id);
                if(current==null || !selected.hash.equals(current.hash) || !current.wanted() || current.problem!=null)
                    throw new java.io.IOException();
                if(!current.ready){u.download(current);current.ready=true;}
                runOnUiThread(()->{
                    if(isDestroyed() || sequence!=generation)return;
                    setBusy(false);status.setText("Verified "+current.label);display(u,true);
                    if(resumed)install(current);
                });
            }catch(Exception error){runOnUiThread(()->{
                if(isDestroyed() || sequence!=generation)return;
                setBusy(false);status.setText("Could not verify this update. Check your connection and refresh.");
            });}
        });
    }
    private void display(Updates u, boolean interactive) {
        rows.removeAllViews();
        ArrayList<String> pendingUpdates = new ArrayList<>();
        for (Updates.App a : u.apps.values()) {
            if (UpdateChannel.PRIVATE && a.id.equals(getPackageName()) && !a.wanted() && a.problem == null)
                continue;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(0xff101d20);
            shape.setCornerRadius(dp(12));
            shape.setStroke(dp(1), 0xff30484b);
            card.setBackground(shape);
            card.setPadding(dp(16), dp(10), dp(16), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(10);
            rows.addView(card, lp);
            card.addView(label(a.label, 19, INK, true));
            card.addView(
                label(a.installed == null ? "Not installed" : "Installed " + a.installed, 14, MUTED, false));
            Uri saved = !UpdateChannel.PRIVATE && a.wanted() && a.problem == null ? Downloads.savedFile(this, a) : null;
            String text = a.problem != null ? a.problem
                : a.current                 ? "Up to date"
                : a.hash == null            ? "Waiting for a published build"
                                 : (a.patched ? "Patched and verified " : a.ready || saved != null ? "Downloaded and verified " : "Available ")
                                    + Updates.displayVersion(a.version, a.code);
            card.addView(label(text, 14, AQUA, false));
            if (a.wanted() && a.problem == null) {
                Button b = new Button(this);
                b.setAllCaps(false);
                b.setText(UpdateChannel.PRIVATE
                    ? (!a.ready ? "Download and install" : a.installed == null ? "Install" : "Install update")
                    : saved != null ? "Install…" : (a.installed == null ? "Download APK" : "Update"));
                b.setEnabled(interactive);
                if (UpdateChannel.PRIVATE)
                    b.setOnClickListener(v -> prepare(a));
                else
                    b.setOnClickListener(v -> downloadUpdate(a, saved));
                if (!UpdateChannel.PRIVATE && a.installed != null && saved == null)
                    pendingUpdates.add(a.hash);
                card.addView(b);
            }
        }
        if (!UpdateChannel.PRIVATE) {
            updateAll.setEnabled(interactive && !pendingUpdates.isEmpty());
            updateAll.setOnClickListener(v -> downloadAll(pendingUpdates));
        }
    }

    private void downloadAll(ArrayList<String> hashes) {
        if (Build.VERSION.SDK_INT >= 29) {
            saveDownloads(hashes, null, false);
            return;
        }
        try {
            pendingBatchExport = hashes;
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), SAVE_APKS);
        } catch (ActivityNotFoundException | SecurityException e) {
            pendingBatchExport = null;
            status.setText("Android Files could not open a folder picker.");
        }
    }
    private void downloadUpdate(Updates.App app, Uri saved) {
        try {
            if (saved != null) {
                Downloads.openFolder(this, app, saved);
                return;
            }
            if (Build.VERSION.SDK_INT < 29) {
                pendingExport = app.hash;
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType(Downloads.MIME)
                    .putExtra(Intent.EXTRA_TITLE, Downloads.fileName(app)), SAVE_APK);
            } else {
                saveDownloads(Collections.singletonList(app.hash), null, false);
            }
        } catch (ActivityNotFoundException | SecurityException e) {
            status.setText("Android Downloads is unavailable. Check your device settings and try again.");
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (UpdateChannel.PRIVATE) return;
        if (request == SAVE_APK) {
            String hash = pendingExport;
            pendingExport = null;
            if (hash != null && result == RESULT_OK && data != null && data.getData() != null)
                saveDownloads(Collections.singletonList(hash), data.getData(), false);
        } else if (request == SAVE_APKS) {
            ArrayList<String> hashes = pendingBatchExport;
            pendingBatchExport = null;
            if (hashes != null && result == RESULT_OK && data != null && data.getData() != null)
                saveDownloads(hashes, data.getData(), true);
        }
    }
    private void saveDownloads(List<String> hashes, Uri destination, boolean folder) {
        long sequence = ++generation;
        setBusy(true);
        rows.removeAllViews();
        status.setText("Downloading and verifying…");
        Updates.worker.execute(() -> {
            try {
                Updates updates = new Updates(this);
                updates.load(false);
                int saved = 0;
                for (Updates.App app : updates.apps.values()) {
                    if (!hashes.contains(app.hash) || !app.wanted() || app.problem != null) continue;
                    try {
                        if (Downloads.savedFile(this, app) == null)
                            Downloads.save(this, updates, app, destination, folder);
                        saved++;
                    } catch (Exception ignored) {
                        // A failed download remains available for retry.
                    }
                }
                String result = downloadResult(saved, hashes.size());
                runOnUiThread(() -> {
                    if (isDestroyed() || sequence != generation) return;
                    setBusy(false);
                    display(updates, true);
                    status.setText(result);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed() || sequence != generation) return;
                    setBusy(false);
                    check(false);
                });
            }
        });
    }

    private static String downloadResult(int saved, int wanted) {
        if (saved == 0) return "No updates were saved. Check for updates and try again.";
        if (saved < wanted) return "Saved " + saved + " of " + wanted + " updates. Retry the remaining downloads.";
        if (saved == 1) return "Download verified. Tap Install to open the APK in Android Files.";
        return saved + " downloads verified. Tap Install for each app.";
    }
    private void install(Updates.App a) {
        try {
            if (!getPackageManager().canRequestPackageInstalls()) {
                pending = a.hash;
                startActivity(new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                return;
            }
            Uri uri = Apks.uri(this, a.hash);
            Intent i = new Intent(Intent.ACTION_VIEW)
                           .setDataAndType(uri, Apks.MIME)
                           .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setClipData(ClipData.newRawUri("APK", uri));
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            pending = null;
            status.setText("Android could not open the package installer.");
        }
    }
    private void resumeInstall(Updates u) {
        String hash = pending;
        pending = null;
        if (!getPackageManager().canRequestPackageInstalls())
            return;
        for (Updates.App a : u.apps.values())
            if (hash.equals(a.hash) && a.wanted() && a.ready && a.problem == null) {
                install(a);
                break;
            }
    }
    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
    private TextView label(String s, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setPadding(0, dp(3), 0, dp(3));
        if (bold)
            v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }
}
