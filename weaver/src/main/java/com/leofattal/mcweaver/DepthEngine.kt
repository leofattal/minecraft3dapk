package com.leofattal.mcweaver

import android.content.Context
import android.system.Os
import android.util.Log
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/**
 * On-device monocular depth: the quantized (w8a8) MiDaS CNN exported for
 * Qualcomm AI Hub, run on the Snapdragon 888's Hexagon NPU through the QNN
 * LiteRT delegate. Falls back to XNNPACK CPU when the HTP backend cannot load.
 *
 * Input: 256x256 RGBA bytes; quantized per the model metadata
 * (q = round((p/255)/scale) + zp).
 * Output: 256x256 float depth in [0,1] (1 = nearest), per-frame normalized,
 * temporally smoothed and lightly blurred for stable stereo warping.
 *
 * Must be used from a single thread.
 */
class DepthEngine(context: Context) {
    companion object {
        const val SIZE = 256
        private const val TAG = "DepthEngine"

        // Quantization parameters of the AI Hub midas-tflite-w8a8 export.
        private const val IN_SCALE = 0.00487531116232276f
        private const val IN_ZP = 24
        private const val OUT_SCALE = 6.5142998695373535f
        private const val IN_MUL = 1f / (255f * IN_SCALE)

        private const val EMA = 0.35f          // new-frame weight for smoothing
        private const val BLUR_RADIUS = 4       // spatial blur, kills depth noise
    }

    private val interpreter: Interpreter
    private var qnnDelegate: QnnDelegate? = null

    private val inputBuf =
        ByteBuffer.allocateDirect(SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
    private val outputBuf =
        ByteBuffer.allocateDirect(SIZE * SIZE).order(ByteOrder.nativeOrder())
    private val raw = FloatArray(SIZE * SIZE)
    private val smoothed = FloatArray(SIZE * SIZE)
    private val blurTmp = FloatArray(SIZE * SIZE)
    private val blurOut = FloatArray(SIZE * SIZE)
    private val depthOut: FloatBuffer =
        ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    private var smoothedInit = false
    private var failures = 0

    init {
        val model = mmapAsset(context, "midas_w8a8.tflite")
        interpreter = try {
            // Try the NPU first: the w8a8 graph fully offloads to Hexagon HTP.
            try {
                val skelDir = context.applicationInfo.nativeLibraryDir
                try {
                    val current = Os.getenv("ADSP_LIBRARY_PATH")
                    val combined = if (current.isNullOrEmpty()) skelDir else "$skelDir;$current"
                    Os.setenv("ADSP_LIBRARY_PATH", combined, true)
                } catch (t: Throwable) {
                    Log.w(TAG, "ADSP_LIBRARY_PATH: ${t.message}")
                }
                val opts = QnnDelegate.Options().apply {
                    setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                    setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_QUANTIZED)
                    setHtpPerformanceMode(
                        QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_BURST)
                    setSkelLibraryDir(skelDir)
                    setCacheDir(context.cacheDir.absolutePath)
                    setModelToken("midas_w8a8_htp")
                }
                val delegate = QnnDelegate(opts)
                qnnDelegate = delegate
                Interpreter(model, Interpreter.Options().apply { addDelegate(delegate) }).also {
                    Log.i(TAG, "depth interpreter running on Hexagon HTP")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "QNN HTP unavailable (${t.message}); using CPU XNNPACK", t)
                Interpreter(model, Interpreter.Options().apply {
                    numThreads = 4
                    setUseXNNPACK(true)
                })
            }
        } catch (t: Throwable) {
            Log.e(TAG, "failed to create interpreter: ${t.message}", t)
            throw t
        }
    }

    private fun mmapAsset(context: Context, name: String): ByteBuffer =
        context.assets.openFd(name).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }

    /** [rgba] must hold SIZE*SIZE*4 bytes. Returns normalized depth or null. */
    fun infer(rgba: ByteBuffer): FloatBuffer? {
        return try {
            rgba.rewind()
            val total = SIZE * SIZE
            for (i in 0 until total) {
                val r = rgba.get(i * 4).toInt() and 0xFF
                val g = rgba.get(i * 4 + 1).toInt() and 0xFF
                val b = rgba.get(i * 4 + 2).toInt() and 0xFF
                inputBuf.put(quantize(r))
                inputBuf.put(quantize(g))
                inputBuf.put(quantize(b))
            }
            inputBuf.rewind()
            outputBuf.rewind()
            interpreter.run(inputBuf, outputBuf)
            failures = 0
            normalize()
        } catch (t: Throwable) {
            if (++failures <= 3) Log.e(TAG, "inference failed: ${t.message}")
            null
        }
    }

    private fun quantize(pixel: Int): Byte =
        (Math.round(pixel * IN_MUL) + IN_ZP).coerceIn(0, 255).toByte()

    private fun normalize(): FloatBuffer {
        val total = SIZE * SIZE
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (i in 0 until total) {
            val v = (outputBuf.get(i).toInt() and 0xFF) * OUT_SCALE
            raw[i] = v
            if (v < min) min = v
            if (v > max) max = v
        }
        val range = if (max - min < 1e-6f) 1f else max - min
        depthOut.clear()
        if (!smoothedInit) {
            for (i in 0 until total) {
                smoothed[i] = ((raw[i] - min) / range).coerceIn(0f, 1f)
            }
            smoothedInit = true
        } else {
            for (i in 0 until total) {
                val v = ((raw[i] - min) / range).coerceIn(0f, 1f)
                smoothed[i] += EMA * (v - smoothed[i])
            }
        }
        boxBlur(smoothed, blurOut)
        for (i in 0 until total) depthOut.put(blurOut[i])
        depthOut.rewind()
        return depthOut
    }

    /** Separable box blur; src and dst must be distinct arrays. */
    private fun boxBlur(src: FloatArray, dst: FloatArray) {
        val n = SIZE
        val r = BLUR_RADIUS
        val inv = 1f / (2 * r + 1)
        for (y in 0 until n) {
            val row = y * n
            var acc = 0f
            for (k in -r..r) acc += src[row + k.coerceIn(0, n - 1)]
            for (x in 0 until n) {
                blurTmp[row + x] = acc * inv
                acc += src[row + (x + r + 1).coerceIn(0, n - 1)] -
                    src[row + (x - r).coerceIn(0, n - 1)]
            }
        }
        for (x in 0 until n) {
            var acc = 0f
            for (k in -r..r) acc += blurTmp[k.coerceIn(0, n - 1) * n + x]
            for (y in 0 until n) {
                dst[y * n + x] = acc * inv
                acc += blurTmp[(y + r + 1).coerceIn(0, n - 1) * n + x] -
                    blurTmp[(y - r).coerceIn(0, n - 1) * n + x]
            }
        }
    }

    fun close() {
        try {
            interpreter.close()
        } catch (_: Throwable) {
        }
        try {
            qnnDelegate?.close()
        } catch (_: Throwable) {
        }
    }
}
