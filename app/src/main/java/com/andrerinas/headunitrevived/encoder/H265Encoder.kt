package com.andrerinas.headunitrevived.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.andrerinas.headunitrevived.utils.AppLog
import java.nio.ByteBuffer

/**
 * Software H.265/HEVC encoder using FFmpeg Kit or Android's software codec.
 * This class provides encoding functionality for video streams.
 */
class H265Encoder {
    
    companion object {
        private const val TAG = "H265Encoder"
        private const val TIMEOUT_US = 10000L
        
        /**
         * Check if software H.265 encoding is available
         */
        fun isSoftwareH265Available(): Boolean {
            return findSoftwareEncoder() != null
        }
        
        /**
         * Find a software H.265 encoder
         */
        private fun findSoftwareEncoder(): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
            
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            return codecList.codecInfos
                .filter { it.isEncoder }
                .filter { it.supportedTypes.any { t -> t.equals("video/hevc", true) } }
                .find { isSoftwareCodec(it.name) }
                ?.name
        }
        
        /**
         * Determine if a codec is software-based
         */
        private fun isSoftwareCodec(name: String): Boolean {
            val lower = name.lowercase()
            return lower.startsWith("c2.android.hevc") || 
                   lower.startsWith("omx.google.hevc") ||
                   lower.contains(".sw.") ||
                   lower.contains("software")
        }
    }
    
    private var codec: MediaCodec? = null
    private var inputBuffers: Array<ByteBuffer>? = null
    private var outputBuffers: Array<ByteBuffer>? = null
    private var bufferInfo: MediaCodec.BufferInfo? = null
    private var width = 0
    private var height = 0
    private var bitrate = 0
    private var framerate = 0
    @Volatile private var running = false
    
    /**
     * Initialize the H.265 encoder with specified parameters
     */
    fun init(
        width: Int,
        height: Int,
        bitrate: Int,
        framerate: Int,
        forceSoftware: Boolean = true
    ): Boolean {
        try {
            this.width = width
            this.height = height
            this.bitrate = bitrate
            this.framerate = framerate
            
            val mimeType = MediaFormat.MIMETYPE_VIDEO_HEVC
            val encoderName = if (forceSoftware) {
                findSoftwareEncoder() ?: run {
                    AppLog.e(TAG, "No software H.265 encoder found")
                    return false
                }
            } else {
                // Find any available H.265 encoder (prefer hardware)
                findAnyEncoder(mimeType) ?: run {
                    AppLog.e(TAG, "No H.265 encoder found")
                    return false
                }
            }
            
            AppLog.i(TAG, "Creating H.265 encoder: $encoderName (${width}x${height}, ${bitrate}bps, ${framerate}fps)")
            
            codec = MediaCodec.createByCodecName(encoderName)
            bufferInfo = MediaCodec.BufferInfo()
            
            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, framerate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }
            
            codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec?.start()
            
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                @Suppress("DEPRECATION")
                inputBuffers = codec?.inputBuffers
                outputBuffers = codec?.outputBuffers
            }
            
            running = true
            AppLog.i(TAG, "H.265 encoder initialized successfully")
            return true
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to initialize H.265 encoder", e)
            release()
            return false
        }
    }
    
    /**
     * Find any available H.265 encoder
     */
    private fun findAnyEncoder(mimeType: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val infos = codecList.codecInfos
            .filter { it.isEncoder }
            .filter { it.supportedTypes.any { t -> t.equals(mimeType, true) } }
        
        // Prefer hardware encoder
        val hwEncoder = infos.find { !isSoftwareCodec(it.name) }
        return hwEncoder?.name ?: infos.firstOrNull()?.name
    }
    
    /**
     * Encode a frame from the input surface
     * Returns encoded H.265 data or null if not ready
     */
    fun encodeFrame(): ByteArray? {
        if (!running || codec == null) return null
        
        try {
            // Get output buffer with encoded data
            val outputIndex = codec?.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            
            if (outputIndex!! >= 0) {
                val outputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    codec?.getOutputBuffer(outputIndex)
                } else {
                    @Suppress("DEPRECATION")
                    outputBuffers?.get(outputIndex)
                }
                
                if (outputBuffer != null && bufferInfo!!.size > 0) {
                    val data = ByteArray(bufferInfo!!.size)
                    outputBuffer.get(data)
                    
                    // Release the buffer
                    codec?.releaseOutputBuffer(outputIndex, false)
                    
                    // Check if this is a config frame (SPS/PPS)
                    val isConfigFrame = (bufferInfo!!.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isKeyFrame = (bufferInfo!!.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    
                    if (isConfigFrame) {
                        AppLog.d(TAG, "Received codec config frame (${bufferInfo!!.size} bytes)")
                    } else if (isKeyFrame) {
                        AppLog.d(TAG, "Received key frame (${bufferInfo!!.size} bytes)")
                    }
                    
                    return data
                }
                
                codec?.releaseOutputBuffer(outputIndex, false)
            }
            
        } catch (e: Exception) {
            AppLog.e(TAG, "Error during encoding", e)
        }
        
        return null
    }
    
    /**
     * Get the input surface for encoding
     */
    fun getInputSurface(): Surface? {
        return try {
            codec?.createInputSurface()
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to create input surface", e)
            null
        }
    }
    
    /**
     * Signal end of stream
     */
    fun signalEndOfStream() {
        try {
            codec?.signalEndOfInputStream()
        } catch (e: Exception) {
            AppLog.e(TAG, "Error signaling EOS", e)
        }
    }
    
    /**
     * Release encoder resources
     */
    fun release() {
        try {
            running = false
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            AppLog.e(TAG, "Error releasing encoder", e)
        } finally {
            codec = null
            inputBuffers = null
            outputBuffers = null
            bufferInfo = null
        }
    }
    
    /**
     * Check if encoder is running
     */
    fun isRunning(): Boolean = running
}
