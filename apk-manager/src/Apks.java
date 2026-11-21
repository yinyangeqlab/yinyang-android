package com.yinyangeq.apkmanager;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.List;

/** Only individually granted, verified, immutable APK objects are accessible to the package installer. */
public final class Apks extends ContentProvider {
    static final String MIME = "application/vnd.android.package-archive";
    static Uri uri(Context ctx, String hash) {
        return new Uri.Builder()
            .scheme("content")
            .authority(ctx.getPackageName() + ".files")
            .appendPath(hash + ".apk")
            .build();
    }
    private File path(Uri uri) {
        List<String> parts = uri.getPathSegments();
        if (parts.size()!=1 || !parts.get(0).matches("[0-9a-f]{64}\\.apk"))
            throw new IllegalArgumentException("Unknown build");
        return new File(new File(getContext().getFilesDir(),"apks"),parts.get(0));
    }
    public boolean onCreate() {
        return true;
    }
    public String getType(Uri uri) {
        path(uri);
        return MIME;
    }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode))
            throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(path(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        File f = path(uri);
        String[] cols = projection == null ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                                           : projection;
        MatrixCursor cursor = new MatrixCursor(cols);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col))
                row.add(f.getName());
            else if (OpenableColumns.SIZE.equals(col))
                row.add(f.length());
            else
                row.add(null);
        }
        return cursor;
    }
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }
    public int delete(Uri uri, String where, String[] args) {
        throw new UnsupportedOperationException();
    }
    public int update(Uri uri, ContentValues values, String where, String[] args) {
        throw new UnsupportedOperationException();
    }
}
