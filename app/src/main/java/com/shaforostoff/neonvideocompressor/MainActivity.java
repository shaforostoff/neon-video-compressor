package com.shaforostoff.neonvideocompressor;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.shaforostoff.neonvideocompressor.engine.Options;
import com.shaforostoff.neonvideocompressor.service.ConversionService;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private Uri selectedUri;

    private TextView txtFile, txtCrf;
    private SeekBar seekCrf;
    private Spinner spVideoMode, spPreset, spAudioMode, spAudioBitrate;
    private View videoEncodeOptions, audioEncodeOptions;
    private MaterialButton btnConvert;

    private int[] bitrateValues;

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) onVideoPicked(uri);
            });

    private final ActivityResultLauncher<String> notifPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> startConversion());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtFile = findViewById(R.id.txtFile);
        txtCrf = findViewById(R.id.txtCrf);
        seekCrf = findViewById(R.id.seekCrf);
        spVideoMode = findViewById(R.id.spVideoMode);
        spPreset = findViewById(R.id.spPreset);
        spAudioMode = findViewById(R.id.spAudioMode);
        spAudioBitrate = findViewById(R.id.spAudioBitrate);
        videoEncodeOptions = findViewById(R.id.videoEncodeOptions);
        audioEncodeOptions = findViewById(R.id.audioEncodeOptions);
        btnConvert = findViewById(R.id.btnConvert);

        bitrateValues = getResources().getIntArray(R.array.audio_bitrate_values);

        setupSpinner(spVideoMode, R.array.video_modes, 0);
        setupSpinner(spPreset, R.array.presets, 6 /* slow */);
        setupSpinner(spAudioMode, R.array.audio_modes, 0);
        setupSpinner(spAudioBitrate, R.array.audio_bitrate_labels, 2 /* 40 kbps */);

        spVideoMode.setOnItemSelectedListener(new SimpleSelected(pos ->
                videoEncodeOptions.setVisibility(pos == 0 ? View.VISIBLE : View.GONE)));
        spAudioMode.setOnItemSelectedListener(new SimpleSelected(pos ->
                audioEncodeOptions.setVisibility(pos == 2 ? View.GONE : View.VISIBLE)));

        seekCrf.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                txtCrf.setText(String.format(Locale.US, getString(R.string.crf_label), p));
            }

            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        txtCrf.setText(String.format(Locale.US, getString(R.string.crf_label), seekCrf.getProgress()));

        ((MaterialButton) findViewById(R.id.btnSelect)).setOnClickListener(v ->
                picker.launch(new String[]{"video/*"}));
        btnConvert.setOnClickListener(v -> onConvertClicked());
    }

    private void onVideoPicked(Uri uri) {
        selectedUri = uri;
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        txtFile.setText(describe(uri));
        btnConvert.setEnabled(true);
    }

    private String describe(Uri uri) {
        String name = queryName(uri);
        String dur = "";
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            String ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (ms != null) {
                long s = Long.parseLong(ms) / 1000;
                dur = String.format(Locale.US, "  (%02d:%02d)", s / 60, s % 60);
            }
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
        return (name != null ? name : uri.getLastPathSegment()) + dur;
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(
                uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void onConvertClicked() {
        if (selectedUri == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            startConversion();
        }
    }

    private void startConversion() {
        if (selectedUri == null) return;
        Options o = new Options();
        o.videoMode = spVideoMode.getSelectedItemPosition() == 0
                ? Options.VideoMode.ENCODE_HEVC : Options.VideoMode.COPY;
        o.crf = seekCrf.getProgress();
        o.preset = (String) spPreset.getSelectedItem();
        switch (spAudioMode.getSelectedItemPosition()) {
            case 0: o.audioMode = Options.AudioMode.ENCODE_AAC_LC; break;
            case 1: o.audioMode = Options.AudioMode.ENCODE_AAC_HE; break;
            default: o.audioMode = Options.AudioMode.COPY; break;
        }
        o.audioBitrate = bitrateValues[spAudioBitrate.getSelectedItemPosition()];

        ConversionService.start(this, selectedUri, o);
        startActivity(new Intent(this, ProgressActivity.class));
    }

    private void setupSpinner(Spinner spinner, int arrayRes, int defaultPos) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, arrayRes, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(defaultPos);
    }

    /** Minimal OnItemSelectedListener wrapper. */
    private static final class SimpleSelected
            implements android.widget.AdapterView.OnItemSelectedListener {
        interface Cb { void onPos(int pos); }
        private final Cb cb;
        SimpleSelected(Cb cb) { this.cb = cb; }
        @Override
        public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
            cb.onPos(pos);
        }
        @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
    }
}
