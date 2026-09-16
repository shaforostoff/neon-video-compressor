package com.shaforostoff.neonvideocompressor.engine;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import com.shaforostoff.neonvideocompressor.R;

import java.io.File;
import java.io.IOException;

/**
 * Orchestrates a single conversion: probe -> (audio pass) -> video encode muxed
 * straight into the MediaStore item (or a stream-copy remux for non-encode
 * modes) -> publish. Runs synchronously on a worker thread; pause, cancel and
 * graceful stop are driven through the shared {@link JobControl}.
 */
public class ConversionJob {

    public enum Phase {PROBING, VIDEO, AUDIO, MUXING, PUBLISHING}

    public interface Listener {
        /**
         * @param processedBytes estimated bytes of the source consumed so far
         *                       (source size scaled by {@code overall}; 0 if the
         *                       source size is unknown)
         * @param outputBytes    bytes written to the output so far (read off the
         *                       growing output file / audio temp on disk)
         */
        void onProgress(Phase phase, long processedUs, long durationUs, double speed,
                        float overall, long processedBytes, long outputBytes);

        /** @param partial true when the encode was stopped early and the file holds only the beginning */
        void onCompleted(Uri output, String displayName, boolean partial);

        void onError(String message);

        void onCancelled();
    }

    private final Context context;
    private final Uri inputUri;
    private final long sourceSizeBytes;
    private final Options options;
    private final JobControl control;
    private final Listener listener;

    private long durationUs = 0;

    // weights for overall progress
    private float wVideo, wAudio, wMux;

    /**
     * Minimum gap between progress updates handed to the listener. The native
     * encoder calls back once per decoded frame, and every one of those used to
     * cost an fstat on the live output fd, a Handler post to the main thread and
     * a full re-render of the progress screen — tens of thousands of them over a
     * long encode, all stealing CPU from the encoder itself. Two updates a second
     * is well past what the eye reads off a progress bar.
     */
    private static final long PROGRESS_INTERVAL_MS = 500;

    // speed tracking (within the current pass)
    private Phase currentPhase = Phase.PROBING;
    private long lastUpdateMs = 0;
    private long lastProcessedUs = 0;
    private double speed = 0;
    // Throttle clock for listener updates only — never for the bookkeeping above.
    private long lastReportMs = 0;

    // Cumulative active (pause-excluded) video-encode time, for an accurate
    // average realtime ratio once the job finishes — unlike the smoothed
    // instantaneous `speed` above, this isn't reset between progress ticks.
    private long encodeActiveWallMs;
    private long encodeActiveMediaUs;

    // Audio temp file: the only intermediate left. The video pass muxes x265
    // output and this audio straight into the final MediaStore item.
    private File audioTemp;

    // Live handle to the final output while the direct encode+mux pass runs, so
    // progress can report real output bytes (fstat) instead of a temp's size.
    private volatile ParcelFileDescriptor liveOutPfd;
    private long lastOutputBytes;

    public ConversionJob(Context context, Uri inputUri, long sourceSizeBytes, Options options,
                         JobControl control, Listener listener) {
        this.context = context.getApplicationContext();
        this.inputUri = inputUri;
        this.sourceSizeBytes = sourceSizeBytes;
        this.options = options;
        this.control = control;
        this.listener = listener;
    }

