package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import mihon.readingapp.settings.ArkhiveSettingsDashboard

/** Task-oriented Arkhive settings; original preference screens and keys remain reachable. */
object SettingsMainScreen : Screen() {
    @Composable override fun Content() { Content(twoPane = false) }
    @Composable fun Content(twoPane: Boolean) {
        val navigator = LocalNavigator.currentOrThrow
        val back = LocalBackPress.current
        ArkhiveSettingsDashboard(back) { screen ->
            if (twoPane) navigator.replaceAll(screen) else navigator.push(screen)
        }
    }
}
