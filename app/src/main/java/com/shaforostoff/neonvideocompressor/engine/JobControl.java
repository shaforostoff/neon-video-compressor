package com.shaforostoff.neonvideocompressor.engine;

/**
 * Shared pause/cancel control for a conversion job. Mirrors its state into the
 * native control block (for the video pass) and exposes a Java monitor (for the
 * MediaCodec audio pass).
 */
public class JobControl {

    private final long nativeHandle;
    private final Object lock = new Object();

    public volatile boolean paused;
    public volatile boolean cancelled;
    public volatile boolean stopRequested;

    public JobControl() {
        nativeHandle = NativeConverter.nativeCreateControl();
    }

    public long nativeHandle() {
        return nativeHandle;
    }

    public void setPaused(boolean p) {
        paused = p;
        NativeConverter.nativeSetPaused(nativeHandle, p);
        wake();
    }

    public void cancel() {
        cancelled = true;
        NativeConverter.nativeCancel(nativeHandle);
        wake();
    }

    /**
     * Graceful stop: the direct encode+mux pass finalizes the output with what
     * has been encoded so far instead of discarding it.
     */
    public void requestStop() {
        stopRequested = true;
        NativeConverter.nativeRequestStop(nativeHandle);
        wake();
    }

    private void wake() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /** Blocks the calling (audio) thread while paused. */
    public void waitIfPaused() throws InterruptedException {
        synchronized (lock) {
            while (paused && !cancelled) {
                lock.wait();
            }
        }
    }

    public void destroy() {
        NativeConverter.nativeDestroyControl(nativeHandle);
    }
}
