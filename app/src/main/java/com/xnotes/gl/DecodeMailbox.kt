package com.xnotes.gl

import java.util.IdentityHashMap

/**
 * One consumer owns source membership; workers only start/complete their exact requests.
 * Values are exclusively owned: completion transfers to this mailbox, receive transfers to its
 * caller, and invalidation disposes only values which have not been received. No disposal runs
 * under the lock. CPU work is independent of the GPU context that will eventually upload it.
 */
internal class DecodeMailbox<K : Any, V : Any>(
    private val dispose: (V) -> Unit,
    private val maxFailures: Int = 2,
    private val now: () -> Long = System::nanoTime,
) {
    internal class Source<K : Any, V : Any>(val key: K) {
        var request: Request<K, V>? = null
        val failures = HashMap<Int, Failure>()
    }

    /** Failed decodes of one target size, and when the latest one settled. */
    internal class Failure(val count: Int, val at: Long)

    internal class Request<K : Any, V : Any>(val source: Source<K, V>, val target: Int) {
        var started = false
        var completed = false
        var receiving = false
        var value: V? = null
    }

    internal class Completion<K : Any, V : Any>(val request: Request<K, V>, val value: V?)

    private val lock = Any()
    private var binding: Any? = null
    private var sources = IdentityHashMap<K, Source<K, V>>()
    private val ready = LinkedHashSet<Request<K, V>>()
    private var closed = false

    init { require(maxFailures > 0) }

    val pendingCount: Int get() = synchronized(lock) { sources.values.count { it.request != null } }

    /** Same-binding rebuilds retain shared sources; a new binding revokes even identical keys. */
    fun retainSources(nextBinding: Any, keys: Collection<K>) {
        val discarded = ArrayList<V>()
        try {
            synchronized(lock) {
                if (closed) return
                val next = IdentityHashMap<K, Source<K, V>>()
                for (key in keys) {
                    next[key] = if (binding === nextBinding) sources[key] ?: Source(key) else Source(key)
                }
                for ((key, source) in sources) {
                    if (next[key] !== source) invalidate(source, discarded)
                }
                sources = next
                binding = nextBinding
            }
        } finally {
            disposeAll(discarded)
        }
    }

    fun source(key: K): Source<K, V>? = synchronized(lock) { sources[key].takeUnless { closed } }

    fun isCurrent(source: Source<K, V>): Boolean = synchronized(lock) { owns(source) }

    fun isCurrent(request: Request<K, V>): Boolean = synchronized(lock) { owns(request) }

    fun request(key: K, target: Int): Request<K, V>? {
        var discarded: V? = null
        val request = synchronized(lock) {
            val source = sources[key]?.takeUnless { closed } ?: return@synchronized null
            if (!mayRetry(source.failures[target])) return@synchronized null
            val old = source.request
            if (old != null && old.target >= target) return@synchronized null
            // Work already underway is let finish, so a coarse image shows while the next size is
            // decoded; only a request still waiting in the queue is superseded.
            if (old != null && old.started) return@synchronized null
            val next = Request(source, target)
            if (old != null) {
                ready.remove(old)
                discarded = old.value
                old.value = null
            }
            source.request = next
            next
        }
        discarded?.let(dispose)
        return request
    }

    /** Also makes an executor which accidentally runs the same Runnable twice harmless. */
    fun start(request: Request<K, V>): Boolean = synchronized(lock) {
        if (!owns(request) || request.started || request.completed) return@synchronized false
        request.started = true
        true
    }

    /** A rejected submission cannot cancel work which the executor already started. */
    fun reject(request: Request<K, V>) {
        synchronized(lock) {
            if (!owns(request) || request.started || request.completed) return
            ready.add(request)
            request.completed = true
        }
    }

    /** Null is a failed decode and still completes the pending request. */
    fun complete(request: Request<K, V>, value: V?) {
        var discarded = value
        try {
            synchronized(lock) {
                if (!owns(request) || !request.started || request.completed) return
                ready.add(request)
                request.completed = true
                request.value = value
                discarded = null
            }
        } finally {
            discarded?.let(dispose)
        }
    }

    fun receive(): Completion<K, V>? = synchronized(lock) {
        val request = ready.firstOrNull() ?: return@synchronized null
        val completion = Completion(request, request.value)
        ready.remove(request)
        request.value = null
        request.receiving = true
        completion
    }

    /** Linearizes acceptance, or settles a failure, without touching another pending request. */
    fun finish(request: Request<K, V>, success: Boolean): Boolean = synchronized(lock) {
        if (!owns(request) || !request.receiving) return@synchronized false
        if (success) request.source.failures.remove(request.target)
        else request.source.failures[request.target] = Failure((request.source.failures[request.target]?.count ?: 0) + 1, now())
        request.source.request = null
        request.receiving = false
        true
    }

    /** An explicit recovery boundary, e.g. a new context; never called on every frame. */
    fun retryFailures() = synchronized(lock) { sources.values.forEach { it.failures.clear() } }

    /** Revokes CPU delivery on any thread. It does not delete GPU handles or stop a decoder. */
    fun close() {
        val discarded = ArrayList<V>()
        try {
            synchronized(lock) {
                if (closed) return
                closed = true
                for (source in sources.values) invalidate(source, discarded)
                sources.clear()
                binding = null
            }
        } finally {
            disposeAll(discarded)
        }
    }

    /**
     * A failure can be passing (memory pressure, a file still being written), so after the first
     * [maxFailures] the size is retried with a doubling wait rather than given up on for good.
     */
    private fun mayRetry(failure: Failure?): Boolean {
        if (failure == null || failure.count < maxFailures) return true
        val doublings = (failure.count - maxFailures).coerceAtMost(MAX_BACKOFF_DOUBLINGS)
        val wait = (RETRY_BACKOFF_NANOS shl doublings).coerceAtMost(MAX_RETRY_BACKOFF_NANOS)
        return now() - failure.at >= wait
    }

    private fun owns(source: Source<K, V>): Boolean = !closed && sources[source.key] === source
    private fun owns(request: Request<K, V>): Boolean = owns(request.source) && request.source.request === request

    /** Caller holds lock. Received values are already null and belong solely to the consumer. */
    private fun invalidate(source: Source<K, V>, discarded: MutableList<V>) {
        val request = source.request ?: return
        request.value?.let(discarded::add)
        request.value = null
        ready.remove(request)
        source.request = null
    }

    private fun disposeAll(values: List<V>) {
        var failure: Throwable? = null
        for (value in values) {
            try { dispose(value) } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    companion object {
        /** First wait before retrying a size that failed [maxFailures] times. */
        const val RETRY_BACKOFF_NANOS = 2_000_000_000L
        const val MAX_RETRY_BACKOFF_NANOS = 60_000_000_000L
        private const val MAX_BACKOFF_DOUBLINGS = 5
    }
}
