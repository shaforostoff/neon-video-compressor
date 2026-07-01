package com.shaforostoff.neonvideocompressor.engine;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Decodes the source audio track and re-encodes it to AAC (LC or HE) via the
 * platform MediaCodec, writing a temporary audio-only .m4a that the native mux
 * pass later combines with the HEVC video.
 */
public final class AudioEncoder {

    public static final int RESULT_OK = 0;
    public static final int RESULT_CANCELLED = 1;
    public static final int RESULT_NO_AUDIO = 2;

    public interface Progress {
        void onProgress(long processedUs);
    }

    private static final long TIMEOUT_US = 10_000;

    private static final class Chunk {
        final byte[] data;
        final long pts;
        final boolean eos;

        Chunk(byte[] data, long pts, boolean eos) {
            this.data = data;
            this.pts = pts;
            this.eos = eos;
        }
    }

    /**
     * @param aacProfile MediaCodecInfo.CodecProfileLevel.AACObject{LC,HE}
     * @param bitRate    target bitrate in bits/sec
     */
    public static int encode(FileDescriptor inFd, String outPath, int aacProfile,
                             int bitRate, JobControl control, Progress progress)
            throws IOException, InterruptedException {

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(inFd);

        int audioTrack = -1;
        MediaFormat srcFormat = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrack = i;
                srcFormat = f;
                break;
            }
        }
        if (audioTrack < 0) {
            extractor.release();
            return RESULT_NO_AUDIO;
        }
        extractor.selectTrack(audioTrack);

        int sampleRate = srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        String srcMime = srcFormat.getString(MediaFormat.KEY_MIME);

        MediaCodec decoder = MediaCodec.createDecoderByType(srcMime);
        decoder.configure(srcFormat, null, null, 0);
        decoder.start();

        MediaFormat encFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount);
        encFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile);
        encFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        encFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        MediaMuxer muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxTrack = -1;
        boolean muxerStarted = false;

        MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();

        ArrayDeque<Chunk> pending = new ArrayDeque<>();
        boolean extractorDone = false;
        boolean decoderDone = false;
        boolean encoderInputDone = false;
        boolean encoderDone = false;

        int result = RESULT_OK;

        try {
            while (!encoderDone) {
                control.waitIfPaused();
                if (control.cancelled) {
                    result = RESULT_CANCELLED;
                    break;
                }

                // 1) extractor -> decoder input
                if (!extractorDone) {
                    int inIndex = decoder.dequeueInputBuffer(0);
                    if (inIndex >= 0) {
                        ByteBuffer buf = decoder.getInputBuffer(inIndex);
                        int size = extractor.readSampleData(buf, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            extractorDone = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            decoder.queueInputBuffer(inIndex, 0, size, pts, 0);
                            extractor.advance();
                        }
                    }
                }

                // 2) decoder output -> pending PCM queue
                if (!decoderDone) {
                    int outIndex = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US);
                    if (outIndex >= 0) {
                        boolean eos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        if (decInfo.size > 0) {
                            ByteBuffer pcm = decoder.getOutputBuffer(outIndex);
                            byte[] bytes = new byte[decInfo.size];
                            pcm.position(decInfo.offset);
                            pcm.get(bytes);
                            pending.add(new Chunk(bytes, decInfo.presentationTimeUs, false));
                        }
                        decoder.releaseOutputBuffer(outIndex, false);
                        if (eos) {
                            pending.add(new Chunk(null, 0, true));
                            decoderDone = true;
                        }
                    }
                }

                // 3) pending PCM -> encoder input
                while (!pending.isEmpty() && !encoderInputDone) {
                    int inIndex = encoder.dequeueInputBuffer(0);
                    if (inIndex < 0) break;
                    Chunk c = pending.pollFirst();
                    if (c.eos) {
                        encoder.queueInputBuffer(inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        encoderInputDone = true;
                    } else {
                        ByteBuffer dst = encoder.getInputBuffer(inIndex);
                        dst.clear();
                        dst.put(c.data);
                        encoder.queueInputBuffer(inIndex, 0, c.data.length, c.pts, 0);
                    }
                }

                // 4) encoder output -> muxer
                int encIndex = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US);
                if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxTrack = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (encIndex >= 0) {
                    ByteBuffer outBuf = encoder.getOutputBuffer(encIndex);
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encInfo.size = 0;
                    }
                    if (encInfo.size > 0 && muxerStarted) {
                        outBuf.position(encInfo.offset);
                        outBuf.limit(encInfo.offset + encInfo.size);
                        muxer.writeSampleData(muxTrack, outBuf, encInfo);
                        if (progress != null) progress.onProgress(encInfo.presentationTimeUs);
                    }
                    encoder.releaseOutputBuffer(encIndex, false);
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true;
                    }
                }
            }
        } finally {
            safeStop(decoder);
            safeStop(encoder);
            if (muxerStarted) {
                try {
                    muxer.stop();
                } catch (Exception ignored) {
                }
            }
            muxer.release();
            extractor.release();
        }
        return result;
    }

    private static void safeStop(MediaCodec codec) {
        try {
            codec.stop();
        } catch (Exception ignored) {
        }
        codec.release();
    }

    private AudioEncoder() {
    }
}
