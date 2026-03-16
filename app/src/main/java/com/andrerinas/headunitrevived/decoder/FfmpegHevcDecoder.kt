package com.andrerinas.headunitrevived.decoder

import android.os.SystemClock
import android.view.Surface
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.HeadUnitScreenConfig
import com.andrerinas.headunitrevived.utils.Settings

/**
 * Software H.265 decoder using FFmpeg (bytedeco). Decodes HEVC NAL units to YUV420P and
 * renders to a Surface via OpenGL. Use when [Settings.useFfmpegForHevc] is true.
 */
internal class FfmpegHevcDecoder(private val settings: Settings) {

    var dimensionsListener: VideoDecoder.VideoDimensionsListener? = null
    var onFpsChanged: ((Int) -> Unit)? = null
    @Volatile var onFirstFrameListener: (() -> Unit)? = null
    @Volatile var lastFrameRenderedMs: Long = 0L

    val videoWidth: Int get() = if (decoderWidth > 0) decoderWidth else HeadUnitScreenConfig.getNegotiatedWidth()
    val videoHeight: Int get() = if (decoderHeight > 0) decoderHeight else HeadUnitScreenConfig.getNegotiatedHeight()

    private var decoderWidth = 0
    private var decoderHeight = 0
    private var renderer: YuvSurfaceRenderer? = null
    private var surface: Surface? = null
    private var frameCount = 0
    private var lastFpsLogTime = 0L
    private var codecConfigured = false
    private var yuvBuffer: ByteArray? = null
    private val maxYuvSize = 3840 * 2160 * 3 / 2

    private var codecContext: Any? = null
    private var codec: Any? = null
    private var packet: Any? = null
    private var frame: Any? = null
    private var running = true

    init {
        try {
            Loader.load()
        } catch (e: Throwable) {
            AppLog.e("FfmpegHevcDecoder: FFmpeg load failed", e)
        }
    }

    fun setSurface(surface: Surface?) {
        synchronized(this) {
            if (this.surface === surface) return
            renderer?.release()
            renderer = null
            this.surface = surface
            if (surface != null && surface.isValid) {
                renderer = YuvSurfaceRenderer().apply { setSurface(surface) }
            }
        }
    }

    fun stop(reason: String = "unknown") {
        running = false
        releaseCodec()
        renderer?.release()
        renderer = null
        surface = null
        onFirstFrameListener = null
        lastFrameRenderedMs = 0L
        AppLog.i("FfmpegHevcDecoder stopped: $reason")
    }

    fun decode(buffer: ByteArray, offset: Int, size: Int) {
        if (!running || surface == null || !surface!!.isValid) return
        if (size < 5) return
        try {
            ensureCodec()
            feedPacket(buffer, offset, size)
            drainFrames()
        } catch (e: Exception) {
            AppLog.e("FfmpegHevcDecoder decode error", e)
        }
    }

    private fun ensureCodec() {
        if (codecContext != null) return
        try {
            val c = avcodec_find_decoder(AV_CODEC_ID_HEVC)
                ?: throw IllegalStateException("HEVC codec not found")
            codec = c
            val ctx = avcodec_alloc_context3(c)
                ?: throw IllegalStateException("avcodec_alloc_context3 failed")
            var ret = avcodec_open2(ctx, c, null)
            if (ret < 0) {
                avcodec_free_context(ctx)
                throw IllegalStateException("avcodec_open2 failed: $ret")
            }
            codecContext = ctx
            packet = av_packet_alloc()
            frame = av_frame_alloc()
            AppLog.i("FfmpegHevcDecoder: FFmpeg HEVC decoder initialized")
        } catch (e: Exception) {
            AppLog.e("FfmpegHevcDecoder: init failed", e)
        }
    }

    private fun feedPacket(buffer: ByteArray, offset: Int, size: Int) {
        val ctx = codecContext ?: return
        val pkt = packet ?: return
        av_init_packet(pkt)
        av_packet_from_data(pkt, buffer, offset, size)
        val ret = avcodec_send_packet(ctx, pkt)
        av_packet_unref(pkt)
        if (ret < 0 && ret != AVERROR_EOF && ret != AVERROR_EAGAIN) {
            AppLog.w("FfmpegHevcDecoder: send_packet failed $ret")
        }
    }

