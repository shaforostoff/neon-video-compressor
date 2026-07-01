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
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import com.shaforostoff.neonvideocompressor.MainActivity;
import com.shaforostoff.neonvideocompressor.ProgressActivity;
import com.shaforostoff.neonvideocompressor.R;
import com.shaforostoff.neonvideocompressor.engine.ConversionJob;
import com.shaforostoff.neonvideocompressor.engine.JobControl;
import com.shaforostoff.neonvideocompressor.engine.Options;

/**
 * Foreground service that runs a conversion on a worker thread so it keeps going
 * while the user switches apps, and exposes pause / resume / cancel both through
 * a bound API (for the UI) and notification actions.
 */
public class ConversionService extends Service implements ConversionJob.Listener {

    public static final String ACTION_START = "start";
    public static final String ACTION_PAUSE = "pause";
    public static final String ACTION_RESUME = "resume";
    public static final String ACTION_CANCEL = "cancel";

    public static final String EXTRA_INPUT_URI = "input_uri";
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
        public float overall;
        public String message;
        public Uri output;
    }

    public interface UiCallback {
        void onUpdate(Snapshot snapshot);
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Snapshot snapshot = new Snapshot();

    private JobControl control;
    private Thread worker;
    private long lastNotifMs = 0;

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
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return START_NOT_STICKY;

        switch (action) {
            case ACTION_START:
                handleStart(intent);
                break;
            case ACTION_PAUSE:
                if (control != null) {
                    control.setPaused(true);
                    snapshot.status = Status.PAUSED;
                    pushUpdate(true);
                }
                break;
            case ACTION_RESUME:
                if (control != null) {
                    control.setPaused(false);
                    snapshot.status = Status.RUNNING;
                    pushUpdate(true);
                }
                break;
            case ACTION_CANCEL:
                if (control != null) control.cancel();
                break;
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void handleStart(Intent intent) {
        if (worker != null) return; // already running

        Uri input = intent.getParcelableExtra(EXTRA_INPUT_URI);
        Options options = (Options) intent.getSerializableExtra(EXTRA_OPTIONS);
        if (input == null || options == null) {
            stopSelf();
            return;
        }

        createChannel();
        startForegroundCompat(buildProgressNotification());

        control = new JobControl();
        snapshot.status = Status.RUNNING;

        ConversionJob job = new ConversionJob(this, input, options, control, this);
        worker = new Thread(() -> {
            try {
                job.run();
            } finally {
                control.destroy();
            }
        }, "conversion-worker");
        worker.start();
    }

    // --- ConversionJob.Listener (called on the worker thread) ---------------

    @Override
    public void onProgress(ConversionJob.Phase phase, long processedUs, long durationUs,
                           double speed, float overall) {
        snapshot.status = control != null && control.paused ? Status.PAUSED : Status.RUNNING;
        snapshot.phase = phase;
        snapshot.processedUs = processedUs;
        snapshot.durationUs = durationUs;
        snapshot.speed = speed;
        snapshot.overall = overall;
        pushUpdate(false);
    }

    @Override
    public void onCompleted(Uri output, String displayName) {
        snapshot.status = Status.DONE;
        snapshot.overall = 1f;
        snapshot.output = output;
        snapshot.message = displayName;
        finish(buildDoneNotification("Saved " + displayName, output));
    }

    @Override
    public void onError(String message) {
        snapshot.status = Status.ERROR;
        snapshot.message = message;
        finish(buildDoneNotification("Conversion failed: " + message, null));
    }

    @Override
    public void onCancelled() {
        snapshot.status = Status.CANCELLED;
        snapshot.message = "Cancelled";
        finish(null);
    }

    private void finish(Notification finalNotification) {
        pushUpdate(true);
        worker = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (finalNotification != null) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(NOTIF_ID + 1, finalNotification);
        }
        stopSelf();
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
        if (control != null) {
            control.setPaused(true);
            snapshot.status = Status.PAUSED;
            pushUpdate(true);
        }
    }

    public void resume() {
        if (control != null) {
            control.setPaused(false);
            snapshot.status = Status.RUNNING;
            pushUpdate(true);
        }
    }

    public void cancel() {
        if (control != null) control.cancel();
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
                    CHANNEL_ID, "Video conversion", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildProgressNotification() {
        boolean paused = snapshot.status == Status.PAUSED;
        int percent = Math.round(snapshot.overall * 100);

        PendingIntent content = PendingIntent.getActivity(this, 0,
                new Intent(this, ProgressActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_convert)
                .setContentTitle(paused ? "Conversion paused" : "Converting video")
                .setContentText(phaseLabel(snapshot.phase) + " · " + percent + "%")
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content);

        if (paused) {
            b.addAction(0, "Resume", actionIntent(ACTION_RESUME));
        } else {
            b.addAction(0, "Pause", actionIntent(ACTION_PAUSE));
        }
        b.addAction(0, "Cancel", actionIntent(ACTION_CANCEL));
        return b.build();
    }

    private Notification buildDoneNotification(String text, Uri output) {
        Intent open = output != null
                ? new Intent(Intent.ACTION_VIEW).setDataAndType(output, "video/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                : new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_convert)
                .setContentTitle("Neon Video Compressor")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
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

    private static String phaseLabel(ConversionJob.Phase phase) {
        switch (phase) {
            case VIDEO:
                return "Encoding video";
            case AUDIO:
                return "Encoding audio";
            case MUXING:
                return "Finalizing";
            case PUBLISHING:
                return "Saving";
            default:
                return "Preparing";
        }
    }

    public static void start(Context ctx, Uri input, Options options) {
        Intent i = new Intent(ctx, ConversionService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_INPUT_URI, input)
                .putExtra(EXTRA_OPTIONS, options);
        ctx.startForegroundService(i);
    }
}
