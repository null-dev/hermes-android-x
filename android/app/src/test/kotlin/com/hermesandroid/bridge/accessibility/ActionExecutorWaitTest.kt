package com.hermesandroid.bridge.accessibility

import com.hermesandroid.bridge.command.Command
import com.hermesandroid.bridge.command.CommandResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private class WaitActions(private val appearsOnAttempt: Int) : AccessibilityActions {
    private var attempt = 0
    private val match = ScreenNode("0.0", "Ready", null, "T", null, false, NodeBounds(0, 0, 1, 1), emptyList())
    private val root = ScreenNode("0", null, null, "Root", null, false, NodeBounds(0, 0, 1, 1), emptyList())
    override fun readTree(includeBounds: Boolean): ScreenNode {
        attempt++
        return if (attempt >= appearsOnAttempt)
            ScreenNode("0", null, null, "Root", null, false, NodeBounds(0, 0, 1, 1), listOf(match))
        else root
    }
    override suspend fun tapAt(x: Int, y: Int) = true
    override fun nodeCenterById(id: String): Pair<Int, Int>? = null
    override fun setFocusedText(text: String, clearFirst: Boolean) = true
    override suspend fun longPressAt(x: Int, y: Int, durationMs: Long) = true
    override suspend fun dragPath(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) = true
    override suspend fun pinchAt(x: Int, y: Int, scale: Double) = true
    override suspend fun swipePath(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) = true
    override fun pressGlobal(action: Int) = true
    override fun launchApp(packageName: String) = true
    override fun foregroundPackage(): String? = null
    override fun installedApps() = emptyList<Pair<String, String>>()
}

class ActionExecutorWaitTest {
    @Test fun foundWhenElementAppears() = runTest {
        val r = ActionExecutor(WaitActions(appearsOnAttempt = 2)).waitFor(Command.Wait("Ready", null, 5_000))
        assertEquals(true, ((r as CommandResult.Ok).data as Map<*, *>)["found"])
    }
    @Test fun notFoundWithinTimeout() = runTest {
        val r = ActionExecutor(WaitActions(appearsOnAttempt = 999)).waitFor(Command.Wait("Ready", null, 500))
        assertEquals(false, ((r as CommandResult.Ok).data as Map<*, *>)["found"])
    }
}