    private fun drainFrames() {
        val ctx = codecContext ?: return
        val f = frame ?: return
        val r = renderer ?: return
        while (running) {
            val ret = avcodec_receive_frame(ctx, f)
            when {
                ret == AVERROR_EAGAIN || ret == AVERROR_EOF -> break
                ret < 0 -> {
                    AppLog.w("FfmpegHevcDecoder: receive_frame $ret")
                    break
                }
            }
            val w = frame_width(f)
            val h = frame_height(f)
            if (w <= 0 || h <= 0) continue
            if (decoderWidth != w || decoderHeight != h) {
                decoderWidth = w
                decoderHeight = h
                dimensionsListener?.onVideoDimensionsChanged(w, h)
            }
            val yuv = copyFrameToYuv(f, w, h)
            if (yuv != null) {
                r.drawFrame(w, h, yuv)
                lastFrameRenderedMs = SystemClock.elapsedRealtime()
                onFirstFrameListener?.let { it(); onFirstFrameListener = null }
                frameCount++
                val now = System.currentTimeMillis()
                val elapsed = now - lastFpsLogTime
                if (elapsed >= 1000 && lastFpsLogTime != 0L) {
                    onFpsChanged?.invoke((frameCount * 1000 / elapsed).toInt())
                    frameCount = 0
                    lastFpsLogTime = now
                } else if (lastFpsLogTime == 0L) lastFpsLogTime = now
            }
        }
    }

    private fun copyFrameToYuv(f: Any, width: Int, height: Int): ByteArray? {
        val ySize = width * height
        val uvSize = (width / 2) * (height / 2)
        val total = ySize + uvSize * 2
        if (total > maxYuvSize) return null
        var buf = yuvBuffer
        if (buf == null || buf.size < total) {
            buf = ByteArray(total)
            yuvBuffer = buf
        }
        val ls0 = frame_linesize(f, 0)
        val ls1 = frame_linesize(f, 1)
        val ls2 = frame_linesize(f, 2)
        if (ls0 <= 0 || ls1 <= 0 || ls2 <= 0) return null
        if (!copyPlaneFromFrame(f, 0, ls0, width, height, buf, 0)) return null
        if (!copyPlaneFromFrame(f, 1, ls1, width / 2, height / 2, buf, ySize)) return null
        if (!copyPlaneFromFrame(f, 2, ls2, width / 2, height / 2, buf, ySize + uvSize)) return null
        return buf
    }

