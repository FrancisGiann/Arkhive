package mihon.readingapp.novels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import mihon.app.di.appGraph
import mihon.readingapp.design.*
import mihon.readingapp.settings.ArkhiveSettingsHeader
import mihon.readingapp.settings.InkSettingsTheme
import mihon.readingapp.library.AddToShelvesDialog
import mihon.readingapp.library.SeriesToolAction
import mihon.readingapp.library.SeriesToolsDialog
import mihon.readingapp.library.SeriesSynopsisTagsDialog
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Info
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.Public
import mihon.readingapp.novels.lnreader.LnReaderUnapprovedCoverOriginException

class NovelCatalogScreen(private val sourceId: String) : Screen() {
    @Composable override fun Content() = NovelCatalog(sourceId)
}

@Composable fun NovelSourceList() {
    val nav=LocalNavigator.currentOrThrow
    val registry=currentNovelSources()
    LaunchedEffect(registry) { registry.refreshAvailableSources() }
    val sources by registry.availableState.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().background(Ink.Canvas),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {InkKicker("Online text sources");Text("Read actual chapters in Arkhive, with saved progress and optional offline batches.",color=Ink.Muted,fontSize=12.sp,modifier=Modifier.padding(top=8.dp))}
        items(sources,key={it.id}) {source->Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.Panel).clickable {nav.push(NovelCatalogScreen(source.id))}.padding(18.dp)) {
            Text(source.name,color=Ink.Text,style=MaterialTheme.typography.titleMedium)
            Text(if(source.id=="wikisource.en") "English · Wikisource · built in" else if(source.id.startsWith("lnreader.")) "${(source as? LnReaderNovelSource)?.site.orEmpty()} · LNReader extension" else "Controlled original test source",color=Ink.Muted,fontSize=12.sp,modifier=Modifier.padding(top=8.dp))
        }}
        item {Text("Installed novel sources use a separate text contract. Manage novel repositories and extensions in Settings → Browse.",color=Ink.Muted,fontSize=11.sp)}
    }
}

