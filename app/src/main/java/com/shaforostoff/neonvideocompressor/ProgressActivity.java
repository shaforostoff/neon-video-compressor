package com.shaforostoff.neonvideocompressor;

import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shaforostoff.neonvideocompressor.engine.ConversionJob;
import com.shaforostoff.neonvideocompressor.service.ConversionService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

public class ProgressActivity extends AppCompatActivity {

    private TextView txtBatch, txtPhase, txtPercent, txtTime, txtSpeed, txtSize, txtRam;
    private ProgressBar progressBar;
    private MaterialButton btnPauseResume, btnCancel, btnOpen, btnShare, btnReplace;
    private View rowResultActions;

    // Cached results so Open/Share survive rotation at DONE (the service may have
    // already stopped, so the recreated activity can't rely on a fresh snapshot).
    private final ArrayList<Uri> resultOutputs = new ArrayList<>();
    private boolean resultAudioOnly;
    // Source of a single-file conversion + whether the output is partial: decide
    // if "Replace original" may be offered (never for a stopped-early file).
    private Uri resultInputUri;
    private boolean resultPartial;

    private static final String STATE_OUTPUTS = "result_outputs";
    private static final String STATE_AUDIO_ONLY = "result_audio_only";
    private static final String STATE_FINISHED = "finished_state";
    private static final String STATE_INPUT_URI = "result_input_uri";
    private static final String STATE_PARTIAL = "result_partial";

    // getPss() walks /proc/self/smaps, so sample it at most once per second
    // rather than on every per-frame snapshot.
    private static final long RAM_SAMPLE_INTERVAL_MS = 1000L;
    private long lastRamSampleMs;

    // While paused there are no progress snapshots, so re-sample RAM on a timer
    // to show the drop as the encoder is torn down.
    private final Handler ramHandler = new Handler(Looper.getMainLooper());
    private final Runnable ramTick = new Runnable() {
        @Override
        public void run() {
            if (!paused || finishedState) return;
            sampleRamNow();
            ramHandler.postDelayed(this, RAM_SAMPLE_INTERVAL_MS);
        }
    };

    private ConversionService service;
    private boolean bound;
    private boolean paused;
    private boolean finishedState;
    // Phase from the last rendered snapshot; decides whether the Stop dialog can
    // offer to save a partial file (only the direct video encode can).
    private ConversionJob.Phase lastPhase = ConversionJob.Phase.PROBING;

    // Deleting the original usually needs the user's consent through a system
    // dialog (MediaStore delete request / RecoverableSecurityException).
    private Uri pendingDeleteUri; // API 29 retry target after consent

