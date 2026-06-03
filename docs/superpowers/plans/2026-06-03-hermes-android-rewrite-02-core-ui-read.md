# Hermes Android Rewrite — Plan 2/5: Core UI & Read Tools

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the foundation with the full core automation surface: gestures (`long_press`, `drag`, `pinch`, `swipe`, `scroll`), node-targeted actions (`tap_text`, `find_nodes`, `describe_node`), screen-change detection (`screen_hash`, `diff_screen`), navigation/system reads (`open_app`, `press_key`, `current_app`, `get_apps`), and `wait`.

**Architecture:** Same as Plan 1 — every new operation is a `Command` variant handled inside the single `CommandExecutor`. Pure logic (gesture math, tree search, diffing) is unit-tested behind seams; Android specifics go through an extended `AccessibilityActions`.

**Tech Stack:** As Plan 1.

> **Prerequisite:** Plan 1 complete and green. Read `docs/superpowers/specs/2026-06-03-hermes-android-rewrite-design.md` §6 (endpoints).
> **VCS:** jj. Every "Commit" step uses `jj commit -m "..."`.

---

## The 5-layer change pattern (every tool in Plans 2–5 follows this)

Adding a tool touches exactly five places. Each task below shows the real code for all
five — this note just names them so the tasks stay terse:

1. **Command** — add a variant to `sealed interface Command` (`command/Command.kt`).
2. **Handler** — add a `when` branch in `BridgeAccessibilityService.handle(...)`.
3. **Route** — add a Ktor route in `BridgeServer` that parses the body and submits.
4. **Client** — add a method to `AndroidClient` (`plugin/client.py`).
5. **Tool** — add an `android_*` function + a `TOOL_SCHEMAS` entry (`plugin/tools.py`).

The `when` in `handle` is exhaustive over the sealed `Command`, so the compiler forces
you to handle every new variant — a missing branch won't compile.

---

## Task 1: Extend the AccessibilityActions seam

**Files:**
- Modify: `android/app/src/main/kotlin/com/hermesandroid/bridge/accessibility/ActionExecutor.kt`
- Modify: `android/app/src/main/kotlin/com/hermesandroid/bridge/accessibility/BridgeAccessibilityService.kt`
- Modify: `android/app/src/test/kotlin/com/hermesandroid/bridge/accessibility/ActionExecutorTest.kt`
- Create: `android/app/src/main/kotlin/com/hermesandroid/bridge/accessibility/GestureMath.kt`
- Test: `android/app/src/test/kotlin/com/hermesandroid/bridge/accessibility/GestureMathTest.kt`

This task widens the seam the later tasks build on, and keeps Plan 1's tests compiling.

- [ ] **Step 1: Write the failing test for `GestureMath`**

```kotlin
package com.hermesandroid.bridge.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureMathTest {

    private val screen = NodeBounds(0, 0, 1080, 1920) // left,top,right,bottom

    @Test
    fun swipeUpMovesFromLowerToUpper() {
        val (from, to) = GestureMath.swipe(screen, Direction.UP, distanceFraction = 0.5)
        assertEquals(540, from.first)  // horizontally centered
        assertEquals(540, to.first)
        assertTrue("up should decrease y", to.second < from.second)
    }

    @Test
    fun swipeLeftMovesFromRightToLeft() {
        val (from, to) = GestureMath.swipe(screen, Direction.LEFT, distanceFraction = 0.5)
        assertTrue(to.first < from.first)
        assertEquals(960, from.second) // vertically centered
    }

    @Test
    fun distanceFractionScalesTravel() {
        val (f1, t1) = GestureMath.swipe(screen, Direction.UP, 0.25)
        val (f2, t2) = GestureMath.swipe(screen, Direction.UP, 0.75)
        assertTrue((f2.second - t2.second) > (f1.second - t1.second))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*GestureMathTest" && cd ..`
Expected: FAIL — `GestureMath` / `Direction` unresolved.

- [ ] **Step 3: Implement `GestureMath.kt`**

```kotlin
package com.hermesandroid.bridge.accessibility

enum class Direction { UP, DOWN, LEFT, RIGHT }

/** Pure coordinate math for swipes/scrolls, centered on a bounds rectangle. */
object GestureMath {
    /** Returns (from, to) points for a swipe in [direction] across [bounds]. */
    fun swipe(
        bounds: NodeBounds,
        direction: Direction,
        distanceFraction: Double = 0.5,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val cx = (bounds.left + bounds.right) / 2
        val cy = (bounds.top + bounds.bottom) / 2
        val w = (bounds.right - bounds.left)
        val h = (bounds.bottom - bounds.top)
        val dx = (w * distanceFraction / 2).toInt()
        val dy = (h * distanceFraction / 2).toInt()
        return when (direction) {
            Direction.UP -> (cx to cy + dy) to (cx to cy - dy)
            Direction.DOWN -> (cx to cy - dy) to (cx to cy + dy)
            Direction.LEFT -> (cx + dx to cy) to (cx - dx to cy)
            Direction.RIGHT -> (cx - dx to cy) to (cx + dx to cy)
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*GestureMathTest" && cd ..`
Expected: PASS (3 tests).

- [ ] **Step 5: Widen `AccessibilityActions`** in `ActionExecutor.kt`

Replace the `interface AccessibilityActions { ... }` block with:

```kotlin
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
```

- [ ] **Step 6: Update Plan 1's `ActionExecutorTest` `FakeActions`** so it still compiles — add the new members with simple stubs:

```kotlin
// inside FakeActions, add:
override fun readTree(includeBounds: Boolean): ScreenNode? = null
override suspend fun longPressAt(x: Int, y: Int, durationMs: Long) = true
override suspend fun dragPath(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) = true
override suspend fun pinchAt(x: Int, y: Int, scale: Double) = true
override suspend fun swipePath(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) = true
override fun pressGlobal(action: Int) = true
override fun launchApp(packageName: String) = true
override fun foregroundPackage(): String? = null
override fun installedApps(): List<Pair<String, String>> = emptyList()
```

