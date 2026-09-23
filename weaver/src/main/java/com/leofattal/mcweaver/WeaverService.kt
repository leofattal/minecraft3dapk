package com.leofattal.mcweaver

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager

/**
 * Whole-tablet 3D, no interference:
 *
 *  1. Capture the REAL screen with MediaProjection (one "Start now?" prompt
 *     per process) into an ImageReader we own.
 *  2. Every frame goes through: MiDaS depth (Hexagon NPU) -> DIBR stereo
 *     synthesis -> CNSDK lightfield weave on the real panel.
 *  3. The woven overlay is NOT_TOUCHABLE, so every touch, key, and nav button
 *     goes straight to the real apps beneath it — Minecraft, the on-screen
 *     keyboard, and the triangle/circle/square nav buttons all work exactly
 *     like normal, with zero forwarding.
 *
 * The overlay is FLAG_SECURE so the screen capture excludes the woven output
 * itself (no feedback loop). No overlay buttons at all: stop by closing the
 * pad (screen off) or from the notification.
 *
 * Output pipeline (CNSDK surface + EGL + DepthEngine) is created once and kept
 * alive across stop/start so restarts never black out. Only the capture side
 * (projection virtual display + ImageReader) is recreated per session.
 */
class WeaverService : Service() {
    companion object {
        private const val TAG = "WeaverService"
        private const val CHANNEL = "mcweaver"
        private const val NOTIF_ID = 43
        private const val ACTION_STOP = "com.leofattal.mcweaver.STOP"
        private const val MAX_CAPTURE_SIDE = 1280

        @Volatile var active: WeaverService? = null
            private set

        /** MediaProjection grant from the one-time consent prompt; reusable for
         *  this process's lifetime so we don't re-prompt on every start. */
        @Volatile var projectionGrant: Pair<Int, Intent>? = null

        fun startProjection(ctx: Context, resultCode: Int, data: Intent) {
            projectionGrant = resultCode to data
            ctx.startForegroundService(Intent(ctx, WeaverService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(
                Intent(ctx, WeaverService::class.java).setAction(ACTION_STOP))
        }
    }

    // Output pipeline — created once, kept alive for the process lifetime.
    private var overlay: Overlay3D? = null
    private var renderer: StereoRenderer? = null

    // Capture pipeline — recreated on each START, torn down on STOP.
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var readerThread: HandlerThread? = null

    private var captureW = 0
    private var captureH = 0
    private var captureDpi = 160
    private var weaving = false
    private var screenOffReceiver: BroadcastReceiver? = null

    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            hideSession()
            return START_NOT_STICKY
        }

        val grant = projectionGrant
        if (grant == null) {
            Log.e(TAG, "no MediaProjection grant (consent not given?)")
            stopSelf()
            return START_NOT_STICKY
        }

        active = this
        startInForeground()

        computeCaptureSize()

        if (!ensureOutputPipeline()) {
            shutdown()
            return START_NOT_STICKY
        }

        if (!startCapture(grant.first, grant.second)) {
            updateStatus("Could not start screen capture")
            shutdown()
            return START_NOT_STICKY
        }

        weaving = true
        main.post { overlay?.show() }
        registerScreenOff()

        updateStatus("Whole tablet in 3D — everything on screen is woven")
        return START_STICKY
    }

    /** Build the persistent output pipeline once (CNSDK overlay + renderer). */
    private fun ensureOutputPipeline(): Boolean {
        if (overlay != null) return true
        val ov = Overlay3D(
            this, captureW * StereoRenderer.VIEWS, captureH,
            onSurfaceReady = { st -> onStereoSurfaceReady(st) })
        if (!ov.init()) {
            Log.e(TAG, "overlay init failed (Leia services missing?)")
            return false
        }
        overlay = ov
        return true
    }

    /** The CNSDK video surface appeared (or re-appeared). Always (re)create the
     *  renderer bound to that exact surface — drawing to a stale surface is
     *  what used to black out the screen after a stop/start cycle. */
    private fun onStereoSurfaceReady(st: android.graphics.SurfaceTexture) {
        val existing = renderer
        if (existing != null && existing.surface === st) {
            Log.i(TAG, "stereo surface unchanged; renderer reused")
            return
        }
        existing?.release()
        val rnd = StereoRenderer(this, st, captureW, captureH)
        applyPrefs(rnd)
        rnd.start()
        renderer = rnd
        Log.i(TAG, "renderer (re)created on fresh CNSDK surface")
    }