    // Where the original lived (captured before it is deleted) so the compressed
    // file can be moved into its place — possibly on another volume (SD card).
    private String replaceVolume, replaceRelPath, replaceBaseName;
    private final ActivityResultLauncher<IntentSenderRequest> deleteRequestLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                if (result.getResultCode() != RESULT_OK) return; // user declined
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // createDeleteRequest already performed the deletion.
                    onOriginalDeleted();
                } else {
                    // API 29: consent granted, the delete must be re-issued.
                    Uri uri = pendingDeleteUri;
                    pendingDeleteUri = null;
                    try {
                        if (uri != null && getContentResolver().delete(uri, null, null) > 0) {
                            onOriginalDeleted();
                        } else {
                            toastReplaceFailed();
                        }
                    } catch (Exception e) {
                        toastReplaceFailed();
                    }
                }
            });

    // Binding with flags=0 to a service that already stopped never connects, so
    // a finish that happened while we were unbound (screen off) would leave the
    // last rendered frame ("Saving", 98%) on screen forever. If the connection
    // hasn't come up shortly after onStart, fall back to the terminal snapshot
    // the service parked before stopping.
    private static final long BIND_FALLBACK_DELAY_MS = 300L;
    private final Handler bindFallbackHandler = new Handler(Looper.getMainLooper());
    private final Runnable bindFallback = () -> {
        if (bound || finishedState) return;
        ConversionService.Snapshot t = ConversionService.getLastTerminal();
        if (t != null) render(t);
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ConversionService.LocalBinder) binder).getService();
            bound = true;
            service.setUiCallback(snapshot -> runOnUiThread(() -> render(snapshot)));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_progress);

        // On SDK 35+ the app is drawn edge-to-edge and android:statusBarColor is
        // ignored, so the content (and its opaque background) extends behind the
        // status bar, hiding the clock/battery. Consume the system-bar insets as
        // padding on top of the existing 24dp so nothing sits under the bars.
        View root = findViewById(R.id.progressRoot);
        final int basePad = Math.round(24 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(basePad + bars.left, basePad + bars.top,
                    basePad + bars.right, basePad + bars.bottom);
            return insets;
        });

        txtBatch = findViewById(R.id.txtBatch);
        txtPhase = findViewById(R.id.txtPhase);
        txtPercent = findViewById(R.id.txtPercent);
        txtTime = findViewById(R.id.txtTime);
        txtSpeed = findViewById(R.id.txtSpeed);
        txtSize = findViewById(R.id.txtSize);
        txtRam = findViewById(R.id.txtRam);
        progressBar = findViewById(R.id.progressBar);
        btnPauseResume = findViewById(R.id.btnPauseResume);
        btnCancel = findViewById(R.id.btnCancel);
        rowResultActions = findViewById(R.id.rowResultActions);
        btnOpen = findViewById(R.id.btnOpen);
        btnShare = findViewById(R.id.btnShare);
        btnReplace = findViewById(R.id.btnReplace);

        btnPauseResume.setOnClickListener(v -> {
            if (!bound) return;
            if (paused) service.resume();
            else service.pause();
        });
        btnCancel.setOnClickListener(v -> {
            if (finishedState) {
                finish();
            } else if (bound) {
                showStopDialog();
            }
        });
        btnOpen.setOnClickListener(v -> {
            if (resultOutputs.isEmpty()) return;
            try {
                startActivity(OutputActions.view(resultOutputs.get(0), resultAudioOnly));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show();
            }
        });
        btnShare.setOnClickListener(v -> {
            if (resultOutputs.isEmpty()) return;
            // A chooser always resolves, so no ActivityNotFoundException guard needed.
            startActivity(OutputActions.share(this, resultOutputs, resultAudioOnly,
                    getString(R.string.share_via)));
        });
        btnReplace.setOnClickListener(v -> confirmReplaceOriginal());

        // Restore cached results so Open/Share work after a rotation at DONE even
        // if the service has already stopped and the rebind finds it gone.
        if (savedInstanceState != null) {
            ArrayList<Uri> saved = savedInstanceState.getParcelableArrayList(STATE_OUTPUTS);
            if (saved != null) resultOutputs.addAll(saved);
            resultAudioOnly = savedInstanceState.getBoolean(STATE_AUDIO_ONLY);
            finishedState = savedInstanceState.getBoolean(STATE_FINISHED);
            resultInputUri = savedInstanceState.getParcelable(STATE_INPUT_URI);
            resultPartial = savedInstanceState.getBoolean(STATE_PARTIAL);
            if (finishedState) {
                btnPauseResume.setEnabled(false);
                btnCancel.setText(R.string.close);
                rowResultActions.setVisibility(resultOutputs.isEmpty() ? View.GONE : View.VISIBLE);
                btnReplace.setVisibility(canReplaceOriginal() ? View.VISIBLE : View.GONE);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putParcelableArrayList(STATE_OUTPUTS, resultOutputs);
        out.putBoolean(STATE_AUDIO_ONLY, resultAudioOnly);
        out.putBoolean(STATE_FINISHED, finishedState);
        out.putParcelable(STATE_INPUT_URI, resultInputUri);
        out.putBoolean(STATE_PARTIAL, resultPartial);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, ConversionService.class), connection, 0);
        bindFallbackHandler.postDelayed(bindFallback, BIND_FALLBACK_DELAY_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        bindFallbackHandler.removeCallbacks(bindFallback);
        stopRamTicker();
        if (bound) {
            service.setUiCallback(null);
            unbindService(connection);
            bound = false;
        }
    }

    /**
     * Stop was pressed mid-run: let the user choose between keeping the part
     * that is already encoded (a playable "first N seconds", with its audio) and
     * discarding it. Saving is only possible during the video-encode phase; in
     * any other phase stopping simply discards the current file.
     */
    private void showStopDialog() {
        boolean canSavePartial = lastPhase == ConversionJob.Phase.VIDEO;
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.stop_dialog_title)
                .setMessage(canSavePartial
                        ? R.string.stop_dialog_message
                        : R.string.stop_dialog_message_nosave)
                .setNegativeButton(R.string.stop_discard, (d, w) -> {
                    if (bound) service.cancel();
                })
                .setNeutralButton(R.string.stop_keep, null);
        if (canSavePartial) {
            b.setPositiveButton(R.string.stop_save_partial, (d, w) -> {
                if (bound) service.stopAndSave();
            });
        }
        b.show();
    }

    private void render(ConversionService.Snapshot s) {
        boolean batch = s.batchTotal > 1;
        lastPhase = s.phase;
        int percent = Math.round(s.batchFraction() * 100);
        progressBar.setProgress(percent);
        txtPercent.setText(percent + "%");

        switch (s.status) {
            case RUNNING:
            case PAUSED:
                paused = s.status == ConversionService.Status.PAUSED;
                if (batch) {
                    String name = s.currentName != null ? ": " + s.currentName : "";
                    txtBatch.setVisibility(View.VISIBLE);
                    txtBatch.setText(getString(R.string.batch_file_format,
                            s.batchIndex + 1, s.batchTotal) + name);
                } else {
                    txtBatch.setVisibility(View.GONE);
                }
                txtPhase.setText(paused
                        ? getString(s.lowMemoryPaused
                                ? R.string.phase_paused_lowmem_format
                                : R.string.phase_paused_format, phaseLabel(s))
                        : phaseLabel(s));
                txtTime.setText(formatTime(s.processedUs) + " / " + formatTime(s.durationUs));
                txtSpeed.setText(s.speed > 0
                        ? getString(R.string.speed_format, s.speed)
                        : getString(R.string.speed_unknown));
                if (s.liveProcessedBytes > 0 || s.liveOutputBytes > 0) {
                    txtSize.setVisibility(View.VISIBLE);
                    txtSize.setText(Formatter.formatShortFileSize(this, s.liveProcessedBytes)
                            + " -> " + Formatter.formatShortFileSize(this, s.liveOutputBytes));
                } else {
                    txtSize.setVisibility(View.GONE);
                }
                btnPauseResume.setText(paused ? R.string.resume : R.string.pause);
                btnPauseResume.setEnabled(true);
                btnCancel.setText(R.string.stop);
                rowResultActions.setVisibility(View.GONE);
                btnReplace.setVisibility(View.GONE);
                if (paused) {
                    startRamTicker();
                } else {
                    stopRamTicker();
                    updateRam();
                }
                break;
            case DONE:
                finishedState = true;
                txtBatch.setVisibility(View.GONE);
                txtPhase.setText(batch ? R.string.batch_complete : R.string.done);
                txtTime.setText(s.message != null ? s.message : "");
                String savedTo = getString(s.audioOnly ? R.string.saved_to_music : R.string.saved_to_movies);
                txtSpeed.setText(savedTo);
                txtSize.setVisibility(View.GONE);
                stopRamTicker();
                txtRam.setText("");
                btnPauseResume.setEnabled(false);
                btnCancel.setText(R.string.close);
                // Cache outputs (defensive copy off the shared Snapshot) and offer
                // Open/Share — covers full and partial success.
                resultOutputs.clear();
                resultOutputs.addAll(s.outputs);
                resultAudioOnly = s.audioOnly;
                resultInputUri = s.inputUri;
                resultPartial = s.partial;
                rowResultActions.setVisibility(resultOutputs.isEmpty() ? View.GONE : View.VISIBLE);
                btnReplace.setVisibility(canReplaceOriginal() ? View.VISIBLE : View.GONE);
                Toast.makeText(this, s.message != null ? s.message : savedTo,
                        Toast.LENGTH_LONG).show();
                break;
            case ERROR:
                finishedState = true;
                txtBatch.setVisibility(View.GONE);
                txtPhase.setText(R.string.error_label);
                txtTime.setText(s.message != null ? s.message : getString(R.string.unknown_error));
                txtSpeed.setText("");
                txtSize.setVisibility(View.GONE);
                stopRamTicker();
                txtRam.setText("");
                btnPauseResume.setEnabled(false);
                btnCancel.setText(R.string.close);
                rowResultActions.setVisibility(View.GONE);
                btnReplace.setVisibility(View.GONE);
                break;
            case CANCELLED:
                finishedState = true;
                txtBatch.setVisibility(View.GONE);
                txtPhase.setText(R.string.cancelled_label);
                txtTime.setText(s.message != null ? s.message : "");
                txtSize.setVisibility(View.GONE);
                stopRamTicker();
                txtRam.setText("");
                btnPauseResume.setEnabled(false);
                btnCancel.setText(R.string.close);
                rowResultActions.setVisibility(View.GONE);
                btnReplace.setVisibility(View.GONE);
                break;
            default:
                break;
        }
    }

    // --- Replace original (delete the source file) ---------------------------

    /**
     * Replacing is only offered for a fully converted single file whose source
     * we still know. A partial (stopped-early) output must never replace the
     * original.
     */
    private boolean canReplaceOriginal() {
        return !resultPartial && resultInputUri != null && !resultOutputs.isEmpty();
    }

    private void confirmReplaceOriginal() {
        if (!canReplaceOriginal()) return;
        // Picker uris redact the display name; resolve the real item for a
        // recognizable name in the dialog.
        Uri nameSource = isPhotoPickerUri(resultInputUri)
                ? findMediaStoreUri(resultInputUri) : resultInputUri;
        String name = nameSource != null ? queryDisplayName(nameSource) : null;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.replace_confirm_title)
                .setMessage(getString(R.string.replace_confirm_message,
                        name != null ? name : getString(R.string.replace_this_video)))
                .setPositiveButton(R.string.replace_confirm_delete,
                        (d, w) -> deleteOriginal(resultInputUri))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Deletes the source file. Depending on where the video came from this is a
     * SAF document delete, or a MediaStore delete that first bounces through a
     * system consent dialog (we don't own the file).
     */
    private void deleteOriginal(Uri uri) {
        android.util.Log.i(TAG_REPLACE, "deleteOriginal: " + uri);
        try {
            if (DocumentsContract.isDocumentUri(this, uri)) {
                // SAF picker uri: prefer the underlying MediaStore item so the
                // system consent flow applies; else delete through the provider.
                Uri media = null;
                try {
                    media = MediaStore.getMediaUri(this, uri);
                } catch (Exception e) {
                    android.util.Log.w(TAG_REPLACE, "getMediaUri failed", e);
                }
                android.util.Log.i(TAG_REPLACE, "document uri -> media uri: " + media);
                if (media != null) {
                    uri = media;
                } else {
                    if (DocumentsContract.deleteDocument(getContentResolver(), uri)) {
                        onOriginalDeleted();
                    } else {
                        android.util.Log.w(TAG_REPLACE, "deleteDocument returned false");
                        toastReplaceFailed();
                    }
                    return;
                }
            } else if (isPhotoPickerUri(uri)) {
                // Photo-picker grants are read-only; find the real item.
                Uri media = findMediaStoreUri(uri);
                android.util.Log.i(TAG_REPLACE, "picker uri -> media uri: " + media);
                if (media == null) {
                    toastReplaceFailed();
                    return;
                }
                uri = media;
            }

            captureOriginalLocation(uri);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                PendingIntent pi = MediaStore.createDeleteRequest(
                        getContentResolver(), Collections.singletonList(uri));
                deleteRequestLauncher.launch(
                        new IntentSenderRequest.Builder(pi.getIntentSender()).build());
            } else {
                try {
                    if (getContentResolver().delete(uri, null, null) > 0) {
                        onOriginalDeleted();
                    } else {
                        android.util.Log.w(TAG_REPLACE, "resolver.delete returned 0");
                        toastReplaceFailed();
                    }
                } catch (RecoverableSecurityException e) {
                    pendingDeleteUri = uri;
                    deleteRequestLauncher.launch(new IntentSenderRequest.Builder(
                            e.getUserAction().getActionIntent().getIntentSender()).build());
                }
            }
        } catch (Exception e) {
            android.util.Log.w(TAG_REPLACE, "deleteOriginal failed for " + uri, e);
            toastReplaceFailed();
        }
    }

    private static final String TAG_REPLACE = "ReplaceOriginal";

    private static boolean isPhotoPickerUri(Uri uri) {
        String path = uri.getPath();
        return "media".equals(uri.getAuthority()) && path != null && path.startsWith("/picker");
    }

    /**
     * Locates the MediaStore video item behind a photo-picker uri. The picker
     * redacts DISPLAY_NAME (it returns "<pickerId>.mp4"), so a name lookup is
     * useless — but for local items the picker id IS the MediaStore {@code _ID}
     * (verified on Android 15), so try that first and sanity-check by SIZE;
     * fall back to a unique SIZE match.
     */
    private Uri findMediaStoreUri(Uri uri) {
        long size = -1;
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) size = c.getLong(0);
        } catch (Exception ignored) {
        }

        Uri videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);

        try {
            long id = ContentUris.parseId(uri);
            Uri candidate = ContentUris.withAppendedId(videos, id);
            try (Cursor c = getContentResolver().query(candidate,
                    new String[]{MediaStore.MediaColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst() && (size <= 0 || c.getLong(0) == size)) {
                    return candidate;
                }
            }
        } catch (Exception ignored) {
        }

        if (size <= 0) return null;
        try (Cursor c = getContentResolver().query(videos,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.SIZE + "=?",
                new String[]{String.valueOf(size)}, null)) {
            if (c != null && c.getCount() == 1 && c.moveToFirst()) {
                return ContentUris.withAppendedId(videos, c.getLong(0));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Remembers the original's volume, folder and base name for the move step. */
    private void captureOriginalLocation(Uri mediaUri) {
        replaceVolume = null;
        replaceRelPath = null;
        replaceBaseName = null;
        try (Cursor c = getContentResolver().query(mediaUri, new String[]{
                MediaStore.MediaColumns.VOLUME_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                replaceVolume = c.getString(0);
                replaceRelPath = c.getString(1);
                String name = c.getString(2);
                if (name != null) {
                    int dot = name.lastIndexOf('.');
                    replaceBaseName = dot > 0 ? name.substring(0, dot) : name;
                }
            }
        } catch (Exception e) {
            android.util.Log.w(TAG_REPLACE, "captureOriginalLocation failed", e);
        }
    }

    private void onOriginalDeleted() {
        resultInputUri = null;
        btnReplace.setVisibility(View.GONE);
        if (replaceVolume != null && replaceRelPath != null && replaceBaseName != null
                && !resultOutputs.isEmpty()) {
            moveOutputToOriginalLocation();
        } else {
            // Location unknown (e.g. deleted through a non-media document
            // provider): the compressed file stays where it was saved.
            Toast.makeText(this, R.string.replace_move_failed, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Moves the compressed file into the deleted original's folder under the
     * original's (base) name. RELATIVE_PATH updates cannot cross volumes (the
     * original may be on the SD card while we saved to internal Movies/), so
     * this copies into a fresh MediaStore item on the target volume and then
     * deletes the app-owned source item. Runs on a background thread.
     */
    private void moveOutputToOriginalLocation() {
        final Uri source = resultOutputs.get(0);
        final boolean audioOnly = resultAudioOnly;
        final String volume = replaceVolume;
        final String relPath = replaceRelPath;
        final String newName = replaceBaseName + (audioOnly ? ".m4a" : ".mp4");
        new Thread(() -> {
            Uri dest = null;
            try {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, newName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, audioOnly ? "audio/mp4" : "video/mp4");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri collection = audioOnly
                        ? MediaStore.Audio.Media.getContentUri(volume)
                        : MediaStore.Video.Media.getContentUri(volume);
                dest = getContentResolver().insert(collection, values);
                if (dest == null) throw new java.io.IOException("insert failed");

                try (java.io.InputStream in = getContentResolver().openInputStream(source);
                     java.io.OutputStream out = getContentResolver().openOutputStream(dest, "w")) {
                    if (in == null || out == null) throw new java.io.IOException("open streams failed");
                    byte[] buf = new byte[1 << 20];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }

                android.content.ContentValues done = new android.content.ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(dest, done, null, null);

                getContentResolver().delete(source, null, null); // app-owned: no consent needed

                final Uri finalDest = dest;
                runOnUiThread(() -> {
                    if (!resultOutputs.isEmpty()) resultOutputs.set(0, finalDest);
                    Toast.makeText(this, R.string.replace_done, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                android.util.Log.w(TAG_REPLACE, "move to original location failed", e);
                if (dest != null) {
                    try {
                        getContentResolver().delete(dest, null, null);
                    } catch (Exception ignored) {
                    }
                }
                runOnUiThread(() -> Toast.makeText(this,
                        R.string.replace_move_failed, Toast.LENGTH_LONG).show());
            }
        }, "replace-move").start();
    }

    private void toastReplaceFailed() {
        Toast.makeText(this, R.string.replace_failed, Toast.LENGTH_LONG).show();
    }

    // Throttled sample for the running state (called per progress snapshot).
    private void updateRam() {
        long now = SystemClock.uptimeMillis();
        if (lastRamSampleMs != 0 && now - lastRamSampleMs < RAM_SAMPLE_INTERVAL_MS) return;
        sampleRamNow();
    }

    // Total process PSS (Java heap + native x265/FFmpeg allocations + mapped code),
    // which is the memory the OS actually attributes to the app.
    private void sampleRamNow() {
        lastRamSampleMs = SystemClock.uptimeMillis();
        long pssKb = Debug.getPss(); // total PSS of this process, in KB
        txtRam.setText(getString(R.string.ram_format, (pssKb + 512) / 1024));
    }

    private void startRamTicker() {
        ramHandler.removeCallbacks(ramTick);
        ramHandler.post(ramTick);
    }

    private void stopRamTicker() {
        ramHandler.removeCallbacks(ramTick);
    }

    private String phaseLabel(ConversionService.Snapshot s) {
        switch (s.phase) {
            case VIDEO: return getString(R.string.phase_video);
            case AUDIO: return getString(R.string.phase_audio);
            case MUXING: return getString(R.string.phase_mux);
            case PUBLISHING: return getString(R.string.phase_publish);
            default: return getString(R.string.preparing);
        }
    }

    private static String formatTime(long us) {
        long totalSec = us / 1_000_000L;
        return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60);
    }
}
