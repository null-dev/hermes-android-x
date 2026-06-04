package com.hermesandroid.bridge.command

/** The closed set of operations the CommandExecutor can run. Extended in later plans. */
sealed interface Command {
    /** Liveness + device info. */
    data object Ping : Command

    /** Read the current accessibility tree. */
    data class ReadScreen(val includeBounds: Boolean) : Command

    /** Tap by absolute coordinate (x,y) or by a node id from a prior ReadScreen. */
    data class Tap(val x: Int?, val y: Int?, val nodeId: String?) : Command

    /** Type into the currently focused input field. */
    data class Type(val text: String, val clearFirst: Boolean) : Command

    data class LongPress(val x: Int?, val y: Int?, val nodeId: String?, val durationMs: Long) : Command
    data class Drag(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val durationMs: Long) : Command
    data class Pinch(val x: Int, val y: Int, val scale: Double) : Command
    data class Swipe(val direction: com.hermesandroid.bridge.accessibility.Direction, val distanceFraction: Double) : Command
    data class Scroll(val direction: com.hermesandroid.bridge.accessibility.Direction, val nodeId: String?) : Command

    data class TapText(val text: String, val exact: Boolean) : Command
    data class FindNodes(val text: String?, val className: String?, val clickableOnly: Boolean) : Command
    data class DescribeNode(val nodeId: String) : Command

    data object ScreenHashCmd : Command
    data class DiffScreen(val previousHash: String) : Command

    data class OpenApp(val packageName: String) : Command
    data class PressKey(val key: String) : Command
    data object CurrentApp : Command
    data object GetApps : Command

    data class Wait(val text: String?, val className: String?, val timeoutMs: Long) : Command

    data object Screenshot : Command

    data class ScreenRecord(val durationMs: Long) : Command
}

/** Typed outcome of running a Command. The server maps these onto HTTP responses. */
sealed class CommandResult {
    /** Action ran and succeeded. [data] is any Gson-serializable value (or null). */
    data class Ok(val data: Any?) : CommandResult()

    /** Action ran and failed for an app-level reason (HTTP 200, envelope ok=false). */
    data class Err(val error: String, val message: String) : CommandResult()

    /** Command exceeded its time budget (HTTP 408). */
    data class Timeout(val message: String) : CommandResult()

    /** Accessibility service is not connected, so no command can run (HTTP 503). */
    data object ServiceUnavailable : CommandResult()
}
