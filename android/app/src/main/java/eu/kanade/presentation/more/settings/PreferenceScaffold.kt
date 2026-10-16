package eu.kanade.presentation.more.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import mihon.readingapp.design.Ink
import mihon.readingapp.settings.ArkhiveSettingsHeader
import mihon.readingapp.settings.InkSettingsTheme
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun PreferenceScaffold(titleRes: StringResource, actions: @Composable RowScope.() -> Unit = {},
    onBackPressed: (() -> Unit)? = null, itemsProvider: @Composable () -> List<Preference>) = InkSettingsTheme {
    val navigator = LocalNavigator.currentOrThrow
    Column(Modifier.fillMaxSize().background(Ink.Canvas)) {
        ArkhiveSettingsHeader(stringResource(titleRes), onBackPressed ?: { navigator.pop(); Unit }, actions)
        PreferenceScreen(items = itemsProvider(),
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(Ink.Panel),
            contentPadding = PaddingValues(start = 10.dp, top = 12.dp, end = 10.dp, bottom = 32.dp))
    }
}
