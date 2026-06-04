package com.hermesandroid.bridge.accessibility

import com.hermesandroid.bridge.command.Command
import com.hermesandroid.bridge.command.CommandResult
import org.junit.Assert.assertEquals
import org.junit.Test

private fun fixedTreeActions(tree: ScreenNode) = object : AccessibilityActions {
    override suspend fun tapAt(x: Int, y: Int) = true
    override fun nodeCenterById(id: String): Pair<Int, Int>? = null
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
}

class ActionExecutorHashTest {
    private val tree = ScreenNode("0", "hello", null, "T", null, false, NodeBounds(0, 0, 10, 10), emptyList())

    @Test fun screenHashReturnsHash() {
        val r = ActionExecutor(fixedTreeActions(tree)).screenHash()
        val hash = ((r as CommandResult.Ok).data as Map<*, *>)["hash"] as String
        assertEquals(ScreenHash.hash(tree), hash)
    }

    @Test fun diffSameHashReportsNoChange() {
        val current = ScreenHash.hash(tree)
        val r = ActionExecutor(fixedTreeActions(tree)).diffScreen(Command.DiffScreen(current))
        assertEquals(false, ((r as CommandResult.Ok).data as Map<*, *>)["changed"])
    }

    @Test fun diffDifferentHashReportsChange() {
        val r = ActionExecutor(fixedTreeActions(tree)).diffScreen(Command.DiffScreen("deadbeef"))
        assertEquals(true, ((r as CommandResult.Ok).data as Map<*, *>)["changed"])
    }
}
