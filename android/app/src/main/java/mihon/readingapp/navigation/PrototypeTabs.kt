package mihon.readingapp.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import mihon.app.di.appGraph
import mihon.readingapp.design.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.readingapp.design.ElectricInkToolbarTitle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/** An overview of the real queue; queue operations stay in the upstream DownloadQueueScreen. */
data object DownloadsTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 5u,
            title = stringResource(MR.strings.pref_category_downloads),
            icon = rememberVectorPainter(MaterialSymbols.Rounded.Download),
        )

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(DownloadQueueScreen)
    }

    @Composable
    override fun Content() {
        mihon.readingapp.library.UnifiedDownloadsContent()
    }
}

/** Exact-edition public provider lookup; unknown prices and unavailable stores remain explicit. */
data object BuyTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 6u,
            title = "Buy",
            icon = rememberVectorPainter(MaterialSymbols.Rounded.LocalLibrary),
        )

    @Composable
    override fun Content() {
        mihon.readingapp.discovery.RetailLookupContent()
    }

}

/** Keep the original MoreTab identity, preferences and navigation, with access to legacy reading tools. */
@Composable
fun PrototypeMoreContent() { MoreTab.Content() }

@Composable
private fun PrototypePage(
    title: String,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = { AppBar(titleContent = { ElectricInkToolbarTitle(title) }) },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun PrototypePanel(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}
