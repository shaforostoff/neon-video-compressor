// Native HEVC transcode + remux engine for Neon Video Compressor.
//
// Uses FFmpeg's libav* APIs directly (not the ffmpeg CLI) so we get clean
// per-frame pause/cancel checkpoints and progress reporting.
//
//  * nativeTranscodeVideo: decode source video -> encode HEVC (libx265) into a
//    video-only temp .mp4 with the hvc1 tag. Reports progress; honours pause/cancel.
//  * nativeRemux: stream-copy a chosen video source + audio source into the final
//    .mp4 with +faststart (and hvc1 when the video was encoded by us). Either
//    source may be absent (fd -1); a missing video source yields an audio-only
//    .m4a.
//  * nativeProbe: duration / audio presence / dimensions / rotation.
//
// Inputs are passed as raw file descriptors and read through a custom AVIO
// (pread on the fd) — never reopened by path, so SAF content-URI grants survive.

#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <errno.h>
#include <unistd.h>
#include <sys/stat.h>
#include <stdarg.h>

#include <android/log.h>

#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/opt.h>
#include <libavutil/display.h>
#include <libavutil/avutil.h>
#include <libswscale/swscale.h>

#define LOG_TAG "NativeConverter"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define RET_OK         0
#define RET_ERROR     -1
#define RET_CANCELLED -100

// Forward FFmpeg's own log messages to logcat (tag "FFmpeg") for diagnostics.
static void ff_log_to_android(void *ptr, int level, const char *fmt, va_list vl) {
    if (level > AV_LOG_INFO) return;
    char line[1024];
    vsnprintf(line, sizeof(line), fmt, vl);
    int pri = level <= AV_LOG_ERROR ? ANDROID_LOG_ERROR
              : (level <= AV_LOG_WARNING ? ANDROID_LOG_WARN : ANDROID_LOG_INFO);
    __android_log_print(pri, "FFmpeg", "%s", line);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    av_log_set_level(AV_LOG_INFO);
    av_log_set_callback(ff_log_to_android);
    return JNI_VERSION_1_6;
}

// ---------------------------------------------------------------------------
// Control block (pause / cancel), shared with Java via an opaque long handle.
// ---------------------------------------------------------------------------
typedef struct {
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    int paused;
    int cancel;
} Control;

static int interrupt_cb(void *arg) {
    Control *c = (Control *) arg;
    return c ? c->cancel : 0;
}

// Blocks while paused. Returns 1 if cancelled.
static int check_control(Control *c) {
    if (!c) return 0;
    pthread_mutex_lock(&c->mutex);
    while (c->paused && !c->cancel) {
        pthread_cond_wait(&c->cond, &c->mutex);
    }
    int cancelled = c->cancel;
    pthread_mutex_unlock(&c->mutex);
    return cancelled;
}

// ---------------------------------------------------------------------------
// Custom AVIO over a raw file descriptor.
//
// We must NOT reopen the input by path (e.g. /proc/self/fd/N): for SAF content
// URIs the fd itself is the access grant, and resolving it back to a real path
// is denied by scoped storage. Reading directly from the fd via pread keeps the
// grant and avoids disturbing the fd's shared file offset.
// ---------------------------------------------------------------------------
typedef struct {
    int fd;
    int64_t pos;
    AVIOContext *avio;
} FdSource;

static int fdsrc_read(void *opaque, uint8_t *buf, int size) {
    FdSource *s = (FdSource *) opaque;
    ssize_t n = pread(s->fd, buf, (size_t) size, s->pos);
    if (n < 0) return AVERROR(errno);
    if (n == 0) return AVERROR_EOF;
    s->pos += n;
    return (int) n;
}

static int64_t fdsrc_seek(void *opaque, int64_t offset, int whence) {
    FdSource *s = (FdSource *) opaque;
    struct stat st;
    if (whence & AVSEEK_SIZE) {
        return fstat(s->fd, &st) ? AVERROR(errno) : (int64_t) st.st_size;
    }
    whence &= ~AVSEEK_FORCE;
    int64_t base = 0;
    if (whence == SEEK_CUR) {
        base = s->pos;
    } else if (whence == SEEK_END) {
        if (fstat(s->fd, &st)) return AVERROR(errno);
        base = st.st_size;
    }
    s->pos = base + offset;
    return s->pos;
}

