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
import io.ktor.server.response.respondTextWriter
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
                get("/screen_hash") {
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.ScreenHashCmd) }
                }
                post("/diff_screen") {
                    val b = gson.fromJson(call.receiveText(), DiffBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.DiffScreen(b.hash)) }
                }
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
                post("/wait") {
                    val b = gson.fromJson(call.receiveText(), WaitBody::class.java)
                    call.guarded {
                        BridgeAccessibilityService.current()!!.submit(
                            Command.Wait(b.text, b.class_name, b.timeout_ms ?: 5_000)
                        )
                    }
                }
                get("/screenshot") {
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.Screenshot) }
                }
                post("/screen_record") {
                    val b = gson.fromJson(call.receiveText(), ScreenRecordBody::class.java)
                    val d = (b.duration_ms ?: 5_000).coerceAtMost(20_000)
                    call.guarded {
                        BridgeAccessibilityService.current()!!.submit(Command.ScreenRecord(d))
                    }
                }
                get("/clipboard") { call.guarded { BridgeAccessibilityService.current()!!.submit(Command.ClipboardRead) } }
                post("/clipboard") {
                    val b = gson.fromJson(call.receiveText(), TextBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.ClipboardWrite(b.text)) }
                }
                post("/intent") {
                    val b = gson.fromJson(call.receiveText(), IntentBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(
                        Command.SendIntent(b.action, b.data, b.extras ?: emptyMap())) }
                }
                post("/broadcast") {
                    val b = gson.fromJson(call.receiveText(), BroadcastBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(
                        Command.Broadcast(b.action, b.extras ?: emptyMap())) }
                }
                post("/sms") {
                    val b = gson.fromJson(call.receiveText(), SmsBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.SendSms(b.number, b.text)) }
                }
                post("/call") {
                    val b = gson.fromJson(call.receiveText(), CallBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.Call(b.number)) }
                }
                get("/contacts") {
                    val q = call.request.queryParameters["q"] ?: ""
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.SearchContacts(q)) }
                }
                get("/location") { call.guarded { BridgeAccessibilityService.current()!!.submit(Command.GetLocation) } }
                post("/media") {
                    val b = gson.fromJson(call.receiveText(), MediaBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.MediaControl(b.action)) }
                }
                post("/speak") {
                    val b = gson.fromJson(call.receiveText(), TextBody::class.java)
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.Speak(b.text)) }
                }
                post("/speak/stop") { call.guarded { BridgeAccessibilityService.current()!!.submit(Command.SpeakStop) } }
                get("/notifications") { call.guarded { BridgeAccessibilityService.current()!!.submit(Command.Notifications) } }
                get("/events") {
                    val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                    call.guarded { BridgeAccessibilityService.current()!!.submit(Command.Events(since)) }
                }
                get("/widgets") { call.guarded { BridgeAccessibilityService.current()!!.submit(Command.Widgets) } }
                get("/events/stream") {
                    val ip = call.request.local.remoteHost
                    when (authenticator.authenticate(ip, call.request.headers["Authorization"])) {
                        AuthResult.Blocked -> call.respond(io.ktor.http.HttpStatusCode.TooManyRequests, mapOf("ok" to false, "error" to "blocked"))
                        AuthResult.Unauthorized -> call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("ok" to false, "error" to "unauthorized"))
                        AuthResult.Ok -> {
                            call.respondTextWriter(contentType = io.ktor.http.ContentType.Text.EventStream) {
                                var lastSeq = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                                try {
                                    while (true) {
                                        val batch = com.hermesandroid.bridge.event.EventBus.events.since(lastSeq)
                                        for (e in batch) {
                                            lastSeq = e.seq
                                            write("data: ${gson.toJson(e)}\n\n")
                                            flush()
                                        }
                                        kotlinx.coroutines.delay(500)
                                    }
                                } catch (e: Exception) {
                                    // client disconnected — end the stream
                                }
                            }
                        }
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
    private data class TapTextBody(val text: String, val exact: Boolean?)
    private data class TypeBody(val text: String?, val clear_first: Boolean?)
    private data class LongPressBody(val x: Int?, val y: Int?, val node_id: String?, val duration_ms: Long?)
    private data class DragBody(val from_x: Int, val from_y: Int, val to_x: Int, val to_y: Int, val duration_ms: Long?)
    private data class PinchBody(val x: Int, val y: Int, val scale: Double)
    private data class SwipeBody(val direction: String, val distance: Double?)
    private data class ScrollBody(val direction: String, val node_id: String?)
    private data class DiffBody(val hash: String)
    private data class OpenAppBody(val package_name: String)
    private data class PressKeyBody(val key: String)
    private data class WaitBody(val text: String?, val class_name: String?, val timeout_ms: Long?)
    private data class ScreenRecordBody(val duration_ms: Long?)

    private data class TextBody(val text: String)
    private data class IntentBody(val action: String, val data: String?, val extras: Map<String, String>?)
    private data class BroadcastBody(val action: String, val extras: Map<String, String>?)
    private data class SmsBody(val number: String, val text: String)
    private data class CallBody(val number: String)
    private data class MediaBody(val action: String)

    private fun parseDirection(s: String) =
        com.hermesandroid.bridge.accessibility.Direction.valueOf(s.uppercase())
}
