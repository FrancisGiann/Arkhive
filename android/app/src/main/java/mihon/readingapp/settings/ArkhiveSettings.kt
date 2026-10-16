package mihon.readingapp.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen as VoyagerScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import tachiyomi.presentation.core.util.collectAsState
import eu.kanade.presentation.more.settings.screen.*
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.more.settings.screen.about.OpenSourceLicensesScreen
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.stats.StatsScreen
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.automirroredrounded.ChromeReaderMode
import mihon.icons.materialsymbols.rounded.*
import eu.kanade.presentation.theme.TachiyomiTheme
import mihon.readingapp.design.*
import mihon.readingapp.updates.ArkhiveUpdateScreen

const val ARKHIVE_PROJECT = "https://github.com/FrancisGiann/Arkhive"
// Repository has_issues=true verified via authenticated metadata + public API, 2026-10-14.
const val ARKHIVE_SUPPORT = "$ARKHIVE_PROJECT/issues"

@Composable fun InkSettingsTheme(content: @Composable () -> Unit) = TachiyomiTheme(content = content)

@Composable fun ArkhiveSettingsHeader(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
) = ArkhiveSettingsHeaderContent(title, onBack, hero = false, actions = actions)

@Composable fun ArkhiveSettingsHeader(
    title: String,
    onBack: (() -> Unit)?,
    hero: Boolean,
    actions: @Composable RowScope.() -> Unit = {},
) = ArkhiveSettingsHeaderContent(title, onBack, hero, actions)

@Composable private fun ArkhiveSettingsHeaderContent(
    title: String,
    onBack: (() -> Unit)?,
    hero: Boolean,
    actions: @Composable RowScope.() -> Unit,
) {
    val foreground = if (hero) Ink.HeroText else Ink.Text
    val muted = if (hero) Ink.HeroMuted else Ink.Muted
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack, "Back", tint = foreground) }
            Column(Modifier.weight(1f).padding(start = if (onBack != null) 8.dp else 0.dp)) {
                InkKicker("Arkhive", color = muted)
                Text(title.uppercase(), style = Ink.Display.copy(fontSize = 26.sp, lineHeight = 30.sp), color = foreground)
            }
            actions()
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.width(44.dp).height(3.dp).background(Ink.Citrus))
    }
}

private data class SettingLink(val title: String, val subtitle: String, val icon: ImageVector, val screen: VoyagerScreen)
private val readingLinks = listOf(
    SettingLink("Manga & webtoon reader", "Page modes, long strips, gestures and display", MaterialSymbols.AutoMirroredRounded.ChromeReaderMode, SettingsReaderScreen),
    SettingLink("Library & collections", "Sorting, categories and chapter updates", MaterialSymbols.Rounded.CollectionsBookmark, SettingsLibraryScreen),
    SettingLink("Sources & extensions", "Languages, trusted repositories and installation", MaterialSymbols.Rounded.Explore, SettingsBrowseScreen),
)
private val offlineLinks = listOf(
    SettingLink("Downloads", "Wi-Fi rules, storage and saved chapters", MaterialSymbols.Rounded.Download, SettingsDownloadScreen),
    SettingLink("Data & backups", "Storage location, restore and local data", MaterialSymbols.Rounded.Storage, SettingsDataScreen),
    SettingLink("Reading trackers", "Connect the tracking services you choose", MaterialSymbols.Rounded.Sync, SettingsTrackingScreen),
)
private val appLinks = listOf(
    SettingLink("Appearance", "Theme, language and motion preferences", MaterialSymbols.Rounded.Palette, SettingsAppearanceScreen),
    SettingLink("Privacy & security", "App lock, screenshots and private reading", MaterialSymbols.Rounded.Security, SettingsSecurityScreen),
    SettingLink("Advanced tools", "Network, maintenance and extension tools", MaterialSymbols.Rounded.Code, SettingsAdvancedScreen),
)