- [ ] **Step 7: Implement the new members on `BridgeAccessibilityService`** (append inside the class, after `setFocusedText`):

```kotlin
override fun readTree(includeBounds: Boolean): ScreenNode? {
    val root = rootInActiveWindow ?: return null
    return ScreenReader.read(AndroidNodeView(root), includeBounds)
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
```

- [ ] **Step 8: Verify compile + full unit suite green**

Run: `cd android && ./gradlew :app:testDebugUnitTest && cd ..`
Expected: `BUILD SUCCESSFUL`; all prior tests still pass.

- [ ] **Step 9: Commit**

```bash
jj commit -m "feat: extend AccessibilityActions seam and add GestureMath"
```

---

## Task 2: Gesture tools (long_press, drag, pinch, swipe, scroll)

**Files:**
- Modify: `command/Command.kt`, `accessibility/ActionExecutor.kt`,
  `accessibility/BridgeAccessibilityService.kt`, `server/BridgeServer.kt`,
  `plugin/client.py`, `plugin/tools.py`
- Create: `accessibility/NodeSearch.kt`
- Test: `accessibility/ActionExecutorGesturesTest.kt`, `tests/test_gestures.py`

- [ ] **Step 1: Add the `Command` variants** to `command/Command.kt` (inside `sealed interface Command`)

```kotlin
    data class LongPress(val x: Int?, val y: Int?, val nodeId: String?, val durationMs: Long) : Command
    data class Drag(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val durationMs: Long) : Command
    data class Pinch(val x: Int, val y: Int, val scale: Double) : Command
    data class Swipe(val direction: com.hermesandroid.bridge.accessibility.Direction, val distanceFraction: Double) : Command
    data class Scroll(val direction: com.hermesandroid.bridge.accessibility.Direction, val nodeId: String?) : Command
```

- [ ] **Step 2: Create `NodeSearch.kt`** (pure tree helpers; extended in Task 3)

```kotlin
package com.hermesandroid.bridge.accessibility

/** Center point of a node's bounds. */
fun ScreenNode.center(): Pair<Int, Int> =
    (bounds.left + bounds.right) / 2 to (bounds.top + bounds.bottom) / 2

/** Pure searches over an immutable ScreenNode tree. */
object NodeSearch {
    fun byId(root: ScreenNode, id: String): ScreenNode? {
        if (root.id == id) return root
        for (child in root.children) byId(child, id)?.let { return it }
        return null
    }
}
```

- [ ] **Step 3: Write the failing Kotlin test** `ActionExecutorGesturesTest.kt`

```kotlin
package com.hermesandroid.bridge.accessibility

import com.hermesandroid.bridge.command.Command
import com.hermesandroid.bridge.command.CommandResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class CapturingActions(private val tree: ScreenNode?) : AccessibilityActions {
    var longPress: Triple<Int, Int, Long>? = null
    var drag: List<Int>? = null
    var pinch: Triple<Int, Int, Double>? = null
    var swipe: List<Int>? = null

    override suspend fun tapAt(x: Int, y: Int) = true
    override fun nodeCenterById(id: String): Pair<Int, Int>? = NodeSearch.byId(tree ?: return null, id)?.center()
    override fun setFocusedText(text: String, clearFirst: Boolean) = true
    override fun readTree(includeBounds: Boolean): ScreenNode? = tree
    override suspend fun longPressAt(x: Int, y: Int, durationMs: Long): Boolean { longPress = Triple(x, y, durationMs); return true }
    override suspend fun dragPath(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean { drag = listOf(fromX, fromY, toX, toY); return true }
    override suspend fun pinchAt(x: Int, y: Int, scale: Double): Boolean { pinch = Triple(x, y, scale); return true }
    override suspend fun swipePath(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean { swipe = listOf(fromX, fromY, toX, toY); return true }
    override fun pressGlobal(action: Int) = true
    override fun launchApp(packageName: String) = true
    override fun foregroundPackage(): String? = null
    override fun installedApps(): List<Pair<String, String>> = emptyList()
}

private fun screen(): ScreenNode = ScreenNode(
    "0", null, null, "root", null, false, NodeBounds(0, 0, 1000, 2000), emptyList()
)

class ActionExecutorGesturesTest {

    @Test
    fun longPressByCoordinate() = runTest {
        val a = CapturingActions(screen())
        val r = ActionExecutor(a).longPress(Command.LongPress(10, 20, null, 600))
        assertTrue(r is CommandResult.Ok)
        assertEquals(Triple(10, 20, 600L), a.longPress)
    }

    @Test
    fun dragPassesEndpoints() = runTest {
        val a = CapturingActions(screen())
        ActionExecutor(a).drag(Command.Drag(1, 2, 3, 4, 300))
        assertEquals(listOf(1, 2, 3, 4), a.drag)
    }

    @Test
    fun pinchPassesScale() = runTest {
        val a = CapturingActions(screen())
        ActionExecutor(a).pinch(Command.Pinch(500, 500, 2.0))
        assertEquals(Triple(500, 500, 2.0), a.pinch)
    }

    @Test
    fun swipeUpDecreasesY() = runTest {
        val a = CapturingActions(screen())
        val r = ActionExecutor(a).swipe(Command.Swipe(Direction.UP, 0.5))
        assertTrue(r is CommandResult.Ok)
        val (fromY, toY) = a.swipe!![1] to a.swipe!![3]
        assertTrue("up: toY < fromY", toY < fromY)
    }

    @Test
    fun swipeWithNoScreenIsServiceUnavailable() = runTest {
        val r = ActionExecutor(CapturingActions(null)).swipe(Command.Swipe(Direction.UP, 0.5))
        assertEquals(CommandResult.ServiceUnavailable, r)
    }

    @Test
    fun scrollDownSwipesContentUp() = runTest {
        val a = CapturingActions(screen())
        ActionExecutor(a).scroll(Command.Scroll(Direction.DOWN, null))
        // scroll DOWN reveals lower content => finger swipes UP => toY < fromY
        assertTrue(a.swipe!![3] < a.swipe!![1])
    }

    @Test
    fun scrollStaleNodeErrors() = runTest {
        val r = ActionExecutor(CapturingActions(screen())).scroll(Command.Scroll(Direction.DOWN, "0.9"))
        assertEquals("stale_node", (r as CommandResult.Err).error)
    }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ActionExecutorGesturesTest" && cd ..`
