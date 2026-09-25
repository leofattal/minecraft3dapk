package com.leofattal.mcweaver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the pure depth math: percentile normalization, dilation,
 *  and the smoothing box blur. */
class DepthMathTest {

    // ------------------------------------------------------------ percentileBin

    @Test
    fun percentileOfUniformHistogramMatchesDefinition() {
        val hist = IntArray(256) { 1 }        // 256 samples, one per bin
        // target = round(256*0.02) = 5 → first bin where cum >= 5 is 4
        assertEquals(4, DepthMath.percentileBin(hist, 256, 0.02f))
        // target = round(256*0.98) = 251 → first bin where cum >= 251 is 250
        assertEquals(250, DepthMath.percentileBin(hist, 256, 0.98f))
    }

    @Test
    fun percentilesIgnoreOutliers() {
        // 100 samples spread over bins 10..19 plus a single far outlier.
        val hist = IntArray(256)
        for (b in 10..19) hist[b] = 10
        hist[200] = 1
        // A min/max scale would stretch hi all the way to bin 200 and
        // flatten the scene; the 98th percentile stays inside the bulk.
        val hi = DepthMath.percentileBin(hist, 101, 0.98f)
        assertTrue("hi=$hi should stay within the bulk bins", hi in 10..19)
    }

    @Test
    fun percentileHandlesTailBeyondLastSample() {
        val hist = IntArray(256)
        hist[0] = 10
        // target 1: answered by bin 0
        assertEquals(0, DepthMath.percentileBin(hist, 10, 0.02f))
    }

    // ---------------------------------------------------------------- rescale

    @Test
    fun rescaleMapsRangeAndClamps() {
        val src = floatArrayOf(0f, 5f, 10f)
        val dst = FloatArray(3)
        DepthMath.rescale(src, dst, 2f, 8f)
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f), dst, 1e-6f)
    }

    @Test
    fun rescaleInPlaceWorks() {
        val a = floatArrayOf(1f, 2f, 3f)
        DepthMath.rescale(a, a, 1f, 3f)
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f), a, 1e-6f)
    }

    @Test
    fun rescaleDegenerateRangeIsFinite() {
        val dst = FloatArray(2)
        DepthMath.rescale(floatArrayOf(3f, 3f), dst, 3f, 3f)
        assertArrayEquals(floatArrayOf(0f, 0f), dst, 1e-6f)
    }

    // ----------------------------------------------------------------- dilate

    @Test
    fun dilateSpreadsBrightPixelToItsNeighborhood() {
        val n = 4
        val src = FloatArray(n * n)
        src[1 * n + 1] = 9f
        val tmp = FloatArray(n * n)
        val dst = FloatArray(n * n)
        DepthMath.dilate(src, dst, tmp, n, 1)
        // every cell within Chebyshev distance 1 of (1,1) picks up the 9
        for (y in 0..2) for (x in 0..2)
            assertEquals(9f, dst[y * n + x], 1e-6f)
        // the far corner is untouched
        assertEquals(0f, dst[3 * n + 3], 1e-6f)
    }

    @Test
    fun dilateDoesNotShrinkUniformRegions() {
        val n = 6
        val src = FloatArray(n * n) { 0.5f }
        val tmp = FloatArray(n * n)
        val dst = FloatArray(n * n)
        DepthMath.dilate(src, dst, tmp, n, 2)
        assertArrayEquals(src, dst, 1e-6f)
    }

    // ---------------------------------------------------------------- boxBlur

    @Test
    fun boxBlurKeepsConstantImage() {
        val n = 5
        val src = FloatArray(n * n) { 2f }
        val tmp = FloatArray(n * n)
        val dst = FloatArray(n * n)
        DepthMath.boxBlur(src, dst, tmp, n, 2)
        assertArrayEquals(src, dst, 1e-6f)
    }

    @Test
    fun boxBlurDistributesACentralImpulseEvenly() {
        val n = 3
        val src = FloatArray(n * n)
        src[1 * n + 1] = 9f
        val tmp = FloatArray(n * n)
        val dst = FloatArray(n * n)
        DepthMath.boxBlur(src, dst, tmp, n, 1)
        // with clamped edges every output ends up a weighted share of the
        // 9; on a 3x3 grid with r=1 that works out to exactly 1 everywhere
        for (i in 0 until n * n) assertEquals(1f, dst[i], 1e-5f)
    }

    @Test
    fun boxBlurRadiusZeroIsIdentity() {
        val n = 4
        val src = FloatArray(n * n) { it.toFloat() % 7f }
        val tmp = FloatArray(n * n)
        val dst = FloatArray(n * n)
        DepthMath.boxBlur(src, dst, tmp, n, 0)
        assertArrayEquals(src, dst, 1e-6f)
    }

    // -------------------------------------------------- dilate-then-blur chain

    @Test
    fun dilatedForegroundSurvivesTheBlur() {
        // A single near pixel (1.0) on a far field (0.0): without dilation
        // the blur erodes it to a faint smear; with dilation first the
        // 3x3 neighborhood stays at full strength before smoothing.
        val n = 3
        val src = FloatArray(n * n)
        src[4] = 1f
        val tmp = FloatArray(n * n)
        val mid = FloatArray(n * n)
        val dst = FloatArray(n * n)
        DepthMath.dilate(src, mid, tmp, n, 1)
        DepthMath.boxBlur(mid, dst, tmp, n, 1)
        // center: max stays 1 after dilate; blur window at center = all 1s
        assertEquals(1f, dst[4], 1e-5f)
        // corner (0,0): dilate gives 1; blur window (clamped) covers 1s → 1
        assertEquals(1f, dst[0], 1e-5f)
        // without the dilate step this would be 1/4 at best
    }
}
