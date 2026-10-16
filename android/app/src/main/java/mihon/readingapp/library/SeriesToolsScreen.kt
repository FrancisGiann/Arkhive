package mihon.readingapp.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.readingapp.design.*
import mihon.readingapp.settings.ArkhiveSettingsHeader
import mihon.readingapp.settings.InkSettingsTheme
import tachiyomi.domain.manga.model.MangaUpdate

class SeriesToolsScreen(private val seriesKey:String):Screen() {
    @Composable override fun Content()=InkSettingsTheme {
        val graph=LocalContext.current.appGraph;val nav=LocalNavigator.currentOrThrow;val scope=rememberCoroutineScope()
        val state by graph.libraryToolsStore.state.collectAsStateWithLifecycle()
        var series by remember {mutableStateOf<LibrarySeries?>(null)};var error by remember {mutableStateOf<String?>(null)}
        var busy by remember {mutableStateOf(false)}
        val initial=remember {graph.libraryToolsStore.profile(seriesKey)}
        var layout by remember {mutableStateOf(initial.layout)}
        var brightness by remember {mutableStateOf(initial.brightnessPercent)}
        var speed by remember {mutableStateOf(initial.autoSpeed)}
        var room by remember {mutableStateOf(initial.room)}
        var alternatives by remember {mutableStateOf(emptyList<LibrarySeries>())}
        var chooseAlternative by remember {mutableStateOf(false)}
        var searchAlternative by remember {mutableStateOf("")}
        var comparisonTarget by remember {mutableStateOf<LibrarySeries?>(null)}
        var comparison by remember {mutableStateOf<ChapterMappingPlan?>(null)}
        var reviewed by remember {mutableStateOf(emptySet<String>())}
        var sameWorkConfirmed by remember {mutableStateOf(false)}
        var comparisonBusy by remember {mutableStateOf(false)}
        var migrationMessage by remember {mutableStateOf<String?>(null)}
        var smartEnabled by remember(seriesKey) {mutableStateOf(state.smartOffline[seriesKey]?.enabled == true)}
        var smartCount by remember(seriesKey) {mutableIntStateOf(state.smartOffline[seriesKey]?.nextUnread ?: 5)}
        var smartMessage by remember {mutableStateOf<String?>(null)}
        LaunchedEffect(seriesKey) {runCatching {graph.seriesToolsRepository.get(seriesKey)}.fold({series=it},{error=it.message})}
        LaunchedEffect(seriesKey,series?.kind) {
            alternatives=if(series?.kind in setOf("Manga","Novel")) runCatching {
                graph.seriesToolsRepository.available().filter {it.kind==series?.kind && it.key!=seriesKey && it.source!=series?.source}.take(200)
            }.getOrDefault(emptyList()) else emptyList()
        }
        Column(Modifier.fillMaxSize().background(Ink.Canvas)) {
            ArkhiveSettingsHeader("Series options",{nav.pop();Unit})
            LazyColumn(Modifier.weight(1f).clip(RoundedCornerShape(topStart=18.dp,topEnd=18.dp)).background(Ink.Panel),contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                item {series?.let {Text(it.title,color=Ink.Text,style=MaterialTheme.typography.titleLarge);Text(it.source,color=Ink.Muted,fontSize=12.sp)};error?.let {Text(it,color=MaterialTheme.colorScheme.error)}}
                item {InkKicker("Reader profile");Text("Overrides apply to this story. Reader defaults and accessibility settings remain available.",color=Ink.Muted,fontSize=12.sp)}
                item {TextButton(onClick={layout=null}) {Text("Default layout"+if(layout==null) " ✓" else "")}
                    val choices=if(seriesKey.startsWith("manga:")) listOf("rtl" to "Right to left","ltr" to "Left to right","webtoon" to "Webtoon","vertical" to "Vertical pages") else if(series?.kind=="PDF") emptyList() else listOf("scroll" to "Scrolling text","pages" to "Page / screen steps")
                    choices.forEach {(key,label)->TextButton(onClick={layout=key}) {Text(label+if(layout==key) " ✓" else "")}}}
                item {Row {Text("Custom brightness",Modifier.weight(1f),color=Ink.Text);Switch(brightness!=null,{brightness=if(it) 60 else null})}
                    brightness?.let {value->Text("$value%",color=Ink.Muted);Slider(value.toFloat(),{brightness=it.toInt()},valueRange=1f..100f)}}
                item {Row {Text("Custom auto-scroll speed",Modifier.weight(1f),color=Ink.Text);Switch(speed!=null,{speed=if(it) 60 else null})}
                    speed?.let {value->Text("$value dp / second",color=Ink.Muted);Slider(value.toFloat(),{speed=it.toInt()},valueRange=16f..240f)}}
                item {TextButton(onClick={room=null}) {Text("Default Reading Room"+if(room==null) " ✓" else "")};ReadingRoom.entries.forEach {value->TextButton(onClick={room=value}) {Text(value.label+if(room==value) " ✓" else "")}}}
                item {Button(onClick={scope.launch {runCatching {
                    if(seriesKey.startsWith("manga:")) {val id=seriesKey.substringAfter(':').toLong();val manga=graph.mangaRepository.getMangaById(id)
                        val mode=when(layout) {"rtl"->ReadingMode.RIGHT_TO_LEFT;"ltr"->ReadingMode.LEFT_TO_RIGHT;"webtoon"->ReadingMode.WEBTOON;"vertical"->ReadingMode.VERTICAL;else->ReadingMode.DEFAULT}
                        graph.mangaRepository.update(MangaUpdate(id) {viewerFlags=(manga.viewerFlags and ReadingMode.MASK.toLong().inv()) or mode.flagValue.toLong()})}
                    graph.libraryToolsStore.setProfile(seriesKey,ReadingProfile(layout,brightness,speed,room))
                }.fold({nav.pop()},{error=it.message})}},modifier=Modifier.fillMaxWidth()) {Text("Save profile")}}
                if(series?.kind in setOf("Manga","Novel")) {
                    val kindName=if(series?.kind=="Manga") "manga" else "novel"
                    item {InkKicker("Compare another source edition");Text("Select a saved $kindName from a different source. This refreshes both chapter lists and compares chapter names and numbering only.",color=Ink.Muted,fontSize=12.sp)}
                    item {TextButton(onClick={searchAlternative="";chooseAlternative=true}) {Text("Choose alternate-source $kindName")}}
                    comparisonTarget?.let {target->
                        item {Text("${series?.title}  ↔  ${target.title}",color=Ink.Text);Text("${series?.source}  ↔  ${target.source}",color=Ink.Muted,fontSize=11.sp);Text(target.path,color=Ink.Muted,fontSize=11.sp)}
                        item {TextButton(onClick={scope.launch {
                            comparisonBusy=true;comparison=null;reviewed=emptySet();sameWorkConfirmed=false;migrationMessage=null
                            runCatching {
                                if(series?.kind=="Manga") graph.seriesToolsRepository.compareMangaEditions(seriesKey,target.key)
                                else graph.seriesToolsRepository.compareNovelEditions(seriesKey,target.key)
                            }
                                .fold({comparison=it},{error=error?.let {old->"$old\n"+it.message} ?: it.message})
                            comparisonBusy=false
                        }},enabled=!comparisonBusy) {Text(if(comparisonBusy) "Checking both chapter lists…" else "Refresh comparison")}}
                        if(comparisonBusy) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
                    }
                    comparison?.let {plan->
                        item {Text("${plan.verified.size} title-and-number matches · ${plan.ambiguous.size} ambiguous candidates · ${plan.unmatched} unmatched chapters",color=Ink.Text,fontSize=12.sp)}
                        item {Text(if(series?.kind=="Manga")
                            "Only reviewed completed chapters and bookmarks transfer. Page offsets never cross source editions."
                            else "Completed chapters and bookmarks can transfer. A mapped current chapter is selected without copying its partial offset because chapter text can differ.",color=Ink.Muted,fontSize=11.sp)}
                        if(plan.verified.isNotEmpty()) item {Text("Verified automatically (unique chapter number and matching title):\n"+plan.verified.take(20).joinToString("\n") {"${it.previous.name} → ${it.target.name}"}+
                            if(plan.verified.size>20) "\n…and ${plan.verified.size-20} more" else "",color=Ink.Muted,fontSize=11.sp)}
                        if(plan.ambiguous.isNotEmpty()) item {Column(Modifier.heightIn(max=300.dp).verticalScroll(rememberScrollState())) {
                            Text("Review number-only or conflicting candidates:",color=Ink.Text,fontSize=12.sp)
                            plan.ambiguous.take(100).forEach {match->Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                                Checkbox(match.key in reviewed,{checked->reviewed=if(checked) reviewed+match.key else reviewed-match.key})
                                Text("${match.previous.name} → ${match.target.name}",color=Ink.Muted,fontSize=11.sp)
                            }}
                            if(plan.ambiguous.size>100) Text("Only the first 100 ambiguous candidates are shown. Unreviewed candidates are skipped.",color=Ink.Muted,fontSize=11.sp)
                        }}
                        item {Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(sameWorkConfirmed,{sameWorkConfirmed=it})
                            Text("I confirmed these entries are the same work and editions.",color=Ink.Text,fontSize=12.sp)
                        }}
                        item {Button(onClick={scope.launch {
                            val targetKey=comparisonTarget?.key ?: return@launch
                            runCatching {
                                val count=if(series?.kind=="Manga") graph.seriesToolsRepository.migrateMangaProgress(seriesKey,targetKey,reviewed)
                                    else graph.seriesToolsRepository.migrateNovelProgress(seriesKey,targetKey,reviewed)
                                val oldAssignment=graph.libraryToolsStore.state.value.assignments[seriesKey]
                                val oldProfile=graph.libraryToolsStore.state.value.profiles[seriesKey]
                                graph.libraryToolsStore.update {stored->stored.copy(
                                    assignments=stored.assignments+oldAssignment?.let {mapOf(targetKey to it)}.orEmpty(),
                                    profiles=stored.profiles+oldProfile?.let {mapOf(targetKey to it)}.orEmpty(),
                                )}
                                if(graph.libraryVisibility.isHidden(seriesKey)) graph.libraryVisibility.setHidden(setOf(targetKey),true)
                                "Completed chapter state and bookmarks copied across $count reviewed chapter mappings. The source entry remains in Library."
                            }.fold({migrationMessage=it},{error=error?.let {old->"$old\n"+it.message} ?: it.message})
                        }},enabled=sameWorkConfirmed && !comparisonBusy && (plan.verified.size+reviewed.size)>0,modifier=Modifier.fillMaxWidth()) {Text("Copy verified reading progress")}}
                    }
                    migrationMessage?.let {item {Text(it,color=Ink.Muted,fontSize=12.sp)}}
                }
                if(series?.kind in setOf("Manga", "Novel")) {
                    item {InkKicker("Smart offline");Text("Opt in to the next 5 or 10 unread chapters from the metadata already saved for this series. The worker requires Wi-Fi, observes the shared storage limit, and waits while a reader is open.",color=Ink.Muted,fontSize=12.sp)}
                    item {Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Enable Smart offline",Modifier.weight(1f),color=Ink.Text)
                        Switch(smartEnabled,{smartEnabled=it})
                    }}
                    if(smartEnabled) item {Row {
                        listOf(5,10).forEach {count->TextButton(onClick={smartCount=count}) {Text("Next $count${if(smartCount==count) " ✓" else ""}")}}
                    };Text("Storage budget: ${state.smartStorageMiB} MiB",color=Ink.Muted,fontSize=11.sp)}
                    item {Button(onClick={runCatching {
                        graph.smartOfflineDispatcher.setRule(seriesKey,SmartOfflineRule(smartEnabled,smartCount))
                        smartMessage=if(smartEnabled) "Rule saved. Smart chapters run only on Wi-Fi and remain separate from the manual queue." else "Smart offline is off for this series. Existing Smart copies are kept."
                    }.onFailure {smartMessage=it.message}},modifier=Modifier.fillMaxWidth()) {Text("Save Smart offline rule")}}
                    smartMessage?.let {item {Text(it,color=Ink.Muted,fontSize=11.sp)}}
                }
                item {InkKicker("Observed source health")}
                item {state.observations[seriesKey]?.let {observed->
                    val stale=System.currentTimeMillis()-observed.checkedAt>24L*60*60*1000
                    Text(if(observed.available) "Available at last check" else "Check failed · ${observed.consecutiveFailures} observed attempts",color=Ink.Text)
                    Text("${if(stale) "Stale · " else ""}Checked ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(observed.checkedAt))}\n${observed.source}\n${observed.provenance}",color=Ink.Muted,fontSize=11.sp)
                    Text(observed.message,color=Ink.Muted,fontSize=12.sp)
                    if(observed.possibleGaps.isNotEmpty()) Text("Possible numbering gaps: ${observed.possibleGaps.joinToString()}. Sources may number chapters differently; these are not confirmed missing chapters.",color=Ink.Muted,fontSize=12.sp)
                } ?: Text("No checks yet. One failed request does not prove a source is broken.",color=Ink.Muted,fontSize=12.sp)}
                item {TextButton(onClick={scope.launch {busy=true;runCatching {graph.seriesToolsRepository.check(seriesKey)}.onFailure {error=it.message};busy=false}},enabled=!busy) {Text(if(busy) "Checking…" else "Check this series")};Text("One series per check, with a 60-second cooldown. No automatic source scanning.",color=Ink.Muted,fontSize=11.sp)}
                item {Spacer(Modifier.navigationBarsPadding())}
            }
        }
        if(chooseAlternative) AlertDialog(onDismissRequest={chooseAlternative=false},title={Text("Choose alternate edition")},text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState())) {
            val kindName=if(series?.kind=="Manga") "manga" else "novels"
            OutlinedTextField(searchAlternative,{searchAlternative=it},singleLine=true,placeholder={Text("Filter saved $kindName")},modifier=Modifier.fillMaxWidth())
            val selected=alternatives.filter {it.title.contains(searchAlternative,true) || it.source.contains(searchAlternative,true)}.take(50)
            selected.forEach {candidate->TextButton(onClick={
                chooseAlternative=false;comparisonTarget=candidate;comparison=null;reviewed=emptySet();sameWorkConfirmed=false
                scope.launch {
                    comparisonBusy=true
                    runCatching {
                        if(series?.kind=="Manga") graph.seriesToolsRepository.compareMangaEditions(seriesKey,candidate.key)
                        else graph.seriesToolsRepository.compareNovelEditions(seriesKey,candidate.key)
                    }
                        .fold({comparison=it},{error=error?.let {old->"$old\n"+it.message} ?: it.message})
                    comparisonBusy=false
                }
            },modifier=Modifier.fillMaxWidth()) {Column(Modifier.fillMaxWidth()) {Text(candidate.title,color=Ink.Text);Text(candidate.source,color=Ink.Muted,fontSize=11.sp)}}}
            if(selected.isEmpty()) Text("No saved $kindName from another source are available.",color=Ink.Muted)
            if(alternatives.size>50) Text("Showing up to 50 matches. Refine the filter to find a series.",color=Ink.Muted,fontSize=11.sp)
        }},confirmButton={TextButton(onClick={chooseAlternative=false}) {Text("Cancel")}})
    }
}
