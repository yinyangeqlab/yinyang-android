package com.yinyangeq.qudelixprobe;

import java.util.Locale;

/** Estimates remaining runtime from observed battery drain for the active device link. */
final class BatteryEstimate {
    private static final String LABEL = "Time ETA · ";
    static final String UNAVAILABLE = LABEL + "Unavailable";
    private static final long MINUTE = 60000;
    private static final long MAX_SAMPLE_GAP = 90000;
    private static String activeLease;
    private static BatteryEstimate active;

    static synchronized BatteryEstimate forLink(String lease) {
        if (!lease.equals(activeLease)) {
            activeLease = lease;
            active = new BatteryEstimate();
        }
        return active;
    }

    private int startPercent = -1;
    private int previousPercent;
    private long startedAt, lastSampleAt;
    private long emptyAt = -1;

    synchronized String update(int percent, String powerState, long now) {
        if (powerState != null || percent < 0 || percent > 100) {
            startPercent = -1;
            return powerState == null ? UNAVAILABLE : LABEL + powerState;
        }
        if (startPercent < 0 || percent > previousPercent || now < lastSampleAt || now - lastSampleAt > MAX_SAMPLE_GAP) {
            startPercent = percent;
            previousPercent = percent;
            startedAt = now;
            emptyAt = -1;
        }
        int drop = startPercent - percent;
        if (drop >= 2 && now - startedAt >= 5 * MINUTE
                && (emptyAt < 0 || percent < previousPercent)) {
            emptyAt = now + Math.round((now - startedAt) * (double) percent / drop);
        }
        previousPercent = percent;
        lastSampleAt = now;
        if (percent == 0) return LABEL + "0:00";
        if (emptyAt <= now) return LABEL + "Estimating";
        long minutes = (emptyAt - now + MINUTE - 1) / MINUTE;
        return LABEL + String.format(Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60);
    }
}
