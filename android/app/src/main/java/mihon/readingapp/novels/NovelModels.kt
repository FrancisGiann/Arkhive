package mihon.readingapp.novels

import kotlinx.serialization.Serializable

/** Separate text source contract; native Mihon HttpSource/Page extensions remain unchanged. */
@Serializable data class NovelItem(val path: String, val name: String, val cover: String? = null)
@Serializable data class NovelChapter(val path: String, val name: String, val chapterNumber: Double? = null,
    val releaseTime: String? = null)
@Serializable data class SourceNovel(val path: String, val name: String, val author: String? = null,
    val cover: String? = null, val genres: List<String> = emptyList(), val summary: String? = null,
    val chapters: List<NovelChapter>, val status: String? = null, val provenanceUrl: String,
    val rights: String)
data class NovelCatalogPage(val items: List<NovelItem>, val nextPage: Int?, val basis: String)
interface NovelSource {
    val id: String
    val name: String
    val site: String
    suspend fun catalog(page: Int, search: String = "", genre: String? = null): NovelCatalogPage
    suspend fun detail(path: String): SourceNovel
    suspend fun chapter(path: String): String
}
@Serializable data class SavedNovel(val id: String, val sourceId: String, val detail: SourceNovel,
    val favorite: Boolean, val lastChapter: String? = null, val lastRead: Long = 0,
    val chapterProgress: Double = 0.0, val totalChapters: Int = 0, val readChapters: Int = 0,
    val progression: Double = 0.0, val savedChapters: Int = 0)
data class ChapterState(val id: String, val novelId: String, val path: String, val name: String,
    val position: Int, val progression: Double = 0.0, val bookmark: Boolean = false,
    val downloaded: Boolean = false, val cached: Boolean = false,
    val chapterNumber: Double? = null, val releaseTime: String? = null, val arrivedAt: Long = 0)
data class NovelChapterUpdate(val novel: SavedNovel, val chapter: ChapterState)
@Serializable data class NovelChapterBackup(val path:String,val name:String,val position:Int,val progression:Double,
    val bookmark:Boolean,val chapterNumber:Double?=null,val releaseTime:String?=null,val arrivedAt:Long=0)
@Serializable data class NovelBackupEntry(val sourceId:String,val detail:SourceNovel,val favorite:Boolean,
    val lastChapterPath:String?=null,val lastRead:Long=0,val chapters:List<NovelChapterBackup>)
data class NovelDownload(val id: String, val novelId: String, val novelTitle: String, val chapterPath: String,
    val chapterName: String, val state: String, val attempts: Int, val error: String?, val token: String)
data class NovelLibraryState(val novels: List<SavedNovel> = emptyList(), val downloads: List<NovelDownload> = emptyList(), val paused: Boolean = false)
