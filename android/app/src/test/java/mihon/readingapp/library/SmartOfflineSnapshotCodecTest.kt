package mihon.readingapp.library

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SmartOfflineSnapshotCodecTest {
    private val json = Json { encodeDefaults = true }

    private fun preservedSnapshot(): SmartOfflineSnapshot {
        val seriesKey = "novel:" + "a".repeat(64)
        val chapterId = "b".repeat(64)
        return SmartOfflineSnapshot(
            paused = true,
            message = "Existing offline state",
            lastRuleKey = seriesKey,
            ownedCopies = mapOf(
                SmartOfflineStore.ownershipKey("novel", chapterId) to
                    SmartOwnedCopy(seriesKey, chapterId, "novel", 123, 456, 789),
            ),
        )
    }

    @Test fun legacySnapshotWithoutReviewFieldPreservesOwnedCopiesAndRotationCursor() {
        val legacy = """{"paused":true,"running":false,"currentSeries":null,"currentChapter":null,"message":"Existing offline state","lastRuleKey":"novel:${"a".repeat(64)}","ownedCopies":{"novel:${"b".repeat(64)}":{"seriesKey":"novel:${"a".repeat(64)}","chapterId":"${"b".repeat(64)}","format":"novel","bytes":123,"completedAt":456,"lastModifiedAt":789}}}"""

        val decoded = SmartOfflineStore.decodeValidatedSnapshot(legacy)

        assertNull(decoded.originReview)
        assertEquals(preservedSnapshot(), decoded)
    }

    @Test fun malformedOrInvalidReviewContextIsDroppedWithoutResettingOfflineBookkeeping() {
        val base = preservedSnapshot()
        val root = json.parseToJsonElement(json.encodeToString(base)).jsonObject
        val malformedContexts = listOf(
            JsonPrimitive("not a review object"),
            JsonArray(emptyList()),
            JsonObject(mapOf("origin" to JsonPrimitive("https://cdn.fixture.invalid:443"))),
            JsonObject(mapOf(
                "seriesKey" to JsonPrimitive("novel:" + "a".repeat(64)),
                "sourceId" to JsonPrimitive("lnreader." + "c".repeat(32)),
                "bundleSha256" to JsonPrimitive("d".repeat(64)),
                "origin" to JsonPrimitive("https://cdn.fixture.invalid:443"),
            )),
            json.parseToJsonElement(json.encodeToString(
                SmartOfflineOriginReview(
                    seriesKey = "novel:" + "a".repeat(64),
                    sourceId = "lnreader." + "c".repeat(32),
                    bundleSha256 = "d".repeat(64),
                    installedAt = 1,
                    enabled = true,
                    approvedOrigins = setOf("https://cdn.fixture.invalid:443"),
                    origin = "https://cdn.fixture.invalid:443",
                ),
            )),
        )

        malformedContexts.forEach { context ->
            val raw = JsonObject(root + ("originReview" to context)).toString()
            val decoded = SmartOfflineStore.decodeValidatedSnapshot(raw)
            assertNull(decoded.originReview)
            assertEquals(base.paused, decoded.paused)
            assertEquals(base.message, decoded.message)
            assertEquals(base.lastRuleKey, decoded.lastRuleKey)
            assertEquals(base.ownedCopies, decoded.ownedCopies)
        }
    }

    @Test fun validReviewContextSurvivesPureCodecRoundTrip() {
        val base = preservedSnapshot()
        val review = SmartOfflineOriginReview(
            seriesKey = requireNotNull(base.lastRuleKey),
            sourceId = "lnreader." + "c".repeat(32),
            bundleSha256 = "d".repeat(64),
            installedAt = 12345,
            enabled = true,
            approvedOrigins = setOf("https://reader.fixture.invalid:443"),
            origin = "https://cdn.fixture.invalid:9443",
        )

        val decoded = SmartOfflineStore.decodeValidatedSnapshot(json.encodeToString(base.copy(originReview = review)))

        assertEquals(review, decoded.originReview)
        assertEquals(base.ownedCopies, decoded.ownedCopies)
        assertEquals(base.lastRuleKey, decoded.lastRuleKey)
    }
}