    public void run() {
        File cache = context.getCacheDir();
        audioTemp = new File(cache, "audio_tmp.m4a");
        // Removing the video track yields an audio-only container; the final file
        // is muxed straight into the MediaStore item, so there is no out_tmp.
        boolean audioOnly = options.removesVideo();
        deleteQuietly(audioTemp);

        ParcelFileDescriptor inputPfd = null;
        try {
            inputPfd = context.getContentResolver().openFileDescriptor(inputUri, "r");
            if (inputPfd == null) {
                listener.onError(context.getString(R.string.error_open_input));
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
                listener.onError(context.getString(R.string.error_no_video_track));
                return;
            }
            if (durationUs <= 0) durationUs = 1; // avoid div-by-zero in fractions

            boolean encodeVideo = options.encodesVideo();
            boolean copyVideo = options.copiesVideo();
            boolean encodeAudio = options.encodesAudio() && hasAudio;
            boolean copyAudio = options.copiesAudio() && hasAudio;

            boolean outputVideo = encodeVideo || copyVideo;
            boolean outputAudio = encodeAudio || copyAudio;
            if (!outputVideo && !outputAudio) {
                listener.onError(context.getString(options.removesAudio()
                        ? R.string.error_nothing_both_removed
                        : R.string.error_nothing_no_audio));
                return;
            }
            computeWeights(encodeVideo, encodeAudio);

            // --- Audio pass (first): its output is interleaved into the final
            // file while the video is encoded, so it must already exist.
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
            // A stop during the audio pass has nothing encoded worth saving yet.
            if (control.stopRequested) {
                listener.onCancelled();
                return;
            }

            String displayName = buildOutputName(audioOnly);
            Uri item = MediaStoreOutput.createPending(context, displayName, audioOnly);
            boolean finalized = false;
            ParcelFileDescriptor videoPfd = null;
            ParcelFileDescriptor audioPfd = null;
            ParcelFileDescriptor outPfd = null;
            try {
                if (encodeAudio) {
                    audioPfd = ParcelFileDescriptor.open(audioTemp, ParcelFileDescriptor.MODE_READ_ONLY);
                } else if (copyAudio) {
                    audioPfd = context.getContentResolver().openFileDescriptor(inputUri, "r");
                }
                int audioFd = audioPfd != null ? audioPfd.getFd() : -1;

                outPfd = context.getContentResolver().openFileDescriptor(item, "rw");
                if (outPfd == null) throw new IOException(context.getString(R.string.error_open_output));

                boolean partial = false;
                if (encodeVideo && options.encodesVideoHardware()) {
                    // --- Hardware video pass: decode -> HW HEVC encode -> mux (with
                    // the audio) straight into the destination item's "rw" fd.
                    setPhase(Phase.VIDEO);
                    liveOutPfd = outPfd;
                    int r;
                    try {
                        r = HardwareVideoEncoder.encode(
                                inputPfd.getFileDescriptor(),
                                encodeAudio ? audioTemp.getAbsolutePath() : null,
                                copyAudio && audioPfd != null ? audioPfd.getFileDescriptor() : null,
                                outPfd.getFileDescriptor(),
                                options, control, 0L,
                                processedUs -> report(Phase.VIDEO, processedUs));
                    } finally {
                        liveOutPfd = null;
                    }
                    if (r == HardwareVideoEncoder.RESULT_CANCELLED || control.cancelled) {
                        listener.onCancelled();
                        return; // finally deletes the still-pending item
                    }
                    if (r != HardwareVideoEncoder.RESULT_OK
                            && r != HardwareVideoEncoder.RESULT_STOPPED) {
                        listener.onError(context.getString(R.string.error_video_encode_failed, r));
                        return;
                    }
                    partial = r == HardwareVideoEncoder.RESULT_STOPPED;
                } else if (encodeVideo) {
                    // --- Video pass: encode x265 and mux (with the audio) straight
                    // into the destination item's "rw" fd — no video temp file.
                    setPhase(Phase.VIDEO);
                    liveOutPfd = outPfd;
                    int r;
                    try {
                        r = NativeConverter.nativeTranscodeMux(
                                inputFd, audioFd, outPfd.getFd(),
                                options.crf, options.preset, control.nativeHandle(),
                                processedUs -> report(Phase.VIDEO, processedUs));
                    } finally {
                        liveOutPfd = null;
                    }
                    if (r == NativeConverter.RET_CANCELLED || control.cancelled) {
                        listener.onCancelled();
                        return; // finally deletes the still-pending item
                    }
                    if (r != NativeConverter.RET_OK && r != NativeConverter.RET_STOPPED) {
                        listener.onError(context.getString(R.string.error_video_encode_failed, r));
                        return;
                    }
                    partial = r == NativeConverter.RET_STOPPED;
                } else {
                    // --- Mux pass: stream-copy the kept tracks into the item.
                    setPhase(Phase.MUXING);
                    if (copyVideo) {
                        videoPfd = context.getContentResolver().openFileDescriptor(inputUri, "r");
                    } // else: video removed -> no video source
                    int videoFd = videoPfd != null ? videoPfd.getFd() : -1;
                    int muxResult = NativeConverter.nativeRemux(
                            videoFd, audioFd, outPfd.getFd(), false);
                    if (muxResult != NativeConverter.RET_OK) {
                        listener.onError(context.getString(R.string.error_muxing_failed, muxResult));
                        return;
                    }
                    if (control.cancelled) {
                        listener.onCancelled();
                        return;
                    }
                }

                // Close the output fd so all writes flush before we unhide it.
                Quietly.close(outPfd);
                outPfd = null;

                // --- Publish: just clear IS_PENDING (the bytes are already there) ---
                setPhase(Phase.PUBLISHING);
                MediaStoreOutput.finalizePending(context, item);
                finalized = true;
                listener.onCompleted(item, displayName, partial);
            } finally {
                liveOutPfd = null;
                Quietly.close(videoPfd);
                Quietly.close(audioPfd);
                Quietly.close(outPfd);
                if (!finalized) {
                    try {
                        context.getContentResolver().delete(item, null, null);
                    } catch (Exception ignored) {
                    }
                }
            }

        } catch (Exception e) {
            if (control.cancelled) {
                listener.onCancelled();
            } else {
                listener.onError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        } finally {
            deleteQuietly(audioTemp);
            Quietly.close(inputPfd);
        }
    }

    // -------------------------------------------------------------------------

    /** Total source media (microseconds) actually encoded during active (non-paused) time. */
    public long getEncodeActiveMediaUs() {
        return encodeActiveMediaUs;
    }

    /** Total wall-clock time (ms) spent actively video-encoding, pauses excluded. */
    public long getEncodeActiveWallMs() {
        return encodeActiveWallMs;
    }

    // Phase order is audio first, then video (which includes the muxing when the
    // video is encoded), then mux (non-encode modes only) / publish.
    private void computeWeights(boolean video, boolean audio) {
        if (video && audio) {
            wVideo = 0.90f;
            wAudio = 0.08f;
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
        lastReportMs = 0; // a phase change always renders, and re-opens the window
        float base;
        switch (phase) {
            case AUDIO:
                base = 0f;
                break;
            case VIDEO:
                base = wAudio;
                break;
            case MUXING:
            case PUBLISHING:
                base = wVideo + wAudio;
                break;
            default:
                base = 0f;
        }
        listener.onProgress(phase, 0, durationUs, 0, base,
                estimatedProcessedBytes(base), currentOutputBytes(phase));
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
                if (phase == Phase.VIDEO) {
                    encodeActiveWallMs += dWall;
                    encodeActiveMediaUs += dUs;
                }
            }
        }
        lastUpdateMs = nowMs;
        lastProcessedUs = processedUs;

        // Everything above runs per frame — the smoothed speed and the active-time
        // totals behind "Avg speed: N× realtime" would be wrong if sampled coarsely.
        // Only the (expensive) trip out to the listener is rate-limited.
        if (nowMs - lastReportMs < PROGRESS_INTERVAL_MS) return;
        lastReportMs = nowMs;

        float frac = Math.max(0f, Math.min(1f, (float) processedUs / (float) durationUs));
        float base = phase == Phase.VIDEO ? wAudio : 0f;
        float weight = phase == Phase.AUDIO ? wAudio : wVideo;
        float overall = base + weight * frac;
        listener.onProgress(phase, processedUs, durationUs, speed, overall,
                estimatedProcessedBytes(overall), currentOutputBytes(phase));
    }

