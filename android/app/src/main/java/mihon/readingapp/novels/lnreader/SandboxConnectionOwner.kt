package mihon.readingapp.novels.lnreader

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializes ownership of an asynchronously-created, process-wide service connection.
 *
 * Cancelling an acquisition abandons only its waiter. The connection future continues because
 * some providers cannot safely cancel a bind after it has started. If that bind completes late,
 * its resource is closed before the next acquisition may start.
 */
internal class SandboxConnectionOwner<T : AutoCloseable> {
    private val lock = Any()
    private var current: Attempt<T>? = null
    private var terminalFailure: Throwable? = null

    fun acquire(connect: () -> CompletableFuture<T>): CompletableFuture<Lease<T>> {
        val result = CompletableFuture<Lease<T>>()
        var start: Attempt<T>? = null
        var gate: CompletableFuture<Unit>? = null
        var failed: Throwable? = null
        synchronized(lock) {
            failed = terminalFailure
            if (failed == null) {
                val attempt = current
                if (attempt == null) {
                    start = Attempt(result)
                    current = start
                } else {
                    gate = attempt.released
                }
            }
        }
        failed?.let {
            result.completeExceptionally(it)
            return result
        }
        gate?.let {
            awaitReleaseThenRetry(it, result, connect)
            return result
        }

        val attempt = checkNotNull(start)
        result.whenComplete { _, _ ->
            if (result.isCancelled) detach(attempt, result)
        }
        begin(attempt, connect)
        return result
    }

    private fun begin(attempt: Attempt<T>, connect: () -> CompletableFuture<T>) {
        val shouldStart = synchronized(lock) {
            if (current !== attempt) false
            else if (attempt.waiter == null || attempt.waiter?.isCancelled == true) {
                current = null
                false
            } else {
                true
            }
        }
        if (!shouldStart) {
            attempt.released.complete(Unit)
            return
        }

        val connection = try {
            connect()
        } catch (failure: Throwable) {
            finishFailure(attempt, failure)
            return
        }
        connection.whenComplete { resource, failure ->
            if (failure != null) finishFailure(attempt, unwrap(failure))
            else if (resource == null) finishFailure(attempt, IllegalStateException("Connection completed without a resource."))
            else finishSuccess(attempt, resource)
        }
    }

    private fun finishSuccess(attempt: Attempt<T>, resource: T) {
        var waiter: CompletableFuture<Lease<T>>? = null
        var lease: Lease<T>? = null
        var abandon = false
        synchronized(lock) {
            if (current !== attempt) {
                abandon = true
            } else {
                val candidate = attempt.waiter
                attempt.waiter = null
                if (candidate != null && !candidate.isCancelled) {
                    waiter = candidate
                    lease = Lease(resource) { closeAndRelease(attempt, resource) }
                } else {
                    abandon = true
                }
            }
        }
        if (abandon) {
            closeAndRelease(attempt, resource)
            return
        }

        val delivered = checkNotNull(lease)
        if (!checkNotNull(waiter).complete(delivered)) {
            // Cancellation raced with delivery; the caller never took ownership.
            delivered.close()
        }
    }

    private fun finishFailure(attempt: Attempt<T>, failure: Throwable) {
        val waiter = synchronized(lock) {
            if (current !== attempt) null
            else {
                attempt.waiter.also { attempt.waiter = null }
            }
        }
        waiter?.completeExceptionally(failure)
        synchronized(lock) {
            if (current === attempt) current = null
        }
        attempt.released.complete(Unit)
    }

    private fun closeAndRelease(attempt: Attempt<T>, resource: T) {
        var closeFailure: Throwable? = null
        try {
            resource.close()
        } catch (failure: Throwable) {
            closeFailure = failure
        }
        synchronized(lock) {
            if (closeFailure != null && terminalFailure == null) {
                // The provider's process-wide bind state may still be held if close did not finish.
                terminalFailure = closeFailure
            }
            if (current === attempt) current = null
        }
        if (closeFailure == null) {
            attempt.released.complete(Unit)
        } else {
            attempt.released.completeExceptionally(closeFailure)
            throw closeFailure
        }
    }

    private fun detach(attempt: Attempt<T>, waiter: CompletableFuture<Lease<T>>) {
        synchronized(lock) {
            if (current === attempt && attempt.waiter === waiter) {
                attempt.waiter = null
            }
        }
    }

    private fun awaitReleaseThenRetry(
        gate: CompletableFuture<Unit>,
        result: CompletableFuture<Lease<T>>,
        connect: () -> CompletableFuture<T>,
    ) {
        gate.whenComplete { _, releaseFailure ->
            if (result.isCancelled) return@whenComplete
            if (releaseFailure != null) {
                result.completeExceptionally(unwrap(releaseFailure))
                return@whenComplete
            }
            val next = acquire(connect)
            result.whenComplete { _, _ ->
                if (result.isCancelled) next.cancel(false)
            }
            next.whenComplete { lease, failure ->
                if (failure != null) {
                    result.completeExceptionally(unwrap(failure))
                } else if (!result.complete(checkNotNull(lease))) {
                    // The queued caller canceled while its fresh connection was completing.
                    lease.close()
                }
            }
        }
    }

    private fun unwrap(failure: Throwable): Throwable = when (failure) {
        is CompletionException, is ExecutionException -> failure.cause?.let(::unwrap) ?: failure
        else -> failure
    }

    private class Attempt<T : AutoCloseable>(
        var waiter: CompletableFuture<Lease<T>>?,
    ) {
        val released = CompletableFuture<Unit>()
    }

    internal class Lease<T : AutoCloseable> internal constructor(
        val resource: T,
        private val release: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }
}
