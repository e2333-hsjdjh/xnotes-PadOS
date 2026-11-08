package com.xnotes.gl

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * One producer stages complete scene updates while a consumer reads only published envelopes.
 * The queue owns its list containers; elements must already obey their own ownership contract.
 * Preparation is producer-only and cannot post or start another batch on this queue.
 */
internal class SceneUpdateQueue<E : Any>(
    private val prepareBatch: (List<E>) -> List<E> = { it },
) {
    class Update<E : Any> internal constructor(val edits: List<E>)

    private class Staging<E : Any> {
        val edits = ArrayList<E>()
        val callbacks = ArrayList<() -> Unit>()
        var failure: Throwable? = null

        fun fail(error: Throwable) {
            if (failure == null) failure = error
        }

        fun checkSuccessful() {
            failure?.let { throw IllegalStateException("The scene batch failed before publication", it) }
        }
    }

    private val ownerThread = Thread.currentThread()
    private val pending = ConcurrentLinkedQueue<Update<E>>()
    private var staged: Staging<E>? = null
    private var preparing = false

    /** A standalone edit is not passed through [prepareBatch]. */
    fun post(edit: E) {
        checkProducer()
        val current = staged
        if (current == null) {
            enqueueFrozen(listOf(edit))
        } else {
            try {
                current.edits.add(edit)
            } catch (error: Throwable) {
                current.fail(error)
                throw error
            }
        }
    }

    /**
     * Nested calls share one outer transaction. Any nested failure invalidates the whole batch,
     * even when the caller catches it. Empty outer batches neither prepare nor run callbacks.
     * Callbacks are registered at entry, run after publication with staging restored, and never
     * turn an already published update into a rollback if they fail.
     */
    fun batch(afterCommit: (() -> Unit)? = null, block: () -> Unit) {
        checkProducer()
        val outer = staged
        if (outer != null) {
            try {
                afterCommit?.let(outer.callbacks::add)
                block()
            } catch (error: Throwable) {
                outer.fail(error)
                throw error
            }
            return
        }

        val current = Staging<E>()
        var committedCallbacks: List<() -> Unit> = emptyList()
        staged = current
        try {
            afterCommit?.let(current.callbacks::add)
            block()
            current.checkSuccessful()
            if (current.edits.isNotEmpty()) {
                val input = current.edits.toList()
                val prepared = try {
                    preparing = true
                    prepareBatch(input)
                } finally {
                    preparing = false
                }
                current.checkSuccessful()
                // Allocate callback storage before enqueue: nothing after publication can abort it.
                val callbacks = current.callbacks.toList()
                enqueueFrozen(prepared)
                committedCallbacks = callbacks
            }
        } finally {
            staged = null
        }
        runCallbacks(committedCallbacks)
    }

    /** The consumer can continue reading older updates while the producer prepares another one. */
    fun poll(): Update<E>? = pending.poll()

    private fun enqueueFrozen(edits: List<E>) {
        val owned = edits.toList()
        check(owned.isNotEmpty()) { "Preparation cannot discard a nonempty scene batch" }
        pending.add(Update(owned))
    }

    private fun checkProducer() {
        check(Thread.currentThread() === ownerThread) { "SceneUpdateQueue requires its producer thread" }
        if (preparing) {
            val error = IllegalStateException("Scene batch preparation cannot reenter its producer")
            staged?.fail(error)
            throw error
        }
    }

    private fun runCallbacks(callbacks: List<() -> Unit>) {
        var failure: Throwable? = null
        for (callback in callbacks) {
            try {
                callback()
            } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error
                else if (first !== error) first.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
