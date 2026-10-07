package com.shaforostoff.neonvideocompressor.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import com.shaforostoff.neonvideocompressor.engine.SourceMetadata;

import java.util.ArrayList;

/**
 * Durable copy of the last finished conversion result, so the "compression
 * finished" screen (Open / Share / Replace original) survives the process being
 * killed. The in-memory {@link ConversionService#getLastTerminal()} dies with
 * the process; a big encode can leave the process bloated and cached, making it
 * a prime low-memory-killer target moments after it finishes — exactly when the
 * user hasn't acted on the result yet. The output file itself is already durable
 * in MediaStore, so persisting its Uri lets us reconstruct the result screen on
 * the next launch and act on the file regardless of process death.
 *
 * <p>"Acknowledged" means the user has actually seen the finished screen (live
 * or restored). Only an <em>unacknowledged</em> result is auto-shown on launch —
 * that is the case that got lost.
 */
public final class ResultStore {

    private static final String PREFS = "last_result";
    private static final String KEY_PRESENT = "present";
    private static final String KEY_ACKED = "acknowledged";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_AUDIO_ONLY = "audio_only";
    private static final String KEY_BATCH_TOTAL = "batch_total";
    private static final String KEY_INPUT_URI = "input_uri";
    private static final String KEY_OUTPUTS = "outputs"; // '\n'-joined uri strings
    // '\n'-joined source uri per output (parallel to KEY_OUTPUTS); "-" = none.
    private static final String KEY_OUTPUT_SOURCES = "output_sources";
    private static final String NO_SOURCE = "-";

    private ResultStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Persist a finished result. No-op unless there is at least one output. */
    public static void save(Context ctx, ConversionService.Snapshot s) {
        if (s == null || s.outputs.isEmpty()) {
            clear(ctx);
            return;
        }
        // Parallel source list, aligned 1:1 with outputs ("-" for none/partial).
        ArrayList<String> sources = new ArrayList<>();
        for (int i = 0; i < s.outputs.size(); i++) {
            Uri src = i < s.outputSources.size() ? s.outputSources.get(i) : null;
            sources.add(src != null ? src.toString() : NO_SOURCE);
        }
        prefs(ctx).edit()
                .putBoolean(KEY_PRESENT, true)
                .putBoolean(KEY_ACKED, false)
                .putString(KEY_MESSAGE, s.message)
                .putBoolean(KEY_AUDIO_ONLY, s.audioOnly)
                .putInt(KEY_BATCH_TOTAL, Math.max(1, s.batchTotal))
                .putString(KEY_INPUT_URI, s.inputUri != null ? s.inputUri.toString() : null)
                .putString(KEY_OUTPUTS, TextUtils.join("\n", s.outputs))
                .putString(KEY_OUTPUT_SOURCES, TextUtils.join("\n", sources))
                .apply();
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    /** Mark the stored result as seen so it is not auto-shown again on launch. */
    public static void markAcknowledged(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.getBoolean(KEY_PRESENT, false)) {
            p.edit().putBoolean(KEY_ACKED, true).apply();
        }
    }

    /** True when a result is stored that the user has not seen yet. */
    public static boolean hasPending(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p.getBoolean(KEY_PRESENT, false) && !p.getBoolean(KEY_ACKED, false);
    }

    /**
     * Reconstruct the stored result as a terminal (DONE) snapshot, or {@code null}
     * if nothing is stored. Drops (and clears) the record when its first output no
     * longer resolves — e.g. the user deleted the file in the meantime.
     */
    public static ConversionService.Snapshot load(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!p.getBoolean(KEY_PRESENT, false)) return null;

        String joined = p.getString(KEY_OUTPUTS, "");
        ArrayList<Uri> outputs = new ArrayList<>();
        if (joined != null && !joined.isEmpty()) {
            for (String part : joined.split("\n")) {
                if (!part.isEmpty()) outputs.add(Uri.parse(part));
            }
        }
        // The output must still exist (the user may have deleted it meanwhile).
        if (outputs.isEmpty() || SourceMetadata.querySize(ctx, outputs.get(0)) <= 0) {
            clear(ctx);
            return null;
        }

        ConversionService.Snapshot s = new ConversionService.Snapshot();
        s.status = ConversionService.Status.DONE;
        s.overall = 1f;
        s.message = p.getString(KEY_MESSAGE, null);
        s.audioOnly = p.getBoolean(KEY_AUDIO_ONLY, false);
        s.batchTotal = p.getInt(KEY_BATCH_TOTAL, 1);
        s.batchIndex = Math.max(0, s.batchTotal - 1); // completed: forces 100%
        String input = p.getString(KEY_INPUT_URI, null);
        s.inputUri = input != null ? Uri.parse(input) : null;
        s.outputs.addAll(outputs);

        // Rebuild the per-output source list; a malformed one offers no Replace.
        String[] parts = p.getString(KEY_OUTPUT_SOURCES, "").split("\n", -1);
        for (int i = 0; i < outputs.size(); i++) {
            String part = parts.length == outputs.size() ? parts[i] : NO_SOURCE;
            s.outputSources.add(NO_SOURCE.equals(part) || part.isEmpty() ? null : Uri.parse(part));
        }
        return s;
    }
}
