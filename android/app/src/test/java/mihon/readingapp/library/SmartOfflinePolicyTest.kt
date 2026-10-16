package mihon.readingapp.library

import eu.kanade.tachiyomi.data.download.SmartOfflineBlockedException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class SmartOfflinePolicyTest {
    @Test fun rotatingRuleWindowDoesNotStarveRulesAfterTheBatchCap() {
        // Check three consecutive passes across a wrap boundary. The cursor should advance by
        // the window size each time rather than restarting at the beginning after one wrap.
        val keys = (0 until 50).map { "novel:${"%064x".format(it)}" }
        val first = SmartOfflinePolicy.fairWindow(keys, null, 20) { it }
        assertEquals(20, first.size)
        assertEquals(keys.take(20), first)
        val next = SmartOfflinePolicy.fairWindow(keys, first.last(), 20) { it }
        assertEquals(keys.drop(20).take(20), next)
        val wrapped = SmartOfflinePolicy.fairWindow(keys, next.last(), 20) { it }
        assertEquals(keys.drop(40) + keys.take(10), wrapped)
        assertEquals(keys.drop(10).take(20), SmartOfflinePolicy.fairWindow(keys, wrapped.last(), 20) { it })
    }

    @Test fun nextUnreadUsesReadingOrderAndBoundsTheSelectedWindow() {
        // Native manga sourceOrder is newest-first: index 0 is the newest chapter, so a
        // conservative next-unread fallback walks larger indices first (oldest unread).
        val chapters = (10 downTo 1).mapIndexed { index, n ->
            SmartOfflinePolicy.Candidate("c$n", n.toDouble(), index.toLong(), read = n <= 3, downloaded = false, manuallyQueued = false)
        }
        assertEquals(listOf("c4", "c5", "c6", "c7", "c8"), SmartOfflinePolicy.nextUnread(chapters, 5).map { it.id })
        val novelUnnumbered = chapters.map { it.copy(number = null, sourceOrder = it.id.substringAfter('c').toLong()) }
        assertEquals(listOf("c4", "c5", "c6", "c7", "c8"), SmartOfflinePolicy.nextUnread(novelUnnumbered, 5).map { it.id })
        val mangaUnnumbered = chapters.map { it.copy(number = null) }
        assertEquals(listOf("c4", "c5", "c6", "c7", "c8"), SmartOfflinePolicy.nextUnread(mangaUnnumbered, 5, newestFirstFallback = true).map { it.id })
        val zeroAndRepeated = listOf(
            SmartOfflinePolicy.Candidate("old", 0.0, 9, false, false, false),
            SmartOfflinePolicy.Candidate("new", 0.0, 1, false, false, false),
        )
        assertEquals(listOf("old", "new"), SmartOfflinePolicy.nextUnread(zeroAndRepeated, 5, newestFirstFallback = true).map { it.id })
    }

    @Test fun existingManualAndHiddenCopiesAreNeverAdopted() {
        val owned = mapOf("manga:1" to SmartOwnedCopy("manga:22", "1", "manga", 32, 1))
        assertFalse(SmartOfflinePolicy.mayDownload("manga", "1", "manga", false, false, false, owned))
        assertFalse(SmartOfflinePolicy.mayDownload("manga", "2", "manga", true, false, false, owned))
        assertFalse(SmartOfflinePolicy.mayDownload("manga", "2", "manga", false, true, false, owned))
        assertFalse(SmartOfflinePolicy.mayDownload("manga", "2", "manga", false, false, true, owned))
        assertTrue(SmartOfflinePolicy.mayDownload("novel", "c3", "novel", false, false, false, owned))
    }

    @Test fun budgetChecksWifiPauseAndAggregatePageWritesConcurrently() {
        var wifi = true
        var enabled = true
        val gate = SmartOfflineBudget({ 100 }, 30, { wifi }, { enabled })
        val pool = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(4)
        repeat(4) {
            pool.execute {
                gate.write(10) { Thread.sleep(10) }
                latch.countDown()
            }
        }
        latch.await()
        assertEquals(40L, gate.itemBytes)
        assertThrows(SmartOfflineBlockedException::class.java) { gate.write(31) {} }
        assertTrue(gate.blockedReason.orEmpty().contains("storage limit"))
        pool.shutdownNow()

        val wifiGate = SmartOfflineBudget({ 100 }, 0, { wifi }, { enabled })
        wifi = false
        assertThrows(SmartOfflineBlockedException::class.java) { wifiGate.write(1) {} }
        assertTrue(wifiGate.blockedReason.orEmpty().contains("Wi-Fi"))

        val pausedGate = SmartOfflineBudget({ 100 }, 0, { true }, { enabled })
        enabled = false
        assertThrows(SmartOfflineBlockedException::class.java) { pausedGate.checkAllowed() }
        assertTrue(pausedGate.blockedReason.orEmpty().contains("paused"))
    }

    @Test fun budgetUsesLiveLimitAndMeasuredStoredBytesAfterTemporaryWrites() {
        var limit = 100L
        val gate = SmartOfflineBudget({ limit }, 0, { true }, { true })
        gate.write(80) {}
        gate.commitItem(30)
        gate.write(20) {}
        limit = 49
        assertThrows(SmartOfflineBlockedException::class.java) { gate.write(1) {} }
        assertTrue(gate.blockedReason.orEmpty().contains("storage limit"))
    }
}
