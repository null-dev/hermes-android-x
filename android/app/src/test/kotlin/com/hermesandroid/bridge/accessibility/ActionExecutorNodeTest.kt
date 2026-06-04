package com.hermesandroid.bridge.accessibility

import com.hermesandroid.bridge.command.Command
import com.hermesandroid.bridge.command.CommandResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class NodeActions(private val tree: ScreenNode?) : AccessibilityActions {
    var tapped: Pair<Int, Int>? = null
    override suspend fun tapAt(x: Int, y: Int): Boolean { tapped = x to y; return true }
    override fun nodeCenterById(id: String): Pair<Int, Int>? { val t = tree ?: return null; return NodeSearch.byId(t, id)?.center() }
    override fun setFocusedText(text: String, clearFirst: Boolean) = true
    override fun readTree(includeBounds: Boolean) = tree
    override suspend fun longPressAt(x: Int, y: Int, durationMs: Long) = true
    override suspend fun dragPath(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) = true
    override suspend fun pinchAt(x: Int, y: Int, scale: Double) = true
    override suspend fun swipePath(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) = true
    override fun pressGlobal(action: Int) = true
    override fun launchApp(packageName: String) = true
    override fun foregroundPackage(): String? = null
    override fun installedApps() = emptyList<Pair<String, String>>()
    override suspend fun takeScreenshotPng(): ByteArray? = null
}

private fun btn(id: String, text: String) =
    ScreenNode(id, text, null, "android.widget.Button", null, true, NodeBounds(0, 0, 100, 100), emptyList())

private fun describedNode(id: String, description: String) =
    ScreenNode(id, null, description, "android.widget.ImageButton", null, true, NodeBounds(0, 0, 100, 100), emptyList())

class ActionExecutorNodeTest {
    private val tree = ScreenNode("0", null, null, "Root", null, false,
        NodeBounds(0, 0, 100, 100), listOf(btn("0.0", "OK")))

    @Test fun tapTextTapsCenterOfMatch() = runTest {
        val a = NodeActions(tree)
        val r = ActionExecutor(a).tapText(Command.TapText("ok", exact = true))
        assertTrue(r is CommandResult.Ok)
        assertEquals(50 to 50, a.tapped)
    }

    @Test fun tapTextNoMatchErrors() = runTest {
        val r = ActionExecutor(NodeActions(tree)).tapText(Command.TapText("nope", exact = true))
        assertEquals("text_not_found", (r as CommandResult.Err).error)
    }

    @Test fun findNodesReturnsMatches() = runTest {
        val r = ActionExecutor(NodeActions(tree)).findNodes(Command.FindNodes("OK", null, false))
        val data = (r as CommandResult.Ok).data as Map<*, *>
        assertEquals(1, (data["nodes"] as List<*>).size)
    }

    @Test fun findNodesMatchesContentDescriptions() = runTest {
        val describedTree = ScreenNode("0", null, null, "Root", null, false,
            NodeBounds(0, 0, 100, 100), listOf(describedNode("0.0", "Open settings")))
        val r = ActionExecutor(NodeActions(describedTree)).findNodes(Command.FindNodes("settings", null, false))
        val data = (r as CommandResult.Ok).data as Map<*, *>
        assertEquals(1, (data["nodes"] as List<*>).size)
    }

    @Test fun describeNodeReturnsTheNode() = runTest {
        val r = ActionExecutor(NodeActions(tree)).describeNode(Command.DescribeNode("0.0"))
        assertTrue(r is CommandResult.Ok)
    }

    @Test fun describeMissingNodeErrors() = runTest {
        val r = ActionExecutor(NodeActions(tree)).describeNode(Command.DescribeNode("9.9"))
        assertEquals("stale_node", (r as CommandResult.Err).error)
    }
}
