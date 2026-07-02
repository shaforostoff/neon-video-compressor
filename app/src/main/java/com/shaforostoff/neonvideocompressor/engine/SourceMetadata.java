package com.shaforostoff.neonvideocompressor.engine;

import android.Manifest;
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

/** Reads display metadata from a source content Uri, regardless of which picker produced it. */
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
     * permission, fall back to matching the item by exact file size + duration
     * against {@code MediaStore.Video.Media} — bailing out (never guessing) if
     * more than one row matches, since a wrong name is worse than a numeric one.
     */
    public static String queryDisplayName(Context context, Uri uri) {
        Uri resolved = resolvePickerUri(context, uri);
        if (!resolved.equals(uri)) {
            String name = queryOne(context, resolved);
            if (name != null) return name;
        }

        if (isLocalPhotoPickerUri(uri) && hasVideoLibraryPermission(context)) {
            String matched = matchBySizeAndDuration(context, uri);
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

    private static boolean hasVideoLibraryPermission(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Matches the picker item against MediaStore.Video.Media by exact file size
     * (a strong signal on its own) confirmed by duration within a small
     * tolerance. Returns null on no match or on any ambiguity — a wrong name is
     * worse than falling back to the numeric one.
     */
    private static String matchBySizeAndDuration(Context context, Uri pickerUri) {
        long size = querySize(context, pickerUri);
        if (size <= 0) return null;
        long durationMs = queryDurationMs(context, pickerUri);

        String[] projection = {
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
        };
        String selection = MediaStore.Video.Media.SIZE + "=?";
        String[] selectionArgs = {String.valueOf(size)};

        try (Cursor c = context.getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null)) {
            if (c == null) return null;
            int nameIdx = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
            int durationIdx = c.getColumnIndex(MediaStore.Video.Media.DURATION);
            if (nameIdx < 0) return null;

            String bestName = null;
            while (c.moveToNext()) {
                if (durationMs > 0 && durationIdx >= 0) {
                    long candidateDuration = c.getLong(durationIdx);
                    if (Math.abs(candidateDuration - durationMs) > DURATION_TOLERANCE_MS) continue;
                }
                if (bestName != null) return null; // ambiguous — more than one plausible match
                bestName = c.getString(nameIdx);
            }
            return bestName;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long queryDurationMs(Context context, Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(context, uri);
            String ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return ms != null ? Long.parseLong(ms) : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** True for content://media/picker/<user>/<local-provider-authority>/media/<id>. */
    private static boolean isLocalPhotoPickerUri(Uri uri) {
        if (!"media".equals(uri.getAuthority())) return false;
        java.util.List<String> segments = uri.getPathSegments();
        return !segments.isEmpty() && "picker".equals(segments.get(0));
    }

    private SourceMetadata() {
    }
}
