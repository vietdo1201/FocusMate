// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import java.util.concurrent.Executor

/**
 * A one-inference mailbox: while an item is in flight, only the newest pending
 * item is retained. The processor must call completion exactly once.
 */
internal class LatestFrameMailbox<T>(
    private val executor: Executor,
    private val processor: (item: T, completion: () -> Unit) -> Unit,
    private val discard: (T) -> Unit = {},
) {
    private val lock = Any()
    private var running = false
    private var inFlight = false
    private var pending: T? = null

    fun start() = synchronized(lock) {
        running = true
    }

    fun offer(item: T) {
        var launch: T? = null
        var dropped: T? = null
        synchronized(lock) {
            if (!running) {
                dropped = item
            } else if (inFlight) {
                dropped = pending
                pending = item
            } else {
                inFlight = true
                launch = item
            }
        }
        dropped?.let(discard)
        launch?.let(::dispatch)
    }

    fun stop() {
        val dropped = synchronized(lock) {
            running = false
            pending.also { pending = null }
        }
        dropped?.let(discard)
    }

    private fun dispatch(item: T) {
        executor.execute {
            var completed = false
            val completion = {
                val shouldComplete = synchronized(lock) {
                    if (completed) false else true.also { completed = true }
                }
                if (shouldComplete) completeOne()
            }
            try {
                processor(item, completion)
            } catch (error: Throwable) {
                discard(item)
                completion()
            }
        }
    }

    private fun completeOne() {
        val next = synchronized(lock) {
            if (running && pending != null) {
                pending.also { pending = null }
            } else {
                if (!running) pending = null
                inFlight = false
                null
            }
        }
        next?.let(::dispatch)
    }
}
