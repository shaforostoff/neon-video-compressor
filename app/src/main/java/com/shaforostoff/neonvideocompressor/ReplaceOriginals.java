package com.shaforostoff.neonvideocompressor;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.FileUtils;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shaforostoff.neonvideocompressor.engine.MediaStoreOutput;
import com.shaforostoff.neonvideocompressor.engine.SourceMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.function.IntConsumer;

/**
 * "Replace original(s)": deletes the source of each converted file and moves the
 * compressed output into the source's folder under the source's name.
 *
 * <p>Kept apart from {@link ProgressActivity} because this is the code that
 * deletes user files. It works on the activity's own result lists — the outputs
 * and, parallel to them, each output's source ({@code null} when it must not be
 * replaced). A replaced source is set to {@code null} and reported through
 * {@code onReplaced}; a moved output's uri is updated in place.
 *
 * <p>Registers activity-result launchers, so it must be created before the
 * activity is started (a field initializer is the natural place).
 */
final class ReplaceOriginals {

    private static final String TAG = "ReplaceOriginal";

    private final AppCompatActivity activity;
    private final ArrayList<Uri> outputs;
    private final ArrayList<Uri> sources;
    private final IntConsumer onReplaced;
    private boolean audioOnly;

    // One queued "replace a source with its compressed output" operation.
    private static final class ReplaceItem {
        final int index;    // position in outputs
        final Uri output;   // compressed, app-owned file to move into place
        final Uri mediaUri; // resolved source MediaStore item to delete
        String volume, relPath, baseName; // source location, captured before delete
        ReplaceItem(int index, Uri output, Uri mediaUri) {
            this.index = index;
            this.output = output;
            this.mediaUri = mediaUri;
        }
    }

    // Sources whose delete is awaiting the system consent dialog; their outputs
    // are moved into place once consent is granted.
    private final ArrayList<ReplaceItem> pendingReplace = new ArrayList<>();
    private int replaceMediaCount;  // how many MediaStore sources this run targets
    private int api29Index;         // API 29: index currently being deleted one-by-one

    // R+ (API 30+): a single consent dialog deletes every source at once.
    private final ActivityResultLauncher<IntentSenderRequest> batchDeleteLauncher;
    // A shared video (e.g. from Google Photos) arrives without library access,
    // so the original can't be read to name it or to put the output in its
    // folder. Ask once on Replace; continue whatever the answer.
    private final ActivityResultLauncher<String> libraryPermissionLauncher;
    // API 29: consent is per-file; on grant, re-issue that delete and continue.
    private final ActivityResultLauncher<IntentSenderRequest> singleDeleteLauncher;

