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
    private class State(var count: Int, var windowStart: Long, @field:Volatile var blockedUntil: Long)
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
