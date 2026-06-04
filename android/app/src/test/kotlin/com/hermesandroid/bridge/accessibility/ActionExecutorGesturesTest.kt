package com.hermesandroid.bridge.accessibility

import com.hermesandroid.bridge.command.Command
import com.hermesandroid.bridge.command.CommandResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class CapturingActions(private val tree: ScreenNode?) : AccessibilityActions {
    var longPress: Triple<Int, Int, Long>? = null
    var drag: List<Int>? = null
    var pinch: Triple<Int, Int, Double>? = null
    var swipe: List<Int>? = null

    override suspend fun tapAt(x: Int, y: Int) = true
    override fun nodeCenterById(id: String): Pair<Int, Int>? {
        if (tree == null) return null
        return NodeSearch.byId(tree, id)?.center()
    }
    override fun setFocusedText(text: String, clearFirst: Boolean) = true
    override fun readTree(includeBounds: Boolean): ScreenNode? = tree
    override suspend fun longPressAt(x: Int, y: Int, durationMs: Long): Boolean { longPress = Triple(x, y, durationMs); return true }
    override suspend fun dragPath(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean { drag = listOf(fromX, fromY, toX, toY); return true }
    override suspend fun pinchAt(x: Int, y: Int, scale: Double): Boolean { pinch = Triple(x, y, scale); return true }
    override suspend fun swipePath(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean { swipe = listOf(fromX, fromY, toX, toY); return true }
    override fun pressGlobal(action: Int) = true
    override fun launchApp(packageName: String) = true
    override fun foregroundPackage(): String? = null
    override fun installedApps(): List<Pair<String, String>> = emptyList()
}

private fun screen(): ScreenNode = ScreenNode(
    "0", null, null, "root", null, false, NodeBounds(0, 0, 1000, 2000), emptyList()
)

class ActionExecutorGesturesTest {

    @Test
    fun longPressByCoordinate() = runTest {
        val a = CapturingActions(screen())
        val r = ActionExecutor(a).longPress(Command.LongPress(10, 20, null, 600))
        assertTrue(r is CommandResult.Ok)
        assertEquals(Triple(10, 20, 600L), a.longPress)
    }

    @Test
    fun dragPassesEndpoints() = runTest {
        val a = CapturingActions(screen())
        ActionExecutor(a).drag(Command.Drag(1, 2, 3, 4, 300))
        assertEquals(listOf(1, 2, 3, 4), a.drag)
    }

    @Test
    fun pinchPassesScale() = runTest {
        val a = CapturingActions(screen())
        ActionExecutor(a).pinch(Command.Pinch(500, 500, 2.0))
        assertEquals(Triple(500, 500, 2.0), a.pinch)
    }

    @Test
    fun swipeUpDecreasesY() = runTest {
        val a = CapturingActions(screen())
        val r = ActionExecutor(a).swipe(Command.Swipe(Direction.UP, 0.5))
        assertTrue(r is CommandResult.Ok)
        val (fromY, toY) = a.swipe!![1] to a.swipe!![3]
        assertTrue("up: toY < fromY", toY < fromY)
    }

    @Test
    fun swipeWithNoScreenIsServiceUnavailable() = runTest {
        val r = ActionExecutor(CapturingActions(null)).swipe(Command.Swipe(Direction.UP, 0.5))
        assertEquals(CommandResult.ServiceUnavailable, r)
    }

    @Test
    fun scrollDownSwipesContentUp() = runTest {
        val a = CapturingActions(screen())
        ActionExecutor(a).scroll(Command.Scroll(Direction.DOWN, null))
        // scroll DOWN reveals lower content => finger swipes UP => toY < fromY
        assertTrue(a.swipe!![3] < a.swipe!![1])
    }

    @Test
    fun scrollStaleNodeErrors() = runTest {
        val r = ActionExecutor(CapturingActions(screen())).scroll(Command.Scroll(Direction.DOWN, "0.9"))
        assertEquals("stale_node", (r as CommandResult.Err).error)
    }
}
