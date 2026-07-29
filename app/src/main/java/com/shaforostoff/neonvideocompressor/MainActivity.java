package com.shaforostoff.neonvideocompressor;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shaforostoff.neonvideocompressor.engine.HardwareEncoderInfo;
import com.shaforostoff.neonvideocompressor.engine.Options;
import com.shaforostoff.neonvideocompressor.engine.SourceMetadata;
import com.shaforostoff.neonvideocompressor.service.ConversionService;
import com.shaforostoff.neonvideocompressor.service.ResultStore;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String STATE_URIS = "selected_uris";

    private static final String PREFS = "settings";
    // Stores the VideoMode enum name. A new key (vs. the old int "video_mode") so
    // an existing install's int value can't be read back as a String and crash.
    private static final String KEY_VIDEO_MODE = "video_mode_enum";
    private static final String KEY_CRF = "crf";
    private static final String KEY_HW_QUALITY = "hw_quality";
    private static final String KEY_HW_CBR_BITRATE = "hw_cbr_bitrate_bps";
    private static final String KEY_HW_VBR_BITRATE = "hw_vbr_bitrate_bps";
    private static final String KEY_HW_VBR = "hw_vbr";

    // Hardware bitrate slider works in 0.1 Mbps steps (so 1.5 Mbps etc. are
    // selectable). The maximum is capped at the source file's own bitrate — going
    // above it can't add quality — falling back to this ceiling when unknown.
    private static final int HW_BITRATE_STEP_BPS = 100_000;       // 0.1 Mbps
    private static final int HW_BITRATE_MIN_BPS = 100_000;        // 0.1 Mbps
    private static final int HW_BITRATE_FALLBACK_MAX_BPS = 50_000_000; // 50 Mbps
    // First-launch bitrates. VBR (the default rate control) treats its target as an
    // average, so it lands far smaller than the same number under CBR.
    private static final int DEFAULT_HW_VBR_BITRATE_BPS = 2_000_000; // 2000 kbps
    private static final int DEFAULT_HW_CBR_BITRATE_BPS = 3_000_000; // 3000 kbps
    private static final String KEY_PRESET = "preset";
    private static final String KEY_AUDIO_MODE = "audio_mode";
    private static final String KEY_AUDIO_BITRATE = "audio_bitrate";
    // Whether we've already offered the Doze battery-optimization exemption, so
    // the prompt is shown at most once rather than on every conversion.
    private static final String KEY_BATTERY_PROMPTED = "battery_prompted";

    private SharedPreferences prefs;

    private final ArrayList<Uri> selectedUris = new ArrayList<>();

    private TextView txtFile, txtCrf;
    private SeekBar seekCrf;
    private Spinner spVideoMode, spPreset, spAudioMode, spAudioBitrate;
    private View videoEncodeOptions, audioEncodeOptions, presetGroup;
    private MaterialButton btnConvert, btnPreview;

    private int[] bitrateValues;

    // The video-mode spinner is built at runtime because the hardware-HEVC entry
    // is only present when a HW encoder exists and its label carries the chip
    // vendor. This parallel list maps each spinner row to its VideoMode.
    private final List<Options.VideoMode> videoModes = new ArrayList<>();
    private HardwareEncoderInfo hwInfo;

    // The single quality SeekBar is shared between the two encode modes, which use
    // different scales (x265 CRF 0–51, lower = better; hardware quality 0–100,
    // higher = better). We keep each mode's value here so switching modes restores
    // the right one rather than reinterpreting the raw slider position.
    private RadioGroup bitrateModeGroup;

    private int x265Crf;
    private int hwQuality;        // CQ mode, 0–100
    private int hwCbrBitrateBps;  // CBR bitrate, bits/sec
    private int hwVbrBitrateBps;  // VBR bitrate, bits/sec (kept separate from CBR)
    private boolean hwVbr;        // false = CBR, true = VBR
    // Bitrate (bits/sec) of the currently selected source, used to cap the slider
    // (0 = unknown, e.g. nothing selected yet -> fall back to the fixed ceiling).
    private int sourceBitrateBps;

    // Folder/document browser (any provider, any folder) — reliable for videos
    // tucked away outside the media gallery, but many providers don't actually
    // filter their file listing by the requested mime type, so JPGs etc. can
    // still show up alongside videos in a mixed folder.
    private final ActivityResultLauncher<String[]> filesPicker =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris != null && !uris.isEmpty()) onVideosPicked(uris);
            });

    // System Photo Picker, restricted to videos — properly filtered (no photos
    // ever shown), but limited to gallery-indexed media (can't browse arbitrary
    // folders or cloud-storage apps).
    private final ActivityResultLauncher<PickVisualMediaRequest> videosPicker =
            registerForActivityResult(new ActivityResultContracts.PickMultipleVisualMedia(), uris -> {
                if (uris != null && !uris.isEmpty()) onVideosPicked(uris);
            });

    private final ActivityResultLauncher<String> notifPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> startConversion());

    // The battery-optimization exemption dialog reports its result via
    // isIgnoringBatteryOptimizations rather than the result code, so we just
    // continue with the conversion whatever the user chose in the system dialog.
    private final ActivityResultLauncher<Intent> batteryExemption =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> startConversion());

    // Not required for the Photo Picker itself (it grants per-item read access
    // on its own); only requested so SourceMetadata can recover the real
    // filename when an OEM's MediaProvider fails to resolve a picker Uri.
    // Launch proceeds either way, so declining just means a possible numeric
    // filename on affected devices rather than a blocked picker.
    private final ActivityResultLauncher<String> videoLibraryPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> launchVideoPicker());

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
        presetGroup = findViewById(R.id.presetGroup);
        bitrateModeGroup = findViewById(R.id.bitrateModeGroup);
        btnConvert = findViewById(R.id.btnConvert);
        btnPreview = findViewById(R.id.btnPreview);

        bitrateValues = getResources().getIntArray(R.array.audio_bitrate_values);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        // Restore the last-used encoding settings (defaults on first launch).
        x265Crf = prefs.getInt(KEY_CRF, 30);
        hwQuality = prefs.getInt(KEY_HW_QUALITY, 60);
        // CBR and VBR each remember their own bitrate, so switching rate control
        // doesn't drag the other mode's number along.
        hwCbrBitrateBps = prefs.getInt(KEY_HW_CBR_BITRATE, DEFAULT_HW_CBR_BITRATE_BPS);
        hwVbrBitrateBps = prefs.getInt(KEY_HW_VBR_BITRATE, DEFAULT_HW_VBR_BITRATE_BPS);
        hwVbr = prefs.getBoolean(KEY_HW_VBR, true);
        bitrateModeGroup.check(hwVbr ? R.id.rbVbr : R.id.rbCbr);
        setupVideoModeSpinner();
        setupSpinner(spPreset, R.array.presets, prefs.getInt(KEY_PRESET, 6 /* slow */));
        setupSpinner(spAudioMode, R.array.audio_modes, prefs.getInt(KEY_AUDIO_MODE, 0));
        setupSpinner(spAudioBitrate, R.array.audio_bitrate_labels,
                prefs.getInt(KEY_AUDIO_BITRATE, 2 /* 40 kbps */));

        // Show the video encode controls (and pick the right quality scale) for the
        // selected mode; only the encode modes have any.
        spVideoMode.setOnItemSelectedListener(new SimpleSelected(this::applyVideoModeUi));
        // Audio encode options (bitrate) are relevant only for the encode modes (pos 0/1/2).
        spAudioMode.setOnItemSelectedListener(new SimpleSelected(pos ->
                audioEncodeOptions.setVisibility(pos <= 2 ? View.VISIBLE : View.GONE)));
        // Apply the restored selections' visibility immediately (a listener added
        // after setSelection doesn't get called for the already-set value).
        applyVideoModeUi(spVideoMode.getSelectedItemPosition());
        audioEncodeOptions.setVisibility(
                spAudioMode.getSelectedItemPosition() <= 2 ? View.VISIBLE : View.GONE);

        seekCrf.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                // Remember the value under whichever mode's scale is active.
                if (currentVideoMode() == Options.VideoMode.ENCODE_HEVC) {
                    x265Crf = p;
                } else if (currentVideoMode() == Options.VideoMode.ENCODE_HEVC_HW) {
                    if (hwUsesCq()) hwQuality = p;
                    else setActiveHwBitrate(Math.max(HW_BITRATE_MIN_BPS, p * HW_BITRATE_STEP_BPS));
                }
                updateQualityLabel();
            }

            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        bitrateModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            hwVbr = checkedId == R.id.rbVbr;
            // Switch the slider to the newly-selected mode's own remembered bitrate.
            applyVideoModeUi(spVideoMode.getSelectedItemPosition());
        });

        ((MaterialButton) findViewById(R.id.btnSelectVideos)).setOnClickListener(v -> onSelectVideosClicked());
        ((MaterialButton) findViewById(R.id.btnSelectFiles)).setOnClickListener(v ->
                filesPicker.launch(new String[]{"video/*"}));
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

        // Videos "sent"/"shared" to us from another app (e.g. the Photos share sheet).
        handleShareIntent(getIntent());

        // If a previous conversion finished but the process was killed before the
        // user could act on it (e.g. a big encode left the app bloated and the OS
        // reaped it while cached), jump straight to that finished screen so Open /
        // Share / Replace are reachable. Only on a genuine cold launch, and never
        // when a share brought us here with a fresh selection to convert.
        if (savedInstanceState == null && selectedUris.isEmpty()
                && ResultStore.hasPending(this) && ResultStore.load(this) != null) {
            startActivity(new Intent(this, ProgressActivity.class));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleTop: a share that arrives while we're already running is delivered
        // here instead of onCreate.
        setIntent(intent);
        handleShareIntent(intent);
    }

    /**
     * If we were launched via the system share sheet ({@code ACTION_SEND} /
     * {@code ACTION_SEND_MULTIPLE}), adopt the shared video(s) as the current
     * selection so the user can go straight to converting.
     */
    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        List<Uri> shared = null;
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) shared = Collections.singletonList(uri);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null && !uris.isEmpty()) shared = uris;
        }
        if (shared != null) onVideosPicked(shared);
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
                .putString(KEY_VIDEO_MODE, currentVideoMode().name())
                .putInt(KEY_CRF, x265Crf)
                .putInt(KEY_HW_QUALITY, hwQuality)
                .putInt(KEY_HW_CBR_BITRATE, hwCbrBitrateBps)
                .putInt(KEY_HW_VBR_BITRATE, hwVbrBitrateBps)
                .putBoolean(KEY_HW_VBR, hwVbr)
                .putInt(KEY_PRESET, spPreset.getSelectedItemPosition())
                .putInt(KEY_AUDIO_MODE, spAudioMode.getSelectedItemPosition())
                .putInt(KEY_AUDIO_BITRATE, spAudioBitrate.getSelectedItemPosition())
                .apply();
    }

    private void showAboutDialog() {
        TextView message = new TextView(this);
        message.setText(readAboutText());
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

    /** Picks the localized about text for the current language, falling back to English. */
    private String readAboutText() {
        String language = getResources().getConfiguration().getLocales().get(0).getLanguage();
        String localized = readAsset("about_" + language + ".txt");
        return !localized.isEmpty() ? localized : readAsset("about.txt");
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
        // Cap the hardware bitrate slider at the source's own bitrate (using the
        // first video for a batch). Re-apply if that control is on screen.
        sourceBitrateBps = probeBitrate(selectedUris.isEmpty() ? null : selectedUris.get(0));
        applyVideoModeUi(spVideoMode.getSelectedItemPosition());
        renderSelection();
    }

    /** Overall container bitrate (bits/sec) of a source, or 0 if unknown. */
    private int probeBitrate(Uri uri) {
        if (uri == null) return 0;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            String b = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            return b != null ? Integer.parseInt(b) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
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
        return SourceMetadata.queryDisplayName(this, uri);
    }

    private void onSelectVideosClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) {
            videoLibraryPermission.launch(Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            launchVideoPicker();
        }
    }

    private void launchVideoPicker() {
        videosPicker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE)
                .build());
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
        o.videoMode = currentVideoMode();
        o.crf = x265Crf;
        o.preset = (String) spPreset.getSelectedItem();
        o.hwQuality = hwQuality;
        o.hwBitrate = Math.max(HW_BITRATE_MIN_BPS, activeHwBitrate());
        o.hwBitrateMode = hwVbr ? Options.HwBitrateMode.VBR : Options.HwBitrateMode.CBR;
        switch (spAudioMode.getSelectedItemPosition()) {
            case 0: o.audioMode = Options.AudioMode.ENCODE_AAC_LC; break;
            case 1: o.audioMode = Options.AudioMode.ENCODE_AAC_HE; break;
            case 2: o.audioMode = Options.AudioMode.ENCODE_AAC_HE_V2; break;
            case 3: o.audioMode = Options.AudioMode.COPY; break;
            default: o.audioMode = Options.AudioMode.REMOVE; break;
        }
        o.audioBitrate = bitrateValues[spAudioBitrate.getSelectedItemPosition()];
        return o;
    }

    private void startConversion() {
        if (selectedUris.isEmpty()) return;
        Options o = buildOptions();

        if (o.removesVideo() && o.removesAudio()) {
            Toast.makeText(this, R.string.remove_both_error, Toast.LENGTH_LONG).show();
            return;
        }

        // Offer the Doze exemption before the first encode. Without it, a long
        // encode freezes once the device enters deep Doze with the screen off,
        // because the system stops honouring our PARTIAL_WAKE_LOCK. The dialog is
        // shown at most once; whatever the user picks, we then start converting.
        if (shouldOfferBatteryExemption()) {
            showBatteryExemptionDialog();
            return;
        }

        ConversionService.start(this, new ArrayList<>(selectedUris), o);
        startActivity(new Intent(this, ProgressActivity.class));
    }

    /** True only when we haven't asked yet and aren't already exempt from Doze. */
    private boolean shouldOfferBatteryExemption() {
        if (prefs.getBoolean(KEY_BATTERY_PROMPTED, false)) return false;
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void showBatteryExemptionDialog() {
        // Remember that we've offered it, so declining doesn't loop back here when
        // the exemption launcher (or "Not now") calls startConversion() again.
        prefs.edit().putBoolean(KEY_BATTERY_PROMPTED, true).apply();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.battery_opt_title)
                .setMessage(R.string.battery_opt_message)
                .setCancelable(false)
                .setNegativeButton(R.string.battery_opt_not_now, (d, w) -> startConversion())
                .setPositiveButton(R.string.battery_opt_allow, (d, w) -> {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    try {
                        batteryExemption.launch(i);
                    } catch (android.content.ActivityNotFoundException e) {
                        // No handler on this device — just proceed with the encode.
                        startConversion();
                    }
                })
                .show();
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

    /**
     * Builds the video-mode spinner from the localized base modes (x265, Copy,
     * Remove), splicing in a hardware "Encode HEVC (vendor)" row right after the
     * x265 one when this device has a hardware HEVC encoder. {@link #videoModes}
     * is kept in lock-step so a spinner position maps back to its VideoMode.
     */
    private void setupVideoModeSpinner() {
        hwInfo = HardwareEncoderInfo.detect();
        String[] base = getResources().getStringArray(R.array.video_modes); // x265, Copy, Remove

        List<String> labels = new ArrayList<>();
        videoModes.clear();

        labels.add(base[0]);
        videoModes.add(Options.VideoMode.ENCODE_HEVC);
        if (hwInfo != null) {
            labels.add(getString(R.string.encode_hevc_hw, hwInfo.vendorLabel()));
            videoModes.add(Options.VideoMode.ENCODE_HEVC_HW);
        }
        labels.add(base[1]);
        videoModes.add(Options.VideoMode.COPY);
        labels.add(base[2]);
        videoModes.add(Options.VideoMode.REMOVE);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spVideoMode.setAdapter(adapter);

        Options.VideoMode saved = parseMode(prefs.getString(KEY_VIDEO_MODE, null));
        int idx = videoModes.indexOf(saved);
        spVideoMode.setSelection(idx >= 0 ? idx : 0);
    }

    private Options.VideoMode parseMode(String name) {
        if (name == null) return defaultVideoMode();
        try {
            return Options.VideoMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return defaultVideoMode();
        }
    }

    /**
     * Hardware HEVC is the default on devices that have a HW encoder (much faster,
     * far less battery); x265 only where there's none. Requires {@link #hwInfo} to
     * have been detected, i.e. call it from {@link #setupVideoModeSpinner} onwards.
     */
    private Options.VideoMode defaultVideoMode() {
        return hwInfo != null
                ? Options.VideoMode.ENCODE_HEVC_HW : Options.VideoMode.ENCODE_HEVC;
    }

    private Options.VideoMode currentVideoMode() {
        int pos = spVideoMode.getSelectedItemPosition();
        return pos >= 0 && pos < videoModes.size()
                ? videoModes.get(pos) : Options.VideoMode.ENCODE_HEVC;
    }

    /** Shows/hides the encode controls and picks the quality scale for the mode. */
    private void applyVideoModeUi(int pos) {
        Options.VideoMode mode = pos >= 0 && pos < videoModes.size()
                ? videoModes.get(pos) : Options.VideoMode.ENCODE_HEVC;
        boolean x265 = mode == Options.VideoMode.ENCODE_HEVC;
        boolean hw = mode == Options.VideoMode.ENCODE_HEVC_HW;

        videoEncodeOptions.setVisibility(x265 || hw ? View.VISIBLE : View.GONE);
        presetGroup.setVisibility(x265 ? View.VISIBLE : View.GONE); // no preset for HW
        // The CBR/VBR selector belongs only to the hardware bitrate path.
        boolean hwBitrate = hw && !hwUsesCq();
        bitrateModeGroup.setVisibility(hwBitrate ? View.VISIBLE : View.GONE);
        if (x265) {
            seekCrf.setMax(51);
            seekCrf.setProgress(x265Crf);
        } else if (hw && hwUsesCq()) {
            seekCrf.setMax(100);
            seekCrf.setProgress(hwQuality);
        } else if (hwBitrate) {
            int maxSteps = bitrateMaxSteps();
            seekCrf.setMax(maxSteps);
            int bps = Math.min(activeHwBitrate(), maxSteps * HW_BITRATE_STEP_BPS);
            setActiveHwBitrate(bps);
            int steps = Math.max(1, Math.min(maxSteps, bps / HW_BITRATE_STEP_BPS));
            seekCrf.setProgress(steps);
        }
        updateQualityLabel();
    }

    /** True when the hardware encoder offers constant-quality (CQ); VBR otherwise. */
    private boolean hwUsesCq() {
        return hwInfo != null && hwInfo.supportsCq();
    }

    /** The bitrate value for the currently selected rate-control mode. */
    private int activeHwBitrate() {
        return hwVbr ? hwVbrBitrateBps : hwCbrBitrateBps;
    }

    private void setActiveHwBitrate(int bps) {
        if (hwVbr) hwVbrBitrateBps = bps;
        else hwCbrBitrateBps = bps;
    }

    /** Slider steps for the bitrate mode: source bitrate (or fallback ceiling) in 0.1 Mbps units. */
    private int bitrateMaxSteps() {
        int capBps = sourceBitrateBps > 0 ? sourceBitrateBps : HW_BITRATE_FALLBACK_MAX_BPS;
        return Math.max(1, capBps / HW_BITRATE_STEP_BPS);
    }

    /** Formats the slider label: CRF for x265, quality% for HW-CQ, Mbps for HW bitrate. */
    private void updateQualityLabel() {
        if (currentVideoMode() == Options.VideoMode.ENCODE_HEVC_HW) {
            if (hwUsesCq()) {
                txtCrf.setText(String.format(Locale.US,
                        getString(R.string.hw_quality_label), seekCrf.getProgress()));
            } else {
                double mbps = Math.max(1, seekCrf.getProgress()) * HW_BITRATE_STEP_BPS / 1_000_000.0;
                txtCrf.setText(String.format(Locale.US,
                        getString(R.string.hw_bitrate_label),
                        String.format(Locale.US, "%.1f", mbps)));
            }
        } else {
            txtCrf.setText(String.format(Locale.US,
                    getString(R.string.crf_label), seekCrf.getProgress()));
        }
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
