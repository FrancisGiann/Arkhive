package mihon.readingapp.books

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.domain.book.LocalBookFormat
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.readingapp.design.*
import mihon.readingapp.library.AddToShelvesDialog
import mihon.readingapp.library.SeriesToolAction
import mihon.readingapp.library.SeriesToolsDialog
import mihon.readingapp.library.SeriesSynopsisTagsDialog
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.Info
import mihon.readingapp.navigation.InkNavigation
import org.json.JSONObject
import org.readium.r2.shared.publication.Link

/** Actual local publication contents in the storyboard's source-to-chapter composition. */
class BookDetailScreen(private val id:String):Screen() {
    private data class Contents(val title:String,val href:String,val locator:String)
    private data class LoadedContents(val chapters:List<Contents>,val synopsis:String?,val tags:List<String>)
    @Composable override fun Content() {
        val context=LocalContext.current;val graph=context.appGraph;val navigator=LocalNavigator.currentOrThrow
        val books by remember {graph.localBookRepository.observeBooks()}.collectAsStateWithLifecycle(emptyList())
        val hidden by graph.libraryVisibility.hiddenSeries.changes().collectAsStateWithLifecycle(graph.libraryVisibility.hiddenSeries.get())
        val removed by graph.libraryShelfMembership.removedBookIds.collectAsStateWithLifecycle()
        val book=books.firstOrNull{it.id==id}
        var chapters by remember {mutableStateOf<List<Contents>>(emptyList())}
        var error by remember {mutableStateOf<String?>(null)}
        var metadataSynopsis by remember(id) { mutableStateOf<String?>(null) }
        var metadataTags by remember(id) { mutableStateOf(emptyList<String>()) }
        var showSynopsis by remember(id) { mutableStateOf(false) }
        LaunchedEffect(book?.assetFile) {
            val selected=book?:return@LaunchedEffect
            if(selected.format!=LocalBookFormat.PDF) try {
                val loaded=withContext(Dispatchers.IO) {
                    val publication=LocalEpubEngine(context).open(graph.localBookImporter.ensureCurrentAsset(selected))
                    try {
                        val metadata = publication.metadata
                        val description = metadata.description
                        val subjects = metadata.subjects.map { it.name.trim() }
                            .filter(String::isNotEmpty).distinct()
                        fun flatten(links:List<Link>):List<Link> {
                            val pending=ArrayDeque(links);val result=mutableListOf<Link>()
                            while(pending.isNotEmpty() && result.size<5000) {
                                val link=pending.removeFirst();result+=link
                                link.children.asReversed().forEach {pending.addFirst(it)}
                            }
                            return result
                        }
                        val chapterRows=flatten(publication.tableOfContents.ifEmpty{publication.readingOrder}).take(5000).mapNotNull { link ->
                            publication.locatorFromLink(link)?.let{Contents(link.title?:(it.title?:"Chapter"),it.href.toString(),it.toJSON().toString())}
                        }
                        LoadedContents(chapterRows, description?.takeIf(String::isNotBlank), subjects)
                    } finally {publication.close()}
                }
                chapters=loaded.chapters
                metadataSynopsis=loaded.synopsis
                metadataTags=loaded.tags
            } catch(cancelled:kotlinx.coroutines.CancellationException) {throw cancelled}
              catch(e:Exception) {error=e.message?:"Contents unavailable"}
        }
        fun open(locator:String?=null) {
            context.startActivity(Intent(context,BookReaderActivity::class.java).putExtra(BookReaderActivity.BOOK_ID,id)
                .apply{if(locator!=null)putExtra(BookReaderActivity.START_LOCATOR,locator)})
        }
        val current=book?.locator?.let{runCatching{JSONObject(it).optString("href")}.getOrNull()}
        val listState=rememberLazyListState()
        var showEarlier by remember(id) { mutableStateOf(false) }
        var tools by remember(id) { mutableStateOf(false) }
        var shelfPicker by remember(id) { mutableStateOf(false) }
        val currentIndex=chapters.indexOfFirst{it.href.substringBefore('#')==current?.substringBefore('#')}.coerceAtLeast(0)
        val visibleChapters=chapters.withIndex().toList().let{if(showEarlier) it else it.drop(currentIndex)}
        val purpleHeaderVisible=listState.firstVisibleItemIndex == 0
        InkStatusBarStyle(if(purpleHeaderVisible) Ink.HeroSurface else Ink.Canvas)
        Column(Modifier.fillMaxSize().background(Ink.Canvas)) {
            LazyColumn(Modifier.weight(1f),state=listState,contentPadding=PaddingValues(bottom=24.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().background(Ink.HeroSurface).inkSlashes(Ink.Violet.copy(alpha=.18f)).statusBarsPadding().padding(start=22.dp,top=32.dp,end=22.dp,bottom=24.dp),
                        verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            androidx.compose.material3.IconButton(onClick={navigator.pop()}) {androidx.compose.material3.Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack,"Back",tint=Ink.HeroText)}
                            Spacer(Modifier.width(12.dp));Text("Library",color=Ink.HeroText,fontSize=14.sp)
                        }
                        book?.let { selected -> Row(horizontalArrangement=Arrangement.spacedBy(23.dp),verticalAlignment=Alignment.Top) {
                            Box(Modifier.width(94.dp).aspectRatio(94f/140f).clip(RoundedCornerShape(6.dp)).background(Ink.Raised)) {
                                graph.localBookImporter.coverFile(selected)?.let {AsyncImage(it,null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())}
                            }
                            Column(Modifier.weight(1f).padding(top=4.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                InkKicker("Local catalog", color = Ink.HeroMuted)
                                Text(selected.title.uppercase(),modifier=Modifier.widthIn(max=180.dp),style=Ink.Display.copy(fontSize=29.sp,lineHeight=34.sp),color=Ink.HeroText)
                                Text(selected.author?:"Local file",color=Ink.HeroMuted,fontSize=12.sp)
                                Text("Private offline copy · ${selected.format.name}",color=Ink.HeroMuted,fontSize=11.sp)
                                TextButton(onClick={tools=true}) { Text("Series tools", color=Ink.HeroText) }
                            }
                        } } ?: Text("This book is no longer in Library.",color=Ink.HeroText)
                    }
                }
                if(book!=null) item {
                    Column(Modifier.padding(horizontal=22.dp,vertical=4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("CHAPTERS",style=Ink.Display.copy(fontSize=21.sp,lineHeight=26.sp),color=Ink.Text)
                            Text(if(chapters.isEmpty())book!!.format.name else "${chapters.size} chapters",color=Ink.Muted,fontSize=10.sp)
                        }
                        Box(Modifier.fillMaxWidth().heightIn(min=48.dp).inkPress{open()},contentAlignment=Alignment.Center) {
                            Box(Modifier.fillMaxWidth().heightIn(min=35.dp).clip(RoundedCornerShape(8.dp)).background(Ink.Violet).padding(vertical=8.dp),contentAlignment=Alignment.Center) {
                                Text(if(book!!.locator==null) "Start reading" else if(chapters.isNotEmpty()) "Continue chapter ${currentIndex+1}" else "Continue reading",color=Ink.OnViolet,fontSize=11.sp,fontWeight=FontWeight.Bold)
                            }
                        }
                        error?.let{Text(it,color=Ink.Muted)}
                    }
                }
                itemsIndexed(visibleChapters,key={_,item->item.value.href}) { _,item ->
                    val index=item.index;val chapter=item.value
                    val active=chapter.href.substringBefore('#')==current?.substringBefore('#')
                    Row(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=3.dp).clip(RoundedCornerShape(10.dp)).background(if(active) Ink.Raised else Ink.Canvas)
                        .heightIn(min=48.dp).inkPress{open(chapter.locator)}.padding(horizontal=15.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(top=4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("${(index+1).toString().padStart(2,'0')}  ${chapter.title}",color=Ink.Text,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                            Text(if(active) "Current chapter · ${(book!!.progression*100).toInt()}%" else "Saved offline",color=Ink.Muted,fontSize=10.sp)
                        }
                        Text(if(active) "✓" else "→",color=Ink.Muted,fontSize=20.sp)
                    }
                }
                if(currentIndex>0 && !showEarlier) item {
                    TextButton(onClick={showEarlier=true},modifier=Modifier.padding(horizontal=24.dp)) {
                        Text("Show previous $currentIndex chapters",color=Ink.Link)
                    }
                }
            }
            InkNavigation(0) { destination -> navigator.popUntilRoot();HomeScreen.openArkhiveTab(destination) }
        }
        if (tools) SeriesToolsDialog(
            actions = listOf(
                SeriesToolAction("Synopsis & tags", MaterialSymbols.Rounded.Info) { showSynopsis = true },
                SeriesToolAction("Add to shelf", MaterialSymbols.Rounded.LocalLibrary) { shelfPicker = true },
            ),
            onDismiss = { tools = false },
        )
        if (showSynopsis) SeriesSynopsisTagsDialog(metadataSynopsis, metadataTags) { showSynopsis = false }
        if (shelfPicker) AddToShelvesDialog(setOf("book:$id"), graph.libraryToolsStore,
            onDismiss = { shelfPicker = false }, onSaved = { shelfPicker = false })
    }
}
