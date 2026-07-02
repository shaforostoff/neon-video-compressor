package com.shaforostoff.neonvideocompressor.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.format.Formatter;

import androidx.core.app.NotificationCompat;

import com.shaforostoff.neonvideocompressor.MainActivity;
import com.shaforostoff.neonvideocompressor.OutputActions;
import com.shaforostoff.neonvideocompressor.ProgressActivity;
import com.shaforostoff.neonvideocompressor.R;
import com.shaforostoff.neonvideocompressor.engine.ConversionJob;
import com.shaforostoff.neonvideocompressor.engine.JobControl;
import com.shaforostoff.neonvideocompressor.engine.Options;
import com.shaforostoff.neonvideocompressor.engine.SourceMetadata;

import java.util.ArrayList;

/**
 * Foreground service that converts a queue of videos on a worker thread so it
 * keeps going while the user switches apps, and exposes pause / resume / cancel
 * both through a bound API (for the UI) and notification actions. Files are
 * processed one after another; cancel aborts the whole batch.
 */
public class ConversionService extends Service implements ConversionJob.Listener {

    public static final String ACTION_START = "start";
    public static final String ACTION_PAUSE = "pause";
    public static final String ACTION_RESUME = "resume";
    public static final String ACTION_CANCEL = "cancel";

    public static final String EXTRA_INPUT_URIS = "input_uris";
    public static final String EXTRA_OPTIONS = "options";

    private static final String CHANNEL_ID = "conversion";
    private static final int NOTIF_ID = 1;

    public enum Status {IDLE, RUNNING, PAUSED, DONE, ERROR, CANCELLED}

    /** Immutable snapshot of progress, delivered to the bound UI. */
    public static final class Snapshot {
        public Status status = Status.IDLE;
        public ConversionJob.Phase phase = ConversionJob.Phase.PROBING;
        public long processedUs;
        public long durationUs;
        public double speed;
        public float overall;          // fraction of the current file [0,1]
        public long liveProcessedBytes; // estimated source bytes consumed so far (current file)
        public long liveOutputBytes;    // bytes written to the output so far (current file)
        public String message;
        public Uri output;

        // Batch state
        public int batchIndex;         // 0-based index of the file being processed
        public int batchTotal = 1;     // number of files in the batch
        public int succeeded;
        public int failed;
        public String currentName;     // display name of the current source file
        public boolean audioOnly;      // output has no video track (.m4a)
        public final ArrayList<Uri> outputs = new ArrayList<>(); // all successful outputs

        /** Progress across the whole batch, factoring in completed files. */
        public float batchFraction() {
            if (batchTotal <= 1) return overall;
            return Math.max(0f, Math.min(1f, (batchIndex + overall) / batchTotal));
        }
    }

    public interface UiCallback {
        void onUpdate(Snapshot snapshot);
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Snapshot snapshot = new Snapshot();

    private final Object controlLock = new Object();
    private JobControl control;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean batchCancelled;
    private long lastNotifMs = 0;

    // Aggregated results across the batch (worker-thread only).
    private Uri lastOutput;
    private String lastDisplayName;
    private String lastError;
    private long totalBytes;        // size of produced outputs
    private long originalBytes;     // size of the sources that succeeded
    private long currentInputBytes; // size of the source currently being processed
    private long totalEncodeMediaUs; // source media encoded, across the batch (active time only)
    private long totalEncodeWallMs;  // wall-clock ms spent actively video-encoding, across the batch
    private final ArrayList<Uri> collectedOutputs = new ArrayList<>();

    public class LocalBinder extends Binder {
        public ConversionService getService() {
            return ConversionService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return START_NOT_STICKY;

        switch (action) {
            case ACTION_START:
                handleStart(intent);
                break;
            case ACTION_PAUSE:
                pause();
                break;
            case ACTION_RESUME:
                resume();
                break;
            case ACTION_CANCEL:
                cancel();
                break;
        }
        return START_NOT_STICKY;
    }

