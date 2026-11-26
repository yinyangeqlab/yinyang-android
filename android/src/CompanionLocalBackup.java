package com.yinyangeq.qudelixprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Backs up user-saved phone records in every device workspace. */
final class CompanionLocalBackup {
    private static final String FORMAT = "yinyang-companion-local-library";
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_CURVE = 4 * 1024 * 1024;
    private static final String MEASUREMENTS = "attached-curves";
    private static final String PAIRS = "paired-presets";
    private static final String PLUS = "plus-presets-v1";
    private static final Pattern SCOPE = Pattern.compile("unlinked|(?:5k|plus)-[0-9A-F]{12}");
    private static final Object GATE = new Object();
    private final Context app;

    CompanionLocalBackup(Context context) { app = context.getApplicationContext(); }

    private SharedPreferences store(String scope, String dataset) {
        return app.getSharedPreferences(scope + "." + dataset, Context.MODE_PRIVATE);
    }

    private Set<String> scopes() {
        Set<String> result = new TreeSet<>();
        File folder = new File(app.getDataDir(), "shared_prefs");
        String[] names = folder.list();
        if (names == null) return result;
        for (String name : names) {
            int boundary = name.indexOf('.');
            if (boundary < 0) continue;
            String scope = name.substring(0, boundary);
            String suffix = name.substring(boundary + 1);
            if (SCOPE.matcher(scope).matches() &&
                    (suffix.equals(MEASUREMENTS + ".xml") || suffix.equals(PAIRS + ".xml") || suffix.equals(PLUS + ".xml"))) {
                result.add(scope);
            }
        }
        return result;
    }

    boolean hasEntries() {
        for (String scope : scopes()) {
            if (!store(scope, MEASUREMENTS).getAll().isEmpty()) return true;
            if (store(scope, PAIRS).getAll().keySet().stream().anyMatch(key -> key.startsWith("pair/"))) return true;
            if (store(scope, PLUS).getAll().keySet().stream().anyMatch(key -> key.startsWith("preset:"))) return true;
        }
        return false;
    }

    byte[] exportLibrary() throws IOException, JSONException {
        JSONObject workspaces = new JSONObject();
        for (String scope : scopes()) {
            JSONObject pairs = savedPresets(scope, PAIRS, "pair/");
            JSONObject plus = savedPresets(scope, PLUS, "preset:");
            JSONObject measurements = new JSONObject();
            for (String id : store(scope, MEASUREMENTS).getAll().keySet()) {
                require(id.matches("[0-9a-f]{64}"));
                try (InputStream curve = curveFile(scope, id).openRead()) {
                    measurements.put(id, read(curve, MAX_CURVE));
                }
            }
            if (pairs.length() + plus.length() + measurements.length() > 0) {
                workspaces.put(scope, new JSONObject().put("pairedPresets", pairs)
                        .put("plusPresets", plus).put("measurements", measurements));
            }
        }
        require(workspaces.length() > 0, "Save a preset or measurement before backing up the library.");
        validate(workspaces);
        byte[] contents = new JSONObject().put("format", FORMAT).put("version", VERSION)
                .put("workspaces", workspaces).toString().getBytes(StandardCharsets.UTF_8);
        require(contents.length <= MAX_BYTES, "The library exceeds the 32 MB backup limit.");
        return contents;
    }

