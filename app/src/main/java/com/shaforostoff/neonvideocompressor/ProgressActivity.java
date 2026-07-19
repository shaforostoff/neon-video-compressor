package com.shaforostoff.neonvideocompressor;

import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
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
import com.shaforostoff.neonvideocompressor.engine.SourceMetadata;
import com.shaforostoff.neonvideocompressor.service.ConversionService;
import com.shaforostoff.neonvideocompressor.service.ResultStore;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Locale;

public class ProgressActivity extends AppCompatActivity {

    private TextView txtBatch, txtPhase, txtPercent, txtTime, txtSpeed, txtSize, txtRam, txtBitrates;
    private ProgressBar progressBar;
    private MaterialButton btnPauseResume, btnCancel, btnOpen, btnShare, btnReplace;
    private View rowResultActions;

    // Cached results so Open/Share survive rotation at DONE (the service may have
    // already stopped, so the recreated activity can't rely on a fresh snapshot).
    private final ArrayList<Uri> resultOutputs = new ArrayList<>();
    // Parallel to resultOutputs: the source of each output, or null when it can't
    // be replaced (partial output, or a batch member that failed). Drives the
    // "Replace original(s)" offer for both single and batch conversions.
    private final ArrayList<Uri> resultOutputSources = new ArrayList<>();
    private boolean resultAudioOnly;
    // Source of a single-file conversion + whether the output is partial: decide
    // if "Replace original" may be offered (never for a stopped-early file).
    private Uri resultInputUri;
    private boolean resultPartial;

    private static final String STATE_OUTPUTS = "result_outputs";
    private static final String STATE_OUTPUT_SOURCES = "result_output_sources";
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