    private void handleStart(Intent intent) {
        if (worker != null) return; // already running

        ArrayList<Uri> inputs = intent.getParcelableArrayListExtra(EXTRA_INPUT_URIS);
        Options options = (Options) intent.getSerializableExtra(EXTRA_OPTIONS);
        if (inputs == null || inputs.isEmpty() || options == null) {
            stopSelf();
            return;
        }

        createChannel();
        snapshot.status = Status.RUNNING;
        snapshot.batchTotal = inputs.size();
        snapshot.batchIndex = 0;
        snapshot.audioOnly = options.removesVideo();
        startForegroundCompat(buildProgressNotification());
        acquireWakeLock();

        worker = new Thread(() -> runBatch(inputs, options), "conversion-worker");
        worker.start();
    }

    private void runBatch(ArrayList<Uri> inputs, Options options) {
        for (int i = 0; i < inputs.size(); i++) {
            if (batchCancelled) break;

            Uri input = inputs.get(i);
            snapshot.batchIndex = i;
            snapshot.overall = 0f;
            snapshot.speed = 0;
            snapshot.liveProcessedBytes = 0;
            snapshot.liveOutputBytes = 0;
            snapshot.currentName = queryName(input);
            snapshot.status = Status.RUNNING;
            currentInputBytes = queryUriSize(input);
            pushUpdate(true);

            JobControl c = new JobControl();
            synchronized (controlLock) {
                control = c;
            }
            ConversionJob job = new ConversionJob(this, input, currentInputBytes, options, c, this);
            try {
                job.run();
                totalEncodeMediaUs += job.getEncodeActiveMediaUs();
                totalEncodeWallMs += job.getEncodeActiveWallMs();
            } catch (Throwable t) {
                snapshot.failed++;
                lastError = t.getMessage() != null ? t.getMessage() : t.toString();
            } finally {
                synchronized (controlLock) {
                    c.destroy();
                    if (control == c) control = null;
                }
            }
        }
        finishBatch(options);
    }

    // --- ConversionJob.Listener (called on the worker thread) ---------------

    @Override
    public void onProgress(ConversionJob.Phase phase, long processedUs, long durationUs,
                           double speed, float overall, long processedBytes, long outputBytes) {
        boolean paused;
        synchronized (controlLock) {
            paused = control != null && control.paused;
        }
        snapshot.status = paused ? Status.PAUSED : Status.RUNNING;
        snapshot.phase = phase;
        snapshot.processedUs = processedUs;
        snapshot.durationUs = durationUs;
        snapshot.speed = speed;
        snapshot.overall = overall;
        snapshot.liveProcessedBytes = processedBytes;
        snapshot.liveOutputBytes = outputBytes;
        pushUpdate(false);
    }

    @Override
    public void onCompleted(Uri output, String displayName) {
        snapshot.succeeded++;
        snapshot.overall = 1f;
        lastOutput = output;
        lastDisplayName = displayName;
        if (output != null) {
            collectedOutputs.add(output); // worker thread only
            totalBytes += queryUriSize(output);
            originalBytes += currentInputBytes;
        }
        pushUpdate(true);
    }

    @Override
    public void onError(String message) {
        snapshot.failed++;
        lastError = message;
        pushUpdate(true);
    }

    @Override
    public void onCancelled() {
        // Reached only when the current job is cancelled, which happens via the
        // user-driven cancel() — abort the rest of the batch too.
        batchCancelled = true;
        pushUpdate(true);
    }