    /** Build the capture side for a session: MediaProjection + virtual display
     *  mirroring the real screen into our ImageReader. */
    private fun startCapture(resultCode: Int, data: Intent): Boolean {
        teardownCapture()  // in case a previous session left one behind

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
        val mp = try {
            mgr.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection failed: ${t.message}")
            null
        }
        if (mp == null) {
            Log.e(TAG, "getMediaProjection returned null")
            return false
        }
        projection = mp

        val thread = HandlerThread("weaver-reader").apply { start() }
        readerThread = thread
        val imageReader = ImageReader.newInstance(
            captureW, captureH, PixelFormat.RGBA_8888, 3)
        imageReader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                renderer?.pushFrame(
                    plane.buffer, image.width, image.height, plane.rowStride)
            } catch (t: Throwable) {
                Log.w(TAG, "frame: ${t.message}")
            } finally {
                image.close()
            }
        }, Handler(thread.looper))
        reader = imageReader

        val vd = try {
            mp.createVirtualDisplay(
                "mcweaver-mirror", captureW, captureH, captureDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                imageReader.surface, null, null)
        } catch (t: Throwable) {
            Log.e(TAG, "createVirtualDisplay failed: ${t.message}")
            null
        }
        if (vd == null) {
            Log.e(TAG, "projection createVirtualDisplay failed")
            return false
        }
        display = vd

        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped by the system")
                main.post { hideSession() }
            }
        }, Handler(thread.looper))

        Log.i(TAG, "capture ready: mirror of real screen at ${captureW}x${captureH}")
        return true
    }

    /** Tear down only the capture side (virtual display + ImageReader). The
     *  output pipeline (renderer + EGL + DepthEngine + CNSDK) stays alive. */
    private fun teardownCapture() {
        try { display?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { readerThread?.quitSafely() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        display = null
        reader = null
        readerThread = null
        projection = null
    }

    /** Capture resolution: full physical panel scaled down to <=1280 on its
     *  long side. Using the REAL panel size (not the app window's bounds, which
     *  exclude system bars) keeps the capture aspect identical to the fullscreen
     *  stereo output — the mismatch was what stretched the picture ("shape
     *  broken") and sent taps to the wrong Y ("goes upwards"). */
    private fun computeCaptureSize() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fullW: Int
        val fullH: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.maximumWindowMetrics.bounds
            fullW = m.width(); fullH = m.height()
        } else {
            val p = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            fullW = p.x; fullH = p.y
        }
        val scale = minOf(1f, MAX_CAPTURE_SIDE.toFloat() / maxOf(fullW, fullH))
        captureW = (fullW * scale).toInt().let { it - it % 2 }
        captureH = (fullH * scale).toInt().let { it - it % 2 }
        captureDpi = maxOf(120, (resources.displayMetrics.densityDpi * scale).toInt())
        Log.i(TAG, "panel ${fullW}x${fullH} -> capture ${captureW}x${captureH} dpi=${captureDpi}")
    }

    // ------------------------------------------------------------------ screen off

    private fun registerScreenOff() {
        if (screenOffReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    Log.i(TAG, "screen off (pad closed) — stopping 3D")
                    hideSession()
                }
            }
        }
        screenOffReceiver = r
        try {
            registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_OFF))
        } catch (t: Throwable) {
            Log.w(TAG, "registerReceiver: ${t.message}")
        }
    }

    private fun unregisterScreenOff() {
        screenOffReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Throwable) {}
        }
        screenOffReceiver = null
    }

    // ------------------------------------------------------------------ prefs

    fun refreshParams() {
        renderer?.let { applyPrefs(it) }
    }

    private fun applyPrefs(rnd: StereoRenderer) {
        val prefs = getSharedPreferences("weaver", Context.MODE_PRIVATE)
        val depth = prefs.getFloat("depth", 40f) / 100f
        val conv = prefs.getFloat("convergence", 45f) / 100f
        val safe = prefs.getInt("safeArea", 0) / 100f
        rnd.baseline = 0.004f + depth * 0.026f
        rnd.convergence = conv
        rnd.swapEyes = prefs.getBoolean("swap", false)
        rnd.flipY = prefs.getBoolean("flip", false)
        rnd.safeArea = safe
    }

    // ------------------------------------------------------------------ lifecycle

    private fun startInForeground() {
        startNotification("Starting 3D…")
    }

    private fun updateStatus(text: String) {
        startNotification(text)
    }

    private fun startNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "MC 3D Weaver",
                    NotificationManager.IMPORTANCE_LOW))
        }
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, WeaverService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notif: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Minecraft 3D weaver")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /** STOP weaving: hide the overlay, tear down ONLY the capture side
     *  (projection virtual display + ImageReader). The output pipeline
     *  (renderer + EGL + DepthEngine + CNSDK) stays alive so the next START is
     *  instant and never blacks out. */
    private fun hideSession() {
        if (!weaving && active == null) return
        Log.i(TAG, "hideSession: hiding overlay, tearing down capture")
        weaving = false
        unregisterScreenOff()
        main.post { overlay?.hide() }
        Thread {
            teardownCapture()
            updateStatus("3D stopped — press START 3D to resume")
        }.start()
    }

    /** Full teardown: everything, used when the process is going away. */
    private fun shutdown() {
        weaving = false
        unregisterScreenOff()
        try { overlay?.hide() } catch (_: Throwable) {}
        try { renderer?.release() } catch (_: Throwable) {}
        try { overlay?.release() } catch (_: Throwable) {}
        renderer = null
        overlay = null
        Thread {
            teardownCapture()
            active = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
    }

    /** Swiping the app away in Recents = clean stop. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed from recents — shutting down cleanly")
        shutdown()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (active === this) shutdown()
        super.onDestroy()
    }
}
