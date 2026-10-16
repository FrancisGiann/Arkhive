package mihon.readingapp.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.readingapp.design.*
import mihon.readingapp.novels.NovelReaderActivity
import mihon.readingapp.novels.NovelChapterUpdate
import mihon.readingapp.settings.ArkhiveSettingsHeader
import mihon.readingapp.settings.InkSettingsTheme
import kotlin.time.Instant

class UnifiedUpdatesScreen:Screen() {
    @Composable override fun Content()=InkSettingsTheme {
        val context=LocalContext.current;val graph=context.appGraph;val nav=LocalNavigator.currentOrThrow
        val since=remember {Instant.fromEpochMilliseconds(System.currentTimeMillis()-30L*24*60*60*1000)}
        val manga by remember {graph.getUpdates.subscribe(false,since)}.collectAsStateWithLifecycle(emptyList())
        val novelState by graph.novelRepository.state.collectAsStateWithLifecycle()
        val hidden by graph.libraryVisibility.hiddenSeries.changes().collectAsStateWithLifecycle(graph.libraryVisibility.hiddenSeries.get())
        var novels by remember {mutableStateOf(emptyList<NovelChapterUpdate>())}
        var filter by remember {mutableStateOf("All")}
        LaunchedEffect(novelState,hidden) {novels=graph.novelRepository.updates(since.toEpochMilliseconds()).filterNot {"novel:${it.novel.id}" in hidden}}
        Column(Modifier.fillMaxSize().background(Ink.Canvas)) {
            ArkhiveSettingsHeader("Chapter inbox",{nav.pop();Unit})
            Row(Modifier.padding(horizontal=22.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("All","Manga","Novels").forEach {value->InkPill(value,filter==value) {filter=value}}}
            LazyColumn(Modifier.weight(1f).padding(top=16.dp).clip(RoundedCornerShape(topStart=18.dp,topEnd=18.dp)).background(Ink.Panel),contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {Text("Unread chapters observed in the last 30 days. Manga uses its existing Updates metadata; novels appear when a refresh discovers new chapters.",color=Ink.Muted,fontSize=12.sp)}
                val rows=buildList {
                    if(filter!="Novels") manga.forEach {update->add(InboxItem("manga:${update.chapterId}",update.mangaTitle,update.chapterName,"Manga",update.dateFetch.toEpochMilliseconds()) {context.startActivity(ReaderActivity.newIntent(context,update.mangaId,update.chapterId))})}
                    if(filter!="Manga") novels.forEach {update->add(InboxItem("novel:${update.chapter.id}",update.novel.detail.name,update.chapter.name,"Novel",update.chapter.arrivedAt) {context.startActivity(NovelReaderActivity.intent(context,update.novel.id,update.chapter.id))})}
                }.sortedByDescending {it.date}
                items(rows,key={it.key}) {row->Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.Raised).clickable(onClick=row.open).padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(row.title,color=Ink.Text);Text(row.chapter,color=Ink.Muted,fontSize=12.sp)
                    Text("${row.kind} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(row.date))}",color=Ink.Muted,fontSize=11.sp)
                }}
                if(rows.isEmpty()) item {Text("No observed unread chapter updates yet.",color=Ink.Muted)}
                item {TextButton(onClick={nav.push(eu.kanade.tachiyomi.ui.updates.UpdatesTab)}) {Text("Manga update controls")};Text("Refresh an individual novel from its title or Series options. No novel polling or new notifications are enabled automatically.",color=Ink.Muted,fontSize=11.sp)}
            }
        }
    }
}
private data class InboxItem(val key:String,val title:String,val chapter:String,val kind:String,val date:Long,val open:()->Unit)