@Composable fun ArkhiveSettingsDashboard(onBack: (() -> Unit)?, open: (VoyagerScreen) -> Unit) = InkSettingsTheme {
    val graph = LocalContext.current.appGraph
    val private by graph.basePreferences.incognitoMode.collectAsState()
    val offline by graph.basePreferences.downloadedOnly.collectAsState()
    val reduced by graph.uiPreferences.arkhiveReducedMotion.collectAsState()
    var help by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Ink.Canvas)) {
        ArkhiveSettingsHeader("Settings", onBack) {
            IconButton(onClick = { open(SettingsSearchScreen()) }) { Icon(MaterialSymbols.Rounded.Search, "Search settings", tint = Ink.Text) }
        }
        LazyColumn(Modifier.weight(1f).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(Ink.Panel),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.HeroSurface).padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Image(painterResource(R.drawable.arkhive_mark_bitmap), null, Modifier.size(64.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("MAKE IT YOURS", style = Ink.Display.copy(fontSize = 22.sp, lineHeight = 25.sp), color = Ink.HeroText)
                        Text("Your stories. Your reading rhythm.", color = Ink.HeroMuted, fontSize = 12.sp)
                        TextButton(onClick = { open(AboutScreen) }, contentPadding = PaddingValues()) { Text(AboutScreen.getVersionName(false), color = Ink.HeroMuted, fontSize = 11.sp) }
                    }
                }
            }
            item { InkKicker("Quick controls", Modifier.padding(top = 8.dp)) }
            item { SettingToggle("Private reading", "Skip recording new reading history and progress", private,
                { graph.basePreferences.incognitoMode.set(it) }) }
            item { SettingToggle("Offline manga only", "Show downloaded manga in the source library", offline,
                { graph.basePreferences.downloadedOnly.set(it) }) }
            item { SettingToggle("Reduce motion", "Calmer transitions and no continuous auto-scroll", reduced,
                { graph.uiPreferences.arkhiveReducedMotion.set(it) }) }
            item { InkKicker("Reading", Modifier.padding(top = 16.dp)) }
            items(readingLinks) { SettingsLink(it.title, it.subtitle, it.icon) { open(it.screen) } }
            item { SettingsLink("Book reader", "Text size, paper, contents and auto read", MaterialSymbols.AutoMirroredRounded.ChromeReaderMode) { help = "Open a novel or imported book, then tap Aa. Text size, line height, page margins, paper and Auto read are saved from the actual reader." } }
            item { SettingsLink("Library tools", "Shared shelves, Reading Room and local stats", MaterialSymbols.Rounded.CollectionsBookmark) { open(mihon.readingapp.library.LibraryToolsScreen()) } }
            item { SettingsLink("Chapter inbox", "Observed chapter updates from manga and novels", MaterialSymbols.Rounded.Info) { open(mihon.readingapp.library.UnifiedUpdatesScreen()) } }
            item { SettingsLink("Reading history", "Return to chapters you have opened", MaterialSymbols.Rounded.Info) { open(eu.kanade.tachiyomi.ui.history.HistoryTab) } }
            item { InkKicker("Library & offline", Modifier.padding(top = 16.dp)) }
            items(offlineLinks) { SettingsLink(it.title, it.subtitle, it.icon) { open(it.screen) } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsActionTile("Queue", MaterialSymbols.Rounded.Download, Modifier.weight(1f)) { open(DownloadQueueScreen) }
                SettingsActionTile("Collections", MaterialSymbols.Rounded.CollectionsBookmark, Modifier.weight(1f)) { open(CategoryScreen()) }
                SettingsActionTile("Stats", MaterialSymbols.Rounded.QueryStats, Modifier.weight(1f)) { open(StatsScreen()) }
            } }
            item { InkKicker("App", Modifier.padding(top = 16.dp)) }
            items(appLinks) { SettingsLink(it.title, it.subtitle, it.icon) { open(it.screen) } }
            item { SettingsLink("Check for updates", "Review and verify a newer Arkhive release", MaterialSymbols.Rounded.Download) { open(ArkhiveUpdateScreen()) } }
            item { SettingsLink("Support Arkhive", "Help, releases and report an issue", MaterialSymbols.Rounded.Info) { open(ArkhiveSupportScreen()) } }
            item { SettingsLink("About & acknowledgments", "Arkhive identity, upstream credits and licenses", MaterialSymbols.Rounded.Info) { open(AboutScreen) } }
            item { Spacer(Modifier.navigationBarsPadding().height(8.dp)) }
        }
    }
    help?.let { AlertDialog(onDismissRequest = { help = null }, title = { Text("Book reader") }, text = { Text(it) },
        confirmButton = { TextButton(onClick = { help = null }) { Text("Done") } }) }
}

@Composable private fun SettingsActionTile(title: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 88.dp).clip(RoundedCornerShape(12.dp)).background(Ink.Raised)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .inkPress(onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Ink.Link, modifier = Modifier.size(20.dp))
        }
        Text(title, color = Ink.Text, fontSize = 11.sp, lineHeight = 13.sp,
            minLines = 2, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable private fun SettingToggle(title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.Raised).inkPress { change(!checked) }
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Ink.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Ink.Muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Switch(checked, change, Modifier.padding(start = 12.dp))
    }
}

@Composable private fun SettingsLink(title: String, subtitle: String, icon: ImageVector, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).background(Ink.Raised)
        .inkPress(click).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Ink.Violet.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Ink.Link, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Ink.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Ink.Muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Text("›", color = Ink.Muted, fontSize = 24.sp)
    }
}

class ArkhiveSupportScreen : Screen() {
    @Composable override fun Content() = ArkhiveSupportContent()
}

