package com.shaforostoff.neonvideocompressor.engine;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaMuxer;
import android.os.ParcelFileDescriptor;

/**
 * Best-effort teardown for the media objects the conversion passes hold.
 *
 * <p>These all live in {@code finally} blocks running after something has
 * already gone wrong — a cancelled job, a codec that failed to configure — where
 * the close itself throwing would mask the real failure and leak the rest of the
 * chain. Each helper tolerates null and swallows whatever the release throws.
 */
public final class Quietly {

    public static void close(ParcelFileDescriptor pfd) {
        if (pfd == null) return;
        try {
            pfd.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * Stops a codec and always releases it. stop() fails on a codec that never
     * started or has already errored out; the release still has to happen.
     */
    public static void stopAndRelease(MediaCodec codec) {
        if (codec == null) return;
        try {
            codec.stop();
        } catch (Exception ignored) {
        }
        codec.release();
    }

    public static void release(MediaMuxer muxer) {
        if (muxer == null) return;
        try {
            muxer.release();
        } catch (Exception ignored) {
        }
    }

    public static void release(MediaExtractor extractor) {
        if (extractor == null) return;
        try {
            extractor.release();
        } catch (Exception ignored) {
        }
    }

    private Quietly() {
    }
}
