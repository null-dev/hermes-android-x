package com.hermesandroid.bridge.accessibility

import com.hermesandroid.bridge.command.Command
import com.hermesandroid.bridge.command.CommandResult

/** The Android-side actions ActionExecutor needs; real impl lives in the service. */
interface AccessibilityActions {
    suspend fun tapAt(x: Int, y: Int): Boolean
    /** Center of the node at [id] on the current tree, or null if it's no longer there. */
    fun nodeCenterById(id: String): Pair<Int, Int>?
    /** Set text on the focused editable; false if nothing editable is focused. */
    fun setFocusedText(text: String, clearFirst: Boolean): Boolean
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
