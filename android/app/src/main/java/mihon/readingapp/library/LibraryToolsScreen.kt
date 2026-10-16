package mihon.readingapp.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.launch
import eu.kanade.presentation.util.Screen
import mihon.app.di.appGraph
import mihon.readingapp.backup.UnifiedBackupPreview
import mihon.readingapp.backup.UnifiedBackupRestoreMode
import mihon.readingapp.design.Ink
import mihon.readingapp.design.InkKicker
import mihon.readingapp.design.inkPress
import mihon.readingapp.settings.ArkhiveSettingsHeader
import mihon.readingapp.settings.InkSettingsTheme
import mihon.icons.materialsymbols.rounded.QueryStats

class LibraryToolsScreen : Screen() {
    @Composable override fun Content() = InkSettingsTheme {
        val graph=LocalContext.current.appGraph
        val nav=LocalNavigator.currentOrThrow
        val scope=rememberCoroutineScope()
        val state by graph.libraryToolsStore.state.collectAsStateWithLifecycle()
        var newShelf by remember {mutableStateOf("")}
        var deleteShelf by remember {mutableStateOf<String?>(null)}
        var budget by remember(state.smartStorageMiB) {mutableFloatStateOf(state.smartStorageMiB.toFloat())}
        var backupFeedback by remember { mutableStateOf<String?>(null) }
        var backupError by remember { mutableStateOf<String?>(null) }
        var pendingRestore by remember { mutableStateOf<Pair<Uri, UnifiedBackupPreview>?>(null) }
        val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) scope.launch {
                runCatching { graph.unifiedLocalBackup.create(uri) }
                    .onSuccess { backupFeedback = "Backup saved. It contains reading metadata, progress, bookmarks and shared library settings. Downloaded chapters and imported book files stay on this device." }
                    .onFailure { backupError = it.message ?: "The backup could not be created." }
            }
        }
        val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                runCatching { graph.unifiedLocalBackup.preview(uri) }
                    .onSuccess { pendingRestore = uri to it; backupError = null }
                    .onFailure { backupError = it.message ?: "This backup could not be opened." }
            }
        }
        Column(Modifier.fillMaxSize().background(Ink.Canvas)) {
            ArkhiveSettingsHeader("Library tools",{nav.pop();Unit})
            LazyColumn(Modifier.weight(1f).clip(RoundedCornerShape(topStart=18.dp,topEnd=18.dp)).background(Ink.Panel),
                contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                item {InkKicker("Your shelves");Text("Long-press stories in Library, then choose Organize. Shelves work across manga, novels and imported books.",color=Ink.Muted,fontSize=12.sp)}
                item {Row(Modifier.fillMaxWidth()) {OutlinedTextField(newShelf,{newShelf=it.take(80)},placeholder={Text("Shelf name")},singleLine=true,modifier=Modifier.weight(1f));TextButton(onClick={graph.libraryToolsStore.addShelf(newShelf);newShelf=""},enabled=newShelf.isNotBlank()) {Text("Add")}}}
                items(state.shelves,key={it}) {shelf->Row(Modifier.fillMaxWidth()) {Text(shelf,Modifier.weight(1f),color=Ink.Text);TextButton(onClick={deleteShelf=shelf}) {Text("Delete shelf")}}}
                item {Text("Reading, On Hold and Finished are available when you organize a story.",color=Ink.Muted,fontSize=12.sp)}
                item {
                    InkKicker("Local backup")
                    Text("Save or restore library metadata, progress, bookmarks, shelves, profiles and hidden status. No accounts, source credentials, downloads or imported files are included. Restored books without a local file remain listed; select the original again through Import to restore the private copy.",color=Ink.Muted,fontSize=12.sp)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={createBackup.launch("Arkhive-library-backup.json")}) {Text("Create backup")}
                        OutlinedButton(onClick={openBackup.launch(arrayOf("application/json","application/vnd.arkhive.library+json"))}) {Text("Choose backup")}
                    }
                    backupFeedback?.let { Text(it,color=Ink.Muted,fontSize=12.sp) }
                    backupError?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp) }
                }
                item {InkKicker("Reading Room");Text("A static palette for text and the space around manga. Artwork keeps its original colors.",color=Ink.Muted,fontSize=12.sp)}
                item {ReadingRoomOption("Use reader defaults",state.room==null,0xFFF7F2E8.toInt(),0xFF28242A.toInt()) {graph.libraryToolsStore.update {it.copy(room=null)}}}
                items(ReadingRoom.entries) {room->ReadingRoomOption(room.label,state.room==room,room.paper,room.text) {graph.libraryToolsStore.update {it.copy(room=room)}}}
                item {InkKicker("Local reading stats");Row(Modifier.fillMaxWidth()) {Column(Modifier.weight(1f)) {Text("Record reading time",color=Ink.Text);Text("Only foreground reading. Stored on this device; private reading is excluded.",color=Ink.Muted,fontSize=12.sp)};Switch(state.statsEnabled,{enabled->graph.libraryToolsStore.update {it.copy(statsEnabled=enabled)}})}}
                item {SettingsActionLink("View local stats","See foreground time and completed chapters on this device",mihon.icons.materialsymbols.MaterialSymbols.Rounded.QueryStats) {nav.push(LocalReadingStatsScreen())}}
                item {InkKicker("Offline storage budget");Text("Smart offline is enabled separately for a series. It saves the next 5 or 10 unread chapters on Wi-Fi.",color=Ink.Muted,fontSize=12.sp)}
                item {Text("Smart storage limit: ${budget.toInt()} MiB",color=Ink.Text);Slider(budget,{budget=it},valueRange=32f..2048f,steps=62,onValueChangeFinished={graph.libraryToolsStore.update {it.copy(smartStorageMiB=budget.toInt())}})}
                item {Row {Column(Modifier.weight(1f)) {Text("Allow cleanup of read Smart copies",color=Ink.Text);Text("Off by default. In Downloads, cleanup is a separate action and only recorded Smart-owned, completed, unbookmarked copies are eligible.",color=Ink.Muted,fontSize=12.sp)};Switch(state.cleanupReadSmartCopies,{enabled->graph.libraryToolsStore.update {it.copy(cleanupReadSmartCopies=enabled)}})}}
                item {Spacer(Modifier.navigationBarsPadding())}
            }
        }
        deleteShelf?.let {name->AlertDialog(onDismissRequest={deleteShelf=null},title={Text("Delete shelf?")},text={Text("Delete “$name” and its assignments. Stories and reading progress stay in Library.")},
            confirmButton={TextButton(onClick={graph.libraryToolsStore.removeShelf(name);deleteShelf=null}) {Text("Delete shelf")}},dismissButton={TextButton(onClick={deleteShelf=null}) {Text("Cancel")}})}
        pendingRestore?.let { (uri, preview) ->
            AlertDialog(
                onDismissRequest={pendingRestore=null},
                title={Text("Review local backup")},
                text={Text("${preview.manga} manga · ${preview.novels} novels · ${preview.books} imported books · ${preview.shelves} shelves · ${preview.hiddenSeries} hidden series.\n\nMerge adds or updates matching reading data. Replace makes the reviewed library membership and shared settings match this file. Missing imported files and downloaded chapters are never restored or deleted; background downloads and cleanup remain off unless already configured on this device.")},
                confirmButton={Column(horizontalAlignment=androidx.compose.ui.Alignment.End) {
                    TextButton(onClick={
                        pendingRestore=null
                        scope.launch { runCatching { graph.unifiedLocalBackup.restore(uri,preview.token,UnifiedBackupRestoreMode.MERGE) }
                            .onSuccess { backupFeedback="Backup merged. ${it.unmatchedSettings} series settings could not be matched on this device." }
                            .onFailure { backupError=it.message ?: "The backup could not be restored." } }
                    }) {Text("Merge data")}
                    TextButton(onClick={
                        pendingRestore=null
                        scope.launch { runCatching { graph.unifiedLocalBackup.restore(uri,preview.token,UnifiedBackupRestoreMode.REPLACE_MEMBERSHIP) }
                            .onSuccess { backupFeedback="Reviewed library membership replaced. Local files remain on this device; missing files can be selected again through Import." }
                            .onFailure { backupError=it.message ?: "The backup could not be restored." } }
                    }) {Text("Replace memberships")}
                    TextButton(onClick={pendingRestore=null}) {Text("Cancel")}
                }},
            )
        }
    }
}

