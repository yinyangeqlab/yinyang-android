package com.yinyangeq.qudelixprobe;

import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Exports generated filters through the system document picker. */
final class FitExports {
    static final int PICK_DESTINATION = 36;
    private static final String TEXT = "text/plain";
    private final String folder;
    private final Map<String, String> files = new LinkedHashMap<>();

    FitExports(String payload) throws JSONException {
        JSONObject export = new JSONObject(payload);
        folder = filename(export.getString("name"));
        JSONArray entries = export.getJSONArray("files");
        if (entries.length() == 0 || entries.length() > 100) throw new JSONException("Invalid file count.");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String name = filename(entry.getString("name")) + ".txt";
            String preset = entry.getString("text");
            if (preset.isEmpty() || preset.length() > 32768 || files.put(name, preset) != null)
                throw new JSONException("Invalid preset files.");
        }
    }

    private static String filename(String text) {
        String name = text.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "-").trim();
        int end = name.offsetByCodePoints(0, Math.min(90, name.codePointCount(0, name.length())));
        name = name.substring(0, end).replaceFirst("^[.]+|[. ]+$", "");
        return name.isEmpty() ? "YinYang EQ" : name;
    }

    Intent picker() {
        if (files.size() > 1) return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(TEXT).putExtra(Intent.EXTRA_TITLE, files.keySet().iterator().next());
    }

    String save(ContentResolver resolver, Uri destination) {
        int saved = 0;
        try {
            if (files.size() == 1) {
                write(resolver, destination, files.values().iterator().next());
                return "Preset TXT saved.";
            }
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(destination,
                    DocumentsContract.getTreeDocumentId(destination));
            Uri directory = DocumentsContract.createDocument(resolver, parent,
                    DocumentsContract.Document.MIME_TYPE_DIR, folder);
            if (directory == null) throw new IOException("Could not create the preset folder.");
            for (Map.Entry<String, String> file : files.entrySet()) {
                Uri document = DocumentsContract.createDocument(resolver, directory, TEXT, file.getKey());
                if (document == null) throw new IOException("Could not create the preset file.");
                write(resolver, document, file.getValue());
                saved++;
            }
            return saved + " preset TXT files saved.";
        } catch (IOException | RuntimeException failure) {
            return saved == 0 ? "Could not save the preset files. Choose another location."
                    : "Saved " + saved + " of " + files.size() + " preset files. Try another location for the full export.";
        }
    }

    private static void write(ContentResolver resolver, Uri document, String text) throws IOException {
        try (OutputStream out = resolver.openOutputStream(document, "wt")) {
            if (out == null) throw new IOException("Could not open the preset file.");
            out.write((text.trim() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException error) {
            try { DocumentsContract.deleteDocument(resolver, document); }
            catch (IOException | RuntimeException ignored) { /* Some providers cannot delete a new document. */ }
            throw error;
        }
    }
}
