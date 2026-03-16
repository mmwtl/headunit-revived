package com.andrerinas.headunitrevived.decoder

import android.opengl.GLES20
import android.view.Surface
import com.andrerinas.headunitrevived.utils.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/**
 * Renders YUV420P (I420) frames to an Android Surface using OpenGL ES 2.0.
 * Thread-safe: call [drawFrame] from any thread after [setSurface] on the same Surface.
 */
internal class YuvSurfaceRenderer {

    private var egl: EGL10? = null
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var surface: Surface? = null
    private var program = 0
    private var texY = 0
    private var texU = 0
    private var texV = 0
    private var width = 0
    private var height = 0
    private val lock = Any()

    private val vertexShader = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShader = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexY;
        uniform sampler2D uTexU;
        uniform sampler2D uTexV;
        void main() {
            float y = texture2D(uTexY, vTexCoord).r;
            float u = texture2D(uTexU, vTexCoord).r - 0.5;
            float v = texture2D(uTexV, vTexCoord).r - 0.5;
            float r = y + 1.402 * v;
            float g = y - 0.344 * u - 0.714 * v;
            float b = y + 1.772 * u;
            gl_FragColor = vec4(r, g, b, 1.0);
        }
    """.trimIndent()

    private val quadCoords = floatArrayOf(
        -1f, -1f, 0f, 0f, 1f,
         1f, -1f, 0f, 1f, 1f,
        -1f,  1f, 0f, 0f, 0f,
         1f,  1f, 0f, 1f, 0f
    )

    fun setSurface(surface: Surface?) {
        synchronized(lock) {
            releaseEgl()
            this.surface = surface
            if (surface != null && surface.isValid) {
                initEgl(surface)
            }
        }
    }

    private fun initEgl(surface: Surface) {
        val egl = EGLContext.getEGL() as EGL10
        eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL10.EGL_NO_DISPLAY) {
            AppLog.e("YuvSurfaceRenderer: eglGetDisplay failed")
            return
        }
        val version = IntArray(2)
        if (!egl.eglInitialize(eglDisplay, version)) {
            AppLog.e("YuvSurfaceRenderer: eglInitialize failed")
            return
        }
        val configAttribs = intArrayOf(
            EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
            EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)
        if (!egl.eglChooseConfig(eglDisplay, configAttribs, configs, 1, numConfig) || numConfig[0] == 0) {
            AppLog.e("YuvSurfaceRenderer: eglChooseConfig failed")
            return
        }
        val contextAttribs = intArrayOf(0x3098, 2, EGL10.EGL_NONE) // EGL_CONTEXT_CLIENT_VERSION, 2
        eglContext = egl.eglCreateContext(eglDisplay, configs[0], EGL10.EGL_NO_CONTEXT, contextAttribs)
        if (eglContext == null || eglContext == EGL10.EGL_NO_CONTEXT) {
            AppLog.e("YuvSurfaceRenderer: eglCreateContext failed")
            return
        }
        eglSurface = egl.eglCreateWindowSurface(eglDisplay, configs[0], surface, null)
        if (eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE) {
            AppLog.e("YuvSurfaceRenderer: eglCreateWindowSurface failed")
            return
        }
        if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            AppLog.e("YuvSurfaceRenderer: eglMakeCurrent failed")
            return
        }
        this.egl = egl
        this.eglDisplay = eglDisplay
        this.eglContext = eglContext
        this.eglSurface = eglSurface
        initGl()
    }

    private fun initGl() {
        val vsh = loadShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fsh = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vsh)
        GLES20.glAttachShader(program, fsh)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            AppLog.e("YuvSurfaceRenderer: program link failed: " + GLES20.glGetProgramInfoLog(program))
            return
        }
        texY = createTexture()
        texU = createTexture()
        texV = createTexture()
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            AppLog.e("YuvSurfaceRenderer: shader compile failed: " + GLES20.glGetShaderInfoLog(shader))
            return 0
        }
        return shader
    }

    private fun createTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    /**
     * Draw YUV420P frame. yuvBuffer layout: Y plane (width*height), U plane (width*height/4), V plane (width*height/4).
     */
    fun drawFrame(width: Int, height: Int, yuvBuffer: ByteArray) {
        synchronized(lock) {
            if (egl == null || eglSurface == null || program == 0 || width <= 0 || height <= 0) return
            val ySize = width * height
            val uvSize = (width / 2) * (height / 2)
            if (yuvBuffer.size < ySize + uvSize * 2) return
            this.width = width
            this.height = height
            GLES20.glUseProgram(program)
            val aPos = GLES20.glGetAttribLocation(program, "aPosition")
            val aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
            val uTexY = GLES20.glGetUniformLocation(program, "uTexY")
            val uTexU = GLES20.glGetUniformLocation(program, "uTexU")
            val uTexV = GLES20.glGetUniformLocation(program, "uTexV")
            val vb = ByteBuffer.allocateDirect(quadCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vb.put(quadCoords)
            vb.flip()
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, vb)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 20, vb)
            vb.position(3)
            GLES20.glEnableVertexAttribArray(aTex)
            uploadPlane(texY, width, height, yuvBuffer, 0, width)
            uploadPlane(texU, width / 2, height / 2, yuvBuffer, ySize, width / 2)
            uploadPlane(texV, width / 2, height / 2, yuvBuffer, ySize + uvSize, width / 2)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texY)
            GLES20.glUniform1i(uTexY, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texU)
            GLES20.glUniform1i(uTexU, 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texV)
            GLES20.glUniform1i(uTexV, 2)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            egl?.eglSwapBuffers(eglDisplay, eglSurface)
        }
    }

    private fun uploadPlane(texId: Int, w: Int, h: Int, data: ByteArray, offset: Int, stride: Int) {
        val buf = ByteBuffer.allocateDirect(w * h)
        if (stride == w) {
            buf.put(data, offset, w * h)
        } else {
            for (row in 0 until h) {
                buf.put(data, offset + row * stride, w)
            }
        }
        buf.flip()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, w, h, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, buf)
    }

    private fun releaseEgl() {
        val egl = this.egl ?: return
        egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
        eglSurface?.let { egl.eglDestroySurface(eglDisplay, it) }
        eglContext?.let { egl.eglDestroyContext(eglDisplay, it) }
        egl.eglTerminate(eglDisplay)
        eglSurface = null
        eglContext = null
        eglDisplay = null
        this.egl = null
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        val texIds = intArrayOf(texY, texU, texV)
        if (texIds.any { it != 0 }) GLES20.glDeleteTextures(3, texIds, 0)
        texY = 0
        texU = 0
        texV = 0
    }

    fun release() {
        synchronized(lock) {
            releaseEgl()
            surface = null
        }
    }
}
