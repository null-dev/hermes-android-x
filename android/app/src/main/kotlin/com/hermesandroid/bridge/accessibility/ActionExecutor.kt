package com.hermesandroid.bridge.accessibility

import com.hermesandroid.bridge.command.Command
import com.hermesandroid.bridge.command.CommandResult

/** The Android-side actions ActionExecutor needs; real impl lives in the service. */
interface AccessibilityActions {
    suspend fun tapAt(x: Int, y: Int): Boolean
    fun nodeCenterById(id: String): Pair<Int, Int>?
    fun setFocusedText(text: String, clearFirst: Boolean): Boolean

    // Added in Plan 2:
    /** Snapshot of the current screen, or null if the service can't read it. */
    fun readTree(includeBounds: Boolean): ScreenNode?
    /** Long-press gesture at a point. */
    suspend fun longPressAt(x: Int, y: Int, durationMs: Long): Boolean
    /** Linear drag between two points over [durationMs]. */
    suspend fun dragPath(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean
    /** Two-finger pinch centered at (x,y); scale<1 zooms out, >1 zooms in. */
    suspend fun pinchAt(x: Int, y: Int, scale: Double): Boolean
    /** A directional swipe between two points. */
    suspend fun swipePath(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean
    /** Press a global/system key; returns false if the action isn't available. */
    fun pressGlobal(action: Int): Boolean
    /** Launch an app by package name; false if not installed. */
    fun launchApp(packageName: String): Boolean
    /** Package name of the foreground app, or null. */
    fun foregroundPackage(): String?
    /** Installed launchable apps as (label, package). */
    fun installedApps(): List<Pair<String, String>>
}

/** Pure command logic for tap/type, delegating Android specifics to [actions]. */
class ActionExecutor(private val actions: AccessibilityActions) {

    suspend fun tap(cmd: Command.Tap): CommandResult {
        val point: Pair<Int, Int> = when {
            cmd.x != null && cmd.y != null -> cmd.x to cmd.y
            cmd.nodeId != null -> actions.nodeCenterById(cmd.nodeId)
                ?: return CommandResult.Err("stale_node", "node ${cmd.nodeId} not on current screen")
            else -> return CommandResult.Err("bad_request", "tap requires (x,y) or node_id")
        }
        return if (actions.tapAt(point.first, point.second)) {
            CommandResult.Ok(mapOf("tapped" to listOf(point.first, point.second)))
        } else {
            CommandResult.Err("gesture_failed", "tap gesture was not dispatched")
        }
    }

    fun type(cmd: Command.Type): CommandResult =
        if (actions.setFocusedText(cmd.text, cmd.clearFirst)) {
            CommandResult.Ok(mapOf("typed_chars" to cmd.text.length))
        } else {
            CommandResult.Err("no_focused_field", "No input field is focused")
        }
}
