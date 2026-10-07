package com.shaforostoff.neonvideocompressor.engine;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.IOException;

/**
 * Manages the shared MediaStore item a conversion writes into: video files go to
 * Movies/, audio-only files to Music/. The item is created up front as pending,
 * muxed into directly (via its "rw" fd), then finalized — no intermediate copy.
 */
public final class MediaStoreOutput {

    public static String mimeType(boolean audioOnly) {
        return audioOnly ? "audio/mp4" : "video/mp4";
    }

    /** {@link #createPending(Context, String, boolean, String, String)} into Movies/ or Music/. */
    public static Uri createPending(Context ctx, String displayName, boolean audioOnly)
            throws IOException {
        return createPending(ctx, displayName, audioOnly, MediaStore.VOLUME_EXTERNAL_PRIMARY,
                audioOnly ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES);
    }

    /**
     * Insert a still-hidden ({@code IS_PENDING=1}) item and return its Uri. Open
     * it {@code "rw"} to get a seekable fd to mux into, then call
     * {@link #finalizePending} on success or {@link ContentResolver#delete} on
     * failure.
     */
    public static Uri createPending(Context ctx, String displayName, boolean audioOnly,
                                    String volume, String relativePath) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType(audioOnly));
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri collection = audioOnly
                ? MediaStore.Audio.Media.getContentUri(volume)
                : MediaStore.Video.Media.getContentUri(volume);
        Uri item = ctx.getContentResolver().insert(collection, values);
        if (item == null) {
            throw new IOException("MediaStore insert failed");
        }
        return item;
    }

    /** Clear {@code IS_PENDING} so the finished item becomes visible to other apps. */
    public static void finalizePending(Context ctx, Uri item) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        ctx.getContentResolver().update(item, values, null, null);
    }

    private MediaStoreOutput() {
    }
}
