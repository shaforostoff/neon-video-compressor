package com.shaforostoff.neonvideocompressor;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

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
    // Source of a single-file conversion, for the bitrate comparison.
    private Uri resultInputUri;

    private static final String STATE_OUTPUTS = "result_outputs";
    private static final String STATE_OUTPUT_SOURCES = "result_output_sources";
    private static final String STATE_AUDIO_ONLY = "result_audio_only";
    private static final String STATE_FINISHED = "finished_state";
    private static final String STATE_INPUT_URI = "result_input_uri";

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

    // Deletes originals and moves the outputs into their place; works on the
    // result lists above. Created here: it registers activity-result launchers.
    private final ReplaceOriginals replace = new ReplaceOriginals(this,
            resultOutputs, resultOutputSources, index -> {
                if (index == 0) resultInputUri = null; // keep the single-file field in sync
                updateReplaceButton();
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
            if (bound) service.setPaused(!paused);
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
        btnReplace.setOnClickListener(v -> replace.start(resultAudioOnly));

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
            if (finishedState) {
                showTerminalState();
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
                                : R.string.phase_paused_format, getString(s.phase.label))
                        : getString(s.phase.label));
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
                // DONE is rendered again on every rebind / terminal fallback (e.g.
                // back from Open or Share). Only the first one adopts the results
                // and runs the one-time side effects: re-adopting would undo a
                // Replace already done (stale output and source uris).
                boolean first = !finishedState;
                if (first) {
                    // The user is now looking at the finished screen — mark the durable
                    // result seen so it is not auto-reopened on the next launch.
                    ResultStore.markAcknowledged(this);
                    // Cache outputs (defensive copy off the shared Snapshot) and offer
                    // Open/Share — covers full and partial success.
                    resultOutputs.clear();
                    resultOutputs.addAll(s.outputs);
                    resultOutputSources.clear();
                    resultOutputSources.addAll(s.outputSources);
                    resultAudioOnly = s.audioOnly;
                    resultInputUri = s.inputUri;
                }
                showTerminalState();
                txtPhase.setText(batch ? R.string.batch_complete : R.string.done);
                txtTime.setText(s.message != null ? s.message : "");
                String savedTo = getString(s.audioOnly ? R.string.saved_to_music : R.string.saved_to_movies);
                txtSpeed.setText(savedTo);
                if (first) {
                    showBitrateComparison();
                    Toast.makeText(this, s.message != null ? s.message : savedTo,
                            Toast.LENGTH_LONG).show();
                }
                break;
            case ERROR:
                showTerminalState();
                txtPhase.setText(R.string.error_label);
                txtTime.setText(s.message != null ? s.message : getString(R.string.unknown_error));
                break;
            case CANCELLED:
                showTerminalState();
                txtPhase.setText(R.string.cancelled_label);
                txtTime.setText(s.message != null ? s.message : "");
                break;
            default:
                break;
        }
    }

    /**
     * The part every finished screen (done / error / cancelled, or one restored
     * after rotation) shares. Open/Share/Replace follow the cached results.
     */
    private void showTerminalState() {
        finishedState = true;
        txtBatch.setVisibility(View.GONE);
        txtSpeed.setText("");
        txtSize.setVisibility(View.GONE);
        stopRamTicker();
        txtRam.setText("");
        btnPauseResume.setEnabled(false);
        btnCancel.setText(R.string.close);
        rowResultActions.setVisibility(resultOutputs.isEmpty() ? View.GONE : View.VISIBLE);
        updateReplaceButton();
    }

    private void updateReplaceButton() {
        int count = replace.replaceableCount();
        btnReplace.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        btnReplace.setText(count > 1 ? R.string.replace_originals : R.string.replace_original);
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
        return getString(fmtRes, Formats.bitrate(this, srcBps), Formats.bitrate(this, outBps));
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
