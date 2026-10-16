package mihon.readingapp.design

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import kotlinx.coroutines.runBlocking
import mihon.app.di.appGraph
import mihon.data.database.DatabaseRepositoryImpl
import mihon.readingapp.fixtures.OriginalDemoSource
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.chapter.model.Chapter
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
import kotlin.time.Clock

/** Explicit screenshot-only original fixtures. Not auto-seeded in the main APK or production library. */
@RunWith(AndroidJUnit4::class)
class StoryboardFixtureTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = context.appGraph
    private val marker get() = File(context.cacheDir,"original-storyboard-fixture.json")
    private fun repos(): Triple<SqlDriver,MangaRepositoryImpl,ChapterRepositoryImpl> {
        lateinit var driver: SqlDriver
        driver=DatabaseBindings.providesSqlDriver(context,DatabaseRepositoryImpl(lazy{driver}))
        val database=DatabaseBindings.providesDatabase(driver)
        return Triple(driver,MangaRepositoryImpl(database),ChapterRepositoryImpl(database))
    }
    @Test fun seedOwnOriginalLibrary() = runBlocking {
        check(context.packageName=="dev.francisgiann.readingapp.dev")
        check(!marker.exists()) { "Preserve the scoped fixture or clean only its recorded IDs first." }
        val nonce=UUID.randomUUID().toString();val ids=JSONArray();val repo=repos()
        try {
            for(sourceId in listOf(OriginalDemoSource.MANGA_ID,OriginalDemoSource.WEBTOON_ID)) {
                val source=graph.sourceManager.get(sourceId) as OriginalDemoSource
                for(remote in source.getPopularManga(1).mangas) {
                    val update=source.getMangaUpdate(remote,emptyList(),true,true)
                    val mode=if(sourceId==OriginalDemoSource.WEBTOON_ID) ReadingMode.WEBTOON else ReadingMode.RIGHT_TO_LEFT
                    val manga=NetworkToLocalManga(repo.second)(Manga.create().copy(source=source.id,
                        url=remote.url+"/original-storyboard-"+nonce,title=remote.title,author=update.manga.author,
                        artist=update.manga.artist,description=update.manga.description,genre=update.manga.genre?.split(',')?.map(String::trim),
                        thumbnailUrl=remote.thumbnail_url,initialized=true,favoriteAt=Clock.System.now(),
                        viewerFlags=(mode.flagValue or ReaderOrientation.LOCKED_PORTRAIT.flagValue).toLong(),chapterFlags=Manga.CHAPTER_SORTING_NUMBER))
                    ids.put(manga.id)
                    repo.third.updateFromRemote(emptyList(),update.chapters.mapIndexed { index,c -> Chapter.create().copy(
                        mangaId=manga.id,url=c.url,name=c.name,chapterNumber=c.chapter_number.toDouble(),sourceOrder=index.toLong(),dateFetch=Clock.System.now()) },emptyList())
                }
            }
            val dir=File(context.cacheDir,"original-book-fixtures").apply{mkdirs()};val epub=File(dir,"original-small-horizons-$nonce.epub")
            writeNovel(epub,nonce)
            val book=graph.localBookImporter.import(FileProvider.getUriForFile(context,"${context.packageName}.bookfixtures",epub)).getOrThrow().book
            graph.localBookRepository.saveProgress(book.id,"""{"href":"chapter-8.xhtml","type":"application/xhtml+xml","locations":{"progression":0.4,"totalProgression":0.41}}""",.41,System.currentTimeMillis())
            marker.writeText(JSONObject().put("nonce",nonce).put("mangas",ids).put("book",book.id).toString())
            epub.delete();assertEquals(4,ids.length());assertEquals("Small Horizons",book.title)
        } finally {repo.first.close()}
    }
    @Test fun refreshOnlyOwnCoverArt() = runBlocking {
        val state=JSONObject(marker.readText());UUID.fromString(state.getString("nonce"));val repo=repos()
        try {
            val ids=state.getJSONArray("mangas")
            for(i in 0 until ids.length()) {
                val manga=checkNotNull(repo.second.getMangaById(ids.getLong(i)))
                check(manga.url.endsWith(state.getString("nonce")))
                graph.coverCache.deleteFromCache(manga,true)
                val source=graph.sourceManager.get(manga.source) as OriginalDemoSource
                for(chapter in repo.third.getChapterByMangaId(manga.id)) {
                    val legacy=eu.kanade.tachiyomi.source.model.SChapter.create().apply {url=chapter.url}
                    graph.chapterCache.putPageListToCache(chapter,source.getPageList(legacy))
                }
            }
        } finally {repo.first.close()}
    }
    @Test fun restoreOnlyOwnNovelComparisonPosition() = runBlocking {
        val state=JSONObject(marker.readText()); val nonce=state.getString("nonce");UUID.fromString(nonce)
        val book=checkNotNull(graph.localBookRepository.get(state.getString("book")))
        check(book.originalName.contains(nonce))
        graph.localBookRepository.saveProgress(book.id,"""{"href":"chapter-8.xhtml","type":"application/xhtml+xml","locations":{"progression":0.4,"totalProgression":0.41}}""",.41,System.currentTimeMillis())
    }
    private fun writeNovel(file:File,nonce:String) {
        val chapters=(1..19).toList()
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(path:String,text:String,stored:Boolean=false) {
                val bytes=text.toByteArray();val e=ZipEntry(path)
                if(stored){e.method=ZipEntry.STORED;e.size=bytes.size.toLong();e.compressedSize=e.size;e.crc=CRC32().apply{update(bytes)}.value}
                zip.putNextEntry(e);zip.write(bytes);zip.closeEntry()
            }
            entry("mimetype","application/epub+zip",true)
            entry("META-INF/container.xml","""<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("package.opf","""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:$nonce</dc:identifier><dc:title>Small Horizons</dc:title><dc:creator>Mira Vale</dc:creator><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-13T00:00:00Z</meta></metadata><manifest><item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>${chapters.joinToString(""){"<item id=\"c$it\" href=\"chapter-$it.xhtml\" media-type=\"application/xhtml+xml\"/>"}}</manifest><spine>${chapters.joinToString(""){"<itemref idref=\"c$it\"/>"}}</spine></package>""")
            entry("nav.xhtml","""<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>${chapters.joinToString(""){"<li><a href=\"chapter-$it.xhtml\">Chapter $it</a></li>"}}</ol></nav></body></html>""")
            for(chapter in chapters) entry("chapter-$chapter.xhtml","""<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter $chapter</title></head><body><h2>CHAPTER $chapter</h2><h1>Where the coast turns inward</h1>${(1..12).joinToString(""){"<p>In the morning, the road curved away from the sea. The village held its breath, as if the tide had left a question on the shore.</p><p>Mira put the map on the table and opened the book. Its pages smelled faintly of cedar. Beyond the glass, a new day gathered slowly.</p>"}}<p>Original self-authored storyboard sample.</p></body></html>""")
            zip.putNextEntry(ZipEntry("cover.png"));InstrumentationRegistry.getInstrumentation().context.assets.open("arkhive-art/small-horizons.png").use{it.copyTo(zip)};zip.closeEntry()
        }
    }
}
