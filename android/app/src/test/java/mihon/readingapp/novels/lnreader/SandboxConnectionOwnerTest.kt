package mihon.readingapp.novels.lnreader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SandboxConnectionOwnerTest {
    @Test
    fun canceledPendingWaiterDoesNotCancelBindAndLateResourceClosesBeforeRetry() {
        val owner = SandboxConnectionOwner<FakeConnection>()
        val firstBind = CompletableFuture<FakeConnection>()
        val retryBind = CompletableFuture<FakeConnection>()
        val bindCount = AtomicInteger()

        val abandoned = owner.acquire {
            bindCount.incrementAndGet()
            firstBind
        }
        abandoned.cancel(true)
        assertFalse(firstBind.isCancelled, "The provider's bind future must remain alive after the caller leaves.")

        val retry = owner.acquire {
            bindCount.incrementAndGet()
            retryBind
        }
        assertEquals(1, bindCount.get(), "A second bind must wait for orphan cleanup.")
        assertFalse(retry.isDone)

        val late = FakeConnection()
        firstBind.complete(late)
        assertEquals(1, late.closeCount, "The late connection must close before retry starts.")
        assertEquals(2, bindCount.get())
        assertFalse(retryBind.isCancelled)

        val recovered = FakeConnection()
        retryBind.complete(recovered)
        val lease = retry.get()
        assertSame(recovered, lease.resource)
        lease.close()
        lease.close()
        assertEquals(1, recovered.closeCount, "A lease closes its connection once even if finally paths repeat.")
    }

    @Test
    fun canceledQueuedCallerDoesNotStartAnotherBindAfterOrphanCleanup() {
        val owner = SandboxConnectionOwner<FakeConnection>()
        val pendingBind = CompletableFuture<FakeConnection>()
        var bindCount = 0
        val abandoned = owner.acquire {
            bindCount++
            pendingBind
        }
        abandoned.cancel(true)
        val queued = owner.acquire {
            bindCount++
            CompletableFuture.completedFuture(FakeConnection())
        }
        queued.cancel(true)

        val late = FakeConnection()
        pendingBind.complete(late)
        assertEquals(1, late.closeCount)
        assertEquals(1, bindCount, "Canceled queued work must not bind after cleanup.")

        val recovered = FakeConnection()
        val lease = owner.acquire {
            bindCount++
            CompletableFuture.completedFuture(recovered)
        }.get()
        assertEquals(2, bindCount)
        lease.close()
        assertEquals(1, recovered.closeCount)
    }

    @Test
    fun cancellationRacingWithConnectionDeliveryClosesOrTransfersExactlyOnce() {
        val owner = SandboxConnectionOwner<FakeConnection>()
        val connection = CompletableFuture<FakeConnection>()
        val acquisition = owner.acquire { connection }
        val resource = FakeConnection()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val cancellationWon = AtomicReference<Boolean>()

        val cancelThread = Thread {
            ready.countDown()
            start.await()
            cancellationWon.set(acquisition.cancel(true))
        }
        val connectThread = Thread {
            ready.countDown()
            start.await()
            connection.complete(resource)
        }
        cancelThread.start()
        connectThread.start()
        ready.await()
        start.countDown()
        cancelThread.join()
        connectThread.join()

        if (cancellationWon.get() == true) {
            assertEquals(1, resource.closeCount, "An abandoned late resource is closed once.")
        } else {
            acquisition.get().close()
            assertEquals(1, resource.closeCount, "A transferred lease is closed once.")
        }
        val nextResource = FakeConnection()
        owner.acquire { CompletableFuture.completedFuture(nextResource) }.get().close()
        assertEquals(1, nextResource.closeCount)
    }

    @Test
    fun concurrentAcquisitionWaitsForTheCurrentLeaseToClose() {
        val owner = SandboxConnectionOwner<FakeConnection>()
        val firstConnection = FakeConnection()
        val secondConnection = FakeConnection()
        val bindCount = AtomicInteger()

        val first = owner.acquire {
            bindCount.incrementAndGet()
            CompletableFuture.completedFuture(firstConnection)
        }
        val firstLease = first.get()
        val second = owner.acquire {
            bindCount.incrementAndGet()
            CompletableFuture.completedFuture(secondConnection)
        }
        assertEquals(1, bindCount.get())
        assertFalse(second.isDone, "Only one sandbox connection may be leased at a time.")

        firstLease.close()
        val secondLease = second.get()
        assertEquals(2, bindCount.get())
        assertEquals(1, firstConnection.closeCount)
        secondLease.close()
        assertEquals(1, secondConnection.closeCount)
    }

    @Test
    fun providerFailureReleasesWaitersAndAllowsANewBind() {
        val owner = SandboxConnectionOwner<FakeConnection>()
        val firstBind = CompletableFuture<FakeConnection>()
        val recovered = FakeConnection()
        val bindCount = AtomicInteger()
        val failure = IllegalStateException("fixture bind failure")

        val first = owner.acquire {
            bindCount.incrementAndGet()
            firstBind
        }
        val retry = owner.acquire {
            bindCount.incrementAndGet()
            CompletableFuture.completedFuture(recovered)
        }
        firstBind.completeExceptionally(failure)
        val observed = runCatching { first.get() }.exceptionOrNull()
        assertTrue(observed is ExecutionException)
        assertSame(failure, observed?.cause)
        val recoveredLease = retry.get()
        assertSame(recovered, recoveredLease.resource)
        recoveredLease.close()
        assertEquals(1, recovered.closeCount)
        assertEquals(2, bindCount.get())
    }

    @Test
    fun synchronousFactoryFailureDoesNotLeaveOwnerLocked() {
        val owner = SandboxConnectionOwner<FakeConnection>()
        val failure = IllegalArgumentException("fixture factory failure")
        val first = owner.acquire { throw failure }
        assertSame(failure, runCatching { first.get() }.exceptionOrNull()?.cause)

        val recovered = FakeConnection()
        val next = owner.acquire { CompletableFuture.completedFuture(recovered) }
        val lease = next.get()
        assertSame(recovered, lease.resource)
        lease.close()
        assertEquals(1, recovered.closeCount)
    }

    @Test
    fun closeFailureFailsClosedInsteadOfAttemptingAnUnsafeRebind() {
        val owner = SandboxConnectionOwner<FakeConnection>()
        val closeFailure = IllegalStateException("fixture unbind failure")
        val connection = FakeConnection(closeFailure)
        var attempts = 0

        val lease = owner.acquire {
            attempts++
            CompletableFuture.completedFuture(connection)
        }.get()
        assertSame(closeFailure, runCatching { lease.close() }.exceptionOrNull())

        val next = owner.acquire {
            attempts++
            CompletableFuture.completedFuture(FakeConnection())
        }
        assertSame(closeFailure, runCatching { next.get() }.exceptionOrNull()?.cause)
        assertEquals(1, attempts, "Do not bind again when cleanup did not release the process guard.")
    }

    private class FakeConnection(
        private val closeFailure: Throwable? = null,
    ) : AutoCloseable {
        private val closes = AtomicInteger()
        val closeCount: Int get() = closes.get()

        override fun close() {
            closes.incrementAndGet()
            closeFailure?.let { throw it }
        }
    }
}