@Composable fun UnifiedDownloadsContent()=InkSettingsTheme {
    val context=LocalContext.current;val graph=context.appGraph;val nav=LocalNavigator.currentOrThrow;val scope=rememberCoroutineScope()
    val queue by graph.downloadManager.queueState.collectAsStateWithLifecycle()
    val running by graph.downloadManager.isDownloaderRunning.collectAsStateWithLifecycle(false)
    val novels by graph.novelRepository.state.collectAsStateWithLifecycle()
    val smart by graph.smartOfflineStore.state.collectAsStateWithLifecycle()
    val libraryTools by graph.libraryToolsStore.state.collectAsStateWithLifecycle()
    val hidden by graph.libraryVisibility.hiddenSeries.changes().collectAsStateWithLifecycle(graph.libraryVisibility.hiddenSeries.get())
    val visibleManga=queue.filterNot {"manga:${it.manga.id}" in hidden}
    val visibleNovels=novels.downloads.filterNot {"novel:${it.novelId}" in hidden}
    var storage by remember {mutableStateOf(emptyList<SeriesStorage>())};var scanning by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    var cleanupMessage by remember {mutableStateOf<String?>(null)}
    val visibleSmartOwned = smart.ownedCopies.values.count { it.seriesKey !in hidden }
    Column(Modifier.fillMaxSize().background(Ink.Canvas)) {
        ArkhiveSettingsHeader("Downloads",null)
        LazyColumn(Modifier.weight(1f).clip(RoundedCornerShape(topStart=18.dp,topEnd=18.dp)).background(Ink.Panel),contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {Text("${visibleManga.size+visibleNovels.size} visible chapters queued",color=Ink.Text);Row {
                TextButton(onClick={scope.launch {graph.smartOfflineDispatcher.pauseAll();graph.downloadManager.pauseDownloads();graph.novelRepository.pause(true)}}) {Text("Pause all")}
                TextButton(onClick={scope.launch {graph.downloadManager.startDownloads();graph.novelRepository.pause(false);graph.smartOfflineDispatcher.resumeAll()}}) {Text("Resume all")}
            };Text("Manga ${if(running) "running" else "paused"} · novels ${if(novels.paused) "paused" else "ready"} · Smart ${when {smart.paused->"paused";smart.running->"running";else->"idle"}}",color=Ink.Muted,fontSize=12.sp)}
            item {Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.Raised).padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("Smart offline · $visibleSmartOwned saved chapter${if(visibleSmartOwned==1) "" else "s"}",color=Ink.Text)
                if(smart.currentSeries !in hidden) smart.currentChapter?.let {Text(it,color=Ink.Muted,fontSize=11.sp)}
                smart.message?.let {Text(it,color=Ink.Muted,fontSize=11.sp)}
                Text("Only opted-in series run. Smart work uses saved chapter lists, pauses for readers and does not resume manual queues.",color=Ink.Muted,fontSize=11.sp)
                TextButton(onClick={scope.launch {
                    val count=graph.smartOfflineDispatcher.cleanupReadOwned()
                    cleanupMessage=if(count==0) "No completed, unbookmarked Smart-owned copies were eligible." else "Removed $count completed Smart-owned ${if(count==1) "copy" else "copies"}."
                }},enabled=libraryTools.cleanupReadSmartCopies) {Text(if(libraryTools.cleanupReadSmartCopies) "Clean read Smart copies" else "Cleanup is off in Library tools")}
                cleanupMessage?.let {Text(it,color=Ink.Muted,fontSize=11.sp)}
            }}
            items(visibleManga,key={"m:${it.chapter.id}"}) {job->Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.Raised).padding(14.dp)) {
                val status by job.statusFlow.collectAsStateWithLifecycle();val progress by job.progressFlow.collectAsStateWithLifecycle(0)
                Text(job.manga.title,color=Ink.Text);Text(job.chapter.name,color=Ink.Muted,fontSize=12.sp)
                Text("${status.name.lowercase().replaceFirstChar(Char::uppercase)} · $progress%",color=Ink.Muted,fontSize=11.sp)
                Row {if(status==Download.State.ERROR) TextButton(onClick={scope.launch {graph.downloadManager.startDownloadNow(job.chapter.id)}}) {Text("Retry")}
                    TextButton(onClick={scope.launch {graph.downloadManager.cancelQueuedDownloads(listOf(job))}}) {Text("Cancel")}}
            }}
            items(visibleNovels,key={"n:${it.id}"}) {job->Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.Raised).padding(14.dp)) {
                Text(job.novelTitle,color=Ink.Text);Text(job.chapterName,color=Ink.Muted,fontSize=12.sp)
                Text(job.state.replaceFirstChar(Char::uppercase),color=Ink.Muted,fontSize=11.sp);job.error?.let {Text(it,color=MaterialTheme.colorScheme.error,fontSize=11.sp)}
                Row {if(job.state in setOf("failed","retry")) TextButton(onClick={scope.launch {graph.novelRepository.retry(job.id)}}) {Text("Retry")}
                    TextButton(onClick={scope.launch {graph.novelRepository.cancel(job.id)}}) {Text("Cancel")}}
            }}
            if(visibleManga.isEmpty() && visibleNovels.isEmpty()) item {Text("No visible queued chapters. Saved copies open from Library.",color=Ink.Muted)}
            item {TextButton(onClick={nav.push(eu.kanade.tachiyomi.ui.download.DownloadQueueScreen)}) {Text("Advanced manga queue")}}
            item {InkKicker("Saved storage");TextButton(onClick={scope.launch {scanning=true;message=null
                runCatching {graph.seriesToolsRepository.available().take(200).map {graph.seriesToolsRepository.storage(it.key)}}.fold({storage=it},{message=it.message});scanning=false
            }},enabled=!scanning) {Text(if(scanning) "Measuring…" else "Measure saved storage")};Text("Measured on demand. Very large folders may return a lower bound. Original local-source files are excluded.",color=Ink.Muted,fontSize=11.sp)}
            items(storage,key={it.series.key}) {usage->Row(Modifier.fillMaxWidth()) {Text(usage.series.title,Modifier.weight(1f),color=Ink.Text);Text("${if(usage.complete) "" else "≥ "}${formatStorage(usage.bytes)}",color=Ink.Muted)}}
            message?.let {item {Text(it,color=MaterialTheme.colorScheme.error)}}
            item {Spacer(Modifier.navigationBarsPadding())}
        }
    }
}
private fun formatStorage(bytes:Long)=if(bytes>=1024*1024) "%.1f MiB".format(bytes/(1024.0*1024)) else "%.1f KiB".format(bytes/1024.0)
