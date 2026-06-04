package com.hermesandroid.bridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.ByteArrayOutputStream
import com.hermesandroid.bridge.command.Command
import com.hermesandroid.bridge.command.CommandExecutor
import com.hermesandroid.bridge.command.CommandResult
import com.hermesandroid.bridge.lifecycle.WakeLockManager
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
    private val wakeLocks by lazy { WakeLockManager(this) }
    private val systemController by lazy {
        com.hermesandroid.bridge.system.SystemController(
            com.hermesandroid.bridge.system.AndroidSystemServices(this)
        )
    }

    /** Per-command timeout (ms). Generous enough for slow UIs; bounded so the queue drains. */
    private val executor = CommandExecutor(scope, timeoutMs = 25_000, handler = ::handle)

    override fun onServiceConnected() {
        super.onServiceConnected()
        ref.set(this)
        systemController.warmUpTts()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        com.hermesandroid.bridge.event.EventBus.events.append(
            com.hermesandroid.bridge.event.EventRecord(
                seq = 0,
                timestamp = System.currentTimeMillis(),
                type = AccessibilityEvent.eventTypeToString(e.eventType),
                packageName = e.packageName?.toString(),
                text = e.text?.joinToString(" ")?.ifBlank { null },
            )
        )
    }
    override fun onInterrupt() { }

    override fun onDestroy() {
        ref.compareAndSet(this, null)
        executor.close()
        scope.cancel()
        dispatcher.close()
        super.onDestroy()
    }

    /** Public entry point the HTTP server calls. */
    suspend fun submit(command: Command): CommandResult =
        wakeLocks.around { executor.submit(command) }

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
                val tree = readScreen(command.includeBounds, command.includeSystemUi)
                    ?: return CommandResult.ServiceUnavailable
                CommandResult.Ok(tree)
            }
            is Command.Tap -> actionExecutor.tap(command)
            is Command.Type -> actionExecutor.type(command)
            is Command.LongPress -> actionExecutor.longPress(command)
            is Command.Drag -> actionExecutor.drag(command)
            is Command.Pinch -> actionExecutor.pinch(command)
            is Command.Swipe -> actionExecutor.swipe(command)
            is Command.Scroll -> actionExecutor.scroll(command)
            is Command.TapText -> actionExecutor.tapText(command)
            is Command.FindNodes -> actionExecutor.findNodes(command)
            is Command.DescribeNode -> actionExecutor.describeNode(command)
            is Command.ScreenHashCmd -> actionExecutor.screenHash()
            is Command.DiffScreen -> actionExecutor.diffScreen(command)
            is Command.OpenApp -> actionExecutor.openApp(command)
            is Command.PressKey -> actionExecutor.pressKey(command)
            is Command.CurrentApp -> actionExecutor.currentApp()
            is Command.GetApps -> actionExecutor.getApps()
            is Command.Wait -> actionExecutor.waitFor(command)
            is Command.Screenshot -> {
                val png = takeScreenshotPng()
                if (png == null) CommandResult.Err("screenshot_failed", "could not capture screen")
                else CommandResult.Ok(mapOf("png_base64" to java.util.Base64.getEncoder().encodeToString(png)))
            }
            is Command.ScreenRecord -> {
                val projection = com.hermesandroid.bridge.lifecycle.MediaProjectionHolder.acquire(this)
                if (projection == null) {
                    CommandResult.Err("screen_record_unavailable", "grant screen recording in the app first")
                } else {
                    val mp4 = com.hermesandroid.bridge.capture.ScreenRecorder(this).record(projection, command.durationMs)
                    if (mp4 == null) CommandResult.Err("screen_record_failed", "recording failed")
                    else CommandResult.Ok(mapOf("mp4_base64" to java.util.Base64.getEncoder().encodeToString(mp4)))
                }
            }
            is Command.ClipboardRead -> systemController.clipboardRead()
            is Command.ClipboardWrite -> systemController.clipboardWrite(command)
            is Command.SendIntent -> systemController.sendIntent(command)
            is Command.Broadcast -> systemController.broadcast(command)
            is Command.SendSms -> systemController.sendSms(command)
            is Command.Call -> systemController.call(command)
            is Command.SearchContacts -> systemController.searchContacts(command)
            is Command.GetLocation -> systemController.location()
            is Command.MediaControl -> systemController.media(command)
            is Command.Speak -> systemController.speak(command)
            is Command.SpeakStop -> systemController.stopSpeaking()
            is Command.Notifications -> CommandResult.Ok(mapOf("notifications" to
                com.hermesandroid.bridge.event.EventBus.notifications.snapshot().map {
                    mapOf("package" to it.packageName, "title" to it.title, "text" to it.text, "posted_at" to it.postedAt)
                }))
            is Command.Events -> CommandResult.Ok(mapOf("events" to
                com.hermesandroid.bridge.event.EventBus.events.since(command.since).map {
                    mapOf("seq" to it.seq, "type" to it.type, "package" to it.packageName, "text" to it.text, "timestamp" to it.timestamp)
                }))
            is Command.Widgets -> {
                val mgr = android.appwidget.AppWidgetManager.getInstance(this)
                val list = try {
                    mgr.installedProviders.map {
                        mapOf("label" to it.loadLabel(packageManager), "package" to it.provider.packageName)
                    }
                } catch (e: Exception) { emptyList() }
                CommandResult.Ok(mapOf("widgets" to list))
            }
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

    override fun readTree(includeBounds: Boolean): ScreenNode? {
        val root = rootInActiveWindow ?: return null
        return ScreenReader.read(AndroidNodeView(root), includeBounds)
    }

    private fun readScreen(includeBounds: Boolean, includeSystemUi: Boolean): ScreenNode? {
        val currentWindows = windows
        return withWindows(currentWindows) {
            val candidateWindows = screenWindows(currentWindows)
            val selectedWindows = ScreenWindowSelector.select(candidateWindows, includeSystemUi)
            val selectedSet = selectedWindows.toSet()
            candidateWindows
                .filterNot { it in selectedSet }
                .forEach { it.root?.recycle() }

            if (selectedWindows.isNotEmpty()) {
                return@withWindows ScreenReader.readWindows(selectedWindows, includeBounds)
            }

            val root = rootInActiveWindow ?: return@withWindows null
            ScreenReader.read(AndroidNodeView(root), includeBounds)
        }
    }

    private fun screenWindows(windows: List<AccessibilityWindowInfo>): List<ScreenWindow> =
        windows.map { window ->
            val root = window.root
            ScreenWindow(
                type = when (window.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> ScreenWindowType.APPLICATION
                    AccessibilityWindowInfo.TYPE_SYSTEM,
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> ScreenWindowType.SYSTEM
                    else -> ScreenWindowType.OTHER
                },
                isActive = window.isActive,
                isFocused = window.isFocused,
                layer = window.layer,
                title = window.title?.toString(),
                rootPackageName = root?.packageName?.toString(),
                root = root?.let { AndroidNodeView(it) },
            )
        }

    override suspend fun longPressAt(x: Int, y: Int, durationMs: Long): Boolean =
        dispatchPath(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 0, durationMs)

    override suspend fun dragPath(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean =
        dispatchPath(Path().apply { moveTo(fromX.toFloat(), fromY.toFloat()); lineTo(toX.toFloat(), toY.toFloat()) }, 0, durationMs)

    override suspend fun swipePath(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean =
        dispatchPath(Path().apply { moveTo(fromX.toFloat(), fromY.toFloat()); lineTo(toX.toFloat(), toY.toFloat()) }, 0, durationMs)

    override suspend fun pinchAt(x: Int, y: Int, scale: Double): Boolean = suspendCancellableCoroutine { cont ->
        val span = 200
        val end = (span * scale).toInt().coerceAtLeast(1)
        fun line(startOff: Int, endOff: Int) = Path().apply {
            moveTo((x + startOff).toFloat(), y.toFloat()); lineTo((x + endOff).toFloat(), y.toFloat())
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(line(-span, -end), 0, 300))
            .addStroke(GestureDescription.StrokeDescription(line(span, end), 0, 300))
            .build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }

    override fun pressGlobal(action: Int): Boolean = performGlobalAction(action)

    override fun launchApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }

    override fun foregroundPackage(): String? = rootInActiveWindow?.let {
        @Suppress("DEPRECATION") val pkg = it.packageName?.toString(); it.recycle(); pkg
    }

    override fun installedApps(): List<Pair<String, String>> {
        val pm = packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).map {
            (it.loadLabel(pm)?.toString() ?: it.activityInfo.packageName) to it.activityInfo.packageName
        }.distinctBy { it.second }
    }

    private suspend fun dispatchPath(path: Path, startTime: Long, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, startTime, durationMs))
                .build()
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }

    override suspend fun takeScreenshotPng(): ByteArray? = suspendCancellableCoroutine { cont ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            java.util.concurrent.Executors.newSingleThreadExecutor(),
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bytes = try {
                        val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val out = ByteArrayOutputStream()
                        bitmap?.compress(Bitmap.CompressFormat.PNG, 100, out)
                        bitmap?.recycle()
                        out.toByteArray()
                    } catch (e: Exception) {
                        null
                    } finally {
                        result.hardwareBuffer.close()
                    }
                    if (cont.isActive) cont.resume(bytes)
                }
                override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
            },
        )
    }

    companion object {
        private val ref = AtomicReference<BridgeAccessibilityService?>(null)
        /** Current connected service, or null if accessibility is off / mid-restart. */
        fun current(): BridgeAccessibilityService? = ref.get()
    }
}
