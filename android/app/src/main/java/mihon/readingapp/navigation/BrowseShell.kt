package mihon.readingapp.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsViewModel
import eu.kanade.tachiyomi.ui.browse.extension.extensionsTab
import eu.kanade.tachiyomi.ui.browse.migration.sources.migrateSourceTab
import eu.kanade.tachiyomi.ui.browse.source.sourcesTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Settings
import mihon.readingapp.design.*
import mihon.readingapp.discovery.DiscoveryContent
import mihon.readingapp.novels.LnReaderExtensionManagement

@Composable fun Screen.BrowseShell(mode: Int, onMode: (Int) -> Unit, extensions: ExtensionsViewModel) {
    var novelSources by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var novelExtensions by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val tabNavigator = LocalTabNavigator.current
    val host = remember { SnackbarHostState() }
    val native = when(mode) { 1 -> if(novelSources) null else sourcesTab(); 2 -> if(novelExtensions) null else extensionsTab(extensions); 3 -> migrateSourceTab(); else -> null }
    Column(Modifier.fillMaxSize().background(Ink.Canvas).statusBarsPadding()) {
        Column(Modifier.padding(horizontal=24.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            InkKicker("Find your next story")
            Row(verticalAlignment=Alignment.CenterVertically) {
                InkHeading(if(mode==0) "Discover" else if(mode==1) "Your sources" else if(mode==2) "Extensions" else "Migrate",Modifier.weight(1f))
                if(native!=null) AppBarActions(native.actions)
                InkIcon(MaterialSymbols.Rounded.Settings,"Settings") { tabNavigator.current=MoreTab }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("Discover","Sources","Extensions","Migrate").forEachIndexed { index,title -> InkPill(title,mode==index){onMode(index)} }
            }
        }
        if(mode==1 || mode==2) Row(Modifier.padding(horizontal=24.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            val selectedNovelTab = if (mode == 1) novelSources else novelExtensions
            InkPill("Manga",!selectedNovelTab) {
                if (mode == 1) novelSources = false else novelExtensions = false
            }
            InkPill("Novels",selectedNovelTab) {
                if (mode == 1) novelSources = true
                else novelExtensions = true
            }
        }
        Box(Modifier.weight(1f)) {
            if(mode==0) DiscoveryContent(active=true)
            else if(mode==1 && novelSources) mihon.readingapp.novels.NovelSourceList()
            else if(mode==2 && novelExtensions) LnReaderExtensionManagement()
            else native?.content?.invoke(PaddingValues(),host)
        }
    }
}
