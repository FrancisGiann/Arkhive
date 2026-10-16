package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.library.service.LibraryVisibility
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.repository.MangaRepository
import kotlin.time.Duration.Companion.seconds

@Inject
class GetLibraryManga(
    private val mangaRepository: MangaRepository,
    private val visibility: LibraryVisibility,
) {

    suspend fun await(): List<LibraryManga> {
        return mangaRepository.getLibraryManga().filterNot { visibility.isMangaHidden(it.id) }
    }

    fun subscribe(): Flow<List<LibraryManga>> {
        return mangaRepository.getLibraryMangaAsFlow()
            .combine(visibility.hiddenSeries.changes()) { manga, hidden -> manga.filterNot { "manga:${it.id}" in hidden } }
            .retry {
                if (it is NullPointerException) {
                    delay(0.5.seconds)
                    true
                } else {
                    false
                }
            }.catch {
                this@GetLibraryManga.logcat(LogPriority.ERROR, it)
            }
    }
}
