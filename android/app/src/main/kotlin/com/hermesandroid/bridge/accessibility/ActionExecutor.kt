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

    private fun resolvePoint(x: Int?, y: Int?, nodeId: String?): Pair<Int, Int>? = when {
        x != null && y != null -> x to y
        nodeId != null -> actions.nodeCenterById(nodeId)
        else -> null
    }

    suspend fun longPress(cmd: Command.LongPress): CommandResult {
        val p = resolvePoint(cmd.x, cmd.y, cmd.nodeId)
            ?: return CommandResult.Err("bad_request", "long_press needs (x,y) or node_id")
        return if (actions.longPressAt(p.first, p.second, cmd.durationMs)) CommandResult.Ok(mapOf("long_pressed" to listOf(p.first, p.second)))
        else CommandResult.Err("gesture_failed", "long-press not dispatched")
    }

    suspend fun drag(cmd: Command.Drag): CommandResult =
        if (actions.dragPath(cmd.fromX, cmd.fromY, cmd.toX, cmd.toY, cmd.durationMs)) CommandResult.Ok(mapOf("dragged" to true))
        else CommandResult.Err("gesture_failed", "drag not dispatched")

    suspend fun pinch(cmd: Command.Pinch): CommandResult =
        if (actions.pinchAt(cmd.x, cmd.y, cmd.scale)) CommandResult.Ok(mapOf("pinched" to cmd.scale))
        else CommandResult.Err("gesture_failed", "pinch not dispatched")

    suspend fun swipe(cmd: Command.Swipe): CommandResult {
        val bounds = actions.readTree(true)?.bounds ?: return CommandResult.ServiceUnavailable
        val (from, to) = GestureMath.swipe(bounds, cmd.direction, cmd.distanceFraction)
        return if (actions.swipePath(from.first, from.second, to.first, to.second, 250)) CommandResult.Ok(mapOf("swiped" to cmd.direction.name))
        else CommandResult.Err("gesture_failed", "swipe not dispatched")
    }

    suspend fun scroll(cmd: Command.Scroll): CommandResult {
        val tree = actions.readTree(true) ?: return CommandResult.ServiceUnavailable
        val bounds = if (cmd.nodeId != null) {
            NodeSearch.byId(tree, cmd.nodeId)?.bounds
                ?: return CommandResult.Err("stale_node", "node ${cmd.nodeId} not on current screen")
        } else tree.bounds
        // Scrolling toward a direction means swiping content the opposite way.
        val swipeDir = when (cmd.direction) {
            Direction.DOWN -> Direction.UP
            Direction.UP -> Direction.DOWN
            Direction.LEFT -> Direction.RIGHT
            Direction.RIGHT -> Direction.LEFT
        }
        val (from, to) = GestureMath.swipe(bounds, swipeDir, 0.6)
        return if (actions.swipePath(from.first, from.second, to.first, to.second, 250)) CommandResult.Ok(mapOf("scrolled" to cmd.direction.name))
        else CommandResult.Err("gesture_failed", "scroll not dispatched")
    }

    suspend fun tapText(cmd: Command.TapText): CommandResult {
        val tree = actions.readTree(true) ?: return CommandResult.ServiceUnavailable
        val match = NodeSearch.byText(tree, cmd.text, cmd.exact).firstOrNull()
            ?: return CommandResult.Err("text_not_found", "no node with text '${cmd.text}'")
        val (x, y) = match.center()
        return if (actions.tapAt(x, y)) CommandResult.Ok(mapOf("tapped_node" to match.id))
        else CommandResult.Err("gesture_failed", "tap not dispatched")
    }

    fun findNodes(cmd: Command.FindNodes): CommandResult {
        val tree = actions.readTree(true) ?: return CommandResult.ServiceUnavailable
        val hits = NodeSearch.matching(tree, cmd.text, cmd.className, cmd.clickableOnly)
        return CommandResult.Ok(mapOf("nodes" to hits))
    }

    fun describeNode(cmd: Command.DescribeNode): CommandResult {
        val tree = actions.readTree(true) ?: return CommandResult.ServiceUnavailable
        val node = NodeSearch.byId(tree, cmd.nodeId)
            ?: return CommandResult.Err("stale_node", "node ${cmd.nodeId} not on current screen")
        return CommandResult.Ok(node)
    }
}
