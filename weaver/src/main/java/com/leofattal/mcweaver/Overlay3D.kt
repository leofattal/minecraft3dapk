package com.leofattal.mcweaver

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.leia.core.LogLevel
import com.leia.sdk.LeiaSDK
import com.leia.sdk.graphics.SurfaceTextureReadyCallback
import com.leia.sdk.views.InputViewsAsset
import com.leia.sdk.views.InterlacedSurfaceView

/**
 * Owns the on-screen 3D machinery:
 *  1. a fullscreen, pixel-exact, NOT_TOUCHABLE [InterlacedSurfaceView] window
 *     that weaves our synthesized SBS stereo onto the lightfield panel (with
 *     the Leia system face tracking), and
 *  2. a transparent touch-catcher window stacked above it that receives ALL
 *     touches (full multi-pointer events) and hands the raw MotionEvents to
 *     the service, which remaps every pointer into the virtual display and
 *     injects them there — two-thumb play, buttons, and the keyboard work
 *     exactly like native.
 *
 * No on-screen buttons: stop by closing the pad (screen off) or the
 * notification; BACK is forwarded from the activity.
 */
class Overlay3D(
    private val context: Context,
    private val sbsWidth: Int,
    private val sbsHeight: Int,
    private val onSurfaceReady: (SurfaceTexture) -> Unit,
    /** (event, offsetX, offsetY, panelW, panelH): raw touch + catcher window
     *  offset + panel dims; the service remaps all pointers. */
    private val onTouch: (MotionEvent, Int, Int, Int, Int) -> Unit,
) {
    companion object {
        private const val TAG = "Overlay3D"

        /** Latest instance, used to clear a zombie overlay after an
         *  interrupted session. */
        @Volatile var current: Overlay3D? = null
            private set

        /**
         * The Leia SDK is a process-global singleton. It is created once and
         * NEVER closed while the process lives: `LeiaSDK.close()` tears down
         * the CNSDK logger mutex, and the face-tracking service connection
         * that completes afterwards (`BaseServiceConnection.onServiceConnected`)
         * then locks that destroyed mutex and aborts the whole process with
         * "FORTIFY: pthread_mutex_lock called on a destroyed mutex".
         *
         * Creating the SDK twice in one process is likewise unsafe, so a
         * single instance is shared by every Overlay3D for the app's lifetime.
         */
        @Volatile private var sdkSingleton: LeiaSDK? = null
        private val sdkLock = Any()

        /** The process-wide Leia SDK, created on first use, never closed. */
        private fun ensureSdk(context: Context): LeiaSDK? {
            sdkSingleton?.let { return it }
            synchronized(sdkLock) {
                sdkSingleton?.let { return it }
                return try {
                    val args = LeiaSDK.InitArgs()
                    args.enableFaceTracking = true
                    args.requiresFaceTrackingPermissionCheck = false
                    args.faceTrackingServerLogLevel = LogLevel.Error
                    args.platform.context = context.applicationContext
                    args.platform.logLevel = LogLevel.Error
                    LeiaSDK.createSDK(args).also { sdkSingleton = it }
                } catch (t: Throwable) {
                    Log.e(TAG, "LeiaSDK init failed: ${t.message}", t)
                    null
                }
            }
        }
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var leiaSdk: LeiaSDK? = null
    private var stereoView: InterlacedSurfaceView? = null
    private var stereoAdded = false
    private var catcherView: FrameLayout? = null
    private var catcherAdded = false
    private val main = Handler(Looper.getMainLooper())

    // Catcher window geometry within the panel (captures its own offset so the
    // service can map touches into panel coordinates).
    private var catcherX = 0
    private var catcherY = 0
    private var panelW = 0
    private var panelH = 0

    fun init(): Boolean {
        // Leia CNSDK: interlacer + face tracking handled by the system services.
        // Shared process-wide and never closed (see ensureSdk).
        leiaSdk = ensureSdk(context)
        if (leiaSdk == null) return false

        val view = try {
            InterlacedSurfaceView(context)
        } catch (t: Throwable) {
            Log.e(TAG, "InterlacedSurfaceView failed: ${t.message}", t)
            return false
        }
        val asset = InputViewsAsset()
        asset.setHorizontalViewsLayout()
        asset.CreateEmptySurfaceForVideo(sbsWidth, sbsHeight,
            SurfaceTextureReadyCallback { st: SurfaceTexture ->
                Log.i(TAG, "CNSDK video surface ready ${sbsWidth}x$sbsHeight")
                onSurfaceReady(st)
            })
        view.setViewAsset(asset)
        stereoView = view
        current = this
        return true
    }

    fun show() {
        val view = stereoView ?: return

        if (!stereoAdded) {
            stereoAdded = true
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        // Keep the panel awake while 3D is up, otherwise a
                        // brief inactivity turns the screen off, which fires
                        // ACTION_SCREEN_OFF and stops the session mid-game.
                        or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE)
            wm.addView(view, params)
        }
        try {
            view.onResume()
        } catch (_: Throwable) {
        }
        try {
            leiaSdk?.enableBacklight(true)
        } catch (t: Throwable) {
            Log.w(TAG, "enableBacklight(true): ${t.message}")
        }

        if (!catcherAdded) {
            catcherAdded = true
            val catcher = FrameLayout(context)
            catcher.setOnTouchListener { _: View, e: MotionEvent ->
                onTouch(e, catcherX, catcherY, panelW, panelH)
                true
            }
            catcherView = catcher

            // Cover the whole panel: touches on the real nav bar would hit
            // display 0; the virtual display's own nav bar (system
            // decorations) is the nav the user sees in 3D.
            // Measure the REAL panel size (status bar area included), not
            // currentWindowMetrics.bounds, which excludes system bars: the
            // overlay covers the full panel, so the touch normalization must
            // use the full panel too or taps drift off toward the bottom.
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val p = android.graphics.Point()
            dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.getRealSize(p)
            panelW = p.x
            panelH = p.y
            catcherX = 0
            catcherY = 0
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT)
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 0
            wm.addView(catcher, params)
        }
    }

    fun hide() {
        try {
            stereoView?.onPause()
        } catch (_: Throwable) {
        }
        try {
            leiaSdk?.enableBacklight(false)
        } catch (_: Throwable) {
        }
        if (catcherAdded) {
            catcherAdded = false
            try { wm.removeView(catcherView) } catch (_: Throwable) {}
            catcherView = null
        }
        if (stereoAdded) {
            stereoAdded = false
            try { wm.removeView(stereoView) } catch (_: Throwable) {}
        }
    }

    fun release() {
        hide()
        try {
            stereoView?.softClose()
        } catch (_: Throwable) {
        }
        stereoView = null
        // Deliberately do NOT close the Leia SDK: it is a process-global
        // singleton and closing it while the face-tracking service is still
        // (re)connecting aborts the process. Detaching the views is enough.
        leiaSdk = null
        if (current === this) {
            current = null
        }
    }
}
