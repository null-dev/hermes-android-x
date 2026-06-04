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
        assertEquals("token length", 18, first.length)
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
    fun tokenUsesReadableAlphaNumericCharset() {
        val token = TokenStore(FakeStorage()).getOrCreate()
        val allowed = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789".toSet()
        assertEquals(18, token.length)
        assertTrue(token.all { it in allowed })
        assertTrue(token.none { it in "Il1Oo0" })
    }
}
