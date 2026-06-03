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
        val bytes = ByteArray(20) // 160 bits
        random.nextBytes(bytes)
        return base32(bytes)
    }

    private fun base32(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1f
                sb.append(alphabet[index])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1f
            sb.append(alphabet[index])
        }
        return sb.toString()
    }
}
