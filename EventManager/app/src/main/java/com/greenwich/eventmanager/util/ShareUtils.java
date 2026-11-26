package com.greenwich.eventmanager.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Writes a file into the app's private cache and shares it with other apps.
 *
 * Other apps cannot read our private files directly, so FileProvider gives them a
 * temporary content:// link with read permission for this share only
 * (safer than saving to public storage, and needs no storage permission).
 */
public final class ShareUtils {

    private static final String EXPORT_DIR = "exports";   // matches res/xml/file_paths.xml

    private ShareUtils() { }

    public static Uri writeToCache(Context ctx, String fileName, byte[] data) throws IOException {
        File dir = new File(ctx.getCacheDir(), EXPORT_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        File file = new File(dir, fileName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
        return FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", file);
    }

    public static void share(Activity activity, Uri uri, String mimeType, String chooserTitle) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(send, chooserTitle));
    }

    /** "Welcome new students!" -> "Welcome_new_students" (safe as a file name). */
    public static String safeFileName(String name) {
        String safe = name == null ? "" : name.replaceAll("[^A-Za-z0-9_-]+", "_")
                .replaceAll("^_+|_+$", "");
        if (safe.isEmpty()) safe = "event";
        return safe.length() > 40 ? safe.substring(0, 40) : safe;
    }
}