Expected: FAIL — new `ActionExecutor` methods unresolved.

- [ ] **Step 5: Add the methods to `ActionExecutor`** (append inside the class)

```kotlin
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
```

- [ ] **Step 6: Run to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ActionExecutorGesturesTest" && cd ..`
Expected: PASS (7 tests).

- [ ] **Step 7: Add handler branches** in `BridgeAccessibilityService.handle(...)` (inside the `when`)

```kotlin
        is Command.LongPress -> actionExecutor.longPress(command)
        is Command.Drag -> actionExecutor.drag(command)
        is Command.Pinch -> actionExecutor.pinch(command)
        is Command.Swipe -> actionExecutor.swipe(command)
        is Command.Scroll -> actionExecutor.scroll(command)
```

- [ ] **Step 8: Add routes** in `BridgeServer` `routing { ... }`

```kotlin
                post("/long_press") {
                    val b = gson.fromJson(call.receiveText(), LongPressBody::class.java)
                    call.guarded {
                        BridgeAccessibilityService.current()!!.submit(
                            Command.LongPress(b.x, b.y, b.node_id, b.duration_ms ?: 600)
                        )
                    }
                }
                post("/drag") {
                    val b = gson.fromJson(call.receiveText(), DragBody::class.java)
                    call.guarded {
                        BridgeAccessibilityService.current()!!.submit(
                            Command.Drag(b.from_x, b.from_y, b.to_x, b.to_y, b.duration_ms ?: 300)
                        )
                    }
                }
                post("/pinch") {
                    val b = gson.fromJson(call.receiveText(), PinchBody::class.java)
                    call.guarded {
                        BridgeAccessibilityService.current()!!.submit(Command.Pinch(b.x, b.y, b.scale))
                    }
                }
                post("/swipe") {
                    val b = gson.fromJson(call.receiveText(), SwipeBody::class.java)
                    call.guarded {
                        BridgeAccessibilityService.current()!!.submit(
                            Command.Swipe(parseDirection(b.direction), b.distance ?: 0.5)
                        )
                    }
                }
                post("/scroll") {
                    val b = gson.fromJson(call.receiveText(), ScrollBody::class.java)
                    call.guarded {
                        BridgeAccessibilityService.current()!!.submit(
                            Command.Scroll(parseDirection(b.direction), b.node_id)
                        )
                    }
                }
```

Add these private body types + a direction parser to `BridgeServer` (next to `TapBody`):

```kotlin
    private data class LongPressBody(val x: Int?, val y: Int?, val node_id: String?, val duration_ms: Long?)
    private data class DragBody(val from_x: Int, val from_y: Int, val to_x: Int, val to_y: Int, val duration_ms: Long?)
    private data class PinchBody(val x: Int, val y: Int, val scale: Double)
    private data class SwipeBody(val direction: String, val distance: Double?)
    private data class ScrollBody(val direction: String, val node_id: String?)

    private fun parseDirection(s: String) =
        com.hermesandroid.bridge.accessibility.Direction.valueOf(s.uppercase())
```

- [ ] **Step 9: Add client methods** to `plugin/client.py` (inside `AndroidClient`)

```python
    async def long_press(self, x=None, y=None, node_id=None, duration_ms=600):
        return await self._request("POST", "/long_press",
                                   json={"x": x, "y": y, "node_id": node_id, "duration_ms": duration_ms})

    async def drag(self, from_x, from_y, to_x, to_y, duration_ms=300):
        return await self._request("POST", "/drag",
                                   json={"from_x": from_x, "from_y": from_y, "to_x": to_x, "to_y": to_y, "duration_ms": duration_ms})

    async def pinch(self, x, y, scale):
        return await self._request("POST", "/pinch", json={"x": x, "y": y, "scale": scale})

    async def swipe(self, direction, distance=0.5):
        return await self._request("POST", "/swipe", json={"direction": direction, "distance": distance})

    async def scroll(self, direction, node_id=None):
        return await self._request("POST", "/scroll", json={"direction": direction, "node_id": node_id})
```

- [ ] **Step 10: Add tools + schemas** to `plugin/tools.py`

```python
async def android_long_press(client, x=None, y=None, node_id=None, duration_ms=600):
    """Long-press by coordinate or node_id."""
    return await _run(client.long_press(x=x, y=y, node_id=node_id, duration_ms=duration_ms))

async def android_drag(client, from_x, from_y, to_x, to_y, duration_ms=300):
    """Drag from one point to another."""
    return await _run(client.drag(from_x, from_y, to_x, to_y, duration_ms=duration_ms))

async def android_pinch(client, x, y, scale):
    """Pinch zoom at (x,y); scale<1 zooms out, >1 zooms in."""
    return await _run(client.pinch(x, y, scale))

async def android_swipe(client, direction, distance=0.5):
    """Swipe up/down/left/right across the screen (distance is a 0..1 fraction)."""
    return await _run(client.swipe(direction, distance=distance))

async def android_scroll(client, direction, node_id=None):
    """Scroll the screen (or a node) up/down/left/right."""
    return await _run(client.scroll(direction, node_id=node_id))
```

Append schema entries to `TOOL_SCHEMAS`:

