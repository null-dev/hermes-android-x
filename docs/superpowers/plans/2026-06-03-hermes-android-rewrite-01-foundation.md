# Hermes Android Rewrite — Plan 1/5: Foundation + Vertical Slice

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the full architecture end-to-end with a minimal tool set (`ping`, `read_screen`, `tap`, `type`), proving the relay-free design works on a real phone.

**Architecture:** Phone runs a Ktor (CIO) HTTP server bound to `0.0.0.0:8765`, guarded by a mandatory bearer-token auth filter. Every accessibility operation is a `Command` serialized through a single-consumer `CommandExecutor` actor. A promoted foreground service keeps it alive. The Python side is a thin async `httpx` client wrapped by `android_*` tools, configured purely by env vars.

**Tech Stack:** Kotlin 1.9.22, AGP 8.3.0, Gradle 8.6, Ktor 2.3.7 (CIO), kotlinx-coroutines 1.7.3, Gson, JUnit4 + MockK + Robolectric; Python 3.12, httpx, pytest + pytest-httpx; mise for tooling.

> **VCS note:** This repo uses **Jujutsu (jj)**, not git. Every "Commit" step uses `jj commit -m "..."`. (`jj commit` finalizes the current working-copy change and starts a fresh one.) Never use `git commit`.

> **Reference:** Spec at `docs/superpowers/specs/2026-06-03-hermes-android-rewrite-design.md`. Read it before starting.

---

## File Structure (created by this plan)

```
.mise.toml                                  # fixed JAVA_HOME + tasks
android/
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  gradle/libs.versions.toml
  gradle/wrapper/gradle-wrapper.properties
  gradlew, gradlew.bat, gradle-wrapper.jar  # copied from upstream
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/res/values/strings.xml
  app/src/main/res/layout/activity_main.xml
  app/src/main/res/xml/accessibility_service_config.xml
  app/src/main/kotlin/com/hermesandroid/bridge/
    command/Command.kt              # Command + Result sealed types, Envelope
    command/CommandExecutor.kt      # single-consumer actor
    accessibility/NodeScope.kt      # useNode / withWindows RAII helpers
    accessibility/ScreenNode.kt     # immutable node tree + stable hash
    accessibility/BridgeAccessibilityService.kt
    accessibility/ScreenReader.kt
    accessibility/ActionExecutor.kt
    server/TokenStore.kt
    server/AuthFilter.kt            # constant-time compare + rate limiter
    server/BridgeServer.kt          # Ktor CIO server + routes
    lifecycle/BridgeForegroundService.kt
    lifecycle/WakeLockManager.kt
    ui/MainActivity.kt
  app/src/test/kotlin/com/hermesandroid/bridge/    # JVM unit + Robolectric
  app/src/androidTest/kotlin/com/hermesandroid/bridge/  # connected device tests
plugin/
  __init__.py
  client.py
  tools.py
  plugin.yaml
  skill.md
  config.py
pyproject.toml
tests/
  conftest.py
  test_client.py
  test_tools.py
.github/workflows/ci.yml
```

---

## Task 0: Fix mise tooling

**Files:**
- Modify: `.mise.toml`

- [ ] **Step 1: Replace `.mise.toml` with a working config**

The current `[env] JAVA_HOME` uses `{{env.MISE_DATA_DIR}}`, which is not defined in
mise's template context, so every mise command errors. mise's core `java` plugin
exports `JAVA_HOME` automatically — so we delete the manual override entirely and
add tasks.

```toml
[tools]
java = "21"
gradle = "8.6"
python = "3.12"

[tasks.test-py]
description = "Run Python unit tests"
run = "python -m pytest tests/ -m 'not device'"

[tasks.test-android]
description = "Run Android JVM unit tests"
dir = "android"
run = "./gradlew testDebugUnitTest"

[tasks.test-device]
description = "Run instrumented tests on a connected phone (ADB)"
dir = "android"
run = "./gradlew connectedDebugAndroidTest"

[tasks.build-apk]
description = "Assemble the debug APK"
dir = "android"
run = "./gradlew assembleDebug"

[tasks.lint]
description = "Lint Kotlin"
dir = "android"
run = "./gradlew lint"
```

- [ ] **Step 2: Verify mise no longer errors and Java resolves**

Run: `mise install && mise env | grep JAVA_HOME && java -version`
Expected: no `mise ERROR` lines; `JAVA_HOME` points at a mise-managed JDK 21;
`java -version` prints `21.x`.

> If `JAVA_HOME` is NOT exported by mise on this machine, add it back explicitly:
> `[env]` then `JAVA_HOME = "{{ env.HOME }}/.local/share/mise/installs/java/21"`.
> Re-run the verify command before proceeding.

- [ ] **Step 3: Commit**

```bash
jj commit -m "build: fix mise JAVA_HOME and add tasks"
```

---

## Task 1: Android Gradle scaffold

**Files:**
- Copy: `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/gradle-wrapper.jar`
- Create: `android/gradle/wrapper/gradle-wrapper.properties`, `android/settings.gradle.kts`,
  `android/build.gradle.kts`, `android/gradle.properties`, `android/gradle/libs.versions.toml`,
  `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`,
  `android/app/src/main/res/values/strings.xml`

- [ ] **Step 1: Copy the Gradle wrapper from upstream**

The wrapper jar is binary; reuse the known-good one from the prototype.

Run:
```bash
mkdir -p android/gradle/wrapper android/app/src/main/res/values \
         android/app/src/main/res/layout android/app/src/main/res/xml \
         android/app/src/main/kotlin/com/hermesandroid/bridge \
         android/app/src/test/kotlin/com/hermesandroid/bridge \
         android/app/src/androidTest/kotlin/com/hermesandroid/bridge
cp ~/Desktop/hermes-android/hermes-android-bridge/gradlew android/gradlew
cp ~/Desktop/hermes-android/hermes-android-bridge/gradlew.bat android/gradlew.bat
cp ~/Desktop/hermes-android/hermes-android-bridge/gradle/wrapper/gradle-wrapper.jar \
   android/gradle/wrapper/gradle-wrapper.jar
chmod +x android/gradlew
```
Expected: files exist; `ls android/gradle/wrapper/gradle-wrapper.jar` succeeds.

- [ ] **Step 2: Write `android/gradle/wrapper/gradle-wrapper.properties`**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.6-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 3: Write `android/settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "hermes-android"
include(":app")
```

- [ ] **Step 4: Write `android/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application") version "8.3.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
```

- [ ] **Step 5: Write `android/gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 6: Write `android/gradle/libs.versions.toml`**

```toml
[versions]
ktor = "2.3.7"
coroutines = "1.7.3"
gson = "2.10.1"
junit = "4.13.2"
mockk = "1.13.10"
robolectric = "4.11.1"
androidx-test = "1.5.0"
androidx-junit = "1.1.5"

[libraries]
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
ktor-server-cio = { module = "io.ktor:ktor-server-cio", version.ref = "ktor" }
ktor-server-content-negotiation = { module = "io.ktor:ktor-server-content-negotiation", version.ref = "ktor" }
ktor-serialization-gson = { module = "io.ktor:ktor-serialization-gson", version.ref = "ktor" }
ktor-server-status-pages = { module = "io.ktor:ktor-server-status-pages", version.ref = "ktor" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
gson = { module = "com.google.code.gson:gson", version.ref = "gson" }
junit = { module = "junit:junit", version.ref = "junit" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidx-test" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidx-test" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidx-junit" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version = "4.12.0" }
```

- [ ] **Step 7: Write `android/app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hermesandroid.bridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hermesandroid.bridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "hermes-android-${variant.versionName}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
            )
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
        getByName("androidTest").java.srcDirs("src/androidTest/kotlin")
    }
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.okhttp)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 8: Write `android/app/src/main/res/values/strings.xml`**

```xml
<resources>
    <string name="app_name">Hermes Bridge</string>
    <string name="accessibility_description">Lets the Hermes agent read the screen and perform taps, swipes, and text input.</string>
</resources>
```

- [ ] **Step 9: Write `android/app/src/main/AndroidManifest.xml`** (services wired up in later tasks; this establishes permissions + launcher)

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="true">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".accessibility.BridgeAccessibilityService"
            android:exported="false"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

        <service
            android:name=".lifecycle.BridgeForegroundService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Local HTTP control server for the device owner's AI agent" />
        </service>
    </application>
