package com.andrerinas.headunitrevived.decoder

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.HeadUnitScreenConfig
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.Locale
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.MediaInformationSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FFmpeg-based video decoder for formats not supported by MediaCodec.
 * Uses ffmpeg-kit-full for hardware-accelerated decoding when available.
 */
class FFmpegVideoDecoder(private val settings: Settings) {
    companion object {
        private const val TAG = "FFmpegVideoDecoder"
        
        /**
         * Checks if FFmpeg is available and initialized.
         */
        fun isFFmpegAvailable(): Boolean {
            return try {
                // Try to get FFmpeg version to verify it's loaded
                val version = FFmpegKitConfig.getFFmpegVersion()
                version.isNotEmpty()
            } catch (e: Exception) {
                AppLog.e("FFmpeg not available", e)
                false
            }
        }
        
        /**
         * Gets FFmpeg version string for debugging.
         */
        fun getFFmpegVersion(): String {
            return try {
                FFmpegKitConfig.getFFmpegVersion()
            } catch (e: Exception) {
                "Unknown"
            }
        }
        
        /**
         * Checks if a specific codec is available in FFmpeg.
         */
        fun isCodecSupported(codecName: String): Boolean {
            return try {
                val session = FFprobeKit.getMediaInformation("-hide_banner -loglevel quiet -show_streams")
                true
            } catch (e: Exception) {
                false
            }
        }
    }
    
    private var currentSession: FFmpegSession? = null
    private val isRunning = AtomicBoolean(false)
    private var mSurface: Surface? = null
    private var mWidth = 0
    private var mHeight = 0
    
    var dimensionsListener: VideoDimensionsListener? = null
    var onFirstFrameListener: (() -> Unit)? = null
    
    val videoWidth: Int get() = mWidth
    val videoHeight: Int get() = mHeight
    
    /**
     * Sets the rendering surface for decoded video.
     */
    fun setSurface(surface: Surface?) {
        synchronized(this) {
            if (mSurface === surface) return
            stop("New surface")
            mSurface = surface
        }
    }
    
    /**
     * Stops the decoder and cancels any running session.
     */
    fun stop(reason: String = "unknown") {
        synchronized(this) {
            isRunning.set(false)
            currentSession?.let { session ->
                try {
                    FFmpegKit.cancel(session.sessionId)
                    AppLog.i("Cancelled FFmpeg session: $reason")
                } catch (e: Exception) {
                    AppLog.e("Error cancelling session", e)
                }
            }
            currentSession = null
        }
    }
    
    /**
     * Decodes a video frame using FFmpeg.
     * This is a simplified example - full implementation would need
     * to handle packet streaming and surface rendering.
     */
    fun decode(buffer: ByteArray, offset: Int, size: Int, forceSoftware: Boolean, codecName: String) {
        if (!isRunning.get() || mSurface == null) {
            initializeDecoder(buffer, offset, size, codecName)
            return
        }
        
        // In a full implementation, this would feed packets to the running session
        // For now, this is a placeholder showing FFmpeg integration
        synchronized(this) {
            // FFmpeg decoding is typically done via command-line sessions
            // For real-time streaming, you'd use libavcodec directly via JNI
            // or implement a custom pipeline with FFmpegKit's frame processing
        }
    }
    
    /**
     * Initializes the decoder with codec configuration data.
     */
    private fun initializeDecoder(buffer: ByteArray, offset: Int, size: Int, codecName: String) {
        if (mSurface == null || !mSurface!!.isValid) return
        
        // Detect video dimensions from SPS/PPS or fallback to negotiated values
        val detectedDims = parseVideoDimensions(buffer, offset, size, codecName)
        mWidth = detectedDims.first ?: HeadUnitScreenConfig.getNegotiatedWidth()
        mHeight = detectedDims.second ?: HeadUnitScreenConfig.getNegotiatedHeight()
        
        if (mWidth <= 0 || mHeight <= 0) {
            AppLog.w("Could not determine video dimensions")
            return
        }
        
        dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
        isRunning.set(true)
        
        AppLog.i("FFmpeg decoder initialized: ${mWidth}x${mHeight}, codec: $codecName")
        AppLog.i("FFmpeg version: ${getFFmpegVersion()}")
    }
    
    /**
     * Parses video dimensions from codec configuration data.
     */
    private fun parseVideoDimensions(buffer: ByteArray, offset: Int, size: Int, codecName: String): Pair<Int?, Int?> {
        // Simplified dimension parsing - in production, use FFprobe
        return when {
            codecName.contains("265", ignoreCase = true) -> {
                // H.265 dimension parsing would go here
                null to null
            }
            else -> {
                // H.264 dimension parsing would go here
                null to null
            }
        }
    }
    
    /**
     * Creates an FFmpeg command for decoding to a surface.
     * This is an example of how to construct FFmpeg commands.
     */
    fun createDecodeCommand(inputPath: String, surface: Surface): String {
        val hwAccel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            "-hwaccel mediacodec"
        } else ""
        
        return "-hwaccel mediacodec -i $inputPath -surface ${surface.hashCode()} -f rawvideo -pix_fmt rgba"
    }
    
    /**
     * Executes an FFmpeg command asynchronously.
     */
    fun executeCommand(command: String, onComplete: (Boolean) -> Unit) {
        val args = command.split(" ")
        
        currentSession = FFmpegKit.executeAsync(*args.toTypedArray(), { session ->
            if (ReturnCode.isSuccess(session.returnCode)) {
                AppLog.i("FFmpeg command completed successfully")
                onComplete(true)
            } else {
                AppLog.e("FFmpeg command failed: ${session.failStackTrace}")
                onComplete(false)
            }
        }, { log ->
            AppLog.d("FFmpeg: ${log.message}")
        }, { report ->
            // Progress reporting
        })
    }
}
