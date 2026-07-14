package com.shaforostoff.neonvideocompressor;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
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

import com.google.android.material.button.MaterialButton;
import com.shaforostoff.neonvideocompressor.service.ConversionService;

import java.util.ArrayList;
import java.util.Locale;

public class ProgressActivity extends AppCompatActivity {

    private TextView txtBatch, txtPhase, txtPercent, txtTime, txtSpeed, txtSize, txtRam;
    private ProgressBar progressBar;
    private MaterialButton btnPauseResume, btnCancel, btnOpen, btnShare;
    private View rowResultActions;

    // Cached results so Open/Share survive rotation at DONE (the service may have
    // already stopped, so the recreated activity can't rely on a fresh snapshot).
    private final ArrayList<Uri> resultOutputs = new ArrayList<>();
    private boolean resultAudioOnly;

    private static final String STATE_OUTPUTS = "result_outputs";
    private static final String STATE_AUDIO_ONLY = "result_audio_only";
    private static final String STATE_FINISHED = "finished_state";

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

        btnPauseResume.setOnClickListener(v -> {
            if (!bound) return;
            if (paused) service.resume();
            else service.pause();
        });
        btnCancel.setOnClickListener(v -> {
            if (finishedState) {
                finish();
            } else if (bound) {
                service.cancel();
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

        // Restore cached results so Open/Share work after a rotation at DONE even
        // if the service has already stopped and the rebind finds it gone.
        if (savedInstanceState != null) {
            ArrayList<Uri> saved = savedInstanceState.getParcelableArrayList(STATE_OUTPUTS);
            if (saved != null) resultOutputs.addAll(saved);
            resultAudioOnly = savedInstanceState.getBoolean(STATE_AUDIO_ONLY);
            finishedState = savedInstanceState.getBoolean(STATE_FINISHED);
            if (finishedState) {
                btnPauseResume.setEnabled(false);
                btnCancel.setText(R.string.close);
                rowResultActions.setVisibility(resultOutputs.isEmpty() ? View.GONE : View.VISIBLE);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putParcelableArrayList(STATE_OUTPUTS, resultOutputs);
        out.putBoolean(STATE_AUDIO_ONLY, resultAudioOnly);
        out.putBoolean(STATE_FINISHED, finishedState);
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

    private void render(ConversionService.Snapshot s) {
        boolean batch = s.batchTotal > 1;
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
                        ? getString(R.string.phase_paused_format, phaseLabel(s))
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
                rowResultActions.setVisibility(View.GONE);
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
                rowResultActions.setVisibility(resultOutputs.isEmpty() ? View.GONE : View.VISIBLE);
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
                break;
            default:
                break;
        }
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