</manifest>
```

- [ ] **Step 10: Write `android/app/src/main/res/xml/accessibility_service_config.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagReportViewIds|flagRequestEnhancedWebAccessibility"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:description="@string/accessibility_description"
    android:notificationTimeout="100" />
```

- [ ] **Step 11: Verify the project configures**

Run: `cd android && ./gradlew help && cd ..`
Expected: `BUILD SUCCESSFUL`. (Downloads Gradle 8.6 + AGP on first run.)

- [ ] **Step 12: Commit**

```bash
jj commit -m "build: scaffold android gradle project"
```

---

## Task 2: Command and Result types

**Files:**
- Create: `android/app/src/main/kotlin/com/hermesandroid/bridge/command/Command.kt`

- [ ] **Step 1: Write the types** (no test — these are plain data declarations exercised by Task 3)

`Command` is the closed set of operations the executor understands; this plan adds
four. `CommandResult` is the executor's typed outcome, later mapped to HTTP by the
server.

```kotlin
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
```

- [ ] **Step 2: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin && cd ..`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
jj commit -m "feat: add Command and CommandResult types"
```

---

## Task 3: CommandExecutor actor

**Files:**
- Create: `android/app/src/main/kotlin/com/hermesandroid/bridge/command/CommandExecutor.kt`
- Test: `android/app/src/test/kotlin/com/hermesandroid/bridge/command/CommandExecutorTest.kt`

- [ ] **Step 1: Write the failing tests**

These lock down the three guarantees from the spec: serialization (one command at a
time), per-command timeout that doesn't wedge the queue, and draining after a thrown
handler.

```kotlin
package com.hermesandroid.bridge.command

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CommandExecutorTest {

    @Test
    fun runsCommandsOneAtATime() = runTest {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val exec = CommandExecutor(this, timeoutMs = 10_000) {
            val now = active.incrementAndGet()
            maxActive.updateAndGet { m -> maxOf(m, now) }
            delay(100)
            active.decrementAndGet()
            CommandResult.Ok(null)
        }
        val jobs = (1..5).map { async { exec.submit(Command.Ping) } }
        jobs.awaitAll()
        assertEquals(1, maxActive.get())
        exec.close()
    }

    @Test
    fun timesOutSlowCommandAndKeepsDraining() = runTest {
        var calls = 0
        val exec = CommandExecutor(this, timeoutMs = 1_000) { cmd ->
            calls++
            if (cmd is Command.ReadScreen) { delay(5_000); CommandResult.Ok("late") }
            else CommandResult.Ok("fast")
        }
        val slow = exec.submit(Command.ReadScreen(false))
        assertTrue("expected Timeout, got $slow", slow is CommandResult.Timeout)
        val fast = exec.submit(Command.Ping)
        assertEquals(CommandResult.Ok("fast"), fast)
        assertEquals(2, calls)
        exec.close()
    }

    @Test
    fun continuesAfterHandlerThrows() = runTest {
        var first = true
        val exec = CommandExecutor(this, timeoutMs = 1_000) {
            if (first) { first = false; throw IllegalStateException("boom") }
            CommandResult.Ok("ok")
        }
        val err = exec.submit(Command.Ping)
        assertTrue(err is CommandResult.Err)
        assertEquals("internal_error", (err as CommandResult.Err).error)
        val ok = exec.submit(Command.Ping)
        assertEquals(CommandResult.Ok("ok"), ok)
        exec.close()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*CommandExecutorTest" && cd ..`
Expected: FAIL — `CommandExecutor` unresolved.

- [ ] **Step 3: Implement `CommandExecutor`**

```kotlin
package com.hermesandroid.bridge.command

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Single-consumer command queue. Every accessibility operation flows through here,
 * so commands execute strictly one at a time on [scope]'s dispatcher — structurally
 * preventing concurrent mutation of accessibility state.
 *
 * @param scope    coroutine scope owning the consumer loop (use a single-thread
 *                 dispatcher in production; a test scope in tests).
 * @param timeoutMs per-command time budget; an over-budget command yields
 *                 [CommandResult.Timeout] and the queue keeps draining.
 * @param handler  runs a single command. May suspend; may throw (throws become
 *                 [CommandResult.Err], the queue continues).
 */
class CommandExecutor(
    scope: CoroutineScope,
    private val timeoutMs: Long,
    private val handler: suspend (Command) -> CommandResult,
) {
    private class Job(val command: Command, val deferred: CompletableDeferred<CommandResult>)

    private val channel = Channel<Job>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (job in channel) {
                val result = try {
                    withTimeout(timeoutMs) { handler(job.command) }
                } catch (e: TimeoutCancellationException) {
                    CommandResult.Timeout("command timed out after ${timeoutMs}ms")
                } catch (e: CancellationException) {
                    throw e // genuine cancellation of the consumer must propagate
                } catch (e: Throwable) {
                    CommandResult.Err("internal_error", e.message ?: e.toString())
                }
                job.deferred.complete(result)
            }
        }
    }

    /** Enqueue [command] and suspend until its result is available. */
    suspend fun submit(command: Command): CommandResult {
        val deferred = CompletableDeferred<CommandResult>()
        channel.send(Job(command, deferred))
        return deferred.await()
    }

    fun close() = channel.close()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*CommandExecutorTest" && cd ..`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: add single-consumer CommandExecutor with per-command timeout"
```

---

## Task 4: Node recycling RAII helpers (bug #1)

**Files:**
- Create: `android/app/src/main/kotlin/com/hermesandroid/bridge/accessibility/NodeScope.kt`
- Test: `android/app/src/test/kotlin/com/hermesandroid/bridge/accessibility/NodeScopeTest.kt`

- [ ] **Step 1: Write the failing tests**

The prototype leaked `AccessibilityNodeInfo` when `buildNode()` threw. These helpers
guarantee `recycle()` runs in a `finally`, even on exception.

```kotlin
package com.hermesandroid.bridge.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NodeScopeTest {

    @Test
    fun useNodeRecyclesOnNormalReturn() {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        val r = useNode(node) { 42 }
        assertEquals(42, r)
        verify(exactly = 1) { node.recycle() }
    }

    @Test
    fun useNodeRecyclesEvenWhenBlockThrows() {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        try {
            useNode(node) { throw RuntimeException("boom") }
            fail("expected exception to propagate")
        } catch (_: RuntimeException) { /* expected */ }
        verify(exactly = 1) { node.recycle() }
    }

    @Test
    fun withWindowsRecyclesAllEvenWhenBlockThrows() {
        val w1 = mockk<AccessibilityWindowInfo>(relaxed = true)
        val w2 = mockk<AccessibilityWindowInfo>(relaxed = true)
        try {
            withWindows(listOf(w1, w2)) { throw RuntimeException("boom") }
            fail("expected exception to propagate")
        } catch (_: RuntimeException) { /* expected */ }
        verify(exactly = 1) { w1.recycle() }
        verify(exactly = 1) { w2.recycle() }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*NodeScopeTest" && cd ..`
Expected: FAIL — `useNode` / `withWindows` unresolved.

- [ ] **Step 3: Implement the helpers**

```kotlin
package com.hermesandroid.bridge.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Run [block] with [node], then recycle it — even if [block] throws. */
inline fun <R> useNode(node: AccessibilityNodeInfo, block: (AccessibilityNodeInfo) -> R): R {
    try {
        return block(node)
    } finally {
        @Suppress("DEPRECATION")
        node.recycle()
    }
}

/** Run [block], then recycle every window in [windows] — even if [block] throws. */
inline fun <R> withWindows(windows: List<AccessibilityWindowInfo>, block: () -> R): R {
    try {
        return block()
    } finally {
        for (w in windows) {
            @Suppress("DEPRECATION")
            w.recycle()
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*NodeScopeTest" && cd ..`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: add RAII node-recycling helpers (fixes leak on exception)"
```

---

## Task 5: ScreenNode + stable content hash (bug #13)

**Files:**
- Create: `android/app/src/main/kotlin/com/hermesandroid/bridge/accessibility/ScreenNode.kt`
- Test: `android/app/src/test/kotlin/com/hermesandroid/bridge/accessibility/ScreenHashTest.kt`

- [ ] **Step 1: Write the failing tests**

The prototype used 32-bit `Object.hashCode()`, which collided and caused false
"no change" diffs. We use a 64-bit FNV-1a content hash.

```kotlin
package com.hermesandroid.bridge.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScreenHashTest {

    private fun leaf(text: String? = null, id: String = "0"): ScreenNode =
        ScreenNode(
            id = id, text = text, contentDescription = null, className = "android.widget.TextView",
            viewId = null, clickable = false, bounds = NodeBounds(0, 0, 10, 10), children = emptyList(),
        )

    private fun parentOf(vararg kids: ScreenNode): ScreenNode =
        ScreenNode("p", null, null, "android.widget.LinearLayout", null, false,
            NodeBounds(0, 0, 100, 100), kids.toList())

    @Test
    fun deterministic() {
        val tree = parentOf(leaf("a"), leaf("b"))
        assertEquals(ScreenHash.hash(tree), ScreenHash.hash(tree))
    }

    @Test
    fun sensitiveToText() {
        assertNotEquals(ScreenHash.hash(leaf("hello")), ScreenHash.hash(leaf("world")))
    }

    @Test
    fun siblingOrderMatters() {
        assertNotEquals(
            ScreenHash.hash(parentOf(leaf("a"), leaf("b"))),
            ScreenHash.hash(parentOf(leaf("b"), leaf("a"))),
        )
    }

    @Test
    fun noCollisionsAcrossManyDistinctTrees() {
        val hashes = (0 until 5000).map { ScreenHash.hash(leaf("item $it")) }.toSet()
        assertEquals(5000, hashes.size)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ScreenHashTest" && cd ..`
Expected: FAIL — `ScreenNode` / `ScreenHash` unresolved.

- [ ] **Step 3: Implement `ScreenNode` + `ScreenHash`**

```kotlin
package com.hermesandroid.bridge.accessibility

/** Pixel bounds of a node on screen. */
data class NodeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Immutable snapshot of one accessibility node and its subtree. */
data class ScreenNode(
    val id: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewId: String?,
    val clickable: Boolean,
    val bounds: NodeBounds,
    val children: List<ScreenNode>,
)

/** Stable 64-bit FNV-1a content hash of a screen, for change detection. */
object ScreenHash {
    private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME = 1099511628211L

    fun hash(root: ScreenNode): String {
        var h = FNV_OFFSET
        fun mix(s: String?) {
            val str = s ?: ""
            for (c in str) {
                h = h xor c.code.toLong()
                h *= FNV_PRIME
            }
            // field separator so "ab"+"c" != "a"+"bc"
            h = h xor 0x1fL
            h *= FNV_PRIME
        }
        fun walk(n: ScreenNode) {
            mix(n.text); mix(n.contentDescription); mix(n.className); mix(n.viewId)
            mix(if (n.clickable) "1" else "0")
            mix("${n.bounds.left},${n.bounds.top},${n.bounds.right},${n.bounds.bottom}")
            mix("(") // structure markers make sibling order significant
            for (child in n.children) walk(child)
            mix(")")
        }
        walk(root)
        return java.lang.Long.toHexString(h)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ScreenHashTest" && cd ..`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: add ScreenNode and stable 64-bit content hash"
```

---

## Task 6: TokenStore (mandatory token generation)

**Files:**
- Create: `android/app/src/main/kotlin/com/hermesandroid/bridge/server/TokenStore.kt`
- Test: `android/app/src/test/kotlin/com/hermesandroid/bridge/server/TokenStoreTest.kt`

- [ ] **Step 1: Write the failing tests**

Storage is abstracted behind an interface so the generation logic is plain-JVM
testable (no Robolectric). The token is 160-bit, base32, persistent across calls.

```kotlin
package com.hermesandroid.bridge.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenStoreTest {

    private class FakeStorage(var value: String? = null) : TokenStorage {
        override fun read(): String? = value
        override fun write(token: String) { value = token }
    }

    @Test
    fun getOrCreateGeneratesThenPersists() {
        val storage = FakeStorage()
        val store = TokenStore(storage)
        val first = store.getOrCreate()
        assertTrue("token too short", first.length >= 16)
        assertEquals("must persist", first, storage.value)
        assertEquals("must be stable", first, store.getOrCreate())
    }

    @Test
    fun regenerateProducesADifferentToken() {
        val store = TokenStore(FakeStorage())
        val a = store.getOrCreate()
        val b = store.regenerate()
        assertNotEquals(a, b)
    }

    @Test
    fun tokenIsBase32Charset() {
        val token = TokenStore(FakeStorage()).getOrCreate()
        assertTrue(token.all { it in 'A'..'Z' || it in '2'..'7' })
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*TokenStoreTest" && cd ..`
Expected: FAIL — `TokenStore` / `TokenStorage` unresolved.

- [ ] **Step 3: Implement `TokenStore` + storage**

```kotlin
package com.hermesandroid.bridge.server

import android.content.Context
import java.security.SecureRandom

/** Persistence boundary for the auth token (kept tiny so generation is unit-testable). */
interface TokenStorage {
    fun read(): String?
    fun write(token: String)
}

/** SharedPreferences-backed storage for production. */
class PrefsTokenStorage(context: Context) : TokenStorage {
    private val prefs = context.getSharedPreferences("hermes_bridge", Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString(KEY, null)
    override fun write(token: String) { prefs.edit().putString(KEY, token).apply() }
    private companion object { const val KEY = "auth_token" }
}

/**
 * Generates and persists the bearer token. The server must never run without one,
 * so [getOrCreate] always returns a token (creating it on first use).
 */
class TokenStore(
    private val storage: TokenStorage,
    private val random: SecureRandom = SecureRandom(),
) {
    fun getOrCreate(): String = storage.read() ?: regenerate()

    fun regenerate(): String {
        val token = generate()
        storage.write(token)
        return token
    }

    private fun generate(): String {
        val bytes = ByteArray(20) // 160 bits
        random.nextBytes(bytes)
        return base32(bytes)
    }

    private fun base32(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1f
                sb.append(alphabet[index])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1f
            sb.append(alphabet[index])
        }
        return sb.toString()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*TokenStoreTest" && cd ..`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: add mandatory token store with 160-bit base32 generation"
```

---

## Task 7: Authenticator + rate limiter (bug #5)

**Files:**
- Create: `android/app/src/main/kotlin/com/hermesandroid/bridge/server/AuthFilter.kt`
- Test: `android/app/src/test/kotlin/com/hermesandroid/bridge/server/AuthenticatorTest.kt`

- [ ] **Step 1: Write the failing tests**

Constant-time compare, reject missing/wrong tokens, and block an IP after repeated
failures (with an injectable clock to test the block window deterministically).

```kotlin
package com.hermesandroid.bridge.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatorTest {

    private var now = 0L
    private fun limiter() = RateLimiter(maxFailures = 3, windowMs = 1_000, blockMs = 5_000) { now }

    @Test
    fun acceptsCorrectBearerToken() {
        val auth = Authenticator("SECRET", limiter())
        assertEquals(AuthResult.Ok, auth.authenticate("1.2.3.4", "Bearer SECRET"))
    }

    @Test
    fun rejectsMissingHeader() {
        val auth = Authenticator("SECRET", limiter())
        assertEquals(AuthResult.Unauthorized, auth.authenticate("1.2.3.4", null))
    }

    @Test
    fun rejectsWrongToken() {
        val auth = Authenticator("SECRET", limiter())
        assertEquals(AuthResult.Unauthorized, auth.authenticate("1.2.3.4", "Bearer NOPE"))
    }

    @Test
    fun blocksAfterRepeatedFailuresThenUnblocksAfterWindow() {
        val auth = Authenticator("SECRET", limiter())
        repeat(3) { auth.authenticate("9.9.9.9", "Bearer NOPE") }
        // Even a correct token is refused while blocked.
        assertEquals(AuthResult.Blocked, auth.authenticate("9.9.9.9", "Bearer SECRET"))
        now += 5_001
        assertEquals(AuthResult.Ok, auth.authenticate("9.9.9.9", "Bearer SECRET"))
    }

    @Test
    fun constantTimeEqualsWorks() {
        assertTrue(constantTimeEquals("abc", "abc"))
        assertFalse(constantTimeEquals("abc", "abd"))
        assertFalse(constantTimeEquals("abc", "abcd"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*AuthenticatorTest" && cd ..`
Expected: FAIL — `Authenticator` / `RateLimiter` / `AuthResult` unresolved.

- [ ] **Step 3: Implement auth**

```kotlin
package com.hermesandroid.bridge.server

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Outcome of an auth check. */
sealed interface AuthResult {
    data object Ok : AuthResult
    data object Unauthorized : AuthResult
    data object Blocked : AuthResult
}

/** Constant-time string compare (avoids leaking token length-prefix timing). */
fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

/** Per-IP failure tracking with a sliding window and temporary block. Thread-safe. */
class RateLimiter(
    private val maxFailures: Int = 5,
    private val windowMs: Long = 60_000,
    private val blockMs: Long = 5 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class State(var count: Int, var windowStart: Long, var blockedUntil: Long)
    private val byIp = ConcurrentHashMap<String, State>()

    fun isBlocked(ip: String): Boolean {
        val s = byIp[ip] ?: return false
        return clock() < s.blockedUntil
    }

    fun recordFailure(ip: String) {
        val now = clock()
        val s = byIp.getOrPut(ip) { State(0, now, 0) }
        synchronized(s) {
            if (now - s.windowStart > windowMs) { s.count = 0; s.windowStart = now }
            s.count++
            if (s.count >= maxFailures) s.blockedUntil = now + blockMs
        }
    }

    fun recordSuccess(ip: String) { byIp.remove(ip) }

    /** Drop stale records; call periodically. */
    fun cleanup() {
        val now = clock()
        byIp.entries.removeIf { (_, s) -> now > s.blockedUntil && now - s.windowStart > windowMs }
    }
}

/** Validates the bearer token and enforces the rate limiter. */
class Authenticator(
    private val expectedToken: String,
    private val rateLimiter: RateLimiter = RateLimiter(),
) {
    fun authenticate(ip: String, authHeader: String?): AuthResult {
        if (rateLimiter.isBlocked(ip)) return AuthResult.Blocked
        val provided = authHeader?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
        if (provided != null && constantTimeEquals(provided, expectedToken)) {
            rateLimiter.recordSuccess(ip)
            return AuthResult.Ok
        }
        rateLimiter.recordFailure(ip)
        return AuthResult.Unauthorized
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*AuthenticatorTest" && cd ..`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: add constant-time authenticator with per-IP rate limiting"
```

---

## Task 8: ScreenReader over a testable NodeView boundary

**Files:**
- Create: `android/app/src/main/kotlin/com/hermesandroid/bridge/accessibility/NodeView.kt`
- Create: `android/app/src/main/kotlin/com/hermesandroid/bridge/accessibility/ScreenReader.kt`
- Test: `android/app/src/test/kotlin/com/hermesandroid/bridge/accessibility/ScreenReaderTest.kt`

The trick: `ScreenReader` reads from a small `NodeView` interface, not directly from
`AccessibilityNodeInfo`. The production adapter wraps the real Android type (validated
on-device in Task 14); the tree-building logic is pure and unit-tested with a fake.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.hermesandroid.bridge.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReaderTest {

    /** A fake node tree that records which nodes were recycled. */
    private class FakeNode(
        val label: String,
        override val clickable: Boolean = false,
        val kids: MutableList<FakeNode> = mutableListOf(),
        val recycled: MutableList<String>,
    ) : NodeView {
        override val text get() = label
        override val contentDescription: String? = null
        override val className = "android.widget.TextView"
        override val viewId: String? = null
        override val bounds = NodeBounds(0, 0, 10, 10)
        override val childCount get() = kids.size
        override fun child(i: Int): NodeView = kids[i]
        override fun recycle() { recycled.add(label) }
    }

    @Test
    fun buildsTreeWithDottedIdPaths() {
        val recycled = mutableListOf<String>()
        val root = FakeNode("root", recycled = recycled).apply {
            kids.add(FakeNode("a", clickable = true, recycled = recycled))
            kids.add(FakeNode("b", recycled = recycled))
        }
        val tree = ScreenReader.read(root, includeBounds = true)

        assertEquals("0", tree.id)
        assertEquals("root", tree.text)
        assertEquals(2, tree.children.size)
        assertEquals("0.0", tree.children[0].id)
        assertEquals("a", tree.children[0].text)
        assertTrue(tree.children[0].clickable)
        assertEquals("0.1", tree.children[1].id)
    }

    @Test
    fun recyclesEveryNodeEvenNested() {
        val recycled = mutableListOf<String>()
        val root = FakeNode("root", recycled = recycled).apply {
            kids.add(FakeNode("a", recycled = recycled))
        }
        ScreenReader.read(root, includeBounds = true)
        assertEquals(setOf("root", "a"), recycled.toSet())
    }

    @Test
    fun omitsBoundsWhenNotRequested() {
        val recycled = mutableListOf<String>()
        val root = FakeNode("root", recycled = recycled)
        val tree = ScreenReader.read(root, includeBounds = false)
        assertEquals(NodeBounds(0, 0, 0, 0), tree.bounds)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ScreenReaderTest" && cd ..`
Expected: FAIL — `NodeView` / `ScreenReader` unresolved.

- [ ] **Step 3: Implement `NodeView` (+ Android adapter) and `ScreenReader`**

`NodeView.kt`:
```kotlin
package com.hermesandroid.bridge.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** The minimal slice of an accessibility node that ScreenReader needs. */
interface NodeView {
    val text: String?
    val contentDescription: String?
    val className: String?
    val viewId: String?
    val clickable: Boolean
    val bounds: NodeBounds
    val childCount: Int
    fun child(i: Int): NodeView?
    fun recycle()
}

/** Production adapter wrapping a real AccessibilityNodeInfo. */
class AndroidNodeView(private val node: AccessibilityNodeInfo) : NodeView {
    override val text get() = node.text?.toString()
    override val contentDescription get() = node.contentDescription?.toString()
    override val className get() = node.className?.toString()
    override val viewId get() = node.viewIdResourceName
    override val clickable get() = node.isClickable
    override val bounds: NodeBounds
        get() {
            val r = Rect()
            node.getBoundsInScreen(r)
            return NodeBounds(r.left, r.top, r.right, r.bottom)
        }
    override val childCount get() = node.childCount
    override fun child(i: Int): NodeView? = node.getChild(i)?.let { AndroidNodeView(it) }
    @Suppress("DEPRECATION")
    override fun recycle() = node.recycle()
}
```

`ScreenReader.kt`:
```kotlin
package com.hermesandroid.bridge.accessibility

/** Builds an immutable ScreenNode snapshot from a NodeView tree, recycling as it goes. */
object ScreenReader {
    fun read(root: NodeView, includeBounds: Boolean): ScreenNode = build(root, "0", includeBounds)

    private fun build(view: NodeView, id: String, includeBounds: Boolean): ScreenNode {
        try {
            val children = ArrayList<ScreenNode>(view.childCount)
            for (i in 0 until view.childCount) {
                val child = view.child(i) ?: continue
                children.add(build(child, "$id.$i", includeBounds))
            }
            return ScreenNode(
                id = id,
                text = view.text,
                contentDescription = view.contentDescription,
                className = view.className,
                viewId = view.viewId,
                clickable = view.clickable,
                bounds = if (includeBounds) view.bounds else NodeBounds(0, 0, 0, 0),
                children = children,
            )
        } finally {
            view.recycle()
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ScreenReaderTest" && cd ..`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: add ScreenReader over a testable NodeView boundary"
```

---

## Task 9: ActionExecutor (tap + type) — bug #10

**Files:**
- Create: `android/app/src/main/kotlin/com/hermesandroid/bridge/accessibility/ActionExecutor.kt`
- Test: `android/app/src/test/kotlin/com/hermesandroid/bridge/accessibility/ActionExecutorTest.kt`

`ActionExecutor` depends on an `AccessibilityActions` seam (real gestures/focus live in
the service). `type` returns `no_focused_field` when nothing is focused — the
prototype's silent false-success bug.

- [ ] **Step 1: Write the failing tests**

```kotlin
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ActionExecutorTest" && cd ..`
Expected: FAIL — `ActionExecutor` / `AccessibilityActions` unresolved.

- [ ] **Step 3: Implement**

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ActionExecutorTest" && cd ..`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: add ActionExecutor with loud no_focused_field failure"
```

---

## Task 10: BridgeAccessibilityService (device wiring)

**Files:**
- Create: `android/app/src/main/kotlin/com/hermesandroid/bridge/accessibility/BridgeAccessibilityService.kt`

This is device code (validated end-to-end in Task 14, not unit-tested). It owns the
single-thread dispatcher, the `CommandExecutor`, the command `handler`, and the real
`AccessibilityActions`. The instance reference is lifecycle-managed (bug #14): set on
connect, cleared on destroy; readers tolerate `null`.

- [ ] **Step 1: Write the service**

```kotlin
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

    private suspend fun handle(command: Command): CommandResult = when (command) {
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
```

- [ ] **Step 2: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin && cd ..`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
jj commit -m "feat: add BridgeAccessibilityService wiring executor and actions"
```

---

## Task 11: Ktor server + HTTP mapping

**Files:**
- Create: `android/app/src/main/kotlin/com/hermesandroid/bridge/server/HttpMapping.kt`
- Create: `android/app/src/main/kotlin/com/hermesandroid/bridge/server/BridgeServer.kt`
- Test: `android/app/src/test/kotlin/com/hermesandroid/bridge/server/HttpMappingTest.kt`

The `CommandResult`→HTTP translation is pure and unit-tested; the Ktor wiring around
it is thin and validated on-device (Task 14).

- [ ] **Step 1: Write the failing test for the mapping**

```kotlin
package com.hermesandroid.bridge.server

import com.hermesandroid.bridge.command.CommandResult
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpMappingTest {

    @Test
    fun okMapsTo200WithData() {
        val r = HttpMapping.toHttp(CommandResult.Ok(mapOf("a" to 1)))
        assertEquals(200, r.status)
        assertEquals(true, r.body["ok"])
        assertEquals(mapOf("a" to 1), r.body["data"])
    }

    @Test
    fun appErrorMapsTo200WithOkFalse() {
        val r = HttpMapping.toHttp(CommandResult.Err("no_focused_field", "nope"))
        assertEquals(200, r.status)
        assertEquals(false, r.body["ok"])
        assertEquals("no_focused_field", r.body["error"])
    }

    @Test
    fun timeoutMapsTo408() {
        val r = HttpMapping.toHttp(CommandResult.Timeout("slow"))
        assertEquals(408, r.status)
        assertEquals("timeout", r.body["error"])
    }

    @Test
    fun serviceUnavailableMapsTo503() {
        val r = HttpMapping.toHttp(CommandResult.ServiceUnavailable)
        assertEquals(503, r.status)
        assertEquals("service_unavailable", r.body["error"])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*HttpMappingTest" && cd ..`
Expected: FAIL — `HttpMapping` unresolved.

- [ ] **Step 3: Implement `HttpMapping`**

```kotlin
package com.hermesandroid.bridge.server

import com.hermesandroid.bridge.command.CommandResult

object HttpMapping {
    data class HttpResponse(val status: Int, val body: Map<String, Any?>)

    fun toHttp(result: CommandResult): HttpResponse = when (result) {
        is CommandResult.Ok ->
            HttpResponse(200, mapOf("ok" to true, "data" to result.data))
        is CommandResult.Err ->
            HttpResponse(200, mapOf("ok" to false, "error" to result.error, "message" to result.message))
        is CommandResult.Timeout ->
            HttpResponse(408, mapOf("ok" to false, "error" to "timeout", "message" to result.message))
        CommandResult.ServiceUnavailable ->
            HttpResponse(503, mapOf("ok" to false, "error" to "service_unavailable",
                "message" to "Accessibility service not enabled"))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*HttpMappingTest" && cd ..`
Expected: PASS (4 tests).

- [ ] **Step 5: Implement `BridgeServer` (Ktor CIO)** — no unit test; covered on-device.

```kotlin
package com.hermesandroid.bridge.server

import com.google.gson.Gson
import com.hermesandroid.bridge.accessibility.BridgeAccessibilityService
import com.hermesandroid.bridge.command.Command
import com.hermesandroid.bridge.command.CommandResult
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * Embedded HTTP server on 0.0.0.0:[port]. Every request must carry a valid bearer
 * token; the server cannot be constructed without one.
 */
class BridgeServer(
    private val port: Int,
    token: String,
) {
    private val authenticator = Authenticator(token)
    private val gson = Gson()
    private var engine: ApplicationEngine? = null

    fun start() {
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) { gson() }
            routing {
                // Auth gate on every route.
                fun authOf(authHeader: String?, ip: String): AuthResult =
                    authenticator.authenticate(ip, authHeader)

                suspend fun io.ktor.server.application.ApplicationCall.guarded(
                    run: suspend () -> CommandResult,
                ) {
                    val ip = request.local.remoteHost
                    when (authOf(request.headers["Authorization"], ip)) {
                        AuthResult.Blocked ->
                            respond(HttpStatusCode.TooManyRequests, mapOf("ok" to false, "error" to "blocked"))
                        AuthResult.Unauthorized ->
                            respond(HttpStatusCode.Unauthorized, mapOf("ok" to false, "error" to "unauthorized"))
                        AuthResult.Ok -> {
                            val service = BridgeAccessibilityService.current()
                            val result = if (service == null) CommandResult.ServiceUnavailable else run()
                            val http = HttpMapping.toHttp(result)
                            respond(HttpStatusCode.fromValue(http.status), http.body)
                        }
                    }
                }

                get("/ping") {
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.Ping) }
                }
                get("/screen") {
                    val bounds = call.request.queryParameters["bounds"]?.toBoolean() ?: true
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.ReadScreen(bounds)) }
                }
                post("/tap") {
                    val body = gson.fromJson(call.receiveText(), TapBody::class.java)
                    call.guarded {
                        BridgeAccessibilityService.current()!!.submit(
                            Command.Tap(body.x, body.y, body.node_id)
                        )
                    }
                }
                post("/type") {
                    val body = gson.fromJson(call.receiveText(), TypeBody::class.java)
                    call.guarded {
                        BridgeAccessibilityService.current()!!.submit(
                            Command.Type(body.text ?: "", body.clear_first ?: false)
                        )
                    }
                }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1_000)
        engine = null
    }

    private data class TapBody(val x: Int?, val y: Int?, val node_id: String?)
    private data class TypeBody(val text: String?, val clear_first: Boolean?)
}
```

> Note: inside `guarded`, after `AuthResult.Ok` we re-check `current()`; the `!!` in
> each route's `run` block is safe because `guarded` only calls `run()` when the
> service is non-null. (If you prefer, hoist the non-null service into `run`'s
> closure — functionally identical.)

- [ ] **Step 6: Verify compile**

Run: `cd android && ./gradlew :app:compileDebugKotlin && cd ..`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
jj commit -m "feat: add Ktor CIO server with auth gate and HTTP mapping"
```

---

## Task 12: WakeLock + foreground service (bugs #6, #7)

**Files:**
- Create: `android/app/src/main/kotlin/com/hermesandroid/bridge/lifecycle/WakeLockManager.kt`
- Create: `android/app/src/main/kotlin/com/hermesandroid/bridge/lifecycle/LocalIp.kt`
- Create: `android/app/src/main/kotlin/com/hermesandroid/bridge/lifecycle/BridgeForegroundService.kt`
- Modify: `android/app/src/main/kotlin/com/hermesandroid/bridge/accessibility/BridgeAccessibilityService.kt`

- [ ] **Step 1: Write `WakeLockManager`** (partial lock, per-command scope, no fixed timeout)

```kotlin
package com.hermesandroid.bridge.lifecycle

import android.content.Context
import android.os.PowerManager

/** Partial wake lock held only for the duration of a single command (bug #7). */
class WakeLockManager(context: Context) {
    private val lock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hermes:bridge-command")
        .apply { setReferenceCounted(false) }

    suspend fun <R> around(block: suspend () -> R): R {
        @Suppress("WakelockTimeout") // bounded by the per-command executor timeout instead
        lock.acquire()
        try {
            return block()
        } finally {
            if (lock.isHeld) lock.release()
        }
    }
}
```

- [ ] **Step 2: Modify `BridgeAccessibilityService` to wrap commands in the wake lock**

Add the field and wrap `submit`:

```kotlin
// add import:
import com.hermesandroid.bridge.lifecycle.WakeLockManager

// add field (after `actionExecutor`):
private val wakeLocks by lazy { WakeLockManager(this) }

// replace the existing submit():
suspend fun submit(command: Command): CommandResult =
    wakeLocks.around { executor.submit(command) }
```

- [ ] **Step 3: Write `LocalIp`** (best LAN IPv4 for the status display)

```kotlin
package com.hermesandroid.bridge.lifecycle

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalIp {
    /** Returns the first site-local IPv4 (e.g. 192.168.x.x / 10.x / Tailscale 100.x), or "0.0.0.0". */
    fun best(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress ?: "0.0.0.0"
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }
}
```

- [ ] **Step 4: Write `BridgeForegroundService`** (promoted at start — bug #6)

```kotlin
package com.hermesandroid.bridge.lifecycle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.hermesandroid.bridge.server.BridgeServer
import com.hermesandroid.bridge.server.PrefsTokenStorage
import com.hermesandroid.bridge.server.TokenStore

class BridgeForegroundService : Service() {

    private var server: BridgeServer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = TokenStore(PrefsTokenStorage(this)).getOrCreate()
        val url = "http://${LocalIp.best()}:$PORT"
        startForeground(NOTIF_ID, buildNotification(url))
        if (server == null) {
            server = BridgeServer(PORT, token).also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hermes Bridge", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(url: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setContentTitle("Hermes Bridge active")
            .setContentText(url)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()

    companion object {
        const val PORT = 8765
        private const val CHANNEL = "hermes_bridge"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, BridgeForegroundService::class.java)
            context.startForegroundService(intent)
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeForegroundService::class.java))
        }
    }
}
```

- [ ] **Step 5: Verify compile + all unit tests still pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest && cd ..`
Expected: `BUILD SUCCESSFUL`; all prior tests green.

- [ ] **Step 6: Commit**

```bash
jj commit -m "feat: promote foreground service and add per-command wake lock"
```

---

## Task 13: Minimal MainActivity

**Files:**
- Create: `android/app/src/main/res/layout/activity_main.xml`
- Create: `android/app/src/main/kotlin/com/hermesandroid/bridge/ui/MainActivity.kt`

A deliberately minimal UI: show the URL + token, a button to start the foreground
service, and a shortcut to the accessibility settings. No long-lived view references
held by singletons (bug #8) — the Activity owns its views and nothing else holds them.

- [ ] **Step 1: Write `activity_main.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="24dp">

    <TextView android:id="@+id/tvUrl"
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:textIsSelectable="true" android:text="URL: —" />

    <TextView android:id="@+id/tvToken"
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:textIsSelectable="true" android:text="Token: —"
        android:layout_marginTop="8dp" />

    <Button android:id="@+id/btnStart"
        android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:text="Start bridge" android:layout_marginTop="16dp" />

    <Button android:id="@+id/btnAccessibility"
        android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:text="Enable accessibility" android:layout_marginTop="8dp" />
</LinearLayout>
```

- [ ] **Step 2: Write `MainActivity`**

```kotlin
package com.hermesandroid.bridge.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.lifecycle.BridgeForegroundService
import com.hermesandroid.bridge.lifecycle.LocalIp
import com.hermesandroid.bridge.server.PrefsTokenStorage
import com.hermesandroid.bridge.server.TokenStore

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val token = TokenStore(PrefsTokenStorage(this)).getOrCreate()
        findViewById<TextView>(R.id.tvUrl).text =
            "URL: http://${LocalIp.best()}:${BridgeForegroundService.PORT}"
        findViewById<TextView>(R.id.tvToken).text = "Token: $token"

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            BridgeForegroundService.start(this)
        }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}
```

- [ ] **Step 3: Add AppCompat dependency**

In `android/app/build.gradle.kts` add to `dependencies`:
```kotlin
    implementation("androidx.appcompat:appcompat:1.6.1")
```
And give the app an AppCompat theme — create `android/app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="AppTheme" parent="Theme.AppCompat.DayNight" />
</resources>
```
Then in `AndroidManifest.xml`, add `android:theme="@style/AppTheme"` to `<application>`.

- [ ] **Step 4: Build the APK**

Run: `cd android && ./gradlew assembleDebug && cd ..`
Expected: `BUILD SUCCESSFUL`; APK at `android/app/build/outputs/apk/debug/hermes-android-1.0.0.apk`.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: add minimal MainActivity showing URL and token"
```

---

## Task 14: On-device instrumented tests (real phone via ADB)

**Files:**
- Create: `android/app/src/androidTest/kotlin/com/hermesandroid/bridge/BridgeServerInstrumentedTest.kt`

These run on a connected device (`mise run test-device`), not in CI. The auth-gate
checks are fully self-contained (no accessibility grant needed). The success-path
checks require the accessibility service to be enabled once on the device; they
`assume` it and skip cleanly otherwise.

- [ ] **Step 1: Write the instrumented test**

```kotlin
package com.hermesandroid.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.bridge.accessibility.BridgeAccessibilityService
import com.hermesandroid.bridge.server.BridgeServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ConnectException

@RunWith(AndroidJUnit4::class)
class BridgeServerInstrumentedTest {

    private val port = 8770
    private val token = "TESTTOKEN1234567890"
    private lateinit var server: BridgeServer
    private val http = OkHttpClient()

    private fun get(path: String, auth: String?): okhttp3.Response {
        val b = Request.Builder().url("http://127.0.0.1:$port$path")
        if (auth != null) b.header("Authorization", auth)
        return http.newCall(b.build()).execute()
    }

    @Before
    fun setUp() {
        server = BridgeServer(port, token)
        server.start()
        // Wait for CIO to bind.
        repeat(50) {
            try { get("/ping", null).close(); return } catch (_: ConnectException) { Thread.sleep(100) }
        }
    }

    @After
    fun tearDown() { server.stop() }

    @Test
    fun rejectsMissingToken() {
        get("/ping", null).use { assertEquals(401, it.code) }
    }

    @Test
    fun rejectsWrongToken() {
        get("/ping", "Bearer NOPE").use { assertEquals(401, it.code) }
    }

    @Test
    fun acceptsCorrectTokenAndServesPing() {
        // Needs the accessibility service enabled on this device; skip otherwise.
        assumeTrue(
            "Enable Hermes Bridge accessibility service to run this test",
            BridgeAccessibilityService.current() != null,
        )
        get("/ping", "Bearer $token").use { resp ->
            assertEquals(200, resp.code)
            val body = resp.body!!.string()
            assertTrue("expected ok=true in $body", body.contains("\"ok\":true"))
        }
    }

    @Test
    fun readsANonTrivialScreen() {
        assumeTrue(BridgeAccessibilityService.current() != null)
        get("/screen?bounds=true", "Bearer $token").use { resp ->
            assertEquals(200, resp.code)
            val body = resp.body!!.string()
            assertTrue("expected a node tree in $body", body.contains("\"children\""))
        }
    }
}
```

- [ ] **Step 2: Run on a connected device**

Connect a phone over ADB (`adb devices` shows it). For the success-path tests, first
install the app and enable the accessibility service once
(Settings → Accessibility → Hermes Bridge → On).

Run: `mise run test-device`
Expected: `rejectsMissingToken` / `rejectsWrongToken` PASS; the two success-path tests
PASS if accessibility is enabled, otherwise reported as skipped (assumption failed).

- [ ] **Step 3: Commit**

```bash
jj commit -m "test: add on-device instrumented bridge server tests"
```

---

## Task 15: Python project scaffold

**Files:**
- Create: `pyproject.toml`, `plugin/__init__.py` (empty for now), `tests/__init__.py`, `tests/conftest.py`

- [ ] **Step 1: Write `pyproject.toml`**

```toml
[project]
name = "hermes-android"
version = "1.0.0"
description = "Direct-HTTP Android control tools for the Hermes agent"
requires-python = ">=3.12"
dependencies = ["httpx>=0.27"]

[project.optional-dependencies]
dev = ["pytest>=8", "pytest-asyncio>=0.23", "pytest-httpx>=0.30"]

[build-system]
requires = ["setuptools>=68"]
build-backend = "setuptools.build_meta"

[tool.setuptools]
packages = ["plugin"]

[tool.pytest.ini_options]
asyncio_mode = "auto"
testpaths = ["tests"]
markers = ["device: requires a real connected phone (set ANDROID_BRIDGE_URL)"]
```

- [ ] **Step 2: Write `tests/conftest.py`** (auto-skip device tests unless a phone is configured)

```python
import os
import pytest


def pytest_collection_modifyitems(config, items):
    if os.environ.get("ANDROID_BRIDGE_URL"):
        return
    skip = pytest.mark.skip(reason="device tests require ANDROID_BRIDGE_URL")
    for item in items:
        if "device" in item.keywords:
            item.add_marker(skip)
```

- [ ] **Step 3: Create empty package files**

Run:
```bash
: > plugin/__init__.py
: > tests/__init__.py
```

- [ ] **Step 4: Install and verify pytest collects nothing yet**

Run: `pip install -e ".[dev]" && python -m pytest -q`
Expected: `no tests ran` (exit code 5 is fine) with no import errors.

- [ ] **Step 5: Commit**

```bash
jj commit -m "build: scaffold python package and pytest config"
```

---

## Task 16: Config + AndroidClient (bugs #2, #11)

**Files:**
- Create: `plugin/config.py`
- Create: `plugin/client.py`
- Test: `tests/test_client.py`

- [ ] **Step 1: Write the failing tests**

```python
import asyncio

import pytest

from plugin.client import AndroidClient, BridgeError
from plugin.config import Config, ConfigError, load_config


def cfg(max_bytes=32 * 1024 * 1024):
    return Config(base_url="http://phone:8765", token="SECRET", timeout=5.0, max_bytes=max_bytes)


def test_load_config_requires_url(monkeypatch):
    monkeypatch.delenv("ANDROID_BRIDGE_URL", raising=False)
    monkeypatch.setenv("ANDROID_BRIDGE_TOKEN", "x")
    with pytest.raises(ConfigError):
        load_config()


def test_load_config_requires_token(monkeypatch):
    monkeypatch.setenv("ANDROID_BRIDGE_URL", "http://phone:8765")
    monkeypatch.delenv("ANDROID_BRIDGE_TOKEN", raising=False)
    with pytest.raises(ConfigError):
        load_config()


def test_client_constructs_without_running_event_loop():
    # Bug #2 regression: constructing the client in a sync context must not raise.
    client = AndroidClient(cfg())
    assert client is not None


async def test_ping_returns_data(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/ping", json={"ok": True, "data": {"device": "Pixel"}})
    client = AndroidClient(cfg())
    assert await client.ping() == {"device": "Pixel"}
    await client.aclose()


async def test_sends_bearer_token(httpx_mock):
    httpx_mock.add_response(json={"ok": True, "data": None})
    client = AndroidClient(cfg())
    await client.ping()
    assert httpx_mock.get_requests()[0].headers["Authorization"] == "Bearer SECRET"
    await client.aclose()


async def test_401_raises_unauthorized(httpx_mock):
    httpx_mock.add_response(status_code=401, json={"ok": False, "error": "unauthorized"})
    client = AndroidClient(cfg())
    with pytest.raises(BridgeError) as e:
        await client.ping()
    assert e.value.error == "unauthorized"
    await client.aclose()


async def test_app_level_failure_raises(httpx_mock):
    httpx_mock.add_response(json={"ok": False, "error": "no_focused_field", "message": "nope"})
    client = AndroidClient(cfg())
    with pytest.raises(BridgeError) as e:
        await client.type_text("hi")
    assert e.value.error == "no_focused_field"
    await client.aclose()


async def test_oversized_response_raises(httpx_mock):
    big = b'{"ok": true, "data": "' + b"x" * 5000 + b'"}'
    httpx_mock.add_response(content=big)
    client = AndroidClient(cfg(max_bytes=1000))
    with pytest.raises(BridgeError) as e:
        await client.ping()
    assert e.value.error == "response_too_large"
    await client.aclose()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_client.py -q`
Expected: FAIL — `plugin.client` / `plugin.config` import errors.

- [ ] **Step 3: Implement `plugin/config.py`**

```python
import os
from dataclasses import dataclass


class ConfigError(RuntimeError):
    """Raised when required configuration is missing."""


@dataclass(frozen=True)
class Config:
    base_url: str
    token: str
    timeout: float
    max_bytes: int


def load_config(env=None) -> Config:
    env = os.environ if env is None else env
    url = env.get("ANDROID_BRIDGE_URL")
    token = env.get("ANDROID_BRIDGE_TOKEN")
    if not url:
        raise ConfigError("ANDROID_BRIDGE_URL is required (e.g. http://192.168.1.50:8765)")
    if not token:
        raise ConfigError("ANDROID_BRIDGE_TOKEN is required (shown in the phone app)")
    return Config(
        base_url=url.rstrip("/"),
        token=token,
        timeout=float(env.get("ANDROID_BRIDGE_TIMEOUT", "30")),
        max_bytes=int(env.get("ANDROID_BRIDGE_MAX_BYTES", str(32 * 1024 * 1024))),
    )
```

- [ ] **Step 4: Implement `plugin/client.py`**

```python
import httpx

from .config import Config


class BridgeError(RuntimeError):
    """A request to the phone failed (transport or app-level)."""

    def __init__(self, error: str, message: str = "", status: int | None = None):
        super().__init__(f"{error}: {message}")
        self.error = error
        self.message = message
        self.status = status


class AndroidClient:
    """Async HTTP client for the phone bridge. Safe to construct in a sync context."""

    def __init__(self, config: Config):
        self._config = config
        # No asyncio.Lock here — httpx is concurrency-safe and a lock created
        # outside a running loop is the prototype's bug #2.
        self._client = httpx.AsyncClient(
            base_url=config.base_url,
            timeout=config.timeout,
            headers={"Authorization": f"Bearer {config.token}"},
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    async def _request(self, method: str, path: str, *, params=None, json=None):
        # Stream so we can enforce the size cap before buffering the whole body (bug #11).
        async with self._client.stream(method, path, params=params, json=json) as resp:
            chunks = []
            total = 0
            async for chunk in resp.aiter_bytes():
                total += len(chunk)
                if total > self._config.max_bytes:
                    raise BridgeError(
                        "response_too_large",
                        f"response exceeded {self._config.max_bytes} bytes",
                        resp.status_code,
                    )
                chunks.append(chunk)
            body = b"".join(chunks)
            status = resp.status_code
        return self._handle(status, body)

    def _handle(self, status: int, body: bytes):
        if status == 401:
            raise BridgeError("unauthorized", "bad or missing token", 401)
        if status == 408:
            raise BridgeError("timeout", "device command timed out", 408)
        if status == 413:
            raise BridgeError("request_too_large", "request body too large", 413)
        if status == 503:
            raise BridgeError("service_unavailable", "accessibility service not enabled", 503)
        if status >= 400:
            raise BridgeError("http_error", f"unexpected status {status}", status)

        import json as _json

        data = _json.loads(body.decode("utf-8"))
        if not data.get("ok", False):
            raise BridgeError(data.get("error", "unknown"), data.get("message", ""), status)
        return data.get("data")

    # --- high-level operations (this plan) ---

    async def ping(self):
        return await self._request("GET", "/ping")

    async def read_screen(self, include_bounds: bool = True):
        return await self._request("GET", "/screen", params={"bounds": str(include_bounds).lower()})

    async def tap(self, x=None, y=None, node_id=None):
        return await self._request("POST", "/tap", json={"x": x, "y": y, "node_id": node_id})

    async def type_text(self, text: str, clear_first: bool = False):
        return await self._request("POST", "/type", json={"text": text, "clear_first": clear_first})
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `python -m pytest tests/test_client.py -q`
Expected: PASS (8 tests).

- [ ] **Step 6: Commit**

```bash
jj commit -m "feat: add async AndroidClient with mandatory token and size cap"
```

---

## Task 17: android_* tool handlers

**Files:**
- Create: `plugin/tools.py`
- Test: `tests/test_tools.py`

Tool functions take the `client` as their first argument so they're unit-testable
against a real `AndroidClient` driven by `httpx_mock`. The plugin (Task 18) binds a
shared client. Each tool returns a normalized dict and never raises on an app-level
failure — it surfaces the phone's reason.

- [ ] **Step 1: Write the failing tests**

```python
import pytest

from plugin.client import AndroidClient
from plugin.config import Config
from plugin import tools


def cfg():
    return Config(base_url="http://phone:8765", token="SECRET", timeout=5.0, max_bytes=10_000)


async def test_ping_ok(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/ping", json={"ok": True, "data": {"device": "Pixel"}})
    client = AndroidClient(cfg())
    result = await tools.android_ping(client)
    assert result == {"ok": True, "data": {"device": "Pixel"}}
    await client.aclose()


async def test_ping_service_unavailable(httpx_mock):
    httpx_mock.add_response(status_code=503, json={"ok": False, "error": "service_unavailable"})
    client = AndroidClient(cfg())
    result = await tools.android_ping(client)
    assert result["ok"] is False
    assert result["error"] == "service_unavailable"
    await client.aclose()


async def test_tap_posts_coordinates(httpx_mock):
    httpx_mock.add_response(url="http://phone:8765/tap", json={"ok": True, "data": {"tapped": [10, 20]}})
    client = AndroidClient(cfg())
    result = await tools.android_tap(client, x=10, y=20)
    assert result["ok"] is True
    import json
    sent = json.loads(httpx_mock.get_requests()[0].content)
    assert sent == {"x": 10, "y": 20, "node_id": None}
    await client.aclose()


async def test_type_no_focused_field_surfaced(httpx_mock):
    httpx_mock.add_response(json={"ok": False, "error": "no_focused_field", "message": "No input field is focused"})
    client = AndroidClient(cfg())
    result = await tools.android_type(client, text="hi")
    assert result["ok"] is False
    assert result["error"] == "no_focused_field"
    await client.aclose()


async def test_read_screen_returns_tree(httpx_mock):
    tree = {"id": "0", "children": [{"id": "0.0", "children": []}]}
    httpx_mock.add_response(url="http://phone:8765/screen?bounds=true", json={"ok": True, "data": tree})
    client = AndroidClient(cfg())
    result = await tools.android_read_screen(client)
    assert result == {"ok": True, "data": tree}
    await client.aclose()


def test_schemas_cover_all_tools():
    names = {s["name"] for s in tools.TOOL_SCHEMAS}
    assert names == {"android_ping", "android_read_screen", "android_tap", "android_type"}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_tools.py -q`
Expected: FAIL — `plugin.tools` import error.

- [ ] **Step 3: Implement `plugin/tools.py`**

```python
from .client import AndroidClient, BridgeError


async def _run(coro):
    try:
        data = await coro
        return {"ok": True, "data": data}
    except BridgeError as e:
        return {"ok": False, "error": e.error, "message": e.message}


async def android_ping(client: AndroidClient):
    """Check that the phone is reachable and the accessibility service is on."""
    return await _run(client.ping())


async def android_read_screen(client: AndroidClient, include_bounds: bool = True):
    """Read the accessibility tree of the current screen."""
    return await _run(client.read_screen(include_bounds=include_bounds))


async def android_tap(client: AndroidClient, x=None, y=None, node_id=None):
    """Tap by absolute (x, y) coordinate or by a node id from a prior read_screen."""
    return await _run(client.tap(x=x, y=y, node_id=node_id))


async def android_type(client: AndroidClient, text: str, clear_first: bool = False):
    """Type text into the currently focused input field."""
    return await _run(client.type_text(text, clear_first=clear_first))


TOOL_SCHEMAS = [
    {
        "name": "android_ping",
        "description": "Check that the phone is reachable and the accessibility service is on.",
        "parameters": {"type": "object", "properties": {}, "required": []},
        "handler": android_ping,
    },
    {
        "name": "android_read_screen",
        "description": "Read the accessibility tree of the current screen.",
        "parameters": {
            "type": "object",
            "properties": {"include_bounds": {"type": "boolean", "default": True}},
            "required": [],
        },
        "handler": android_read_screen,
    },
    {
        "name": "android_tap",
        "description": "Tap by (x, y) coordinate or by a node_id from android_read_screen.",
        "parameters": {
            "type": "object",
            "properties": {
                "x": {"type": "integer"},
                "y": {"type": "integer"},
                "node_id": {"type": "string"},
            },
            "required": [],
        },
        "handler": android_tap,
    },
    {
        "name": "android_type",
        "description": "Type text into the currently focused input field.",
        "parameters": {
            "type": "object",
            "properties": {
                "text": {"type": "string"},
                "clear_first": {"type": "boolean", "default": False},
            },
            "required": ["text"],
        },
        "handler": android_type,
    },
]
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_tools.py -q`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: add android_ping/read_screen/tap/type tool handlers"
```

---

## Task 18: Plugin registration

**Files:**
- Create: `plugin/__init__.py` (replace the empty file)
- Create: `plugin/plugin.yaml`
- Create: `plugin/skill.md`

- [ ] **Step 1: Write `plugin/__init__.py`**

Binds a shared `AndroidClient` (built from env config) into each tool handler and
registers them with the hermes-agent plugin context.

```python
import functools

from .client import AndroidClient
from .config import load_config
from .tools import TOOL_SCHEMAS

_client: AndroidClient | None = None


def _get_client() -> AndroidClient:
    global _client
    if _client is None:
        _client = AndroidClient(load_config())
    return _client


def register(ctx):
    """hermes-agent plugin entry point. Registers all android_* tools."""
    for schema in TOOL_SCHEMAS:
        handler = schema["handler"]

        @functools.wraps(handler)
        async def bound(*args, _handler=handler, **kwargs):
            return await _handler(_get_client(), *args, **kwargs)

        ctx.register_tool(
            name=schema["name"],
            description=schema["description"],
            parameters=schema["parameters"],
            handler=bound,
        )
```

> The shared client is created lazily on first tool call (inside a running event
> loop) — never at import time — preserving the bug #2 fix end-to-end.

- [ ] **Step 2: Write `plugin/plugin.yaml`**

```yaml
name: hermes-android
version: 1.0.0
description: Direct-HTTP Android device control for the Hermes agent.
entrypoint: __init__.register
env:
  - ANDROID_BRIDGE_URL
  - ANDROID_BRIDGE_TOKEN
```

- [ ] **Step 3: Write `plugin/skill.md`**

```markdown
# Android control

Control a paired Android phone over your local network.

## Setup
1. Install the Hermes Bridge app on the phone and enable its accessibility service.
2. Tap **Start bridge**. The app shows a URL (`http://<phone-ip>:8765`) and a token.
3. Set `ANDROID_BRIDGE_URL` and `ANDROID_BRIDGE_TOKEN` in the agent environment.
4. Call `android_ping` to confirm connectivity.

The phone must be on the same LAN or VPN as the agent.

## Tools (this build)
- `android_ping` — connectivity + device info
- `android_read_screen` — accessibility tree (each node has an `id` usable by `android_tap`)
- `android_tap` — tap by `(x, y)` or `node_id`
- `android_type` — type into the focused field
```

- [ ] **Step 4: Verify the package imports and the Python suite is green**

Run: `python -c "import plugin; print('register' in dir(plugin))" && python -m pytest -q`
Expected: prints `True`; all Python tests pass.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: register android tools as a hermes-agent plugin"
```

---

## Task 19: CI workflow

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Write the workflow** (Python unit tests + Kotlin unit tests + APK build)

```yaml
name: CI
on:
  push:
  pull_request:

jobs:
  python:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: "3.12"
      - run: pip install -e ".[dev]"
      - run: python -m pytest -q

  android:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
      - name: Unit tests
        working-directory: android
        run: ./gradlew testDebugUnitTest
      - name: Assemble debug APK
        working-directory: android
        run: ./gradlew assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: hermes-android-debug-apk
          path: android/app/build/outputs/apk/debug/*.apk
```

- [ ] **Step 2: Validate locally** (the commands CI runs)

Run: `python -m pytest -q && cd android && ./gradlew testDebugUnitTest assembleDebug && cd ..`
Expected: Python suite PASS; Gradle `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
jj commit -m "ci: run python + kotlin tests and build apk"
```

---

## Final verification (whole plan)

- [ ] **Run the complete suite**

Run: `mise run test-py && mise run test-android && mise run build-apk`
Expected: Python tests PASS; Kotlin unit tests PASS; APK built at
`android/app/build/outputs/apk/debug/hermes-android-1.0.0.apk`.

- [ ] **On-device smoke (optional, needs a phone over ADB)**

Run: `mise run build-apk && adb install -r android/app/build/outputs/apk/debug/hermes-android-1.0.0.apk && mise run test-device`
Expected: app installs; auth-gate instrumented tests PASS; success-path tests PASS
once the accessibility service is enabled.

- [ ] **Manual end-to-end**

1. Open the app, tap **Enable accessibility**, toggle Hermes Bridge on.
2. Tap **Start bridge**; note the URL + token.
3. On the agent host: `export ANDROID_BRIDGE_URL=http://<ip>:8765 ANDROID_BRIDGE_TOKEN=<token>`.
4. Drive `android_ping`, `android_read_screen`, `android_tap`, `android_type` and
   confirm real effects on the phone.

---

## What Plan 1 delivers

A working, authenticated, background-surviving bridge with a four-tool vertical slice
(`ping`, `read_screen`, `tap`, `type`) proving the relay-free architecture end-to-end,
plus the reusable spine — `CommandExecutor`, RAII node helpers, `ScreenReader`,
`ActionExecutor`, auth, server, foreground service, and the Python client/plugin — that
Plans 2–5 extend with the remaining ~33 tools.

**Bugs fixed and regression-tested in this plan:** #1 (node leak), #2 (async lock),
#3 (single-consumer executor), #5 (mandatory token), #6 (foreground service), #7 (wake
lock), #10 (no_focused_field), #11 (response cap), #13 (stable hash). Remaining bugs
(#4 relay deleted — already moot; #8 activity leak — avoided by design; #9 future leak —
moot; #12 temp files; #14 listener races) are addressed as their features land in
Plans 3 and 5.
