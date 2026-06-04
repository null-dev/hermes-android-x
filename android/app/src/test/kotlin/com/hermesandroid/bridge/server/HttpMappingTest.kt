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
