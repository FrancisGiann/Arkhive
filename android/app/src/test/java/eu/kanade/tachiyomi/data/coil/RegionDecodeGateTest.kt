package eu.kanade.tachiyomi.data.coil

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RegionDecodeGateTest {
    @Test
    fun canceledQueuedViewportDecodeDoesNotRunBeforeTheLatestRequest() = runTest {
        val gate = RegionDecodeGate(StandardTestDispatcher(testScheduler))
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        var staleDecodeRan = false
        var latestDecodeRan = false

        val first = launch {
            gate.withPermit {
                firstStarted.complete(Unit)
                finishFirst.await()
            }
        }
        runCurrent()
        assertTrue(firstStarted.isCompleted)

        val stale = launch { gate.withPermit { staleDecodeRan = true } }
        runCurrent()
        assertFalse(stale.isCompleted)
        stale.cancel()

        val latest = launch { gate.withPermit { latestDecodeRan = true } }
        runCurrent()
        finishFirst.complete(Unit)
        runCurrent()

        first.join()
        stale.join()
        latest.join()
        assertFalse(staleDecodeRan)
        assertTrue(latestDecodeRan)
    }

    @Test
    fun failedRegionDecodeReleasesPermitForTheNextViewport() = runTest {
        val gate = RegionDecodeGate(StandardTestDispatcher(testScheduler))
        var failure: Throwable? = null
        var nextDecodeRan = false

        val broken = launch {
            try {
                gate.withPermit { throw IOException("controlled native decode failure") }
            } catch (error: IOException) {
                failure = error
            }
        }
        runCurrent()
        broken.join()

        val next = launch { gate.withPermit { nextDecodeRan = true } }
        runCurrent()
        next.join()

        assertTrue(failure is IOException)
        assertTrue(nextDecodeRan)
    }
}
