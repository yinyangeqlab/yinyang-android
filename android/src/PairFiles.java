package com.yinyangeq.qudelixprobe;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.json.JSONException;
import org.json.JSONObject;

/** A portable pair contains the two PEQ texts and a title. */
final class PairFiles {
    private static final int IMPORT = 891, EXPORT = 892;
    private static final int MAX_BYTES = 65536;
    private static final String FORMAT = "yinyang-eq-pair-v1";
    private final Activity activity;
    private final Consumer<String> report;
    private String export;

    static final class Pair {
        final String title, main, match;
        Pair(String title, String main, String match) {
            String clean = title.replaceAll("\\p{Cntrl}", " ").trim();
            this.title = clean.isEmpty() ? "Paired EQ" : clean.substring(0, Math.min(48, clean.length()));
            this.main = main;
            this.match = match;
        }
        Peq[] forOriginal() {
            return new Peq[]{Peq.flat(0).importText(main),Peq.flat(1).importText(match)};
        }
        PlusPreset.Channel[] forPlus() {
            PlusPreset.Channel standard = PlusPreset.parse(main,false,30)[0];
            PlusPreset.Channel[] ears = PlusPreset.parse(match,true,20);
            PlusPreset.banks(PlusPreset.Mode.LAYERED,standard,ears[0],ears[1],true);
            return new PlusPreset.Channel[]{standard,ears[0],ears[1]};
        }
        String encode() {
            try {
                return new JSONObject().put("format", FORMAT).put("name", title)
                        .put("main", main).put("match", match).toString();
            } catch (JSONException impossible) {
                throw new IllegalArgumentException("Could not prepare the paired EQ.", impossible);
            }
        }
        static Pair decode(String text) throws IOException {
            try {
                JSONObject data = new JSONObject(text);
                if (FORMAT.equals(data.getString("format")))
                    return new Pair(data.getString("name"), data.getString("main"), data.getString("match"));
            } catch (JSONException invalid) { /* The same message covers invalid and unsupported files. */ }
            throw new IOException("Choose a YYEQ paired EQ file.");
        }
    }

    PairFiles(Activity activity, Consumer<String> report, Bundle state) {
        this.activity = activity;
        this.report = report;
        if (state != null) export = state.getString("pairedEqExport");
    }
    void saveState(Bundle state) {
        state.putString("pairedEqExport", export);
    }
    void pick() {
        open(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT);
    }
    void write(Pair pair) {
        export = pair.encode();
        String filename = pair.title.replaceAll("[^a-zA-Z0-9 _-]", "-") + ".yyeq-pair.json";
        open(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, filename), EXPORT);
    }
    private void open(Intent intent, int request) {
        try { activity.startActivityForResult(intent, request); }
        catch (android.content.ActivityNotFoundException missing) {
            export = null;
            report.accept("Android's file picker is unavailable.");
        }
    }

    boolean result(int request, int result, Intent data, Executor worker, Consumer<Pair> accept) {
        if (request != IMPORT && request != EXPORT) return false;
        String contents = export;
        if (request == EXPORT) export = null;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri uri = data.getData();
        worker.execute(() -> {
            try {
                if (request == IMPORT) {
                    Pair pair = Pair.decode(read(uri));
                    deliver(() -> {
                        try { accept.accept(pair); }
                        catch (IllegalArgumentException incompatible) { report.accept(incompatible.getMessage()); }
                    });
                } else {
                    if (contents == null) throw new IOException("Choose Export again to prepare the pair.");
                    try (OutputStream out = activity.getContentResolver().openOutputStream(uri, "wt")) {
                        if (out == null) throw new IOException("Could not open the paired EQ destination.");
                        out.write(contents.getBytes(StandardCharsets.UTF_8));
                    }
                    deliver(() -> report.accept("Paired EQ file exported."));
                }
            } catch (IOException | SecurityException failure) {
                deliver(() -> report.accept(failure.getMessage() == null ? "Could not open the paired EQ file." : failure.getMessage()));
            }
        });
        return true;
    }

    private void deliver(Runnable action) {
        activity.runOnUiThread(() -> {
            if (!activity.isDestroyed() && !activity.isFinishing()) action.run();
        });
    }

    private String read(Uri uri) throws IOException {
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open the paired EQ file.");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] block = new byte[2048];
            int count;
            while ((count = in.read(block)) != -1) {
                if (bytes.size() + count > MAX_BYTES) throw new IOException("Choose a paired EQ file under 64 KB.");
                bytes.write(block, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static String uniqueTitle(String title, Predicate<String> used) {
        String name = title;
        for (int suffix = 2; used.test(name); suffix++) {
            String serial = " (" + suffix + ")";
            name = title.substring(0, Math.min(title.length(), 48 - serial.length())) + serial;
        }
        return name;
    }
}