// Opens an input from a raw fd using a custom AVIO. Returns NULL on failure.
static AVFormatContext *fd_open_input(int fd, Control *ctrl, FdSource **out) {
    FdSource *s = calloc(1, sizeof(FdSource));
    if (!s) return NULL;
    s->fd = fd;
    s->pos = 0;
    size_t bufsz = 1 << 16;
    uint8_t *buf = av_malloc(bufsz);
    if (!buf) { free(s); return NULL; }
    s->avio = avio_alloc_context(buf, (int) bufsz, 0, s, fdsrc_read, NULL, fdsrc_seek);
    if (!s->avio) { av_free(buf); free(s); return NULL; }

    AVFormatContext *fmt = avformat_alloc_context();
    if (!fmt) {
        av_freep(&s->avio->buffer);
        avio_context_free(&s->avio);
        free(s);
        return NULL;
    }
    fmt->pb = s->avio;
    fmt->flags |= AVFMT_FLAG_CUSTOM_IO;
    if (ctrl) {
        fmt->interrupt_callback.callback = interrupt_cb;
        fmt->interrupt_callback.opaque = ctrl;
    }
    if (avformat_open_input(&fmt, NULL, NULL, NULL) < 0) {
        // fmt is freed by avformat_open_input on failure; free our AVIO.
        av_freep(&s->avio->buffer);
        avio_context_free(&s->avio);
        free(s);
        return NULL;
    }
    *out = s;
    return fmt;
}

static void fd_close_input(AVFormatContext **fmt, FdSource *s) {
    if (fmt && *fmt) avformat_close_input(fmt); // leaves custom pb to us
    if (s) {
        if (s->avio) {
            av_freep(&s->avio->buffer);
            avio_context_free(&s->avio);
        }
        free(s);
    }
}

// ---------------------------------------------------------------------------
// Control lifecycle
// ---------------------------------------------------------------------------
JNIEXPORT jlong JNICALL
Java_com_shaforostoff_neonvideocompressor_engine_NativeConverter_nativeCreateControl(
        JNIEnv *env, jclass clazz) {
    Control *c = calloc(1, sizeof(Control));
    if (!c) return 0;
    pthread_mutex_init(&c->mutex, NULL);
    pthread_cond_init(&c->cond, NULL);
    return (jlong) (intptr_t) c;
}

JNIEXPORT void JNICALL
Java_com_shaforostoff_neonvideocompressor_engine_NativeConverter_nativeSetPaused(
        JNIEnv *env, jclass clazz, jlong handle, jboolean paused) {
    Control *c = (Control *) (intptr_t) handle;
    if (!c) return;
    pthread_mutex_lock(&c->mutex);
    c->paused = paused ? 1 : 0;
    pthread_cond_broadcast(&c->cond);
    pthread_mutex_unlock(&c->mutex);
}

JNIEXPORT void JNICALL
Java_com_shaforostoff_neonvideocompressor_engine_NativeConverter_nativeCancel(
        JNIEnv *env, jclass clazz, jlong handle) {
    Control *c = (Control *) (intptr_t) handle;
    if (!c) return;
    pthread_mutex_lock(&c->mutex);
    c->cancel = 1;
    pthread_cond_broadcast(&c->cond);
    pthread_mutex_unlock(&c->mutex);
}

JNIEXPORT void JNICALL
Java_com_shaforostoff_neonvideocompressor_engine_NativeConverter_nativeDestroyControl(
        JNIEnv *env, jclass clazz, jlong handle) {
    Control *c = (Control *) (intptr_t) handle;
    if (!c) return;
    pthread_mutex_destroy(&c->mutex);
    pthread_cond_destroy(&c->cond);
    free(c);
}

// ---------------------------------------------------------------------------
// Probe
// returns long[6] = { durationUs, hasAudio, width, height, rotationDeg, hasVideo }
// ---------------------------------------------------------------------------
JNIEXPORT jlongArray JNICALL
Java_com_shaforostoff_neonvideocompressor_engine_NativeConverter_nativeProbe(
        JNIEnv *env, jclass clazz, jint fd) {
    jlong out[6] = {0, 0, 0, 0, 0, 0};

    FdSource *src = NULL;
    AVFormatContext *fmt = fd_open_input(fd, NULL, &src);
    if (fmt) {
        if (avformat_find_stream_info(fmt, NULL) >= 0) {
            if (fmt->duration > 0) out[0] = (jlong) fmt->duration; // microseconds
            for (unsigned i = 0; i < fmt->nb_streams; i++) {
                AVStream *st = fmt->streams[i];
                if (st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO && out[5] == 0) {
                    out[5] = 1;
                    out[2] = st->codecpar->width;
                    out[3] = st->codecpar->height;
                    const AVPacketSideData *sd = av_packet_side_data_get(
                            st->codecpar->coded_side_data,
                            st->codecpar->nb_coded_side_data,
                            AV_PKT_DATA_DISPLAYMATRIX);
                    if (sd && sd->size >= 9 * 4) {
                        double rot = av_display_rotation_get((const int32_t *) sd->data);
                        if (!isnan(rot)) {
                            long deg = ((long) llround(rot)) % 360;
                            if (deg < 0) deg += 360;
                            out[4] = deg;
                        }
                    }
                } else if (st->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
                    out[1] = 1;
                }
            }
        }
    } else {
        LOGE("nativeProbe: open failed");
    }
    fd_close_input(&fmt, src);
    LOGI("nativeProbe: done dur=%lld hasAudio=%lld hasVideo=%lld",
         (long long) out[0], (long long) out[1], (long long) out[5]);

    jlongArray arr = (*env)->NewLongArray(env, 6);
    if (arr) (*env)->SetLongArrayRegion(env, arr, 0, 6, out);
    return arr;
}

