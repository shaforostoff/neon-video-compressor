package com.shaforostoff.neonvideocompressor.engine;

import android.media.MediaCodecInfo;

import java.io.Serializable;

/** User-chosen conversion options. */
public class Options implements Serializable {

    public enum VideoMode {
        /** Software HEVC via the bundled libx265 (CRF + preset). */
        ENCODE_HEVC,
        /** Hardware HEVC via the platform MediaCodec (CQ or VBR, no preset). */
        ENCODE_HEVC_HW,
        COPY,
        REMOVE
    }

    /** Rate-control mode for the hardware encoder when CQ isn't available. */
    public enum HwBitrateMode {
        /** Constant bitrate — holds the target closely. */
        CBR,
        /** Variable bitrate — treats the target as an average, may overshoot. */
        VBR
    }

    public enum AudioMode {
        ENCODE_AAC_LC,
        ENCODE_AAC_HE,
        ENCODE_AAC_HE_V2,
        COPY,
        REMOVE
    }

    public VideoMode videoMode = VideoMode.ENCODE_HEVC;
    public int crf = 30;
    public String preset = "slow";

    /**
     * CQ quality for the hardware encoder, 0..100 (higher = better), mapped onto
     * the encoder's own quality range. Used only when the encoder supports CQ.
     */
    public int hwQuality = 60;

    /**
     * Target bitrate (bits/sec) for the hardware encoder when it has no CQ
     * support. 0 falls back to a resolution-derived default.
     */
    public int hwBitrate = 8_000_000;

    /** Rate control for the hardware bitrate path (ignored when the encoder is CQ). */
    public HwBitrateMode hwBitrateMode = HwBitrateMode.VBR;

    public AudioMode audioMode = AudioMode.ENCODE_AAC_LC;
    public int audioBitrate = 40_000; // bits per second

    public boolean encodesVideo() {
        return videoMode == VideoMode.ENCODE_HEVC || videoMode == VideoMode.ENCODE_HEVC_HW;
    }

    /** Software (libx265) HEVC encode — honours {@link #crf} and {@link #preset}. */
    public boolean encodesVideoSoftware() {
        return videoMode == VideoMode.ENCODE_HEVC;
    }

    /** Hardware (MediaCodec) HEVC encode — honours {@link #hwQuality}. */
    public boolean encodesVideoHardware() {
        return videoMode == VideoMode.ENCODE_HEVC_HW;
    }

    public boolean copiesVideo() {
        return videoMode == VideoMode.COPY;
    }

    /** When true the output carries no video track (audio-only, .m4a). */
    public boolean removesVideo() {
        return videoMode == VideoMode.REMOVE;
    }

    public boolean encodesAudio() {
        return audioMode == AudioMode.ENCODE_AAC_LC
                || audioMode == AudioMode.ENCODE_AAC_HE
                || audioMode == AudioMode.ENCODE_AAC_HE_V2;
    }

    public boolean copiesAudio() {
        return audioMode == AudioMode.COPY;
    }

    /** When true the output carries no audio track. */
    public boolean removesAudio() {
        return audioMode == AudioMode.REMOVE;
    }

    /** AAC profile constant for MediaCodec, valid only when {@link #encodesAudio()}. */
    public int aacProfile() {
        switch (audioMode) {
            case ENCODE_AAC_HE:
                return MediaCodecInfo.CodecProfileLevel.AACObjectHE;
            case ENCODE_AAC_HE_V2:
                return MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS;
            default:
                return MediaCodecInfo.CodecProfileLevel.AACObjectLC;
        }
    }
}
