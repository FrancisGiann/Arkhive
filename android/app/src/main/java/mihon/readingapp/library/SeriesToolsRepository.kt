package mihon.readingapp.library

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import mihon.domain.book.LocalBookRepository
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.readingapp.books.LocalBookImporter
import mihon.readingapp.novels.NovelRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.library.service.LibraryVisibility
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import java.io.File

data class LibrarySeries(val key:String,val title:String,val kind:String,val source:String,val path:String)
data class SeriesStorage(val series:LibrarySeries,val bytes:Long,val complete:Boolean=true)

@Inject
@SingleIn(AppScope::class)
class SeriesToolsRepository(
    private val manga:MangaRepository,private val chapters:ChapterRepository,private val sources:SourceManager,
    private val books:LocalBookRepository,private val importer:LocalBookImporter,private val novels:NovelRepository,
    private val tools:LibraryToolsStore,private val visibility:LibraryVisibility,private val downloads:DownloadProvider,
    private val updateRemote:UpdateMangaFromRemote,
    private val membership:LibraryShelfMembership,
) {
    suspend fun get(key:String):LibrarySeries=withContext(Dispatchers.IO) {
        require(LibraryToolsPolicy.validKey(key))
        when {
            key.startsWith("manga:")->manga.getMangaById(key.substringAfter(':').toLong()).let {LibrarySeries(key,it.title,"Manga",sources.getOrStub(it.source).name,it.url)}
            key.startsWith("novel:")->requireNotNull(novels.get(key.substringAfter(':'))).let {LibrarySeries(key,it.detail.name,"Novel",runCatching {novels.sources.get(it.sourceId).name}.getOrDefault(it.sourceId),it.detail.provenanceUrl)}
            else->requireNotNull(books.get(key.substringAfter(':'))).let {LibrarySeries(key,it.title,it.format.name,"Local import",it.originalName)}
        }
    }
    suspend fun available(includeHidden:Boolean=false):List<LibrarySeries> = withContext(Dispatchers.IO) {
        val result=manga.getFavorites().map {LibrarySeries("manga:${it.id}",it.title,"Manga",sources.getOrStub(it.source).name,it.url)} +
            novels.state.value.novels.map {LibrarySeries("novel:${it.id}",it.detail.name,"Novel",it.sourceId,it.detail.provenanceUrl)} +
            books.observeBooks().first().filterNot {it.id in membership.removedBookIds.value}.map {LibrarySeries("book:${it.id}",it.title,it.format.name,"Local import",it.originalName)}
        result.filter {includeHidden || !visibility.isHidden(it.key)}.sortedBy {it.title.lowercase()}
    }
    suspend fun check(key:String):SeriesObservation {
        val previous=tools.state.value.observations[key]
        if(previous!=null && System.currentTimeMillis()-previous.checkedAt<60_000) return previous
        val series=get(key)
        val observation=try {
            withTimeout(45_000) {
                val count:Int
                val numbers:List<Double>
                when {
                    key.startsWith("manga:")-> {
                        val selected=manga.getMangaById(key.substringAfter(':').toLong())
                        updateRemote(selected,fetchChapters=true,manualFetch=true).getOrThrow()
                        val data=chapters.getChapterByMangaId(selected.id);count=data.size;numbers=data.map {it.chapterNumber}
                    }
                    key.startsWith("novel:")-> {
                        val selected=requireNotNull(novels.get(key.substringAfter(':')))
                        novels.load(selected.sourceId,selected.detail.path)
                        val data=novels.chapters(selected.id);count=data.size;numbers=data.mapNotNull {it.chapterNumber}
                    }
                    else->{importer.ensureCurrentAsset(requireNotNull(books.get(key.substringAfter(':'))));count=1;numbers=emptyList()}
                }
                SeriesObservation(System.currentTimeMillis(),series.source,series.path,true,chapterCount=count,
                    possibleGaps=LibraryToolsPolicy.possibleGaps(numbers),message="Metadata was available on this check. Chapter text or images may still vary by source.")
            }
        } catch(timeout:TimeoutCancellationException) {
            // Keep caller cancellation distinct from this check's own deadline.
            currentCoroutineContext().ensureActive()
            SeriesObservation(System.currentTimeMillis(),series.source,series.path,false,(previous?.consecutiveFailures ?: 0)+1,
                message="This check timed out after 45 seconds. Try again later.")
        } catch(cancelled:CancellationException) {throw cancelled}
          catch(error:Exception) {SeriesObservation(System.currentTimeMillis(),series.source,series.path,false,(previous?.consecutiveFailures ?: 0)+1,
              message=(error.message ?: "This check could not reach the source.").take(1000))}
        tools.update {it.copy(observations=it.observations+(key to observation))}
        return observation
    }
    suspend fun storage(key:String):SeriesStorage=withContext(Dispatchers.IO) {
        val series=get(key)
        when {
            key.startsWith("book:")-> {
                val book=requireNotNull(books.get(key.substringAfter(':')))
                SeriesStorage(series,setOf(book.assetFile,book.originalFile,book.coverFile).filterNotNull().sumOf {File(importer.directory,it).takeIf(File::isFile)?.length() ?: 0})
            }
            key.startsWith("novel:")->SeriesStorage(series,novels.savedBytes(key.substringAfter(':')))
            else->{
                val selected=manga.getMangaById(key.substringAfter(':').toLong())
                val source=sources.get(selected.source)
                val directory=source?.let {downloads.findMangaDir(selected.title,it)}
                var bytes=0L;var visited=0;var complete=true
                val pending=ArrayDeque<com.hippo.unifile.UniFile>();directory?.let(pending::addLast)
                while(pending.isNotEmpty()) {
                    val file=pending.removeFirst()
                    if(++visited>10_000) {complete=false;break}
                    if(file.isDirectory) file.listFiles()?.forEach(pending::addLast) else bytes+=file.length()
                }
                SeriesStorage(series,bytes,complete)
            }
        }
    }
    suspend fun chapterReferences(key:String):List<ChapterReference> = withContext(Dispatchers.IO) {
        when {
            key.startsWith("manga:")->chapters.getChapterByMangaId(key.substringAfter(':').toLong()).map {ChapterReference(it.id.toString(),it.name,it.chapterNumber)}
            key.startsWith("novel:")->novels.chapters(key.substringAfter(':')).map {ChapterReference(it.id,it.name,it.chapterNumber)}
            else->emptyList()
        }
    }
    suspend fun compareNovelEditions(previousKey:String,targetKey:String):ChapterMappingPlan = withTimeout(45_000) {
        require(previousKey.startsWith("novel:") && targetKey.startsWith("novel:") && previousKey!=targetKey)
        val previous=requireNotNull(novels.get(previousKey.substringAfter(':')))
        val target=requireNotNull(novels.get(targetKey.substringAfter(':')))
        require(previous.sourceId!=target.sourceId) {"Choose a saved novel from a different source for comparison."}
        novels.load(previous.sourceId,previous.detail.path)
        novels.load(target.sourceId,target.detail.path)
        novels.previewMigration(previous.id,target.id)
    }

    suspend fun compareMangaEditions(previousKey:String,targetKey:String):ChapterMappingPlan = withTimeout(45_000) {
        require(previousKey.startsWith("manga:") && targetKey.startsWith("manga:") && previousKey!=targetKey)
        val previous=manga.getMangaById(previousKey.substringAfter(':').toLong())
        val target=manga.getMangaById(targetKey.substringAfter(':').toLong())
        require(previous.source!=target.source) {"Choose a saved manga from a different source for comparison."}
        updateRemote(previous,fetchChapters=true,manualFetch=true).getOrThrow()
        updateRemote(target,fetchChapters=true,manualFetch=true).getOrThrow()
        ChapterMappingPolicy.plan(
            chapters.getChapterByMangaId(previous.id).map {ChapterReference(it.id.toString(),it.name,it.chapterNumber)},
            chapters.getChapterByMangaId(target.id).map {ChapterReference(it.id.toString(),it.name,it.chapterNumber)},
        )
    }

    /** Copies only reviewed completion and bookmark state. Page offsets never cross sources. */
    suspend fun migrateMangaProgress(previousKey:String,targetKey:String,reviewed:Set<String>):Int = withTimeout(45_000) {
        require(previousKey.startsWith("manga:") && targetKey.startsWith("manga:") && previousKey!=targetKey)
        val previousManga=manga.getMangaById(previousKey.substringAfter(':').toLong())
        val targetManga=manga.getMangaById(targetKey.substringAfter(':').toLong())
        require(previousManga.source!=targetManga.source) {"Progress transfer requires a selected edition from another source."}
        updateRemote(targetManga,fetchChapters=true,manualFetch=true).getOrThrow()
        val previous=chapters.getChapterByMangaId(previousManga.id)
        val target=chapters.getChapterByMangaId(targetManga.id)
        val plan=ChapterMappingPolicy.plan(
            previous.map {ChapterReference(it.id.toString(),it.name,it.chapterNumber)},
            target.map {ChapterReference(it.id.toString(),it.name,it.chapterNumber)},
        )
        val mapping=ChapterMappingPolicy.selected(plan,reviewed)
        val previousById=previous.associateBy {it.id.toString()}
        val targetById=target.associateBy {it.id.toString()}
        chapters.updateAll(mapping.map {match->
            val from=requireNotNull(previousById[match.previous.id])
            val to=requireNotNull(targetById[match.target.id])
            ChapterUpdate(to.id) {
                read=from.read || to.read
                bookmark=from.bookmark || to.bookmark
            }
        })
        mapping.size
    }

    suspend fun migrateNovelProgress(previousKey:String,targetKey:String,reviewed:Set<String>):Int {
        require(previousKey.startsWith("novel:") && targetKey.startsWith("novel:") && previousKey!=targetKey)
        val previousId=previousKey.substringAfter(':');val targetId=targetKey.substringAfter(':')
        val previous=requireNotNull(novels.get(previousId));val target=requireNotNull(novels.get(targetId))
        require(previous.sourceId!=target.sourceId) {"Choose a saved novel from a different source for migration."}
        return novels.migrateProgress(previousId,targetId,reviewed)
    }
}
