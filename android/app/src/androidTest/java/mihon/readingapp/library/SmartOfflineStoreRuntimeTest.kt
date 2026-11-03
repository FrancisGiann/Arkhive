package mihon.readingapp.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmartOfflineStoreRuntimeTest {
    @Test fun pauseAndOwnershipSurviveStoreRecreationWithoutActivatingRules() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SmartOfflineStore(context)
        val tools = LibraryToolsStore(context)
        val before = store.state.value
        val seriesKey = "novel:" + "a".repeat(64)
        val chapterId = "b".repeat(64)
        val sourceId = "lnreader." + "c".repeat(32)
        val bundleSha = "d".repeat(64)
        val installedAt = 67890L
        val approvals = setOf("https://reader.fixture.invalid:443")
        val origin = "https://cdn.fixture.invalid:9443"
        val review = SmartOfflineOriginReview(seriesKey, sourceId, bundleSha, installedAt, true, approvals, origin)
        try {
            store.setPaused(true)
            store.setCurrent(seriesKey, "Private title · Chapter 3", "Saved 9 Smart chapters in this bounded pass.")
            store.recordOwned(SmartOwnedCopy(seriesKey, chapterId, "novel", 128, 1000, 999))
            store.setOriginReview(review)
            val afterRestart = SmartOfflineStore(context).state.value
            assertTrue(afterRestart.paused)
            assertFalse(afterRestart.running)
            assertNull(afterRestart.currentSeries)
            assertNull(afterRestart.currentChapter)
            assertEquals("Smart offline pass finished.", afterRestart.message)
            assertEquals(999L, afterRestart.ownedCopies["novel:$chapterId"]?.lastModifiedAt)
            assertEquals(review, afterRestart.originReview)
            assertFalse(tools.state.value.smartOffline[seriesKey]?.enabled == true)

            store.clearOriginReview(review.copy(bundleSha256 = "e".repeat(64)))
            assertEquals("A mismatched bundle cannot clear a pending context.", review, store.state.value.originReview)
            store.clearOriginReview(review)
            assertNull(store.state.value.originReview)
        } finally {
            store.update { before }
        }
    }

    @Test fun invalidReviewContextIsDiscardedWithoutLosingOwnershipOrRotationHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("arkhive_smart_offline", android.content.Context.MODE_PRIVATE)
        val originalRaw = preferences.getString("state", null)
        val seriesKey = "novel:" + "f".repeat(64)
        val chapterId = "e".repeat(64)
        val owned = mapOf(
            SmartOfflineStore.ownershipKey("novel", chapterId) to
                SmartOwnedCopy(seriesKey, chapterId, "novel", 512, 1234, 4321),
        )
        val validSource = "lnreader." + "a".repeat(32)
        val validSha = "b".repeat(64)
        val validSnapshot = SmartOfflineSnapshot(paused = true, lastRuleKey = seriesKey, ownedCopies = owned)
        val validRoot = Json.parseToJsonElement(Json.encodeToString(validSnapshot)).jsonObject
        val invalidReviews = listOf(
            SmartOfflineOriginReview(
                seriesKey,
                validSource,
                validSha,
                1,
                true,
                setOf("https://reader.fixture.invalid:443"),
                "http://untrusted.fixture.invalid:80",
            ),
            SmartOfflineOriginReview(seriesKey, "wikisource.en", validSha, 1, true, setOf("https://reader.fixture.invalid:443"), "https://api.fixture.invalid:443"),
            SmartOfflineOriginReview(seriesKey, validSource, "bad-hash", 1, true, setOf("https://reader.fixture.invalid:443"), "https://api.fixture.invalid:443"),
            SmartOfflineOriginReview("manga:123", validSource, validSha, 1, true, setOf("https://reader.fixture.invalid:443"), "https://api.fixture.invalid:443"),
            SmartOfflineOriginReview(seriesKey, validSource, validSha, -1, true, setOf("https://reader.fixture.invalid:443"), "https://api.fixture.invalid:443"),
            SmartOfflineOriginReview(seriesKey, validSource, validSha, 1, false, setOf("https://reader.fixture.invalid:443"), "https://api.fixture.invalid:443"),
            SmartOfflineOriginReview(seriesKey, validSource, validSha, 1, true, setOf("http://private.fixture.invalid:80"), "https://api.fixture.invalid:443"),
            SmartOfflineOriginReview(seriesKey, validSource, validSha, 1, true, setOf("https://api.fixture.invalid:443"), "https://api.fixture.invalid:443"),
        )
        try {
            fun assertPreserved(raw: String) {
                preferences.edit().putString("state", raw).commit()
                val restored = SmartOfflineStore(context).state.value
                assertNull(restored.originReview)
                assertEquals(seriesKey, restored.lastRuleKey)
                assertEquals(owned, restored.ownedCopies)
                assertTrue(restored.paused)
            }

            invalidReviews.forEach { invalidReview ->
                val withInvalidContext = validRoot + ("originReview" to Json.parseToJsonElement(Json.encodeToString(invalidReview)))
                assertPreserved(JsonObject(withInvalidContext).toString())
            }
            listOf(
                "{}",
                "\"not an object\"",
                "[]",
                "{\"origin\":\"https://api.fixture.invalid:443\"}",
                "{\"seriesKey\":\"$seriesKey\",\"sourceId\":\"$validSource\",\"bundleSha256\":\"$validSha\",\"origin\":\"https://api.fixture.invalid:443\"}",
                "\"missing required snapshot fields\"",
            ).forEach { malformedContext ->
                assertPreserved(JsonObject(validRoot + ("originReview" to Json.parseToJsonElement(malformedContext))).toString())
            }
        } finally {
            if (originalRaw == null) preferences.edit().remove("state").commit()
            else preferences.edit().putString("state", originalRaw).commit()
        }
    }

    @Test fun snapshotsWrittenBeforeOriginReviewFieldRemainReadable() {
        val legacy = """{"paused":true,"running":false,"currentSeries":null,"currentChapter":null,"message":"Old Smart offline status","lastRuleKey":"novel:${"1".repeat(64)}","ownedCopies":{}}"""
        val restored = Json.decodeFromString<SmartOfflineSnapshot>(legacy)
        assertNull(restored.originReview)
        assertEquals("novel:" + "1".repeat(64), restored.lastRuleKey)
        assertEquals("Old Smart offline status", restored.message)
    }
}
