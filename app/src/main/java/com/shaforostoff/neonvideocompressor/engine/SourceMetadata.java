package com.shaforostoff.neonvideocompressor.engine;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.core.content.ContextCompat;

/**
 * Reads metadata from a source content Uri, and locates the MediaStore item
 * behind it, regardless of which picker or app produced it.
 */
public final class SourceMetadata {

    // Duration reported by MediaMetadataRetriever vs MediaStore's stored column
    // can differ slightly due to container/rounding, so allow some slack.
    private static final long DURATION_TOLERANCE_MS = 1500;

    /**
     * The system Photo Picker hands back a {@code content://media/picker/...} Uri
     * whose own {@code DISPLAY_NAME} can be a synthetic picker id (e.g.
     * "1000029282.mp4") rather than the real filename. {@link MediaStore#getMediaUri}
     * (API 33+) is the correct way to resolve it, but some OEM MediaProvider forks
     * throw for it; when that happens and the app holds video-library read
     * permission, fall back to {@link #findMediaStoreVideo}.
     */
    public static String queryDisplayName(Context context, Uri uri) {
        Uri resolved = resolvePickerUri(context, uri);
        if (!resolved.equals(uri)) {
            String name = queryOne(context, resolved);
            if (name != null) return name;
        }

        if (isPhotoPickerUri(uri) && hasVideoLibraryPermission(context)) {
            Uri match = findMediaStoreVideo(context, uri);
            String matched = match != null ? queryOne(context, match) : null;
            if (matched != null) return matched;
        }

        return queryOne(context, uri);
    }

    /** File size in bytes, or 0 if it can't be determined. */
    public static long querySize(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) {
        }
        try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (afd != null) {
                long len = afd.getLength();
                if (len >= 0) return len;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static String queryOne(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Uri resolvePickerUri(Context context, Uri uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return uri;
        try {
            Uri real = MediaStore.getMediaUri(context, uri);
            if (real != null) return real;
        } catch (Exception ignored) {
            // Not resolvable (e.g. a SAF document, or an OEM MediaProvider that
            // doesn't support this Uri).
        }
        return uri;
    }

    /** Whether READ_MEDIA_VIDEO is granted (API 33+; always false below). */
    public static boolean hasVideoLibraryPermission(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Locates the MediaStore video item behind a photo-picker or share-provider
     * uri. The picker redacts DISPLAY_NAME (it returns "<pickerId>.mp4"), so a
     * name lookup is useless — but for local items the picker id IS the
     * MediaStore {@code _ID} (verified on Android 15), so try that first and
     * sanity-check by SIZE. Otherwise match by exact SIZE (a strong signal on its
     * own), narrowed by duration when several items share it. Returns null on no
     * match or on any ambiguity: a wrong item is worse than none.
     */
    public static Uri findMediaStoreVideo(Context context, Uri uri) {
        long size = querySize(context, uri);
        Uri videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        try {
            Uri candidate = ContentUris.withAppendedId(videos, ContentUris.parseId(uri));
            try (Cursor c = context.getContentResolver().query(candidate,
                    new String[]{MediaStore.MediaColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst() && (size <= 0 || c.getLong(0) == size)) {
                    return candidate;
                }
            }
        } catch (Exception ignored) {
        }

        if (size <= 0) return null;
        try (Cursor c = context.getContentResolver().query(videos,
                new String[]{MediaStore.MediaColumns._ID, MediaStore.Video.Media.DURATION},
                MediaStore.MediaColumns.SIZE + "=?", new String[]{String.valueOf(size)}, null)) {
            if (c == null) return null;
            // The duration is only needed, and only probed, to break a tie.
            long durationMs = c.getCount() > 1 ? probeDurationAndBitrate(context, uri)[0] : 0;
            Uri match = null;
            while (c.moveToNext()) {
                if (durationMs > 0
                        && Math.abs(c.getLong(1) - durationMs) > DURATION_TOLERANCE_MS) continue;
                if (match != null) return null; // ambiguous — more than one plausible match
                match = ContentUris.withAppendedId(videos, c.getLong(0));
            }
            return match;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** @return {@code {durationMs, bitrateBps}}; 0 for whatever the container doesn't report. */
    public static long[] probeDurationAndBitrate(Context context, Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(context, uri);
            return new long[]{
                    parseLongOrZero(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)),
                    parseLongOrZero(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE))};
        } catch (Exception e) {
            return new long[]{0, 0};
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static long parseLongOrZero(String s) {
        try {
            return s != null ? Long.parseLong(s) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * True for photo-picker uris: content://media/picker/... and
     * content://media/picker_get_content/....
     */
    public static boolean isPhotoPickerUri(Uri uri) {
        String path = uri.getPath();
        return "media".equals(uri.getAuthority()) && path != null && path.startsWith("/picker");
    }

    private SourceMetadata() {
    }
}
