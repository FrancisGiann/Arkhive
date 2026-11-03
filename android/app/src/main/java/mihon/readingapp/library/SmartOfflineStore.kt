package mihon.readingapp.library

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import mihon.readingapp.novels.lnreader.normalizePublicHttpsOrigin

@Serializable
data class SmartOwnedCopy(
    val seriesKey: String,
    val chapterId: String,
    val format: String,
    val bytes: Long,
    val completedAt: Long,
    val lastModifiedAt: Long? = null,
)

/** Exact, non-authorizing context for routing a blocked Smart Offline request to source review. */
@Serializable
data class SmartOfflineOriginReview(
    val seriesKey: String,
    val sourceId: String,
    val bundleSha256: String,
    val installedAt: Long,
    val enabled: Boolean,
    val approvedOrigins: Set<String>,
    val origin: String,
)

@Serializable
data class SmartOfflineSnapshot(
    val paused: Boolean = false,
    val running: Boolean = false,
    val currentSeries: String? = null,
    val currentChapter: String? = null,
    val message: String? = null,
    val lastRuleKey: String? = null,
    val ownedCopies: Map<String, SmartOwnedCopy> = emptyMap(),
    val originReview: SmartOfflineOriginReview? = null,
)

/** Persists only Smart-owned copy identities and dispatcher status. */
@Inject
@SingleIn(AppScope::class)
class SmartOfflineStore(context: Context) {
    private val preferences = context.getSharedPreferences("arkhive_smart_offline", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(runCatching {
        preferences.getString("state", null)?.let { raw ->
            val restored = decodeValidatedSnapshot(raw)
            restored.copy(
                running = false,
                currentSeries = null,
                currentChapter = null,
                message = restored.message?.takeUnless { it.startsWith("Saved ", ignoreCase = true) }
                    ?: restored.message?.let { "Smart offline pass finished." },
            ).also { preferences.edit().putString("state", json.encodeToString(it)).apply() }
        }
    }.getOrNull() ?: SmartOfflineSnapshot())
    val state = mutableState.asStateFlow()

    @Synchronized fun update(transform: (SmartOfflineSnapshot) -> SmartOfflineSnapshot) {
        val next = validate(transform(mutableState.value))
        preferences.edit().putString("state", json.encodeToString(next)).apply()
        mutableState.value = next
    }

    fun setPaused(value: Boolean) = update { it.copy(paused = value, running = false, currentSeries = null, currentChapter = null) }

    fun setCurrent(series: String?, chapter: String?, message: String? = null) = update {
        it.copy(running = series != null, currentSeries = series, currentChapter = chapter, message = message)
    }

    fun setMessage(message: String?) = update { it.copy(message = message) }
    fun setLastRuleKey(key: String?) = update { it.copy(lastRuleKey = key) }

    fun setOriginReview(review: SmartOfflineOriginReview) = update { it.copy(originReview = review) }

    /** Clear only the exact review context that initiated the successful explicit review flow. */
    fun clearOriginReview(
        expected: SmartOfflineOriginReview,
    ) = update { snapshot ->
        val current = snapshot.originReview
        if (current == expected) snapshot.copy(originReview = null) else snapshot
    }

    fun recordOwned(copy: SmartOwnedCopy) = update {
        it.copy(ownedCopies = it.ownedCopies + (ownershipKey(copy.format, copy.chapterId) to copy))
    }

    fun forgetOwned(format: String, chapterId: String) = update {
        it.copy(ownedCopies = it.ownedCopies - ownershipKey(format, chapterId))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

        fun ownershipKey(format: String, chapterId: String) = "$format:$chapterId"

        /** Pure decode seam used by JVM tests; discards malformed review context in isolation. */
        internal fun decodeValidatedSnapshot(raw: String): SmartOfflineSnapshot =
            validate(decodeSnapshotPreservingLegacyState(raw))

        private fun validate(snapshot: SmartOfflineSnapshot): SmartOfflineSnapshot {
            require(snapshot.ownedCopies.size <= 100_000)
            require(snapshot.ownedCopies.all { (key, copy) ->
                key == ownershipKey(copy.format, copy.chapterId) &&
                    copy.format in setOf("manga", "novel") &&
                    LibraryToolsPolicy.validKey(copy.seriesKey) &&
                    copy.chapterId.length in 1..128 &&
                    copy.bytes in 0..(8L * 1024 * 1024 * 1024) && copy.completedAt >= 0 &&
                    (copy.lastModifiedAt == null || copy.lastModifiedAt >= 0)
            })
            require(snapshot.message == null || snapshot.message.length <= 500)
            require(snapshot.lastRuleKey == null || LibraryToolsPolicy.validKey(snapshot.lastRuleKey))
            val safeOriginReview = snapshot.originReview?.takeIf { review ->
                validOriginReview(review)
            }
            return snapshot.copy(originReview = safeOriginReview)
        }

        private fun decodeSnapshotPreservingLegacyState(raw: String): SmartOfflineSnapshot {
            val root = json.parseToJsonElement(raw).jsonObject
            val reviewElement: JsonElement? = root["originReview"]
            val safeRoot = if (reviewElement != null && reviewElement != JsonNull) {
                val review = runCatching { json.decodeFromJsonElement<SmartOfflineOriginReview>(reviewElement) }.getOrNull()
                if (review == null || !validOriginReview(review)) JsonObject(root - "originReview") else root
            } else root
            return json.decodeFromJsonElement(safeRoot)
        }

        private fun validOriginReview(review: SmartOfflineOriginReview): Boolean = runCatching {
            require(review.seriesKey.startsWith("novel:") && LibraryToolsPolicy.validKey(review.seriesKey))
            require(review.sourceId.matches(Regex("lnreader\\.[a-f0-9]{32}")))
            require(review.bundleSha256.matches(Regex("[a-f0-9]{64}")))
            require(review.installedAt >= 0 && review.enabled)
            require(review.approvedOrigins.isNotEmpty() && review.approvedOrigins.size <= 24)
            require(review.approvedOrigins.all { normalizePublicHttpsOrigin(it) == it })
            require(normalizePublicHttpsOrigin(review.origin) == review.origin)
            require(review.origin !in review.approvedOrigins)
        }.isSuccess
    }
}
