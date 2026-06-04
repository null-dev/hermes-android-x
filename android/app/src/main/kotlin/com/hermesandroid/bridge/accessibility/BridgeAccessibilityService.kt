package com.hermesandroid.bridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.hermesandroid.bridge.command.Command
import com.hermesandroid.bridge.command.CommandExecutor
import com.hermesandroid.bridge.command.CommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

class BridgeAccessibilityService : AccessibilityService(), AccessibilityActions {

    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val actionExecutor = ActionExecutor(this)

    /** Per-command timeout (ms). Generous enough for slow UIs; bounded so the queue drains. */
    private val executor = CommandExecutor(scope, timeoutMs = 25_000, handler = ::handle)

    override fun onServiceConnected() {
        super.onServiceConnected()
        ref.set(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* event handling added in plan 5 */ }
    override fun onInterrupt() { }

    override fun onDestroy() {
        ref.compareAndSet(this, null)
        executor.close()
        scope.cancel()
        dispatcher.close()
        super.onDestroy()
    }

    /** Public entry point the HTTP server calls. */
    suspend fun submit(command: Command): CommandResult = executor.submit(command)

    private suspend fun handle(command: Command): CommandResult {
        return when (command) {
            is Command.Ping -> CommandResult.Ok(
                mapOf(
                    "device" to android.os.Build.MODEL,
                    "android_version" to android.os.Build.VERSION.RELEASE,
                    "service_enabled" to true,
                )
            )
            is Command.ReadScreen -> {
                val root = rootInActiveWindow
                    ?: return CommandResult.ServiceUnavailable
                CommandResult.Ok(ScreenReader.read(AndroidNodeView(root), command.includeBounds))
            }
            is Command.Tap -> actionExecutor.tap(command)
            is Command.Type -> actionExecutor.type(command)
        }
    }

    // --- AccessibilityActions (real Android implementations) ---

    override suspend fun tapAt(x: Int, y: Int): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    override fun nodeCenterById(id: String): Pair<Int, Int>? {
        val parts = id.split(".")
        if (parts.isEmpty() || parts[0] != "0") return null
        var current: AccessibilityNodeInfo = rootInActiveWindow ?: return null
        val toRecycle = mutableListOf(current)
        try {
            for (i in 1 until parts.size) {
                val idx = parts[i].toIntOrNull() ?: return null
                if (idx !in 0 until current.childCount) return null
                val next = current.getChild(idx) ?: return null
                toRecycle.add(next)
                current = next
            }
            val r = Rect()
            current.getBoundsInScreen(r)
            return (r.left + r.right) / 2 to (r.top + r.bottom) / 2
        } finally {
            @Suppress("DEPRECATION")
            toRecycle.forEach { it.recycle() }
        }
    }

    override fun setFocusedText(text: String, clearFirst: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        @Suppress("DEPRECATION")
        try {
            if (focused == null || !focused.isEditable) return false
            val existing = if (clearFirst) "" else (focused.text?.toString() ?: "")
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    existing + text,
                )
            }
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            focused?.recycle()
            root.recycle()
        }
    }

    companion object {
        private val ref = AtomicReference<BridgeAccessibilityService?>(null)
        /** Current connected service, or null if accessibility is off / mid-restart. */
        fun current(): BridgeAccessibilityService? = ref.get()
    }
}
