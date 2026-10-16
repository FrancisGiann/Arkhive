package mihon.readingapp.library

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SmartOwnedCopy(
    val seriesKey: String,
    val chapterId: String,
    val format: String,
    val bytes: Long,
    val completedAt: Long,
    val lastModifiedAt: Long? = null,
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
)

/** Persists only Smart-owned copy identities and dispatcher status. */
@Inject
@SingleIn(AppScope::class)
class SmartOfflineStore(context: Context) {
    private val preferences = context.getSharedPreferences("arkhive_smart_offline", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val mutableState = MutableStateFlow(runCatching {
        preferences.getString("state", null)?.let { raw ->
            val restored = validate(json.decodeFromString<SmartOfflineSnapshot>(raw))
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

    fun recordOwned(copy: SmartOwnedCopy) = update {
        it.copy(ownedCopies = it.ownedCopies + (ownershipKey(copy.format, copy.chapterId) to copy))
    }

    fun forgetOwned(format: String, chapterId: String) = update {
        it.copy(ownedCopies = it.ownedCopies - ownershipKey(format, chapterId))
    }

    companion object {
        fun ownershipKey(format: String, chapterId: String) = "$format:$chapterId"

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
            return snapshot
        }
    }
}
