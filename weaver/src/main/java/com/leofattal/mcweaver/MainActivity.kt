package com.leofattal.mcweaver

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

/**
 * Setup + control UI. The whole-tablet 3D weaving runs in [WeaverService];
 * this activity only collects the permissions it needs (Shizuku + draw-over
 * apps + camera for face tracking), stores tuning preferences, picks the app
 * to run on the hidden 3D display, starts/stops the session, and forwards the
 * hardware BACK key into the 3D display while the session is running.
 */
class MainActivity : Activity() {
    companion object {
        private const val REQ_CAMERA = 7
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var updateButton: Button

    /** (label, package) pairs for the app picker; Minecraft pinned first. */
    private var appList: List<Pair<String, String>> = emptyList()

    private val shizukuListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
        _, grantResult -> refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("weaver", Context.MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }

        setContentView(buildUi())
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuListener)
        refreshStatus()
    }

    override fun onDestroy() {
        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Clear any zombie overlay left behind by an interrupted 3D session.
        // Only detach its windows — never release the process-wide Leia SDK.
        if (!WeaverService.running && Overlay3D.current != null) {
            try { Overlay3D.current?.hide() } catch (_: Throwable) {}
        }
        refreshStatus()
    }

    /** While 3D runs, the hardware BACK key goes to the 3D desktop. */
    override fun onBackPressed() {
        if (WeaverService.running) {
            WeaverService.forwardKey(this, android.view.KeyEvent.KEYCODE_BACK)
            return
        }
        super.onBackPressed()
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
            text = "The whole tablet in glasses-free 3D"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad)
        })

        statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, pad / 2)
        }
        root.addView(statusText)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val shizukuBtn = Button(this).apply {
            text = "Fix Shizuku"
            setOnClickListener { fixShizuku() }
        }
        val overlayBtn = Button(this).apply {
            text = "Overlay permission"
            setOnClickListener {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
        }
        buttons.addView(shizukuBtn, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(overlayBtn, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        root.addView(TextView(this).apply {
            text = "App to run in 3D"
        })
        appList = installedLaunchableApps()
        val appSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_item,
                appList.map { it.first }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val saved = prefs.getString("targetPkg", null)
            val idx = appList.indexOfFirst { it.second == saved }
            setSelection(if (idx >= 0) idx else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.edit().putString("targetPkg", appList[pos].second).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        root.addView(appSpinner)

        root.addView(TextView(this).apply {
            text = "3D depth strength"
        })
        root.addView(makeSeek("depth", 40) { refreshService() })

        root.addView(TextView(this).apply {
            text = "Convergence (screen plane)"
        })
        root.addView(makeSeek("convergence", 45) { refreshService() })

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

        updateButton = Button(this).apply {
            text = "  Update to latest version  "
            setOnClickListener { onUpdateClicked() }
        }
        root.addView(updateButton)

        root.addView(TextView(this).apply {
            text = """
                One-time setup: install the Shizuku app (Play Store) and start
                it via "Wireless debugging". Then tap Fix Shizuku and Overlay
                permission here, allow the camera permission (face tracking).

                START 3D launches the selected app onto a hidden 3D display
                and weaves it in glasses-free 3D. Two-thumb touch works natively,
                and the hardware BACK key goes to the 3D display.

                "Update to latest version" fetches the newest GitHub release
                and installs it through Shizuku (stop 3D first; afterwards
                re-allow Shizuku + camera).

                The app keeps running when you stop: close the pad (turn the
                screen off) or tap the notification's Stop action to leave 3D.
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
        if (WeaverService.running) {
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
        if (!ShellPriv.isShizukuReady()) {
            fixShizuku()
            return
        }
        WeaverService.start(this, prefs.getString("targetPkg", null))
    }

    // ------------------------------------------------------------------ update

    /** Check GitHub Releases for a newer build; download and install it
     *  through Shizuku when there is one. */
    private fun onUpdateClicked() {
        if (WeaverService.running) {
            statusText.text = "Stop 3D first (tap STOP 3D) before updating."
            return
        }
        if (!ShellPriv.isShizukuReady()) {
            statusText.text = "Shizuku not ready — start Shizuku, then update."
            return
        }
        updateButton.isEnabled = false
        statusText.text = "Checking for updates…"
        Thread { checkForUpdate() }.start()
    }

    private fun checkForUpdate() {
        val current = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Throwable) {
            "?"
        }
        val latest = Updater.fetchLatest()
        if (latest == null) {
            runOnUiThread {
                updateButton.isEnabled = true
                statusText.text = "Update check failed — no release or no APK asset."
            }
            return
        }
        if (!Updater.isNewer(latest.tag, current)) {
            runOnUiThread {
                updateButton.isEnabled = true
                statusText.text = "Up to date ($current); latest release is ${latest.tag}."
            }
            return
        }

        // Newer release: download, then install as the shell uid.
        val dest = java.io.File(cacheDir, "update.apk")
        try {
            runOnUiThread { statusText.text = "Downloading ${latest.tag}…" }
            var lastUi = 0L
            Updater.download(latest.apkUrl, dest) { read, total ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastUi > 200) {
                    lastUi = now
                    val msg = if (total > 0)
                        "Downloading ${latest.tag}: ${read / 1048576}/${total / 1048576} MB"
                    else "Downloading ${latest.tag}: ${read / 1048576} MB"
                    runOnUiThread { statusText.text = msg }
                }
            }
        } catch (t: Throwable) {
            runOnUiThread {
                updateButton.isEnabled = true
                statusText.text = "Download failed: ${t.message}"
            }
            return
        }

        runOnUiThread { statusText.text = "Installing ${latest.tag}…" }
        val ok = ShellPriv.installApk(dest)
        dest.delete()
        // A successful replace normally kills this process; if we are
        // still alive the install did not take.
        runOnUiThread {
            updateButton.isEnabled = true
            statusText.text = if (ok)
                "Installed ${latest.tag} — reopen the app, then tap Fix Shizuku " +
                "and re-allow the camera (updates revoke runtime grants)."
            else "Install failed — check that Shizuku is running and retry."
        }
    }

    /** All launchable apps, Minecraft pinned first, alphabetical after. */
    private fun installedLaunchableApps(): List<Pair<String, String>> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedWith(compareBy(
                { if (WeaverService.MINECRAFT_CANDIDATES.contains(it.second)) 0 else 1 },
                { it.first.lowercase() }))
    }

    private fun fixShizuku() {
        when {
            ShellPriv.isShizukuReady() -> return
            rikka.shizuku.Shizuku.pingBinder() -> try {
                rikka.shizuku.Shizuku.requestPermission(71)
            } catch (_: Throwable) {
            }
            else -> {
                // Shizuku app not running: open its Play Store page or launch it.
                val launch = packageManager.getLaunchIntentForPackage(
                    "moe.shizuku.privileged.api")
                if (launch != null) startActivity(launch)
                else startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")))
            }
        }
    }

    private fun refreshService() {
        WeaverService.active?.refreshParams()
    }

    private fun refreshStatus() {
        val running = WeaverService.running
        val shizuku = ShellPriv.isShizukuReady()
        val overlay = hasOverlayPermission()
        statusText.text = buildString {
            append(if (running) "STATE: RUNNING 3D" else "STATE: stopped")
            append("\nShizuku: ").append(if (shizuku) "ready" else "NOT ready")
            append("\nOverlay permission: ").append(if (overlay) "granted" else "missing")
        }
        startButton.text = if (running) "  STOP 3D  " else "  START 3D  "
    }
}
