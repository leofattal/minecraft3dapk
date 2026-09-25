package com.leofattal.mcweaver

/**
 * Pure touch-remap math, extracted from WeaverService so it can be unit
 * tested: maps a touch from panel space into the virtual display's
 * capture rect, undoing the safe-area letterbox exactly the way the
 * DIBR shader draws it (uSafe), and clamping to the display bounds.
 */
object TouchRemap {

    /**
     * Map a point in panel space (plus the catcher window's offset within
     * the panel) into the capture rect (0,0)..(captureW,captureH).
     * [pad] is the safe-area letterbox fraction each side (0 = fill).
     */
    fun map(x: Float, y: Float, offsetX: Int, offsetY: Int,
            panelW: Int, panelH: Int, captureW: Int, captureH: Int,
            pad: Float): Pair<Float, Float> {
        val span = (1f - 2f * pad).let { if (it <= 0f) 1f else it }
        val u = (((x + offsetX) / panelW - pad) / span).coerceIn(0f, 1f)
        val v = (((y + offsetY) / panelH - pad) / span).coerceIn(0f, 1f)
        return u * captureW to v * captureH
    }
}
