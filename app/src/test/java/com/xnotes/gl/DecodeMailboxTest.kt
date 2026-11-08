package com.xnotes.gl

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DecodeMailboxTest {
    private class Value {
        var releases = 0
        fun release() { check(releases++ == 0) { "Released twice" } }
    }

    private class Fixture {
        val key = Any()
        val binding = Any()
        var clock = 0L
        val mailbox = DecodeMailbox<Any, Value>(Value::release, now = { clock })
        init { mailbox.retainSources(binding, listOf(key)) }
        fun request(target: Int = 128) = checkNotNull(mailbox.request(key, target))
        fun complete(request: DecodeMailbox.Request<Any, Value>, value: Value? = Value()): Value? {
            assertTrue(mailbox.start(request))
            mailbox.complete(request, value)
            return value
        }
    }

    @Test fun receiveTransfersOwnershipBeforeCloseAndCommitIsRevoked() {
        val f = Fixture()
        val request = f.request()
        val value = f.complete(request)!!
        val completion = checkNotNull(f.mailbox.receive())
        assertSame(value, completion.value)
        f.mailbox.close()
        assertEquals(0, value.releases)
        assertFalse(f.mailbox.finish(request, true))
        value.release()
        assertEquals(1, value.releases)
        assertEquals(0, f.mailbox.pendingCount)
    }

    @Test fun closeRecyclesQueuedValuesAndRejectsLateWorkerResults() {
        val f = Fixture()
        val queued = f.complete(f.request())!!
        val other = Any()
        f.mailbox.retainSources(f.binding, listOf(f.key, other))
        val running = checkNotNull(f.mailbox.request(other, 128))
        assertTrue(f.mailbox.start(running))
        f.mailbox.close()
        val late = Value()
        f.mailbox.complete(running, late)
        assertEquals(1, queued.releases)
        assertEquals(1, late.releases)
        assertNull(f.mailbox.receive())
        f.mailbox.retainSources(Any(), listOf(f.key))
        f.mailbox.retryFailures()
        assertNull(f.mailbox.request(f.key, 128))
    }

    @Test fun sameBindingRebuildKeepsSourceAndRequest() {
        val f = Fixture()
        val source = f.mailbox.source(f.key)
        val request = f.request()
        val value = f.complete(request)!!
        f.mailbox.retainSources(f.binding, listOf(f.key, f.key))
        assertSame(source, f.mailbox.source(f.key))
        assertSame(request, checkNotNull(f.mailbox.receive()).request)
        assertTrue(f.mailbox.finish(request, true))
        assertEquals(0, value.releases)
        value.release()
    }

    @Test fun newBindingRevokesTheSameImageKeyAndCannotClearItsReplacement() {
        val f = Fixture()
        val old = f.request()
        assertTrue(f.mailbox.start(old))
        f.mailbox.retainSources(Any(), listOf(f.key))
        val next = f.request()
        val late = Value()
        f.mailbox.complete(old, late)
        assertEquals(1, late.releases)
        assertEquals(1, f.mailbox.pendingCount)
        assertFalse(f.mailbox.finish(old, false))
        assertTrue(f.mailbox.start(next))
        f.mailbox.close()
    }

    @Test fun removalAndReentryDoNotReviveAnOldRequest() {
        val f = Fixture()
        val old = f.request()
        f.mailbox.retainSources(f.binding, emptyList())
        f.mailbox.retainSources(f.binding, listOf(f.key))
        val next = f.request()
        assertFalse(f.mailbox.start(old))
        f.mailbox.reject(old)
        assertEquals(1, f.mailbox.pendingCount)
        assertTrue(f.mailbox.start(next))
        f.mailbox.close()
    }

    @Test fun highResolutionSupersedesOnlyALowResolutionStillWaitingInTheQueue() {
        val f = Fixture()
        val low = f.request(128)
        val high = f.request(512)
        assertFalse("a queued request is superseded and never runs", f.mailbox.start(low))
        assertNull(f.mailbox.request(f.key, 128))
        assertEquals(1, f.mailbox.pendingCount)
        val highValue = f.complete(high)!!
        assertSame(high, checkNotNull(f.mailbox.receive()).request)
        assertTrue(f.mailbox.finish(high, true))
        highValue.release()
    }

    @Test fun startedLowResolutionIsDeliveredBeforeAHighResolutionRequest() {
        // Zooming into an image with no texture yet: each finished size shows, rather than the
        // image staying blank until the pinch stops and the last decode lands.
        for (stage in listOf("running", "completed", "received")) {
            val f = Fixture()
            val low = f.request(128)
            assertTrue(f.mailbox.start(low))
            val value = Value()
            if (stage != "running") f.mailbox.complete(low, value)
            if (stage == "received") assertSame(value, checkNotNull(f.mailbox.receive()).value)
            assertNull("$stage: no larger request while the smaller one is underway", f.mailbox.request(f.key, 512))
            assertEquals("$stage: the smaller result is kept", 0, value.releases)
            if (stage == "running") f.mailbox.complete(low, value)
            if (stage != "received") assertSame(value, checkNotNull(f.mailbox.receive()).value)
            assertTrue(f.mailbox.finish(low, true))
            value.release()
            val high = checkNotNull(f.mailbox.request(f.key, 512)) { "$stage: next size once the first settled" }
            assertTrue(f.mailbox.start(high))
            f.mailbox.close()
        }
    }

    @Test fun nullResultsHaveABoundedRetryBudgetWithExplicitRecovery() {
        val f = Fixture()
        repeat(2) {
            val request = f.request()
            f.complete(request, null)
            assertNull(checkNotNull(f.mailbox.receive()).value)
            assertTrue(f.mailbox.finish(request, false))
            assertEquals(0, f.mailbox.pendingCount)
        }
        repeat(100) { assertNull(f.mailbox.request(f.key, 128)) }
        f.clock += DecodeMailbox.RETRY_BACKOFF_NANOS - 1
        assertNull("still inside the first backoff", f.mailbox.request(f.key, 128))
        f.mailbox.retryFailures()
        val recovered = f.request()
        val value = f.complete(recovered)!!
        f.mailbox.receive()
        assertTrue(f.mailbox.finish(recovered, true))
        value.release()
    }

    @Test fun repeatedFailuresAreRetriedAfterADoublingWaitNotNeverAgain() {
        // A large photo can fail on a moment of memory pressure; it must not stay invisible
        // until the GL context happens to be recreated.
        val f = Fixture()
        fun fail() {
            val request = f.request()
            f.complete(request, null)
            f.mailbox.receive()
            assertTrue(f.mailbox.finish(request, false))
        }
        repeat(2) { fail() }
        var wait = DecodeMailbox.RETRY_BACKOFF_NANOS
        repeat(8) { round ->
            f.clock += wait - 1
            assertNull("round $round: inside the wait", f.mailbox.request(f.key, 128))
            f.clock += 1
            fail()
            wait = (wait * 2).coerceAtMost(DecodeMailbox.MAX_RETRY_BACKOFF_NANOS)
        }
        assertEquals("the wait is capped", DecodeMailbox.MAX_RETRY_BACKOFF_NANOS, wait)
        f.clock += wait
        val recovered = f.request()
        val value = f.complete(recovered)!!
        f.mailbox.receive()
        assertTrue(f.mailbox.finish(recovered, true))
        value.release()
        assertNotNull("success clears the failures", f.mailbox.request(f.key, 128))
        f.mailbox.close()
    }

    @Test fun schedulingRejectionSettlesButCannotOverrideAnAlreadyStartedTask() {
        val f = Fixture()
        val rejected = f.request()
        f.mailbox.reject(rejected)
        assertFalse(f.mailbox.start(rejected))
        assertSame(rejected, checkNotNull(f.mailbox.receive()).request)
        assertTrue(f.mailbox.finish(rejected, false))
        val accepted = f.request()
        assertTrue(f.mailbox.start(accepted))
        assertFalse(f.mailbox.start(accepted))
        f.mailbox.reject(accepted)
        assertNull(f.mailbox.receive())
        val value = Value()
        f.mailbox.complete(accepted, value)
        assertSame(value, checkNotNull(f.mailbox.receive()).value)
        assertTrue(f.mailbox.finish(accepted, true))
        value.release()
    }

    @Test fun sourcesUseIdentityEvenWhenKeysCompareEqual() {
        data class Key(val name: String)
        val a = Key("same")
        val b = Key("same")
        val mailbox = DecodeMailbox<Key, Value>(Value::release)
        val binding = Any()
        mailbox.retainSources(binding, listOf(a, b))
        val first = checkNotNull(mailbox.request(a, 128))
        val second = checkNotNull(mailbox.request(b, 128))
        mailbox.retainSources(binding, listOf(b))
        assertFalse(mailbox.start(first))
        assertTrue(mailbox.start(second))
        mailbox.close()
    }

    @Test fun workerCompletingAfterConcurrentCloseRecyclesItsOwnResult() {
        val f = Fixture()
        val request = f.request()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val value = Value()
        val worker = Thread {
            try {
                assertTrue(f.mailbox.start(request))
                started.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
                f.mailbox.complete(request, value)
            } catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            f.mailbox.close()
        } finally {
            finish.countDown()
            worker.join(5_000)
        }
        assertFalse(worker.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertEquals(1, value.releases)
        assertNull(f.mailbox.receive())
    }

    @Test fun cleanupIsOutsideTheLockAndContinuesAfterOneDisposerFails() {
        val keyA = Any()
        val keyB = Any()
        val a = Value()
        val b = Value()
        lateinit var mailbox: DecodeMailbox<Any, Value>
        mailbox = DecodeMailbox({ value ->
            val read = Thread { mailbox.pendingCount }
            read.start()
            read.join(2_000)
            check(!read.isAlive) { "Disposal ran with the mailbox lock held" }
            value.release()
            if (value === a) error("dispose failed")
        })
        mailbox.retainSources(Any(), listOf(keyA, keyB))
        for ((key, value) in listOf(keyA to a, keyB to b)) {
            val request = checkNotNull(mailbox.request(key, 128))
            assertTrue(mailbox.start(request))
            mailbox.complete(request, value)
        }
        assertThrows(IllegalStateException::class.java) { mailbox.close() }
        assertEquals(1, a.releases)
        assertEquals(1, b.releases)
        assertEquals(0, mailbox.pendingCount)
    }
}
