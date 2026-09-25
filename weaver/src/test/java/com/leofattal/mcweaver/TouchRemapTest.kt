package com.leofattal.mcweaver

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the panel → virtual-display touch mapping, including the
 *  safe-area letterbox that mirrors the DIBR shader. */
class TouchRemapTest {

    private val panelW = 1600
    private val panelH = 2560
    private val captureW = 800
    private val captureH = 1280

    @Test
    fun cornersAndCenterMapProportionally() {
        val (x0, y0) = TouchRemap.map(0f, 0f, 0, 0, panelW, panelH, captureW, captureH, 0f)
        assertEquals(0f, x0, 1e-3f)
        assertEquals(0f, y0, 1e-3f)

        val (x1, y1) = TouchRemap.map(
            panelW.toFloat(), panelH.toFloat(), 0, 0, panelW, panelH, captureW, captureH, 0f)
        assertEquals(captureW.toFloat(), x1, 1e-3f)
        assertEquals(captureH.toFloat(), y1, 1e-3f)

        val (xc, yc) = TouchRemap.map(800f, 1280f, 0, 0, panelW, panelH, captureW, captureH, 0f)
        assertEquals(400f, xc, 1e-3f)
        assertEquals(640f, yc, 1e-3f)
    }

    @Test
    fun letterboxMarginIsRemovedBeforeScaling() {
        // pad = 0.1: the inner 80% of the panel maps onto the full capture
        val (u, v) = TouchRemap.map(160f, 256f, 0, 0, panelW, panelH, captureW, captureH, 0.1f)
        assertEquals(0f, u, 1e-3f)     // exactly at the letterbox edge
        assertEquals(0f, v, 1e-3f)

        // panel center stays the capture center under any symmetric pad
        val (uc, vc) = TouchRemap.map(800f, 1280f, 0, 0, panelW, panelH, captureW, captureH, 0.1f)
        assertEquals(400f, uc, 1e-3f)
        assertEquals(640f, vc, 1e-3f)
    }

    @Test
    fun outsideTheLetterboxClampsToDisplayEdges() {
        val (u, v) = TouchRemap.map(0f, panelH.toFloat(), 0, 0, panelW, panelH, captureW, captureH, 0.1f)
        assertEquals(0f, u, 1e-3f)
        assertEquals(captureH.toFloat(), v, 1e-3f)
    }

    @Test
    fun windowOffsetIsIncluded() {
        // touch (0,0) in a catcher window offset (100,200) inside the panel
        val (u, v) = TouchRemap.map(0f, 0f, 100, 200, 1700, 2760, captureW, captureH, 0f)
        assertEquals(100f / 1700f * captureW, u, 1e-3f)
        assertEquals(200f / 2760f * captureH, v, 1e-3f)
    }

    @Test
    fun degeneratePadStaysFiniteAndClamped() {
        // pad >= 0.5 leaves no span; the fallback span (1) keeps the math
        // finite and the clamp keeps it on the display — no NaN, no crash
        val (u, v) = TouchRemap.map(800f, 1280f, 0, 0, panelW, panelH, captureW, captureH, 0.9f)
        assertEquals(0f, u, 1e-3f)
        assertEquals(0f, v, 1e-3f)
    }
}
