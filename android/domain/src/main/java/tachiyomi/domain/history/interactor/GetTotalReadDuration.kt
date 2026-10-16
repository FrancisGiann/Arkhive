package tachiyomi.domain.history.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.library.service.LibraryVisibility

@Inject
class GetTotalReadDuration(
    private val repository: HistoryRepository,
    private val visibility: LibraryVisibility,
) {

    suspend fun await(): Long {
        val hidden=visibility.hiddenSeries.get().filter {it.startsWith("manga:")}.sumOf {key->repository.getHistoryByMangaId(key.substringAfter(':').toLong()).sumOf {it.readDuration.coerceAtLeast(0)}}
        return (repository.getTotalReadDuration()-hidden).coerceAtLeast(0)
    }
}
