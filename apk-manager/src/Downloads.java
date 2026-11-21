package com.yinyangeq.apkmanager;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

final class Downloads {
    static final String MIME = "application/vnd.android.package-archive";

    static String fileName(Updates.App app) {
        return app.downloadName.isEmpty()
            ? app.asset.replaceFirst("\\.apk$", "") + "-" + app.code + ".apk"
            : app.downloadName;
    }

    private static String relativeFolder(Updates.App app) {
        return Environment.DIRECTORY_DOWNLOADS + "/YinYang Updates/"
            + app.asset.replaceFirst("\\.apk$", "") + "-" + app.code + "-" + app.hash.substring(0, 12);
    }

    static void openFolder(Context context, Updates.App app, Uri saved) {
        String storage = "com.android.externalstorage.documents";
        String directory = "primary:" + Environment.DIRECTORY_DOWNLOADS;
        if (Build.VERSION.SDK_INT >= 29 && "media".equals(saved.getAuthority())) {
            try (Cursor row = context.getContentResolver().query(saved,
                    new String[]{MediaStore.MediaColumns.RELATIVE_PATH}, null, null, null)) {
                if (row != null && row.moveToFirst()) {
                    String path = row.getString(0);
                    if ((relativeFolder(app) + "/").equals(path))
                        directory = "primary:" + relativeFolder(app);
                }
            }
        } else if (storage.equals(saved.getAuthority()) && DocumentsContract.isDocumentUri(context, saved)) {
            String document = DocumentsContract.getDocumentId(saved);
            int slash = document.lastIndexOf('/');
            if (document.startsWith("primary:") && slash > "primary:".length())
                directory = document.substring(0, slash);
            else if (document.startsWith("primary:"))
                directory = "primary:";
        }
        Uri folder = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
            directory);
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW)
                .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR));
        } catch (ActivityNotFoundException | SecurityException e) {
            context.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
        }
    }

    static Uri savedFile(Context context, Updates.App app) {
        String location = context.getSharedPreferences("downloads", Context.MODE_PRIVATE).getString(app.hash, null);
        if (location == null) return null;
        Uri uri = Uri.parse(location);
        try (Cursor row = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            return row != null && row.moveToFirst() && row.getLong(0) == app.size ? uri : null;
        } catch (IllegalArgumentException | SecurityException e) {
            return null;
        }
    }

    static void save(Context context, Updates updates, Updates.App app, Uri destination, boolean folder) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri target = folder ? null : destination;
        File verified = null;
        boolean stored = false;
        try {
            verified = updates.downloadVerified(app);
            if (folder) {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(destination, DocumentsContract.getTreeDocumentId(destination));
                target = DocumentsContract.createDocument(resolver, parent, MIME, fileName(app));
                if (target == null) throw new IOException("Cannot create a download.");
            } else if (target == null) {
                ContentValues entry = new ContentValues();
                entry.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(app));
                entry.put(MediaStore.MediaColumns.MIME_TYPE, MIME);
                entry.put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFolder(app));
                entry.put(MediaStore.MediaColumns.IS_PENDING, 1);
                target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, entry);
                if (target == null) throw new IOException("Cannot create a download.");
            }
            try (FileInputStream source = new FileInputStream(verified);
                 OutputStream output = resolver.openOutputStream(target, "w")) {
                if (output == null) throw new IOException("Cannot write the download.");
                byte[] buffer = new byte[65536];
                int length;
                while ((length = source.read(buffer)) != -1)
                    output.write(buffer, 0, length);
            }
            if (destination == null) {
                ContentValues complete = new ContentValues();
                complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(target, complete, null, null) != 1)
                    throw new IOException("Cannot finish saving the download.");
            }
            stored = true;
            context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
                .edit().putString(app.hash, target.toString()).apply();
        } finally {
            if (verified != null) verified.delete();
            if (!stored && target != null) {
                try { resolver.delete(target, null, null); }
                catch (UnsupportedOperationException | IllegalArgumentException | SecurityException ignored) {}
            }
        }
    }
}
