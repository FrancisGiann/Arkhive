package mihon.readingapp.library

import eu.kanade.tachiyomi.data.download.SmartOfflineBlockedException
import mihon.readingapp.novels.lnreader.LnReaderUnapprovedSourceOriginException
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

    @Test fun initialAndRedirectOriginBlocksProduceExactReviewContextWithoutGrantingTrust() {
        val seriesKey = "novel:" + "1".repeat(64)
        val sourceId = "lnreader." + "a".repeat(32)
        val bundleSha = "b".repeat(64)
        val installedAt = 123456L
        val existingApprovals = mutableSetOf("https://reader.fixture.invalid:443")
        val beforeApprovals = existingApprovals.toSet()
        val blockedOrigins = listOf(
            "https://api.fixture.invalid:443",
            "https://cdn.fixture.invalid:9443",
        )

        blockedOrigins.forEach { blockedOrigin ->
            val context = SmartOfflinePolicy.originReview(
                seriesKey,
                sourceId,
                LnReaderUnapprovedSourceOriginException(blockedOrigin, sourceId, bundleSha, installedAt, true, existingApprovals),
            )
            assertEquals(SmartOfflineOriginReview(seriesKey, sourceId, bundleSha, installedAt, true, existingApprovals, blockedOrigin), context)
            assertTrue(blockedOrigin !in existingApprovals, "Mapping a source error cannot grant an origin")
        }
        assertEquals(beforeApprovals, existingApprovals)
    }

    @Test fun invalidOriginReviewDescriptorsAndStaleContextAreNotActionable() {
        val seriesKey = "novel:" + "1".repeat(64)
        val sourceId = "lnreader." + "a".repeat(32)
        val bundleSha = "b".repeat(64)
        val invalid = listOf(
            LnReaderUnapprovedSourceOriginException("http://plain.fixture.invalid:80", sourceId, bundleSha),
            LnReaderUnapprovedSourceOriginException("https://127.0.0.1:443", sourceId, bundleSha),
            LnReaderUnapprovedSourceOriginException("https://api.fixture.invalid:443/path", sourceId, bundleSha),
            LnReaderUnapprovedSourceOriginException("https://api.fixture.invalid:443", "wikisource.en", bundleSha),
            LnReaderUnapprovedSourceOriginException("https://api.fixture.invalid:443", sourceId, "bad-hash"),
            LnReaderUnapprovedSourceOriginException("https://api.fixture.invalid:443", sourceId, bundleSha, 123456, true, emptySet()),
            LnReaderUnapprovedSourceOriginException(
                "https://reader.fixture.invalid:443", sourceId, bundleSha, 123456, true,
                setOf("https://reader.fixture.invalid:443"),
            ),
        )
        invalid.forEach { failure ->
            assertNull(SmartOfflinePolicy.originReview(seriesKey, sourceId, failure))
        }
        assertNull(SmartOfflinePolicy.originReview("manga:12", sourceId,
            LnReaderUnapprovedSourceOriginException("https://api.fixture.invalid:443", sourceId, bundleSha)))

        val installedAt = 123456L
        val review = SmartOfflineOriginReview(
            seriesKey, sourceId, bundleSha, installedAt, true, setOf("https://reader.fixture.invalid:443"),
            "https://api.fixture.invalid:443",
        )
        val approvals = setOf("https://reader.fixture.invalid:443")
        fun visible(
            hidden: Set<String> = emptySet(),
            favoriteSource: String? = sourceId,
            ruleEnabled: Boolean = true,
            installedEnabled: Boolean = true,
            installedSha: String? = bundleSha,
            currentInstalledAt: Long? = installedAt,
            approved: Set<String>? = approvals,
        ) = SmartOfflinePolicy.canReviewOrigin(
            review, hidden, favoriteSource, ruleEnabled, installedEnabled, installedSha, currentInstalledAt, approved,
        )

        assertTrue(visible())
        assertFalse(visible(hidden = setOf(seriesKey)))
        assertFalse(visible(favoriteSource = null))
        assertFalse(visible(ruleEnabled = false))
        assertFalse(visible(installedEnabled = false))
        assertFalse(visible(installedSha = "c".repeat(64)))
        assertFalse(visible(currentInstalledAt = installedAt + 1))
        assertFalse(visible(approved = approvals + "https://other.fixture.invalid:443"))
        assertFalse(visible(approved = approvals + review.origin))
    }
}