@Composable private fun NovelCatalog(sourceId: String) = InkSettingsTheme {
    val nav=LocalNavigator.currentOrThrow
    val registry=currentNovelSources()
    val source=remember(sourceId,registry) {registry.get(sourceId)}
    val extensionSource=source as? LnReaderNovelSource
    val extensionDescriptor=extensionSource?.descriptor?.collectAsStateWithLifecycle()?.value
    val pager=remember(sourceId,registry) {registry.pager(sourceId)}
    val state by pager.state.collectAsStateWithLifecycle()
    var input by rememberSaveable {mutableStateOf(state.query)}
    var filters by remember {mutableStateOf(false)}
    var filterValues by remember {mutableStateOf<Map<String,JsonElement>>(emptyMap())}
    val titles=state.items;val next=state.next;val busy=state.busy;val error=state.error;val genre=state.genre
    val basis=state.basis;val checked=state.checked
    val grid=rememberLazyListState(pager.scrollIndex,pager.scrollOffset)
    var viewportRevision by remember(pager) {mutableLongStateOf(state.revision)}
    LaunchedEffect(state.revision) {if(viewportRevision!=state.revision) {viewportRevision=state.revision;grid.scrollToItem(0)}}
    LaunchedEffect(sourceId) {pager.select(state.query,state.genre)}
    LaunchedEffect(extensionDescriptor) {
        val descriptor=extensionDescriptor ?: return@LaunchedEffect
        if(filterValues.isEmpty()) filterValues=descriptor.filters.associate {it.key to it.defaultValue}
        extensionSource?.setFilterValues(filterValues)
    }
    DisposableEffect(pager) {onDispose {pager.scrollIndex=grid.firstVisibleItemIndex;pager.scrollOffset=grid.firstVisibleItemScrollOffset;pager.pause()}}
    LaunchedEffect(grid) {snapshotFlow {grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset}.distinctUntilChanged().collect {(index,offset)->pager.scrollIndex=index;pager.scrollOffset=offset}}
    val nearEnd by remember {derivedStateOf {grid.layoutInfo.let {it.totalItemsCount>2 && (it.visibleItemsInfo.lastOrNull()?.index ?: 0)>=it.totalItemsCount-4}}}
    LaunchedEffect(nearEnd,next,busy,error) {if(nearEnd && !busy && error==null && titles.isNotEmpty()) pager.loadNext()}
    Column(Modifier.fillMaxSize().background(Ink.Canvas)) {
        ArkhiveSettingsHeader("Novels",{nav.pop();Unit})
        Row(Modifier.padding(horizontal=22.dp),verticalAlignment=Alignment.CenterVertically) {
            OutlinedTextField(input,{input=it.take(120)},placeholder={Text("Title")},singleLine=true,modifier=Modifier.weight(1f))
            TextButton(onClick={pager.select(input.trim(),genre,true)}) {Text("Search")}
        }
        Row(Modifier.padding(horizontal=22.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Text(source.name,color=Ink.Muted,fontSize=11.sp,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
            when {
                source.id=="wikisource.en" -> TextButton(onClick={filters=true}) {Text(genre ?: "Genres")}
                extensionSource!=null -> TextButton(onClick={filters=true}) {Text(if(extensionDescriptor?.filters.isNullOrEmpty()) "Source filters" else "${extensionDescriptor?.filters?.size} source filters")}
            }
        }
        LazyColumn(Modifier.weight(1f).clip(RoundedCornerShape(topStart=18.dp,topEnd=18.dp)).background(Ink.Panel),state=grid,
            contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(titles.isEmpty() && busy) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
            items(titles,key={it.path}) {title->Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.Raised).clickable {nav.push(NovelDetailScreen(source.id,title.path))}.padding(14.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
                NovelCoverImage(source.id,title.name,title.cover,Modifier.width(56.dp).height(80.dp))
                Column(Modifier.weight(1f)) {Text(title.name,color=Ink.Text,style=MaterialTheme.typography.titleMedium);Text("${source.name} · novel",color=Ink.Muted,fontSize=11.sp,modifier=Modifier.padding(top=6.dp))}
                Text("›",color=Ink.Link,fontSize=26.sp)
            }}
            item {
                if(error!=null) {Text(error!!,color=Ink.Muted);TextButton(onClick=pager::retry) {Text("Retry source")}}
                else if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if(titles.isEmpty()) Text("No novels returned for this query. Try fewer words or another genre.",color=Ink.Muted)
                else if(next==null) Text("End of this source's results",color=Ink.Muted,fontSize=11.sp)
                else TextButton(onClick=pager::loadNext) {Text("Load more")}
            }
            if(basis.isNotBlank()) item {Text("$basis\nChecked ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(checked))}",color=Ink.Muted,fontSize=11.sp,lineHeight=16.sp)}
        }
    }
    if(filters && source.id=="wikisource.en") AlertDialog(onDismissRequest={filters=false},title={Text("Source genre shelves")},text={Column {
        listOf(null,"Fantasy","Science fiction").forEach {value->TextButton(onClick={pager.select(state.query,value);filters=false}) {Text(value ?: "All genres")}}
        Text("These categories are supplied by Wikisource.",fontSize=12.sp)
    }},confirmButton={TextButton(onClick={filters=false}) {Text("Close")}})
    if(filters && extensionSource!=null) ExtensionFilterDialog(
        descriptor=extensionDescriptor,
        values=filterValues,
        onValue={key,value->filterValues=filterValues+ (key to value)},
        onDismiss={filters=false},
        onApply={extensionSource.setFilterValues(filterValues);pager.select(state.query,null,true);filters=false},
    )
}

class NovelDetailScreen(private val sourceId:String,private val path:String) : Screen() {
    @Composable override fun Content() = NovelDetail(sourceId,path)
}
@Composable private fun NovelDetail(sourceId:String,path:String) = InkSettingsTheme {
    val context=LocalContext.current;val nav=LocalNavigator.currentOrThrow;val uri=LocalUriHandler.current
    val sourceRegistry=currentNovelSources()
    val repository=remember {context.appGraph.novelRepository};val scope=rememberCoroutineScope()
    val updates by repository.state.collectAsStateWithLifecycle()
    var novel by remember {mutableStateOf<SavedNovel?>(null)};var chapters by remember {mutableStateOf(emptyList<ChapterState>())}
    var busy by remember {mutableStateOf(true)};var error by remember {mutableStateOf<String?>(null)};var refresh by remember {mutableIntStateOf(0)}
    var coverError by remember {mutableStateOf<String?>(null)}
    var coverOriginSuggestion by remember {mutableStateOf<String?>(null)}
    var coverRetry by remember {mutableIntStateOf(0)}
    var tools by remember { mutableStateOf(false) }
    var synopsis by remember { mutableStateOf(false) }
    var shelfPicker by remember { mutableStateOf(false) }
    var batches by remember {mutableStateOf(false)};var selecting by remember {mutableStateOf(false)};var selected by remember {mutableStateOf(emptySet<String>())}
    var feedback by remember {mutableStateOf<String?>(null)}
    var removing by remember {mutableStateOf<List<ChapterState>?>(null)}
    val downloadByChapter = remember(updates.downloads) { updates.downloads.associateBy { it.id } }
    val titleDownloads = remember(updates.downloads, sourceId, path) {
        val id = NovelRepository.novelId(sourceId, path)
        updates.downloads.filter { it.novelId == id }
    }
    suspend fun reload() {val id=NovelRepository.novelId(sourceId,path);novel=repository.get(id);chapters=repository.chapters(id)}
    LaunchedEffect(sourceId,path,refresh) {
        busy=true;error=null
        try {val id=NovelRepository.novelId(sourceId,path);novel=repository.get(id)
            if(novel==null || refresh>0) novel=repository.load(sourceId,path)
            reload()
        } catch(cancelled:CancellationException) {throw cancelled}
          catch(problem:Exception) {error=problem.message ?: "The source is unavailable."}
        finally {busy=false}
    }
    LaunchedEffect(updates) {if(novel!=null) reload()}
    val life=LocalLifecycleOwner.current.lifecycle
    val latestCoverError=rememberUpdatedState(coverError)
    DisposableEffect(life) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME) scope.launch {
            reload()
            if(latestCoverError.value!=null) coverRetry++
        }}
        life.addObserver(observer);onDispose {life.removeObserver(observer)}
    }
    fun queue(keys:Collection<String>) {scope.launch {runCatching {repository.queue(requireNotNull(novel).id,keys)}.fold({feedback="$it chapters added to Downloads"},{feedback=it.message});batches=false;selected=emptySet();selecting=false}}
    InkStatusBarStyle(Ink.HeroSurface)
    Column(Modifier.fillMaxSize().background(Ink.HeroSurface)) {
        ArkhiveSettingsHeader("Novel", { nav.pop(); Unit }, hero = true)
        novel?.let {n->Row(Modifier.fillMaxWidth().padding(start=22.dp,end=22.dp,bottom=22.dp),horizontalArrangement=Arrangement.spacedBy(18.dp)) {
            NovelCoverImage(
                sourceId, n.detail.name, n.detail.cover, Modifier.width(90.dp).height(130.dp),
                retryToken = refresh + coverRetry,
                onExtensionCoverError = { coverError = it },
                onUnapprovedCoverOrigin = { coverOriginSuggestion = it },
            )
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                InkKicker("Source edition", color = Ink.HeroMuted);Text(n.detail.name.uppercase(),style=Ink.Display.copy(fontSize=24.sp,lineHeight=28.sp),color=Ink.HeroText)
                n.detail.author?.let {Text(it,color=Ink.HeroMuted,fontSize=12.sp)}
                Text("${runCatching {sourceRegistry.get(sourceId).name}.getOrDefault("Saved novel source")} · ${chapters.size} chapters",color=Ink.HeroMuted,fontSize=11.sp)
                val saved = n.savedChapters.coerceIn(0,n.totalChapters)
                val activeCount = titleDownloads.count {it.state=="downloading"}
                val queuedCount = titleDownloads.count {it.state=="queued"}
                val retryCount = titleDownloads.count {it.state=="retry"}
                val failedCount = titleDownloads.count {it.state=="failed"}
                Text(buildList {
                    add(if(n.totalChapters>0 && saved==n.totalChapters) "Saved offline" else "Saved $saved/${n.totalChapters}")
                    if(activeCount>0) add("$activeCount downloading")
                    if(queuedCount>0) add(if(updates.paused) "$queuedCount queued · paused" else "$queuedCount queued")
                    if(retryCount>0) add("$retryCount to retry")
                    if(failedCount>0) add("$failedCount failed")
                }.joinToString(" · "),color=Ink.HeroMuted,fontSize=11.sp)
                TextButton(onClick={scope.launch {repository.favorite(n.id,!n.favorite);reload()}}) {Text(if(n.favorite) "In your library ✓" else "Add to library",color=Ink.HeroText)}
                TextButton(onClick={tools=true}) { Text("Series tools", color=Ink.HeroText) }
            }
        }}
        coverError?.let { message -> Column(Modifier.fillMaxWidth().padding(horizontal=22.dp).padding(bottom=12.dp)) {
            Text(message,color=Ink.HeroMuted,fontSize=11.sp)
            TextButton(onClick={nav.push(NovelExtensionsScreen(coverOriginSuggestion, sourceId))}) {Text("Review extension origins",color=Ink.HeroText)}
        }}
        LazyColumn(Modifier.weight(1f).clip(RoundedCornerShape(topStart=18.dp,topEnd=18.dp)).background(Ink.Panel),contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(busy) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
            if(error!=null) item {Text(error!!,color=Ink.Muted);TextButton(onClick={refresh++}) {Text("Retry source")}}
            novel?.let {n->
                item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    InkKicker("Chapters");TextButton(onClick={refresh++},enabled=!busy) {Text("Refresh")}
                }}
                item {Button(onClick={context.startActivity(NovelReaderActivity.intent(context,n.id,n.lastChapter))},enabled=chapters.isNotEmpty(),modifier=Modifier.fillMaxWidth()) {Text(if(n.lastChapter==null) "Start reading" else "Continue reading")}}
                item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    InkPill("Download",modifier=Modifier.weight(1f)) {batches=true}
                    InkPill(if(selecting) "Done" else "Select",modifier=Modifier.weight(1f)) {selecting=!selecting;selected=emptySet()}
                    InkPill("Queue",modifier=Modifier.weight(1f)) {nav.push(NovelDownloadsScreen())}
                }}
                if(selecting) item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onClick={selected=chapters.mapTo(hashSetOf()){it.id}}) {Text("Select all")}
                    TextButton(onClick={queue(selected)},enabled=selected.isNotEmpty()) {Text("Save ${selected.size}")}
                    TextButton(onClick={removing=chapters.filter {it.id in selected && it.cached}},enabled=chapters.any {it.id in selected && it.cached}) {Text("Remove saved")}
                }}
                items(chapters,key={it.id}) {c->Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ink.Raised).clickable {
                    if(selecting) selected=if(c.id in selected) selected-c.id else selected+c.id
                    else context.startActivity(NovelReaderActivity.intent(context,n.id,c.id))
                }.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(selecting) Checkbox(c.id in selected,{selected=if(it) selected+c.id else selected-c.id})
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(c.name,color=Ink.Text,fontSize=14.sp)
                        val queued = downloadByChapter[c.id]
                        Text(listOfNotNull(if(c.progression>=.999) "Read" else if(c.progression>0) "${(c.progression*100).toInt()}%" else "Unread",
                            if(c.downloaded) "Saved offline" else null,
                            queued?.let { when(it.state) {"downloading"->"Downloading";"queued"->"Queued";"retry"->"Retry pending";"failed"->"Failed";else->null} },
                            if(c.bookmark) "Bookmarked" else null).joinToString(" · "),color=Ink.Muted,fontSize=11.sp)
                    }
                    if(!selecting) TextButton(onClick={
                        when(downloadByChapter[c.id]?.state) {
                            "downloading","queued" -> scope.launch { repository.cancel(c.id); reload() }
                            "retry","failed" -> scope.launch { repository.retry(c.id); reload() }
                            else -> if(c.downloaded) removing=listOf(c) else queue(listOf(c.id))
                        }
                    }) {Text(when(downloadByChapter[c.id]?.state) {"downloading"->"Cancel";"queued"->"Cancel";"retry","failed"->"Retry";else->if(c.downloaded) "Remove" else "↓"})}
                }}
            }
        }
    }
    novel?.let { n ->
        if (tools) SeriesToolsDialog(
            actions = listOf(
                SeriesToolAction("Synopsis & tags", MaterialSymbols.Rounded.Info) { synopsis = true },
                SeriesToolAction("Add to shelf", MaterialSymbols.Rounded.LocalLibrary) { shelfPicker = true },
                SeriesToolAction("Open source website", MaterialSymbols.Rounded.Public) { uri.openUri(n.detail.provenanceUrl) },
            ),
            onDismiss = { tools = false },
        )
        if (synopsis) SeriesSynopsisTagsDialog(n.detail.summary, n.detail.genres, n.detail.rights) { synopsis = false }
        if (shelfPicker) AddToShelvesDialog(setOf("novel:${n.id}"), context.appGraph.libraryToolsStore,
            onDismiss = { shelfPicker = false }, onSaved = { shelfPicker = false })
    }
    if(batches) AlertDialog(onDismissRequest={batches=false},title={Text("Save novel chapters")},text={Column {
        val unread=chapters.filter {it.progression<.999 && !it.downloaded}
        listOf(1,5,10,25).forEach {amount->TextButton(onClick={queue(unread.take(amount).map{it.id})},enabled=unread.isNotEmpty()) {Text("Next $amount unread")}}
        TextButton(onClick={queue(unread.map{it.id})},enabled=unread.isNotEmpty()) {Text("All unread (${unread.size})")}
        TextButton(onClick={queue(chapters.filter{it.bookmark}.map{it.id})},enabled=chapters.any{it.bookmark}) {Text("Bookmarked")}
        Text("Only chapters supplied lawfully by this source are saved. Downloads pause while you read.",fontSize=12.sp)
    }},confirmButton={TextButton(onClick={batches=false}) {Text("Close")}})
    removing?.let {copies->AlertDialog(onDismissRequest={removing=null},title={Text("Remove saved chapters?")},
        text={Text("Remove ${copies.size} saved or cached chapters from this device. Reading progress and bookmarks stay in your library. The next read will need the source again.")},
        confirmButton={TextButton(onClick={scope.launch {runCatching {copies.forEach {repository.removeSaved(it.id)}}.fold({feedback="Saved copies removed"},{feedback=it.message});removing=null;selected=emptySet();reload()}}){Text("Remove")}},
        dismissButton={TextButton(onClick={removing=null}){Text("Keep")}})}
    feedback?.let {AlertDialog(onDismissRequest={feedback=null},title={Text("Downloads")},text={Text(it)},confirmButton={TextButton(onClick={feedback=null}){Text("Done")}})}
}

