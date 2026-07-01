package com.shaforostoff.neonvideocompressor.engine;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.OpenableColumns;

import java.io.File;

/**
 * Orchestrates a single conversion: probe -> (video pass) -> (audio pass) ->
 * mux -> publish to MediaStore. Runs synchronously on a worker thread; pause and
 * cancel are driven through the shared {@link JobControl}.
 */
public class ConversionJob {

    public enum Phase {PROBING, VIDEO, AUDIO, MUXING, PUBLISHING}

    public interface Listener {
        void onProgress(Phase phase, long processedUs, long durationUs, double speed, float overall);

        void onCompleted(Uri output, String displayName);

        void onError(String message);

        void onCancelled();
    }

    private final Context context;
    private final Uri inputUri;
    private final Options options;
    private final JobControl control;
    private final Listener listener;

    private long durationUs = 0;

    // weights for overall progress
    private float wVideo, wAudio, wMux;

    // speed tracking (within the current pass)
    private Phase currentPhase = Phase.PROBING;
    private long lastUpdateMs = 0;
    private long lastProcessedUs = 0;
    private double speed = 0;

    public ConversionJob(Context context, Uri inputUri, Options options,
                         JobControl control, Listener listener) {
        this.context = context.getApplicationContext();
        this.inputUri = inputUri;
        this.options = options;
        this.control = control;
        this.listener = listener;
    }

