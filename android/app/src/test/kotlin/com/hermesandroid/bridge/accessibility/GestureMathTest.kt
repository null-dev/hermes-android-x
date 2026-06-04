package com.hermesandroid.bridge.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureMathTest {

    private val screen = NodeBounds(0, 0, 1080, 1920) // left,top,right,bottom

    @Test
    fun swipeUpMovesFromLowerToUpper() {
        val (from, to) = GestureMath.swipe(screen, Direction.UP, distanceFraction = 0.5)
        assertEquals(540, from.first)  // horizontally centered
        assertEquals(540, to.first)
        assertTrue("up should decrease y", to.second < from.second)
    }

    @Test
    fun swipeLeftMovesFromRightToLeft() {
        val (from, to) = GestureMath.swipe(screen, Direction.LEFT, distanceFraction = 0.5)
        assertTrue(to.first < from.first)
        assertEquals(960, from.second) // vertically centered
    }

    @Test
    fun distanceFractionScalesTravel() {
        val (f1, t1) = GestureMath.swipe(screen, Direction.UP, 0.25)
        val (f2, t2) = GestureMath.swipe(screen, Direction.UP, 0.75)
        assertTrue((f2.second - t2.second) > (f1.second - t1.second))
    }
}