class NovelDownloadsScreen : Screen() {
    @Composable override fun Content() = InkSettingsTheme {
        val context=LocalContext.current;val nav=LocalNavigator.currentOrThrow;val repository=remember {context.appGraph.novelRepository}
        val graph=context.appGraph
        val state by repository.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
        val hidden by graph.libraryVisibility.hiddenSeries.changes().collectAsStateWithLifecycle(graph.libraryVisibility.hiddenSeries.get())
        val visibleDownloads=state.downloads.filterNot { "novel:${it.novelId}" in hidden }
        Column(Modifier.fillMaxSize().background(Ink.Canvas)) {
            ArkhiveSettingsHeader("Novel downloads",{nav.pop();Unit})
            LazyColumn(Modifier.weight(1f).clip(RoundedCornerShape(topStart=18.dp,topEnd=18.dp)).background(Ink.Panel),contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {Text(if(state.paused) "Paused · ${visibleDownloads.size} visible chapters" else "${visibleDownloads.size} visible chapters queued",color=Ink.Text)
                    TextButton(onClick={scope.launch {repository.pause(!state.paused)}}) {Text(if(state.paused) "Resume" else "Pause")}
                    Text("Saved chapters are limited to 1 GiB; temporary reading cache to 64 MiB. Reading takes priority over background downloads.",color=Ink.Muted,fontSize=11.sp)}
                if(visibleDownloads.isEmpty()) item {Text("No visible novel chapters queued. Saved chapters open from their title in Library.",color=Ink.Muted)}
                items(visibleDownloads,key={it.id}) {job->Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.Raised).padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(job.novelTitle,color=Ink.Text);Text(job.chapterName,color=Ink.Muted,fontSize=12.sp)
                    Text(job.state.replaceFirstChar(Char::uppercase),color=Ink.Link,fontSize=11.sp)
                    job.error?.let {Text(it,color=Ink.Muted,fontSize=11.sp)}
                    Row {if(job.state=="failed" || job.state=="retry") TextButton(onClick={scope.launch {repository.retry(job.id)}}) {Text("Retry")}
                        TextButton(onClick={scope.launch {repository.cancel(job.id)}}) {Text("Cancel")}}
                }}
            }
        }
    }
}

