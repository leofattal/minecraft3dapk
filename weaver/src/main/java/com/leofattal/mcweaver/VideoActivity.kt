package com.leofattal.mcweaver

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Toast
import com.leia.sdk.views.InputViewsAsset
import com.leia.sdk.views.InterlacedSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A true-3D local video player. The weaver cannot capture DRM streams,
 * but locally decoded files have no such wall — so this activity decodes
 * the video itself (MediaPlayer → SurfaceTexture) and weaves it:
 *
 *  - SBS and top-bottom 3D files play as REAL stereo — each eye is woven
 *    straight from its own half of the frame, no AI depth;
 *  - ordinary 2D files get the MiDaS+DIBR treatment (AI depth);
 *  - the mode is auto-guessed from the file's aspect and changeable live
 *    while playing.
 *
 * Audio stays in sync (the player owns decoding; no capture pipeline).
 * A small pbuffer GL thread letterboxes/rotates the video into a
 * capture-sized RGBA buffer and feeds [StereoRenderer.pushFrame], which
 * does the weaving into the CNSDK interlacer exactly as in the weaver.
 */
class VideoActivity : Activity() {
    companion object {
        private const val TAG = "VideoActivity"
        private const val MSG_FRAME = 1
        private const val MSG_GL_RELEASE = 2

        private val MODE_LABELS = listOf(
            "2D → 3D (AI depth)", "SBS 3D (side-by-side)",
            "Top-Bottom 3D", "Flat 2D")

        private fun modeFor(pos: Int): Int = when (pos) {
            1 -> StereoRenderer.MODE_SBS
            2 -> StereoRenderer.MODE_TB
            3 -> StereoRenderer.MODE_FLAT
            else -> StereoRenderer.MODE_DIBR
        }
    }

    private var uri: Uri? = null
    private var player: MediaPlayer? = null
    private var renderer: StereoRenderer? = null
    private var stereoView: InterlacedSurfaceView? = null
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null
    private var glReady = false

    private var captureW = 0
    private var captureH = 0
    private var videoW = 0
    private var videoH = 0
    private var videoRot = 0
    private var durationMs = 0

    // GL-thread-owned state
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var videoTex = 0
    private var videoFbo = 0
    private var videoFboTex = 0
    private var vbo = 0
    private var oesProgram = 0
    private var videoSt: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private val texMat = FloatArray(16)
    private var readBuf: ByteBuffer? = null
    private var uvRect = FloatArray(4)

