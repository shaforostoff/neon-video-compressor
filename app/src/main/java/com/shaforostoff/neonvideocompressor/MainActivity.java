package com.shaforostoff.neonvideocompressor;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shaforostoff.neonvideocompressor.engine.Options;
import com.shaforostoff.neonvideocompressor.service.ConversionService;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String STATE_URIS = "selected_uris";

    private static final String PREFS = "settings";
    private static final String KEY_VIDEO_MODE = "video_mode";
    private static final String KEY_CRF = "crf";
    private static final String KEY_PRESET = "preset";
    private static final String KEY_AUDIO_MODE = "audio_mode";
    private static final String KEY_AUDIO_BITRATE = "audio_bitrate";

    private SharedPreferences prefs;

    private final ArrayList<Uri> selectedUris = new ArrayList<>();

    private TextView txtFile, txtCrf;
    private SeekBar seekCrf;
    private Spinner spVideoMode, spPreset, spAudioMode, spAudioBitrate;
    private View videoEncodeOptions, audioEncodeOptions;
    private MaterialButton btnConvert, btnPreview;

    private int[] bitrateValues;

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris != null && !uris.isEmpty()) onVideosPicked(uris);
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
        btnPreview = findViewById(R.id.btnPreview);

        bitrateValues = getResources().getIntArray(R.array.audio_bitrate_values);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        // Restore the last-used encoding settings (defaults on first launch).
        setupSpinner(spVideoMode, R.array.video_modes, prefs.getInt(KEY_VIDEO_MODE, 0));
        setupSpinner(spPreset, R.array.presets, prefs.getInt(KEY_PRESET, 6 /* slow */));
        setupSpinner(spAudioMode, R.array.audio_modes, prefs.getInt(KEY_AUDIO_MODE, 0));
        setupSpinner(spAudioBitrate, R.array.audio_bitrate_labels,
                prefs.getInt(KEY_AUDIO_BITRATE, 2 /* 40 kbps */));

        // Video encode options (CRF/preset) are relevant only for "Encode HEVC" (pos 0).
        spVideoMode.setOnItemSelectedListener(new SimpleSelected(pos ->
                videoEncodeOptions.setVisibility(pos == 0 ? View.VISIBLE : View.GONE)));
        // Audio encode options (bitrate) are relevant only for the encode modes (pos 0/1).
        spAudioMode.setOnItemSelectedListener(new SimpleSelected(pos ->
                audioEncodeOptions.setVisibility(pos <= 1 ? View.VISIBLE : View.GONE)));
        // Apply the restored selections' visibility immediately (a listener added
        // after setSelection doesn't get called for the already-set value).
        videoEncodeOptions.setVisibility(
                spVideoMode.getSelectedItemPosition() == 0 ? View.VISIBLE : View.GONE);
        audioEncodeOptions.setVisibility(
                spAudioMode.getSelectedItemPosition() <= 1 ? View.VISIBLE : View.GONE);

        seekCrf.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                txtCrf.setText(String.format(Locale.US, getString(R.string.crf_label), p));
            }

            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        seekCrf.setProgress(prefs.getInt(KEY_CRF, seekCrf.getProgress()));
        txtCrf.setText(String.format(Locale.US, getString(R.string.crf_label), seekCrf.getProgress()));

        ((MaterialButton) findViewById(R.id.btnSelect)).setOnClickListener(v ->
                picker.launch(new String[]{"video/*"}));
        btnPreview.setOnClickListener(v -> onPreviewClicked());
        btnConvert.setOnClickListener(v -> onConvertClicked());
        ((MaterialButton) findViewById(R.id.btnAbout)).setOnClickListener(v -> showAboutDialog());

        // Restore the selection across recreation (e.g. rotating the phone while
        // the preview is open recreates this activity underneath it).
        if (savedInstanceState != null) {
            ArrayList<Uri> saved = savedInstanceState.getParcelableArrayList(STATE_URIS);
            if (saved != null && !saved.isEmpty()) {
                selectedUris.addAll(saved);
                renderSelection();
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelableArrayList(STATE_URIS, selectedUris);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Persist the encoding settings so they carry over to the next launch.
        prefs.edit()
                .putInt(KEY_VIDEO_MODE, spVideoMode.getSelectedItemPosition())
                .putInt(KEY_CRF, seekCrf.getProgress())
                .putInt(KEY_PRESET, spPreset.getSelectedItemPosition())
                .putInt(KEY_AUDIO_MODE, spAudioMode.getSelectedItemPosition())
                .putInt(KEY_AUDIO_BITRATE, spAudioBitrate.getSelectedItemPosition())
                .apply();
    }

    private void showAboutDialog() {
        TextView message = new TextView(this);
        message.setText(readAsset("about.txt"));
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        message.setPadding(pad, pad, pad, pad);
        message.setTextIsSelectable(true);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(message);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setView(scroll)
                .setPositiveButton(R.string.done, null)
                .show();
    }

    private String readAsset(String name) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getAssets().open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        } catch (IOException e) {
            return "";
        }
        return sb.toString();
    }

    private void onVideosPicked(List<Uri> uris) {
        selectedUris.clear();
        selectedUris.addAll(uris);
        for (Uri uri : uris) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        }
        renderSelection();
    }

    /** Updates the file label and button state to reflect {@link #selectedUris}. */
    private void renderSelection() {
        if (selectedUris.size() == 1) {
            txtFile.setText(describe(selectedUris.get(0)));
        } else if (!selectedUris.isEmpty()) {
            txtFile.setText(String.format(Locale.US,
                    getString(R.string.videos_selected), selectedUris.size()));
        } else {
            txtFile.setText(R.string.no_file_selected);
        }
        boolean any = !selectedUris.isEmpty();
        btnConvert.setEnabled(any);
        btnPreview.setEnabled(any);
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
        if (selectedUris.isEmpty()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            startConversion();
        }
    }

    /** Reads the current spinner/seekbar selections into an {@link Options}. */
    private Options buildOptions() {
        Options o = new Options();
        switch (spVideoMode.getSelectedItemPosition()) {
            case 0: o.videoMode = Options.VideoMode.ENCODE_HEVC; break;
            case 1: o.videoMode = Options.VideoMode.COPY; break;
            default: o.videoMode = Options.VideoMode.REMOVE; break;
        }
        o.crf = seekCrf.getProgress();
        o.preset = (String) spPreset.getSelectedItem();
        switch (spAudioMode.getSelectedItemPosition()) {
            case 0: o.audioMode = Options.AudioMode.ENCODE_AAC_LC; break;
            case 1: o.audioMode = Options.AudioMode.ENCODE_AAC_HE; break;
            case 2: o.audioMode = Options.AudioMode.COPY; break;
            default: o.audioMode = Options.AudioMode.REMOVE; break;
        }
        o.audioBitrate = bitrateValues[spAudioBitrate.getSelectedItemPosition()];
        return o;
    }

    private void startConversion() {
        if (selectedUris.isEmpty()) return;
        Options o = buildOptions();

        if (o.removesVideo() && o.removesAudio()) {
            Toast.makeText(this, "Can't remove both video and audio", Toast.LENGTH_LONG).show();
            return;
        }

        ConversionService.start(this, new ArrayList<>(selectedUris), o);
        startActivity(new Intent(this, ProgressActivity.class));
    }

    private void onPreviewClicked() {
        if (selectedUris.isEmpty()) return;
        Options o = buildOptions();
        // Preview compares the encoded result against the source, so it only
        // makes sense when we're actually re-encoding the video.
        if (!o.encodesVideo()) {
            Toast.makeText(this, R.string.preview_needs_encode, Toast.LENGTH_LONG).show();
            return;
        }
        PreviewActivity.start(this, selectedUris.get(0), o);
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