    // One queued "replace a source with its compressed output" operation.
    private static final class ReplaceItem {
        final int index;    // position in resultOutputs
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
    private final ActivityResultLauncher<IntentSenderRequest> batchDeleteLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                if (result.getResultCode() != RESULT_OK) { // declined: nothing deleted
                    pendingReplace.clear();
                    return;
                }
                for (ReplaceItem it : pendingReplace) applyReplaceMove(it);
                pendingReplace.clear();
                finishReplace();
            });

    // API 29: consent is per-file; on grant, re-issue that delete and continue.
    private final ActivityResultLauncher<IntentSenderRequest> singleDeleteLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && api29Index < pendingReplace.size()) {
                    ReplaceItem it = pendingReplace.get(api29Index);
                    try {
                        if (getContentResolver().delete(it.mediaUri, null, null) > 0) {
                            applyReplaceMove(it);
                        } else {
                            toastReplaceFailed();
                        }
                    } catch (Exception e) {
                        toastReplaceFailed();
                    }
                }
                deleteNextApi29(api29Index + 1);
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
        // The in-memory terminal snapshot is gone if the process was killed after
        // finishing; fall back to the durable copy so Open/Share/Replace still work.
        if (t == null) t = ResultStore.load(this);
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
        txtBitrates = findViewById(R.id.txtBitrates);
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
        btnReplace.setOnClickListener(v -> confirmReplace());

        // Restore cached results so Open/Share work after a rotation at DONE even
        // if the service has already stopped and the rebind finds it gone.
        if (savedInstanceState != null) {
            ArrayList<Uri> saved = savedInstanceState.getParcelableArrayList(STATE_OUTPUTS);
            if (saved != null) resultOutputs.addAll(saved);
            ArrayList<Uri> savedSources = savedInstanceState.getParcelableArrayList(STATE_OUTPUT_SOURCES);
            if (savedSources != null) resultOutputSources.addAll(savedSources);
            resultAudioOnly = savedInstanceState.getBoolean(STATE_AUDIO_ONLY);
            finishedState = savedInstanceState.getBoolean(STATE_FINISHED);
            resultInputUri = savedInstanceState.getParcelable(STATE_INPUT_URI);
            resultPartial = savedInstanceState.getBoolean(STATE_PARTIAL);
            if (finishedState) {
                btnPauseResume.setEnabled(false);
                btnCancel.setText(R.string.close);
                rowResultActions.setVisibility(resultOutputs.isEmpty() ? View.GONE : View.VISIBLE);
                updateReplaceButton();
                showBitrateComparison(); // recompute after rotation (not saved in state)
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putParcelableArrayList(STATE_OUTPUTS, resultOutputs);
        out.putParcelableArrayList(STATE_OUTPUT_SOURCES, resultOutputSources);
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
                txtBitrates.setVisibility(View.GONE);
                if (paused) {
                    startRamTicker();
                } else {
                    stopRamTicker();
                    updateRam();
                }
                break;
            case DONE:
                finishedState = true;
                // The user is now looking at the finished screen — mark the durable
                // result seen so it is not auto-reopened on the next launch.
                ResultStore.markAcknowledged(this);
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
                resultOutputSources.clear();
                resultOutputSources.addAll(s.outputSources);
                resultAudioOnly = s.audioOnly;
                resultInputUri = s.inputUri;
                resultPartial = s.partial;
                rowResultActions.setVisibility(resultOutputs.isEmpty() ? View.GONE : View.VISIBLE);
                updateReplaceButton();
                showBitrateComparison();
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

    // --- Replace original(s): delete each source, move its output into place ----

    /** Number of outputs whose source is still known and replaceable (never partial). */
    private int replaceableCount() {
        int n = 0;
        for (int i = 0; i < resultOutputs.size(); i++) {
            if (i < resultOutputSources.size() && resultOutputSources.get(i) != null) n++;
        }
        return n;
    }

    private void updateReplaceButton() {
        int count = replaceableCount();
        btnReplace.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        btnReplace.setText(count > 1 ? R.string.replace_originals : R.string.replace_original);
    }

    private Uri firstReplaceableSource() {
        for (int i = 0; i < resultOutputs.size(); i++) {
            if (i < resultOutputSources.size() && resultOutputSources.get(i) != null) {
                return resultOutputSources.get(i);
            }
        }
        return null;
    }

    private void confirmReplace() {
        int count = replaceableCount();
        if (count == 0) return;
        String title, message;
        if (count == 1) {
            // Picker uris redact the display name; resolve the real item for a
            // recognizable name in the dialog.
            Uri src = firstReplaceableSource();
            Uri nameSource = src != null && isPhotoPickerUri(src) ? findMediaStoreUri(src) : src;
            String name = nameSource != null ? queryDisplayName(nameSource) : null;
            title = getString(R.string.replace_confirm_title);
            message = getString(R.string.replace_confirm_message,
                    name != null ? name : getString(R.string.replace_this_video));
        } else {
            title = getString(R.string.replace_confirm_title_batch);
            message = getString(R.string.replace_confirm_message_batch, count);
        }
        new MaterialAlertDialogBuilder(this)
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
        for (int i = 0; i < resultOutputs.size(); i++) {
            Uri source = i < resultOutputSources.size() ? resultOutputSources.get(i) : null;
            if (source == null) continue;
            Uri media = resolveToMediaUri(source);
            if (media == null) {
                deleteSafDocument(source, i);
                continue;
            }
            ReplaceItem item = new ReplaceItem(i, resultOutputs.get(i), media);
            String[] loc = captureLocation(media);
            if (loc != null) {
                item.volume = loc[0];
                item.relPath = loc[1];
                item.baseName = loc[2];
            }
            pendingReplace.add(item);
        }
        replaceMediaCount = pendingReplace.size();
        if (pendingReplace.isEmpty()) return; // SAF-only case handled inline above

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ArrayList<Uri> uris = new ArrayList<>();
            for (ReplaceItem it : pendingReplace) uris.add(it.mediaUri);
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), uris);
                batchDeleteLauncher.launch(
                        new IntentSenderRequest.Builder(pi.getIntentSender()).build());
            } catch (Exception e) {
                android.util.Log.w(TAG_REPLACE, "batch delete request failed", e);
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
        ReplaceItem it = pendingReplace.get(index);
        try {
            if (getContentResolver().delete(it.mediaUri, null, null) > 0) {
                applyReplaceMove(it);
            } else {
                toastReplaceFailed();
            }
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

    /** A SAF document with no MediaStore item: delete it directly; the output stays where it was. */
    private void deleteSafDocument(Uri source, int index) {
        try {
            if (DocumentsContract.isDocumentUri(this, source)
                    && DocumentsContract.deleteDocument(getContentResolver(), source)) {
                markReplaced(index);
                Toast.makeText(this, R.string.replace_move_failed, Toast.LENGTH_SHORT).show();
            } else {
                toastReplaceFailed();
            }
        } catch (Exception e) {
            android.util.Log.w(TAG_REPLACE, "SAF delete failed for " + source, e);
            toastReplaceFailed();
        }
    }

    /** Resolve a source (picker / SAF / plain media) uri to a deletable MediaStore item, or null. */
    private Uri resolveToMediaUri(Uri uri) {
        try {
            if (DocumentsContract.isDocumentUri(this, uri)) {
                try {
                    return MediaStore.getMediaUri(this, uri);
                } catch (Exception e) {
                    return null; // SAF doc with no MediaStore backing
                }
            }
            if (isPhotoPickerUri(uri)) return findMediaStoreUri(uri);
            return uri; // already a MediaStore item
        } catch (Exception e) {
            return null;
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

    /** @return {volume, relPath, baseName} for a media uri, or null if it can't be read. */
    private String[] captureLocation(Uri mediaUri) {
        try (Cursor c = getContentResolver().query(mediaUri, new String[]{
                MediaStore.MediaColumns.VOLUME_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(2);
                String base = name;
                if (name != null) {
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) base = name.substring(0, dot);
                }
                return new String[]{c.getString(0), c.getString(1), base};
            }
        } catch (Exception e) {
            android.util.Log.w(TAG_REPLACE, "captureLocation failed", e);
        }
        return null;
    }

    /** A source was deleted: drop it from the replaceable set and move its output into place. */
    private void applyReplaceMove(ReplaceItem it) {
        markReplaced(it.index);
        if (it.volume != null && it.relPath != null && it.baseName != null) {
            moveOutputToLocation(it);
        } else {
            // Location unknown: the compressed file stays where it was saved.
            Toast.makeText(this, R.string.replace_move_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void markReplaced(int index) {
        if (index >= 0 && index < resultOutputSources.size()) resultOutputSources.set(index, null);
        if (index == 0) resultInputUri = null; // keep the single-file field in sync
    }

    /** Called once the whole replace run's deletions are done: one summary toast + button refresh. */
    private void finishReplace() {
        if (replaceMediaCount > 0) {
            Toast.makeText(this, replaceMediaCount > 1
                    ? R.string.replace_done_batch : R.string.replace_done, Toast.LENGTH_LONG).show();
        }
        replaceMediaCount = 0;
        updateReplaceButton();
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
        final boolean audioOnly = resultAudioOnly;
        final String volume = it.volume;
        final String relPath = it.relPath;
        final String newName = it.baseName + (audioOnly ? ".m4a" : ".mp4");
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

                try (java.io.InputStream in = getContentResolver().openInputStream(output);
                     java.io.OutputStream out = getContentResolver().openOutputStream(dest, "w")) {
                    if (in == null || out == null) throw new java.io.IOException("open streams failed");
                    byte[] buf = new byte[1 << 20];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }

                android.content.ContentValues done = new android.content.ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(dest, done, null, null);

                getContentResolver().delete(output, null, null); // app-owned: no consent needed

                final Uri finalDest = dest;
                runOnUiThread(() -> {
                    if (index >= 0 && index < resultOutputs.size()) resultOutputs.set(index, finalDest);
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

    // --- Original -> new bitrate comparison (single-file completion) ----------

    /**
     * Measures the source and output per-track bitrates off the main thread and
     * shows an "original → new" line for video and audio. Only for single-file
     * conversions (a batch has no single source to compare against).
     */
    private void showBitrateComparison() {
        txtBitrates.setVisibility(View.GONE);
        final Uri input = resultInputUri;
        final Uri output = resultOutputs.isEmpty() ? null : resultOutputs.get(0);
        if (input == null || output == null) return;
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            long[] src = measureBitrates(ctx, input);
            long[] out = measureBitrates(ctx, output);
            String video = bitrateLine(R.string.result_bitrate_video, src[0], out[0]);
            String audio = bitrateLine(R.string.result_bitrate_audio, src[1], out[1]);
            final String text = video != null && audio != null ? video + "\n" + audio
                    : video != null ? video : audio;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !finishedState || text == null) return;
                txtBitrates.setText(text);
                txtBitrates.setVisibility(View.VISIBLE);
            });
        }, "bitrate-measure").start();
    }

    /** @return the formatted "Video/Audio: orig → new" line, or null if that track is absent in both. */
    private String bitrateLine(int fmtRes, long srcBps, long outBps) {
        if (srcBps < 0 && outBps < 0) return null;
        return getString(fmtRes, formatBitrate(srcBps), formatBitrate(outBps));
    }

    private String formatBitrate(long bps) {
        if (bps < 0) return getString(R.string.bitrate_none);
        if (bps >= 1_000_000) {
            return getString(R.string.bitrate_mbps,
                    String.format(Locale.US, "%.1f", bps / 1_000_000.0));
        }
        return getString(R.string.bitrate_kbps, Math.round(bps / 1000.0));
    }

    /**
     * @return {@code {videoBps, audioBps}}; -1 for an absent track. The audio rate
     * is exact (its samples are summed — they're tiny); the video rate is the
     * container bitrate (size/duration) minus audio, which avoids reading the
     * large video track.
     */
    private static long[] measureBitrates(Context ctx, Uri uri) {
        long videoBps = -1, audioBps = -1;
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, uri, null);
            int audioTrack = -1;
            boolean hasVideo = false;
            long durUs = 0;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (f.containsKey(MediaFormat.KEY_DURATION)) {
                    durUs = Math.max(durUs, f.getLong(MediaFormat.KEY_DURATION));
                }
                if (mime.startsWith("video/")) hasVideo = true;
                else if (mime.startsWith("audio/") && audioTrack < 0) audioTrack = i;
            }
            double durSec = durUs / 1_000_000.0;
            long sizeBytes = SourceMetadata.querySize(ctx, uri);
            long overallBps = durSec > 0 && sizeBytes > 0 ? (long) (sizeBytes * 8 / durSec) : 0;

            if (audioTrack >= 0 && durSec > 0) {
                ex.selectTrack(audioTrack);
                long audioBytes = 0;
                ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
                int n;
                while ((n = ex.readSampleData(buf, 0)) >= 0) {
                    audioBytes += n;
                    if (!ex.advance()) break;
                }
                audioBps = (long) (audioBytes * 8 / durSec);
            }
            if (hasVideo) {
                long a = audioBps > 0 ? audioBps : 0;
                videoBps = overallBps > 0 ? Math.max(0, overallBps - a) : 0;
            }
        } catch (Exception ignored) {
            // Unreadable / unsupported source — leave both as -1 (line is hidden).
        } finally {
            try {
                ex.release();
            } catch (Exception ignored) {
            }
        }
        return new long[]{videoBps, audioBps};
    }
}
