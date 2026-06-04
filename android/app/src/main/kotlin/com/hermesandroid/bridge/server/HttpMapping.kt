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
