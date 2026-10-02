package com.leofattal.mcweaver

/**
 * Pure helpers for the 3D video player: aspect-preserving letterboxing,
 * rotation-aware aspect, and the initial stereo-format guess. Unit-tested.
 */
object VideoFit {

    /**
     * uv rect (u0, v0, u1, v1) that fits a [videoAspect] (w/h) inside a
     * [frameAspect] frame, centered, with bars on the other axis.
     */
    fun uvRect(videoAspect: Float, frameAspect: Float): FloatArray {
        return if (videoAspect > frameAspect) {
            val h = frameAspect / videoAspect
            floatArrayOf(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
        } else {
            val w = videoAspect / frameAspect
            floatArrayOf((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
        }
    }

    /** Effective (post-rotation) display aspect of a video track. */
    fun effectiveAspect(w: Int, h: Int, rotationDeg: Int): Float =
        if (rotationDeg == 90 || rotationDeg == 270) h.toFloat() / w.toFloat()
        else w.toFloat() / h.toFloat()

    /**
     * Initial render-mode guess from the track aspect. Only wide SBS rips
     * (per-eye 16:9 → full frame ≈ 3.56) are safely distinguishable from
     * ordinary content: 21:9 scope movies (2.33) must NOT be misdetected,
     * and top-bottom files (~0.9) overlap ordinary portrait-ish video, so
     * everything else starts as AI depth and the user switches to SBS/TB
     * from the mode picker.
     */
    fun guessMode(aspect: Float): Int =
        if (aspect >= 2.9f) StereoRenderer.MODE_SBS else StereoRenderer.MODE_DIBR
}
