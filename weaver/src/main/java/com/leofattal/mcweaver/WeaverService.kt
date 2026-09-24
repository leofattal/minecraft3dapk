package com.leofattal.mcweaver

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
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager

/**
 * Whole-tablet 3D on a hidden trusted virtual display:
 *
 *  1. creates a TRUSTED, system-decorated virtual display via Shizuku/shell —
 *     the 3D desktop, with its own nav bar, that everything runs on;
 *  2. launches the Android home screen there, so any app opened from the 3D
 *     home runs in 3D (games, browser, videos);
 *  3. captures the display into an ImageReader, runs MiDaS depth on the
 *     Hexagon NPU, synthesizes the stereo pair (DIBR) and weaves it onto the
 *     lightfield panel via the CNSDK interlacer with face tracking;
 *  4. forwards every touch — all pointers, so two-thumb play, buttons, and
 *     the keyboard (IME policy LOCAL renders it on the virtual display) work
 *     natively in 3D.
 *
 * No on-screen buttons: stop by closing the pad (screen off) or from the
 * notification. BACK from the hardware key is forwarded in. The output
 * pipeline (CNSDK surface + EGL + DepthEngine) is created once and kept
 * alive across stop/start so restarts never black out.
 */
class WeaverService : Service() {
    companion object {
        private const val TAG = "WeaverService"
        private const val CHANNEL = "mcweaver"
        private const val NOTIF_ID = 43
        private const val ACTION_STOP = "com.leofattal.mcweaver.STOP"
        private const val EXTRA_KEYCODE = "com.leofattal.mcweaver.KEYCODE"
        private const val EXTRA_PKG = "com.leofattal.mcweaver.PKG"
        private const val MAX_CAPTURE_SIDE = 1280

        /** Minecraft Bedrock has shipped under two package ids. */
        val MINECRAFT_CANDIDATES = listOf(
            "com.mojang.minecraftpe", "com.mojang.minecraft")

        fun minecraftPackage(pm: android.content.pm.PackageManager): String? =
            MINECRAFT_CANDIDATES.firstOrNull { pm.getLaunchIntentForPackage(it) != null }

        /** The live service instance (non-null while the service exists at all). */
        @Volatile var active: WeaverService? = null
            private set

        /**
         * True only while a 3D session is actually weaving. This is distinct
         * from [active]: STOP, screen-off, or a failed start leave the service
         * alive but the session stopped, and the UI must then offer START
         * again — keying the button off [active] made the app show STOP for a
         * dead session and refuse to ever restart.
         */
        @Volatile var running = false
            private set

        fun start(ctx: Context, pkg: String? = null) {
            ctx.startForegroundService(Intent(ctx, WeaverService::class.java)
                .putExtra(EXTRA_PKG, pkg))
        }

        fun stop(ctx: Context) {
            ctx.startService(
                Intent(ctx, WeaverService::class.java).setAction(ACTION_STOP))
        }

        /** Forward a hardware key (e.g. BACK) into the 3D desktop. */
        fun forwardKey(ctx: Context, keyCode: Int) {
            ctx.startService(Intent(ctx, WeaverService::class.java)
                .setAction("com.leofattal.mcweaver.KEY")
                .putExtra(EXTRA_KEYCODE, keyCode))
        }
    }

    // Output pipeline — created once, kept alive for the process lifetime.
    private var overlay: Overlay3D? = null
    private var renderer: StereoRenderer? = null

    // Capture pipeline — recreated on each START, torn down on STOP.
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var readerThread: HandlerThread? = null

