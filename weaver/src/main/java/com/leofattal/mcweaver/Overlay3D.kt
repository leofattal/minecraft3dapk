package com.leofattal.mcweaver

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.leia.core.LogLevel
import com.leia.sdk.LeiaSDK
import com.leia.sdk.graphics.SurfaceTextureReadyCallback
import com.leia.sdk.views.InputViewsAsset
import com.leia.sdk.views.InterlacedSurfaceView

/**
 * The on-screen 3D output: a fullscreen, opaque, NOT_TOUCHABLE, FLAG_SECURE
 * [InterlacedSurfaceView] that shows the woven side-by-side stereo image of
 * whatever is on the real screen beneath it.
 *
 *  - NOT_TOUCHABLE: every touch falls through to the real apps/nav bar beneath,
 *    so Minecraft, typing, and the triangle/circle/square nav buttons all work
 *    natively with no forwarding and no interference.
 *  - FLAG_SECURE: the screen capture excludes this overlay itself, so the
 *    woven image never feeds back into the pipeline (no infinite recursion).
 *  - No overlay buttons at all (they interfered with gameplay); stop by
 *    closing the pad (screen off) or the notification action.
 */
class Overlay3D(
    private val context: Context,
    private val sbsWidth: Int,
    private val sbsHeight: Int,
    private val onSurfaceReady: (SurfaceTexture) -> Unit,
) {
    companion object {
        private const val TAG = "Overlay3D"

        /** Latest instance, used to clear a zombie overlay after an interrupted
         *  session. */
        @Volatile var current: Overlay3D? = null
            private set
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var leiaSdk: LeiaSDK? = null
    private var stereoView: InterlacedSurfaceView? = null
    private var stereoAdded = false
    private val main = Handler(Looper.getMainLooper())

    fun init(): Boolean {
        leiaSdk = try {
            val args = LeiaSDK.InitArgs()
            args.enableFaceTracking = true
            args.requiresFaceTrackingPermissionCheck = false
            args.faceTrackingServerLogLevel = LogLevel.Error
            args.platform.context = context.applicationContext
            args.platform.logLevel = LogLevel.Error
            LeiaSDK.createSDK(args)
        } catch (t: Throwable) {
            Log.e(TAG, "LeiaSDK init failed: ${t.message}", t)
            null
        }
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
                        // excluded from screen capture -> the woven output never
                        // loops back into the pipeline
                        or WindowManager.LayoutParams.FLAG_SECURE
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
        if (stereoAdded) {
            stereoAdded = false
            try {
                wm.removeView(stereoView)
            } catch (_: Throwable) {
            }
        }
    }

    fun release() {
        hide()
        try {
            stereoView?.softClose()
        } catch (_: Throwable) {
        }
        stereoView = null
        try {
            leiaSdk?.close()
        } catch (_: Throwable) {
        }
        leiaSdk = null
        if (current === this) {
            current = null
        }
    }
}