// ---------------------------------------------------------------------------
// Transcode helpers
// ---------------------------------------------------------------------------
static void copy_display_matrix(AVStream *in, AVStream *out) {
    const AVPacketSideData *sd = av_packet_side_data_get(
            in->codecpar->coded_side_data,
            in->codecpar->nb_coded_side_data,
            AV_PKT_DATA_DISPLAYMATRIX);
    if (!sd) return;
    AVPacketSideData *entry = av_packet_side_data_new(
            &out->codecpar->coded_side_data,
            &out->codecpar->nb_coded_side_data,
            AV_PKT_DATA_DISPLAYMATRIX, sd->size, 0);
    if (entry && entry->data) memcpy(entry->data, sd->data, sd->size);
}

static int drain_encoder(AVCodecContext *enc, AVFormatContext *ofmt,
                         AVStream *ost, AVPacket *opkt, int64_t *lastDts) {
    int ret;
    while ((ret = avcodec_receive_packet(enc, opkt)) >= 0) {
        opkt->stream_index = ost->index;
        av_packet_rescale_ts(opkt, enc->time_base, ost->time_base);
        // Monotonic-DTS guard. Normally a no-op, but after a pause the encoder is
        // torn down and rebuilt to release RAM; the fresh encoder restarts its DTS
        // relative to the (continuing) input PTS, which can dip below the last DTS
        // we wrote by the B-frame reorder depth. Bump such packets so the muxer
        // keeps a strictly increasing DTS, holding PTS >= DTS.
        if (opkt->dts != AV_NOPTS_VALUE) {
            if (*lastDts != AV_NOPTS_VALUE && opkt->dts <= *lastDts) {
                opkt->dts = *lastDts + 1;
                if (opkt->pts != AV_NOPTS_VALUE && opkt->pts < opkt->dts) {
                    opkt->pts = opkt->dts;
                }
            }
            *lastDts = opkt->dts;
        }
        ret = av_interleaved_write_frame(ofmt, opkt); // takes ownership / unrefs
        if (ret < 0) return ret;
    }
    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) return 0;
    return ret;
}

