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
