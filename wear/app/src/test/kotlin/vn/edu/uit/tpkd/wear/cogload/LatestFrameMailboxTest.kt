// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

class LatestFrameMailboxTest {
    @Test
    fun retainsOnlyNewestFrameWhileInferenceIsBusy() {
        val processed = mutableListOf<Int>()
        val discarded = mutableListOf<Int>()
        val completions = ArrayDeque<() -> Unit>()
        val mailbox = LatestFrameMailbox(
            executor = Executor(Runnable::run),
            processor = { item, completion ->
                processed += item
                completions += completion
            },
            discard = discarded::add,
        )
        mailbox.start()

        mailbox.offer(1)
        mailbox.offer(2)
        mailbox.offer(3)
        assertEquals(listOf(1), processed)
        assertEquals(listOf(2), discarded)

        completions.removeFirst().invoke()
        assertEquals(listOf(1, 3), processed)
        completions.removeFirst().invoke()
    }

    @Test
    fun stopDropsPendingAndRejectsNewFrames() {
        val discarded = mutableListOf<Int>()
        val completions = ArrayDeque<() -> Unit>()
        val mailbox = LatestFrameMailbox(
            executor = Executor(Runnable::run),
            processor = { _, completion -> completions += completion },
            discard = discarded::add,
        )
        mailbox.start()
        mailbox.offer(1)
        mailbox.offer(2)
        mailbox.stop()
        mailbox.offer(3)

        assertEquals(listOf(2, 3), discarded)
        completions.removeFirst().invoke()
    }
}