    private void finishBatch(Options options) {
        worker = null;
        int total = snapshot.batchTotal;
        int ok = snapshot.succeeded;
        int bad = snapshot.failed;

        // Size change across the successful file(s), e.g. "500 MB -> 145 MB".
        String sizeText = totalBytes > 0
                ? Formatter.formatShortFileSize(this, originalBytes)
                        + " -> " + Formatter.formatShortFileSize(this, totalBytes)
                : null;
        // Average x265 encode speed as a realtime ratio, e.g. "Avg speed: 1.85× realtime".
        // Only meaningful when video was actually re-encoded (not Copy/Remove).
        String avgSpeedText = totalEncodeWallMs > 0
                ? getString(R.string.avg_speed_format, (totalEncodeMediaUs / 1000.0) / totalEncodeWallMs)
                : null;
        // Stats go on their own line below the headline, e.g.:
        //   Saved clip_hevc.mp4
        //   500 MB -> 145 MB · Avg speed: 1.85× realtime
        String extra = "";
        if (sizeText != null || avgSpeedText != null) {
            StringBuilder sb = new StringBuilder("\n");
            if (sizeText != null) sb.append(sizeText);
            if (sizeText != null && avgSpeedText != null) sb.append(" · ");
            if (avgSpeedText != null) sb.append(avgSpeedText);
            extra = sb.toString();
        }

        Status finalStatus;
        String summary;
        if (batchCancelled) {
            finalStatus = Status.CANCELLED;
            summary = getString(R.string.summary_cancelled, ok, total) + extra;
        } else if (bad == 0) {
            finalStatus = Status.DONE;
            summary = (total == 1
                    ? getString(R.string.summary_saved_one, lastDisplayName)
                    : getString(R.string.summary_converted_n, ok)) + extra;
        } else if (ok == 0) {
            finalStatus = Status.ERROR;
            summary = total == 1
                    ? (lastError != null
                            ? getString(R.string.summary_failed_reason, lastError)
                            : getString(R.string.summary_failed))
                    : (lastError != null
                            ? getString(R.string.summary_all_failed_reason, bad, lastError)
                            : getString(R.string.summary_all_failed, bad));
        } else {
            finalStatus = Status.DONE; // partial success
            summary = getString(R.string.summary_partial, ok, total, bad) + extra;
        }

        snapshot.status = finalStatus;
        snapshot.overall = 1f;
        snapshot.message = summary;
        snapshot.output = lastOutput;
        // Terminal, single write of the shared list before the final push; the UI
        // copies it defensively on receipt.
        snapshot.outputs.clear();
        snapshot.outputs.addAll(collectedOutputs);

        pushUpdate(true);
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (finalStatus != Status.CANCELLED) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID + 1, buildDoneNotification(summary, collectedOutputs));
        }
        stopSelf();
    }

