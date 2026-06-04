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
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        return buildString(capacity = 18) {
            repeat(18) {
                append(alphabet[random.nextInt(alphabet.length)])
            }
        }
    }
}