    private var displayId = -1
    private var captureW = 0
    private var captureH = 0
    private var captureDpi = 160
    private var weaving = false
    private var screenOffReceiver: BroadcastReceiver? = null

    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                hideSession()
                return START_NOT_STICKY
            }
            "com.leofattal.mcweaver.KEY" -> {
                val code = intent.getIntExtra(EXTRA_KEYCODE, -1)
                if (code > 0 && displayId >= 0) {
                    Thread { ShellPriv.injectKey(code, displayId) }.start()
                }
                return START_STICKY
            }
        }

        if (!ShellPriv.isShizukuReady()) {
            Log.e(TAG, "Shizuku not running/authorized")
            updateStatus("Shizuku not ready — start Shizuku, then press START 3D")
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

        if (!startCapture()) {
            updateStatus("Virtual display creation failed")
            shutdown()
            return START_NOT_STICKY
        }

        // Launch the chosen app onto the virtual display by explicit component.
        // (A HOME-category start silently no-ops: it just brings the existing
        // home task forward on display 0, leaving the virtual display black.)
        val targetPkg = intent?.getStringExtra(EXTRA_PKG)
            ?: minecraftPackage(packageManager)
        Thread {
            val ok = targetPkg != null && ShellPriv.launchOnDisplay(targetPkg, displayId)
            Log.i(TAG, "launch $targetPkg on display $displayId -> $ok")
            val imeOk = ShellPriv.setImeLocal(displayId)
            Log.i(TAG, "ime local on display $displayId -> $imeOk")
            Log.i(TAG, "display info:\n${ShellPriv.displayInfo(displayId)}")
            updateStatus(when {
                ok -> "Running ${appLabel(targetPkg)} in 3D (display $displayId)"
                targetPkg == null -> "No app selected — pick one, stop, and retry"
                else -> "Launch of $targetPkg failed; stop and retry"
            })
        }.start()

        weaving = true
        running = true
        main.post { overlay?.show() }
        registerScreenOff()
        updateStatus("Starting ${appLabel(intent?.getStringExtra(EXTRA_PKG))} in 3D (display $displayId)")
        return START_STICKY
    }

    /** Display name for a package, falling back to the package id. */
    private fun appLabel(pkg: String?): String {
        if (pkg == null) return "app"
        return try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) {
            pkg
        }
    }

    /** Build the persistent output pipeline once (CNSDK overlay + renderer). */
    private fun ensureOutputPipeline(): Boolean {
        if (overlay != null) return true
        val ov = Overlay3D(
            this, captureW * StereoRenderer.VIEWS, captureH,
            onSurfaceReady = { st -> onStereoSurfaceReady(st) },
            onTouch = ::forwardTouch)
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

    /** Build the capture side: trusted virtual display + ImageReader. */
    private fun startCapture(): Boolean {
        teardownCapture()  // in case a previous session left one behind

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

        val vd = ShellPriv.createDisplay(
            this, "mcweaver-display", captureW, captureH, captureDpi, imageReader.surface)
        if (vd == null) {
            Log.e(TAG, "virtual display creation failed")
            return false
        }
        display = vd
        displayId = vd.display?.displayId ?: -1
        if (displayId < 0) {
            return false
        }
        Log.i(TAG, "capture ready: ${captureW}x${captureH} on display $displayId")
        return true
    }

    /** Tear down only the capture side (virtual display + ImageReader). The
     *  output pipeline (renderer + EGL + DepthEngine + CNSDK) stays alive. */
    private fun teardownCapture() {
        try { display?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { readerThread?.quitSafely() } catch (_: Throwable) {}
        display = null
        reader = null
        readerThread = null
        displayId = -1
    }

    /** Capture resolution: full physical panel scaled down to <=1280 on its
     *  long side. Using the REAL panel size keeps the capture aspect
     *  identical to the fullscreen stereo output — the mismatch was what
     *  stretched the picture ("shape broken") and sent taps to the wrong Y
     *  ("goes upwards"). */
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

    // ------------------------------------------------------------------ input

    private var inputThread: HandlerThread? = null
    private var inputHandler: Handler? = null
    private var safeAreaFrac = 0f

    /**
     * Forward a raw touch from the overlay into the virtual display's content,
     * remapping EVERY pointer from panel coordinates into the capture rect so
     * multi-touch (joystick + look + buttons at once) works natively.
     */
    private fun forwardTouch(ev: MotionEvent, offsetX: Int, offsetY: Int,
                             panelW: Int, panelH: Int) {
        if (displayId < 0 || panelW <= 0 || panelH <= 0 || captureW <= 0 || captureH <= 0) {
            return
        }
        val action = ev.actionMasked
        if (action != MotionEvent.ACTION_DOWN &&
            action != MotionEvent.ACTION_POINTER_DOWN &&
            action != MotionEvent.ACTION_MOVE &&
            action != MotionEvent.ACTION_UP &&
            action != MotionEvent.ACTION_POINTER_UP &&
            action != MotionEvent.ACTION_CANCEL
        ) {
            return
        }

        // Remap every pointer from panel space into the capture rect
        // (letterboxed by the safe-area margin, matching the DIBR shader).
        val pad = safeAreaFrac
        val span = (1f - 2f * pad).let { if (it <= 0f) 1f else it }
        val count = ev.pointerCount
        val props = arrayOfNulls<MotionEvent.PointerProperties>(count)
        val coords = arrayOfNulls<MotionEvent.PointerCoords>(count)
        for (i in 0 until count) {
            val p = MotionEvent.PointerProperties()
            ev.getPointerProperties(i, p)
            val c = MotionEvent.PointerCoords()
            ev.getPointerCoords(i, c)
            val u = (((ev.getX(i) + offsetX) / panelW - pad) / span).coerceIn(0f, 1f)
            val v = (((ev.getY(i) + offsetY) / panelH - pad) / span).coerceIn(0f, 1f)
            c.x = u * captureW
            c.y = v * captureH
            props[i] = p
            coords[i] = c
        }

        // Rebuild the event preserving the full action (incl. pointer index),
        // pointer ids, and timing; inject onto the virtual display.
        val mapped = MotionEvent.obtain(
            ev.downTime, ev.eventTime, ev.action, count, props, coords,
            ev.metaState, ev.buttonState, ev.xPrecision, ev.yPrecision,
            ev.deviceId, ev.edgeFlags, ev.source, ev.flags)

        if (inputHandler == null) {
            val t = HandlerThread("weaver-input").apply { start() }
            inputThread = t
            inputHandler = Handler(t.looper)
        }
        val d = displayId
        inputHandler?.post { ShellPriv.injectEvent(mapped, d) }
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
        safeAreaFrac = safe
    }

    // ------------------------------------------------------------------ lifecycle

    private fun startInForeground() {
        startNotification("Starting… display $displayId")
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
            .setContentTitle("MC 3D weaver")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    /** STOP weaving: move the 3D desktop's tasks back to the real screen,
     *  hide the overlay, tear down ONLY the capture side. The output pipeline
     *  (renderer + EGL + DepthEngine + CNSDK) stays alive so the next START is
     *  instant and never blacks out. */
    private fun hideSession() {
        if (!weaving && active == null) return
        Log.i(TAG, "hideSession: moving tasks to display 0, hiding overlay")
        weaving = false
        running = false
        unregisterScreenOff()
        main.post { overlay?.hide() }
        Thread {
            try {
                ShellPriv.moveAllTasks(displayId, 0)
            } catch (_: Throwable) {}
            teardownCapture()
            updateStatus("3D stopped — press START 3D to resume")
        }.start()
    }

    /** Full teardown: everything, used when the process is going away. */
    private fun shutdown() {
        weaving = false
        running = false
        unregisterScreenOff()
        try { overlay?.hide() } catch (_: Throwable) {}
        try { renderer?.release() } catch (_: Throwable) {}
        try { overlay?.release() } catch (_: Throwable) {}
        renderer = null
        overlay = null
        Thread {
            try {
                ShellPriv.moveAllTasks(displayId, 0)
            } catch (_: Throwable) {}
            teardownCapture()
            try { inputThread?.quitSafely() } catch (_: Throwable) {}
            inputThread = null
            inputHandler = null
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