@Composable private fun ReadingRoomOption(label:String,selected:Boolean,paper:Int,text:Int,onClick:()->Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min=56.dp).clip(RoundedCornerShape(12.dp))
            .background(if(selected) Ink.Violet.copy(alpha=.12f) else Ink.Raised)
            .selectable(selected=selected,role=Role.RadioButton,onClick=onClick)
            .padding(horizontal=14.dp,vertical=9.dp),
        verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(width=40.dp,height=30.dp).clip(RoundedCornerShape(7.dp)).background(Color(paper))) {
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.38f).background(Color(text)))
        }
        Text(label,Modifier.weight(1f),color=Ink.Text,fontSize=14.sp,maxLines=2)
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable private fun SettingsActionLink(title:String,subtitle:String,icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clip(RoundedCornerShape(12.dp)).background(Ink.Raised)
        .inkPress(onClick).padding(14.dp),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Ink.Violet.copy(alpha=.12f)),contentAlignment=Alignment.Center) {
            Icon(icon,null,tint=Ink.Link,modifier=Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,color=Ink.Text,fontSize=14.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text(subtitle,color=Ink.Muted,fontSize=11.sp,lineHeight=15.sp)
        }
        Text("›",color=Ink.Muted,fontSize=24.sp)
    }
}

@Composable fun OrganizeSeriesDialog(keys:Set<String>,store:LibraryToolsStore,onDone:()->Unit) {
    val state by store.state.collectAsStateWithLifecycle()
    val initial=keys.singleOrNull()?.let {state.assignments[it]} ?: ShelfAssignment()
    var status by remember {mutableStateOf(initial.status)}
    var shelves by remember {mutableStateOf(initial.shelves)}
    var name by remember {mutableStateOf("")}
    AlertDialog(onDismissRequest=onDone,title={Text("Organize stories")},text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        TextButton(onClick={status=null}) {Text("No reading status"+if(status==null) " ✓" else "")}
        ShelfStatus.entries.forEach {value->TextButton(onClick={status=value}) {Text(value.label+if(status==value) " ✓" else "")}}
        state.shelves.forEach {shelf->Row {Checkbox(shelf in shelves,{checked->shelves=if(checked) shelves+shelf else shelves-shelf});Text(shelf,Modifier.padding(top=12.dp))}}
        OutlinedTextField(name,{name=it.take(80)},placeholder={Text("New shelf")},singleLine=true)
        TextButton(onClick={val clean=name.trim();store.addShelf(clean);shelves=shelves+clean;name=""},enabled=name.isNotBlank()) {Text("Add shelf")}
    }},confirmButton={TextButton(onClick={store.assign(keys,ShelfAssignment(status,shelves));onDone()}) {Text("Save")}},dismissButton={TextButton(onClick=onDone) {Text("Cancel")}})
}

class LocalReadingStatsScreen : Screen() {
    @Composable override fun Content() = InkSettingsTheme {
        val graph=LocalContext.current.appGraph;val nav=LocalNavigator.currentOrThrow
        val state by graph.libraryToolsStore.state.collectAsStateWithLifecycle()
        val hidden by graph.libraryVisibility.hiddenSeries.changes().collectAsStateWithLifecycle(graph.libraryVisibility.hiddenSeries.get())
        val visible=state.stats.filterKeys {it !in hidden}.values
        Column(Modifier.fillMaxSize().background(Ink.Canvas)) {
            ArkhiveSettingsHeader("Local stats",{nav.pop();Unit})
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ink.Panel).padding(22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("${visible.sumOf {it.foregroundMillis}/60_000} minutes recorded",color=Ink.Text)
                Text("${visible.sumOf {it.completedChapters.size}} chapters completed while stats were enabled",color=Ink.Text)
                Text(if(state.statsEnabled) "Recording is enabled. Time is written when reading pauses, never for a background window." else "Recording is off. Past local stats are kept.",color=Ink.Muted)
                TextButton(onClick={nav.push(eu.kanade.tachiyomi.ui.stats.StatsScreen())}) {Text("Manga library statistics")}
            }
        }
    }
}
