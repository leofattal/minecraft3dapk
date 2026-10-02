package com.leofattal.mcweaver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the 3D video player's letterbox/aspect/guess math. */
class VideoFitTest {

    @Test
    fun wideVideoLetterboxesVertically() {
        // 16:9 video in a 16:10 frame: full width, 5% bars top and bottom
        val r = VideoFit.uvRect(16f / 9f, 16f / 10f)
        assertEquals(0f, r[0], 1e-6f)
        assertEquals(1f, r[2], 1e-6f)
        assertEquals(0.05f, r[1], 1e-4f)
        assertEquals(0.95f, r[3], 1e-4f)
    }

    @Test
    fun narrowVideoPillarboxesHorizontally() {
        // 4:3 video in a 16:9 frame: full height, 12.5% bars left and right
        val r = VideoFit.uvRect(4f / 3f, 16f / 9f)
        assertEquals(0.125f, r[0], 1e-4f)
        assertEquals(0.875f, r[2], 1e-4f)
        assertEquals(0f, r[1], 1e-6f)
        assertEquals(1f, r[3], 1e-6f)
    }

    @Test
    fun exactAspectFillsTheFrame() {
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f),
            VideoFit.uvRect(1.6f, 1.6f), 1e-6f)
    }

    @Test
    fun rotationSwapsAspect() {
        assertEquals(1080f / 1920f, VideoFit.effectiveAspect(1920, 1080, 90), 1e-6f)
        assertEquals(1080f / 1920f, VideoFit.effectiveAspect(1920, 1080, 270), 1e-6f)
        assertEquals(1920f / 1080f, VideoFit.effectiveAspect(1920, 1080, 0), 1e-6f)
        assertEquals(1920f / 1080f, VideoFit.effectiveAspect(1920, 1080, 180), 1e-6f)
    }

    @Test
    fun onlyWideSbsRipsAreGuessedAsSbs() {
        // full-frame SBS with 16:9 per-eye halves = 32:9
        assertEquals(StereoRenderer.MODE_SBS, VideoFit.guessMode(3840f / 1080f))
        // an ordinary 21:9 scope movie must stay AI depth
        assertEquals(StereoRenderer.MODE_DIBR, VideoFit.guessMode(21f / 9f))
        assertEquals(StereoRenderer.MODE_DIBR, VideoFit.guessMode(16f / 9f))
        // a top-bottom pair is not auto-guessable
        assertEquals(StereoRenderer.MODE_DIBR, VideoFit.guessMode(1920f / 2160f))
    }
}
