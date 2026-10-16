package mihon.readingapp.novels

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import tachiyomi.domain.download.service.DownloadPreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import mihon.readingapp.library.ChapterMappingPlan
import mihon.readingapp.library.ChapterMappingPolicy
import mihon.readingapp.library.ChapterReference
import eu.kanade.tachiyomi.data.download.SmartDownloadGate
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Separate transactional novel store: no change to Mihon's source SDK or manga/book tables. */
@SingleIn(AppScope::class)
class NovelRepository internal constructor(private val context: Context, val sources: NovelSources,
    databaseName: String, private val scheduleWorkers: Boolean, private val wifiOnly:()->Boolean={true},
    private val preloadNetworkAllowed:()->Boolean={networkAllowsPreload(context,wifiOnly())}) {
    @Inject constructor(context: Context, sources: NovelSources, downloads:DownloadPreferences) : this(context, sources, "arkhive-novels.db", true,
        {downloads.downloadOnlyOverWifi.get()}, {networkAllowsPreload(context,downloads.downloadOnlyOverWifi.get())})
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val contentLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val helper = NovelDatabase(context, databaseName)
    private val db get() = helper.writableDatabase
    private val stateMutable = MutableStateFlow(NovelLibraryState())
    val state: StateFlow<NovelLibraryState> = stateMutable
    private val foregroundTextRequests = AtomicInteger()
    private val activeTextPreload = AtomicReference<Job?>(null)
    private val storageName = if(databaseName == "arkhive-novels.db") "arkhive-novel-chapters" else "arkhive-novel-test-"+id(databaseName).take(16)
    private val savedDirectory get() = File(context.filesDir, storageName).apply { mkdirs() }
    private val cacheDirectory get() = File(context.cacheDir, storageName).apply { mkdirs() }
    @Volatile var readingActive = false
        private set
    fun readerForeground(value: Boolean) {
        readingActive = value
        if (value) activeJob?.cancel() else {
            activeTextPreload.get()?.cancel()
            schedule(true)
        }
    }
    @Volatile private var activeToken: String? = null
    @Volatile private var activeJob: Job? = null
    private val ready = scope.async {
        lock.withLock {
            db.execSQL("UPDATE novel_queue SET state='queued' WHERE state='downloading'")
            listOf(savedDirectory, cacheDirectory).forEach { dir -> dir.listFiles().orEmpty().filter { it.extension == "part" }.forEach(File::delete) }
            db.beginTransaction()
            try {
                db.rawQuery("SELECT id FROM novel_queue", null).use { cursor ->
                    while(cursor.moveToNext()) {
                        val key=cursor.getString(0);val file=ownedFile(savedDirectory,key)
                        if(file.isFile && file.length() in 1..2_000_000 && runCatching {NovelHtmlPolicy.sanitize(file.readText())}.isSuccess) {
                            db.execSQL("UPDATE novel_chapters SET saved=1 WHERE id=?",arrayOf(key))
                            db.delete("novel_queue","id=?",arrayOf(key))
                        }
                    }
                }
                // Saved markers are a database index for the Library card aggregate. Reconcile
                // missing durable files once at startup; composition and progress emissions do
                // not perform per-title filesystem walks.
                db.rawQuery("SELECT id FROM novel_chapters WHERE saved=1", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val key = cursor.getString(0)
                        val file = ownedFile(savedDirectory, key)
                        if (!file.isFile || file.length() !in 1..2_000_000) {
                            db.execSQL("UPDATE novel_chapters SET saved=0 WHERE id=?", arrayOf(key))
                        }
                    }
                }
                db.setTransactionSuccessful()
            } finally {db.endTransaction()}
            publishLocked()
        }
        if (stateMutable.value.downloads.any { it.state == "queued" || it.state == "retry" } && !stateMutable.value.paused) schedule(false)
    }
    companion object {
        const val WORK_NAME = "arkhive-novel-downloads"
        const val SAVED_LIMIT = 1024L * 1024 * 1024
        private const val TEXT_PRELOAD_TIMEOUT_MS = 4_000L
        /** Read by eager constructor startup before instance initialization has necessarily finished. */
        private const val NOVEL_QUERY = "SELECT n.*,COUNT(c.id) AS chapter_count,SUM(CASE WHEN c.progress>=0.999 THEN 1 ELSE 0 END) AS read_count,SUM(CASE WHEN c.saved=1 THEN 1 ELSE 0 END) AS saved_count,AVG(c.progress) AS whole_progress,(SELECT progress FROM novel_chapters WHERE id=n.last_chapter) AS current_progress FROM novels n LEFT JOIN novel_chapters c ON c.novel_id=n.id AND c.active=1"
        private fun networkAllowsPreload(context: Context, wifiOnly: Boolean): Boolean {
            return try {
                val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
                val network = manager.activeNetwork ?: return false
                val caps = manager.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    (!wifiOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            } catch (_: SecurityException) {
                false
            }
        }
        fun id(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun novelId(source: String, path: String) = id("$source\u0000$path")
        fun chapterId(novel: String, path: String) = id("$novel\u0000$path")
    }
    suspend fun load(sourceId: String, path: String): SavedNovel = withContext(Dispatchers.IO) {
        ready.await()
        val detail = sources.get(sourceId).detail(path)
        currentCoroutineContext().ensureActive()
        require(detail.path == path && detail.name.isNotBlank() && detail.name.length <= 500 && detail.chapters.size in 1..5000)
        require(detail.chapters.all { it.path.isNotBlank() && it.path.length <= 500 && it.name.length <= 500 })
        require(detail.chapters.map { it.path }.distinct().size == detail.chapters.size) { "The source repeated chapter paths." }
        val key = novelId(sourceId, path)
        lock.withLock {
            db.beginTransaction()
            try {
                val old = novelLocked(key)
                val values = ContentValues().apply {
                    put("id", key); put("source", sourceId); put("path", path); put("name", detail.name); put("details", json.encodeToString(SourceNovel.serializer(), detail.copy(chapters = emptyList())))
                    put("favorite", if (old?.favorite == true) 1 else 0); put("last_chapter", old?.lastChapter); put("last_read", old?.lastRead ?: 0)
                }
                // REPLACE would cascade-delete chapters/progress. Update existing rows in place.
                if (old == null) db.insertOrThrow("novels", null, values) else db.update("novels", values, "id=?", arrayOf(key))
                db.execSQL("UPDATE novel_chapters SET active=0 WHERE novel_id=?", arrayOf(key))
                detail.chapters.forEachIndexed { index, chapter ->
                    val existing=chapterLocked(chapterId(key,chapter.path))
                    val row = ContentValues().apply { put("id", chapterId(key, chapter.path)); put("novel_id", key); put("path", chapter.path); put("name", chapter.name); put("position", index); put("active", 1)
                        put("number",chapter.chapterNumber);put("release_time",chapter.releaseTime)
                        put("arrived_at",existing?.arrivedAt ?: if(old?.favorite==true) System.currentTimeMillis() else 0)
                    }
                    if (db.update("novel_chapters", row, "id=?", arrayOf(chapterId(key, chapter.path))) == 0) db.insertOrThrow("novel_chapters", null, row)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            publishLocked()
            requireNotNull(novelLocked(key))
        }
    }
    suspend fun get(id: String): SavedNovel? { ready.await(); return withContext(Dispatchers.IO) { lock.withLock { novelLocked(id) } } }
    suspend fun chapters(id: String): List<ChapterState> { ready.await(); return withContext(Dispatchers.IO) { lock.withLock { chaptersLocked(id) } } }
    suspend fun updates(after:Long=0):List<NovelChapterUpdate> {ready.await();return withContext(Dispatchers.IO) {lock.withLock {
        novelsLocked().flatMap {n->chaptersLocked(n.id).filter {it.arrivedAt>after && it.progression<.999}.map {NovelChapterUpdate(n,it)}}.sortedByDescending {it.chapter.arrivedAt}.take(500)
    }}}
    suspend fun previewMigration(previousId:String,targetId:String):ChapterMappingPlan {
        require(previousId!=targetId)
        val previous=chapters(previousId);val target=chapters(targetId)
        return ChapterMappingPolicy.plan(previous.map {ChapterReference(it.id,it.name,it.chapterNumber)},target.map {ChapterReference(it.id,it.name,it.chapterNumber)})
    }
    /** Copies only reviewed chapter progress/bookmarks into a selected alternate edition. */
    suspend fun migrateProgress(previousId:String,targetId:String,reviewed:Set<String>):Int {
        require(previousId!=targetId)
        ready.await()
        return withContext(Dispatchers.IO) {lock.withLock {
            val previousNovel=requireNotNull(novelLocked(previousId))
            val targetNovel=requireNotNull(novelLocked(targetId))
            require(previousNovel.sourceId!=targetNovel.sourceId) {"Progress transfer requires a selected edition from another source."}
            val previous=chaptersLocked(previousId);val target=chaptersLocked(targetId)
            val plan=ChapterMappingPolicy.plan(previous.map {ChapterReference(it.id,it.name,it.chapterNumber)},target.map {ChapterReference(it.id,it.name,it.chapterNumber)})
            val mapping=ChapterMappingPolicy.selected(plan,reviewed)
            val previousById=previous.associateBy {it.id};val targetById=target.associateBy {it.id}
            db.beginTransaction()
            try {
                mapping.forEach {match->
                    val from=requireNotNull(previousById[match.previous.id]);val to=requireNotNull(targetById[match.target.id])
                    // Chapter percentages depend on edition layout and text length. Carry only
                    // completed state across editions; keep the target's own partial position.
                    val progress = if (from.progression >= 0.999) maxOf(from.progression, to.progression) else to.progression
                    db.execSQL("UPDATE novel_chapters SET progress=?,bookmark=? WHERE id=? AND novel_id=?",
                        arrayOf<Any>(progress,if(from.bookmark||to.bookmark) 1 else 0,to.id,targetId))
                }
                val mappedCurrent=mapping.firstOrNull {it.previous.id==previousNovel.lastChapter}
                if(mappedCurrent!=null) db.execSQL("UPDATE novels SET last_chapter=?,last_read=? WHERE id=?",
                    arrayOf<Any>(mappedCurrent.target.id,maxOf(previousNovel.lastRead,targetNovel.lastRead),targetId))
                db.setTransactionSuccessful()
            } finally {db.endTransaction()}
            publishLocked()
            mapping.size
        }}
    }
    suspend fun backupEntries():List<NovelBackupEntry> {ready.await();return withContext(Dispatchers.IO) {lock.withLock {
        db.rawQuery("SELECT id FROM novels",null).use {c->buildList {while(c.moveToNext()) {
            val n=requireNotNull(novelLocked(c.getString(0)));val chapters=chaptersLocked(n.id,false)
            add(NovelBackupEntry(n.sourceId,n.detail,n.favorite,chapters.firstOrNull {it.id==n.lastChapter}?.path,n.lastRead,
                chapters.map {NovelChapterBackup(it.path,it.name,it.position,it.progression,it.bookmark,it.chapterNumber,it.releaseTime,it.arrivedAt)}))
        }}}
    }}}
    suspend fun restoreEntries(entries:List<NovelBackupEntry>,replace:Boolean) {ready.await();withContext(Dispatchers.IO) {lock.withLock {
        require(entries.size<=20_000)
        db.beginTransaction()
        try {
            if(replace) db.execSQL("UPDATE novels SET favorite=0")
            entries.forEach {entry->
                require(entry.detail.path.isNotBlank() && entry.detail.name.length in 1..500 && entry.chapters.size<=5000)
                require(entry.chapters.map {it.path}.distinct().size==entry.chapters.size)
                val key=novelId(entry.sourceId,entry.detail.path);val old=novelLocked(key)
                val row=ContentValues().apply {put("id",key);put("source",entry.sourceId);put("path",entry.detail.path);put("name",entry.detail.name)
                    put("details",json.encodeToString(SourceNovel.serializer(),entry.detail.copy(chapters=emptyList())))
                    put("favorite",if(entry.favorite || (!replace && old?.favorite==true)) 1 else 0)
                    put("last_read",if(replace) entry.lastRead else maxOf(entry.lastRead,old?.lastRead ?: 0))
                    put("last_chapter",entry.lastChapterPath?.let {chapterId(key,it)} ?: old?.lastChapter)
                }
                if(old==null) db.insertOrThrow("novels",null,row) else db.update("novels",row,"id=?",arrayOf(key))
                db.execSQL("UPDATE novel_chapters SET active=0 WHERE novel_id=?", arrayOf(key))
                entry.chapters.forEach {chapter->
                    require(chapter.progression.isFinite() && chapter.progression in 0.0..1.0 && chapter.position>=0 && chapter.name.length<=500)
                    val id=chapterId(key,chapter.path);val previous=chapterLocked(id)
                    val values=ContentValues().apply {put("id",id);put("novel_id",key);put("path",chapter.path);put("name",chapter.name);put("position",chapter.position);put("active",1)
                        put("progress",if(replace) chapter.progression else maxOf(chapter.progression,previous?.progression ?: 0.0))
                        put("bookmark",if(chapter.bookmark || (!replace && previous?.bookmark==true)) 1 else 0)
                        put("number",chapter.chapterNumber);put("release_time",chapter.releaseTime);put("arrived_at",chapter.arrivedAt)
                    }
                    if(previous==null) db.insertOrThrow("novel_chapters",null,values) else db.update("novel_chapters",values,"id=?",arrayOf(id))
                }
            }
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
        publishLocked()
    }}}
    suspend fun savedBytes(novel:String):Long=withContext(Dispatchers.IO) {ready.await();lock.withLock {chaptersLocked(novel,false).sumOf {ownedFile(savedDirectory,it.id).takeIf(File::isFile)?.length() ?: 0}}}
    internal fun smartSavedLastModified(chapterId: String): Long? =
        ownedFile(savedDirectory, chapterId).takeIf(File::isFile)?.lastModified()
    suspend fun favorite(id: String, value: Boolean) { ready.await(); withContext(Dispatchers.IO) { lock.withLock {
        db.execSQL("UPDATE novels SET favorite=? WHERE id=?", arrayOf<Any>(if (value) 1 else 0, id)); publishLocked()
    } } }
    suspend fun bookmark(chapter: String, value: Boolean) { ready.await(); withContext(Dispatchers.IO) { lock.withLock {
        db.execSQL("UPDATE novel_chapters SET bookmark=? WHERE id=?", arrayOf<Any>(if (value) 1 else 0, chapter)); publishLocked()
    } } }
    fun saveProgress(novel: String, chapter: String, progression: Double): Job =
        scope.launch { ready.await(); lock.withLock {
            require(progression.isFinite())
            db.beginTransaction()
            try {
                db.execSQL("UPDATE novel_chapters SET progress=? WHERE id=? AND novel_id=?", arrayOf<Any>(progression.coerceIn(0.0, 1.0), chapter, novel))
                db.execSQL("UPDATE novels SET last_chapter=?,last_read=? WHERE id=?", arrayOf<Any>(chapter, System.currentTimeMillis(), novel))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            publishLocked()
        } }
    suspend fun flush() { ready.await(); withContext(Dispatchers.IO) { lock.withLock { publishLocked() } } }
    suspend fun text(novelId: String, chapterId: String): String {
        ready.await()
        foregroundTextRequests.incrementAndGet()
        try {
            return withContext(Dispatchers.IO) {
                val requestJob = currentCoroutineContext().job
                activeTextPreload.get()?.takeIf { it !== requestJob }?.let { speculative ->
                    speculative.cancel(CancellationException("Foreground chapter read took priority."))
                }
                val pair = lock.withLock { (novelLocked(novelId) ?: error("This novel is no longer available.")) to (chapterLocked(chapterId) ?: error("This chapter is unavailable.")) }
                require(pair.second.novelId == novelId)
                contentLock.withLock {
                val saved = ownedFile(savedDirectory, chapterId)
                var repairSavedStub = false
                fun readOwned(file: File, isSaved: Boolean): String? {
                    if (!file.isFile) return null
                    check(file.length() in 1..2_000_000) { "Saved chapter is damaged. Remove its saved copy and retry." }
                    val raw = try { file.readText() } catch (_: java.io.FileNotFoundException) { return null }
                    if (NovelHtmlPolicy.isRedirectStub(raw)) {
                        if (isSaved) repairSavedStub = true
                        return null
                    }
                    val safe = NovelHtmlPolicy.sanitize(raw)
                    if (!isSaved) file.setLastModified(System.currentTimeMillis())
                    if (repairSavedStub && !isSaved) writeAtomic(saved, safe)
                    return safe
                }
                readOwned(saved, isSaved = true)
                    ?: readOwned(ownedFile(cacheDirectory, chapterId), isSaved = false)
                    ?: NovelHtmlPolicy.sanitize(sources.get(pair.first.sourceId).chapter(pair.second.path)).also { safe ->
                        if (repairSavedStub) writeAtomic(saved, safe)
                        else { writeAtomic(ownedFile(cacheDirectory, chapterId), safe); trimCache() }
                    }
                }
            }
        } finally {
            foregroundTextRequests.decrementAndGet()
        }
    }

    /**
     * Warms at most the next active chapter into the existing temporary cache. This path never
     * reads, repairs, creates, or marks a durable saved copy. It does not wait for foreground
     * chapter I/O and is canceled when a foreground read or reader background transition occurs.
     */
    internal suspend fun preloadNextText(novelId: String, currentChapterId: String): Boolean {
        ready.await()
        if (!readingActive || !preloadNetworkAllowed()) return false
        val target = withContext(Dispatchers.IO) {
            lock.withLock {
                val novel = novelLocked(novelId) ?: return@withLock null
                val current = activeChapterLocked(currentChapterId)?.takeIf { it.novelId == novelId } ?: return@withLock null
                val next = db.rawQuery(
                    "SELECT * FROM novel_chapters WHERE novel_id=? AND active=1 AND position>? ORDER BY position LIMIT 1",
                    arrayOf(novelId, current.position.toString()),
                ).use { cursor -> if (cursor.moveToFirst()) chapterFrom(cursor) else null } ?: return@withLock null
                if (next.downloaded || next.cached) return@withLock null
                val queued = db.rawQuery("SELECT 1 FROM novel_queue WHERE id=? LIMIT 1", arrayOf(next.id)).use { it.moveToFirst() }
                if (queued) return@withLock null
                novel to next
            }
        } ?: return false

        val requestJob = currentCoroutineContext().job
        if (!activeTextPreload.compareAndSet(null, requestJob)) return false
        try {
            // Never hold the chapter-storage mutex over source I/O. A transport that reaches its
            // own blocking body-read timeout must not delay foreground chapter navigation.
            if (!readingActive || foregroundTextRequests.get() > 0) return false
            // This is a cooperative cancellation budget, not a hard deadline for a source that
            // is already draining a blocking response body. Supported production transports
            // own bounded call/read timeouts; the foreground never waits for this job or lock.
            val safe = withTimeoutOrNull(TEXT_PRELOAD_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    NovelHtmlPolicy.sanitize(sources.get(target.first.sourceId).chapter(target.second.path))
                }
            } ?: return false
            currentCoroutineContext().ensureActive()
            if (!readingActive || foregroundTextRequests.get() > 0) return false
            // The current resource may have been queued or saved while the request was in flight.
            val cached = withContext(Dispatchers.IO) { lock.withLock {
                val stillEligible = novelLocked(novelId) != null && activeChapterLocked(target.second.id)?.let { !it.downloaded } == true &&
                    db.rawQuery("SELECT 1 FROM novel_queue WHERE id=? LIMIT 1", arrayOf(target.second.id)).use { !it.moveToFirst() } &&
                    !ownedFile(cacheDirectory, target.second.id).isFile && !ownedFile(savedDirectory, target.second.id).isFile
                if (!stillEligible || !readingActive || foregroundTextRequests.get() > 0) false else {
                    writeAtomic(ownedFile(cacheDirectory, target.second.id), safe)
                    trimCache()
                    true
                }
            } }
            return cached
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Speculative failures stay out of the explicit download queue and do not replace
            // the current chapter's foreground error state.
            return false
        } finally {
            // Keep the single slot occupied until this coroutine really exits. Cancellation can
            // be requested while an adapter is in a bounded blocking body read.
            activeTextPreload.compareAndSet(requestJob, null)
        }
    }
    suspend fun queue(novel: String, chapters: Collection<String>): Int {
        ready.await()
        val count = withContext(Dispatchers.IO) { lock.withLock {
            requireNotNull(novelLocked(novel))
            var added = 0
            db.beginTransaction()
            try {
                for (key in chapters.distinct().take(5000)) {
                    val chapter = chapterLocked(key) ?: continue
                    if (chapter.novelId != novel || chapter.downloaded) continue
                    val values = ContentValues().apply { put("id", key); put("state", "queued"); put("attempts", 0); put("token", UUID.randomUUID().toString()); put("added", System.currentTimeMillis()) }
                    if (db.insertWithOnConflict("novel_queue", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            publishLocked(); added
        } }
        if (count > 0 && !stateMutable.value.paused) schedule(false)
        return count
    }
    suspend fun pause(value: Boolean) {
        ready.await(); withContext(Dispatchers.IO) { lock.withLock {
            db.execSQL("INSERT OR REPLACE INTO novel_options(key,value) VALUES('paused',?)", arrayOf<Any>(if (value) "1" else "0"))
            if (value) db.execSQL("UPDATE novel_queue SET state='queued' WHERE state='downloading'")
            publishLocked()
        } }
        if (value) { activeJob?.cancel(); if (scheduleWorkers) WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
        else schedule(true)
    }
    suspend fun cancel(key: String) {
        ready.await(); val token = withContext(Dispatchers.IO) { lock.withLock {
            val old = stateMutable.value.downloads.firstOrNull { it.id == key }?.token
            db.delete("novel_queue", "id=?", arrayOf(key)); publishLocked(); old
        } }
        if (token != null && token == activeToken) { activeJob?.cancel(); schedule(true) }
    }
    suspend fun retry(key: String) { ready.await(); withContext(Dispatchers.IO) { lock.withLock {
        db.execSQL("UPDATE novel_queue SET state='queued',attempts=0,error=NULL,token=? WHERE id=?", arrayOf(UUID.randomUUID().toString(), key)); publishLocked()
    } }; schedule(true) }
    suspend fun removeSaved(chapter: String) { ready.await(); cancel(chapter); withContext(Dispatchers.IO) { contentLock.withLock { lock.withLock {
        ownedFile(savedDirectory, chapter).delete(); ownedFile(cacheDirectory, chapter).delete(); db.execSQL("UPDATE novel_chapters SET saved=0 WHERE id=?", arrayOf(chapter)); publishLocked()
    } } } }
    suspend fun remove(novel: String) { ready.await(); withContext(Dispatchers.IO) { contentLock.withLock { lock.withLock {
        val chapters = chaptersLocked(novel, activeOnly = false)
        db.delete("novels", "id=?", arrayOf(novel))
        chapters.forEach { ownedFile(savedDirectory, it.id).delete(); ownedFile(cacheDirectory, it.id).delete() }
        publishLocked()
    } } } }
    internal suspend fun claim(): NovelDownload? { ready.await(); return withContext(Dispatchers.IO) { lock.withLock {
        if (pausedLocked() || readingActive) return@withLock null
        val job = downloadsLocked().firstOrNull { it.state == "queued" || it.state == "retry" } ?: return@withLock null
        val token=UUID.randomUUID().toString()
        db.execSQL("UPDATE novel_queue SET state='downloading',attempts=attempts+1,error=NULL,token=? WHERE id=? AND token=?", arrayOf(token, job.id, job.token))
        publishLocked(); job.copy(state = "downloading", attempts = job.attempts + 1,token=token)
    } } }
    internal suspend fun runDownload(job: NovelDownload) {
        ready.await()
        activeToken = job.token; activeJob = currentCoroutineContext()[Job]
        try {
            if(readingActive) return
            withContext(Dispatchers.IO) { contentLock.withLock {
                val novel = lock.withLock { novelLocked(job.novelId) } ?: error("Novel removed from Library.")
                val cached = ownedFile(cacheDirectory, job.id).takeIf(File::isFile)
                val cachedText = cached?.readText()?.takeUnless(NovelHtmlPolicy::isRedirectStub)
                val body = NovelHtmlPolicy.sanitize(cachedText ?: sources.get(novel.sourceId).chapter(job.chapterPath))
                if (cached != null && cachedText == null) cached.delete()
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) { lock.withLock {
                    val current = downloadsLocked().firstOrNull { it.id == job.id && it.token == job.token && it.state == "downloading" }
                    if (current != null && !pausedLocked() && !readingActive) {
                        val bytes = savedDirectory.listFiles().orEmpty().filter { it.extension == "html" }.sumOf(File::length)
                        require(bytes + body.toByteArray().size <= SAVED_LIMIT) { "Saved novel chapters reached the 1 GiB limit. Remove some saved copies before retrying." }
                        writeAtomic(ownedFile(savedDirectory, job.id), body)
                        db.beginTransaction()
                        try { db.execSQL("UPDATE novel_chapters SET saved=1 WHERE id=?", arrayOf(job.id)); db.delete("novel_queue", "id=? AND token=?", arrayOf(job.id, job.token)); db.setTransactionSuccessful() }
                        finally { db.endTransaction() }
                        publishLocked()
                    }
                } }
            } }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { withContext(NonCancellable + Dispatchers.IO) { lock.withLock {
              db.execSQL("UPDATE novel_queue SET state=?,error=? WHERE id=? AND token=? AND state='downloading'",
                  arrayOf(if (job.attempts < 3) "retry" else "failed", (error.message ?: "Chapter download failed.").take(500), job.id, job.token)); publishLocked()
          } } }
        finally {
            if (activeToken == job.token) { activeToken = null; activeJob = null }
            withContext(NonCancellable + Dispatchers.IO) { lock.withLock {
                db.execSQL("UPDATE novel_queue SET state='queued' WHERE id=? AND token=? AND state='downloading'", arrayOf(job.id, job.token)); publishLocked()
            } }
        }
    }

    /** Smart-only save path: no manual queue mutation and no cache/manual-copy adoption. */
    suspend fun downloadSmartChapter(novelId: String, chapterId: String, gate: SmartDownloadGate): Long {
        ready.await()
        return withContext(Dispatchers.IO) {
            contentLock.withLock {
                val (novel, chapter) = lock.withLock {
                    requireNotNull(novelLocked(novelId)) to requireNotNull(chapterLocked(chapterId))
                }
                require(chapter.novelId == novelId)
                check(!chapter.downloaded && !ownedFile(savedDirectory, chapterId).exists()) {
                    "This chapter already has a saved copy. Smart offline skipped it."
                }
                check(!readingActive) { "Smart offline waits until the reader is in the background." }
                gate.checkAllowed()
                val body = withTimeout(45_000) {
                    NovelHtmlPolicy.sanitize(sources.get(novel.sourceId).chapter(chapter.path))
                }
                currentCoroutineContext().ensureActive()
                val bytes = body.toByteArray(Charsets.UTF_8)
                require(bytes.size in 1..2_000_000) { "Novel chapter is empty or exceeds 2 MB." }
                val target = ownedFile(savedDirectory, chapterId)
                var wroteTarget = false
                try {
                    gate.write(bytes.size) {
                        writeAtomic(target, body)
                        wroteTarget = true
                    }
                    lock.withLock {
                        gate.checkAllowed()
                        val current = chapterLocked(chapterId)
                        check(current?.novelId == novelId && chaptersLocked(novelId).any { it.id == chapterId }) { "Novel changed during the Smart save." }
                        db.execSQL("UPDATE novel_chapters SET saved=1 WHERE id=? AND novel_id=?", arrayOf(chapterId, novelId))
                        publishLocked()
                    }
                    val savedBytes = target.length().coerceAtLeast(0)
                    gate.commitItem(savedBytes)
                    savedBytes
                } catch (error: Throwable) {
                    gate.discardItem()
                    if (wroteTarget) target.delete()
                    throw error
                }
            }
        }
    }

    /** Removes only the saved file named by the Smart ownership record; manual queue state is preserved. */
    suspend fun deleteSmartOwnedChapter(novelId: String, chapterId: String): Boolean {
        ready.await()
        return withContext(Dispatchers.IO) { contentLock.withLock { lock.withLock inner@ {
            val chapter = chapterLocked(chapterId) ?: return@inner true
            if (chapter.novelId != novelId || !chapter.downloaded || downloadsLocked().any { it.id == chapterId }) return@inner false
            val file = ownedFile(savedDirectory, chapterId)
            if (file.exists() && !file.delete()) return@inner false
            db.execSQL("UPDATE novel_chapters SET saved=0 WHERE id=? AND novel_id=?", arrayOf(chapterId, novelId))
            publishLocked()
            true
        } } }
    }
    private fun schedule(replace: Boolean) {
        if (!scheduleWorkers || stateMutable.value.paused || stateMutable.value.downloads.none { it.state == "queued" || it.state == "retry" || it.state == "downloading" }) return
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<NovelDownloadWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(if(wifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    internal fun continueBatch() {
        if(!scheduleWorkers || stateMutable.value.paused) return
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME,ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<NovelDownloadWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(if(wifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED).build()).build())
    }
    private fun publishLocked() { stateMutable.value = NovelLibraryState(novelsLocked(), downloadsLocked(), pausedLocked()) }
    private fun novelsLocked(): List<SavedNovel> = db.rawQuery("$NOVEL_QUERY WHERE n.favorite=1 GROUP BY n.id ORDER BY n.last_read DESC", null).use { c -> buildList { while(c.moveToNext()) add(novelFrom(c)) } }
    private fun novelLocked(id: String): SavedNovel? = db.rawQuery("$NOVEL_QUERY WHERE n.id=? GROUP BY n.id", arrayOf(id)).use { if (it.moveToFirst()) novelFrom(it) else null }
    private fun novelFrom(c: Cursor): SavedNovel = SavedNovel(c.text("id"), c.text("source"),
        json.decodeFromString(SourceNovel.serializer(), c.text("details")), c.int("favorite") != 0,
        c.optional("last_chapter"), c.long("last_read"), c.getDouble(c.getColumnIndexOrThrow("current_progress")),
        c.int("chapter_count"), c.int("read_count"), c.getDouble(c.getColumnIndexOrThrow("whole_progress")),
        c.int("saved_count"))
    private fun chaptersLocked(novel: String, activeOnly: Boolean = true): List<ChapterState> =
        db.rawQuery("SELECT * FROM novel_chapters WHERE novel_id=?${if (activeOnly) " AND active=1" else ""} ORDER BY position", arrayOf(novel)).use { c -> buildList { while(c.moveToNext()) add(chapterFrom(c)) } }
    private fun chapterLocked(id: String): ChapterState? = db.rawQuery("SELECT * FROM novel_chapters WHERE id=?", arrayOf(id)).use { if(it.moveToFirst()) chapterFrom(it) else null }
    private fun activeChapterLocked(id: String): ChapterState? = db.rawQuery("SELECT * FROM novel_chapters WHERE id=? AND active=1", arrayOf(id)).use { if(it.moveToFirst()) chapterFrom(it) else null }
    private fun chapterFrom(c: Cursor): ChapterState {
        val id = c.text("id")
        val saved = ownedFile(savedDirectory, id).isFile
        return ChapterState(id, c.text("novel_id"), c.text("path"), c.text("name"), c.int("position"), c.getDouble(c.getColumnIndexOrThrow("progress")),
            c.int("bookmark") != 0, saved, saved || ownedFile(cacheDirectory, id).isFile,
            c.getColumnIndexOrThrow("number").let {if(c.isNull(it)) null else c.getDouble(it)},c.optional("release_time"),c.long("arrived_at"))
    }
    private fun downloadsLocked(): List<NovelDownload> = db.rawQuery("SELECT q.*,c.novel_id,c.path,c.name,n.name AS novel_title FROM novel_queue q JOIN novel_chapters c ON c.id=q.id JOIN novels n ON n.id=c.novel_id ORDER BY q.added,c.position", null).use { c -> buildList {
        while(c.moveToNext()) add(NovelDownload(c.text("id"), c.text("novel_id"), c.text("novel_title"),
            c.text("path"), c.text("name"), c.text("state"), c.int("attempts"), c.optional("error"), c.text("token")))
    } }
    private fun pausedLocked() = db.rawQuery("SELECT value FROM novel_options WHERE key='paused'", null).use { it.moveToFirst() && it.getString(0) == "1" }
    private fun ownedFile(dir: File, id: String): File { require(Regex("[a-f0-9]{64}").matches(id)); return File(dir, "$id.html") }
    private fun writeAtomic(target: File, text: String) {
        val stage = File.createTempFile("chapter-", ".part", target.parentFile)
        try {
            FileOutputStream(stage).use { it.write(text.toByteArray()); it.fd.sync() }
            Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { stage.delete() }
    }
    private fun trimCache() {
        val files = cacheDirectory.listFiles().orEmpty().filter { it.extension == "html" }.sortedByDescending(File::lastModified)
        var retained = 0L
        files.forEachIndexed { i, file -> retained += file.length(); if (i >= 24 || retained > 64L * 1024 * 1024) file.delete() }
    }
    internal suspend fun closeForTest() { ready.await(); scope.cancel(); helper.close() }
}

private fun Cursor.text(key: String): String = getString(getColumnIndexOrThrow(key))
private fun Cursor.optional(key: String): String? { val i = getColumnIndexOrThrow(key); return if (isNull(i)) null else getString(i) }
private fun Cursor.int(key: String) = getInt(getColumnIndexOrThrow(key))
private fun Cursor.long(key: String) = getLong(getColumnIndexOrThrow(key))

private class NovelDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 2) {
    init { require(Regex("[a-zA-Z0-9_-]+\\.db").matches(name)) }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE novels(id TEXT PRIMARY KEY,source TEXT NOT NULL,path TEXT NOT NULL,name TEXT NOT NULL,details TEXT NOT NULL,favorite INTEGER NOT NULL DEFAULT 0,last_chapter TEXT,last_read INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE novel_chapters(id TEXT PRIMARY KEY,novel_id TEXT NOT NULL REFERENCES novels(id) ON DELETE CASCADE,path TEXT NOT NULL,name TEXT NOT NULL,position INTEGER NOT NULL,active INTEGER NOT NULL DEFAULT 1,progress REAL NOT NULL DEFAULT 0,bookmark INTEGER NOT NULL DEFAULT 0,saved INTEGER NOT NULL DEFAULT 0,number REAL,release_time TEXT,arrived_at INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX novel_chapter_list ON novel_chapters(novel_id,active,position)")
        db.execSQL("CREATE TABLE novel_queue(id TEXT PRIMARY KEY REFERENCES novel_chapters(id) ON DELETE CASCADE,state TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,error TEXT,token TEXT NOT NULL,added INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE novel_options(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<2) {db.execSQL("ALTER TABLE novel_chapters ADD COLUMN number REAL");db.execSQL("ALTER TABLE novel_chapters ADD COLUMN release_time TEXT");db.execSQL("ALTER TABLE novel_chapters ADD COLUMN arrived_at INTEGER NOT NULL DEFAULT 0")}
    }
}
