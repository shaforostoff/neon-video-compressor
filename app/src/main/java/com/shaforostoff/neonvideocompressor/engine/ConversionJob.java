package com.shaforostoff.neonvideocompressor.engine;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import com.shaforostoff.neonvideocompressor.R;

import java.io.File;
import java.io.IOException;

/**
 * Orchestrates a single conversion: probe -> (video pass) -> (audio pass) ->
 * mux -> publish to MediaStore. Runs synchronously on a worker thread; pause and
 * cancel are driven through the shared {@link JobControl}.
 */
public class ConversionJob {

    public enum Phase {PROBING, VIDEO, AUDIO, MUXING, PUBLISHING}

    public interface Listener {
        /**
         * @param processedBytes estimated bytes of the source consumed so far
         *                       (source size scaled by {@code overall}; 0 if the
         *                       source size is unknown)
         * @param outputBytes    bytes written to the output so far (read straight
         *                       off the growing temp file on disk)
         */
        void onProgress(Phase phase, long processedUs, long durationUs, double speed,
                        float overall, long processedBytes, long outputBytes);

        void onCompleted(Uri output, String displayName);

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

    // speed tracking (within the current pass)
    private Phase currentPhase = Phase.PROBING;
    private long lastUpdateMs = 0;
    private long lastProcessedUs = 0;
    private double speed = 0;

    // Cumulative active (pause-excluded) video-encode time, for an accurate
    // average realtime ratio once the job finishes — unlike the smoothed
    // instantaneous `speed` above, this isn't reset between progress ticks.
    private long encodeActiveWallMs;
    private long encodeActiveMediaUs;

    // Temp output files, used both to build the final result and to read a live
    // "bytes written so far" size for progress display.
    private File videoTemp;
    private File audioTemp;

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
        videoTemp = new File(cache, "video_tmp.mp4");
        audioTemp = new File(cache, "audio_tmp.m4a");
        // Removing the video track yields an audio-only container; the final file
        // is muxed straight into the MediaStore item, so there is no out_tmp.
        boolean audioOnly = options.removesVideo();
        deleteQuietly(videoTemp, audioTemp);

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

            // --- Video pass ---
            if (encodeVideo) {
                setPhase(Phase.VIDEO);
                int r = NativeConverter.nativeTranscodeVideo(
                        inputFd, videoTemp.getAbsolutePath(), options.crf, options.preset,
                        control.nativeHandle(), 0L /* whole file */,
                        processedUs -> report(Phase.VIDEO, processedUs));
                if (r == NativeConverter.RET_CANCELLED || control.cancelled) {
                    listener.onCancelled();
                    return;
                }
                if (r != NativeConverter.RET_OK) {
                    listener.onError(context.getString(R.string.error_video_encode_failed, r));
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

            // --- Mux pass: stream-copy straight into the MediaStore target ---
            // The final file is muxed directly into the destination item's "rw"
            // fd, so there is no full-size out_tmp and no publish-time copy.
            setPhase(Phase.MUXING);
            String displayName = buildOutputName(audioOnly);
            Uri item = MediaStoreOutput.createPending(context, displayName, audioOnly);
            boolean finalized = false;
            ParcelFileDescriptor videoPfd = null;
            ParcelFileDescriptor audioPfd = null;
            ParcelFileDescriptor outPfd = null;
            try {
                if (encodeVideo) {
                    videoPfd = ParcelFileDescriptor.open(videoTemp, ParcelFileDescriptor.MODE_READ_ONLY);
                } else if (copyVideo) {
                    videoPfd = context.getContentResolver().openFileDescriptor(inputUri, "r");
                } // else: video removed -> no video source

                if (encodeAudio) {
                    audioPfd = ParcelFileDescriptor.open(audioTemp, ParcelFileDescriptor.MODE_READ_ONLY);
                } else if (copyAudio) {
                    audioPfd = context.getContentResolver().openFileDescriptor(inputUri, "r");
                }

                outPfd = context.getContentResolver().openFileDescriptor(item, "rw");
                if (outPfd == null) throw new IOException(context.getString(R.string.error_open_output));

                int videoFd = videoPfd != null ? videoPfd.getFd() : -1;
                int audioFd = audioPfd != null ? audioPfd.getFd() : -1;
                int muxResult = NativeConverter.nativeRemux(
                        videoFd, audioFd, outPfd.getFd(), encodeVideo);
                if (muxResult != NativeConverter.RET_OK) {
                    listener.onError(context.getString(R.string.error_muxing_failed, muxResult));
                    return; // finally deletes the still-pending item
                }
                if (control.cancelled) {
                    listener.onCancelled();
                    return;
                }

                // Close the output fd so all writes flush before we unhide it.
                closeQuietly(outPfd);
                outPfd = null;

                // --- Publish: just clear IS_PENDING (the bytes are already there) ---
                setPhase(Phase.PUBLISHING);
                MediaStoreOutput.finalizePending(context, item);
                finalized = true;
                listener.onCompleted(item, displayName);
            } finally {
                closeQuietly(videoPfd);
                closeQuietly(audioPfd);
                closeQuietly(outPfd);
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
            deleteQuietly(videoTemp, audioTemp);
            closeQuietly(inputPfd);
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

        float frac = Math.max(0f, Math.min(1f, (float) processedUs / (float) durationUs));
        float base = phase == Phase.AUDIO ? wVideo : 0f;
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
            case VIDEO:
                return lengthOf(videoTemp);
            case AUDIO:
                return lengthOf(videoTemp) + lengthOf(audioTemp);
            case MUXING:
            case PUBLISHING:
                // Output streams straight to the MediaStore item (no temp to
                // stat); approximate with the muxed inputs' combined size.
                return lengthOf(videoTemp) + lengthOf(audioTemp);
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

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Exception ignored) {
            }
        }
    }
}