```python
    {"name": "android_long_press", "description": "Long-press by coordinate or node_id.",
     "parameters": {"type": "object", "properties": {
         "x": {"type": "integer"}, "y": {"type": "integer"}, "node_id": {"type": "string"},
         "duration_ms": {"type": "integer", "default": 600}}, "required": []},
     "handler": android_long_press},
    {"name": "android_drag", "description": "Drag from one point to another.",
     "parameters": {"type": "object", "properties": {
         "from_x": {"type": "integer"}, "from_y": {"type": "integer"},
         "to_x": {"type": "integer"}, "to_y": {"type": "integer"},
         "duration_ms": {"type": "integer", "default": 300}},
         "required": ["from_x", "from_y", "to_x", "to_y"]},
     "handler": android_drag},
    {"name": "android_pinch", "description": "Pinch zoom at (x,y); scale<1 out, >1 in.",
     "parameters": {"type": "object", "properties": {
         "x": {"type": "integer"}, "y": {"type": "integer"}, "scale": {"type": "number"}},
         "required": ["x", "y", "scale"]},
     "handler": android_pinch},
    {"name": "android_swipe", "description": "Swipe up/down/left/right.",
     "parameters": {"type": "object", "properties": {
         "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
         "distance": {"type": "number", "default": 0.5}}, "required": ["direction"]},
     "handler": android_swipe},
    {"name": "android_scroll", "description": "Scroll the screen or a node.",
     "parameters": {"type": "object", "properties": {
         "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
         "node_id": {"type": "string"}}, "required": ["direction"]},
     "handler": android_scroll},
```

- [ ] **Step 11: Write the failing Python test** `tests/test_gestures.py`

```python
import json
from plugin.client import AndroidClient
from plugin.config import Config
from plugin import tools


def cfg():
    return Config(base_url="http://phone:8765", token="SECRET", timeout=5.0, max_bytes=10_000)


async def test_swipe_posts_direction(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/swipe", json={"ok": True, "data": {"swiped": "UP"}})
    client = AndroidClient(cfg())
    r = await tools.android_swipe(client, "up")
    assert r["ok"] is True
    assert json.loads(httpx_mock.get_requests()[0].content) == {"direction": "up", "distance": 0.5}
    await client.aclose()


async def test_drag_posts_endpoints(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/drag", json={"ok": True, "data": {"dragged": True}})
    client = AndroidClient(cfg())
    await tools.android_drag(client, 1, 2, 3, 4)
    sent = json.loads(httpx_mock.get_requests()[0].content)
    assert sent == {"from_x": 1, "from_y": 2, "to_x": 3, "to_y": 4, "duration_ms": 300}
    await client.aclose()


async def test_scroll_surfaces_stale_node(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/scroll",
                            json={"ok": False, "error": "stale_node", "message": "gone"})
    client = AndroidClient(cfg())
    r = await tools.android_scroll(client, "down", node_id="0.9")
    assert r["ok"] is False and r["error"] == "stale_node"
    await client.aclose()
```

- [ ] **Step 12: Run all tests (Kotlin + Python) to verify green**

Run:
```bash
python -m pytest tests/test_gestures.py -q
cd android && ./gradlew :app:testDebugUnitTest && cd ..
```
Expected: Python 3 tests PASS; Kotlin suite PASS.

- [ ] **Step 13: Commit**

```bash
jj commit -m "feat: add long_press, drag, pinch, swipe, scroll tools"
```

---

## Task 3: Node-targeted tools (tap_text, find_nodes, describe_node)

**Files:**
- Modify: `command/Command.kt`, `accessibility/NodeSearch.kt`, `accessibility/ActionExecutor.kt`,
  `accessibility/BridgeAccessibilityService.kt`, `server/BridgeServer.kt`,
  `plugin/client.py`, `plugin/tools.py`
- Test: `accessibility/NodeSearchTest.kt`, `accessibility/ActionExecutorNodeTest.kt`, `tests/test_nodes.py`

- [ ] **Step 1: Add `Command` variants** to `Command.kt`

```kotlin
    data class TapText(val text: String, val exact: Boolean) : Command
    data class FindNodes(val text: String?, val className: String?, val clickableOnly: Boolean) : Command
    data class DescribeNode(val nodeId: String) : Command
```

- [ ] **Step 2: Write the failing `NodeSearchTest.kt`**

```kotlin
package com.hermesandroid.bridge.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeSearchTest {
    private fun node(id: String, text: String?, cls: String, clickable: Boolean, kids: List<ScreenNode> = emptyList()) =
        ScreenNode(id, text, null, cls, null, clickable, NodeBounds(0, 0, 10, 10), kids)

    private val tree = node("0", null, "Root", false, listOf(
        node("0.0", "Submit", "android.widget.Button", true),
        node("0.1", "submit form", "android.widget.TextView", false),
        node("0.2", "Cancel", "android.widget.Button", true),
    ))

    @Test fun byIdFindsNested() { assertEquals("Cancel", NodeSearch.byId(tree, "0.2")?.text) }
    @Test fun byIdMissingIsNull() { assertNull(NodeSearch.byId(tree, "9.9")) }

    @Test fun byTextExactMatchesWholeStringCaseInsensitive() {
        val hits = NodeSearch.byText(tree, "submit", exact = true)
        assertEquals(listOf("0.0"), hits.map { it.id })
    }

    @Test fun byTextSubstringMatchesContains() {
        val hits = NodeSearch.byText(tree, "submit", exact = false).map { it.id }
        assertEquals(listOf("0.0", "0.1"), hits)
    }

    @Test fun matchingFiltersByClassAndClickable() {
        val hits = NodeSearch.matching(tree, text = null, className = "Button", clickableOnly = true).map { it.id }
        assertEquals(listOf("0.0", "0.2"), hits)
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*NodeSearchTest" && cd ..`
Expected: FAIL — `byText` / `matching` unresolved.

- [ ] **Step 4: Extend `NodeSearch.kt`** (add to the `object`)

