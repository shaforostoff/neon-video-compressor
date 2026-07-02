package com.shaforostoff.neonvideocompressor;

import android.content.Context;
import android.content.Intent;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.shaforostoff.neonvideocompressor.engine.JobControl;
import com.shaforostoff.neonvideocompressor.engine.NativeConverter;
import com.shaforostoff.neonvideocompressor.engine.Options;

import java.io.File;

/**
 * Encodes the first few seconds of the selected video with the current options,
 * then plays the encoded clip in a loop. Holding a finger on the screen reveals
 * the (losslessly copied) original at the same position for an instant A/B
 * quality comparison; releasing switches back to the encoded result.
 */
public class PreviewActivity extends AppCompatActivity {

    private static final String EXTRA_URI = "uri";
    private static final String EXTRA_OPTIONS = "options";
    private static final long PREVIEW_US = 5_000_000L; // first 5 seconds

    private Uri inputUri;
    private Options options;

    private File encodedFile, originalFile;

    private TextureView texEncoded, texOriginal;
    private View loadingOverlay;
    private ProgressBar loadingBar;
    private TextView txtStatus, txtHint, txtLabel;

    private MediaPlayer mpEncoded, mpOriginal;
    private Surface surfEncoded, surfOriginal;
    private int preparedCount;
    private boolean playersStarted;
    private boolean showingOriginal;

    // Both clips loop off a single manual clock: the two are seeked back to 0
    // together at loopEndMs, so they share an identical loop length and wrap at
    // the same instant (independent setLooping would drift, since the encoded and
    // copied clips aren't exactly the same length). Between wraps the hidden
    // player is nudged to the visible one only when it actually drifts, so the
    // HEVC decoder isn't kept perpetually mid-seek.
    private static final long DRIVER_INTERVAL_MS = 40L;
    private static final int SYNC_THRESHOLD_MS = 80;
    private static final int WRAP_MARGIN_MS = 120; // wrap this far before the true end
    private int loopEndMs;

    // Source geometry, from the native probe.
    private int vidW, vidH, rotationDeg;

    private boolean clipsReady;
    private volatile boolean destroyed;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object ctrlLock = new Object();
    private JobControl ctrl;

