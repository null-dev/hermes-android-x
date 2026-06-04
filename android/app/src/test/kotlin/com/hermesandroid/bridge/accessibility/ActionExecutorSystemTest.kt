package com.hermesandroid.bridge.accessibility

import com.hermesandroid.bridge.command.Command
import com.hermesandroid.bridge.command.CommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class SystemActions(
    val launchOk: Boolean = true,
    val fg: String? = "com.foo",
    val apps: List<Pair<String, String>> = listOf("Foo" to "com.foo"),
) : AccessibilityActions {
    var pressed: Int? = null
    var launched: String? = null
    override suspend fun tapAt(x: Int, y: Int) = true
    override fun nodeCenterById(id: String): Pair<Int, Int>? = null
    override fun setFocusedText(text: String, clearFirst: Boolean) = true
    override fun readTree(includeBounds: Boolean): ScreenNode? = null
    override suspend fun longPressAt(x: Int, y: Int, durationMs: Long) = true
    override suspend fun dragPath(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) = true
    override suspend fun pinchAt(x: Int, y: Int, scale: Double) = true
    override suspend fun swipePath(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) = true
    override fun pressGlobal(action: Int): Boolean { pressed = action; return true }
    override fun launchApp(packageName: String): Boolean { launched = packageName; return launchOk }
    override fun foregroundPackage(): String? = fg
    override fun installedApps(): List<Pair<String, String>> = apps
    override suspend fun takeScreenshotPng(): ByteArray? = null
}

class ActionExecutorSystemTest {
    @Test fun pressKeyMapsAndDispatches() {
        val a = SystemActions()
        val r = ActionExecutor(a).pressKey(Command.PressKey("back"))
        assertTrue(r is CommandResult.Ok)
        assertEquals(KeyMap.globalAction("back"), a.pressed)
    }
    @Test fun pressUnknownKeyErrors() {
        val r = ActionExecutor(SystemActions()).pressKey(Command.PressKey("nope"))
        assertEquals("unknown_key", (r as CommandResult.Err).error)
    }
    @Test fun openAppLaunches() {
        val a = SystemActions(launchOk = true)
        val r = ActionExecutor(a).openApp(Command.OpenApp("com.foo"))
        assertTrue(r is CommandResult.Ok); assertEquals("com.foo", a.launched)
    }
    @Test fun openMissingAppErrors() {
        val r = ActionExecutor(SystemActions(launchOk = false)).openApp(Command.OpenApp("com.x"))
        assertEquals("app_not_found", (r as CommandResult.Err).error)
    }
    @Test fun currentAppReturnsPackage() {
        val r = ActionExecutor(SystemActions(fg = "com.bar")).currentApp()
        assertEquals("com.bar", ((r as CommandResult.Ok).data as Map<*, *>)["package"])
    }
    @Test fun getAppsReturnsList() {
        val r = ActionExecutor(SystemActions()).getApps()
        assertEquals(1, (((r as CommandResult.Ok).data as Map<*, *>)["apps"] as List<*>).size)
    }
}