```kotlin
    /** Pre-order list of all nodes. */
    private fun flatten(root: ScreenNode, out: MutableList<ScreenNode> = mutableListOf()): List<ScreenNode> {
        out.add(root)
        for (c in root.children) flatten(c, out)
        return out
    }

    fun byText(root: ScreenNode, query: String, exact: Boolean): List<ScreenNode> {
        val q = query.lowercase()
        return flatten(root).filter { n ->
            val t = n.text?.lowercase() ?: return@filter false
            if (exact) t == q else t.contains(q)
        }
    }

    fun matching(root: ScreenNode, text: String?, className: String?, clickableOnly: Boolean): List<ScreenNode> =
        flatten(root).filter { n ->
            (text == null || (n.text?.contains(text, ignoreCase = true) == true)) &&
            (className == null || (n.className?.contains(className, ignoreCase = true) == true)) &&
            (!clickableOnly || n.clickable)
        }
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*NodeSearchTest" && cd ..`
Expected: PASS (5 tests).

- [ ] **Step 6: Write failing `ActionExecutorNodeTest.kt`**

```kotlin
package com.hermesandroid.bridge.accessibility

import com.hermesandroid.bridge.command.Command
import com.hermesandroid.bridge.command.CommandResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class NodeActions(private val tree: ScreenNode?) : AccessibilityActions {
    var tapped: Pair<Int, Int>? = null
    override suspend fun tapAt(x: Int, y: Int): Boolean { tapped = x to y; return true }
    override fun nodeCenterById(id: String) = NodeSearch.byId(tree ?: return null, id)?.center()
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

private fun btn(id: String, text: String) =
    ScreenNode(id, text, null, "android.widget.Button", null, true, NodeBounds(0, 0, 100, 100), emptyList())

class ActionExecutorNodeTest {
    private val tree = ScreenNode("0", null, null, "Root", null, false,
        NodeBounds(0, 0, 100, 100), listOf(btn("0.0", "OK")))

    @Test fun tapTextTapsCenterOfMatch() = runTest {
        val a = NodeActions(tree)
        val r = ActionExecutor(a).tapText(Command.TapText("ok", exact = true))
        assertTrue(r is CommandResult.Ok)
        assertEquals(50 to 50, a.tapped)
    }

    @Test fun tapTextNoMatchErrors() = runTest {
        val r = ActionExecutor(NodeActions(tree)).tapText(Command.TapText("nope", exact = true))
        assertEquals("text_not_found", (r as CommandResult.Err).error)
    }

    @Test fun findNodesReturnsMatches() = runTest {
        val r = ActionExecutor(NodeActions(tree)).findNodes(Command.FindNodes("OK", null, false))
        val data = (r as CommandResult.Ok).data as Map<*, *>
        assertEquals(1, (data["nodes"] as List<*>).size)
    }

    @Test fun describeNodeReturnsTheNode() = runTest {
        val r = ActionExecutor(NodeActions(tree)).describeNode(Command.DescribeNode("0.0"))
        assertTrue(r is CommandResult.Ok)
    }

    @Test fun describeMissingNodeErrors() = runTest {
        val r = ActionExecutor(NodeActions(tree)).describeNode(Command.DescribeNode("9.9"))
        assertEquals("stale_node", (r as CommandResult.Err).error)
    }
}
```

- [ ] **Step 7: Run to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ActionExecutorNodeTest" && cd ..`
Expected: FAIL — methods unresolved.

- [ ] **Step 8: Add methods to `ActionExecutor`**

```kotlin
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
```

- [ ] **Step 9: Run to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ActionExecutorNodeTest" && cd ..`
Expected: PASS (5 tests).

- [ ] **Step 10: Wire handler + routes + client + tools**

Handler branches in `BridgeAccessibilityService.handle`:
```kotlin
        is Command.TapText -> actionExecutor.tapText(command)
        is Command.FindNodes -> actionExecutor.findNodes(command)
        is Command.DescribeNode -> actionExecutor.describeNode(command)
```

Routes in `BridgeServer`:
```kotlin
                post("/tap_text") {
                    val b = gson.fromJson(call.receiveText(), TapTextBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.TapText(b.text, b.exact ?: false)) }
                }
                get("/find_nodes") {
                    val q = call.request.queryParameters
                    call.guarded {
                        BridgeAccessibilityService.current()!!.submit(
                            Command.FindNodes(q["text"], q["class"], q["clickable"]?.toBoolean() ?: false)
                        )
                    }
                }
                get("/describe_node") {
                    val id = call.request.queryParameters["node_id"] ?: ""
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.DescribeNode(id)) }
                }
```
Body type in `BridgeServer`:
```kotlin
    private data class TapTextBody(val text: String, val exact: Boolean?)
```

Client methods in `plugin/client.py`:
```python
    async def tap_text(self, text, exact=False):
        return await self._request("POST", "/tap_text", json={"text": text, "exact": exact})

    async def find_nodes(self, text=None, class_name=None, clickable=False):
        params = {"clickable": str(clickable).lower()}
        if text is not None: params["text"] = text
        if class_name is not None: params["class"] = class_name
        return await self._request("GET", "/find_nodes", params=params)

    async def describe_node(self, node_id):
        return await self._request("GET", "/describe_node", params={"node_id": node_id})
```

Tools + schemas in `plugin/tools.py`:
```python
async def android_tap_text(client, text, exact=False):
    """Tap the first element whose visible text matches."""
    return await _run(client.tap_text(text, exact=exact))

async def android_find_nodes(client, text=None, class_name=None, clickable=False):
    """Search the screen for nodes by text/class/clickable."""
    return await _run(client.find_nodes(text=text, class_name=class_name, clickable=clickable))

async def android_describe_node(client, node_id):
    """Get full details of a node by id."""
    return await _run(client.describe_node(node_id))
```
```python
    {"name": "android_tap_text", "description": "Tap the first element whose visible text matches.",
     "parameters": {"type": "object", "properties": {
         "text": {"type": "string"}, "exact": {"type": "boolean", "default": False}},
         "required": ["text"]}, "handler": android_tap_text},
    {"name": "android_find_nodes", "description": "Search nodes by text/class/clickable.",
     "parameters": {"type": "object", "properties": {
         "text": {"type": "string"}, "class_name": {"type": "string"},
         "clickable": {"type": "boolean", "default": False}}, "required": []},
     "handler": android_find_nodes},
    {"name": "android_describe_node", "description": "Get full details of a node by id.",
     "parameters": {"type": "object", "properties": {"node_id": {"type": "string"}},
         "required": ["node_id"]}, "handler": android_describe_node},
```