    private fun copyPlaneFromFrame(f: Any, planeIndex: Int, srcStride: Int, w: Int, h: Int, dst: ByteArray, dstOffset: Int): Boolean {
        return try {
            val dataPtr = f.javaClass.getMethod("data").invoke(f)
            val ptr = dataPtr?.javaClass?.getMethod("get", Int::class.javaPrimitiveType)?.invoke(dataPtr, planeIndex) ?: return false
            var off = dstOffset
            for (y in 0 until h) {
                ptr.javaClass.getMethod("position", Long::class.javaPrimitiveType).invoke(ptr, y.toLong() * srcStride)
                ptr.javaClass.getMethod("get", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(ptr, dst, off, w)
                off += w
            }
            true
        } catch (_: Exception) { false }
    }

    private fun releaseCodec() {
        try {
            codecContext?.let { avcodec_free_context(it) }
            packet?.let { av_packet_free(it) }
            frame?.let { av_frame_free(it) }
        } catch (_: Exception) {}
        codecContext = null
        codec = null
        packet = null
        frame = null
        codecConfigured = false
        decoderWidth = 0
        decoderHeight = 0
    }

    companion object {
        private const val AVERROR_EOF = -541478725
        private const val AVERROR_EAGAIN = -11

        init {
            try {
                Loader.load()
            } catch (_: Throwable) {}
        }
    }
}

// Stub: replace with bytedeco FFmpeg calls. We use reflection or direct imports once dependency is confirmed.
private object Loader {
    fun load() {
        Class.forName("org.bytedeco.ffmpeg.global.avcodec")
        Class.forName("org.bytedeco.ffmpeg.global.avutil")
    }
}

private fun avcodec_find_decoder(id: Int): Any? = try {
    val c = Class.forName("org.bytedeco.ffmpeg.global.avcodec")
    val m = c.getMethod("avcodec_find_decoder", Int::class.javaPrimitiveType)
    m.invoke(null, id)
} catch (e: Exception) { null }

private fun avcodec_alloc_context3(codec: Any): Any? = try {
    val c = Class.forName("org.bytedeco.ffmpeg.global.avcodec")
    val m = c.getMethod("avcodec_alloc_context3", Class.forName("org.bytedeco.ffmpeg.avcodec.AVCodec"))
    m.invoke(null, codec)
} catch (e: Exception) { null }

private fun avcodec_open2(ctx: Any, codec: Any, opts: Any?): Int = try {
    val c = Class.forName("org.bytedeco.ffmpeg.global.avcodec")
    val dict = Class.forName("org.bytedeco.ffmpeg.avutil.AVDictionary")
    val m = c.getMethod("avcodec_open2", Class.forName("org.bytedeco.ffmpeg.avcodec.AVCodecContext"), Class.forName("org.bytedeco.ffmpeg.avcodec.AVCodec"), dict)
    (m.invoke(null, ctx, codec, opts) as? Number)?.toInt() ?: -1
} catch (e: Exception) { -1 }

private fun avcodec_free_context(ctx: Any) = try {
    val c = Class.forName("org.bytedeco.ffmpeg.global.avcodec")
    val m = c.getMethod("avcodec_free_context", Class.forName("org.bytedeco.javacpp.Pointer"))
    m.invoke(null, ctx)
} catch (_: Exception) {}

private fun av_packet_alloc(): Any? = try {
    val c = Class.forName("org.bytedeco.ffmpeg.global.avcodec")
    val m = c.getMethod("av_packet_alloc")
    m.invoke(null)
} catch (e: Exception) { null }

private fun av_frame_alloc(): Any? = try {
    val c = Class.forName("org.bytedeco.ffmpeg.global.avutil")
    val m = c.getMethod("av_frame_alloc")
    m.invoke(null)
} catch (e: Exception) { null }

private fun av_init_packet(pkt: Any) = try {
    val c = Class.forName("org.bytedeco.ffmpeg.global.avcodec")
    val m = c.getMethod("av_init_packet", Class.forName("org.bytedeco.ffmpeg.avcodec.AVPacket"))
    m.invoke(null, pkt)
} catch (_: Exception) {}

private fun av_packet_from_data(pkt: Any, data: ByteArray, offset: Int, size: Int) = try {
    val bytePointer = Class.forName("org.bytedeco.javacpp.BytePointer")
    val con = bytePointer.getConstructor(ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    val ptr = con.newInstance(data, offset, size)
    pkt.javaClass.getMethod("data", Class.forName("org.bytedeco.javacpp.Pointer")).invoke(pkt, ptr)
    pkt.javaClass.getMethod("size", Int::class.javaPrimitiveType).invoke(pkt, size)
} catch (_: Exception) {}

private fun avcodec_send_packet(ctx: Any, pkt: Any): Int = try {
    val c = Class.forName("org.bytedeco.ffmpeg.global.avcodec")
    val m = c.getMethod("avcodec_send_packet", Class.forName("org.bytedeco.ffmpeg.avcodec.AVCodecContext"), Class.forName("org.bytedeco.ffmpeg.avcodec.AVPacket"))
    (m.invoke(null, ctx, pkt) as? Number)?.toInt() ?: -1
} catch (e: Exception) { -1 }

private fun av_packet_unref(pkt: Any) = try {
    val c = Class.forName("org.bytedeco.ffmpeg.global.avcodec")
    val m = c.getMethod("av_packet_unref", Class.forName("org.bytedeco.ffmpeg.avcodec.AVPacket"))
    m.invoke(null, pkt)
} catch (_: Exception) {}

private fun avcodec_receive_frame(ctx: Any, frame: Any): Int = try {
    val c = Class.forName("org.bytedeco.ffmpeg.global.avcodec")
    val m = c.getMethod("avcodec_receive_frame", Class.forName("org.bytedeco.ffmpeg.avcodec.AVCodecContext"), Class.forName("org.bytedeco.ffmpeg.avutil.AVFrame"))
    (m.invoke(null, ctx, frame) as? Number)?.toInt() ?: -1
} catch (e: Exception) { -1 }

private fun av_packet_free(pkt: Any) = try {
    val c = Class.forName("org.bytedeco.ffmpeg.global.avcodec")
    val m = c.getMethod("av_packet_free", Class.forName("org.bytedeco.javacpp.Pointer"))
    m.invoke(null, pkt)
} catch (_: Exception) {}

private fun av_frame_free(frame: Any) = try {
    val c = Class.forName("org.bytedeco.ffmpeg.global.avutil")
    val m = c.getMethod("av_frame_free", Class.forName("org.bytedeco.javacpp.Pointer"))
    m.invoke(null, frame)
} catch (_: Exception) {}

private val AV_CODEC_ID_HEVC: Int
    get() = try {
        val c = Class.forName("org.bytedeco.ffmpeg.global.avcodec")
        val f = c.getField("AV_CODEC_ID_HEVC")
        f.getInt(null)
    } catch (e: Exception) { 173 }

private fun frame_width(f: Any): Int = try {
    f.javaClass.getMethod("width").invoke(f) as? Int ?: 0
} catch (_: Exception) { 0 }

private fun frame_height(f: Any): Int = try {
    f.javaClass.getMethod("height").invoke(f) as? Int ?: 0
} catch (_: Exception) { 0 }

private fun frame_linesize(f: Any, index: Int): Int = try {
    val linesize = f.javaClass.getMethod("linesize").invoke(f)
    val arr = linesize?.javaClass?.getMethod("get", Int::class.javaPrimitiveType)?.invoke(linesize, index)
    (arr as? Number)?.toInt() ?: 0
} catch (_: Exception) { 0 }
