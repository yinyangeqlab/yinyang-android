package com.yinyangeq.apkmanager;

import android.app.*;
import android.app.job.*;
import android.content.*;
import java.util.ArrayList;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Checks extends JobService {
    private static final String CHANNEL = "builds";
    private static final int UPDATE_ID = 71;
    private static final String APPS = "alert_apps";
    private AtomicBoolean canceled;

    static Set<String> selectedApps(Context context) {
        return context.getSharedPreferences("settings", MODE_PRIVATE).getStringSet(APPS, null);
    }

    static synchronized void setAlerts(Context context, boolean enabled, Set<String> packages) {
        context.getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putBoolean("alerts", enabled).putStringSet(APPS, packages).apply();
        preferencesChanged(context);
    }

    static synchronized void preferencesChanged(Context context) {
        context.getSystemService(NotificationManager.class).cancel(UPDATE_ID);
        context.getSharedPreferences("notifications", MODE_PRIVATE).edit().remove("ready").apply();
        schedule(context);
    }

    static boolean alertsEnabled(Context context) {
        if (!context.getSharedPreferences("settings", MODE_PRIVATE).getBoolean("alerts", false)) return false;
        Set<String> apps = selectedApps(context);
        return apps == null || !apps.isEmpty();
    }

    static void schedule(Context c) {
        JobScheduler jobs = c.getSystemService(JobScheduler.class);
        if (!alertsEnabled(c)) {
            jobs.cancel(UPDATE_ID);
            return;
        }
        JobInfo existing = jobs.getPendingJob(UPDATE_ID);
        if (existing != null && existing.isPersisted()) jobs.cancel(UPDATE_ID);
        if (jobs.getPendingJob(UPDATE_ID) == null) {
            jobs.schedule(new JobInfo.Builder(UPDATE_ID, new ComponentName(c, Checks.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setRequiresStorageNotLow(true)
                    .setPeriodic(60 * 60 * 1000L)
                    .build());
        }
        c.getSystemService(NotificationManager.class)
            .createNotificationChannel(
                new NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT));
    }
    public boolean onStartJob(JobParameters p) {
        if (!alertsEnabled(this)) return false;
        AtomicBoolean stopped = new AtomicBoolean();
        canceled = stopped;
        Updates.worker.execute(() -> {
            boolean retry = false;
            try {
                if (stopped.get())
                    return;
                Updates updates = new Updates(this);
                updates.load(true);
                if (!stopped.get())
                    synchronized (Checks.class) {
                        notifyReady(updates);
                    }
            } catch (Exception e) {
                retry = true;
            }
            if (!stopped.get())
                jobFinished(p, retry);
        });
        return true;
    }
    public boolean onStopJob(JobParameters p) {
        if (canceled != null)
            canceled.set(true);
        return true;
    }

    void notifyReady(Updates updates) {
        if (!alertsEnabled(this)) return;
        Set<String> selected = selectedApps(this);
        StringBuilder available = new StringBuilder();
        ArrayList<Updates.App> pending = new ArrayList<>();
        for (Updates.App a : updates.apps.values())
            if (a.wanted() && a.problem == null && (selected == null || selected.contains(a.id))) {
                available.append(a.hash);
                pending.add(a);
            }
        SharedPreferences prefs = getSharedPreferences("notifications", MODE_PRIVATE);
        String marker = available.toString();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (pending.isEmpty()) {
            manager.cancel(UPDATE_ID);
            prefs.edit().remove("ready").apply();
            return;
        }
        if (!manager.areNotificationsEnabled() || marker.equals(prefs.getString("ready", "")))
            return;
        PendingIntent action = PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder notification = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(action)
            .setAutoCancel(true);
        if (pending.size() == 1) {
            notification.setContentTitle(UpdateChannel.PRIVATE ? getApplicationInfo().loadLabel(getPackageManager())
                    : MainActivity.PUBLIC_TITLE)
                .setContentText(pending.get(0).label + " has a new release.");
        } else {
            StringJoiner names = new StringJoiner(" · ");
            Notification.InboxStyle details = new Notification.InboxStyle();
            for (Updates.App app : pending) {
                names.add(app.notificationLabel);
                details.addLine(app.label);
            }
            notification.setContentTitle("Updates available")
                .setContentText(names.toString())
                .setStyle(details);
        }
        manager.notify(UPDATE_ID, notification.build());
        prefs.edit().putString("ready", marker).apply();
    }
}
