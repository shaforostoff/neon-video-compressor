package com.shaforostoff.neonvideocompressor.engine;

/**
 * Thin JNI bridge to the native FFmpeg + libx265 engine.
 *
 * <p>Inputs and outputs are passed as raw file descriptors, which the native
 * side reads and writes with pread/pwrite (never reopening them by path, so
 * content-URI grants keep working).
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
    /** Stopped early on request; the output was finalized with the partial content. */
    public static final int RET_STOPPED = -101;

    /** @return {@code [durationUs, hasAudio, width, height, rotationDeg, hasVideo]} */
    public static native long[] nativeProbe(int fd);

    // --- pause / cancel control block -------------------------------------
    public static native long nativeCreateControl();

    public static native void nativeSetPaused(long handle, boolean paused);

    public static native void nativeCancel(long handle);

    /**
     * Graceful stop: the running {@link #nativeTranscodeMux} finalizes the output
     * with everything encoded so far (audio truncated to match) and returns
     * {@link #RET_STOPPED}. Other passes treat it like a pause-breaking no-op.
     */
    public static native void nativeRequestStop(long handle);

    public static native void nativeDestroyControl(long handle);

    /**
     * Purge retained native heap back to the OS ({@code mallopt(M_PURGE)}). Call
     * after an encode: the x265/FFmpeg frame pools have been freed but the
     * allocator holds the pages, leaving the process bloated and an easy target
     * for the low-memory killer while it sits cached.
     */
    public static native void nativeTrimMemory();

    // --- conversion passes -------------------------------------------------
    // Outputs are caller-owned seekable, readable and writable fds (a MediaStore
    // item opened "rw", or a cache file): +faststart reopens them for reading to
    // relocate the moov atom.

    /**
     * Decode {@code inFd}'s video, encode it to HEVC (libx265, {@code hvc1}) and
     * mux it — interleaved with the first audio stream of {@code audioFd}, or
     * video-only when {@code audioFd} is -1 — straight into {@code outFd} with
     * {@code +faststart}. No temp file and no separate remux pass.
     *
     * @param maxDurationUs stop after this many microseconds of source video
     *                      (for previews); pass {@code 0} to encode the whole file.
     * @return {@link #RET_OK}, {@link #RET_STOPPED} (stop requested; the file was
     * finalized with the partial content), {@link #RET_CANCELLED} or
     * {@link #RET_ERROR}
     */
    public static native int nativeTranscodeMux(int inFd, int audioFd, int outFd,
                                                int crf, String preset, long ctrlHandle,
                                                long maxDurationUs, ProgressCallback cb);

    /**
     * Stream-copy the first video stream of {@code videoFd} and the first audio
     * stream of {@code audioFd} straight into {@code outFd} with {@code +faststart}
     * (an mp4/ISO-BMFF container). Pass -1 for either input fd to omit that
     * track; passing -1 for {@code videoFd} produces an audio-only file.
     *
     * @param maxDurationUs drop everything from this timestamp on (the preview's
     *                      lossless reference clip); {@code 0} copies it all.
     * @return {@link #RET_OK} or {@link #RET_ERROR}
     */
    public static native int nativeRemux(int videoFd, int audioFd, int outFd,
                                         long maxDurationUs);

    private NativeConverter() {
    }
}
