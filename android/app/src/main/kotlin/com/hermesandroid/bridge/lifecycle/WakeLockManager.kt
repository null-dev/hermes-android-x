package com.hermesandroid.bridge.lifecycle

import android.content.Context
import android.os.PowerManager

/** Partial wake lock held only for the duration of a single command (bug #7). */
class WakeLockManager(context: Context) {
    private val lock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hermes:bridge-command")
        .apply { setReferenceCounted(false) }

    suspend fun <R> around(block: suspend () -> R): R {
        @Suppress("WakelockTimeout") // bounded by the per-command executor timeout instead
        lock.acquire()
        try {
            return block()
        } finally {
            if (lock.isHeld) lock.release()
        }
    }
}
