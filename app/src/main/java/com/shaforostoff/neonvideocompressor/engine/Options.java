package com.shaforostoff.neonvideocompressor.engine;

import android.media.MediaCodecInfo;

import java.io.Serializable;

/** User-chosen conversion options. */
public class Options implements Serializable {

    public enum VideoMode {
        ENCODE_HEVC,
        COPY
    }

    public enum AudioMode {
        ENCODE_AAC_LC,
        ENCODE_AAC_HE,
        COPY
    }

    public VideoMode videoMode = VideoMode.ENCODE_HEVC;
    public int crf = 30;
    public String preset = "slow";

    public AudioMode audioMode = AudioMode.ENCODE_AAC_LC;
    public int audioBitrate = 40_000; // bits per second

    public boolean encodesVideo() {
        return videoMode == VideoMode.ENCODE_HEVC;
    }

    public boolean encodesAudio() {
        return audioMode == AudioMode.ENCODE_AAC_LC || audioMode == AudioMode.ENCODE_AAC_HE;
    }

    /** AAC profile constant for MediaCodec, valid only when {@link #encodesAudio()}. */
    public int aacProfile() {
        return audioMode == AudioMode.ENCODE_AAC_HE
                ? MediaCodecInfo.CodecProfileLevel.AACObjectHE
                : MediaCodecInfo.CodecProfileLevel.AACObjectLC;
    }
}
