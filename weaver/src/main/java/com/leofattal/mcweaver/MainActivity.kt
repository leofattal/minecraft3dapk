package com.leofattal.mcweaver

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * Setup + control UI. The whole-tablet 3D weaving runs in [WeaverService];
 * this activity only collects the two permissions it needs (screen capture
 * consent + draw-over-apps), stores tuning preferences, and starts/stops it.
 */
class MainActivity : Activity() {
    companion object {
        private const val REQ_PROJECTION = 71
        private const val REQ_CAMERA = 7
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var startButton: Button

    private val projectionManager: MediaProjectionManager
        get() = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("weaver", Context.MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }

        setContentView(buildUi())
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        // Clear any zombie overlay left behind by an interrupted 3D session.
        if (WeaverService.active == null && Overlay3D.current != null) {
            try { Overlay3D.current?.release() } catch (_: Throwable) {}
        }
        refreshStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                WeaverService.startProjection(this, resultCode, data)
                refreshStatus()
            } else {
                toast("Screen capture permission is needed to weave the screen into 3D")
            }
        }
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): ScrollView {
        val pad = (resources.displayMetrics.density * 16).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "MC 3D Weaver"
            textSize = 24f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Everything on your tablet, in glasses-free 3D"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad)
        })

        statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, pad / 2)
        }
        root.addView(statusText)

        root.addView(Button(this).apply {
            text = "Overlay permission"
            setOnClickListener {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
        })

        root.addView(TextView(this).apply {
            text = "3D depth strength"
        })
        root.addView(makeSeek("depth", 40) { refreshService() })

        root.addView(TextView(this).apply {
            text = "Convergence (screen plane)"
        })
        root.addView(makeSeek("convergence", 45) { refreshService() })

        root.addView(TextView(this).apply {
            text = "Screen margin (0 = fill the screen fully)"
        })
        root.addView(makeSeekInt("safeArea", 0, 15) { refreshService() })

        root.addView(CheckBox(this).apply {
            text = "Swap eyes (if depth looks inverted)"
            isChecked = prefs.getBoolean("swap", false)
            setOnCheckedChangeListener { _, _ ->
                prefs.edit().putBoolean("swap", isChecked).apply()
                refreshService()
            }
        })

        root.addView(CheckBox(this).apply {
            text = "Flip image vertically (if the picture is upside down)"
            isChecked = prefs.getBoolean("flip", false)
            setOnCheckedChangeListener { _, _ ->
                prefs.edit().putBoolean("flip", isChecked).apply()
                refreshService()
            }
        })

        startButton = Button(this).apply {
            text = "  START 3D  "
            textSize = 18f
            setOnClickListener { onStartClicked() }
        }
        root.addView(startButton)

        root.addView(TextView(this).apply {
            text = """
                How it works: everything currently on your screen — home screen,
                Minecraft, browser, videos — is captured, given real depth by an
                on-device AI pass, and woven onto the lightfield display in 3D.

                No overlay buttons get in your way: touches, the on-screen
                keyboard, and the back / home / recents buttons all work exactly
                like normal. Close the pad (turn the screen off) or tap the Stop
                notification to leave 3D.
            """.trimIndent()
            textSize = 12f
            setPadding(0, pad, 0, 0)
            movementMethod = ScrollingMovementMethod()
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun makeSeek(key: String, def: Int, onChange: () -> Unit): SeekBar {
        return SeekBar(this).apply {
            max = 100
            progress = prefs.getFloat(key, def.toFloat()).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) {
                        prefs.edit().putFloat(key, v.toFloat()).apply()
                        onChange()
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
    }

    private fun makeSeekInt(key: String, def: Int, maxV: Int,
                            onChange: () -> Unit): SeekBar {
        return SeekBar(this).apply {
            max = maxV
            progress = prefs.getInt(key, def)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) {
                        prefs.edit().putInt(key, v).apply()
                        onChange()
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
    }

    // ------------------------------------------------------------------ actions

    private fun hasOverlayPermission(): Boolean =
        Settings.canDrawOverlays(this)

    private fun onStartClicked() {
        if (WeaverService.active != null) {
            WeaverService.stop(this)
            refreshStatus()
            return
        }
        if (!hasOverlayPermission()) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        val grant = WeaverService.projectionGrant
        if (grant != null) {
            WeaverService.startProjection(this, grant.first, grant.second)
        } else {
            // One-time "Start now?" consent for screen capture.
            startActivityForResult(
                projectionManager.createScreenCaptureIntent(), REQ_PROJECTION)
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun refreshService() {
        WeaverService.active?.refreshParams()
    }

    private fun refreshStatus() {
        val running = WeaverService.active != null
        val overlay = hasOverlayPermission()
        val granted = WeaverService.projectionGrant != null
        statusText.text = buildString {
            append(if (running) "STATE: RUNNING 3D" else "STATE: stopped")
            append("\nOverlay permission: ").append(if (overlay) "granted" else "missing")
            append("\nScreen capture: ").append(if (granted) "granted" else "not yet granted (prompts on first start)")
        }
        startButton.text = if (running) "  STOP 3D  " else "  START 3D  "
    }
}
