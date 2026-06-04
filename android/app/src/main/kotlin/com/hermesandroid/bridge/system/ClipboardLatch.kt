package com.hermesandroid.bridge.system

import android.content.Context
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Synchronisation point between the HTTP handler thread and [ClipboardFloatingActivity].
 * [request] starts the overlay activity and blocks (up to 3 s) for the result.
 * [deliver] is called from the activity's main-thread callback with the clipboard text.
 */
internal object ClipboardLatch {
    private val pending = AtomicReference<CompletableFuture<String?>?>()

    fun request(context: Context): String? {
        val future = CompletableFuture<String?>()
        pending.set(future)
        context.startActivity(ClipboardFloatingActivity.intent(context))
        return try {
            future.get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        } finally {
            pending.set(null)
        }
    }

    fun deliver(text: String?) {
        pending.get()?.complete(text)
    }
}