    public static void start(Context ctx, Uri uri, Options options) {
        Intent i = new Intent(ctx, PreviewActivity.class)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_OPTIONS, options);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_preview);

        inputUri = getIntent().getParcelableExtra(EXTRA_URI);
        options = (Options) getIntent().getSerializableExtra(EXTRA_OPTIONS);
        if (inputUri == null || options == null) {
            finish();
            return;
        }
        // x265's preset trades encode speed for compression efficiency at a given
        // CRF, not visual quality — so previewing always uses the fastest preset
        // to keep the wait short; the real conversion still honors the user's pick.
        options.preset = "ultrafast";

        encodedFile = new File(getCacheDir(), "preview_encoded.mp4");
        originalFile = new File(getCacheDir(), "preview_original.mp4");

        texEncoded = findViewById(R.id.texEncoded);
        texOriginal = findViewById(R.id.texOriginal);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        loadingBar = findViewById(R.id.loadingBar);
        txtStatus = findViewById(R.id.txtStatus);
        txtHint = findViewById(R.id.txtHint);
        txtLabel = findViewById(R.id.txtLabel);

        texEncoded.setSurfaceTextureListener(new SurfaceCb(true));
        texOriginal.setSurfaceTextureListener(new SurfaceCb(false));
        texOriginal.setAlpha(0f);

        findViewById(R.id.root).setOnTouchListener((v, e) -> {
            if (!playersStarted) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    showOriginal(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    showOriginal(false);
                    return true;
                default:
                    return true;
            }
        });

        Thread worker = new Thread(this::buildPreview, "preview-builder");
        worker.start();
    }

    // --- clip generation (worker thread) ------------------------------------

    private void buildPreview() {
        JobControl c = new JobControl();
        synchronized (ctrlLock) {
            ctrl = c;
        }
        ParcelFileDescriptor encPfd = null, copyPfd = null;
        try {
            encPfd = getContentResolver().openFileDescriptor(inputUri, "r");
            if (encPfd == null) {
                fail();
                return;
            }
            int fd = encPfd.getFd();

            long[] probe = NativeConverter.nativeProbe(fd);
            vidW = (int) probe[2];
            vidH = (int) probe[3];
            rotationDeg = ((int) probe[4] % 360 + 360) % 360;

            int r1 = NativeConverter.nativeTranscodeVideo(
                    fd, encodedFile.getAbsolutePath(), options.crf, options.preset,
                    c.nativeHandle(), PREVIEW_US,
                    processedUs -> {
                        int pct = (int) Math.min(100, processedUs * 100 / PREVIEW_US);
                        main.post(() -> loadingBar.setProgress(pct));
                    });
            if (c.cancelled || destroyed) return;
            if (r1 != NativeConverter.RET_OK) {
                fail();
                return;
            }

            copyPfd = getContentResolver().openFileDescriptor(inputUri, "r");
            if (copyPfd == null) {
                fail();
                return;
            }
            int r2 = NativeConverter.nativeCopyClip(
                    copyPfd.getFd(), originalFile.getAbsolutePath(), PREVIEW_US);
            if (destroyed) return;
            if (r2 != NativeConverter.RET_OK) {
                fail();
                return;
            }

            main.post(() -> {
                if (destroyed) return;
                clipsReady = true;
                loadingBar.setProgress(100);
                tryStartPlayback();
            });
        } catch (Exception e) {
            fail();
        } finally {
            synchronized (ctrlLock) {
                c.destroy();
                if (ctrl == c) ctrl = null;
            }
            closeQuietly(encPfd);
            closeQuietly(copyPfd);
        }
    }

    private void fail() {
        main.post(() -> {
            if (destroyed) return;
            txtStatus.setText(R.string.preview_failed);
            loadingBar.setVisibility(View.GONE);
        });
    }

    // --- playback -----------------------------------------------------------

    private void tryStartPlayback() {
        if (destroyed || !clipsReady || playersStarted) return;
        if (surfEncoded == null || surfOriginal == null) return;
        if (mpEncoded != null) return; // already building
        preparedCount = 0;
        mpEncoded = buildPlayer(encodedFile, surfEncoded, texEncoded);
        mpOriginal = buildPlayer(originalFile, surfOriginal, texOriginal);
        if (mpEncoded == null || mpOriginal == null) {
            fail();
            releasePlayers();
        }
    }

    private MediaPlayer buildPlayer(File file, Surface surface, TextureView tv) {
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setSurface(surface);
            mp.setDataSource(file.getAbsolutePath());
            mp.setLooping(false); // looping is driven manually so both wrap together
            mp.setVolume(0f, 0f); // preview compares picture, not sound
            mp.setOnVideoSizeChangedListener((m, w, h) -> applyTransform(tv));
            mp.setOnErrorListener((m, what, extra) -> {
                fail();
                return true;
            });
            mp.setOnPreparedListener(m -> {
                applyTransform(tv);
                m.start();
                onPlayerPrepared();
            });
            mp.prepareAsync();
            return mp;
        } catch (Exception e) {
            return null;
        }
    }

    private void onPlayerPrepared() {
        if (destroyed) return;
        if (++preparedCount < 2) return;
        // Wrap both clips at the shorter one's end (minus a margin) so they share
        // an identical loop length regardless of their slightly different lengths.
        int shared = Math.min(mpEncoded.getDuration(), mpOriginal.getDuration());
        loopEndMs = Math.max(500, shared - WRAP_MARGIN_MS);

        playersStarted = true;
        showingOriginal = false;
        texOriginal.setAlpha(0f);
        loadingOverlay.setVisibility(View.GONE);
        txtHint.setVisibility(View.VISIBLE);
        txtLabel.setVisibility(View.VISIBLE);
        txtLabel.setText(R.string.label_encoded);
        main.postDelayed(driverRunnable, DRIVER_INTERVAL_MS);
    }

    private void showOriginal(boolean show) {
        if (mpEncoded == null || mpOriginal == null) return;
        // Just flip which one is on top — no seek on the visible player. The
        // background sync loop has already aligned the one we're revealing.
        showingOriginal = show;
        texOriginal.setAlpha(show ? 1f : 0f);
        txtLabel.setText(show ? R.string.label_original : R.string.label_encoded);
    }

    // Drives looping and keeps the two players locked together. The visible one
    // is the clock; when it reaches loopEndMs both wrap to 0 in the same tick, so
    // they never drift apart at the loop boundary. Between wraps the hidden player
    // is nudged only if it has actually drifted, so a swap reveals an aligned
    // frame without a visible seek.
    private final Runnable driverRunnable = new Runnable() {
        @Override
        public void run() {
            if (!playersStarted || mpEncoded == null || mpOriginal == null) return;
            try {
                MediaPlayer visible = showingOriginal ? mpOriginal : mpEncoded;
                MediaPlayer hidden = showingOriginal ? mpEncoded : mpOriginal;
                int vp = visible.getCurrentPosition();
                if (vp >= loopEndMs || !visible.isPlaying()) {
                    // Coordinated wrap: both back to the start at the same instant.
                    mpEncoded.seekTo(0, MediaPlayer.SEEK_CLOSEST);
                    mpOriginal.seekTo(0, MediaPlayer.SEEK_CLOSEST);
                    if (!mpEncoded.isPlaying()) mpEncoded.start();
                    if (!mpOriginal.isPlaying()) mpOriginal.start();
                } else {
                    int hp = hidden.getCurrentPosition();
                    if (Math.abs(vp - hp) > SYNC_THRESHOLD_MS) {
                        hidden.seekTo(vp, MediaPlayer.SEEK_CLOSEST);
                    }
                }
            } catch (IllegalStateException ignored) {
            }
            main.postDelayed(this, DRIVER_INTERVAL_MS);
        }
    };

    /**
     * Fits the video into the TextureView with letterboxing, applying the
     * source rotation (TextureView doesn't honour rotation metadata itself).
     * Both clips share the source's dimensions, so the same transform applies.
     */
    private void applyTransform(TextureView tv) {
        if (vidW <= 0 || vidH <= 0) return;
        int vw = tv.getWidth(), vh = tv.getHeight();
        if (vw == 0 || vh == 0) return;

        boolean swap = (rotationDeg == 90 || rotationDeg == 270);
        float dispW = swap ? vidH : vidW;
        float dispH = swap ? vidW : vidH;
        float scale = Math.min(vw / dispW, vh / dispH);
        float fw = dispW * scale; // on-screen video width
        float fh = dispH * scale; // on-screen video height

        float cx = vw / 2f, cy = vh / 2f;
        Matrix m = new Matrix();
        // TextureView stretches the raw (unrotated) frame to fill vw×vh; scale it
        // down so that, once rotated, it occupies exactly fw×fh, then rotate.
        float sx = (swap ? fh : fw) / vw;
        float sy = (swap ? fw : fh) / vh;
        m.postScale(sx, sy, cx, cy);
        m.postRotate(rotationDeg, cx, cy);
        tv.setTransform(m);
    }

    private void releasePlayers() {
        main.removeCallbacks(driverRunnable);
        preparedCount = 0;
        playersStarted = false;
        showingOriginal = false;
        if (mpEncoded != null) {
            try { mpEncoded.release(); } catch (Exception ignored) {}
            mpEncoded = null;
        }
        if (mpOriginal != null) {
            try { mpOriginal.release(); } catch (Exception ignored) {}
            mpOriginal = null;
        }
    }

    // --- lifecycle ----------------------------------------------------------

    @Override
    protected void onStop() {
        super.onStop();
        // Surfaces are torn down when we leave the foreground; drop the players
        // so they can be rebuilt cleanly on return.
        releasePlayers();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        synchronized (ctrlLock) {
            if (ctrl != null) ctrl.cancel();
        }
        releasePlayers();
        //noinspection ResultOfMethodCallIgnored
        encodedFile.delete();
        //noinspection ResultOfMethodCallIgnored
        originalFile.delete();
    }

    private final class SurfaceCb implements TextureView.SurfaceTextureListener {
        private final boolean encoded;

        SurfaceCb(boolean encoded) {
            this.encoded = encoded;
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            Surface s = new Surface(st);
            if (encoded) surfEncoded = s;
            else surfOriginal = s;
            tryStartPlayback();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            applyTransform(encoded ? texEncoded : texOriginal);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            if (encoded) surfEncoded = null;
            else surfOriginal = null;
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
        }
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd != null) {
            try { pfd.close(); } catch (Exception ignored) {}
        }
    }
}
