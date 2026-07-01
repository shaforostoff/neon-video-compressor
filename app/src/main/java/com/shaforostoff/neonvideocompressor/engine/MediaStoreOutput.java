package com.shaforostoff.neonvideocompressor.engine;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Publishes a finished file into the shared Movies/ collection via MediaStore. */
public final class MediaStoreOutput {

    public static Uri publish(Context ctx, File src, String displayName) throws IOException {
        ContentResolver cr = ctx.getContentResolver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES);
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri collection = MediaStore.Video.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri item = cr.insert(collection, values);
        if (item == null) {
            throw new IOException("MediaStore insert failed");
        }

        try (OutputStream os = cr.openOutputStream(item);
             InputStream is = new FileInputStream(src)) {
            if (os == null) throw new IOException("Cannot open output stream");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        } catch (IOException e) {
            cr.delete(item, null, null);
            throw e;
        }

        values.clear();
        values.put(MediaStore.Video.Media.IS_PENDING, 0);
        cr.update(item, values, null, null);
        return item;
    }

    private MediaStoreOutput() {
    }
}
