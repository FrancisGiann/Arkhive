package mihon.domain.migration.usecases

import dev.zacsweers.metro.Inject
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mihon.core.metro.AppCoroutineScope
import mihon.domain.migration.models.MigrationFlag
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.UpsertTrack
import kotlin.time.Clock
import mihon.readingapp.library.ChapterMappingPolicy
import mihon.readingapp.library.ChapterMappingPlan
import mihon.readingapp.library.ChapterReference
import mihon.readingapp.library.LibraryToolsStore
import tachiyomi.domain.library.service.LibraryVisibility

@Inject
class MigrateMangaUseCase(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val sourcePreferences: SourcePreferences,
    private val trackerManager: TrackerManager,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val updateManga: UpdateManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val getTracks: GetTracks,
    private val upsertTrack: UpsertTrack,
    private val coverCache: CoverCache,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val libraryTools: LibraryToolsStore,
    private val visibility: LibraryVisibility,
) {
    private val enhancedServices by lazy { trackerManager.trackers.filterIsInstance<EnhancedTracker>() }

    suspend fun preview(current:Manga,target:Manga):ChapterMappingPlan {
        updateMangaFromRemote(target,fetchChapters=true).getOrThrow()
        return ChapterMappingPolicy.plan(
            getChaptersByMangaId.await(current.id).map {ChapterReference(it.id.toString(),it.name,it.chapterNumber)},
            getChaptersByMangaId.await(target.id).map {ChapterReference(it.id.toString(),it.name,it.chapterNumber)},
        )
    }

    suspend operator fun invoke(current: Manga, target: Manga, replace: Boolean, reviewed:Set<String> = emptySet()) {
        val targetSource = sourceManager.get(target.source) ?: return
        val currentSource = sourceManager.get(current.source)
        val flags = sourcePreferences.migrationFlags.get()

        try {
            updateMangaFromRemote(target, fetchChapters = true).getOrThrow()

            // Update chapters read, bookmark and dateFetch
            if (MigrationFlag.CHAPTER in flags) {
                val prevMangaChapters = getChaptersByMangaId.await(current.id)
                val mangaChapters = getChaptersByMangaId.await(target.id)

                val plan=ChapterMappingPolicy.plan(prevMangaChapters.map {ChapterReference(it.id.toString(),it.name,it.chapterNumber)},mangaChapters.map {ChapterReference(it.id.toString(),it.name,it.chapterNumber)})
                val mapping=ChapterMappingPolicy.selected(plan,reviewed)
                val chapterUpdates = mapping.map {match->
                    val previous=prevMangaChapters.first {it.id.toString()==match.previous.id}
                    val destination=mangaChapters.first {it.id.toString()==match.target.id}
                    ChapterUpdate(destination.id) {
                        dateFetch=previous.dateFetch
                        bookmark=previous.bookmark || destination.bookmark
                        read=previous.read || destination.read
                        if(current.source==target.source && previous.url==destination.url) lastPageRead=maxOf(previous.lastPageRead,destination.lastPageRead)
                    }
                }
                updateChapter.awaitAll(chapterUpdates)
            }

            // Update categories
            if (MigrationFlag.CATEGORY in flags) {
                val categoryIds = getCategories.await(current.id).map { it.id }
                setMangaCategories.await(target.id, categoryIds)
            }

            // Update track
            getTracks.await(current.id).mapNotNull { track ->
                val updatedTrack = track.copy(mangaId = target.id)

                val service = enhancedServices
                    .firstOrNull { it.isTrackFrom(updatedTrack, current, currentSource) }

                if (service != null) {
                    service.migrateTrack(updatedTrack, target, targetSource)
                } else {
                    updatedTrack
                }
            }
                .takeIf { it.isNotEmpty() }
                ?.let { upsertTrack.awaitAll(it) }

            // Delete downloaded
            if (MigrationFlag.REMOVE_DOWNLOAD in flags && currentSource != null) {
                // Not awaited, so a slow storage delete doesn't hold up the migration
                scope.launch { downloadManager.deleteManga(current, currentSource) }
            }

            // Update custom cover (recheck if custom cover exists)
            if (MigrationFlag.CUSTOM_COVER in flags && current.hasCustomCover()) {
                coverCache.getCustomCoverFile(current.id).inputStream().use {
                    coverCache.setCustomCoverToCache(target, it)
                }
            }

            val currentMangaUpdate = MangaUpdate(current.id) {
                favoriteAt = null
            }
                .takeIf { replace }
            val targetMangaUpdate = MangaUpdate(target.id) {
                favoriteAt = current.favoriteAt?.takeIf { replace } ?: Clock.System.now()
                chapterFlags = current.chapterFlags
                viewerFlags = current.viewerFlags
                if (MigrationFlag.NOTES in flags) notes = current.notes
            }

            updateManga.awaitAll(listOfNotNull(currentMangaUpdate, targetMangaUpdate))
            val from="manga:${current.id}";val to="manga:${target.id}"
            libraryTools.update {state->state.copy(
                assignments=state.assignments+state.assignments[from]?.let {mapOf(to to it)}.orEmpty(),
                profiles=state.profiles+state.profiles[from]?.let {mapOf(to to it)}.orEmpty(),
            )}
            if(visibility.isHidden(from)) visibility.setHidden(setOf(to),true)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            throw e
        }
    }
}
