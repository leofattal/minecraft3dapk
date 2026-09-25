package com.leofattal.mcweaver

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders the weaver output: takes the captured mono frame plus the MiDaS
 * depth map and synthesizes a side-by-side stereo pair via depth-image-based
 * rendering (DIBR), drawn straight into the CNSDK's video SurfaceTexture,
 * which the InterlacedSurfaceView weaves onto the lightfield panel.
 */
class StereoRenderer(
    context: Context,
    private val outputTexture: SurfaceTexture,
    private val captureW: Int,
    private val captureH: Int,
) {
    /** The CNSDK video surface this renderer draws into (read-only, used to
     *  detect surface replacement across stop/start cycles). */
    val surface: SurfaceTexture get() = outputTexture
    companion object {
        private const val TAG = "StereoRenderer"
        const val VIEWS = 2
        private const val DEPTH_EVERY = 2   // depth update every Nth frame
        private const val MSG_INIT = 1
        private const val MSG_FRAME = 2
        private const val MSG_DEPTH = 3
        private const val MSG_RELEASE = 4
    }

    // UI-tunable parameters
    @Volatile var baseline = 0.02f       // max parallax (UV units)
    @Volatile var convergence = 0.5f     // normalized depth placed at screen plane
    @Volatile var swapEyes = false
    @Volatile var flipY = false           // vertical flip (toggle live if upside down)
    @Volatile var safeArea = 0f         // letterbox fraction (0 = fill screen)

    private val appContext = context.applicationContext
    private val sbsW = captureW * VIEWS
    private val sbsH = captureH

    private val frameLock = Any()
    private var pendingFrame: ByteBuffer? = null
    private var pendingIndex = -1
    private var pendingW = 0
    private var pendingH = 0

    // Capture-frame pool: fixed ~4 MB direct buffers handed from the
    // ImageReader thread to the render thread. Allocating a fresh buffer
    // per frame (plus a 256 KB depth copy) churned the heap every frame
    // and stuttered or killed long sessions. frameBusy[i] is set while a
    // buffer is being filled, pending, or uploaded; when the pool is
    // exhausted the incoming frame is dropped — the latest-frame-only
    // policy already prefers dropping over queueing.
    private val framePool = Array(3) {
        ByteBuffer.allocateDirect(captureW * captureH * 4).order(ByteOrder.nativeOrder())
    }
    private val frameBusy = BooleanArray(3)
    private val rowScratch = ByteArray(captureW * 4)

    // Two rotating depth readback buffers: one holds the pixels being
    // inferred on the depth thread, the other the pixels being read back
    // (depthBusy guarantees at most one inference is in flight).
    private val depthBufs = Array(2) {
        ByteBuffer.allocateDirect(DepthEngine.SIZE * DepthEngine.SIZE * 4)
            .order(ByteOrder.nativeOrder())
    }
    private var depthFlip = 0

    private lateinit var renderThread: HandlerThread
    private lateinit var renderHandler: Handler
    private lateinit var depthThread: HandlerThread
    private lateinit var depthHandler: Handler

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var blitProgram = 0
    private var vbo = 0
    private var frameTex = 0
    private var depthTex = 0
    private var frameW = 0
    private var frameH = 0
    private var downscaleFbo = 0
    private var downscaleTex = 0

    private var depth: DepthEngine? = null
    @Volatile private var depthBusy = false
    private var frameCounter = 0
    private var frameCount = 0L
    private var fpsStart = 0L

    fun start() {
        outputTexture.setDefaultBufferSize(sbsW, sbsH)
        depthThread = HandlerThread("weaver-depth").apply { start() }
        depthHandler = Handler(depthThread.looper) {
            val rgba = it.obj as ByteBuffer
            val d = try { depth?.infer(rgba) } catch (t: Throwable) { null }
            if (d != null) {
                renderHandler.sendMessage(renderHandler.obtainMessage(MSG_DEPTH, d))
            } else {
                depthBusy = false
            }
            true
        }
        renderThread = HandlerThread("weaver-render").apply { start() }
        renderHandler = Handler(renderThread.looper) {
            when (it.what) {
                MSG_INIT -> initGl()
                MSG_FRAME -> drawFrame()
                MSG_DEPTH -> {
                    uploadDepth(it.obj as java.nio.FloatBuffer)
                    depthBusy = false
                }
                MSG_RELEASE -> releaseGl()
            }
            true
        }
        renderHandler.sendEmptyMessage(MSG_INIT)
    }

    /** Called from the ImageReader thread; keeps the latest frame only.
     * Copies into a pooled buffer (no per-frame allocation); drops the
     * frame when every pooled buffer is still handed off. */
    fun pushFrame(src: ByteBuffer, width: Int, height: Int, rowStride: Int) {
        if (width * height * 4 > framePool[0].capacity()) {
            Log.w(TAG, "frame ${width}x${height} exceeds pool size; dropped")
            return
        }
        val idx = synchronized(frameLock) {
            var free = -1
            for (i in frameBusy.indices) if (!frameBusy[i]) { free = i; break }
            if (free < 0) return           // render thread behind; drop frame
            frameBusy[free] = true
            free
        }
        val packed = framePool[idx]
        packed.clear()
        val rowBytes = width * 4
        if (rowStride == rowBytes) {
            src.rewind()
            packed.put(src)
        } else {
            val row = if (rowBytes <= rowScratch.size) rowScratch else ByteArray(rowBytes)
            for (y in 0 until height) {
                src.position(y * rowStride)
                src.get(row, 0, rowBytes)
                packed.put(row, 0, rowBytes)
            }
        }
        packed.rewind()
        synchronized(frameLock) {
            // A previous frame never got rendered; hand its buffer back.
            if (pendingIndex >= 0) frameBusy[pendingIndex] = false
            pendingFrame = packed
            pendingIndex = idx
            pendingW = width
            pendingH = height
        }
        renderHandler.removeMessages(MSG_FRAME)
        renderHandler.sendEmptyMessage(MSG_FRAME)
    }

    fun release() {
        if (::renderHandler.isInitialized) renderHandler.sendEmptyMessage(MSG_RELEASE)
    }

    // ---------------------------------------------------------------- GL

    private fun initGl() {
        // EGL context rendering into the CNSDK video surface
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1))
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0],
            Surface(outputTexture), intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        blitProgram = buildProgram(BLIT_VS, BLIT_FS)

        val vb = IntArray(1)
        GLES30.glGenBuffers(1, vb, 0)
        vbo = vb[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val quad = ByteBuffer.allocateDirect(QUAD.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        quad.put(QUAD).rewind()
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, QUAD.size * 4, quad, GLES30.GL_STATIC_DRAW)

        frameTex = makeTexture()
        depthTex = makeTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTex)
        // flat mid-depth placeholder until the network warms up. GL_R8 (not
        // float) so LINEAR filtering is guaranteed in core ES3.
        val flat = ByteBuffer.allocateDirect(DepthEngine.SIZE * DepthEngine.SIZE)
            .order(ByteOrder.nativeOrder())
        repeat(DepthEngine.SIZE * DepthEngine.SIZE) { flat.put(128.toByte()) }
        flat.rewind()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8,
            DepthEngine.SIZE, DepthEngine.SIZE, 0, GLES30.GL_RED,
            GLES30.GL_UNSIGNED_BYTE, flat)

        downscaleTex = makeTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, downscaleTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
            DepthEngine.SIZE, DepthEngine.SIZE, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        downscaleFbo = fbo[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, downscaleFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, downscaleTex, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) ==
            GLES30.GL_FRAMEBUFFER_COMPLETE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        depth = try {
            DepthEngine(appContext)
        } catch (t: Throwable) {
            Log.e(TAG, "depth engine unavailable: ${t.message}")
            null
        }
        fpsStart = System.nanoTime()
        Log.i(TAG, "GL ready: capture ${captureW}x$captureH, sbs ${sbsW}x$sbsH")
    }

    private fun makeTexture(): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun drawFrame() {
        try {
            drawFrameInner()
        } catch (t: Throwable) {
            Log.e(TAG, "drawFrame: ${t.message}", t)
        }
    }

    private fun drawFrameInner() {
        val buf: ByteBuffer
        val w: Int
        val h: Int
        val idx: Int
        synchronized(frameLock) {
            val p = pendingFrame ?: return
            buf = p
            w = pendingW
            h = pendingH
            idx = pendingIndex
            pendingFrame = null
            pendingIndex = -1
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex)
        if (w != frameW || h != frameH) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            frameW = w
            frameH = h
        } else {
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        }
        // Client-pointer GL uploads consume the data before returning, so
        // the buffer can go straight back to the pool.
        synchronized(frameLock) { frameBusy[idx] = false }

        // depth input downscale + throttled readback
        runBlit(frameTex, downscaleFbo, DepthEngine.SIZE, DepthEngine.SIZE, blitProgram)
        if (depth != null && !depthBusy && frameCounter % DEPTH_EVERY == 0) {
            dispatchDepth()
        }
        frameCounter++

        // DIBR pass into the SBS output
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, sbsW, sbsH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uFrame"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uDepth"), 1)
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(program, "uBaseline"), baseline)
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(program, "uConvergence"), convergence)
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(program, "uSwap"), if (swapEyes) 1f else 0f)
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(program, "uFlipY"), if (flipY) 1f else 0f)
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(program, "uSafe"), safeArea)

        bindQuad(program)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, System.nanoTime())
        check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { "swapBuffers" }

        if (++frameCount % 90L == 0L) {
            val now = System.nanoTime()
            val fps = 90.0 / ((now - fpsStart) / 1e9)
            fpsStart = now
            Log.i(TAG, "render fps=%.1f depthBusy=%b".format(fps, depthBusy))
        }
    }

    private fun dispatchDepth() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, downscaleFbo)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        val buf = depthBufs[depthFlip]
        depthFlip = 1 - depthFlip
        buf.rewind()
        GLES30.glReadPixels(0, 0, DepthEngine.SIZE, DepthEngine.SIZE,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        depthBusy = true
        depthHandler.removeMessages(0)
        depthHandler.sendMessage(depthHandler.obtainMessage(0, buf))
    }

    private val depthBytes = ByteArray(DepthEngine.SIZE * DepthEngine.SIZE)

    private fun uploadDepth(d: java.nio.FloatBuffer) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTex)
        d.rewind()
        for (i in depthBytes.indices) {
            depthBytes[i] = (d.get() * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0,
            DepthEngine.SIZE, DepthEngine.SIZE, GLES30.GL_RED,
            GLES30.GL_UNSIGNED_BYTE,
            ByteBuffer.wrap(depthBytes))
    }

    private fun runBlit(tex: Int, fbo: Int, w: Int, h: Int, prog: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(prog)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uFrame"), 0)
        bindQuad(prog)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun bindQuad(prog: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val aPos = GLES30.glGetAttribLocation(prog, "aPos")
        val aUv = GLES30.glGetAttribLocation(prog, "aUv")
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(aUv)
        GLES30.glVertexAttribPointer(aUv, 2, GLES30.GL_FLOAT, false, 16, 8)
    }

    private fun releaseGl() {
        try {
            depthThread.quitSafely()
            depthThread.join(2000)
        } catch (_: Throwable) {
        }
        try { depth?.close() } catch (_: Throwable) {}
        depth = null
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "egl release: ${t.message}")
        }
        renderThread.quitSafely()
    }

    // ---------------------------------------------------------------- GLSL

    private val QUAD = floatArrayOf(
        -1f, 1f, 0f, 0f,
        -1f, -1f, 0f, 1f,
        1f, 1f, 1f, 0f,
        1f, -1f, 1f, 1f
    )

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val log = GLES30.glGetShaderInfoLog(s)
            if (!log.isNullOrBlank()) Log.e(TAG, "shader: $log")
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val log = GLES30.glGetProgramInfoLog(p)
        if (!log.isNullOrBlank()) Log.e(TAG, "program: $log")
        return p
    }

    private val VERTEX_SHADER = """
        #version 300 es
        in vec2 aPos;
        in vec2 aUv;
        out vec2 vUv;
        uniform float uFlipY;
        void main() {
            vUv = aUv;
            float y = mix(aPos.y, -aPos.y, uFlipY);
            gl_Position = vec4(aPos.x, y, 0.0, 1.0);
        }
    """.trimIndent()

    /**
     * Two-view side-by-side DIBR: each output tile is the captured frame
     * horizontally reprojected by (depth - convergence) * baseline, with the
     * two eyes offset in opposite directions. uSafe letterboxes the game
     * away from the panel edges (better touch accuracy + lightfield sweet
     * spot) instead of filling the screen.
     */
    private val FRAGMENT_SHADER = """
        #version 300 es
        precision highp float;
        in vec2 vUv;
        out vec4 frag;
        uniform sampler2D uFrame;
        uniform sampler2D uDepth;
        uniform float uBaseline;
        uniform float uConvergence;
        uniform float uSwap;
        uniform float uSafe;

        void main() {
            float tile = floor(vUv.x * 2.0);          // 0 = left tile, 1 = right
            float u = vUv.x * 2.0 - tile;             // 0..1 inside the tile
            float v = vUv.y;
            if (u < uSafe || u > 1.0 - uSafe || v < uSafe || v > 1.0 - uSafe) {
                frag = vec4(0.0, 0.0, 0.0, 1.0);      // letterbox margin
                return;
            }
            u = (u - uSafe) / (1.0 - 2.0 * uSafe);
            v = (v - uSafe) / (1.0 - 2.0 * uSafe);
            float eye = tile == 0.0 ? 1.0 : -1.0;    // left eye shifts right
            if (uSwap > 0.5) eye = -eye;
            // Depth at the OUTPUT pixel only describes where flat-scene
            // color came from; at object edges the fetched color actually
            // lives at a different depth, and using the output-pixel depth
            // smears foreground parallax onto the background. Re-sample the
            // depth at the shifted position a few times so the parallax
            // converges to the depth of the color that is actually fetched.
            float d = texture(uDepth, vec2(u, v)).r;
            float sx = u;
            for (int i = 0; i < 3; i++) {
                sx = clamp(u + eye * uBaseline * (d - uConvergence), 0.0, 1.0);
                d = texture(uDepth, vec2(sx, v)).r;
            }
            sx = clamp(u + eye * uBaseline * (d - uConvergence), 0.0, 1.0);
            vec3 color = texture(uFrame, vec2(sx, v)).rgb;
            frag = vec4(color, 1.0);
        }
    """.trimIndent()

    private val BLIT_VS = """
        #version 300 es
        in vec2 aPos;
        in vec2 aUv;
        out vec2 vUv;
        void main() { vUv = aUv; gl_Position = vec4(aPos, 0.0, 1.0); }
    """.trimIndent()

    private val BLIT_FS = """
        #version 300 es
        precision highp float;
        in vec2 vUv;
        out vec4 frag;
        uniform sampler2D uFrame;
        void main() { frag = texture(uFrame, vUv); }
    """.trimIndent()
}
