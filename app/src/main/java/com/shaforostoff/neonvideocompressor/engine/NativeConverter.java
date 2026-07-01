package com.shaforostoff.neonvideocompressor.engine;

/**
 * Thin JNI bridge to the native FFmpeg + libx265 engine.
 *
 * <p>Inputs are passed as raw file descriptors; the native side reads them via
 * {@code /proc/self/fd/<fd>} (seekable for regular files). Output is written to a
 * plain filesystem path (the app cache), which the caller then publishes.
 */
public final class NativeConverter {

    static {
        System.loadLibrary("nativeconverter");
    }

    /** Per-decoded-frame progress callback (invoked on the calling thread). */
    public interface ProgressCallback {
        void onProgress(long processedUs);
    }

    public static final int RET_OK = 0;
    public static final int RET_ERROR = -1;
    public static final int RET_CANCELLED = -100;

    /** @return {@code [durationUs, hasAudio, width, height, rotationDeg, hasVideo]} */
    public static native long[] nativeProbe(int fd);

    // --- pause / cancel control block -------------------------------------
    public static native long nativeCreateControl();

    public static native void nativeSetPaused(long handle, boolean paused);

    public static native void nativeCancel(long handle);

    public static native void nativeDestroyControl(long handle);

    // --- conversion passes -------------------------------------------------

    /**
     * Decode the source video and encode it to HEVC (libx265) into a video-only
     * mp4 at {@code outPath}, tagged {@code hvc1}.
     *
     * @return {@link #RET_OK}, {@link #RET_CANCELLED} or {@link #RET_ERROR}
     */
    public static native int nativeTranscodeVideo(int inFd, String outPath, int crf,
                                                  String preset, long ctrlHandle,
                                                  ProgressCallback cb);

    /**
     * Stream-copy the first video stream of {@code videoFd} and the first audio
     * stream of {@code audioFd} (pass -1 for none) into {@code outPath} with
     * {@code +faststart}. Forces the {@code hvc1} tag when {@code videoWasEncoded}.
     */
    public static native int nativeRemux(int videoFd, int audioFd, String outPath,
                                         boolean videoWasEncoded);

    private NativeConverter() {
    }
}