// Pull all currently-available frames from the decoder, scale if needed,
// encode them, and report progress per frame.
// Returns <0 on error, 0 when the decoder is drained, or 1 when maxUs was
// reached (the current frame is beyond the limit and was dropped) so the caller
// should stop feeding input and finalize.
static int pump_decoder(JNIEnv *env, AVCodecContext *dec, AVCodecContext *enc,
                        AVFormatContext *ofmt, AVStream *ost, AVStream *ist,
                        AVFrame *frame, AVPacket *opkt,
                        struct SwsContext **sws, AVFrame **swsFrame,
                        int64_t *lastPts, int64_t *lastDts, int64_t maxUs,
                        jobject cb, jmethodID onProg) {
    int ret;
    while ((ret = avcodec_receive_frame(dec, frame)) >= 0) {
        // Re-label deprecated full-range YUVJ formats as their plain equivalents.
        // The byte layout is identical, so this is a metadata-only change that
        // lets full-range data pass straight through to the (full-range-signaled)
        // encoder instead of being range-compressed by swscale.
        if (frame->format == AV_PIX_FMT_YUVJ420P) frame->format = AV_PIX_FMT_YUV420P;
        else if (frame->format == AV_PIX_FMT_YUVJ422P) frame->format = AV_PIX_FMT_YUV422P;
        else if (frame->format == AV_PIX_FMT_YUVJ444P) frame->format = AV_PIX_FMT_YUV444P;

        int64_t ts = frame->best_effort_timestamp != AV_NOPTS_VALUE
                     ? frame->best_effort_timestamp : frame->pts;

        // Preview mode: once we pass the requested window, drop this frame and
        // tell the caller to finalize. Frames still buffered below the limit are
        // flushed normally on the following (drain) pass.
        if (maxUs > 0 && ts != AV_NOPTS_VALUE) {
            int64_t us = av_rescale_q(ts, ist->time_base, AV_TIME_BASE_Q);
            if (us >= maxUs) { av_frame_unref(frame); return 1; }
        }

        if (cb && onProg && ts != AV_NOPTS_VALUE) {
            int64_t us = av_rescale_q(ts, ist->time_base, AV_TIME_BASE_Q);
            (*env)->CallVoidMethod(env, cb, onProg, (jlong) us);
        }

        // Encoder time base == input time base, so timestamps pass through.
        // Guard strict monotonicity: VFR / duplicate source PTS would otherwise
        // make the encoder emit duplicate DTS that the muxer rejects.
        int64_t out_pts = (ts != AV_NOPTS_VALUE) ? ts : (*lastPts + 1);
        if (out_pts <= *lastPts) out_pts = *lastPts + 1;
        *lastPts = out_pts;

        AVFrame *encIn = frame;
        if (frame->format != enc->pix_fmt) {
            if (!*sws) {
                *sws = sws_getContext(frame->width, frame->height, frame->format,
                                      enc->width, enc->height, enc->pix_fmt,
                                      SWS_BILINEAR, NULL, NULL, NULL);
                if (!*sws) { av_frame_unref(frame); return AVERROR(ENOMEM); }
                *swsFrame = av_frame_alloc();
                (*swsFrame)->format = enc->pix_fmt;
                (*swsFrame)->width = enc->width;
                (*swsFrame)->height = enc->height;
                if (av_frame_get_buffer(*swsFrame, 0) < 0) {
                    av_frame_unref(frame);
                    return AVERROR(ENOMEM);
                }
            }
            av_frame_make_writable(*swsFrame);
            sws_scale(*sws, (const uint8_t *const *) frame->data, frame->linesize,
                      0, frame->height, (*swsFrame)->data, (*swsFrame)->linesize);
            encIn = *swsFrame;
        }

        encIn->pts = out_pts;

        ret = avcodec_send_frame(enc, encIn);
        av_frame_unref(frame);
        if (ret < 0) return ret;
        ret = drain_encoder(enc, ofmt, ost, opkt, lastDts);
        if (ret < 0) return ret;
    }
    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) return 0;
    return ret;
}

// Allocates and opens a fresh libx265 encoder configured to match the decoded
// source. Used for the initial encode and to rebuild the encoder after a
// pause-time tear-down — because the settings are identical, its VPS/SPS/PPS
// match the muxer's existing sample entry, and its first output frame is a fresh
// IDR that cleanly splices onto the already-written stream. Returns NULL on
// failure.
static AVCodecContext *open_hevc_encoder(const AVCodec *encoder, AVCodecContext *dec,
                                         AVFormatContext *ifmt, AVStream *ist,
                                         const char *preset, int crf) {
    AVCodecContext *enc = avcodec_alloc_context3(encoder);
    if (!enc) return NULL;
    enc->width = dec->width;
    enc->height = dec->height;
    enc->pix_fmt = AV_PIX_FMT_YUV420P;
    enc->sample_aspect_ratio = dec->sample_aspect_ratio;
    enc->color_range = dec->color_range;
    enc->color_primaries = dec->color_primaries;
    enc->color_trc = dec->color_trc;
    enc->colorspace = dec->colorspace;

    // Many H.264 phone recordings decode to the deprecated full-range YUVJ420P
    // (== YUV420P + JPEG range). Encoding to YUV420P would make swscale squeeze
    // the data into limited range while the copied metadata still says full
    // range → washed-out colours. Signal full range explicitly and let
    // pump_decoder re-label the frames as YUV420P so no range-shifting scale runs.
    int fullRange = dec->color_range == AVCOL_RANGE_JPEG
                    || dec->pix_fmt == AV_PIX_FMT_YUVJ420P
                    || dec->pix_fmt == AV_PIX_FMT_YUVJ422P
                    || dec->pix_fmt == AV_PIX_FMT_YUVJ444P;
    if (fullRange) enc->color_range = AVCOL_RANGE_JPEG;

    AVRational fr = av_guess_frame_rate(ifmt, ist, NULL);
    if (fr.num <= 0 || fr.den <= 0) fr = (AVRational) {30, 1};
    enc->framerate = fr;
    // Use the input's (fine-grained) time base so source timestamps pass through
    // unchanged — avoids collapsing distinct VFR timestamps onto the same tick.
    enc->time_base = ist->time_base;

    av_opt_set(enc->priv_data, "preset", preset, 0);
    char crfStr[16];
    snprintf(crfStr, sizeof(crfStr), "%d", crf);
    av_opt_set(enc->priv_data, "crf", crfStr, 0);

    enc->codec_tag = MKTAG('h', 'v', 'c', '1');
    enc->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    if (avcodec_open2(enc, encoder, NULL) < 0) {
        avcodec_free_context(&enc);
        return NULL;
    }
    LOGI("x265 encoder opened: %dx%d crf=%d preset=%s range=%d", enc->width,
         enc->height, crf, preset, enc->color_range);
    return enc;
}