- [ ] **Step 11: Write + run `tests/test_nodes.py`**

```python
from plugin.client import AndroidClient
from plugin.config import Config
from plugin import tools


def cfg():
    return Config(base_url="http://phone:8765", token="SECRET", timeout=5.0, max_bytes=100_000)


async def test_tap_text_not_found_surfaced(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/tap_text",
                            json={"ok": False, "error": "text_not_found", "message": "no node"})
    client = AndroidClient(cfg())
    r = await tools.android_tap_text(client, "Buy")
    assert r["ok"] is False and r["error"] == "text_not_found"
    await client.aclose()


async def test_find_nodes_passes_query(httpx_mock):
    httpx_mock.add_response(json={"ok": True, "data": {"nodes": []}})
    client = AndroidClient(cfg())
    await tools.android_find_nodes(client, text="OK", clickable=True)
    req = httpx_mock.get_requests()[0]
    assert "text=OK" in str(req.url) and "clickable=true" in str(req.url)
    await client.aclose()
```

Run:
```bash
python -m pytest tests/test_nodes.py -q
cd android && ./gradlew :app:testDebugUnitTest && cd ..
```
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
jj commit -m "feat: add tap_text, find_nodes, describe_node tools"
```

---

## Task 4: Screen-change detection (screen_hash, diff_screen)

**Files:**
- Modify: `command/Command.kt`, `accessibility/ActionExecutor.kt`,
  `accessibility/BridgeAccessibilityService.kt`, `server/BridgeServer.kt`,
  `plugin/client.py`, `plugin/tools.py`
- Test: `accessibility/ActionExecutorHashTest.kt`, `tests/test_hash.py`

Reuses `ScreenHash` from Plan 1.

- [ ] **Step 1: Add `Command` variants** to `Command.kt`

```kotlin
    data object ScreenHashCmd : Command
    data class DiffScreen(val previousHash: String) : Command
```

- [ ] **Step 2: Write failing `ActionExecutorHashTest.kt`**

```kotlin
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
```

- [ ] **Step 3: Run to verify it fails**, then implement, then verify pass.

Run (fail): `cd android && ./gradlew :app:testDebugUnitTest --tests "*ActionExecutorHashTest" && cd ..`

Add to `ActionExecutor`:
```kotlin
    fun screenHash(): CommandResult {
        val tree = actions.readTree(true) ?: return CommandResult.ServiceUnavailable
        return CommandResult.Ok(mapOf("hash" to ScreenHash.hash(tree)))
    }

    fun diffScreen(cmd: Command.DiffScreen): CommandResult {
        val tree = actions.readTree(true) ?: return CommandResult.ServiceUnavailable
        val current = ScreenHash.hash(tree)
        return CommandResult.Ok(mapOf("changed" to (current != cmd.previousHash), "hash" to current))
    }
```

Run (pass): `cd android && ./gradlew :app:testDebugUnitTest --tests "*ActionExecutorHashTest" && cd ..`
Expected: PASS (3 tests).

- [ ] **Step 4: Wire handler + routes + client + tools**

Handler:
```kotlin
        is Command.ScreenHashCmd -> actionExecutor.screenHash()
        is Command.DiffScreen -> actionExecutor.diffScreen(command)
```
Routes:
```kotlin
                get("/screen_hash") {
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.ScreenHashCmd) }
                }
                post("/diff_screen") {
                    val b = gson.fromJson(call.receiveText(), DiffBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.DiffScreen(b.hash)) }
                }
```
Body: `private data class DiffBody(val hash: String)`

Client:
```python
    async def screen_hash(self):
        return await self._request("GET", "/screen_hash")

    async def diff_screen(self, previous_hash):
        return await self._request("POST", "/diff_screen", json={"hash": previous_hash})
```
Tools + schemas:
```python
async def android_screen_hash(client):
    """Get a stable hash of the current screen for change detection."""
    return await _run(client.screen_hash())

async def android_diff_screen(client, hash):
    """Compare the current screen against a previously captured hash."""
    return await _run(client.diff_screen(hash))
```
```python
    {"name": "android_screen_hash", "description": "Stable hash of the current screen.",
     "parameters": {"type": "object", "properties": {}, "required": []},
     "handler": android_screen_hash},
    {"name": "android_diff_screen", "description": "Compare current screen to a prior hash.",
     "parameters": {"type": "object", "properties": {"hash": {"type": "string"}},
         "required": ["hash"]}, "handler": android_diff_screen},
```

- [ ] **Step 5: Write + run `tests/test_hash.py`**

```python
from plugin.client import AndroidClient
from plugin.config import Config
from plugin import tools


def cfg():
    return Config(base_url="http://phone:8765", token="SECRET", timeout=5.0, max_bytes=10_000)


async def test_diff_reports_change(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/diff_screen",
                            json={"ok": True, "data": {"changed": True, "hash": "abc"}})
    client = AndroidClient(cfg())
    r = await tools.android_diff_screen(client, "old")
    assert r["data"]["changed"] is True
    await client.aclose()
```

Run:
```bash
python -m pytest tests/test_hash.py -q
cd android && ./gradlew :app:testDebugUnitTest && cd ..
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
jj commit -m "feat: add screen_hash and diff_screen tools"
```

---

## Task 5: Navigation & system reads (open_app, press_key, current_app, get_apps)

**Files:**
- Modify: `command/Command.kt`, `accessibility/ActionExecutor.kt`,
  `accessibility/BridgeAccessibilityService.kt`, `server/BridgeServer.kt`,
  `plugin/client.py`, `plugin/tools.py`
- Create: `accessibility/KeyMap.kt`
- Test: `accessibility/KeyMapTest.kt`, `accessibility/ActionExecutorSystemTest.kt`, `tests/test_system.py`

- [ ] **Step 1: Add `Command` variants** to `Command.kt`

```kotlin
    data class OpenApp(val packageName: String) : Command
    data class PressKey(val key: String) : Command
    data object CurrentApp : Command
    data object GetApps : Command
