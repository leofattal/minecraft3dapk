package com.leofattal.mcweaver

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub Releases: fetches the latest release from the
 * API, compares it with the installed version, and downloads the APK
 * asset. Installation itself goes through [ShellPriv.installApk]
 * (`pm install` as the shell uid via Shizuku) so no unknown-sources
 * prompt or extra permission is needed.
 */
object Updater {
    private const val TAG = "Updater"
    private const val RELEASES_URL =
        "https://api.github.com/repos/leofattal/minecraft3dapk/releases/latest"

    /** Metadata of the latest release's APK asset (null when absent). */
    class Release(val tag: String, val apkUrl: String, val apkSize: Long)

    /** Fetch the latest release, or null when unreachable / no APK asset. */
    fun fetchLatest(): Release? = try {
        val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) {
                Log.w(TAG, "releases/latest -> HTTP ${conn.responseCode}")
                null
            } else {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val assets = json.getJSONArray("assets")
                var apk: JSONObject? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.getString("name")
                    if (name == "MC3D-Weaver.apk") { apk = a; break }
                    if (apk == null && name.endsWith(".apk")) apk = a
                }
                apk?.let {
                    Release(json.getString("tag_name"),
                        it.getString("browser_download_url"),
                        it.getLong("size"))
                }
            }
        } finally {
            conn.disconnect()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "fetchLatest: ${t.message}")
        null
    }

    /** True when [latestTag] (e.g. "v2.3") is newer than the installed
     *  [current] (e.g. "2.2"). Tolerates a "v" prefix and compares
     *  dot-separated segments numerically ("2.10" > "2.9"). */
    fun isNewer(latestTag: String, current: String): Boolean {
        val lt = latestTag.trim().removePrefix("v").removePrefix("V")
        val ct = current.trim().removePrefix("v").removePrefix("V")
        if (lt == ct) return false
        val l = lt.split('.').map { it.toIntOrNull() ?: 0 }
        val c = ct.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Download [url] into [dest], reporting (bytesRead, total) progress.
     *  Throws on any failure; a partial [dest] is best-effort deleted. */
    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            try {
                dest.outputStream().use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            onProgress(read, total)
                        }
                    }
                }
            } catch (t: Throwable) {
                dest.delete()
                throw t
            }
        } finally {
            conn.disconnect()
        }
    }
}
