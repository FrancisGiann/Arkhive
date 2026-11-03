package mihon.readingapp.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import mihon.icons.materialsymbols.rounded.KeyboardArrowUp
import mihon.icons.materialsymbols.roundedfilled.Pause
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnifiedDownloadsContent() = InkSettingsTheme {
    val context = LocalContext.current
    val graph = context.appGraph
    val nav = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val queue by graph.downloadManager.queueState.collectAsStateWithLifecycle()
    val running by graph.downloadManager.isDownloaderRunning.collectAsStateWithLifecycle(false)
    val novels by graph.novelRepository.state.collectAsStateWithLifecycle()
    val smart by graph.smartOfflineStore.state.collectAsStateWithLifecycle()
    val libraryTools by graph.libraryToolsStore.state.collectAsStateWithLifecycle()
    val extensionManager = remember(graph) { graph.novelRepository.sources.lnReaderExtensions }
    val extensionSnapshot by extensionManager.snapshots.collectAsStateWithLifecycle()
    val hidden by graph.libraryVisibility.hiddenSeries.changes().collectAsStateWithLifecycle(graph.libraryVisibility.hiddenSeries.get())
    val visibleManga = queue.filterNot { "manga:${it.manga.id}" in hidden }
    val visibleNovels = novels.downloads.filterNot { "novel:${it.novelId}" in hidden }
    val visibleCount = visibleManga.size + visibleNovels.size
    val hasHiddenQueue = queue.size > visibleManga.size || novels.downloads.size > visibleNovels.size
    val smartRulesEnabled = libraryTools.smartOffline.values.any { it.enabled }
    val rawActions = DownloadsActionPolicy.resolve(
        mangaQueueCount = queue.size,
        mangaRunning = running,
        novelQueueCount = novels.downloads.size,
        novelsPaused = novels.paused,
        smartRulesEnabled = smartRulesEnabled,
        smartPaused = smart.paused,
        smartRunning = smart.running,
    )
    val reviewableSmartOrigin = smart.originReview?.takeIf { pending ->
        val savedNovel = novels.novels.firstOrNull { it.id == pending.seriesKey.removePrefix("novel:") && it.sourceId == pending.sourceId }
        val installed = extensionSnapshot.installedPlugins.firstOrNull { it.sourceId == pending.sourceId }
        SmartOfflinePolicy.canReviewOrigin(
            review = pending,
            hiddenSeries = hidden,
            favoriteSourceId = savedNovel?.takeIf { it.favorite }?.sourceId,
            smartRuleEnabled = libraryTools.smartOffline[pending.seriesKey]?.enabled == true,
            installedEnabled = installed?.enabled == true,
            installedBundleSha256 = installed?.sha256,
            installedAt = installed?.installedAt,
            approvedOrigins = installed?.approvedOrigins,
        )
    }
    var storage by remember { mutableStateOf(emptyList<SeriesStorage>()) }
    var scanning by remember { mutableStateOf(false) }
    var storageError by remember { mutableStateOf<String?>(null) }
    var cleanupMessage by remember { mutableStateOf<String?>(null) }
    var smartDetailsExpanded by rememberSaveable { mutableStateOf(false) }
    val visibleSmartOwned = smart.ownedCopies.values.count { it.seriesKey !in hidden }
    Column(Modifier.fillMaxSize().background(Ink.Canvas)) {
        ArkhiveSettingsHeader("Downloads", null)
        LazyColumn(
            Modifier.weight(1f).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(Ink.Panel),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                DownloadsQueueSummary(
                    visibleCount = visibleCount,
                    hasHiddenQueue = hasHiddenQueue,
                    visibleMangaCount = visibleManga.size,
                    visibleNovelCount = visibleNovels.size,
                    mangaRunning = running,
                    novelsPaused = novels.paused,
                    actionState = rawActions,
                    onPause = {
                        scope.launch {
                            graph.smartOfflineDispatcher.pauseAll()
                            graph.downloadManager.pauseDownloads()
                            graph.novelRepository.pause(true)
                        }
                    },
                    onResume = {
                        scope.launch {
                            graph.downloadManager.startDownloads()
                            graph.novelRepository.pause(false)
                            graph.smartOfflineDispatcher.resumeAll()
                        }
                    },
                )
            }
            item {
                SmartOfflineSummary(
                    savedCount = visibleSmartOwned,
                    status = rawActions.smartStatus,
                    currentChapter = smart.currentChapter.takeIf { smart.currentSeries !in hidden },
                    message = smart.message.takeIf { smart.currentSeries !in hidden },
                    expanded = smartDetailsExpanded,
                    onToggleExpanded = { smartDetailsExpanded = !smartDetailsExpanded },
                    blockedOrigin = reviewableSmartOrigin?.origin,
                    onReviewOrigin = reviewableSmartOrigin?.let { pending ->
                        {
                            context.startActivity(
                                mihon.readingapp.novels.NovelExtensionOriginReviewActivity.intent(
                                    context,
                                    pending.sourceId,
                                    pending.origin,
                                    pending.bundleSha256,
                                    pending.seriesKey,
                                    pending.installedAt,
                                    pending.approvedOrigins,
                                ),
                            )
                        }
                    },
                    cleanupEnabled = libraryTools.cleanupReadSmartCopies,
                    cleanupMessage = cleanupMessage,
                    onCleanup = {
                        scope.launch {
                            val count = graph.smartOfflineDispatcher.cleanupReadOwned()
                            cleanupMessage = if (count == 0) {
                                "No eligible completed Smart copies were found."
                            } else {
                                "Removed $count eligible Smart ${if (count == 1) "copy" else "copies"}."
                            }
                        }
                    },
                    onConfigureCleanup = { nav.push(LibraryToolsScreen()) },
                )
            }
            items(visibleManga, key = { "m:${it.chapter.id}" }) { job ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.Raised).padding(14.dp)) {
                    val status by job.statusFlow.collectAsStateWithLifecycle()
                    val progress by job.progressFlow.collectAsStateWithLifecycle(0)
                    val content = inkReadableForeground(Ink.Text, Ink.Raised)
                    val secondary = inkReadableForeground(Ink.Muted, Ink.Raised)
                    Text(job.manga.title, color = content)
                    Text(job.chapter.name, color = secondary, fontSize = 12.sp)
                    Text("${status.name.lowercase().replaceFirstChar(Char::uppercase)} · $progress%", color = secondary, fontSize = 11.sp)
                    Row {
                        if (status == Download.State.ERROR) TextButton(
                            onClick = { scope.launch { graph.downloadManager.startDownloadNow(job.chapter.id) } },
                            colors = ButtonDefaults.textButtonColors(contentColor = inkReadableForeground(Ink.Violet, Ink.Raised)),
                        ) { Text("Retry") }
                        TextButton(
                            onClick = { scope.launch { graph.downloadManager.cancelQueuedDownloads(listOf(job)) } },
                            colors = ButtonDefaults.textButtonColors(contentColor = inkReadableForeground(Ink.Violet, Ink.Raised)),
                        ) { Text("Cancel") }
                    }
                }
            }
            items(visibleNovels, key = { "n:${it.id}" }) { job ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.Raised).padding(14.dp)) {
                    val content = inkReadableForeground(Ink.Text, Ink.Raised)
                    val secondary = inkReadableForeground(Ink.Muted, Ink.Raised)
                    Text(job.novelTitle, color = content)
                    Text(job.chapterName, color = secondary, fontSize = 12.sp)
                    Text(job.state.replaceFirstChar(Char::uppercase), color = secondary, fontSize = 11.sp)
                    job.error?.let {
                        Text("Error: $it", color = inkReadableForeground(MaterialTheme.colorScheme.error, Ink.Raised), fontSize = 11.sp)
                    }
                    Row {
                        if (job.state in setOf("failed", "retry")) TextButton(
                            onClick = { scope.launch { graph.novelRepository.retry(job.id) } },
                            colors = ButtonDefaults.textButtonColors(contentColor = inkReadableForeground(Ink.Violet, Ink.Raised)),
                        ) { Text("Retry") }
                        TextButton(
                            onClick = { scope.launch { graph.novelRepository.cancel(job.id) } },
                            colors = ButtonDefaults.textButtonColors(contentColor = inkReadableForeground(Ink.Violet, Ink.Raised)),
                        ) { Text("Cancel") }
                    }
                }
            }
            if (visibleManga.isEmpty() && visibleNovels.isEmpty()) {
                item {
                    val foreground = inkReadableForeground(Ink.Muted, Ink.Panel)
                    Text(
                        when {
                            hasHiddenQueue -> "Some queued chapters are hidden by Library filters."
                            smart.running -> "No visible chapters queued. Smart Offline is working."
                            smartRulesEnabled && !smart.paused -> "No visible chapters queued. Smart Offline is waiting."
                            else -> "Nothing is queued. Saved chapters are in your Library."
                        },
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        color = foreground,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = { nav.push(eu.kanade.tachiyomi.ui.download.DownloadQueueScreen) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = inkReadableForeground(Ink.Violet, Ink.Panel)),
                    ) { Text("Advanced manga queue") }
                    TextButton(
                        onClick = {
                            scope.launch {
                                scanning = true
                                storageError = null
                                runCatching {
                                    graph.seriesToolsRepository.available().take(200)
                                        .map { graph.seriesToolsRepository.storage(it.key) }
                                }.fold({ storage = it }, { storageError = it.message ?: "Storage could not be measured." })
                                scanning = false
                            }
                        },
                        enabled = !scanning,
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = inkReadableForeground(Ink.Violet, Ink.Panel)),
                    ) { Text(if (scanning) "Measuring…" else "Measure storage") }
                }
                Text("Saved storage · measured on demand · up to 200 visible stories", color = inkReadableForeground(Ink.Muted, Ink.Panel), fontSize = 11.sp)
            }
            items(storage, key = { it.series.key }) { usage ->
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(usage.series.title, Modifier.weight(1f), color = inkReadableForeground(Ink.Text, Ink.Panel), maxLines = 2)
                    Text("${if (usage.complete) "" else "≥ "}${formatStorage(usage.bytes)}", color = inkReadableForeground(Ink.Muted, Ink.Panel))
                }
            }
            storageError?.let {
                item { Text("Storage error: $it", color = inkReadableForeground(MaterialTheme.colorScheme.error, Ink.Panel)) }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadsQueueSummary(
    visibleCount: Int,
    hasHiddenQueue: Boolean,
    visibleMangaCount: Int,
    visibleNovelCount: Int,
    mangaRunning: Boolean,
    novelsPaused: Boolean,
    actionState: DownloadsActionState,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val surface = Ink.Raised
    val foreground = inkReadableForeground(Ink.Text, surface)
    val secondary = inkReadableForeground(Ink.Muted, surface)
    val resumeSurface = MaterialTheme.colorScheme.primary
    val resumeForeground = inkReadableForeground(Ink.OnViolet, resumeSurface)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(surface).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("${visibleCount} visible chapter${if (visibleCount == 1) "" else "s"}", color = foreground, style = MaterialTheme.typography.titleSmall)
        when {
            visibleCount == 0 && hasHiddenQueue -> Text("Some queued chapters are hidden by Library filters.", color = secondary, style = MaterialTheme.typography.bodySmall)
            visibleCount == 0 -> {
                Text("No visible chapters queued", color = secondary, style = MaterialTheme.typography.bodySmall)
                Text(
                    listOf(actionState.mangaStatus, actionState.novelStatus, actionState.smartStatus).joinToString(" · "),
                    color = secondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> {
                val visibleStatus = buildList {
                    if (visibleMangaCount > 0) add(if (mangaRunning) "Manga active" else "Manga waiting")
                    if (visibleNovelCount > 0) add(if (novelsPaused) "Novels paused" else "Novels ready")
                }.joinToString(" · ")
                Text(visibleStatus, color = secondary, style = MaterialTheme.typography.bodySmall)
                if (hasHiddenQueue) Text("Other queued chapters are hidden by Library filters.", color = secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (actionState.showPause || actionState.showResume) {
            Text("Pause and resume apply across manga, novels and Smart Offline.", color = secondary, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (actionState.showPause) OutlinedButton(
                    onClick = onPause,
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = if (actionState.pauseLabel == "Pause all") {
                            "Pause all downloads"
                        } else {
                            "${actionState.pauseLabel} downloads; applies across manga, novels and Smart Offline"
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Icon(MaterialSymbols.RoundedFilled.Pause, contentDescription = null, tint = foreground)
                    Spacer(Modifier.width(6.dp))
                    Text(actionState.pauseLabel, color = foreground, maxLines = 2)
                }
                if (actionState.showResume) Button(
                    onClick = onResume,
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = when (actionState.resumeLabel) {
                            "Resume downloads" -> "Resume downloads across manga, novels and Smart Offline"
                            "Resume all" -> "Resume all downloads"
                            else -> "${actionState.resumeLabel} downloads; applies across manga, novels and Smart Offline"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(contentColor = resumeForeground),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Icon(MaterialSymbols.RoundedFilled.PlayArrow, contentDescription = null, tint = resumeForeground)
                    Spacer(Modifier.width(6.dp))
                    Text(actionState.resumeLabel, color = resumeForeground, maxLines = 2)
                }
            }
        }
    }
}

@Composable
internal fun SmartOfflineSummary(
    savedCount: Int,
    status: String,
    currentChapter: String?,
    message: String?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    blockedOrigin: String?,
    onReviewOrigin: (() -> Unit)?,
    cleanupEnabled: Boolean,
    cleanupMessage: String?,
    onCleanup: () -> Unit,
    onConfigureCleanup: () -> Unit,
) {
    val surface = Ink.Raised
    val foreground = inkReadableForeground(Ink.Text, surface)
    val secondary = inkReadableForeground(Ink.Muted, surface)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Smart Offline", color = foreground, style = MaterialTheme.typography.titleSmall)
                Text("$status · $savedCount saved", color = secondary, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
                onClick = onToggleExpanded,
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = if (expanded) "Hide Smart Offline details" else "Show Smart Offline details"
                },
                colors = ButtonDefaults.textButtonColors(contentColor = inkReadableForeground(Ink.Violet, surface)),
            ) {
                Text(if (expanded) "Less" else "Details")
                Icon(
                    if (expanded) MaterialSymbols.Rounded.KeyboardArrowUp else MaterialSymbols.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
        }
        currentChapter?.let { Text(it, color = secondary, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
        if (blockedOrigin != null && onReviewOrigin != null) {
            Text("Blocked origin: $blockedOrigin", color = foreground, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = onReviewOrigin,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground),
            ) {
                Text("Review source origin", color = foreground)
            }
        }
        if (expanded) {
            HorizontalDivider()
            message?.let { Text(it, color = secondary, style = MaterialTheme.typography.bodySmall) }
            Text(
                "Only opted-in stories run. Smart work uses saved chapter lists, pauses for readers and leaves manual queues alone.",
                color = secondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Only completed, unbookmarked Smart-owned copies qualify for cleanup.",
                color = secondary,
                style = MaterialTheme.typography.bodySmall,
            )
            if (cleanupEnabled) {
                OutlinedButton(
                    onClick = onCleanup,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground),
                ) {
                    Text("Clean eligible read copies", color = foreground)
                }
            } else {
                TextButton(
                    onClick = onConfigureCleanup,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = inkReadableForeground(Ink.Violet, surface)),
                ) {
                    Text("Set up Smart cleanup")
                }
                Text("Cleanup is off by default.", color = secondary, style = MaterialTheme.typography.bodySmall)
            }
            cleanupMessage?.let { Text(it, color = secondary, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
private fun formatStorage(bytes:Long)=if(bytes>=1024*1024) "%.1f MiB".format(bytes/(1024.0*1024)) else "%.1f KiB".format(bytes/1024.0)