```

- [ ] **Step 2: Write failing `KeyMapTest.kt`**

```kotlin
package com.hermesandroid.bridge.accessibility

import android.accessibilityservice.AccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyMapTest {
    @Test fun mapsKnownKeys() {
        assertEquals(AccessibilityService.GLOBAL_ACTION_BACK, KeyMap.globalAction("back"))
        assertEquals(AccessibilityService.GLOBAL_ACTION_HOME, KeyMap.globalAction("HOME"))
        assertEquals(AccessibilityService.GLOBAL_ACTION_RECENTS, KeyMap.globalAction("recents"))
    }
    @Test fun unknownKeyIsNull() { assertNull(KeyMap.globalAction("teleport")) }
}
```

- [ ] **Step 3: Run (fail), implement `KeyMap.kt`, run (pass)**

Run (fail): `cd android && ./gradlew :app:testDebugUnitTest --tests "*KeyMapTest" && cd ..`

```kotlin
package com.hermesandroid.bridge.accessibility

import android.accessibilityservice.AccessibilityService

/** Maps friendly key names to AccessibilityService global actions. */
object KeyMap {
    fun globalAction(key: String): Int? = when (key.lowercase()) {
        "back" -> AccessibilityService.GLOBAL_ACTION_BACK
        "home" -> AccessibilityService.GLOBAL_ACTION_HOME
        "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
        "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
        "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        else -> null
    }
}
```

Run (pass): same `--tests "*KeyMapTest"`. Expected: PASS (2 tests).

- [ ] **Step 4: Write failing `ActionExecutorSystemTest.kt`**

```kotlin
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
```

- [ ] **Step 5: Run (fail), implement, run (pass)**

Add to `ActionExecutor`:
```kotlin
    fun pressKey(cmd: Command.PressKey): CommandResult {
        val action = KeyMap.globalAction(cmd.key)
            ?: return CommandResult.Err("unknown_key", "no such key '${cmd.key}'")
        return if (actions.pressGlobal(action)) CommandResult.Ok(mapOf("pressed" to cmd.key))
        else CommandResult.Err("action_failed", "global action not performed")
    }

    fun openApp(cmd: Command.OpenApp): CommandResult =
        if (actions.launchApp(cmd.packageName)) CommandResult.Ok(mapOf("opened" to cmd.packageName))
        else CommandResult.Err("app_not_found", "no launchable app '${cmd.packageName}'")

    fun currentApp(): CommandResult = CommandResult.Ok(mapOf("package" to actions.foregroundPackage()))

    fun getApps(): CommandResult = CommandResult.Ok(
        mapOf("apps" to actions.installedApps().map { mapOf("label" to it.first, "package" to it.second) })
    )
```

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ActionExecutorSystemTest" && cd ..`
Expected: PASS (6 tests).

- [ ] **Step 6: Wire handler + routes + client + tools**

Handler:
```kotlin
        is Command.OpenApp -> actionExecutor.openApp(command)
        is Command.PressKey -> actionExecutor.pressKey(command)
        is Command.CurrentApp -> actionExecutor.currentApp()
        is Command.GetApps -> actionExecutor.getApps()
```
Routes:
```kotlin
                post("/open_app") {
                    val b = gson.fromJson(call.receiveText(), OpenAppBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.OpenApp(b.package_name)) }
                }
                post("/press_key") {
                    val b = gson.fromJson(call.receiveText(), PressKeyBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.PressKey(b.key)) }
                }
                get("/current_app") { call.guarded { BridgeAccessibilityService.current()!!.submit(Command.CurrentApp) } }
                get("/apps") { call.guarded { BridgeAccessibilityService.current()!!.submit(Command.GetApps) } }
```
Bodies:
```kotlin
    private data class OpenAppBody(val package_name: String)
    private data class PressKeyBody(val key: String)
```
Client:
```python
    async def open_app(self, package):
        return await self._request("POST", "/open_app", json={"package_name": package})
    async def press_key(self, key):
        return await self._request("POST", "/press_key", json={"key": key})
    async def current_app(self):
        return await self._request("GET", "/current_app")
    async def get_apps(self):
        return await self._request("GET", "/apps")
```
Tools + schemas:
```python
async def android_open_app(client, package):
    """Launch an app by package name."""
    return await _run(client.open_app(package))
async def android_press_key(client, key):
    """Press a system key: back, home, recents, notifications, quick_settings, power_dialog, lock_screen."""
    return await _run(client.press_key(key))
async def android_current_app(client):
    """Get the foreground app's package name."""
    return await _run(client.current_app())
async def android_get_apps(client):
    """List installed launchable apps."""
    return await _run(client.get_apps())
```
```python
    {"name": "android_open_app", "description": "Launch an app by package name.",
     "parameters": {"type": "object", "properties": {"package": {"type": "string"}},
         "required": ["package"]}, "handler": android_open_app},
    {"name": "android_press_key", "description": "Press a system key (back/home/recents/...).",
     "parameters": {"type": "object", "properties": {"key": {"type": "string"}},
         "required": ["key"]}, "handler": android_press_key},
    {"name": "android_current_app", "description": "Foreground app package name.",
     "parameters": {"type": "object", "properties": {}, "required": []},
     "handler": android_current_app},
    {"name": "android_get_apps", "description": "List installed launchable apps.",
     "parameters": {"type": "object", "properties": {}, "required": []},
     "handler": android_get_apps},
```

- [ ] **Step 7: Write + run `tests/test_system.py`**

