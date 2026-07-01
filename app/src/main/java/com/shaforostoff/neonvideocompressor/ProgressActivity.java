package com.shaforostoff.neonvideocompressor;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.shaforostoff.neonvideocompressor.service.ConversionService;

import java.util.Locale;

public class ProgressActivity extends AppCompatActivity {

    private TextView txtPhase, txtPercent, txtTime, txtSpeed;
    private ProgressBar progressBar;
    private MaterialButton btnPauseResume, btnCancel;

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

        txtPhase = findViewById(R.id.txtPhase);
        txtPercent = findViewById(R.id.txtPercent);
        txtTime = findViewById(R.id.txtTime);
        txtSpeed = findViewById(R.id.txtSpeed);
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
        int percent = Math.round(s.overall * 100);
        progressBar.setProgress(percent);
        txtPercent.setText(percent + "%");

        switch (s.status) {
            case RUNNING:
            case PAUSED:
                paused = s.status == ConversionService.Status.PAUSED;
                txtPhase.setText(phaseLabel(s) + (paused ? " (paused)" : ""));
                txtTime.setText(formatTime(s.processedUs) + " / " + formatTime(s.durationUs));
                txtSpeed.setText(s.speed > 0
                        ? String.format(Locale.US, "Speed: %.2f× realtime", s.speed)
                        : "Speed: —");
                btnPauseResume.setText(paused ? R.string.resume : R.string.pause);
                btnPauseResume.setEnabled(true);
                break;
            case DONE:
                finishedState = true;
                txtPhase.setText("Done");
                txtTime.setText(s.message != null ? s.message : "");
                txtSpeed.setText("Saved to Movies");
                btnPauseResume.setEnabled(false);
                btnCancel.setText("Close");
                Toast.makeText(this, "Saved to Movies", Toast.LENGTH_LONG).show();
                break;
            case ERROR:
                finishedState = true;
                txtPhase.setText("Error");
                txtTime.setText(s.message != null ? s.message : "Unknown error");
                txtSpeed.setText("");
                btnPauseResume.setEnabled(false);
                btnCancel.setText("Close");
                break;
            case CANCELLED:
                finishedState = true;
                txtPhase.setText("Cancelled");
                btnPauseResume.setEnabled(false);
                btnCancel.setText("Close");
                break;
            default:
                break;
        }
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
