package com.hermesandroid.bridge.accessibility

import com.hermesandroid.bridge.command.Command
import com.hermesandroid.bridge.command.CommandResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorTest {

    private class FakeActions(
        val tapResult: Boolean = true,
        val center: Pair<Int, Int>? = null,
        val focusedField: Boolean = true,
    ) : AccessibilityActions {
        var tappedAt: Pair<Int, Int>? = null
        var typed: String? = null
        override suspend fun tapAt(x: Int, y: Int): Boolean { tappedAt = x to y; return tapResult }
        override fun nodeCenterById(id: String): Pair<Int, Int>? = center
        override fun setFocusedText(text: String, clearFirst: Boolean): Boolean {
            if (!focusedField) return false
            typed = text; return true
        }
        override fun readTree(includeBounds: Boolean): ScreenNode? = null
        override suspend fun longPressAt(x: Int, y: Int, durationMs: Long) = true
        override suspend fun dragPath(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) = true
        override suspend fun pinchAt(x: Int, y: Int, scale: Double) = true
        override suspend fun swipePath(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) = true
        override fun pressGlobal(action: Int) = true
        override fun launchApp(packageName: String) = true
        override fun foregroundPackage(): String? = null
        override fun installedApps(): List<Pair<String, String>> = emptyList()
        override suspend fun takeScreenshotPng(): ByteArray? = null
    }

    @Test
    fun tapByCoordinateDispatchesGesture() = runTest {
        val actions = FakeActions(tapResult = true)
        val r = ActionExecutor(actions).tap(Command.Tap(x = 100, y = 200, nodeId = null))
        assertTrue(r is CommandResult.Ok)
        assertEquals(100 to 200, actions.tappedAt)
    }

    @Test
    fun tapByStaleNodeIdReturnsError() = runTest {
        val actions = FakeActions(center = null)
        val r = ActionExecutor(actions).tap(Command.Tap(x = null, y = null, nodeId = "0.3"))
        assertTrue(r is CommandResult.Err)
        assertEquals("stale_node", (r as CommandResult.Err).error)
    }

    @Test
    fun tapWithNeitherCoordNorNodeIsBadRequest() = runTest {
        val r = ActionExecutor(FakeActions()).tap(Command.Tap(null, null, null))
        assertEquals("bad_request", (r as CommandResult.Err).error)
    }

    @Test
    fun typeIntoFocusedFieldSucceeds() {
        val actions = FakeActions(focusedField = true)
        val r = ActionExecutor(actions).type(Command.Type("hello", clearFirst = false))
        assertTrue(r is CommandResult.Ok)
        assertEquals("hello", actions.typed)
    }

    @Test
    fun typeWithNoFocusedFieldFailsLoudly() {
        val r = ActionExecutor(FakeActions(focusedField = false)).type(Command.Type("hi", false))
        assertTrue(r is CommandResult.Err)
        assertEquals("no_focused_field", (r as CommandResult.Err).error)
    }
}
