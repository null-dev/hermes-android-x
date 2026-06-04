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
