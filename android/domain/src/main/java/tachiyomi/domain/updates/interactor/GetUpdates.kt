package tachiyomi.domain.updates.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.library.service.LibraryVisibility
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.repository.UpdatesRepository
import kotlin.time.Instant

@Inject
class GetUpdates(
    private val repository: UpdatesRepository,
    private val visibility: LibraryVisibility,
) {

    suspend fun await(read: Boolean, after: Instant): List<UpdatesWithRelations> {
        return repository.awaitWithRead(read, after, limit = 500).filterNot { visibility.isMangaHidden(it.mangaId) }
    }

    fun subscribe(
        instant: Instant,
        unread: Boolean?,
        started: Boolean?,
        bookmarked: Boolean?,
        hideExcludedScanlators: Boolean,
        includedCategories: List<Long>,
        excludedCategories: List<Long>,
    ): Flow<List<UpdatesWithRelations>> {
        return repository.subscribeAll(
            instant,
            limit = 500,
            unread = unread,
            started = started,
            bookmarked = bookmarked,
            hideExcludedScanlators = hideExcludedScanlators,
            includedCategories = includedCategories,
            excludedCategories = excludedCategories,
        )
            .combine(visibility.hiddenSeries.changes()) { updates, hidden -> updates.filterNot { "manga:${it.mangaId}" in hidden } }
    }

    fun subscribe(read: Boolean, after: Instant): Flow<List<UpdatesWithRelations>> {
        return repository.subscribeWithRead(read, after, limit = 500)
            .combine(visibility.hiddenSeries.changes()) { updates, hidden -> updates.filterNot { "manga:${it.mangaId}" in hidden } }
    }
}