    public void run() {
        File cache = context.getCacheDir();
        File videoTemp = new File(cache, "video_tmp.mp4");
        File audioTemp = new File(cache, "audio_tmp.m4a");
        File finalTemp = new File(cache, "out_tmp.mp4");
        deleteQuietly(videoTemp, audioTemp, finalTemp);

        ParcelFileDescriptor inputPfd = null;
        try {
            inputPfd = context.getContentResolver().openFileDescriptor(inputUri, "r");
            if (inputPfd == null) {
                listener.onError("Cannot open the selected video");
                return;
            }
            int inputFd = inputPfd.getFd();

            // --- Probe ---
            setPhase(Phase.PROBING);
            long[] probe = NativeConverter.nativeProbe(inputFd);
            durationUs = probe[0];
            boolean hasAudio = probe[1] == 1;
            boolean hasVideo = probe[5] == 1;
            if (!hasVideo) {
                listener.onError("No video track found in the input");
                return;
            }
            if (durationUs <= 0) durationUs = 1; // avoid div-by-zero in fractions

            boolean encodeVideo = options.encodesVideo();
            boolean encodeAudio = options.encodesAudio() && hasAudio;
            boolean copyAudio = options.audioMode == Options.AudioMode.COPY && hasAudio;
            computeWeights(encodeVideo, encodeAudio);

            // --- Video pass ---
            if (encodeVideo) {
                setPhase(Phase.VIDEO);
                int r = NativeConverter.nativeTranscodeVideo(
                        inputFd, videoTemp.getAbsolutePath(), options.crf, options.preset,
                        control.nativeHandle(),
                        processedUs -> report(Phase.VIDEO, processedUs));
                if (r == NativeConverter.RET_CANCELLED || control.cancelled) {
                    listener.onCancelled();
                    return;
                }
                if (r != NativeConverter.RET_OK) {
                    listener.onError("Video encoding failed (code " + r + ")");
                    return;
                }
            }

            // --- Audio pass ---
            if (encodeAudio) {
                setPhase(Phase.AUDIO);
                int r = AudioEncoder.encode(
                        inputPfd.getFileDescriptor(), audioTemp.getAbsolutePath(),
                        options.aacProfile(), options.audioBitrate, control,
                        processedUs -> report(Phase.AUDIO, processedUs));
                if (r == AudioEncoder.RESULT_CANCELLED || control.cancelled) {
                    listener.onCancelled();
                    return;
                }
                if (r == AudioEncoder.RESULT_NO_AUDIO) {
                    encodeAudio = false; // nothing to mux
                }
            }

            // --- Mux pass ---
            setPhase(Phase.MUXING);
            ParcelFileDescriptor videoPfd = null;
            ParcelFileDescriptor audioPfd = null;
            int muxResult;
            try {
                videoPfd = encodeVideo
                        ? ParcelFileDescriptor.open(videoTemp, ParcelFileDescriptor.MODE_READ_ONLY)
                        : context.getContentResolver().openFileDescriptor(inputUri, "r");

                if (encodeAudio) {
                    audioPfd = ParcelFileDescriptor.open(audioTemp, ParcelFileDescriptor.MODE_READ_ONLY);
                } else if (copyAudio) {
                    audioPfd = context.getContentResolver().openFileDescriptor(inputUri, "r");
                }

                int audioFd = audioPfd != null ? audioPfd.getFd() : -1;
                muxResult = NativeConverter.nativeRemux(
                        videoPfd.getFd(), audioFd, finalTemp.getAbsolutePath(), encodeVideo);
            } finally {
                closeQuietly(videoPfd);
                closeQuietly(audioPfd);
            }
            if (muxResult != NativeConverter.RET_OK) {
                listener.onError("Muxing failed (code " + muxResult + ")");
                return;
            }

            if (control.cancelled) {
                listener.onCancelled();
                return;
            }

            // --- Publish ---
            setPhase(Phase.PUBLISHING);
            String displayName = buildOutputName();
            Uri out = MediaStoreOutput.publish(context, finalTemp, displayName);
            listener.onCompleted(out, displayName);

        } catch (Exception e) {
            if (control.cancelled) {
                listener.onCancelled();
            } else {
                listener.onError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        } finally {
            deleteQuietly(videoTemp, audioTemp, finalTemp);
            closeQuietly(inputPfd);
        }
    }

    // -------------------------------------------------------------------------

    private void computeWeights(boolean video, boolean audio) {
        if (video && audio) {
            wVideo = 0.88f;
            wAudio = 0.10f;
            wMux = 0.02f;
        } else if (video) {
            wVideo = 0.97f;
            wAudio = 0f;
            wMux = 0.03f;
        } else if (audio) {
            wVideo = 0f;
            wAudio = 0.95f;
            wMux = 0.05f;
        } else {
            wVideo = 0f;
            wAudio = 0f;
            wMux = 1f;
        }
    }

    private void setPhase(Phase phase) {
        currentPhase = phase;
        lastUpdateMs = 0;
        lastProcessedUs = 0;
        speed = 0;
        float base;
        switch (phase) {
            case VIDEO:
                base = 0f;
                break;
            case AUDIO:
                base = wVideo;
                break;
            case MUXING:
            case PUBLISHING:
                base = wVideo + wAudio;
                break;
            default:
                base = 0f;
        }
        listener.onProgress(phase, 0, durationUs, 0, base);
    }

    private void report(Phase phase, long processedUs) {
        long nowMs = SystemClock.elapsedRealtime();
        if (lastUpdateMs > 0) {
            long dWall = nowMs - lastUpdateMs;
            long dUs = processedUs - lastProcessedUs;
            // Ignore long gaps (paused / stalled) so the speed reflects active work.
            if (dWall > 0 && dWall < 2000 && dUs >= 0) {
                double inst = (dUs / 1000.0) / dWall; // mediaMs / wallMs == realtime ratio
                speed = speed <= 0 ? inst : speed * 0.8 + inst * 0.2;
            }
        }
        lastUpdateMs = nowMs;
        lastProcessedUs = processedUs;

        float frac = Math.max(0f, Math.min(1f, (float) processedUs / (float) durationUs));
        float base = phase == Phase.AUDIO ? wVideo : 0f;
        float weight = phase == Phase.AUDIO ? wAudio : wVideo;
        float overall = base + weight * frac;
        listener.onProgress(phase, processedUs, durationUs, speed, overall);
    }

    private String buildOutputName() {
        String base = queryDisplayName();
        if (base == null) base = "video_" + System.currentTimeMillis();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base + "_hevc.mp4";
    }

    private String queryDisplayName() {
        try (Cursor c = context.getContentResolver().query(
                inputUri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void deleteQuietly(File... files) {
        for (File f : files) {
            if (f != null && f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Exception ignored) {
            }
        }
    }
}
