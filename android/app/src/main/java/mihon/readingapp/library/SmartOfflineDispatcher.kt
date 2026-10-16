package mihon.readingapp.library

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.data.download.SmartOfflineBlockedException
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import mihon.app.di.appGraph
import mihon.readingapp.novels.ChapterState
import mihon.readingapp.novels.NovelRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryVisibility
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import java.io.File
import java.util.concurrent.TimeUnit

/** Serialized, explicitly opted-in one-shot Smart download dispatcher. */
@Inject
@SingleIn(AppScope::class)
class SmartOfflineDispatcher(
    private val context: Context,
    private val tools: LibraryToolsStore,
    private val store: SmartOfflineStore,
    private val foreground: ReaderForegroundSignal,
    private val manga: MangaRepository,
    private val chapters: ChapterRepository,
    private val sources: SourceManager,
    private val downloader: Downloader,
    private val downloadManager: DownloadManager,
    private val downloadProvider: eu.kanade.tachiyomi.data.download.DownloadProvider,
    private val visibility: LibraryVisibility,
    private val novels: NovelRepository,
) {
    private sealed interface Candidate {
        val seriesKey: String
        val chapterId: String
        val title: String
        val chapterTitle: String
        val format: String

        data class MangaChapter(val series: Manga, val chapter: Chapter) : Candidate {
            override val seriesKey = "manga:${series.id}"
            override val chapterId = chapter.id.toString()
            override val title = series.title
            override val chapterTitle = chapter.name
            override val format = "manga"
        }

        data class NovelChapter(val series: mihon.readingapp.novels.SavedNovel, val chapter: ChapterState) : Candidate {
            override val seriesKey = "novel:${series.id}"
            override val chapterId = chapter.id
            override val title = series.detail.name
            override val chapterTitle = chapter.name
            override val format = "novel"
        }
    }

    fun setRule(seriesKey: String, rule: SmartOfflineRule) {
        require(LibraryToolsPolicy.validKey(seriesKey))
        require(seriesKey.startsWith("manga:") || seriesKey.startsWith("novel:"))
        require(rule.nextUnread in setOf(5, 10))
        tools.update { it.copy(smartOffline = it.smartOffline + (seriesKey to rule)) }
        val anyEnabled = tools.state.value.smartOffline.values.any { it.enabled }
        if (rule.enabled) schedule(append = false)
        else if (anyEnabled) schedule(append = true)
        else WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun pauseAll() {
        store.setPaused(true)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun resumeAll() {
        store.setPaused(false)
        schedule(append = false)
    }

    /** Called only at a reading activity lifecycle boundary, never polled. */
    fun onReaderIdle() {
        if (!foreground.isActive()) schedule(append = true)
    }

    suspend fun cleanupReadOwned(): Int {
        if (!tools.state.value.cleanupReadSmartCopies) return 0
        var removed = 0
        for (owned in store.state.value.ownedCopies.values.toList()) {
            if (visibility.isHidden(owned.seriesKey)) continue
            val currentModifiedAt = currentLastModified(owned)
            if (owned.lastModifiedAt == null || currentModifiedAt == null || currentModifiedAt != owned.lastModifiedAt) {
                // A missing or replaced path no longer proves Smart ownership. Forget the old
                // claim instead of ever applying cleanup to a later manual copy at that path.
                store.forgetOwned(owned.format, owned.chapterId)
                continue
            }
            val didRemove = when (owned.format) {
                "novel" -> {
                    val chapter = novels.chapters(owned.seriesKey.substringAfter(':')).firstOrNull { it.id == owned.chapterId }
                    if (chapter == null || chapter.progression < .999 || chapter.bookmark) false
                    else novels.deleteSmartOwnedChapter(owned.seriesKey.substringAfter(':'), owned.chapterId)
                }
                "manga" -> {
                    val mangaId = owned.seriesKey.substringAfter(':').toLongOrNull() ?: continue
                    val chapterId = owned.chapterId.toLongOrNull() ?: continue
                    val chapter = chapters.getChapterById(chapterId)
                    if (chapter == null || !chapter.read || chapter.bookmark) false
                    else {
                        val series = runCatching { manga.getMangaById(mangaId) }.getOrNull() ?: continue
                        val source = sources.get(series.source) ?: continue
                        downloadManager.deleteSmartOwnedChapter(chapter, series, source)
                    }
                }
                else -> false
            }
            if (didRemove) {
                store.forgetOwned(owned.format, owned.chapterId)
                removed++
            }
        }
        return removed
    }

    suspend fun runBatch() {
        if (store.state.value.paused) return status("Smart offline is paused.")
        if (foreground.isActive()) return status("Smart offline waits until all readers leave the foreground.")
        if (!isWifi()) return status("Smart offline waits for Wi-Fi.")

        val sortedRules = tools.state.value.smartOffline.entries
            .filter { it.value.enabled && (it.key.startsWith("manga:") || it.key.startsWith("novel:")) }
            .sortedBy { it.key }
        if (sortedRules.isEmpty()) return status("No series have Smart offline enabled.")
        val activeRules = SmartOfflinePolicy.fairWindow(
            sortedRules,
            store.state.value.lastRuleKey,
            MAX_SERIES_PER_BATCH,
        ) { it.key }

        var total = 0
        var attempted = 0
        var lastVisitedRule: String? = null
        var hadFailure = false
        var stopReason: String? = null
        for ((seriesKey, rule) in activeRules) {
            if (attempted >= MAX_CHAPTERS_PER_BATCH) break
            if (!canProcess(seriesKey)) continue
            lastVisitedRule = seriesKey
            val seriesCandidates = try {
                candidates(seriesKey, rule.nextUnread)
            } catch (timeout: TimeoutCancellationException) {
                stopReason = "Smart offline stopped while metadata was loading. Try again later."
                break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                store.setMessage("Could not read saved chapter metadata for this series. Try its individual source check first.")
                continue
            }

            for (candidate in seriesCandidates) {
                if (attempted >= MAX_CHAPTERS_PER_BATCH) break
                if (!canProcess(seriesKey) || !isWifi()) {
                    stopReason = if (foreground.isActive()) "Smart offline paused while a reader is in the foreground."
                    else if (store.state.value.paused) "Smart offline is paused."
                    else "Smart offline waits for Wi-Fi."
                    break
                }
                val alreadyDownloaded: Boolean
                val manuallyQueued: Boolean
                when (candidate) {
                    is Candidate.MangaChapter -> {
                        manuallyQueued = downloadManager.getQueuedDownloadOrNull(candidate.chapter.id) != null
                        alreadyDownloaded = downloadProvider.findChapterDir(
                            candidate.chapter.name, candidate.chapter.scanlator, candidate.chapter.url,
                            candidate.series.title, sources.get(candidate.series.source) ?: continue,
                        ) != null
                    }
                    is Candidate.NovelChapter -> {
                        manuallyQueued = novels.state.value.downloads.any { it.id == candidate.chapterId }
                        alreadyDownloaded = candidate.chapter.downloaded
                    }
                }
                if (!SmartOfflinePolicy.mayDownload(
                        candidate.format,
                        candidate.chapterId,
                        candidate.format,
                        visibility.isHidden(seriesKey),
                        alreadyDownloaded,
                        manuallyQueued,
                        store.state.value.ownedCopies,
                    )
                ) continue

                // The native queue itself checks source order and may fan pages out; Smart owns
                // only this one bounded chapter attempt at a time.
                if (candidate is Candidate.MangaChapter && sources.get(candidate.series.source) !is HttpSource) continue

                val gate = SmartOfflineBudget(
                    { tools.state.value.smartStorageMiB.toLong() * 1024 * 1024 },
                    store.state.value.ownedCopies.values.sumOf { it.bytes },
                    ::isWifi,
                    { canProcess(seriesKey) && !isManuallyQueued(candidate) },
                    {
                        when {
                            foreground.isActive() -> "Smart offline paused while a reader is in the foreground."
                            store.state.value.paused -> "Smart offline is paused."
                            visibility.isHidden(seriesKey) -> "Smart offline stopped because this series is hidden."
                            isManuallyQueued(candidate) -> "Smart offline yielded to the manual chapter queue."
                            else -> "Smart offline is off for this series."
                        }
                    },
                )
                attempted++
                store.setLastRuleKey(seriesKey)
                store.setCurrent(seriesKey, "${candidate.title} · ${candidate.chapterTitle}")
                try {
                    val savedBytes = withTimeout(CHAPTER_TIMEOUT_MS) {
                        when (candidate) {
                            is Candidate.NovelChapter -> novels.downloadSmartChapter(candidate.series.id, candidate.chapterId, gate)
                            is Candidate.MangaChapter -> {
                                val source = sources.get(candidate.series.source) as? HttpSource
                                    ?: return@withTimeout 0L
                                downloader.downloadSmartChapter(Download(source, candidate.series, candidate.chapter), gate) ?: 0L
                            }
                        }
                    }
                    if (savedBytes > 0 && gate.blockedReason == null) {
                        store.recordOwned(
                            SmartOwnedCopy(
                                seriesKey,
                                candidate.chapterId,
                                candidate.format,
                                savedBytes,
                                System.currentTimeMillis(),
                                currentLastModified(candidate),
                            ),
                        )
                        total++
                        store.setMessage("Smart offline saved a chapter in this bounded pass.")
                    } else if (gate.blockedReason != null) {
                        stopReason = gate.blockedReason
                        gate.discardItem()
                        break
                    }
                } catch (timeout: TimeoutCancellationException) {
                    gate.discardItem()
                    hadFailure = true
                    stopReason = "A Smart chapter check reached its 45-second limit; no retry was queued."
                    break
                } catch (cancelled: CancellationException) {
                    gate.discardItem()
                    throw cancelled
                } catch (blocked: SmartOfflineBlockedException) {
                    gate.discardItem()
                    stopReason = blocked.message
                    break
                } catch (error: Exception) {
                    gate.discardItem()
                    hadFailure = true
                    store.setMessage("A Smart chapter could not be saved. Review this series' source check, then try again.")
                }
            }
            store.setLastRuleKey(seriesKey)
            if (stopReason != null) break
        }
        if (lastVisitedRule != null) store.setLastRuleKey(lastVisitedRule)
        val completionMessage = stopReason ?: store.state.value.message ?: "Smart offline pass finished."
        store.setCurrent(null, null, completionMessage)
        if (attempted >= MAX_CHAPTERS_PER_BATCH && total > 0 && !hadFailure && stopReason == null &&
            !store.state.value.paused && !foreground.isActive() && isWifi() && tools.state.value.smartOffline.values.any { it.enabled }
        ) {
            scheduleContinuation()
        }
    }

    private suspend fun candidates(seriesKey: String, limit: Int): List<Candidate> = withTimeout(METADATA_TIMEOUT_MS) {
        if (visibility.isHidden(seriesKey)) return@withTimeout emptyList()
        when {
            seriesKey.startsWith("manga:") -> {
                val selected = manga.getMangaById(seriesKey.substringAfter(':').toLong())
                if (!selected.favorite) return@withTimeout emptyList()
                val rows = chapters.getChapterByMangaId(selected.id)
                SmartOfflinePolicy.nextUnread(rows.map {
                    SmartOfflinePolicy.Candidate(it.id.toString(), it.chapterNumber, it.sourceOrder, it.read, false, false)
                }, limit, newestFirstFallback = true).mapNotNull { row -> rows.firstOrNull { it.id.toString() == row.id }?.let { Candidate.MangaChapter(selected, it) } }
            }
            seriesKey.startsWith("novel:") -> {
                val selected = novels.get(seriesKey.substringAfter(':')) ?: return@withTimeout emptyList()
                if (!selected.favorite) return@withTimeout emptyList()
                val rows = novels.chapters(selected.id)
                SmartOfflinePolicy.nextUnread(rows.map {
                    SmartOfflinePolicy.Candidate(it.id, it.chapterNumber, it.position.toLong(), it.progression >= .999, it.downloaded, false)
                }, limit).mapNotNull { row -> rows.firstOrNull { it.id == row.id }?.let { Candidate.NovelChapter(selected, it) } }
            }
            else -> emptyList()
        }
    }

    private fun canProcess(seriesKey: String): Boolean =
        !store.state.value.paused && !foreground.isActive() && !visibility.isHidden(seriesKey) &&
            tools.state.value.smartOffline[seriesKey]?.enabled == true

    private fun isManuallyQueued(candidate: Candidate): Boolean = when (candidate) {
        is Candidate.MangaChapter -> downloadManager.getQueuedDownloadOrNull(candidate.chapter.id) != null
        is Candidate.NovelChapter -> novels.state.value.downloads.any { it.id == candidate.chapterId }
    }

    private suspend fun currentLastModified(candidate: Candidate): Long? {
        return when (candidate) {
            is Candidate.NovelChapter -> novels.smartSavedLastModified(candidate.chapterId)
            is Candidate.MangaChapter -> {
                val source = sources.get(candidate.series.source) ?: return null
                downloadProvider.findChapterDir(
                    candidate.chapter.name,
                    candidate.chapter.scanlator,
                    candidate.chapter.url,
                    candidate.series.title,
                    source,
                )?.lastModified()
            }
        }
    }

    private suspend fun currentLastModified(owned: SmartOwnedCopy): Long? {
        return when (owned.format) {
            "novel" -> novels.smartSavedLastModified(owned.chapterId)
            "manga" -> {
                val chapterId = owned.chapterId.toLongOrNull() ?: return null
                val chapter = chapters.getChapterById(chapterId) ?: return null
                val mangaId = owned.seriesKey.substringAfter(':').toLongOrNull() ?: return null
                val series = runCatching { manga.getMangaById(mangaId) }.getOrNull() ?: return null
                val source = sources.get(series.source) ?: return null
                downloadProvider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, series.title, source)?.lastModified()
            }
            else -> null
        }
    }

    private fun isWifi(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun schedule(append: Boolean) {
        if (store.state.value.paused || foreground.isActive() || tools.state.value.smartOffline.values.none { it.enabled }) return
        val request = OneTimeWorkRequestBuilder<SmartOfflineWorker>()
            // UNMETERED avoids cellular; the worker still independently requires TRANSPORT_WIFI.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun scheduleContinuation() {
        if (store.state.value.paused || foreground.isActive() || !isWifi() || tools.state.value.smartOffline.values.none { it.enabled }) return
        val request = OneTimeWorkRequestBuilder<SmartOfflineWorker>()
            .setInitialDelay(CONTINUATION_DELAY_SECONDS, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun status(message: String) { store.setCurrent(null, null, message) }

    companion object {
        const val WORK_NAME = "arkhive-smart-offline"
        const val MAX_SERIES_PER_BATCH = 20
        const val MAX_CHAPTERS_PER_BATCH = 8
        const val CONTINUATION_DELAY_SECONDS = 15L
        const val METADATA_TIMEOUT_MS = 30_000L
        const val CHAPTER_TIMEOUT_MS = 45_000L
    }
}

class SmartOfflineWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val graph = applicationContext.appGraph
    override suspend fun doWork(): Result {
        if (graph.smartOfflineStore.state.value.paused) return Result.success()
        graph.smartOfflineDispatcher.runBatch()
        return Result.success()
    }
}