@Composable fun ArkhiveSupportContent() = InkSettingsTheme {
    val nav = LocalNavigator.currentOrThrow
    val uri = LocalUriHandler.current
    var guide by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Ink.Canvas)) {
        ArkhiveSettingsHeader("Support", LocalBackPress.current ?: { nav.pop(); Unit })
        LazyColumn(Modifier.weight(1f).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(Ink.Panel),
            contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Image(painterResource(R.drawable.arkhive_mark_bitmap), "Arkhive", Modifier.size(88.dp))
                Spacer(Modifier.height(14.dp))
                Text("KEEP YOUR STORY MOVING", style = Ink.Display.copy(fontSize = 28.sp, lineHeight = 32.sp), color = Ink.Text)
                Text("Help and updates for Arkhive.", color = Ink.Muted, modifier = Modifier.padding(top = 8.dp))
            }
            item { SettingsLink("Report an issue", "Open Arkhive's GitHub issues", MaterialSymbols.Rounded.Code) { uri.openUri(ARKHIVE_SUPPORT) } }
            item { SettingsLink("Releases & feature status", "Downloads and honest preview limitations", MaterialSymbols.Rounded.Download) { uri.openUri("$ARKHIVE_PROJECT/releases") } }
            item { InkKicker("Reader help", Modifier.padding(top = 12.dp)) }
            item { SettingsLink("Long manhwa pages", "Readable strips, page steps and continuous mode", MaterialSymbols.AutoMirroredRounded.ChromeReaderMode) {
                guide = "Long static pages use readable width in paged mode when Smart long-page fit is enabled. Tap or use the Next control to step through the strip before the next image. For seamless scrolling between images, choose Webtoon in the reader's Reading mode. Ordinary manga keeps its selected page mode." } }
            item { SettingsLink("Imports & saved chapters", "Reading offline from your library", MaterialSymbols.Rounded.Storage) {
                guide = "Import EPUB, PDF or text through Library → More → Import EPUB / PDF / text. Manga chapter downloads and batches are available from each title's download menu. Keep files until downloads finish, then open them from Library. Online novel support is documented in the current release status." } }
            item { SettingsLink("Auto read", "Adjust continuous speed or timed steps", MaterialSymbols.AutoMirroredRounded.ChromeReaderMode) {
                guide = "Open Auto read in the reader controls. Continuous mode scrolls supported text or webtoon readers at your chosen speed. Timed steps move discrete reading steps. Touch, backgrounding and chapter boundaries pause playback; use Start to resume." } }
            item { InkKicker("Acknowledgments", Modifier.padding(top = 12.dp)) }
            item { SettingsLink("Upstream credits & licenses", "Mihon and the libraries behind Arkhive", MaterialSymbols.Rounded.Info) { nav.push(AboutScreen) } }
            item { Text("Arkhive support is handled through the Arkhive project. No chat community or donation service is configured.", color = Ink.Muted, fontSize = 11.sp, lineHeight = 16.sp) }
            item { Spacer(Modifier.navigationBarsPadding().height(8.dp)) }
        }
    }
    guide?.let { AlertDialog(onDismissRequest = { guide = null }, title = { Text("Reader help") }, text = { Text(it) },
        confirmButton = { TextButton(onClick = { guide = null }) { Text("Done") } }) }
}

@Composable fun ArkhiveAboutContent() = InkSettingsTheme {
    val nav = LocalNavigator.currentOrThrow
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().background(Ink.Canvas)) {
        ArkhiveSettingsHeader("About Arkhive", LocalBackPress.current ?: { nav.pop(); Unit })
        LazyColumn(Modifier.weight(1f).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(Ink.Panel),
            contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Image(painterResource(R.drawable.arkhive_mark_bitmap), "Arkhive", Modifier.size(104.dp))
                Text("ARKHIVE", style = Ink.Display, color = Ink.Text, modifier = Modifier.padding(top = 18.dp))
                Text("Manga & Novel Reader", color = Ink.Muted)
                Text(AboutScreen.getVersionName(true), color = Ink.Link, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            }
            item { SettingsLink("Arkhive project", "Releases, documentation and support", MaterialSymbols.Rounded.Public) { uri.openUri(ARKHIVE_PROJECT) } }
            item { InkKicker("Privacy in this preview", Modifier.padding(top = 8.dp)) }
            item { Text("Reading files, progress and bookmarks stay in this app's private storage. Browsing and online reading contact the selected content providers. Installed external extensions use their own sources. This build includes no telemetry or automatic application updater.", color = Ink.Muted, fontSize = 12.sp, lineHeight = 18.sp) }
            item { InkKicker("Upstream acknowledgments", Modifier.padding(top = 8.dp)) }
            item { Text("Arkhive is based on Mihon and Tachiyomi. Mihon's contributors provide the manga reader, library, native extension engine and many preferences. Their copyright and Apache-2.0 license are retained. Arkhive is an independent modification; its support is handled separately.", color = Ink.Text, fontSize = 13.sp, lineHeight = 20.sp) }
            item { SettingsLink("Mihon · upstream source", "Copyright, license and contributor history", MaterialSymbols.Rounded.Code) { uri.openUri("https://github.com/mihonapp/mihon") } }
            item { SettingsLink("Open source licenses", "Included software and font notices", MaterialSymbols.Rounded.Info) { nav.push(OpenSourceLicensesScreen()) } }
            item { Text("Readium provides local EPUB navigation. Noto fonts are distributed under the SIL Open Font License. Original Arkhive fixture artwork and the Arkhive logo are separate from upstream branding.", color = Ink.Muted, fontSize = 12.sp, lineHeight = 18.sp) }
            item { Spacer(Modifier.navigationBarsPadding().height(8.dp)) }
        }
    }
}