    /**
     * Rough estimate of how much of the source has been consumed, derived from
     * overall job progress (assumes a roughly constant source bitrate — good
     * enough for a live "N MB -> M MB" indicator, not exact byte accounting).
     */
    private long estimatedProcessedBytes(float overallFrac) {
        if (sourceSizeBytes <= 0) return 0;
        return Math.round(sourceSizeBytes * (double) Math.max(0f, Math.min(1f, overallFrac)));
    }

    /** Bytes actually written to disk so far for the given phase's output. */
    private long currentOutputBytes(Phase phase) {
        switch (phase) {
            case AUDIO:
                return lengthOf(audioTemp);
            case VIDEO:
                // The encode muxes straight into the MediaStore item; fstat the
                // live output fd for the real size.
                ParcelFileDescriptor p = liveOutPfd;
                if (p != null) {
                    long sz = p.getStatSize();
                    if (sz > 0) lastOutputBytes = sz;
                }
                return lastOutputBytes;
            case MUXING:
            case PUBLISHING:
                // Output streams straight to the MediaStore item (no temp to
                // stat); approximate with the best size known so far.
                return Math.max(lastOutputBytes, lengthOf(audioTemp));
            default:
                return 0;
        }
    }

    private static long lengthOf(File f) {
        return f != null && f.exists() ? f.length() : 0;
    }

    private String buildOutputName(boolean audioOnly) {
        String base = SourceMetadata.queryDisplayName(context, inputUri);
        if (base == null) base = "video_" + System.currentTimeMillis();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return audioOnly ? base + "_audio.m4a" : base + "_hevc.mp4";
    }

    private static void deleteQuietly(File... files) {
        for (File f : files) {
            if (f != null && f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
