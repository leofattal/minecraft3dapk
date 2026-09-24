package com.leofattal.mcweaver

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Privileged operations executed at the shell uid through Shizuku (no root):
 *
 *  - create a TRUSTED virtual display with system decorations (own nav bar)
 *    whose composited output feeds our capture surface,
 *  - move the launcher/apps onto that display,
 *  - set the display IME policy so the on-screen keyboard renders ON the
 *    virtual display (and is therefore woven in 3D),
 *  - inject full multi-touch MotionEvents and key events onto that display.
 *
 * The trick is to swap [DisplayManagerGlobal]'s cached IDisplayManager binder
 * with one wrapped in Shizuku's shell-uid binder, so ordinary framework calls
 * run with shell privileges. Everything hidden is reached via reflection.
 */
object ShellPriv {
    private const val TAG = "ShellPriv"

    // android.hardware.display.DisplayManager virtual-display flags (@hide);
    // resolved reflectively per build because their values vary.
    private const val FALLBACK_FLAG_PUBLIC = 1
    private const val FALLBACK_FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val FALLBACK_FLAG_TRUSTED = 1 shl 10

    private var hiddenApiReady = false
    private var displayBinderSwapped = false
    private var cachedFlags: Int? = null

    fun isShizukuReady(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    private fun unhide() {
        if (!hiddenApiReady) {
            HiddenApiBypass.addHiddenApiExemptions("")
            hiddenApiReady = true
        }
    }

    private fun serviceBinder(name: String): IBinder {
        val sm = Class.forName("android.os.ServiceManager")
        return sm.getMethod("getService", String::class.java).invoke(null, name) as IBinder
    }

    private fun hiddenDisplayFlag(name: String, fallback: Int): Int = try {
        unhide()
        val field = DisplayManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.getInt(null)
    } catch (t: Throwable) {
        Log.w(TAG, "flag $name not resolvable ($fallback), using fallback")
        fallback
    }

    /** PUBLIC | OWN_CONTENT_ONLY | TRUSTED | (SHOULD_SHOW_SYSTEM_DECORATIONS) */
    private fun displayFlags(): Int {
        cachedFlags?.let { return it }
        var flags = hiddenDisplayFlag("VIRTUAL_DISPLAY_FLAG_PUBLIC", FALLBACK_FLAG_PUBLIC) or
            hiddenDisplayFlag("VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY", FALLBACK_FLAG_OWN_CONTENT_ONLY) or
            hiddenDisplayFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED", FALLBACK_FLAG_TRUSTED)
        try {
            val decor = DisplayManager::class.java
                .getDeclaredField("VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS")
                .also { it.isAccessible = true }.getInt(null)
            flags = flags or decor
            Log.i(TAG, "display flags include system decorations ($decor)")
        } catch (t: Throwable) {
            Log.w(TAG, "SHOULD_SHOW_SYSTEM_DECORATIONS not available: ${t.message}")
        }
        cachedFlags = flags
        Log.i(TAG, "display flags = $flags")
        return flags
    }

    /** Wrap the IDisplayManager binder with the shell uid (once per process). */
    private fun swapDisplayBinder() {
        if (displayBinderSwapped) return
        unhide()
        val stubClass = Class.forName("android.hardware.display.IDisplayManager\$Stub")
        val shellDm = stubClass
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(serviceBinder("display")))
        val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val global = globalClass.getMethod("getInstance").invoke(null)
        val binderField = globalClass.getDeclaredField("mDm").apply { isAccessible = true }
        binderField.set(global, shellDm)
        displayBinderSwapped = true
        Log.i(TAG, "DisplayManagerGlobal now runs as shell")
    }

    /** True when the active session's virtual display was created without
     *  FLAG_TRUSTED (some ROMs do not let the shell uid set it). */
    @Volatile var lastDisplayUntrusted = false
        private set