    ReplaceOriginals(AppCompatActivity activity, ArrayList<Uri> outputs, ArrayList<Uri> sources,
                     IntConsumer onReplaced) {
        this.activity = activity;
        this.outputs = outputs;
        this.sources = sources;
        this.onReplaced = onReplaced;

        batchDeleteLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                    if (result.getResultCode() != Activity.RESULT_OK) { // declined: nothing deleted
                        pendingReplace.clear();
                        return;
                    }
                    for (ReplaceItem it : pendingReplace) applyReplaceMove(it);
                    pendingReplace.clear();
                    finishReplace();
                });
        libraryPermissionLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> confirmReplace());
        singleDeleteLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && api29Index < pendingReplace.size()) {
                        try {
                            deleteThenMove(pendingReplace.get(api29Index));
                        } catch (Exception e) {
                            toastReplaceFailed();
                        }
                    }
                    deleteNextApi29(api29Index + 1);
                });
    }

    /** Number of outputs whose source is still known and replaceable (never partial). */
    int replaceableCount() {
        int n = 0;
        for (int i = 0; i < outputs.size(); i++) {
            if (i < sources.size() && sources.get(i) != null) n++;
        }
        return n;
    }

    /** Entry point for the Replace button: asks for library access if needed, then confirms. */
    void start(boolean audioOnly) {
        this.audioOnly = audioOnly;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !SourceMetadata.hasVideoLibraryPermission(activity)) {
            libraryPermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            confirmReplace();
        }
    }

    private Uri firstReplaceableSource() {
        for (int i = 0; i < outputs.size(); i++) {
            if (i < sources.size() && sources.get(i) != null) {
                return sources.get(i);
            }
        }
        return null;
    }

    private void confirmReplace() {
        int count = replaceableCount();
        if (count == 0) return;
        String title, message;
        if (count == 1) {
            // Picker / share-provider uris may redact the display name; resolve
            // the real item for a recognizable name in the dialog.
            Uri src = firstReplaceableSource();
            Uri media = src != null ? resolveToMediaUri(src) : null;
            Uri nameSource = media != null ? media : src;
            String name = nameSource != null
                    ? SourceMetadata.queryDisplayName(activity, nameSource) : null;
            title = activity.getString(R.string.replace_confirm_title);
            message = activity.getString(R.string.replace_confirm_message,
                    name != null ? name : activity.getString(R.string.replace_this_video));
        } else {
            title = activity.getString(R.string.replace_confirm_title_batch);
            message = activity.getString(R.string.replace_confirm_message_batch, count);
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.replace_confirm_delete, (d, w) -> startReplace())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Builds and runs the replace plan: resolve each source to a MediaStore item,
     * capture its location, then delete (one batched consent on R+, per-file on
     * API 29) and move the compressed output into place. SAF documents with no
     * MediaStore backing are deleted directly (the compressed copy stays put).
     */
    private void startReplace() {
        pendingReplace.clear();
        for (int i = 0; i < outputs.size(); i++) {
            Uri source = i < sources.size() ? sources.get(i) : null;
            if (source == null) continue;
            Uri media = resolveToMediaUri(source);
            if (media == null) {
                deleteSafDocument(source, i);
                continue;
            }
            ReplaceItem item = new ReplaceItem(i, outputs.get(i), media);
            captureLocation(item);
            pendingReplace.add(item);
        }
        replaceMediaCount = pendingReplace.size();
        if (pendingReplace.isEmpty()) return; // SAF-only case handled inline above

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ArrayList<Uri> uris = new ArrayList<>();
            for (ReplaceItem it : pendingReplace) uris.add(it.mediaUri);
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(activity.getContentResolver(), uris);
                batchDeleteLauncher.launch(
                        new IntentSenderRequest.Builder(pi.getIntentSender()).build());
            } catch (Exception e) {
                Log.w(TAG, "batch delete request failed", e);
                toastReplaceFailed();
                pendingReplace.clear();
            }
        } else {
            deleteNextApi29(0);
        }
    }

    /** API 29: delete one source at a time, requesting consent when the system demands it. */
    private void deleteNextApi29(int index) {
        if (index >= pendingReplace.size()) {
            pendingReplace.clear();
            finishReplace();
            return;
        }
        api29Index = index;
        try {
            deleteThenMove(pendingReplace.get(index));
            deleteNextApi29(index + 1);
        } catch (RecoverableSecurityException e) {
            // Resumes in singleDeleteLauncher, which advances to index+1.
            singleDeleteLauncher.launch(new IntentSenderRequest.Builder(
                    e.getUserAction().getActionIntent().getIntentSender()).build());
        } catch (Exception e) {
            toastReplaceFailed();
            deleteNextApi29(index + 1);
        }
    }

    /** Deletes one source and moves its output into place; throws what delete() throws. */
    private void deleteThenMove(ReplaceItem it) {
        if (activity.getContentResolver().delete(it.mediaUri, null, null) > 0) applyReplaceMove(it);
        else toastReplaceFailed();
    }

    /** A SAF document with no MediaStore item: delete it directly; the output stays where it was. */
    private void deleteSafDocument(Uri source, int index) {
        try {
            if (DocumentsContract.isDocumentUri(activity, source)
                    && DocumentsContract.deleteDocument(activity.getContentResolver(), source)) {
                markReplaced(index);
                Toast.makeText(activity, R.string.replace_move_failed, Toast.LENGTH_SHORT).show();
            } else {
                toastReplaceFailed();
            }
        } catch (Exception e) {
            Log.w(TAG, "SAF delete failed for " + source, e);
            toastReplaceFailed();
        }
    }

    /** Resolve a source (picker / SAF / plain media) uri to a deletable MediaStore item, or null. */
    private Uri resolveToMediaUri(Uri uri) {
        try {
            if (DocumentsContract.isDocumentUri(activity, uri)) {
                try {
                    return MediaStore.getMediaUri(activity, uri);
                } catch (Exception e) {
                    return null; // SAF doc with no MediaStore backing
                }
            }
            if (SourceMetadata.isPhotoPickerUri(uri)) {
                return SourceMetadata.findMediaStoreVideo(activity, uri);
            }
            if (MediaStore.AUTHORITY.equals(uri.getAuthority())) return uri; // already a MediaStore item
            // Another app's provider (e.g. Google Photos share): createDeleteRequest
            // only takes MediaStore ID uris, so find the item behind it.
            Uri embedded = findEmbeddedMediaUri(uri);
            return embedded != null ? embedded : SourceMetadata.findMediaStoreVideo(activity, uri);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Some share providers carry the MediaStore uri inside their own, e.g. Google
     * Photos: {@code content://com.google.android.apps.photos.contentprovider/-1/2/
     * content%3A%2F%2Fmedia%2Fexternal%2Fvideo%2Fmedia%2F1000032771/REQUIRE_ORIGINAL/...}.
     * Returns that uri if it names an existing item (or can't be checked).
     */
    private Uri findEmbeddedMediaUri(Uri uri) {
        for (String segment : uri.getPathSegments()) {
            if (!segment.startsWith("content://" + MediaStore.AUTHORITY + "/")) continue;
            Uri candidate = Uri.parse(segment);
            try (Cursor c = activity.getContentResolver().query(candidate,
                    new String[]{MediaStore.MediaColumns._ID}, null, null, null)) {
                if (c != null && c.moveToFirst()) return candidate;
            } catch (Exception ignored) {
            }
            // Unreadable without library access: trust the provider's uri. The
            // system delete dialog still shows the user which item goes.
            if (!SourceMetadata.hasVideoLibraryPermission(activity)) return candidate;
        }
        return null;
    }

    /** Fills in the item's source location; left null where it can't be read. */
    private void captureLocation(ReplaceItem it) {
        try (Cursor c = activity.getContentResolver().query(it.mediaUri, new String[]{
                MediaStore.MediaColumns.VOLUME_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                it.volume = c.getString(0);
                it.relPath = c.getString(1);
                String name = c.getString(2);
                int dot = name != null ? name.lastIndexOf('.') : -1;
                it.baseName = dot > 0 ? name.substring(0, dot) : name;
            }
        } catch (Exception e) {
            Log.w(TAG, "captureLocation failed", e);
        }
    }

    /** A source was deleted: drop it from the replaceable set and move its output into place. */
    private void applyReplaceMove(ReplaceItem it) {
        markReplaced(it.index);
        if (it.volume != null && it.relPath != null && it.baseName != null) {
            moveOutputToLocation(it);
        } else {
            // Location unknown: the compressed file stays where it was saved.
            Toast.makeText(activity, R.string.replace_move_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void markReplaced(int index) {
        if (index >= 0 && index < sources.size()) sources.set(index, null);
        onReplaced.accept(index);
    }

    /** Called once the whole replace run's deletions are done: one summary toast. */
    private void finishReplace() {
        if (replaceMediaCount > 0) {
            Toast.makeText(activity, replaceMediaCount > 1
                    ? R.string.replace_done_batch : R.string.replace_done, Toast.LENGTH_LONG).show();
        }
        replaceMediaCount = 0;
    }

    /**
     * Moves a compressed output into the deleted source's folder under the
     * source's (base) name. RELATIVE_PATH updates cannot cross volumes (the source
     * may be on the SD card while we saved to internal Movies/), so this copies
     * into a fresh MediaStore item on the target volume and then deletes the
     * app-owned output. Runs on a background thread.
     */
    private void moveOutputToLocation(ReplaceItem it) {
        final int index = it.index;
        final Uri output = it.output;
        final boolean audioOnly = this.audioOnly;
        final String volume = it.volume;
        final String relPath = it.relPath;
        final String newName = it.baseName + (audioOnly ? ".m4a" : ".mp4");
        new Thread(() -> {
            Uri dest = null;
            try {
                dest = MediaStoreOutput.createPending(activity, newName, audioOnly, volume, relPath);

                try (InputStream in = activity.getContentResolver().openInputStream(output);
                     OutputStream out = activity.getContentResolver().openOutputStream(dest, "w")) {
                    if (in == null || out == null) throw new IOException("open streams failed");
                    // Both sides are whole-file MediaStore items, so these are
                    // FileInputStream/FileOutputStream and FileUtils.copy takes its
                    // sendfile(2) path: the bytes never enter the Java heap (the old
                    // hand-rolled loop held a 1 MB buffer for a whole video).
                    FileUtils.copy(in, out);
                }

                MediaStoreOutput.finalizePending(activity, dest);

                activity.getContentResolver().delete(output, null, null); // app-owned: no consent needed

                final Uri finalDest = dest;
                activity.runOnUiThread(() -> {
                    if (index >= 0 && index < outputs.size()) outputs.set(index, finalDest);
                });
            } catch (Exception e) {
                Log.w(TAG, "move to original location failed", e);
                if (dest != null) {
                    try {
                        activity.getContentResolver().delete(dest, null, null);
                    } catch (Exception ignored) {
                    }
                }
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        R.string.replace_move_failed, Toast.LENGTH_LONG).show());
            }
        }, "replace-move").start();
    }

    private void toastReplaceFailed() {
        Toast.makeText(activity, R.string.replace_failed, Toast.LENGTH_LONG).show();
    }
}