    // UI
    private lateinit var controls: LinearLayout
    private lateinit var playButton: Button
    private lateinit var seekBar: SeekBar
    private lateinit var modeSpinner: Spinner
    private lateinit var swapBox: CheckBox
    private lateinit var flipBox: CheckBox
    private var trackingSeek = false
    private val ui = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!trackingSeek) seekBar.progress = p.currentPosition / 1000
            if (p.isPlaying) ui.postDelayed(this, 500)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        uri = intent?.data
        if (uri == null) {
            toast("No video selected")
            finish()
            return
        }
        if (!probeVideo()) return
        if (Overlay3D.sdk(this) == null) {
            toast("Leia services missing — 3D playback unavailable")
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 7)
        }
        computeCaptureSize()
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        try { stereoView?.onResume() } catch (_: Throwable) {}
        try { Overlay3D.sdk(this)?.enableBacklight(true) } catch (_: Throwable) {}
    }

    override fun onPause() {
        try { player?.pause(); playButton.text = "Play" } catch (_: Throwable) {}
        try { stereoView?.onPause() } catch (_: Throwable) {}
        try { Overlay3D.sdk(this)?.enableBacklight(false) } catch (_: Throwable) {}
        super.onPause()
    }

    override fun onDestroy() {
        try { player?.release() } catch (_: Throwable) {}
        player = null
        glHandler?.sendEmptyMessage(MSG_GL_RELEASE)
        renderer?.release()
        try { stereoView?.softClose() } catch (_: Throwable) {}
        super.onDestroy()
    }

    // ---------------------------------------------------------------- setup

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    /** Read the track's size / rotation / duration; finishes on failure. */
    private fun probeVideo(): Boolean = try {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(this, uri)
            videoW = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            videoH = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            videoRot = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            durationMs = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.toInt() ?: 0
            if (videoW <= 0 || videoH <= 0) {
                toast("Unplayable video")
                finish()
                false
            } else true
        } finally {
            mmr.release()
        }
    } catch (t: Throwable) {
        toast("Cannot open video: ${t.message}")
        finish()
        false
    }

    /** Frame size mirrors the panel aspect (like the weaver) so the weave
     *  fills the screen; the video is letterboxed inside it by the GL pass. */
    private fun computeCaptureSize() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as
                android.hardware.display.DisplayManager
        val p = android.graphics.Point()
        dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.getRealSize(p)
        val scale = minOf(1f, 1280f / maxOf(p.x, p.y))
        captureW = (p.x * scale).toInt().let { it - it % 2 }
        captureH = (p.y * scale).toInt().let { it - it % 2 }
        readBuf = ByteBuffer.allocateDirect(captureW * captureH * 4)
            .order(ByteOrder.nativeOrder())
        uvRect = VideoFit.uvRect(
            VideoFit.effectiveAspect(videoW, videoH, videoRot),
            captureW.toFloat() / captureH)
    }

    private fun buildUi(): View {
        val root = FrameLayout(this)
        val view = try {
            InterlacedSurfaceView(this).also { v ->
                val asset = InputViewsAsset()
                asset.setHorizontalViewsLayout()
                asset.CreateEmptySurfaceForVideo(
                    captureW * StereoRenderer.VIEWS, captureH
                ) { st -> onStereoReady(st) }
                v.setViewAsset(asset)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "InterlacedSurfaceView failed: ${t.message}", t)
            null
        }
        if (view == null) {
            toast("3D view unavailable (Leia services?)")
            finish()
            return root
        }
        stereoView = view
        root.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Tap anywhere to show/hide the controls.
        val tap = View(this)
        tap.setOnClickListener {
            controls.visibility =
                if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        root.addView(tap, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.addView(buildControls(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        return root
    }

    private fun buildControls(): LinearLayout {
        val pad = (resources.displayMetrics.density * 12).toInt()
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val row1 = LinearLayout(this)
        playButton = Button(this).apply {
            text = "Pause"
            setOnClickListener { togglePlay() }
        }
        seekBar = SeekBar(this).apply {
            max = maxOf(durationMs / 1000, 1)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) player?.seekTo(v * 1000)
                }
                override fun onStartTrackingTouch(s: SeekBar?) { trackingSeek = true }
                override fun onStopTrackingTouch(s: SeekBar?) { trackingSeek = false }
            })
        }
        row1.addView(playButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row1.addView(seekBar, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(row1)

        val row2 = LinearLayout(this)
        val guess = VideoFit.guessMode(
            VideoFit.effectiveAspect(videoW, videoH, videoRot))
        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@VideoActivity,
                android.R.layout.simple_spinner_item, MODE_LABELS).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(when (guess) {
                StereoRenderer.MODE_SBS -> 1
                StereoRenderer.MODE_TB -> 2
                else -> 0
            })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    renderer?.mediaMode = modeFor(pos)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        row2.addView(modeSpinner, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        swapBox = CheckBox(this).apply {
            text = "Swap eyes"
            setOnCheckedChangeListener { _, c -> renderer?.swapEyes = c }
        }
        flipBox = CheckBox(this).apply {
            text = "Flip"
            setOnCheckedChangeListener { _, c -> renderer?.flipY = c }
        }
        row2.addView(swapBox)
        row2.addView(flipBox)
        controls.addView(row2)
        return controls
    }

    // ---------------------------------------------------------------- playback

    private fun togglePlay() {
        val p = player ?: return
        try {
            if (p.isPlaying) {
                p.pause()
                playButton.text = "Play"
            } else {
                p.start()
                playButton.text = "Pause"
                ui.post(tick)
            }
        } catch (_: Throwable) {}
    }

    private fun createPlayer() {
        val surface = videoSurface ?: return
        val videoUri = uri ?: return
        val p = MediaPlayer()
        try {
            p.setSurface(surface)
            p.setDataSource(this, videoUri)
            p.setOnPreparedListener {
                it.start()
                playButton.text = "Pause"
                ui.post(tick)
            }
            p.setOnCompletionListener { playButton.text = "Play" }
            p.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error $what/$extra")
                runOnUiThread {
                    toast("Playback error $what/$extra")
                    finish()
                }
                true
            }
            p.prepareAsync()
            player = p
        } catch (t: Throwable) {
            try { p.release() } catch (_: Throwable) {}
            toast("Cannot play: ${t.message}")
            finish()
        }
    }

    private fun onStereoReady(st: SurfaceTexture) {
        Log.i(TAG, "CNSDK video surface ready ${captureW}x$captureH")
        val prefs = getSharedPreferences("weaver", Context.MODE_PRIVATE)
        val depth = prefs.getFloat("depth", 40f) / 100f
        val conv = prefs.getFloat("convergence", 45f) / 100f
        val rnd = StereoRenderer(this, st, captureW, captureH)
        rnd.baseline = 0.004f + depth * 0.026f
        rnd.convergence = conv
        rnd.mediaMode = VideoFit.guessMode(
            VideoFit.effectiveAspect(videoW, videoH, videoRot))
        rnd.start()
        renderer = rnd
        startGl()
    }

    // ---------------------------------------------------------------- video GL

    private fun startGl() {
        val t = HandlerThread("video-gl").apply { start() }
        glThread = t
        glHandler = Handler(t.looper) { msg ->
            when (msg.what) {
                MSG_FRAME -> drawVideoFrame()
                MSG_GL_RELEASE -> releaseVideoGl()
            }
            true
        }
        glHandler?.post { initVideoGl() }
    }

    private fun initVideoGl() {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(eglDisplay != EGL14.EGL_NO_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1))
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, 0x0040 /* EGL_OPENGL_ES3_BIT */,
                EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0)
                && num[0] > 0)
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(eglContext != EGL14.EGL_NO_CONTEXT)
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))

            oesProgram = buildProgram(OES_VS, OES_FS)
            val vb = IntArray(1)
            GLES30.glGenBuffers(1, vb, 0)
            vbo = vb[0]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            val quad = ByteBuffer.allocateDirect(QUAD.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            quad.put(QUAD).rewind()
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, QUAD.size * 4, quad,
                GLES30.GL_STATIC_DRAW)

            // External-OES texture the decoder renders into.
            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            videoTex = t[0]
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            val st = SurfaceTexture(videoTex)
            st.setDefaultBufferSize(videoW, videoH)
            st.setOnFrameAvailableListener(
                { glHandler?.sendEmptyMessage(MSG_FRAME) }, glHandler)
            videoSt = st
            videoSurface = Surface(st)

            // Capture-sized RGBA target the frame is read back from.
            val fbt = IntArray(1)
            GLES30.glGenTextures(1, fbt, 0)
            videoFboTex = fbt[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, videoFboTex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
                captureW, captureH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            val fb = IntArray(1)
            GLES30.glGenFramebuffers(1, fb, 0)
            videoFbo = fb[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, videoFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, videoFboTex, 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) ==
                GLES30.GL_FRAMEBUFFER_COMPLETE)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

            glReady = true
            runOnUiThread { createPlayer() }
        } catch (t: Throwable) {
            Log.e(TAG, "video GL init failed: ${t.message}", t)
            runOnUiThread {
                toast("3D pipeline failed: ${t.message}")
                finish()
            }
        }
    }

    private fun drawVideoFrame() {
        val st = videoSt ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMat)

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, videoFbo)
            GLES30.glViewport(0, 0, captureW, captureH)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            GLES30.glUseProgram(oesProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(oesProgram, "uTex"), 0)
            GLES30.glUniformMatrix4fv(
                GLES30.glGetUniformLocation(oesProgram, "uXf"), 1, false, texMat, 0)
            GLES30.glUniform4fv(
                GLES30.glGetUniformLocation(oesProgram, "uRect"), 1, uvRect, 0)
            GLES30.glUniform1i(
                GLES30.glGetUniformLocation(oesProgram, "uRot"), videoRot)

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            val aPos = GLES30.glGetAttribLocation(oesProgram, "aPos")
            val aUv = GLES30.glGetAttribLocation(oesProgram, "aUv")
            GLES30.glEnableVertexAttribArray(aPos)
            GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 16, 0)
            GLES30.glEnableVertexAttribArray(aUv)
            GLES30.glVertexAttribPointer(aUv, 2, GLES30.GL_FLOAT, false, 16, 8)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            val buf = readBuf!!
            buf.rewind()
            GLES30.glReadPixels(0, 0, captureW, captureH,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            buf.rewind()
            renderer?.pushFrame(buf, captureW, captureH, captureW * 4)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "drawVideoFrame: ${t.message}")
        }
    }

    private fun releaseVideoGl() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "video GL release: ${t.message}")
        }
        glThread?.quitSafely()
    }

    // ---------------------------------------------------------------- GL helpers

    private val QUAD = floatArrayOf(
        -1f, 1f, 0f, 0f,
        -1f, -1f, 0f, 1f,
        1f, 1f, 1f, 0f,
        1f, -1f, 1f, 1f)

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

    /**
     * Draws the decoder's external texture through the letterbox rect and
     * the video's rotation metadata. The quad is raster-flipped (vertex
     * shader) so the readback buffer's row 0 is the video's top row — the
     * same top-down convention the weaver's ImageReader capture uses.
     */
    private val OES_VS = """
        #version 300 es
        in vec2 aPos;
        in vec2 aUv;
        out vec2 vUv;
        void main() {
            vUv = aUv;
            gl_Position = vec4(aPos.x, -aPos.y, 0.0, 1.0);
        }
    """.trimIndent()

    private val OES_FS = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;
        in vec2 vUv;
        out vec4 frag;
        uniform samplerExternalOES uTex;
        uniform mat4 uXf;
        uniform vec4 uRect;
        uniform int uRot;
        void main() {
            vec2 uv = mix(uRect.xy, uRect.zw, vUv);
            vec2 st;
            if (uRot == 90) st = vec2(1.0 - uv.y, uv.x);
            else if (uRot == 180) st = vec2(1.0 - uv.x, 1.0 - uv.y);
            else if (uRot == 270) st = vec2(uv.y, 1.0 - uv.x);
            else st = uv;
            frag = texture(uTex, (uXf * vec4(st, 0.0, 1.0)).xy);
        }
    """.trimIndent()
}
