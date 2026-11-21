package com.yinyangeq.qudelixprobe;

import android.content.SharedPreferences;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Previous saved versions on this phone, scoped by paired device, EQ bank and slot. */
final class PresetHistory {
    private static final int DEPTH = 8;
    private final SharedPreferences store;

    PresetHistory(SharedPreferences store) { this.store = store; }

    static final class Version {
        final String name;
        final Peq eq;
        final boolean swapped;
        final long time;

        Version(String name, Peq eq, boolean swapped, long time) {
            this.name = name;
            this.eq = eq;
            this.swapped = swapped;
            this.time = time;
        }
    }

    List<Version> versions(String bank, int slot, int group) throws IOException {
        try {
            JSONArray rows = new JSONArray(store.getString(bank+"."+slot,"[]"));
            List<Version> versions = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                Peq eq = new Peq(group,Base64.getDecoder().decode(row.getString("peq")));
                versions.add(new Version(row.getString("name"),eq,row.getBoolean("swapped"),row.getLong("time")));
            }
            return versions;
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("Could not open the local preset history.",error);
        }
    }

    void remember(String bank, int slot, String name, Peq previous, boolean swapped) throws IOException {
        List<Version> versions = versions(bank,slot,previous.group);
        if (!versions.isEmpty()) {
            Version last = versions.get(0);
            if (last.eq.same(previous) && last.swapped == swapped && last.name.equals(name)) return;
        }
        versions.add(0,new Version(name,previous,swapped,System.currentTimeMillis()));
        JSONArray rows = new JSONArray();
        try {
            for (Version row : versions.subList(0,Math.min(DEPTH,versions.size()))) {
                rows.put(new JSONObject().put("name",row.name).put("swapped",row.swapped).put("time",row.time)
                        .put("peq",Base64.getEncoder().encodeToString(row.eq.bytes)));
            }
        } catch (JSONException error) {
            throw new IOException("Could not store the previous preset version.",error);
        }
        if (!store.edit().putString(bank+"."+slot,rows.toString()).commit())
            throw new IOException("Could not store the previous preset version. The slot was not overwritten.");
    }
}
