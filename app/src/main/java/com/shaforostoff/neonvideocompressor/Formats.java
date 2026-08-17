package com.shaforostoff.neonvideocompressor;

import android.content.Context;

import java.util.Locale;

/** Human-readable formatting shared between the screens. */
final class Formats {

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

    private Formats() {
    }
}
