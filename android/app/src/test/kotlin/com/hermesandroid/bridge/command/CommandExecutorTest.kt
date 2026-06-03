package com.hermesandroid.bridge.command

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CommandExecutorTest {

    @Test
    fun runsCommandsOneAtATime() = runTest {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val exec = CommandExecutor(this, timeoutMs = 10_000) {
            val now = active.incrementAndGet()
            maxActive.updateAndGet { m -> maxOf(m, now) }
            delay(100)
            active.decrementAndGet()
            CommandResult.Ok(null)
        }
        val jobs = (1..5).map { async { exec.submit(Command.Ping) } }
        jobs.awaitAll()
        assertEquals(1, maxActive.get())
        exec.close()
    }

    @Test
    fun timesOutSlowCommandAndKeepsDraining() = runTest {
        var calls = 0
        val exec = CommandExecutor(this, timeoutMs = 1_000) { cmd ->
            calls++
            if (cmd is Command.ReadScreen) { delay(5_000); CommandResult.Ok("late") }
            else CommandResult.Ok("fast")
        }
        val slow = exec.submit(Command.ReadScreen(false))
        assertTrue("expected Timeout, got $slow", slow is CommandResult.Timeout)
        val fast = exec.submit(Command.Ping)
        assertEquals(CommandResult.Ok("fast"), fast)
        assertEquals(2, calls)
        exec.close()
    }

    @Test
    fun continuesAfterHandlerThrows() = runTest {
        var first = true
        val exec = CommandExecutor(this, timeoutMs = 1_000) {
            if (first) { first = false; throw IllegalStateException("boom") }
            CommandResult.Ok("ok")
        }
        val err = exec.submit(Command.Ping)
        assertTrue(err is CommandResult.Err)
        assertEquals("internal_error", (err as CommandResult.Err).error)
        val ok = exec.submit(Command.Ping)
        assertEquals(CommandResult.Ok("ok"), ok)
        exec.close()
    }
}
