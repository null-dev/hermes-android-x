package com.hermesandroid.bridge.accessibility

enum class Direction { UP, DOWN, LEFT, RIGHT }

/** Pure coordinate math for swipes/scrolls, centered on a bounds rectangle. */
object GestureMath {
    /** Returns (from, to) points for a swipe in [direction] across [bounds]. */
    fun swipe(
        bounds: NodeBounds,
        direction: Direction,
        distanceFraction: Double = 0.5,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val cx = (bounds.left + bounds.right) / 2
        val cy = (bounds.top + bounds.bottom) / 2
        val w = (bounds.right - bounds.left)
        val h = (bounds.bottom - bounds.top)
        val dx = (w * distanceFraction / 2).toInt()
        val dy = (h * distanceFraction / 2).toInt()
        return when (direction) {
            Direction.UP -> (cx to cy + dy) to (cx to cy - dy)
            Direction.DOWN -> (cx to cy - dy) to (cx to cy + dy)
            Direction.LEFT -> (cx + dx to cy) to (cx - dx to cy)
            Direction.RIGHT -> (cx - dx to cy) to (cx + dx to cy)
        }
    }
}
