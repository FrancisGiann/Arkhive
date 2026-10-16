package mihon.readingapp.navigation

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import eu.kanade.tachiyomi.ui.main.MainActivity
import android.content.ActivityNotFoundException
import mihon.app.di.appGraph
import mihon.domain.book.LocalBook
import mihon.domain.book.LocalBookFormat
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.FilterList
import mihon.icons.materialsymbols.rounded.MoreVert
import eu.kanade.tachiyomi.source.online.HttpSource
import tachiyomi.domain.manga.model.MangaUpdate
import mihon.readingapp.books.BookReaderActivity
import mihon.readingapp.books.BookImportState
import mihon.readingapp.books.BookImportStatusPanel
import mihon.readingapp.books.BooksShelf
import mihon.readingapp.design.*
import mihon.readingapp.discovery.MangaContentClassification
import mihon.readingapp.fixtures.OriginalDemoSource
import mihon.readingapp.library.ShelfAssignment
import mihon.readingapp.library.ShelfStatus

object LibrarySearchEvents {
    private var sequence = 0L
    val query = MutableStateFlow(0L to "")
    fun request(text: String) { query.value = (++sequence) to text }
}
private data class ShelfStory(val key: String, val title: String, val author: String, val kind: String, val cover: Any?,
    val progression: Float, val status: String, val lastRead: Long, val mangaId: Long? = null, val book: LocalBook? = null, val novel: mihon.readingapp.novels.SavedNovel? = null)

