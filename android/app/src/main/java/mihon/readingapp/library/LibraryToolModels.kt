package mihon.readingapp.library

import kotlinx.serialization.Serializable

@Serializable enum class ShelfStatus(val label: String) { READING("Reading"), ON_HOLD("On Hold"), FINISHED("Finished") }
@Serializable enum class ReadingRoom(val label: String, val paper: Int, val text: Int) {
    WARM_PAPER("Warm paper", 0xFFF7F2E8.toInt(), 0xFF28242A.toInt()),
    MIDNIGHT_VIOLET("Midnight violet", 0xFF0F1428.toInt(), 0xFFEAE5F0.toInt()),
    INK_BLACK("Ink black", 0xFF000000.toInt(), 0xFFF2EFF7.toInt()),
}
@Serializable data class ShelfAssignment(val status: ShelfStatus? = null, val shelves: Set<String> = emptySet())
@Serializable data class ReadingProfile(
    val layout: String? = null,
    val brightnessPercent: Int? = null,
    val autoSpeed: Int? = null,
    val room: ReadingRoom? = null,
)
@Serializable data class SmartOfflineRule(val enabled: Boolean = false, val nextUnread: Int = 5)
@Serializable data class SeriesObservation(
    val checkedAt: Long, val source: String, val provenance: String, val available: Boolean,
    val consecutiveFailures: Int = 0, val chapterCount: Int? = null, val possibleGaps: List<String> = emptyList(),
    val message: String,
)
@Serializable data class LocalReadingStat(val foregroundMillis: Long = 0, val completedChapters: Set<String> = emptySet())
@Serializable data class LibraryToolsState(
    val schema: Int = 1,
    val shelves: List<String> = emptyList(),
    val assignments: Map<String, ShelfAssignment> = emptyMap(),
    val profiles: Map<String, ReadingProfile> = emptyMap(),
    val room: ReadingRoom? = null,
    val smartOffline: Map<String, SmartOfflineRule> = emptyMap(),
    val smartStorageMiB: Int = 256,
    val cleanupReadSmartCopies: Boolean = false,
    val statsEnabled: Boolean = false,
    val stats: Map<String, LocalReadingStat> = emptyMap(),
    val observations: Map<String, SeriesObservation> = emptyMap(),
    val dashboardFilter: String = "All",
    val dashboardCollection: String = "",
)

object LibraryToolsPolicy {
    fun validKey(value: String) = Regex("(?:manga:[0-9]+|(?:book|novel):[a-f0-9]{64})").matches(value)

    /** Add selected shelves without replacing any per-title status or existing shelf memberships. */
    fun addToShelves(state: LibraryToolsState, keys: Set<String>, shelfNames: Set<String>): LibraryToolsState {
        if (keys.isEmpty() || shelfNames.isEmpty()) return state
        val additions = shelfNames.map { it.trim().take(80) }.filter(String::isNotEmpty).toSet()
        if (additions.isEmpty()) return state
        val shelves = (state.shelves + additions).distinct()
        val assignments = state.assignments.toMutableMap()
        keys.forEach { key ->
            val current = assignments[key] ?: ShelfAssignment()
            assignments[key] = current.copy(shelves = current.shelves + additions)
        }
        return validate(state.copy(shelves = shelves, assignments = assignments))
    }
    fun validate(state: LibraryToolsState): LibraryToolsState {
        require(state.schema == 1) { "Unsupported library settings schema." }
        require(state.shelves.size <= 100 && state.shelves.distinct().size == state.shelves.size)
        require(state.shelves.all { it.isNotBlank() && it == it.trim() && it.length <= 80 })
        require(state.smartStorageMiB in 32..8192)
        require(state.dashboardFilter in setOf("All", "Novels", "Manga", "PDFs", "In progress"))
        require(state.dashboardCollection.isEmpty() || state.dashboardCollection in ShelfStatus.entries.map { it.label } || state.dashboardCollection in state.shelves)
        listOf(state.assignments.keys, state.profiles.keys, state.smartOffline.keys, state.stats.keys, state.observations.keys).forEach {
            require(it.size <= 20_000 && it.all(::validKey)) { "Invalid series identities in library settings." }
        }
        require(state.assignments.values.all { it.shelves.all(state.shelves::contains) })
        require(state.profiles.values.all { profile ->
            (profile.layout == null || profile.layout in setOf("scroll", "pages", "rtl", "ltr", "webtoon", "vertical")) &&
                (profile.brightnessPercent == null || profile.brightnessPercent in 1..100) &&
                (profile.autoSpeed == null || profile.autoSpeed in 16..240)
        })
        require(state.smartOffline.values.all { it.nextUnread in setOf(5, 10) })
        require(state.stats.values.all { it.foregroundMillis >= 0 && it.completedChapters.size <= 100_000 })
        require(state.observations.values.all { it.checkedAt >= 0 && it.consecutiveFailures >= 0 && it.message.length <= 1000 && it.possibleGaps.size <= 20 })
        return state
    }
    fun possibleGaps(numbers: List<Double>): List<String> = numbers.filter { it >= 1 && it.isFinite() && it % 1.0 == 0.0 }
        .distinct().sorted().zipWithNext().filter { (a, b) -> b - a > 1.0 }.take(20)
        .map { (a, b) -> "${a.toInt()} → ${b.toInt()}" }
}
