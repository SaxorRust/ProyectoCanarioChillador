package com.canario.iot;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Proveedor de contenidos nativo (ContentProvider) para compartir archivos CSV/JSON
 * de auditoría con aplicaciones externas (Quick Share, WhatsApp, Drive, Gmail)
 * sin violar Scoped Storage ni requerir dependencias externas de AndroidX.
 */
public class CanarioFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.canario.iot.provider";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = getFileForUri(uri);
        if (file == null || !file.exists()) {
            throw new FileNotFoundException("Archivo de telemetría no encontrado: " + uri);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String path = uri.getPath();
        if (path != null && path.endsWith(".csv")) return "text/csv";
        if (path != null && path.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = getFileForUri(uri);
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(cols);
        if (file != null && file.exists()) {
            MatrixCursor.RowBuilder builder = cursor.newRow();
            for (String col : cols) {
                if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                    builder.add(file.getName());
                } else if (OpenableColumns.SIZE.equals(col)) {
                    builder.add(file.length());
                } else {
                    builder.add(null);
                }
            }
        }
        return cursor;
    }

    private File getFileForUri(Uri uri) {
        if (getContext() == null) return null;
        String path = uri.getPath();
        if (path == null) return null;
        // Sanitizar estrictamente para mitigar cualquier riesgo de path traversal (OWASP Top 10)
        String fileName = new File(path).getName();
        if (fileName.isEmpty() || fileName.contains("..")) return null;

        File external = new File(getContext().getExternalFilesDir(null), fileName);
        if (external.exists()) return external;

        File internal = new File(getContext().getFilesDir(), fileName);
        if (internal.exists()) return internal;

        File cache = new File(getContext().getCacheDir(), fileName);
        if (cache.exists()) return cache;

        return external;
    }

    public static Uri getUriForFile(File file) {
        return Uri.parse("content://" + AUTHORITY + "/" + file.getName());
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