/** One library presentation over the existing manga and local-book repositories. No database migration. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LibraryDashboard() {
    val context = LocalContext.current
    val graph = context.appGraph
    val navigator = LocalNavigator.currentOrThrow
    val tabs = LocalTabNavigator.current
    val scope = rememberCoroutineScope()
    val mangaState by remember { graph.mangaRepository.getLibraryMangaAsFlow().map { it as List<tachiyomi.domain.library.model.LibraryManga>? } }.collectAsStateWithLifecycle(null)
    val bookState by remember { graph.localBookRepository.observeBooks().map { it as List<LocalBook>? } }.collectAsStateWithLifecycle(null)
    val novelState by graph.novelRepository.state.collectAsStateWithLifecycle()
    val removedBookIds by graph.libraryShelfMembership.removedBookIds.collectAsStateWithLifecycle()
    val importState by graph.bookImportCoordinator.state.collectAsStateWithLifecycle()
    val libraryTools by graph.libraryToolsStore.state.collectAsStateWithLifecycle()
    val hiddenSeries by graph.libraryVisibility.hiddenSeries.changes().collectAsStateWithLifecycle(graph.libraryVisibility.hiddenSeries.get())
    val novels = novelState.novels
    val novelDownloads = remember(novelState.downloads) { novelState.downloads.groupBy { it.novelId } }
    val manga = mangaState.orEmpty().distinctBy { it.id }
    val books = bookState.orEmpty().filterNot { it.id in removedBookIds }
    LaunchedEffect(mangaState != null && bookState != null) {
        if (mangaState != null && bookState != null) (context as? MainActivity)?.ready = true
    }
    val externalQuery by LibrarySearchEvents.query.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable(libraryTools.dashboardFilter) { mutableStateOf(libraryTools.dashboardFilter) }
    var tools by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var openingStoryKey by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDeleteCopies by remember { mutableStateOf(false) }
    var organize by remember { mutableStateOf(false) }
    var addToShelves by remember { mutableStateOf(false) }
    var confirmHide by remember { mutableStateOf(false) }
    var revealHidden by remember { mutableStateOf(false) }
    var hiddenVisit by rememberSaveable { mutableStateOf(false) }
    var collection by rememberSaveable(libraryTools.dashboardCollection) { mutableStateOf(libraryTools.dashboardCollection) }
    val snackbar = remember { SnackbarHostState() }
    fun selectDashboard(filterValue: String = filter, collectionValue: String = collection) {
        filter = filterValue
        collection = collectionValue
        graph.libraryToolsStore.setDashboardSelection(filterValue, collectionValue)
    }
    LaunchedEffect(externalQuery) { if (externalQuery.second.isNotBlank()) { query = externalQuery.second; searchOpen = true } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && !graph.bookImportCoordinator.start(uris)) feedback = "An import is already running. Its progress is shown in Library."
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && !graph.bookImportCoordinator.start(uris)) feedback = "An import is already running. Its progress is shown in Library."
    }
    fun launchImport() {
        if (importState is BookImportState.Running) {
            feedback = "An import is already running. Its progress is shown in Library."
            return
        }
        try { picker.launch(arrayOf("application/epub+zip","application/pdf","text/plain")) }
        catch (_: ActivityNotFoundException) { feedback = "Android's file picker is unavailable on this device." }
    }
    fun launchPdfImport() {
        if (importState is BookImportState.Running) {
            feedback = "An import is already running. Its progress is shown in Library."
            return
        }
        try { pdfPicker.launch(arrayOf("application/pdf")) }
        catch (_: ActivityNotFoundException) { feedback = "Android's file picker is unavailable on this device." }
    }
    val stories = remember(manga, books, novels, novelDownloads, novelState.paused) {
        fun novelOfflineStatus(novel: mihon.readingapp.novels.SavedNovel): String {
            val jobs = novelDownloads[novel.id].orEmpty()
            val saved = novel.savedChapters.coerceIn(0, novel.totalChapters)
            val parts = buildList {
                if (novel.totalChapters > 0 && saved == novel.totalChapters) add("saved offline")
                else if (saved > 0) add("saved $saved/${novel.totalChapters}")
                else add("not saved offline")
                val downloading = jobs.count { it.state == "downloading" }
                val queued = jobs.count { it.state == "queued" }
                val retry = jobs.count { it.state == "retry" }
                val failed = jobs.count { it.state == "failed" }
                if (downloading > 0) add("$downloading downloading")
                if (queued > 0) add(if (novelState.paused) "$queued queued · paused" else "$queued queued")
                if (retry > 0) add("$retry to retry")
                if (failed > 0) add("$failed failed")
            }
            return "${novel.readChapters} / ${novel.totalChapters} read · ${parts.joinToString(" · ")}"
        }
        manga.map { item -> ShelfStory("manga:${item.id}",
            if (item.manga.source in listOf(OriginalDemoSource.MANGA_ID, OriginalDemoSource.WEBTOON_ID)) item.manga.title.substringBefore(" · Original demo") else item.manga.title,
            item.manga.author.orEmpty(),
            MangaContentClassification.fromGenres(item.manga.genre).label, item.manga,
            if (item.totalChapters > 0) (item.readCount.toFloat()/item.totalChapters).coerceIn(0f,1f) else 0f,
            "${item.readCount} / ${item.totalChapters} chapters read", item.lastRead?.toEpochMilliseconds() ?: 0, mangaId = item.id) } +
            books.map { book -> ShelfStory("book:${book.id}", book.title, book.author ?: "Local file", book.format.name,
                graph.localBookImporter.coverFile(book), book.progression.toFloat().coerceIn(0f,1f),
                if (book.locator == null) "Start reading" else "${(book.progression*100).toInt()}% · ${book.format.name}",
                if (book.locator != null) book.updatedAt else 0, book = book) } + novels.map {n -> ShelfStory("novel:${n.id}",n.detail.name,n.detail.author ?: n.sourceId,"Novel",n.detail.cover,
                n.progression.toFloat().coerceIn(0f,1f),novelOfflineStatus(n),n.lastRead,novel=n) }
    }
    val visible = stories.filter { story ->
        (if(hiddenVisit) story.key in hiddenSeries else story.key !in hiddenSeries) &&
        (collection.isEmpty() || libraryTools.assignments[story.key]?.let { collection == it.status?.label || collection in it.shelves } == true) &&
        (when(filter) { "Novels" -> story.novel != null || (story.book != null && story.book.format != LocalBookFormat.PDF); "Manga" -> story.mangaId != null;
            "PDFs" -> story.book?.format == LocalBookFormat.PDF; "In progress" -> story.lastRead > 0; else -> true }) &&
            (query.isBlank() || story.title.contains(query,true) || story.author.contains(query,true))
    }.sortedBy { it.title.lowercase() }
    val resume = visible.filter { it.lastRead > 0 }.maxByOrNull { it.lastRead }
    fun detail(story: ShelfStory) {
        if(story.novel!=null) {navigator.push(mihon.readingapp.novels.NovelDetailScreen(story.novel.sourceId,story.novel.detail.path));return}
        if (story.mangaId != null) navigator.push(MangaScreen(story.mangaId))
        else story.book?.let { navigator.push(mihon.readingapp.books.BookDetailScreen(it.id)) }
    }
    fun novelCardAvailability(novel: mihon.readingapp.novels.SavedNovel): String {
        val jobs = novelDownloads[novel.id].orEmpty()
        val saved = novel.savedChapters.coerceIn(0, novel.totalChapters)
        val downloading = jobs.count { it.state == "downloading" }
        val queued = jobs.count { it.state == "queued" }
        val retry = jobs.count { it.state == "retry" }
        val failed = jobs.count { it.state == "failed" }
        return buildList {
            add(when {
                novel.totalChapters <= 0 -> "No chapters yet"
                saved == novel.totalChapters -> "Offline"
                saved > 0 -> "$saved/${novel.totalChapters} saved"
                else -> "Not saved offline"
            })
            if (failed > 0) add("$failed failed")
            if (downloading > 0) add("$downloading downloading")
            if (queued > 0) add(if (novelState.paused) "$queued queued · paused" else "$queued queued")
            if (retry > 0) add("$retry retry")
        }.joinToString(" · ")
    }
    fun read(story: ShelfStory) {
        if(story.novel!=null) {context.startActivity(mihon.readingapp.novels.NovelReaderActivity.intent(context,story.novel.id,story.novel.lastChapter));return}
        if (story.mangaId == null) story.book?.let { context.startActivity(Intent(context, BookReaderActivity::class.java).putExtra(BookReaderActivity.BOOK_ID,it.id)) }
        else {
            if (openingStoryKey != null) return
            openingStoryKey = story.key
            scope.launch {
                try {
                    val chapter = withContext(Dispatchers.IO) {
                        graph.getNextChapters.await(story.mangaId).firstOrNull()
                    }
                    if (chapter != null) context.startActivity(ReaderActivity.newIntent(context, story.mangaId, chapter.id))
                    else detail(story)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    feedback = error.message ?: "The next chapter could not be opened."
                } finally {
                    if (openingStoryKey == story.key) openingStoryKey = null
                }
            }
        }
    }
    fun toggleSelection(story: ShelfStory) {
        selected = if (story.key in selected) selected - story.key else selected + story.key
    }
    fun removeSelectedFromLibrary() {
        val targets = stories.filter { it.key in selected }
        if (targets.isEmpty()) return
        val mangaFavorites = targets.mapNotNull { story ->
            story.mangaId?.let { id -> manga.firstOrNull { it.id == id }?.manga?.favoriteAt?.let { id to it } }
        }
        scope.launch {
            targets.forEach { story ->
                when {
                    story.book != null -> graph.libraryShelfMembership.removeBook(story.book.id)
                    story.novel != null -> graph.novelRepository.favorite(story.novel.id, false)
                    story.mangaId != null -> graph.mangaRepository.update(MangaUpdate(story.mangaId) { favoriteAt = null })
                }
            }
            selected = emptySet()
            if (snackbar.showSnackbar(
                    message = "${targets.size} ${if (targets.size == 1) "item" else "items"} removed from Library",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Long,
                ) == SnackbarResult.ActionPerformed) {
                targets.forEach { story ->
                    when {
                        story.book != null -> graph.libraryShelfMembership.restoreBook(story.book.id)
                        story.novel != null -> graph.novelRepository.favorite(story.novel.id, true)
                        story.mangaId != null -> mangaFavorites.firstOrNull { it.first == story.mangaId }?.second?.let { restored ->
                            graph.mangaRepository.update(MangaUpdate(story.mangaId) { favoriteAt = restored })
                        }
                    }
                }
            }
        }
    }
    fun deleteSelectedCopies() {
        val targets = stories.filter { it.key in selected }
        scope.launch {
            var deleted = 0
            var skipped = 0
            targets.forEach { story ->
                when {
                    story.book != null -> {
                        if (graph.localBookImporter.deletePrivateAssets(story.book.id)) deleted++
                        graph.libraryShelfMembership.removeBook(story.book.id)
                    }
                    story.novel != null -> {
                        graph.novelRepository.chapters(story.novel.id).filter { it.downloaded || it.cached }
                            .forEach { graph.novelRepository.removeSaved(it.id); deleted++ }
                    }
                    story.mangaId != null -> {
                        val item = manga.firstOrNull { it.id == story.mangaId }?.manga
                        val source = item?.let { graph.sourceManager.get(it.source) }
                        if (item != null && source is HttpSource) {
                            graph.downloadManager.deleteManga(item, source)
                            deleted++
                        } else skipped++
                    }
                }
            }
            selected = emptySet()
            feedback = buildString {
                append(if (deleted > 0) "Downloaded/private copies removed. Progress and bookmarks were kept." else "No downloaded copies were found.")
                if (skipped > 0) append(" Local-source files were left untouched.")
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(GridCells.Adaptive(if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.4f) 145.dp else 92.dp),
        Modifier.fillMaxSize().background(Ink.Canvas).statusBarsPadding().padding(horizontal = 24.dp),
        contentPadding = PaddingValues(top = 40.dp, bottom = if (selected.isEmpty()) 24.dp else 184.dp), horizontalArrangement = Arrangement.spacedBy(26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InkKicker(if(hiddenVisit) "Revealed for this visit" else "Pick up your story")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    InkHeading(if(hiddenVisit) "Hidden library" else "Your library", Modifier.weight(1f))
                    InkIcon(MaterialSymbols.Rounded.Search, "Search library") { searchOpen = !searchOpen }
                    Spacer(Modifier.width(8.dp)); InkIcon(MaterialSymbols.Rounded.Settings,"Settings") { tabs.current = MoreTab }
                }
                if (searchOpen) OutlinedTextField(query,{query=it},placeholder={Text("Title or author")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("All" to "All", "Novels" to "Novels", "Comics" to "Manga").forEach { (label, value) ->
                            InkPill(label, if(value=="All") filter in setOf("All","PDFs","In progress") else filter==value) { selectDashboard(value) }
                        }
                    }
                    val activeFilterCount=(if(filter in setOf("PDFs","In progress")) 1 else 0)+(if(collection.isNotEmpty()) 1 else 0)
                    InkIcon(MaterialSymbols.Rounded.FilterList,if(activeFilterCount==0) "Filters" else "Filters, $activeFilterCount active") { filtersOpen=true }
                    InkIcon(MaterialSymbols.Rounded.MoreVert,"More actions") { tools=true }
                }
                val activeFilterLabels=listOfNotNull(
                    filter.takeIf {it in setOf("PDFs","In progress")},
                    collection.takeIf {it.isNotEmpty()},
                )
                if(activeFilterLabels.isNotEmpty()) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("Filtered by ${activeFilterLabels.joinToString(" · ")}",Modifier.weight(1f),color=Ink.Muted,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                    TextButton(onClick={selectDashboard("All","")},modifier=Modifier.heightIn(min=48.dp)) {Text("Clear filters",color=Ink.Link)}
                }
                if(hiddenVisit) InkPill("Return to library") {hiddenVisit=false;selected=emptySet();selectDashboard(collectionValue="");query=""}
                BookImportStatusPanel(
                    state = importState,
                    onOpen = { id -> navigator.push(mihon.readingapp.books.BookDetailScreen(id)) },
                    onRetry = { graph.bookImportCoordinator.retry() },
                    onDismiss = { graph.bookImportCoordinator.dismiss() },
                )
            }
        }
        if (resume != null && query.isBlank()) item(span={GridItemSpan(maxLineSpan)}) {
            Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    InkKicker("Continue reading")
                    if(resume.mangaId!=null && resume.kind in listOf("Manga","Webtoon") && resume.author.contains("Arkhive",true)) Text("Original sample",color=Ink.Muted,fontSize=11.sp)
                    else if(resume.book!=null) Text("Saved offline",color=Ink.Muted,fontSize=11.sp)
                }
                Box(Modifier.fillMaxWidth().heightIn(min=221.dp).clip(RoundedCornerShape(20.dp)).background(Ink.Violet).inkSlashes().inkContextPress(
                    onClick = { if (selected.isEmpty()) read(resume) else toggleSelection(resume) },
                    onLongClick = { toggleSelection(resume) },
                )) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=22.dp),horizontalArrangement=Arrangement.spacedBy(22.dp),verticalAlignment=Alignment.Top) {
                        ShelfCover(resume,Modifier.width(114.dp).aspectRatio(.695f))
                        Column(Modifier.weight(1f).heightIn(min=164.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(resume.title.uppercase(),style=Ink.Display.copy(fontSize=26.sp,lineHeight=30.sp),color=Ink.OnViolet,maxLines=3,overflow=TextOverflow.Ellipsis)
                            Text(resume.author,color=Ink.OnViolet,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(resume.kind.uppercase(),Modifier.clip(RoundedCornerShape(8.dp)).background(Ink.OnViolet).padding(horizontal=10.dp,vertical=6.dp),color=Ink.Text,fontSize=10.sp)
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(progress={resume.progression},modifier=Modifier.fillMaxWidth().padding(end=48.dp).height(3.dp),color=Ink.OnViolet,trackColor=Ink.OnViolet.copy(alpha=.3f))
                            Text(resume.status,Modifier.padding(end=42.dp),color=Ink.OnViolet,fontSize=12.sp,maxLines=2)
                        }
                    }
                    Box(Modifier.align(Alignment.BottomEnd).padding(end=14.dp,bottom=16.dp).size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Ink.OnViolet),contentAlignment=Alignment.Center) {
                        if (openingStoryKey == resume.key) CircularProgressIndicator(Modifier.size(22.dp), color=Ink.Violet, strokeWidth=2.dp)
                        else Icon(MaterialSymbols.AutoMirroredRounded.ArrowForward,"Resume reading",tint=Ink.Text,modifier=Modifier.size(24.dp))
                    }
                    if (resume.key in selected) SelectionBadge(Modifier.align(Alignment.TopEnd).padding(12.dp))
                }
            }
        }
        item(span={GridItemSpan(maxLineSpan)}) {
            Row(Modifier.fillMaxWidth().height(24.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                InkKicker(if(filter=="All") "On your shelf" else if(filter=="Manga") "Comics" else filter)
                if (visible.isNotEmpty()) TextButton(onClick={navigator.push(LibraryToolsScreen(false))},contentPadding=PaddingValues(horizontal=4.dp,vertical=0.dp),modifier=Modifier.requiredHeight(48.dp)) {Text("View all",color=Ink.Link,fontSize=11.sp)}
            }
        }
        if (visible.isEmpty() && filter == "PDFs") item(span={GridItemSpan(maxLineSpan)}) {
                Column(Modifier.fillMaxWidth().padding(vertical=24.dp),horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Ink.Raised), contentAlignment=Alignment.Center) {
                        Text("PDF", color=Ink.Link, style=Ink.Display.copy(fontSize=16.sp))
                    }
                    Text("No PDFs yet", color=Ink.Text, style=Ink.Display.copy(fontSize=24.sp))
                    Text("Keep a reading copy on this device by importing a PDF you own.", color=Ink.Muted, fontSize=13.sp)
                    Button(onClick=::launchPdfImport, enabled=importState !is BookImportState.Running,
                        modifier=Modifier.fillMaxWidth().widthIn(max=360.dp).heightIn(min=48.dp),
                        shape=RoundedCornerShape(14.dp)) { Text("Import PDF") }
                    TextButton(onClick=::launchImport, enabled=importState !is BookImportState.Running,
                        modifier=Modifier.heightIn(min=48.dp)) { Text("Import EPUB or text", color=Ink.Link) }
                }
        } else if (visible.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) {
                Column(Modifier.fillMaxWidth().padding(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(if(stories.isEmpty()) "Your next chapter starts here." else "No matching stories.",color=Ink.Text,style=Ink.Display.copy(fontSize=24.sp))
                    InkPill("Import a book") { launchImport() }
                    InkPill("Browse sources") { tabs.current=eu.kanade.tachiyomi.ui.browse.BrowseTab }
                }
        }
        items(visible,key={it.key}) { story ->
            Column(Modifier.inkContextPress(
                onClick = { if (selected.isEmpty()) detail(story) else toggleSelection(story) },
                onLongClick = { toggleSelection(story) },
            ),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Box {
                    ShelfCover(story,Modifier.fillMaxWidth().aspectRatio(.68f))
                    if (story.key in selected) SelectionBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
                }
                Text(story.title,color=Ink.Text,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(story.kind+if(story.book!=null && story.lastRead>0) " · ${(story.progression*100).toInt()}%" else "",color=Ink.Muted,fontSize=10.sp,maxLines=1)
                story.novel?.let { Text(novelCardAvailability(it), color = Ink.Muted, fontSize = 9.sp, lineHeight = 12.sp) }
            }
        }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (selected.isEmpty()) 12.dp else 176.dp))
    if (selected.isNotEmpty()) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),shape=RoundedCornerShape(18.dp),color=Ink.Panel,shadowElevation=8.dp) {
        Column(Modifier.padding(horizontal=14.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={selected=emptySet()}) { Icon(MaterialSymbols.Rounded.Close,"Close selection",tint=Ink.Text) }
                Text("${selected.size} selected",Modifier.weight(1f),color=Ink.Text,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton(onClick={addToShelves=true}) { Text("Add to shelf") }
                TextButton(onClick={organize=true}) { Text("Organize") }
                if(selected.size==1) TextButton(onClick={navigator.push(mihon.readingapp.library.SeriesToolsScreen(selected.single()));selected=emptySet()}) {Text("Series options")}
                TextButton(onClick={confirmHide=true}) { Text(if(hiddenVisit) "Unhide" else "Hide") }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton(onClick=::removeSelectedFromLibrary) { Text("Remove from library") }
                TextButton(onClick={confirmDeleteCopies=true}) { Text("Delete copies") }
            }
        }
    }
    }
    if (filtersOpen) ModalBottomSheet(
        onDismissRequest={filtersOpen=false},
        sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=Ink.Panel,
        contentColor=Ink.Text,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(horizontal=20.dp,vertical=10.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Library filters",color=Ink.Text,style=Ink.Display.copy(fontSize=24.sp))
            Text("Quick filters",color=Ink.Muted,fontSize=12.sp)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                InkPill("PDFs",filter=="PDFs") {selectDashboard(if(filter=="PDFs") "All" else "PDFs")}
                InkPill("In progress",filter=="In progress") {selectDashboard(if(filter=="In progress") "All" else "In progress")}
            }
            HorizontalDivider(color=Ink.Raised,modifier=Modifier.padding(vertical=4.dp))
            Text("Reading status and shelves",color=Ink.Muted,fontSize=12.sp)
            InkPill("Every shelf",collection.isEmpty()) {selectDashboard(collectionValue="")}
            (ShelfStatus.entries.map {it.label}+libraryTools.shelves).distinct().forEach {shelf->
                InkPill(shelf,collection==shelf) {selectDashboard(collectionValue=if(collection==shelf) "" else shelf)}
            }
            if(filter!="All" || collection.isNotEmpty()) TextButton(onClick={selectDashboard("All","")},modifier=Modifier.heightIn(min=48.dp)) {
                Text("Clear all filters",color=Ink.Link)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (tools) ModalBottomSheet(
        onDismissRequest={tools=false},
        sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=Ink.Panel,
        contentColor=Ink.Text,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(horizontal=20.dp, vertical=8.dp),
            verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("Library actions", color=Ink.Text, style=Ink.Display.copy(fontSize=24.sp))
            Text("Import and manage", color=Ink.Muted, fontSize=11.sp, modifier=Modifier.padding(top=8.dp, bottom=2.dp))
            LibraryToolRow("＋", "Import EPUB, PDF or text") { tools=false;launchImport() }
            LibraryToolRow("▤", "Manage imported books") { tools=false;navigator.push(LibraryToolsScreen(true)) }
            HorizontalDivider(color=Ink.Raised, modifier=Modifier.padding(vertical=6.dp))
            Text("Reading library", color=Ink.Muted, fontSize=11.sp, modifier=Modifier.padding(top=2.dp, bottom=2.dp))
            LibraryToolRow("▦", "Collections and manga filters") { tools=false;navigator.push(LibraryToolsScreen(false)) }
            LibraryToolRow("↻", "Chapter inbox") { tools=false;navigator.push(mihon.readingapp.library.UnifiedUpdatesScreen()) }
            LibraryToolRow("◉", "Hidden library") { tools=false;revealHidden=true }
            Spacer(Modifier.height(8.dp))
        }
    }
    feedback?.let { message -> AlertDialog(onDismissRequest={feedback=null},title={Text("Import")},text={Text(message)},confirmButton={TextButton(onClick={feedback=null}){Text("Done")}}) }
    if (confirmDeleteCopies) AlertDialog(onDismissRequest={confirmDeleteCopies=false},title={Text("Delete downloaded copies?")},
        text={Text("Arkhive will delete its private EPUB, PDF, text and downloaded chapter copies. Your originally selected files stay where they are. Reading progress and bookmarks are kept.")},
        confirmButton={TextButton(onClick={confirmDeleteCopies=false;deleteSelectedCopies()}){Text("Delete copies")}},
        dismissButton={TextButton(onClick={confirmDeleteCopies=false}){Text("Cancel")}})
    if(addToShelves) mihon.readingapp.library.AddToShelvesDialog(
        keys=selected, store=graph.libraryToolsStore, onDismiss={addToShelves=false},
        onSaved={addToShelves=false;selected=emptySet()},
    )
    if(organize) mihon.readingapp.library.OrganizeSeriesDialog(selected,graph.libraryToolsStore,{organize=false;selected=emptySet()})
    if(confirmHide) AlertDialog(onDismissRequest={confirmHide=false},title={Text(if(hiddenVisit) "Unhide selected stories?" else "Hide selected stories?")},
        text={Text(if(hiddenVisit) "These stories will return to the default library, updates and reading history." else "These stories leave default Library, Continue reading, updates and history. Reveal them deliberately in Hidden library. Hiding does not encrypt their files.")},
        confirmButton={TextButton(onClick={graph.libraryVisibility.setHidden(selected,!hiddenVisit);selected=emptySet();confirmHide=false}) {Text(if(hiddenVisit) "Unhide" else "Hide")}},
        dismissButton={TextButton(onClick={confirmHide=false}) {Text("Cancel")}})
    if(revealHidden) AlertDialog(onDismissRequest={revealHidden=false},title={Text("Reveal hidden library?")},text={Text("Show hidden stories for this visit. They stay hidden until you select Unhide.")},
        confirmButton={TextButton(onClick={hiddenVisit=true;selectDashboard("All","");query="";selected=emptySet();revealHidden=false}) {Text("Reveal")}},
        dismissButton={TextButton(onClick={revealHidden=false}) {Text("Cancel")}})
}
@Composable private fun LibraryToolRow(glyph:String, label:String, onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clip(RoundedCornerShape(14.dp)).background(Ink.Canvas)
        .combinedClickable(onClick=onClick).padding(horizontal=12.dp), verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Ink.Raised), contentAlignment=Alignment.Center) {
            Text(glyph, Modifier.clearAndSetSemantics {}, color=Ink.Link, fontSize=19.sp)
        }
        Text(label, color=Ink.Text, fontSize=14.sp)
    }
}
@Composable private fun SelectionBadge(modifier:Modifier=Modifier) {
    Box(modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Ink.Citrus).border(2.dp,Ink.Canvas,androidx.compose.foundation.shape.CircleShape),contentAlignment=Alignment.Center) {
        Icon(MaterialSymbols.Rounded.Check,"Selected",tint=Ink.OnViolet,modifier=Modifier.size(20.dp))
    }
}
@Composable private fun ShelfCover(story: ShelfStory,modifier:Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Ink.Raised)) {
        if(story.novel?.sourceId?.startsWith("lnreader.")==true)
            mihon.readingapp.novels.NovelCoverImage(story.novel.sourceId,story.title,story.novel.detail.cover,Modifier.fillMaxSize())
        else if(story.cover!=null) AsyncImage(story.cover,null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
        else Text(story.title.uppercase(),Modifier.padding(10.dp),color=Ink.Text,style=Ink.Display.copy(fontSize=18.sp,lineHeight=20.sp),maxLines=5)
    }
}
class LibraryToolsScreen(private val books:Boolean):Screen() {
    @Composable override fun Content() { if(books) Column(Modifier.fillMaxSize().background(Ink.Canvas).statusBarsPadding()) { BooksShelf() } else LibraryTab.Content() }
}
