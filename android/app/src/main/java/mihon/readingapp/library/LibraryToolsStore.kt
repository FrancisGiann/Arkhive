package mihon.readingapp.library

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** Small local metadata only. No chapter content, account credentials or per-frame writes. */
@Inject
@SingleIn(AppScope::class)
class LibraryToolsStore(context: Context) {
    private val preferences = context.getSharedPreferences("arkhive_library_tools", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val mutableState = MutableStateFlow(runCatching {
        preferences.getString("state", null)?.let { LibraryToolsPolicy.validate(json.decodeFromString<LibraryToolsState>(it)) }
    }.getOrNull() ?: LibraryToolsState())
    val state = mutableState.asStateFlow()

    @Synchronized fun update(transform: (LibraryToolsState) -> LibraryToolsState) {
        val next = LibraryToolsPolicy.validate(transform(mutableState.value))
        preferences.edit().putString("state", json.encodeToString(next)).apply()
        mutableState.value = next
    }
    fun assign(keys: Set<String>, assignment: ShelfAssignment) = update { it.copy(assignments = it.assignments + keys.associateWith { assignment }) }
    fun addShelf(name: String) = update { val clean = name.trim().take(80); require(clean.isNotEmpty()); it.copy(shelves = (it.shelves + clean).distinct()) }
    fun addToShelves(keys: Set<String>, shelfNames: Set<String>) = update {
        LibraryToolsPolicy.addToShelves(it, keys, shelfNames)
    }
    fun removeShelf(name: String) = update { it.copy(
        shelves = it.shelves - name,
        assignments = it.assignments.mapValues { (_, a) -> a.copy(shelves = a.shelves - name) },
        dashboardCollection = it.dashboardCollection.takeUnless { selected -> selected == name }.orEmpty(),
    ) }
    fun setDashboardSelection(filter: String, collection: String) = update {
        it.copy(dashboardFilter = filter, dashboardCollection = collection)
    }
    fun profile(key: String): ReadingProfile = state.value.profiles[key] ?: ReadingProfile()
    fun setProfile(key: String, profile: ReadingProfile) = update { it.copy(profiles = it.profiles + (key to profile)) }
    fun recordReading(key: String, duration: Long, completed: String? = null) {
        if (!state.value.statsEnabled || !LibraryToolsPolicy.validKey(key)) return
        update {
            val old = it.stats[key] ?: LocalReadingStat()
            it.copy(stats = it.stats + (key to old.copy(
                foregroundMillis = old.foregroundMillis + duration.coerceIn(0, 24L * 60 * 60 * 1000),
                completedChapters = if (completed == null) old.completedChapters else old.completedChapters + completed,
            )))
        }
    }
}