// ---------------------------------------------------------------------------
// nativeTranscodeVideo
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_shaforostoff_neonvideocompressor_engine_NativeConverter_nativeTranscodeVideo(
        JNIEnv *env, jclass clazz, jint inFd, jstring joutPath,
        jint crf, jstring jpreset, jlong ctrlHandle, jlong maxDurationUs, jobject cb) {

    Control *ctrl = (Control *) (intptr_t) ctrlHandle;
    const char *outPath = (*env)->GetStringUTFChars(env, joutPath, NULL);
    const char *preset = (*env)->GetStringUTFChars(env, jpreset, NULL);

    jmethodID onProg = NULL;
    if (cb) {
        jclass cbCls = (*env)->GetObjectClass(env, cb);
        onProg = (*env)->GetMethodID(env, cbCls, "onProgress", "(J)V");
    }

    AVFormatContext *ifmt = NULL, *ofmt = NULL;
    FdSource *inSrc = NULL;
    AVCodecContext *dec = NULL, *enc = NULL;
    struct SwsContext *sws = NULL;
    AVFrame *frame = NULL, *swsFrame = NULL;
    AVPacket *pkt = NULL, *opkt = NULL;
    int ret = RET_ERROR;
    int header_written = 0;

    ifmt = fd_open_input(inFd, ctrl, &inSrc);
    if (!ifmt) { LOGE("open input failed"); goto end; }
    if (avformat_find_stream_info(ifmt, NULL) < 0) goto end;

    int vstream = av_find_best_stream(ifmt, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    if (vstream < 0) { LOGE("no video stream"); goto end; }
    AVStream *ist = ifmt->streams[vstream];

    const AVCodec *decoder = avcodec_find_decoder(ist->codecpar->codec_id);
    if (!decoder) goto end;
    dec = avcodec_alloc_context3(decoder);
    if (!dec) goto end;
    avcodec_parameters_to_context(dec, ist->codecpar);
    dec->pkt_timebase = ist->time_base;
    LOGI("transcode: src codec=%s pix_fmt=%d %dx%d",
         decoder->name, dec->pix_fmt, dec->width, dec->height);
    if (avcodec_open2(dec, decoder, NULL) < 0) { LOGE("decoder open failed"); goto end; }

    const AVCodec *encoder = avcodec_find_encoder_by_name("libx265");
    if (!encoder) { LOGE("libx265 not found"); goto end; }
    enc = open_hevc_encoder(encoder, dec, ifmt, ist, preset, (int) crf);
    if (!enc) { LOGE("x265 open failed"); goto end; }

    if (avformat_alloc_output_context2(&ofmt, NULL, NULL, outPath) < 0 || !ofmt) goto end;
    AVStream *ost = avformat_new_stream(ofmt, NULL);
    if (!ost) goto end;
    avcodec_parameters_from_context(ost->codecpar, enc);
    ost->codecpar->codec_tag = MKTAG('h', 'v', 'c', '1');
    ost->time_base = enc->time_base;
    copy_display_matrix(ist, ost);

    if (!(ofmt->oformat->flags & AVFMT_NOFILE)) {
        if (avio_open(&ofmt->pb, outPath, AVIO_FLAG_WRITE) < 0) { LOGE("avio_open out failed"); goto end; }
    }
    int wh = avformat_write_header(ofmt, NULL);
    if (wh < 0) { LOGE("write_header failed: %s", av_err2str(wh)); goto end; }
    header_written = 1;
    LOGI("transcode: header written, entering encode loop");

    frame = av_frame_alloc();
    pkt = av_packet_alloc();
    opkt = av_packet_alloc();
    if (!frame || !pkt || !opkt) goto end;

    int64_t lastPts = INT64_MIN;
    int64_t lastDtsOut = AV_NOPTS_VALUE;

    while (1) {
        // Pause handling. Rather than block with the encoder alive — which would
        // pin x265's lookahead, reference frames, frame-thread buffers and thread
        // pool (hundreds of MB) for the whole paused duration — we flush and free
        // the encoder so that memory is released while idle. The decoder and muxer
        // stay open, so we keep our exact stream position; on resume we rebuild the
        // encoder, whose first frame is a fresh IDR that splices onto the stream.
        if (ctrl) {
            pthread_mutex_lock(&ctrl->mutex);
            int wantPause = ctrl->paused && !ctrl->cancel;
            pthread_mutex_unlock(&ctrl->mutex);
            if (wantPause) {
                avcodec_send_frame(enc, NULL);
                if (drain_encoder(enc, ofmt, ost, opkt, &lastDtsOut) < 0) {
                    LOGE("drain before pause failed"); ret = RET_ERROR; goto end;
                }
                avcodec_free_context(&enc);
                if (sws) { sws_freeContext(sws); sws = NULL; }
                if (swsFrame) av_frame_free(&swsFrame);
                LOGI("paused: encoder torn down, RAM released");

                pthread_mutex_lock(&ctrl->mutex);
                while (ctrl->paused && !ctrl->cancel) {
                    pthread_cond_wait(&ctrl->cond, &ctrl->mutex);
                }
                int cancelled = ctrl->cancel;
                pthread_mutex_unlock(&ctrl->mutex);
                if (cancelled) { ret = RET_CANCELLED; goto end; }

                enc = open_hevc_encoder(encoder, dec, ifmt, ist, preset, (int) crf);
                if (!enc) { LOGE("re-open x265 after pause failed"); ret = RET_ERROR; goto end; }
                LOGI("resumed: encoder rebuilt");
            }
            if (ctrl->cancel) { ret = RET_CANCELLED; goto end; }
        }

        int r = av_read_frame(ifmt, pkt);
        if (r < 0) break; // EOF or interrupted
        if (pkt->stream_index == vstream) {
            r = avcodec_send_packet(dec, pkt);
            av_packet_unref(pkt);
            if (r < 0) { LOGE("send_packet failed: %s", av_err2str(r)); ret = RET_ERROR; goto end; }
            r = pump_decoder(env, dec, enc, ofmt, ost, ist, frame, opkt,
                             &sws, &swsFrame, &lastPts, &lastDtsOut, maxDurationUs, cb, onProg);
            if (r < 0) { LOGE("pump_decoder failed: %s", av_err2str(r)); ret = RET_ERROR; goto end; }
            if (r == 1) break; // reached the requested preview window
        } else {
            av_packet_unref(pkt);
        }
    }

    if (check_control(ctrl)) { ret = RET_CANCELLED; goto end; }

    // Flush decoder, then encoder.
    avcodec_send_packet(dec, NULL);
    pump_decoder(env, dec, enc, ofmt, ost, ist, frame, opkt, &sws, &swsFrame, &lastPts, &lastDtsOut, maxDurationUs, cb, onProg);
    avcodec_send_frame(enc, NULL);
    drain_encoder(enc, ofmt, ost, opkt, &lastDtsOut);

    if (av_write_trailer(ofmt) < 0) goto end;
    ret = RET_OK;

end:
    if (header_written && ret != RET_OK && ofmt) {
        // best-effort: leave file for Java to delete
    }
    if (sws) sws_freeContext(sws);
    if (swsFrame) av_frame_free(&swsFrame);
    if (frame) av_frame_free(&frame);
    if (pkt) av_packet_free(&pkt);
    if (opkt) av_packet_free(&opkt);
    if (dec) avcodec_free_context(&dec);
    if (enc) avcodec_free_context(&enc);
    if (ofmt) {
        if (ofmt->pb && !(ofmt->oformat->flags & AVFMT_NOFILE)) avio_closep(&ofmt->pb);
        avformat_free_context(ofmt);
    }
    fd_close_input(&ifmt, inSrc);
    (*env)->ReleaseStringUTFChars(env, joutPath, outPath);
    (*env)->ReleaseStringUTFChars(env, jpreset, preset);
    return ret;
}

// ---------------------------------------------------------------------------
// nativeRemux: stream-copy video (videoFd) + audio (audioFd, or -1) into outPath
// with +faststart. Forces hvc1 tag on the video when videoWasEncoded.
// ---------------------------------------------------------------------------
typedef struct {
    AVFormatContext *fmt;
    FdSource *src;
    int stream_index;   // index of the wanted stream in the input
    AVStream *out;       // matching output stream
    AVPacket *pkt;
    int eof;
    int has_pending;
} CopySource;

static int src_open(CopySource *s, int fd, enum AVMediaType type) {
    s->fmt = NULL;
    s->src = NULL;
    s->stream_index = -1;
    s->pkt = av_packet_alloc();
    s->eof = 0;
    s->has_pending = 0;
    s->fmt = fd_open_input(fd, NULL, &s->src);
    if (!s->fmt) return -1;
    if (avformat_find_stream_info(s->fmt, NULL) < 0) return -1;
    s->stream_index = av_find_best_stream(s->fmt, type, -1, -1, NULL, 0);
    if (s->stream_index < 0) return -1;
    return 0;
}

static void src_close(CopySource *s) {
    if (s->pkt) av_packet_free(&s->pkt);
    fd_close_input(&s->fmt, s->src);
    s->src = NULL;
}

// Reads the next packet belonging to the wanted stream into s->pkt.
static int src_next(CopySource *s) {
    if (s->eof) return 0;
    while (1) {
        int r = av_read_frame(s->fmt, s->pkt);
        if (r < 0) { s->eof = 1; s->has_pending = 0; return 0; }
        if (s->pkt->stream_index == s->stream_index) { s->has_pending = 1; return 1; }
        av_packet_unref(s->pkt);
    }
}

static int64_t src_dts_us(CopySource *s) {
    AVStream *in = s->fmt->streams[s->stream_index];
    int64_t t = s->pkt->dts != AV_NOPTS_VALUE ? s->pkt->dts : s->pkt->pts;
    if (t == AV_NOPTS_VALUE) return INT64_MAX;
    return av_rescale_q(t, in->time_base, AV_TIME_BASE_Q);
}

static int src_write(CopySource *s, AVFormatContext *ofmt) {
    AVStream *in = s->fmt->streams[s->stream_index];
    av_packet_rescale_ts(s->pkt, in->time_base, s->out->time_base);
    s->pkt->stream_index = s->out->index;
    s->pkt->pos = -1;
    int r = av_interleaved_write_frame(ofmt, s->pkt); // unrefs
    s->has_pending = 0;
    return r;
}

JNIEXPORT jint JNICALL
Java_com_shaforostoff_neonvideocompressor_engine_NativeConverter_nativeRemux(
        JNIEnv *env, jclass clazz, jint videoFd, jint audioFd,
        jstring joutPath, jboolean videoWasEncoded) {

    const char *outPath = (*env)->GetStringUTFChars(env, joutPath, NULL);
    int ret = RET_ERROR;
    int header_written = 0;
    int have_video = (videoFd >= 0);
    int have_audio = (audioFd >= 0);

    CopySource v = {0}, a = {0};
    AVFormatContext *ofmt = NULL;

    if (have_video && src_open(&v, videoFd, AVMEDIA_TYPE_VIDEO) < 0) {
        LOGE("remux: video open failed");
        goto end;
    }
    if (have_audio && src_open(&a, audioFd, AVMEDIA_TYPE_AUDIO) < 0) {
        // No usable audio: continue video-only.
        src_close(&a);
        memset(&a, 0, sizeof(a));
        have_audio = 0;
    }
    if (!have_video && !have_audio) { LOGE("remux: no input streams"); goto end; }

    if (avformat_alloc_output_context2(&ofmt, NULL, NULL, outPath) < 0 || !ofmt) {
        // This FFmpeg build has no ipod muxer, which owns the .m4a extension, so
        // the filename guess fails for audio-only output. The mp4 muxer produces
        // a compatible ISO-BMFF container, so force it explicitly.
        if (avformat_alloc_output_context2(&ofmt, NULL, "mp4", outPath) < 0 || !ofmt) {
            LOGE("remux: no output muxer for %s", outPath);
            goto end;
        }
    }

    // Video output stream
    if (have_video) {
        v.out = avformat_new_stream(ofmt, NULL);
        if (!v.out) goto end;
        avcodec_parameters_copy(v.out->codecpar, v.fmt->streams[v.stream_index]->codecpar);
        v.out->codecpar->codec_tag = videoWasEncoded
                ? MKTAG('h', 'v', 'c', '1')
                : 0; // 0 -> let muxer choose a valid tag for the copied codec
        v.out->time_base = v.fmt->streams[v.stream_index]->time_base;
        copy_display_matrix(v.fmt->streams[v.stream_index], v.out);
    }

    // Audio output stream
    if (have_audio) {
        a.out = avformat_new_stream(ofmt, NULL);
        if (!a.out) goto end;
        avcodec_parameters_copy(a.out->codecpar, a.fmt->streams[a.stream_index]->codecpar);
        a.out->codecpar->codec_tag = 0;
        a.out->time_base = a.fmt->streams[a.stream_index]->time_base;
    }

    if (!(ofmt->oformat->flags & AVFMT_NOFILE)) {
        if (avio_open(&ofmt->pb, outPath, AVIO_FLAG_WRITE) < 0) goto end;
    }

    AVDictionary *opts = NULL;
    av_dict_set(&opts, "movflags", "+faststart", 0);
    if (avformat_write_header(ofmt, &opts) < 0) {
        LOGE("remux: write_header failed (incompatible copied codec?)");
        av_dict_free(&opts);
        goto end;
    }
    av_dict_free(&opts);
    header_written = 1;

    // Prime both sources, then merge by DTS.
    if (have_video) src_next(&v);
    if (have_audio) src_next(&a);

    while ((have_video && v.has_pending) || (have_audio && a.has_pending)) {
        int write_video;
        if (!have_video || !v.has_pending) {
            write_video = 0;
        } else if (!have_audio || !a.has_pending) {
            write_video = 1;
        } else {
            write_video = src_dts_us(&v) <= src_dts_us(&a);
        }

        if (write_video) {
            if (src_write(&v, ofmt) < 0) goto end;
            src_next(&v);
        } else {
            if (src_write(&a, ofmt) < 0) goto end;
            src_next(&a);
        }
    }

    if (av_write_trailer(ofmt) < 0) goto end;
    ret = RET_OK;

end:
    if (ofmt) {
        if (ofmt->pb && !(ofmt->oformat->flags & AVFMT_NOFILE)) {
            avio_closep(&ofmt->pb);
        }
        avformat_free_context(ofmt);
    }
    if (have_video) src_close(&v);
    if (have_audio) src_close(&a);
    (*env)->ReleaseStringUTFChars(env, joutPath, outPath);
    return ret;
}

// ---------------------------------------------------------------------------
// nativeCopyClip: stream-copy (no re-encode) the first maxUs of the source
// video track into outPath. Used to produce a lossless reference clip for the
// preview A/B comparison, so the "original" side shows true source quality.
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_shaforostoff_neonvideocompressor_engine_NativeConverter_nativeCopyClip(
        JNIEnv *env, jclass clazz, jint inFd, jstring joutPath, jlong maxUs) {

    const char *outPath = (*env)->GetStringUTFChars(env, joutPath, NULL);
    int ret = RET_ERROR;
    CopySource v = {0};
    AVFormatContext *ofmt = NULL;

    if (src_open(&v, inFd, AVMEDIA_TYPE_VIDEO) < 0) { LOGE("clip: no video stream"); goto end; }

    if (avformat_alloc_output_context2(&ofmt, NULL, NULL, outPath) < 0 || !ofmt) {
        if (avformat_alloc_output_context2(&ofmt, NULL, "mp4", outPath) < 0 || !ofmt) {
            LOGE("clip: no output muxer for %s", outPath);
            goto end;
        }
    }

    v.out = avformat_new_stream(ofmt, NULL);
    if (!v.out) goto end;
    avcodec_parameters_copy(v.out->codecpar, v.fmt->streams[v.stream_index]->codecpar);
    v.out->codecpar->codec_tag = 0; // let the muxer pick a valid tag
    v.out->time_base = v.fmt->streams[v.stream_index]->time_base;
    copy_display_matrix(v.fmt->streams[v.stream_index], v.out);

    if (!(ofmt->oformat->flags & AVFMT_NOFILE)) {
        if (avio_open(&ofmt->pb, outPath, AVIO_FLAG_WRITE) < 0) { LOGE("clip: avio_open failed"); goto end; }
    }

    AVDictionary *opts = NULL;
    av_dict_set(&opts, "movflags", "+faststart", 0);
    if (avformat_write_header(ofmt, &opts) < 0) {
        LOGE("clip: write_header failed");
        av_dict_free(&opts);
        goto end;
    }
    av_dict_free(&opts);

    src_next(&v);
    while (v.has_pending) {
        if (maxUs > 0 && src_dts_us(&v) >= maxUs) break;
        if (src_write(&v, ofmt) < 0) goto end;
        src_next(&v);
    }

    if (av_write_trailer(ofmt) < 0) goto end;
    ret = RET_OK;

end:
    if (ofmt) {
        if (ofmt->pb && !(ofmt->oformat->flags & AVFMT_NOFILE)) avio_closep(&ofmt->pb);
        avformat_free_context(ofmt);
    }
    src_close(&v);
    (*env)->ReleaseStringUTFChars(env, joutPath, outPath);
    return ret;
}