    private JSONObject savedPresets(String scope, String dataset, String prefix) throws JSONException {
        JSONObject result = new JSONObject();
        for (Map.Entry<String, ?> entry : store(scope, dataset).getAll().entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return result;
    }

    int restore(InputStream input) throws IOException, JSONException {
        JSONTokener source = new JSONTokener(read(input, MAX_BYTES));
        Object parsed = source.nextValue();
        require(parsed instanceof JSONObject && source.nextClean() == '\u0000');
        JSONObject backup = (JSONObject) parsed;
        require(backup.length() == 3 && FORMAT.equals(backup.opt("format")));
        require(Integer.valueOf(VERSION).equals(backup.opt("version")), "This backup needs a different Companion version.");
        JSONObject workspaces = backup.getJSONObject("workspaces");
        validate(workspaces);
        synchronized (GATE) {
            int count = 0;
            for (String scope : keys(workspaces)) {
                JSONObject workspace = workspaces.getJSONObject(scope);
                count += restorePresets(scope, PAIRS, "pair/", workspace.getJSONObject("pairedPresets"));
                count += restorePresets(scope, PLUS, "preset:", workspace.getJSONObject("plusPresets"));
                count += restoreMeasurements(scope, workspace.getJSONObject("measurements"));
            }
            return count;
        }
    }

    private static void validate(JSONObject workspaces) throws JSONException, IOException {
        for (String scope : keys(workspaces)) {
            require(SCOPE.matcher(scope).matches());
            JSONObject workspace = workspaces.getJSONObject(scope);
            require(workspace.length() == 3);
            JSONObject pairs = workspace.getJSONObject("pairedPresets");
            for (String name : keys(pairs)) {
                require(!name.trim().isEmpty());
                JSONArray stages = new JSONArray(text(pairs, name));
                require(stages.length() == 2);
                for (int group = 0; group < 2; group++) {
                    JSONObject stage = stages.getJSONObject(group);
                    text(stage, "name");
                    require(new Peq(group, Base64.getDecoder().decode(text(stage, "eq"))).parametric());
                }
            }
            JSONObject plus = workspace.getJSONObject("plusPresets");
            for (String name : keys(plus)) {
                require(!name.trim().isEmpty());
                JSONObject preset = new JSONObject(text(plus, name));
                text(preset, "name");
                PlusPreset.Mode.valueOf(text(preset, "mode"));
                PlusPreset.parse(text(preset, "shared"), false, 30);
                PlusPreset.parse(text(preset, "match"), true, 20);
                if (preset.has("inactiveMain")) {
                    String inactive = text(preset, "inactiveMain");
                    if (!inactive.isEmpty()) PlusPreset.parse(inactive, false, 30);
                }
            }
            JSONObject measurements = workspace.getJSONObject("measurements");
            for (String id : keys(measurements)) {
                String data = text(measurements, id);
                require(data.getBytes(StandardCharsets.UTF_8).length <= MAX_CURVE && matchesCurveId(data, id));
                JSONObject curve = new JSONObject(data);
                String type = text(curve, "type");
                require("ie".equals(type) || "oe".equals(type));
                require(!text(curve, "name").isEmpty());
                validatePoints(curve.getJSONArray("points"));
                if (curve.has("leftPoints") || curve.has("rightPoints")) {
                    validatePoints(curve.getJSONArray("leftPoints"));
                    validatePoints(curve.getJSONArray("rightPoints"));
                }
            }
        }
    }

    private static boolean matchesCurveId(String data, String id) throws IOException {
        try {
            return OfflineFitter.hash(data).equals(id);
        } catch (Exception error) {
            throw new IOException("Could not verify the measurement backup.", error);
        }
    }

    private static void validatePoints(JSONArray points) throws JSONException, IOException {
        require(points.length() >= 2);
        for (int i = 0; i < points.length(); i++) {
            JSONArray row = points.getJSONArray(i);
            require(row.length() == 2 && row.get(0) instanceof Number && row.get(1) instanceof Number &&
                    Double.isFinite(row.getDouble(0)) && Double.isFinite(row.getDouble(1)));
        }
    }

    private int restorePresets(String scope, String dataset, String prefix, JSONObject presets)
            throws JSONException, IOException {
        SharedPreferences target = store(scope, dataset);
        Map<String, Object> existing = new HashMap<>(target.getAll());
        SharedPreferences.Editor edits = target.edit();
        List<String> added = new ArrayList<>();
        for (String name : keys(presets)) {
            String data = text(presets, name);
            if (PAIRS.equals(dataset)) {
                JSONArray stages = new JSONArray(data);
                for (int i = 0; i < stages.length(); i++) stages.getJSONObject(i).remove("uri");
                data = stages.toString();
                String original = target.getString(prefix + name, null);
                if (original != null) {
                    JSONArray active = new JSONArray(original);
                    for (int i = 0; i < active.length(); i++) active.getJSONObject(i).remove("uri");
                    if (data.equals(active.toString())) continue;
                }
            }
            String key = prefix + name;
            for (int copy = 1; existing.containsKey(key) && !data.equals(existing.get(key)); copy++) {
                key = prefix + name + " (restored " + copy + ")";
            }
            if (!existing.containsKey(key)) {
                existing.put(key, data);
                edits.putString(key, data);
                added.add(key);
            }
        }
        commit(target, edits, added);
        return added.size();
    }

    private int restoreMeasurements(String scope, JSONObject curves) throws JSONException, IOException {
        SharedPreferences target = store(scope, MEASUREMENTS);
        SharedPreferences.Editor edits = target.edit();
        List<String> added = new ArrayList<>();
        for (String id : keys(curves)) {
            AtomicFile file = curveFile(scope, id);
            if (target.contains(id) && file.getBaseFile().isFile()) continue;
            String data = text(curves, id);
            JSONObject value = new JSONObject(data);
            File directory = file.getBaseFile().getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IOException("Could not open the measurement library.");
            }
            FileOutputStream out = file.startWrite();
            try {
                out.write(data.getBytes(StandardCharsets.UTF_8));
                file.finishWrite(out);
            } catch (IOException error) {
                file.failWrite(out);
                throw error;
            }
            if (!target.contains(id)) {
                edits.putString(id, new JSONObject().put("id", id).put("type", value.getString("type"))
                        .put("name", value.getString("name")).toString());
                added.add(id);
            }
        }
        commit(target, edits, added);
        return added.size();
    }

    private static void commit(SharedPreferences store, SharedPreferences.Editor edits, List<String> keys)
            throws IOException {
        if (keys.isEmpty() || edits.commit()) return;
        SharedPreferences.Editor rollback = store.edit();
        keys.forEach(rollback::remove);
        rollback.apply();
        throw new IOException("Restore stopped while saving. Choose the same backup to finish.");
    }

    private AtomicFile curveFile(String scope, String id) {
        return new AtomicFile(new File(new File(app.getFilesDir(), scope), "curve-" + id));
    }

    private static List<String> keys(JSONObject object) {
        List<String> keys = new ArrayList<>();
        object.keys().forEachRemaining(keys::add);
        Collections.sort(keys);
        return keys;
    }

    private static String text(JSONObject object, String key) throws JSONException, IOException {
        Object value = object.get(key);
        require(value instanceof String);
        return (String) value;
    }

    private static String read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        for (int length; (length = input.read(block)) != -1; ) {
            if (output.size() + length > limit) throw new IOException("This file exceeds the backup size limit.");
            output.write(block, 0, length);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static void require(boolean condition) throws IOException {
        require(condition, "Choose a backup that matches the Companion library format.");
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
