package com.hermesandroid.bridge.command

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Single-consumer command queue. Every accessibility operation flows through here,
 * so commands execute strictly one at a time on [scope]'s dispatcher — structurally
 * preventing concurrent mutation of accessibility state.
 *
 * @param scope    coroutine scope owning the consumer loop (use a single-thread
 *                 dispatcher in production; a test scope in tests).
 * @param timeoutMs per-command time budget; an over-budget command yields
 *                 [CommandResult.Timeout] and the queue keeps draining.
 * @param handler  runs a single command. May suspend; may throw (throws become
 *                 [CommandResult.Err], the queue continues).
 */
class CommandExecutor(
    scope: CoroutineScope,
    private val timeoutMs: Long,
    private val handler: suspend (Command) -> CommandResult,
) {
    private class Job(val command: Command, val deferred: CompletableDeferred<CommandResult>)

    private val channel = Channel<Job>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (job in channel) {
                val result = try {
                    withTimeout(timeoutMs) { handler(job.command) }
                } catch (e: TimeoutCancellationException) {
                    CommandResult.Timeout("command timed out after ${timeoutMs}ms")
                } catch (e: CancellationException) {
                    throw e // genuine cancellation of the consumer must propagate
                } catch (e: Throwable) {
                    CommandResult.Err("internal_error", e.message ?: e.toString())
                }
                job.deferred.complete(result)
            }
        }
    }

    /** Enqueue [command] and suspend until its result is available. */
    suspend fun submit(command: Command): CommandResult {
        val deferred = CompletableDeferred<CommandResult>()
        channel.send(Job(command, deferred))
        return deferred.await()
    }

    fun close() = channel.close()
}