@Composable fun NovelCoverImage(
    sourceId:String,
    name:String,
    cover:String?,
    modifier:Modifier,
    retryToken:Int=0,
    onExtensionCoverError:(String?)->Unit = {},
    onUnapprovedCoverOrigin:(String?)->Unit = {},
) {
    val isExtension=sourceId.startsWith("lnreader.")
    val registry=currentNovelSources()
    val extension=remember(sourceId,registry,isExtension) {if(isExtension) runCatching {registry.get(sourceId) as? LnReaderNovelSource}.getOrNull() else null}
    var ownedModel by remember(sourceId,cover) {mutableStateOf<File?>(null)}
    val latestCoverError=rememberUpdatedState(onExtensionCoverError)
    LaunchedEffect(sourceId,cover,retryToken) {
        ownedModel=null
        latestCoverError.value(null)
        onUnapprovedCoverOrigin(null)
        if(isExtension && cover!=null) {
            try {
                ownedModel=extension?.coverFile(cover)
                latestCoverError.value(null)
            } catch(cancelled:CancellationException) {
                throw cancelled
            } catch(failure:Exception) {
                latestCoverError.value(failure.message ?: "The extension cover could not be loaded safely.")
                onUnapprovedCoverOrigin((failure as? LnReaderUnapprovedCoverOriginException)?.origin)
            }
        }
    }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Ink.Raised)) {
        if(isExtension) {
            if(ownedModel!=null) AsyncImage(ownedModel,name,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            else Text(name.uppercase(),Modifier.padding(9.dp),color=Ink.Text,style=Ink.Display.copy(fontSize=16.sp,lineHeight=19.sp),maxLines=5)
        }
        else if(cover!=null) AsyncImage(cover,name,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else Text(name.uppercase(),Modifier.padding(9.dp),color=Ink.Text,style=Ink.Display.copy(fontSize=16.sp,lineHeight=19.sp),maxLines=5)
    }
}

@Composable private fun ExtensionFilterDialog(
    descriptor:LnReaderSourceDescriptor?,
    values:Map<String,JsonElement>,
    onValue:(String,JsonElement)->Unit,
    onDismiss:()->Unit,
    onApply:()->Unit,
) {
    AlertDialog(onDismissRequest=onDismiss,title={Text("Source filters")},text={
        if(descriptor==null) Text("Loading this extension's declared filter definitions…")
        else if(descriptor.filters.isEmpty()) Text("This extension does not declare supported filters.")
        else Column(Modifier.heightIn(max=420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            descriptor.filters.forEach {filter->
                val value=values[filter.key] ?: filter.defaultValue
                Text(filter.label,color=Ink.Text,style=MaterialTheme.typography.titleSmall)
                when(filter.type) {
                    "Text" -> OutlinedTextField(value.jsonPrimitive.contentOrNull.orEmpty(),{onValue(filter.key,JsonPrimitive(it.take(120)))},singleLine=true,label={Text(filter.label)})
                    "Switch" -> Row(verticalAlignment=Alignment.CenterVertically) {Text("Enabled",Modifier.weight(1f),color=Ink.Muted);Switch(value.jsonPrimitive.content=="true",{onValue(filter.key,JsonPrimitive(it))})}
                    "Picker" -> filter.options.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onValue(filter.key, JsonPrimitive(option.value)) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(value.jsonPrimitive.contentOrNull == option.value, { onValue(filter.key, JsonPrimitive(option.value)) })
                            Text(option.label, color = Ink.Muted)
                        }
                    }
                    "Checkbox" -> filter.options.forEach {option->
                        val selected=value.jsonArray.mapNotNull {it.jsonPrimitive.contentOrNull}.toSet()
                        Row(Modifier.fillMaxWidth().clickable {onValue(filter.key,JsonArray(if(option.value in selected) selected.minus(option.value).map(::JsonPrimitive) else (selected+option.value).map(::JsonPrimitive)))},verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(option.value in selected,{checked->onValue(filter.key,JsonArray(if(checked) (selected+option.value).map(::JsonPrimitive) else selected.minus(option.value).map(::JsonPrimitive)))})
                            Text(option.label,color=Ink.Muted)
                        }
                    }
                    "XCheckbox" -> Text("This extension's include/exclude filter is retained at its default. Editing this filter type is not supported in the current UI.",color=Ink.Muted,fontSize=11.sp)
                    else -> Text("The extension's ${filter.type} filter is sent with its declared default. Editing this filter type is not supported.", color=Ink.Muted, fontSize=11.sp)
                }
            }
        }
    },confirmButton={TextButton(onClick=onApply,enabled=descriptor!=null) {Text("Apply")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}