    /**
     * Create a virtual display rendering into [surface]. Prefers a TRUSTED,
     * system-decorated display (real input viewport + own nav bar), and falls
     * back to a plain PUBLIC|OWN_CONTENT_ONLY display when the shell uid is not
     * allowed to set FLAG_TRUSTED.
     *
     * The fallback matters: on the Lume Pad build the shell uid does not hold
     * ADD_TRUSTED_DISPLAY, so the TRUSTED attempt throws a SecurityException.
     * Without the fallback the whole session aborted (and, before the Leia SDK
     * singleton fix, closing it afterwards aborted the process).
     */
    fun createDisplay(
        context: Context, name: String, w: Int, h: Int, dpi: Int, surface: Surface,
    ): VirtualDisplay? {
        return try {
            swapDisplayBinder()
            // DisplayManagerService checks that the caller package owns the request;
            // running as shell, the request must claim to be shell.
            val shellContext = object : android.content.ContextWrapper(context.applicationContext) {
                override fun getPackageName() = "com.android.shell"
                override fun getOpPackageName() = "com.android.shell"
            }
            val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val global = globalClass.getMethod("getInstance").invoke(null)
            val create = globalClass.declaredMethods.first {
                it.name == "createVirtualDisplay" && it.parameterTypes.size == 5
            }.apply { isAccessible = true }

            val cfgClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            fun make(flags: Int): VirtualDisplay {
                val builder = cfgClass
                    .getConstructor(String::class.java, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .newInstance(name, w, h, dpi)
                cfgClass.getMethod("setFlags", Int::class.javaPrimitiveType)
                    .invoke(builder, flags)
                cfgClass.getMethod("setSurface", android.view.Surface::class.java)
                    .invoke(builder, surface)
                val cfg = cfgClass.getMethod("build").invoke(builder)
                return create.invoke(global, shellContext, null, cfg, null, null) as VirtualDisplay
            }

            try {
                make(displayFlags()).also {
                    lastDisplayUntrusted = false
                    Log.i(TAG, "virtual display created (trusted) ${w}x$h id=${it.display?.displayId}")
                }
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)
                    ?.targetException ?: t.cause ?: t
                Log.w(TAG, "trusted display rejected (${cause.javaClass.simpleName}: " +
                        "${cause.message}); falling back to untrusted", t)
                val untrusted = hiddenDisplayFlag("VIRTUAL_DISPLAY_FLAG_PUBLIC",
                        FALLBACK_FLAG_PUBLIC) or
                    hiddenDisplayFlag("VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY",
                        FALLBACK_FLAG_OWN_CONTENT_ONLY)
                make(untrusted).also {
                    lastDisplayUntrusted = true
                    Log.i(TAG, "virtual display created (untrusted) ${w}x$h " +
                            "id=${it.display?.displayId}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "createDisplay failed: ${t.message}", t)
            null
        }
    }

    /** Make the on-screen keyboard render ON the virtual display (woven in 3D). */
    fun setImeLocal(displayId: Int): Boolean {
        return try {
            unhide()
            val binder = ShizukuBinderWrapper(serviceBinder("window"))
            val wm = Class.forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            wm.javaClass
                .getMethod("setDisplayImePolicy",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(wm, displayId, 1 /* DISPLAY_IME_POLICY_LOCAL */)
            Log.i(TAG, "display $displayId IME policy = LOCAL")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setDisplayImePolicy failed: ${t.message}")
            false
        }
    }

    // ---------------------------------------------------------------- launch

    /** Resolve a package's launcher activity to a component string. */
    fun launcherComponent(pkg: String): String? = try {
        val out = shCapture("cmd", "package", "resolve-activity", "--brief",
            "-c", "android.intent.category.LAUNCHER", pkg)
        out.trim().lineSequence().lastOrNull { it.contains("/") }?.trim()
    } catch (t: Throwable) {
        null
    }

    /** Resolve the device's default home launcher component. */
    fun homeComponent(): String? = try {
        val out = shCapture("cmd", "package", "resolve-activity", "--brief",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
        out.trim().lineSequence().lastOrNull { it.contains("/") }?.trim()
    } catch (t: Throwable) {
        null
    }

    /** Launch the home screen (3D desktop) on the virtual display. */
    fun launchHomeOnDisplay(displayId: Int): Boolean = try {
        val rc = shRun("am", "start", "--display", displayId.toString(),
            "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.HOME",
            "-f", "0x10000000" /* FLAG_ACTIVITY_NEW_TASK */)
        Log.i(TAG, "launched home on display $displayId rc=$rc")
        rc == 0
    } catch (t: Throwable) {
        Log.e(TAG, "launchHome failed: ${t.message}", t)
        false
    }

    /**
     * Fresh-launch a single app onto [displayId]. Tasks merely migrated to an
     * OWN_CONTENT_ONLY display render black, so the launch always
     * force-stops and starts clean.
     */
    fun launchOnDisplay(pkg: String, displayId: Int): Boolean {
        return try {
            val component = launcherComponent(pkg) ?: return false
            shRun("am", "force-stop", pkg)
            val rc = shRun("am", "start", "--display", displayId.toString(),
                "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER",
                "-n", component,
                "-f", (0x10000000 or 0x00200000).toString())
            Log.i(TAG, "launched $component on display $displayId rc=$rc")
            rc == 0
        } catch (t: Throwable) {
            Log.e(TAG, "launchOnDisplay failed: ${t.message}", t)
            false
        }
    }

    private fun taskIdFor(pkg: String): Int = try {
        val listing = shCapture("am", "stack", "list")
            .ifBlank { shCapture("am", "task", "list") }
        Regex("""taskId=(\d+):[^\n]*${Regex.escape(pkg)}""")
            .find(listing)?.groupValues?.get(1)?.toInt() ?: -1
    } catch (t: Throwable) {
        -1
    }

    /** Move [pkg]'s task to [displayId] (0 = real screen), or fresh-launch if none. */
    fun moveToDisplay(pkg: String, displayId: Int): Boolean {
        return try {
            val taskId = taskIdFor(pkg)
            if (taskId >= 0) {
                val rc = shRun("am", "stack", "move-task",
                    taskId.toString(), displayId.toString(), "true")
                if (rc == 0) return true
            }
            true // best effort; leaving the task is harmless when stopping
        } catch (t: Throwable) {
            Log.e(TAG, "moveToDisplay failed: ${t.message}", t)
            false
        }
    }

    /** Move every task from [fromDisplay] to [toDisplay]. */
    fun moveAllTasks(fromDisplay: Int, toDisplay: Int) {
        if (fromDisplay < 0) return
        try {
            val dump = shCapture("dumpsys", "activity", "activities")
            val displayRe = Regex("""Display #(\d+) \(activities""")
            val matches = displayRe.findAll(dump).toList()
            val block = matches.firstOrNull { it.groupValues[1].toInt() == fromDisplay }
                ?.let { m ->
                    val start = m.range.last
                    val end = matches.firstOrNull { it.range.first > start }
                        ?.range?.first ?: dump.length
                    dump.substring(start, end)
                } ?: return
            Regex("""taskId=(\d+)""").findAll(block).forEach { tm ->
                shRun("am", "stack", "move-task",
                    tm.groupValues[1], toDisplay.toString(), "true")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "moveAllTasks: ${t.message}")
        }
    }

    /** Diagnostic text about a display (used in logs to verify input viewport). */
    fun displayInfo(displayId: Int): String = try {
        val out = shCapture("dumpsys", "display")
        val idx = out.indexOf("mDisplayId=$displayId")
            .let { if (it < 0) out.indexOf("Display $displayId") else it }
        if (idx < 0) "display $displayId not found"
        else out.substring(idx, minOf(out.length, idx + 700))
    } catch (t: Throwable) {
        "dumpsys failed: ${t.message}"
    }

    // ---------------------------------------------------------------- input

    private var shellInput: Any? = null

    private fun inputManager(): Any {
        if (shellInput == null) {
            unhide()
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            shellInput = stub.getMethod("asInterface", IBinder::class.java)
                .invoke(null, ShizukuBinderWrapper(serviceBinder("input")))
        }
        return shellInput!!
    }

    /** Inject a fully-formed MotionEvent (multi-touch supported) onto
     *  [displayId] (ASYNC mode). Takes ownership of the event. */
    fun injectEvent(event: MotionEvent, displayId: Int): Boolean {
        return try {
            MotionEvent::class.java
                .getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
            inputManager().javaClass
                .getMethod("injectInputEvent",
                    Class.forName("android.view.InputEvent"), Int::class.javaPrimitiveType)
                .invoke(inputManager(), event, 0) as Boolean
        } catch (t: Throwable) {
            Log.e(TAG, "injectEvent failed: ${t.message}")
            false
        } finally {
            event.recycle()
        }
    }

    /** Inject a key event onto [displayId] (e.g. BACK from the hardware key). */
    fun injectKey(keyCode: Int, displayId: Int): Boolean = try {
        shRun("input", "-d", displayId.toString(), "keyevent", keyCode.toString()) == 0
    } catch (t: Throwable) {
        false
    }

    // ---------------------------------------------------------------- shell

    private fun newShellProcess(cmd: Array<String>): Any {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
        return m.invoke(null, cmd, null, null)
            ?: error("Shizuku.newProcess returned null")
    }

    private fun shRun(vararg cmd: String): Int {
        val p = newShellProcess(arrayOf(*cmd))
        p.javaClass.getMethod("waitFor").invoke(p)
        return p.javaClass.getMethod("exitValue").invoke(p) as Int
    }

    private fun shCapture(vararg cmd: String): String {
        val p = newShellProcess(arrayOf(*cmd))
        val out = (p.javaClass.getMethod("getInputStream").invoke(p) as java.io.InputStream)
            .bufferedReader().readText()
        p.javaClass.getMethod("waitFor").invoke(p)
        return out
    }
}
