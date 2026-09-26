package com.leofattal.mcweaver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the release-tag vs installed-version comparison used by the
 *  in-app updater. */
class UpdaterTest {

    @Test
    fun newerTagIsNewer() {
        assertTrue(Updater.isNewer("v2.3", "2.2"))
        assertTrue(Updater.isNewer("2.3", "v2.2"))
        assertTrue(Updater.isNewer("v3.0", "2.9"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(Updater.isNewer("v2.2", "2.2"))
        assertFalse(Updater.isNewer("2.2", "v2.2"))
        assertFalse(Updater.isNewer("v2.2.0", "2.2"))
    }

    @Test
    fun segmentsCompareNumerically() {
        // lexicographic comparison would call "2.9" > "2.10"
        assertTrue(Updater.isNewer("v2.10", "2.9"))
        assertFalse(Updater.isNewer("v2.9", "2.10"))
    }

    @Test
    fun missingSegmentsDefaultToZero() {
        assertTrue(Updater.isNewer("2.3.1", "2.3"))     // 2.3.1 > 2.3.0
        assertFalse(Updater.isNewer("2.3", "2.3.1"))    // 2.3.0 < 2.3.1
        assertFalse(Updater.isNewer("", "2.2"))        // 0.0 < 2.2
    }

    @Test
    fun garbageSegmentsDoNotCrash() {
        assertFalse(Updater.isNewer("beta", "1.0"))     // "beta" -> 0
        assertTrue(Updater.isNewer("1.1", "beta"))      // 1.1 > 0
    }
}
