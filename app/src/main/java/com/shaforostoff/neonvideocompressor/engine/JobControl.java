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

    public JobControl() {
        nativeHandle = NativeConverter.nativeCreateControl();
    }

    public long nativeHandle() {
        return nativeHandle;
    }

    public void setPaused(boolean p) {
        paused = p;
        NativeConverter.nativeSetPaused(nativeHandle, p);
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    public void cancel() {
        cancelled = true;
        NativeConverter.nativeCancel(nativeHandle);
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
