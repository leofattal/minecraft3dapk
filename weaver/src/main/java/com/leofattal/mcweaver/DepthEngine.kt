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
 * Output: 256x256 float depth in [0,1] (1 = nearest), normalized with
 * temporally smoothed 2nd/98th percentiles (so the scale does not
 * "breathe" when something close enters the frame), dilated at object
 * edges, and lightly blurred for stable stereo warping.
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
        private const val DILATE_RADIUS = 1     // 3x3 max filter before the blur
        private const val SCALE_EMA = 0.10f     // smoothing of the percentile pair
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
    private val dilateOut = FloatArray(SIZE * SIZE)
    private val blurOut = FloatArray(SIZE * SIZE)
    private val hist = IntArray(256)
    private var smLo = Float.NaN
    private var smHi = Float.NaN
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
        java.util.Arrays.fill(hist, 0)
        for (i in 0 until total) {
            val b = outputBuf.get(i).toInt() and 0xFF
            raw[i] = b * OUT_SCALE
            hist[b]++
        }
        // Robust, temporally stable scale: per-frame min/max made the whole
        // scene "breathe" whenever something close entered the frame. The
        // 2nd/98th percentiles ignore such outliers, and EMA-smoothing the
        // pair keeps the scale from jumping frame to frame.
        val lo = DepthMath.percentileBin(hist, total, 0.02f) * OUT_SCALE
        val hi = DepthMath.percentileBin(hist, total, 0.98f) * OUT_SCALE
        if (smLo.isNaN()) {
            smLo = lo
            smHi = hi
        } else {
            smLo += SCALE_EMA * (lo - smLo)
            smHi += SCALE_EMA * (hi - smHi)
        }
        DepthMath.rescale(raw, raw, smLo, smHi)
        depthOut.clear()
        if (!smoothedInit) {
            System.arraycopy(raw, 0, smoothed, 0, total)
            smoothedInit = true
        } else {
            for (i in 0 until total) {
                smoothed[i] += EMA * (raw[i] - smoothed[i])
            }
        }
        // Dilate before blurring: the blur averages foreground depth onto
        // the background at object edges, smearing them; a small max filter
        // keeps near silhouettes at (slightly beyond) their true extent.
        DepthMath.dilate(smoothed, dilateOut, blurTmp, SIZE, DILATE_RADIUS)
        DepthMath.boxBlur(dilateOut, blurOut, blurTmp, SIZE, BLUR_RADIUS)
        for (i in 0 until total) depthOut.put(blurOut[i])
        depthOut.rewind()
        return depthOut
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
