package com.shaforostoff.neonvideocompressor.engine;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.EncoderCapabilities;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Transcodes the source video to HEVC using the platform's hardware MediaCodec
 * encoder and muxes it — together with the already-prepared audio — straight into
 * the output. This is the {@link Options.VideoMode#ENCODE_HEVC_HW} counterpart to
 * the native libx265 pass ({@link NativeConverter#nativeTranscodeMux}).
 *
 * <p>The pipeline is the standard decode→surface→encode chain: the source video
 * is decoded onto the encoder's input {@link Surface}, so frames never round-trip
 * through Java and colour conversion stays on the GPU/ISP. Audio is stream-copied
 * (never re-encoded) from either the AAC temp produced by {@link AudioEncoder} or
 * the source file directly, and interleaved into the same {@link MediaMuxer}.
 *
 * <p>Pause / cancel / graceful-stop are driven through the shared
 * {@link JobControl}, and results use the native pass's {@code RET_*} codes, so
 * the service and UI treat both encoders identically.
 */
public final class HardwareVideoEncoder {

    private static final String TAG = "HardwareVideoEncoder";

    private static final long TIMEOUT_US = 10_000;
    private static final int I_FRAME_INTERVAL_SEC = 2;
    private static final int DEFAULT_FRAME_RATE = 30;

    /**
     * Full encode + mux into {@code outFd} (a seekable, writable fd such as a
     * MediaStore item opened "rw"). Exactly one of {@code audioPath} /
     * {@code audioCopyFd} may be non-null to attach an audio track; pass null for
     * both to produce a video-only file.
     *
     * @param audioPath   path to a ready AAC file (from {@link AudioEncoder}), or null
     * @param audioCopyFd source fd whose first audio track is stream-copied, or null
     * @param maxDurationUs stop after this much source video (previews); 0 = whole file
     * @return a {@link NativeConverter} {@code RET_*} code; {@code RET_STOPPED}
     * means stopped early on request, the output holding the partial content
     */
    public static int encode(FileDescriptor videoFd, String audioPath, FileDescriptor audioCopyFd,
                             FileDescriptor outFd, Options options, JobControl control,
                             long maxDurationUs, NativeConverter.ProgressCallback progress) {
        MediaExtractor audio = null;
        MediaMuxer muxer = null;
        try {
            AudioSource audioSource = null;
            if (audioPath != null || audioCopyFd != null) {
                audio = new MediaExtractor();
                if (audioPath != null) audio.setDataSource(audioPath);
                else audio.setDataSource(audioCopyFd);
                audioSource = AudioSource.from(audio);
            }
            muxer = new MediaMuxer(outFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            return transcode(videoFd, muxer, audioSource, options, control, maxDurationUs, progress);
        } catch (Exception e) {
            Log.e(TAG, "encode failed", e);
            return control.cancelled ? NativeConverter.RET_CANCELLED : NativeConverter.RET_ERROR;
        } finally {
            Quietly.release(muxer);
            Quietly.release(audio);
        }
    }

    // -------------------------------------------------------------------------

    private static int transcode(FileDescriptor videoFd, MediaMuxer muxer, AudioSource audio,
                                 Options options, JobControl control, long maxDurationUs,
                                 NativeConverter.ProgressCallback progress) throws IOException {
        MediaExtractor video = new MediaExtractor();
        video.setDataSource(videoFd);
        int videoTrack = selectTrack(video, "video/");
        if (videoTrack < 0) {
            video.release();
            return NativeConverter.RET_ERROR;
        }
        video.selectTrack(videoTrack);
        MediaFormat srcFormat = video.getTrackFormat(videoTrack);

        int width = srcFormat.getInteger(MediaFormat.KEY_WIDTH);
        int height = srcFormat.getInteger(MediaFormat.KEY_HEIGHT);
        int frameRate = getNumber(srcFormat, MediaFormat.KEY_FRAME_RATE, DEFAULT_FRAME_RATE);
        int rotation = getNumber(srcFormat, MediaFormat.KEY_ROTATION, 0);
        String srcMime = srcFormat.getString(MediaFormat.KEY_MIME);

        HardwareEncoderInfo hw = HardwareEncoderInfo.detect();
        Log.i(TAG, "encoder=" + (hw != null ? hw.codecName() : "default")
                + " cq=" + (hw != null && hw.supportsCq())
                + " " + width + "x" + height + "@" + frameRate);

        MediaFormat encFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_HEVC, width, height);
        encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC);
        if (hw != null && hw.supportsCq()) {
            encFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    EncoderCapabilities.BITRATE_MODE_CQ);
            encFormat.setInteger(MediaFormat.KEY_QUALITY, hw.cqQualityFor(options.hwQuality));
        } else {
            int bitrate = options.hwBitrate > 0
                    ? options.hwBitrate
                    : targetBitrate(width, height, frameRate);
            // CBR holds the target closely; VBR treats it as an average and (on
            // Qualcomm) overshoots several-fold. The user picks which they want.
            int mode = options.hwBitrateMode == Options.HwBitrateMode.VBR
                    ? EncoderCapabilities.BITRATE_MODE_VBR
                    : EncoderCapabilities.BITRATE_MODE_CBR;
            encFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, mode);
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        }

        MediaCodec encoder = null;
        MediaCodec decoder = null;
        Surface inputSurface = null;
        boolean muxerStarted = false;
        int result = NativeConverter.RET_OK;
        try {
            encoder = hw != null
                    ? MediaCodec.createByCodecName(hw.codecName())
                    : MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = encoder.createInputSurface();
            encoder.start();

            decoder = MediaCodec.createDecoderByType(srcMime);
            decoder.configure(srcFormat, inputSurface, null, 0);
            decoder.start();

            if (rotation != 0) muxer.setOrientationHint(rotation);

            MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();

            int muxVideoTrack = -1;
            int muxAudioTrack = -1;
            boolean decoderInputDone = false;
            boolean decoderOutputDone = false;
            boolean encoderDone = false;
            boolean audioDone = audio == null;
            long lastVideoPtsUs = 0;

            while (!encoderDone) {
                control.waitIfPaused();
                if (control.cancelled) {
                    result = NativeConverter.RET_CANCELLED;
                    break;
                }
                // A graceful stop flows the same way as reaching the duration limit:
                // stop feeding the decoder, let the EOS drain through the encoder,
                // and finalize the partial file.
                boolean stopping = control.stopRequested;

                // 1) source video -> decoder input
                if (!decoderInputDone) {
                    int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIndex >= 0) {
                        long sampleTime = video.getSampleTime();
                        boolean overLimit = maxDurationUs > 0 && sampleTime >= maxDurationUs;
                        int size = (sampleTime < 0 || overLimit || stopping)
                                ? -1
                                : video.readSampleData(decoder.getInputBuffer(inIndex), 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            decoderInputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, sampleTime, 0);
                            video.advance();
                        }
                    }
                }

                // 2) decoder output -> encoder input surface (rendered)
                if (!decoderOutputDone) {
                    int outIndex = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US);
                    if (outIndex >= 0) {
                        boolean eos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        // Render the frame onto the encoder's input surface.
                        decoder.releaseOutputBuffer(outIndex, decInfo.size > 0);
                        if (eos) {
                            decoderOutputDone = true;
                            encoder.signalEndOfInputStream();
                        }
                    }
                }

                // 3) encoder output -> muxer (interleaving audio up to the video pts)
                int encIndex = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US);
                if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                    if (audio != null) muxAudioTrack = muxer.addTrack(audio.format);
                    muxer.start();
                    muxerStarted = true;
                } else if (encIndex >= 0) {
                    ByteBuffer outBuf = encoder.getOutputBuffer(encIndex);
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encInfo.size = 0; // CSD is folded into the track format
                    }
                    if (encInfo.size > 0 && muxerStarted) {
                        outBuf.position(encInfo.offset);
                        outBuf.limit(encInfo.offset + encInfo.size);
                        muxer.writeSampleData(muxVideoTrack, outBuf, encInfo);
                        lastVideoPtsUs = encInfo.presentationTimeUs;
                        if (progress != null) progress.onProgress(lastVideoPtsUs);
                        if (!audioDone) {
                            audioDone = pumpAudio(muxer, muxAudioTrack, audio, lastVideoPtsUs);
                        }
                    }
                    encoder.releaseOutputBuffer(encIndex, false);
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true;
                    }
                }
            }

            // Flush any remaining audio. On a graceful stop keep only what lines up
            // with the encoded video; otherwise write it all out.
            if (result == NativeConverter.RET_OK && muxerStarted && !audioDone) {
                long limit = control.stopRequested ? lastVideoPtsUs : Long.MAX_VALUE;
                pumpAudio(muxer, muxAudioTrack, audio, limit);
            }
            if (result == NativeConverter.RET_OK && control.stopRequested) {
                result = NativeConverter.RET_STOPPED;
            }
        } catch (Exception e) {
            if (control.cancelled) result = NativeConverter.RET_CANCELLED;
            else if (result == NativeConverter.RET_OK) {
                Log.e(TAG, "transcode failed (encoder="
                        + (hw != null ? hw.codecName() : "default")
                        + ", " + width + "x" + height + "@" + frameRate
                        + ", cq=" + (hw != null && hw.supportsCq()) + ")", e);
                result = NativeConverter.RET_ERROR;
            }
        } finally {
            // stop() finalizes the mp4 (writes the moov atom); releasing a started
            // muxer without it leaves a corrupt file. On cancel/error the caller
            // discards the output anyway, so a failing stop() there is harmless.
            if (muxerStarted) {
                try {
                    muxer.stop();
                } catch (Exception ignored) {
                }
            }
            Quietly.stopAndRelease(decoder);
            Quietly.stopAndRelease(encoder);
            if (inputSurface != null) inputSurface.release();
            video.release();
        }
        return result;
    }

    /**
     * Writes audio samples whose timestamp is at or before {@code untilUs} into the
     * muxer, advancing the extractor. Returns true once the audio track is drained.
     */
    private static boolean pumpAudio(MediaMuxer muxer, int muxAudioTrack,
                                     AudioSource audio, long untilUs) {
        if (muxAudioTrack < 0) return true;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            long pts = audio.extractor.getSampleTime();
            if (pts < 0) return true;              // end of audio
            if (pts > untilUs) return false;       // ahead of the video — later
            int size = audio.extractor.readSampleData(audio.buffer, 0);
            if (size < 0) return true;
            info.set(0, size, pts,
                    (audio.extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            audio.buffer.position(0);
            audio.buffer.limit(size);
            muxer.writeSampleData(muxAudioTrack, audio.buffer, info);
            audio.extractor.advance();
        }
    }

    /**
     * Fallback VBR bitrate when the caller supplies none, derived from resolution
     * and frame rate via a bits-per-pixel heuristic (HEVC-tuned), clamped to a
     * sane range.
     */
    private static int targetBitrate(int width, int height, int frameRate) {
        double bits = (double) width * height * Math.max(1, frameRate) * 0.08;
        long clamped = Math.max(300_000L, Math.min((long) bits, 60_000_000L));
        return (int) clamped;
    }

    private static int selectTrack(MediaExtractor extractor, String mimePrefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return i;
        }
        return -1;
    }

    /** Reads an int key, tolerating containers that store it as a float (e.g. frame rate). */
    private static int getNumber(MediaFormat format, String key, int fallback) {
        if (!format.containsKey(key)) return fallback;
        try {
            return format.getInteger(key);
        } catch (ClassCastException e) {
            try {
                return Math.round(format.getFloat(key));
            } catch (Exception ignored) {
                return fallback;
            }
        }
    }

    /** A selected, ready-to-copy audio track plus a reusable read buffer. */
    private static final class AudioSource {
        final MediaExtractor extractor;
        final MediaFormat format;
        final ByteBuffer buffer;

        private AudioSource(MediaExtractor extractor, MediaFormat format, int maxInput) {
            this.extractor = extractor;
            this.format = format;
            this.buffer = ByteBuffer.allocate(maxInput);
        }

        /** @return the track, or null if the extractor has no audio track to copy. */
        static AudioSource from(MediaExtractor extractor) {
            int track = selectTrack(extractor, "audio/");
            if (track < 0) return null;
            extractor.selectTrack(track);
            MediaFormat format = extractor.getTrackFormat(track);
            int maxInput = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
            return new AudioSource(extractor, format, Math.max(maxInput, 256 * 1024));
        }
    }

    private HardwareVideoEncoder() {
    }
}
