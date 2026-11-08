package com.xnotes.gl

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SceneUpdateQueueTest {
    @Test fun nestedSuccessPublishesOnePreparedUpdateInOrder() {
        val queue = SceneUpdateQueue<String> { listOf("reserve") + it }
        queue.post("earlier")
        queue.batch {
            queue.post("first")
            queue.batch { queue.post("second") }
            queue.post("last")
        }

        assertEquals(listOf("earlier"), queue.poll()!!.edits)
        assertEquals(listOf("reserve", "first", "second", "last"), queue.poll()!!.edits)
        assertNull(queue.poll())
    }

    @Test fun consumerCanReadEarlierUpdatesButCannotObserveAPartialBatch() {
        val queue = SceneUpdateQueue<String>()
        queue.post("earlier")
        val staged = CountDownLatch(1)
        val inspected = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val consumer = Thread {
            try {
                assertTrue(staged.await(5, TimeUnit.SECONDS))
                assertEquals(listOf("earlier"), queue.poll()!!.edits)
                assertNull(queue.poll())
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                inspected.countDown()
            }
        }.apply { isDaemon = true }
        consumer.start()
        try {
            queue.batch {
                queue.post("first")
                staged.countDown()
                assertTrue(inspected.await(5, TimeUnit.SECONDS))
                failure.get()?.let { throw it }
                queue.post("last")
            }
        } finally {
            staged.countDown()
            consumer.join(5_000)
        }
        assertFalse(consumer.isAlive)
        failure.get()?.let { throw it }
        assertEquals(listOf("first", "last"), queue.poll()!!.edits)
        assertNull(queue.poll())
    }

    @Test fun preparationDoesNotPreventTheConsumerFromReadingCommittedUpdates() {
        val preparing = CountDownLatch(1)
        val inspected = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val queue = SceneUpdateQueue<String> { edits ->
            preparing.countDown()
            assertTrue(inspected.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            edits
        }
        queue.post("earlier")
        val consumer = Thread {
            try {
                assertTrue(preparing.await(5, TimeUnit.SECONDS))
                assertEquals(listOf("earlier"), queue.poll()!!.edits)
                assertNull(queue.poll())
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                inspected.countDown()
            }
        }.apply { isDaemon = true }
        consumer.start()
        try {
            queue.batch { queue.post("prepared") }
        } finally {
            preparing.countDown()
            consumer.join(5_000)
        }
        assertFalse(consumer.isAlive)
        failure.get()?.let { throw it }
        assertEquals(listOf("prepared"), queue.poll()!!.edits)
        assertNull(queue.poll())
    }

    @Test fun propagatedNestedFailureKeepsItsIdentityAndPublishesNothingFromTheBatch() {
        val queue = SceneUpdateQueue<String>()
        val original = IllegalArgumentException("nested")
        var callbacks = 0
        queue.post("earlier")

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            queue.batch(afterCommit = { callbacks++ }) {
                queue.post("outer")
                queue.batch(afterCommit = { callbacks++ }) {
                    queue.post("inner")
                    throw original
                }
            }
        }

        assertSame(original, thrown)
        assertEquals(0, callbacks)
        assertEquals(listOf("earlier"), queue.poll()!!.edits)
        assertNull(queue.poll())
        queue.post("later")
        assertEquals(listOf("later"), queue.poll()!!.edits)
    }

    @Test fun catchingNestedFailuresCannotCommitTheOuterBatch() {
        val queue = SceneUpdateQueue<String>()
        val first = IllegalArgumentException("first")
        val second = IllegalArgumentException("second")
        var callbacks = 0
        val thrown = assertThrows(IllegalStateException::class.java) {
            queue.batch(afterCommit = { callbacks++ }) {
                queue.post("before")
                for (error in listOf(first, second)) {
                    try {
                        queue.batch(afterCommit = { callbacks++ }) {
                            queue.post("nested")
                            throw error
                        }
                    } catch (_: IllegalArgumentException) {
                        // Recovery inside the block cannot turn this into a complete scene update.
                    }
                }
                queue.post("after")
            }
        }

        assertSame(first, thrown.cause)
        assertEquals(0, callbacks)
        assertNull(queue.poll())
        queue.batch { queue.post("fresh") }
        assertEquals(listOf("fresh"), queue.poll()!!.edits)
    }

    @Test fun prepareFailureDoesNotPublishAndTheQueueCanBeReused() {
        val original = IllegalArgumentException("prepare")
        var failPreparation = true
        var callbacks = 0
        val queue = SceneUpdateQueue<String> {
            if (failPreparation) throw original
            listOf("reserve") + it
        }
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            queue.batch(afterCommit = { callbacks++ }) { queue.post("discarded") }
        }
        assertSame(original, thrown)
        assertEquals(0, callbacks)
        assertNull(queue.poll())

        failPreparation = false
        queue.batch(afterCommit = { callbacks++ }) { queue.post("fresh") }
        assertEquals(1, callbacks)
        assertEquals(listOf("reserve", "fresh"), queue.poll()!!.edits)
    }

    @Test fun preparationCannotSilentlyDiscardANonemptyBatch() {
        val queue = SceneUpdateQueue<String> { emptyList() }
        var callbacks = 0
        assertThrows(IllegalStateException::class.java) {
            queue.batch(afterCommit = { callbacks++ }) { queue.post("required") }
        }
        assertEquals(0, callbacks)
        assertNull(queue.poll())
        queue.post("standalone")
        assertEquals(listOf("standalone"), queue.poll()!!.edits)
    }

    @Test fun publishedContainersDoNotAliasPreparationOutputOrTheNextBatch() {
        val prepared = mutableListOf<String>()
        val queue = SceneUpdateQueue<String> {
            prepared.clear()
            prepared.addAll(it)
            prepared
        }
        queue.batch { queue.post("first"); queue.post("second") }
        val first = queue.poll()!!
        prepared.clear()
        prepared.add("caller mutation")
        queue.batch { queue.post("next") }
        val next = queue.poll()!!
        prepared.clear()

        assertEquals(listOf("first", "second"), first.edits)
        assertEquals(listOf("next"), next.edits)
        assertNull(queue.poll())
    }

    @Test fun onlyTheOwnerCanPostOrStartABatch() {
        val queue = SceneUpdateQueue<String>()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                assertThrows(IllegalStateException::class.java) { queue.post("wrong thread") }
                assertThrows(IllegalStateException::class.java) { queue.batch { queue.post("wrong") } }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }.apply { isDaemon = true }
        worker.start()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        failure.get()?.let { throw it }
        assertNull(queue.poll())
        queue.post("owner")
        assertEquals(listOf("owner"), queue.poll()!!.edits)
    }

    @Test fun preparationCannotHidePostOrBatchReentryByCatchingTheError() {
        for (startBatch in listOf(false, true)) {
            var rejected: Throwable? = null
            lateinit var queue: SceneUpdateQueue<String>
            queue = SceneUpdateQueue { edits ->
                rejected = assertThrows(IllegalStateException::class.java) {
                    if (startBatch) queue.batch { queue.post("reentrant") }
                    else queue.post("reentrant")
                }
                edits
            }
            val thrown = assertThrows(IllegalStateException::class.java) {
                queue.batch { queue.post("outer") }
            }
            assertSame(rejected, thrown.cause)
            assertNull(queue.poll())
            queue.post("after failure")
            assertEquals(listOf("after failure"), queue.poll()!!.edits)
        }
    }

    @Test fun callbacksRunOnlyAfterOuterCommitInRegistrationOrderWithStagingRestored() {
        val calls = mutableListOf<String>()
        val queue = SceneUpdateQueue<String>()
        queue.batch(afterCommit = {
            calls.add("outer")
            queue.post("from callback")
        }) {
            queue.post("outer")
            queue.batch(afterCommit = { calls.add("inner") }) { queue.post("inner") }
            assertTrue(calls.isEmpty())
        }

        assertEquals(listOf("outer", "inner"), calls)
        assertEquals(listOf("outer", "inner"), queue.poll()!!.edits)
        assertEquals(listOf("from callback"), queue.poll()!!.edits)
        assertNull(queue.poll())
    }

    @Test fun emptyOuterBatchNeitherPreparesPublishesNorRunsCallbacks() {
        var preparations = 0
        var callbacks = 0
        val queue = SceneUpdateQueue<String> { preparations++; it }
        queue.batch(afterCommit = { callbacks++ }) {
            queue.batch(afterCommit = { callbacks++ }) { }
        }
        assertEquals(0, preparations)
        assertEquals(0, callbacks)
        assertNull(queue.poll())
    }

    @Test fun successfulEmptyNestedBatchRunsItsCallbackWhenTheOuterBatchPublishes() {
        val calls = mutableListOf<String>()
        val queue = SceneUpdateQueue<String>()
        queue.batch(afterCommit = { calls.add("outer") }) {
            queue.batch(afterCommit = { calls.add("inner") }) { }
            queue.post("content")
        }
        assertEquals(listOf("outer", "inner"), calls)
        assertEquals(listOf("content"), queue.poll()!!.edits)
    }

    @Test fun callbackFailuresDoNotRollbackOrPreventOtherCallbacksAndLaterPosts() {
        val first = IllegalArgumentException("first callback")
        val later = IllegalStateException("later callback")
        val calls = mutableListOf<String>()
        val queue = SceneUpdateQueue<String>()
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            queue.batch(afterCommit = { calls.add("outer"); throw first }) {
                queue.batch(afterCommit = { calls.add("same error"); throw first }) { }
                queue.batch(afterCommit = { calls.add("later error"); throw later }) { }
                queue.batch(afterCommit = { calls.add("last") }) { }
                queue.post("committed")
            }
        }

        assertSame(first, thrown)
        assertEquals(listOf("outer", "same error", "later error", "last"), calls)
        assertEquals(listOf(later), thrown.suppressed.toList())
        assertEquals(listOf("committed"), queue.poll()!!.edits)
        queue.post("after callback failure")
        assertEquals(listOf("after callback failure"), queue.poll()!!.edits)
        queue.batch { queue.post("next batch") }
        assertEquals(listOf("next batch"), queue.poll()!!.edits)
    }
}
