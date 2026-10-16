package mihon.readingapp.library

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shelf membership for local imports whose metadata/progress must survive removal and later re-import. */
@Inject
@SingleIn(AppScope::class)
class LibraryShelfMembership(context: Context) {
    private val preferences = context.getSharedPreferences("arkhive_library_membership", Context.MODE_PRIVATE)
    private val removed = MutableStateFlow(preferences.getStringSet(REMOVED_BOOKS, emptySet()).orEmpty().toSet())
    val removedBookIds = removed.asStateFlow()

    @Synchronized fun removeBook(id: String) = update(removed.value + id)
    @Synchronized fun restoreBook(id: String) = update(removed.value - id)

    private fun update(ids: Set<String>) {
        preferences.edit().putStringSet(REMOVED_BOOKS, ids).apply()
        removed.value = ids
    }

    private companion object { const val REMOVED_BOOKS = "removed_local_books" }
}
