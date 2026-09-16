package com.shaforostoff.neonvideocompressor;

import android.content.Context;

import com.shaforostoff.neonvideocompressor.engine.ConversionJob;

import java.util.Locale;

/** Human-readable formatting shared between the screens and the service. */
public final class Formats {

    /**
     * A bit rate as "4.2 Mbps" / "820 kbps" (localized units), or the placeholder
     * dash for a negative rate, meaning "absent track".
     */
    static String bitrate(Context context, long bps) {
        if (bps < 0) return context.getString(R.string.bitrate_none);
        if (bps >= 1_000_000) {
            return context.getString(R.string.bitrate_mbps,
                    String.format(Locale.US, "%.1f", bps / 1_000_000.0));
        }
        return context.getString(R.string.bitrate_kbps, Math.round(bps / 1000.0));
    }

    /** The localized name of a conversion phase, as shown on screen and in the notification. */
    public static String phase(Context context, ConversionJob.Phase phase) {
        final int res;
        switch (phase) {
            case VIDEO:
                res = R.string.phase_video;
                break;
            case AUDIO:
                res = R.string.phase_audio;
                break;
            case MUXING:
                res = R.string.phase_mux;
                break;
            case PUBLISHING:
                res = R.string.phase_publish;
                break;
            default:
                res = R.string.preparing;
                break;
        }
        return context.getString(res);
    }

    private Formats() {
    }
}