```python
import json
from plugin.client import AndroidClient
from plugin.config import Config
from plugin import tools


def cfg():
    return Config(base_url="http://phone:8765", token="SECRET", timeout=5.0, max_bytes=100_000)


async def test_open_app_posts_package(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/open_app", json={"ok": True, "data": {"opened": "com.foo"}})
    client = AndroidClient(cfg())
    await tools.android_open_app(client, "com.foo")
    assert json.loads(httpx_mock.get_requests()[0].content) == {"package_name": "com.foo"}
    await client.aclose()


async def test_get_apps_returns_list(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/apps",
                            json={"ok": True, "data": {"apps": [{"label": "Foo", "package": "com.foo"}]}})
    client = AndroidClient(cfg())
    r = await tools.android_get_apps(client)
    assert r["data"]["apps"][0]["package"] == "com.foo"
    await client.aclose()
```

Run:
```bash
python -m pytest tests/test_system.py -q
cd android && ./gradlew :app:testDebugUnitTest && cd ..
```
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
jj commit -m "feat: add open_app, press_key, current_app, get_apps tools"
```

---

## Task 6: wait (poll for element)

**Files:**
- Modify: `command/Command.kt`, `accessibility/ActionExecutor.kt`,
  `accessibility/BridgeAccessibilityService.kt`, `server/BridgeServer.kt`,
  `plugin/client.py`, `plugin/tools.py`
- Test: `accessibility/ActionExecutorWaitTest.kt`, `tests/test_wait.py`

- [ ] **Step 1: Add `Command` variant** to `Command.kt`

```kotlin
    data class Wait(val text: String?, val className: String?, val timeoutMs: Long) : Command
```

- [ ] **Step 2: Write failing `ActionExecutorWaitTest.kt`** (virtual time)

```kotlin
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
```

- [ ] **Step 3: Run (fail), implement, run (pass)**

Add to `ActionExecutor` (import `kotlinx.coroutines.delay` at top of file):
```kotlin
    suspend fun waitFor(cmd: Command.Wait): CommandResult {
        val pollMs = 250L
        val attempts = maxOf(1, (cmd.timeoutMs / pollMs).toInt())
        repeat(attempts) {
            val tree = actions.readTree(true)
            if (tree != null) {
                val hit = NodeSearch.matching(tree, cmd.text, cmd.className, clickableOnly = false).firstOrNull()
                if (hit != null) return CommandResult.Ok(mapOf("found" to true, "node_id" to hit.id))
            }
            delay(pollMs)
        }
        return CommandResult.Ok(mapOf("found" to false))
    }
```

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ActionExecutorWaitTest" && cd ..`
Expected: PASS (2 tests).

> Note: keep `timeout_ms` below the executor's 25s per-command budget (Plan 1, Task 10),
> or the command times out before `wait` returns `found:false`.

- [ ] **Step 4: Wire handler + route + client + tool**

Handler: `is Command.Wait -> actionExecutor.waitFor(command)`
Route:
```kotlin
                post("/wait") {
                    val b = gson.fromJson(call.receiveText(), WaitBody::class.java)
                    call.guarded {
                        BridgeAccessibilityService.current()!!.submit(
                            Command.Wait(b.text, b.class_name, b.timeout_ms ?: 5_000)
                        )
                    }
                }
```
Body: `private data class WaitBody(val text: String?, val class_name: String?, val timeout_ms: Long?)`
Client:
```python
    async def wait(self, text=None, class_name=None, timeout_ms=5000):
        return await self._request("POST", "/wait",
                                   json={"text": text, "class_name": class_name, "timeout_ms": timeout_ms})
```
Tool + schema:
```python
async def android_wait(client, text=None, class_name=None, timeout_ms=5000):
    """Wait for an element with the given text/class to appear (up to timeout_ms)."""
    return await _run(client.wait(text=text, class_name=class_name, timeout_ms=timeout_ms))
```
```python
    {"name": "android_wait", "description": "Wait for an element to appear.",
     "parameters": {"type": "object", "properties": {
         "text": {"type": "string"}, "class_name": {"type": "string"},
         "timeout_ms": {"type": "integer", "default": 5000}}, "required": []},
     "handler": android_wait},
```

- [ ] **Step 5: Write + run `tests/test_wait.py`**

```python
from plugin.client import AndroidClient
from plugin.config import Config
from plugin import tools


def cfg():
    return Config(base_url="http://phone:8765", token="SECRET", timeout=30.0, max_bytes=10_000)


async def test_wait_reports_found(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/wait",
                            json={"ok": True, "data": {"found": True, "node_id": "0.3"}})
    client = AndroidClient(cfg())
    r = await tools.android_wait(client, text="Done")
    assert r["data"]["found"] is True
    await client.aclose()
```

Run:
```bash
python -m pytest tests/test_wait.py -q
cd android && ./gradlew :app:testDebugUnitTest && cd ..
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
jj commit -m "feat: add wait (poll for element) tool"
```

---

## Final verification (Plan 2)

- [ ] **Whole suite + APK**

Run: `mise run test-py && mise run test-android && mise run build-apk`
Expected: all Python tests PASS; all Kotlin unit tests PASS; APK builds.

- [ ] **Schema count check**

Run: `python -c "from plugin.tools import TOOL_SCHEMAS; print(len(TOOL_SCHEMAS))"`
Expected: `19` (4 from Plan 1 + 15 from Plan 2).

- [ ] **On-device smoke (optional, phone via ADB)**

With the app installed and accessibility enabled: `mise run test-device`, then manually
drive `android_swipe`, `android_tap_text`, `android_open_app`, `android_wait` and confirm
real effects.

---

## What Plan 2 delivers

The complete core automation surface — 19 tools total. Gestures, node-targeted actions,
change detection, navigation, and waiting all run through the same single-consumer
executor with the same RAII/seam discipline, fully unit-tested behind fakes and
validated on-device. Plans 3–5 add capture/media, system/comms, and notifications/events.
