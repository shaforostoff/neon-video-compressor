package com.shaforostoff.neonvideocompressor;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.ArrayList;

/**
 * Builds the standard "open in a player" and "share" intents for converted
 * outputs. Shared by {@link ProgressActivity} (buttons) and
 * {@link com.shaforostoff.neonvideocompressor.service.ConversionService}
 * (completion-notification action).
 *
 * <p>Outputs are MediaStore {@code content://} URIs, so no FileProvider is
 * needed — but every intent must carry {@link Intent#FLAG_GRANT_READ_URI_PERMISSION},
 * and for {@code ACTION_SEND_MULTIPLE} the flag alone does NOT grant the
 * {@code EXTRA_STREAM} URIs: they must also be attached as {@link ClipData}.
 */
public final class OutputActions {

    private OutputActions() {
    }

    private static String mime(boolean audioOnly) {
        return audioOnly ? "audio/mp4" : "video/mp4";
    }

    /** ACTION_VIEW for a single output (single file, or the first file of a batch). */
    public static Intent view(Uri uri, boolean audioOnly) {
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime(audioOnly))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    /** Chooser wrapping ACTION_SEND / ACTION_SEND_MULTIPLE with read grants for every URI. */
    public static Intent share(Context ctx, ArrayList<Uri> uris, boolean audioOnly, String title) {
        Intent send;
        if (uris.size() == 1) {
            send = new Intent(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_STREAM, uris.get(0))
                    .setType(mime(audioOnly));
        } else {
            send = new Intent(Intent.ACTION_SEND_MULTIPLE)
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    // A batch always produces one mime; the broad type lets a
                    // single chooser cover every file.
                    .setType(audioOnly ? "audio/*" : "video/*");
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // The grant flag only reaches URIs the system finds via getData()/ClipData;
        // EXTRA_STREAM lists are NOT auto-granted, so attach every URI as ClipData.
        ContentResolver cr = ctx.getContentResolver();
        ClipData clip = null;
        for (Uri u : uris) {
            ClipData.Item item = new ClipData.Item(u);
            if (clip == null) {
                clip = new ClipData("outputs", new String[]{cr.getType(u)}, item);
            } else {
                clip.addItem(item);
            }
        }
        if (clip != null) send.setClipData(clip);

        return Intent.createChooser(send, title);
    }
}
