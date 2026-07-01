package com.shaforostoff.neonvideocompressor;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.shaforostoff.neonvideocompressor.service.ConversionService;

import java.util.Locale;

public class ProgressActivity extends AppCompatActivity {

    private TextView txtBatch, txtPhase, txtPercent, txtTime, txtSpeed, txtRam;
    private ProgressBar progressBar;
    private MaterialButton btnPauseResume, btnCancel;

    // getPss() walks /proc/self/smaps, so sample it at most once per second
    // rather than on every per-frame snapshot.
    private static final long RAM_SAMPLE_INTERVAL_MS = 1000L;
    private long lastRamSampleMs;

    private ConversionService service;
    private boolean bound;
    private boolean paused;
    private boolean finishedState;

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
        txtRam = findViewById(R.id.txtRam);
        progressBar = findViewById(R.id.progressBar);
        btnPauseResume = findViewById(R.id.btnPauseResume);
        btnCancel = findViewById(R.id.btnCancel);

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
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, ConversionService.class), connection, 0);
    }

    @Override
    protected void onStop() {
        super.onStop();
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
                    txtBatch.setText(String.format(Locale.US, "File %d of %d%s",
                            s.batchIndex + 1, s.batchTotal, name));
                } else {
                    txtBatch.setVisibility(View.GONE);
                }
                txtPhase.setText(phaseLabel(s) + (paused ? " (paused)" : ""));
                txtTime.setText(formatTime(s.processedUs) + " / " + formatTime(s.durationUs));
                txtSpeed.setText(s.speed > 0
                        ? String.format(Locale.US, "Speed: %.2f× realtime", s.speed)
                        : "Speed: —");
                btnPauseResume.setText(paused ? R.string.resume : R.string.pause);
                btnPauseResume.setEnabled(true);
                updateRam();
                break;
            case DONE:
                finishedState = true;
                txtBatch.setVisibility(View.GONE);
                txtPhase.setText(batch ? "Batch complete" : "Done");
                txtTime.setText(s.message != null ? s.message : "");
                String savedTo = s.audioOnly ? "Saved to Music" : "Saved to Movies";
                txtSpeed.setText(savedTo);
                txtRam.setText("");
                btnPauseResume.setEnabled(false);
                btnCancel.setText("Close");
                Toast.makeText(this, s.message != null ? s.message : savedTo,
                        Toast.LENGTH_LONG).show();
                break;
            case ERROR:
                finishedState = true;
                txtBatch.setVisibility(View.GONE);
                txtPhase.setText("Error");
                txtTime.setText(s.message != null ? s.message : "Unknown error");
                txtSpeed.setText("");
                txtRam.setText("");
                btnPauseResume.setEnabled(false);
                btnCancel.setText("Close");
                break;
            case CANCELLED:
                finishedState = true;
                txtBatch.setVisibility(View.GONE);
                txtPhase.setText("Cancelled");
                txtTime.setText(s.message != null ? s.message : "");
                txtRam.setText("");
                btnPauseResume.setEnabled(false);
                btnCancel.setText("Close");
                break;
            default:
                break;
        }
    }

    // Total process PSS (Java heap + native x265/FFmpeg allocations + mapped code),
    // which is the memory the OS actually attributes to the app.
    private void updateRam() {
        long now = SystemClock.uptimeMillis();
        if (lastRamSampleMs != 0 && now - lastRamSampleMs < RAM_SAMPLE_INTERVAL_MS) return;
        lastRamSampleMs = now;
        long pssKb = Debug.getPss(); // total PSS of this process, in KB
        txtRam.setText(String.format(Locale.US, "RAM: %d MB", (pssKb + 512) / 1024));
    }

    private String phaseLabel(ConversionService.Snapshot s) {
        switch (s.phase) {
            case VIDEO: return "Encoding video";
            case AUDIO: return "Encoding audio";
            case MUXING: return "Finalizing";
            case PUBLISHING: return "Saving";
            default: return "Preparing";
        }
    }

    private static String formatTime(long us) {
        long totalSec = us / 1_000_000L;
        return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60);
    }
}
