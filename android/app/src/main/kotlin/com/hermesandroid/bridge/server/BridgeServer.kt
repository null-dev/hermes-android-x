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
import io.ktor.server.plugins.statuspages.StatusPages
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
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    val (status, error) = when (cause) {
                        is io.ktor.server.plugins.BadRequestException,
                        is com.google.gson.JsonSyntaxException ->
                            io.ktor.http.HttpStatusCode.BadRequest to "bad_request"
                        else ->
                            io.ktor.http.HttpStatusCode.InternalServerError to "internal_error"
                    }
                    call.respond(status, mapOf("ok" to false, "error" to error, "message" to (cause.message ?: "")))
                }
            }
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
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1_000)
        engine = null
    }

    private data class TapBody(val x: Int?, val y: Int?, val node_id: String?)
    private data class TapTextBody(val text: String, val exact: Boolean?)
    private data class TypeBody(val text: String?, val clear_first: Boolean?)
    private data class LongPressBody(val x: Int?, val y: Int?, val node_id: String?, val duration_ms: Long?)
    private data class DragBody(val from_x: Int, val from_y: Int, val to_x: Int, val to_y: Int, val duration_ms: Long?)
    private data class PinchBody(val x: Int, val y: Int, val scale: Double)
    private data class SwipeBody(val direction: String, val distance: Double?)
    private data class ScrollBody(val direction: String, val node_id: String?)

    private fun parseDirection(s: String) =
        com.hermesandroid.bridge.accessibility.Direction.valueOf(s.uppercase())
}
