package com.savchenko.smshare;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Віддає вибраний файл іншим застосункам (системному Bluetooth-обміну), без інтернету. */
public class ShareProvider extends ContentProvider {

    static final String AUTHORITY = "com.savchenko.smshare.files";

    static Uri uriFor(String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(name == null ? "file" : name).build();
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri u, String[] projection, String sel, String[] args, String order) {
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = Share.name;
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = Share.size >= 0 ? Share.size : null;
        }
        c.addRow(row);
        return c;
    }

    @Override public String getType(Uri u) { return Share.mime != null ? Share.mime : "application/octet-stream"; }

    @Override
    public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        File f = Share.file;
        Uri src = Share.uri;
        if (f != null) return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        if (src != null) {
            ParcelFileDescriptor pfd = getContext().getContentResolver().openFileDescriptor(src, "r");
            if (pfd != null) return pfd;
        }
        throw new FileNotFoundException("Файл не вибрано");
    }

    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
