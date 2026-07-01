package com.shaforostoff.neonvideocompressor;

import android.app.Application;
import android.util.Log;

import java.io.File;

/**
 * Sweeps orphaned conversion/preview temp files out of the cache dir on process
 * launch.
 *
 * <p>All intermediate files ({@code video_tmp.mp4}, {@code audio_tmp.m4a},
 * {@code out_tmp.*}, {@code preview_*.mp4}) live in {@link #getCacheDir()} and
 * are normally removed by finally-block cleanup. But a hard process kill (e.g.
 * the OS reclaiming a paused, backgrounded encode) skips those finally blocks
 * and leaves a partial file behind. Such a file cannot be resumed — the video
 * temp has no MP4 {@code moov} index until the encoder writes its trailer, and
 * there is no persisted progress checkpoint — so the only correct action is to
 * delete it. This runs once per fresh process, before any job or preview
 * recreates its own temps.
 */
public class App extends Application {

    private static final String TAG = "App";

    @Override
    public void onCreate() {
        super.onCreate();
        final File cache = getCacheDir();
        // Off the main thread — launch shouldn't block on file I/O.
        new Thread(() -> sweepTempFiles(cache), "cache-sweep").start();
    }

    private static void sweepTempFiles(File cache) {
        if (cache == null) return;
        File[] files = cache.listFiles();
        if (files == null) return;
        int deleted = 0;
        for (File f : files) {
            if (f.isFile() && isTempFile(f.getName()) && f.delete()) {
                deleted++;
            }
        }
        if (deleted > 0) {
            Log.i(TAG, "swept " + deleted + " orphaned temp file(s) from cache");
        }
    }

    /** Matches the fixed temp names used by ConversionJob and PreviewActivity. */
    private static boolean isTempFile(String name) {
        return name.endsWith("_tmp.mp4")
                || name.endsWith("_tmp.m4a")
                || name.startsWith("preview_");
    }
}
