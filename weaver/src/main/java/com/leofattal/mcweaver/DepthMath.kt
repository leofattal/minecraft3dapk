package com.leofattal.mcweaver

/**
 * Pure depth-map math, extracted from [DepthEngine] so it can be unit
 * tested without a device or an interpreter: robust (percentile)
 * normalization, the max filter that keeps foreground silhouettes from
 * eroding, and the smoothing box blur. No Android dependencies.
 */
object DepthMath {

    /**
     * Smallest histogram bin whose cumulative count reaches [frac] of
     * [total] samples (e.g. frac = 0.02 → the 2nd percentile). Unlike a
     * plain min/max this ignores outliers: one near object or one far
     * sky pixel cannot move the scale of everything else in the frame.
     */
    fun percentileBin(hist: IntArray, total: Int, frac: Float): Int {
        val target = (total * frac).let {
            if (it <= 0f) 1 else Math.round(it).coerceIn(1, total)
        }
        var cum = 0
        for (b in hist.indices) {
            cum += hist[b]
            if (cum >= target) return b
        }
        return hist.lastIndex
    }

    /**
     * Map [src] from lo..hi into 0..1 (clamped), writing [dst]; src and
     * dst may be the same array. A degenerate range falls back to a span
     * of 1 so the output stays finite.
     */
    fun rescale(src: FloatArray, dst: FloatArray, lo: Float, hi: Float) {
        val range = if (hi - lo < 1e-6f) 1f else hi - lo
        for (i in src.indices) {
            dst[i] = ((src[i] - lo) / range).coerceIn(0f, 1f)
        }
    }

    /**
     * Max filter (dilation) over a (2r+1)² neighborhood, edges clamped
     * (edge samples repeat). Max is separable: a horizontal pass into
     * [tmp], then a vertical pass into [dst]. Dilating the near map
     * before blurring keeps foreground silhouettes at (slightly beyond)
     * their true extent, so the blur cannot pull foreground depth onto
     * the background at object edges. [src], [tmp] and [dst] must be
     * distinct n×n arrays.
     */
    fun dilate(src: FloatArray, dst: FloatArray, tmp: FloatArray, n: Int, r: Int) {
        for (y in 0 until n) {
            val row = y * n
            for (x in 0 until n) {
                var m = src[row + x]
                for (k in -r..r) {
                    val v = src[row + (x + k).coerceIn(0, n - 1)]
                    if (v > m) m = v
                }
                tmp[row + x] = m
            }
        }
        for (y in 0 until n) {
            val row = y * n
            for (x in 0 until n) {
                var m = tmp[row + x]
                for (k in -r..r) {
                    val v = tmp[(y + k).coerceIn(0, n - 1) * n + x]
                    if (v > m) m = v
                }
                dst[row + x] = m
            }
        }
    }

    /**
     * Separable box blur (sliding-window moving average, edges clamped
     * with repeated samples) — identical semantics to the original
     * DepthEngine blur, parameterized. [src], [tmp] and [dst] must be
     * distinct n×n arrays.
     */
    fun boxBlur(src: FloatArray, dst: FloatArray, tmp: FloatArray, n: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        for (y in 0 until n) {
            val row = y * n
            var acc = 0f
            for (k in -r..r) acc += src[row + k.coerceIn(0, n - 1)]
            for (x in 0 until n) {
                tmp[row + x] = acc * inv
                acc += src[row + (x + r + 1).coerceIn(0, n - 1)] -
                    src[row + (x - r).coerceIn(0, n - 1)]
            }
        }
        for (x in 0 until n) {
            var acc = 0f
            for (k in -r..r) acc += tmp[k.coerceIn(0, n - 1) * n + x]
            for (y in 0 until n) {
                dst[y * n + x] = acc * inv
                acc += tmp[(y + r + 1).coerceIn(0, n - 1) * n + x] -
                    tmp[(y - r).coerceIn(0, n - 1) * n + x]
            }
        }
    }
}
