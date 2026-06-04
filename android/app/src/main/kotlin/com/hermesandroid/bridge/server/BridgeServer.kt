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