    @SuppressWarnings("WakelockTimeout")
    private void acquireWakeLock() {
        if (wakeLock != null) return;
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "NeonVideoCompressor:conversion");
        wakeLock.setReferenceCounted(false);
        // Long encodes can run for hours; cap the lock so a crashed/leaked hold
        // can't keep the CPU awake forever. finishBatch() releases it normally.
        wakeLock.acquire(6 * 60 * 60 * 1000L /* 6h */);
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
        }
    }

    // --- bound API ----------------------------------------------------------

    private UiCallback uiCallback;

    public void setUiCallback(UiCallback cb) {
        this.uiCallback = cb;
        if (cb != null) {
            final Snapshot s = snapshot;
            main.post(() -> {
                if (uiCallback != null) uiCallback.onUpdate(s);
            });
        }
    }

    public void pause() {
        synchronized (controlLock) {
            if (control != null) {
                control.setPaused(true);
                snapshot.status = Status.PAUSED;
            }
        }
        pushUpdate(true);
    }

    public void resume() {
        synchronized (controlLock) {
            if (control != null) {
                control.setPaused(false);
                snapshot.status = Status.RUNNING;
            }
        }
        pushUpdate(true);
    }

    public void cancel() {
        batchCancelled = true;
        synchronized (controlLock) {
            if (control != null) control.cancel();
        }
    }

    // --- updates / notifications -------------------------------------------

    private void pushUpdate(boolean force) {
        final Snapshot s = snapshot;
        main.post(() -> {
            if (uiCallback != null) uiCallback.onUpdate(s);
        });

        long now = SystemClock.elapsedRealtime();
        if (force || now - lastNotifMs > 500) {
            lastNotifMs = now;
            if (s.status == Status.RUNNING || s.status == Status.PAUSED) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.notify(NOTIF_ID, buildProgressNotification());
            }
        }
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel_conversion), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildProgressNotification() {
        boolean paused = snapshot.status == Status.PAUSED;
        boolean batch = snapshot.batchTotal > 1;
        int percent = Math.round(snapshot.batchFraction() * 100);

        String title;
        if (batch) {
            title = getString(R.string.notif_batch_progress_format,
                    getString(paused ? R.string.notif_title_batch_paused : R.string.notif_title_batch_converting),
                    snapshot.batchIndex + 1, snapshot.batchTotal);
        } else {
            title = getString(paused ? R.string.notif_title_single_paused : R.string.notif_title_single_converting);
        }

        String text = getString(R.string.notif_text_format, phaseLabel(snapshot.phase), percent);
        if (batch && snapshot.currentName != null) {
            text = getString(R.string.notif_text_batch_format, snapshot.currentName, phaseLabel(snapshot.phase));
        }

        PendingIntent content = PendingIntent.getActivity(this, 0,
                new Intent(this, ProgressActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_convert)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content);

        if (paused) {
            b.addAction(0, getString(R.string.resume), actionIntent(ACTION_RESUME));
        } else {
            b.addAction(0, getString(R.string.pause), actionIntent(ACTION_PAUSE));
        }
        b.addAction(0, getString(R.string.cancel), actionIntent(ACTION_CANCEL));
        return b.build();
    }

    private Notification buildDoneNotification(String text, ArrayList<Uri> outputs) {
        boolean audioOnly = snapshot.audioOnly;
        Uri first = outputs.isEmpty() ? null : outputs.get(0);

        Intent open = first != null
                ? OutputActions.view(first, audioOnly)
                : new Intent(this, MainActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_convert)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                // The summary can span multiple lines (headline + size/speed stats);
                // BigTextStyle keeps that intact when the notification is expanded,
                // instead of squashing it onto the collapsed single line.
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(contentPi);

        // Share action — a chooser launched via getActivity so it still works
        // after this service has stopped.
        if (!outputs.isEmpty()) {
            Intent chooser = OutputActions.share(this, outputs, audioOnly,
                    getString(R.string.share_via));
            PendingIntent sharePi = PendingIntent.getActivity(this, 3, chooser,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                            | PendingIntent.FLAG_CANCEL_CURRENT);
            b.addAction(0, getString(R.string.share), sharePi);
        }
        return b.build();
    }

    private PendingIntent actionIntent(String action) {
        Intent i = new Intent(this, ConversionService.class).setAction(action);
        return PendingIntent.getService(this, action.hashCode(), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private long queryUriSize(Uri uri) {
        return SourceMetadata.querySize(this, uri);
    }

    private String queryName(Uri uri) {
        String name = SourceMetadata.queryDisplayName(this, uri);
        return name != null ? name : uri.getLastPathSegment();
    }

    private String phaseLabel(ConversionJob.Phase phase) {
        switch (phase) {
            case VIDEO:
                return getString(R.string.phase_video);
            case AUDIO:
                return getString(R.string.phase_audio);
            case MUXING:
                return getString(R.string.phase_mux);
            case PUBLISHING:
                return getString(R.string.phase_publish);
            default:
                return getString(R.string.preparing);
        }
    }

    public static void start(Context ctx, ArrayList<Uri> inputs, Options options) {
        Intent i = new Intent(ctx, ConversionService.class)
                .setAction(ACTION_START)
                .putParcelableArrayListExtra(EXTRA_INPUT_URIS, inputs)
                .putExtra(EXTRA_OPTIONS, options);
        ctx.startForegroundService(i);
    }
}
