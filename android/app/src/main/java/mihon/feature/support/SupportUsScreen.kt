package mihon.feature.support

import androidx.compose.runtime.Composable
import eu.kanade.presentation.util.Screen

/** Existing entry points now lead to Arkhive's own support and help. */
class SupportUsScreen : Screen() {
    @Composable override fun Content() = mihon.readingapp.settings.ArkhiveSupportContent()
}
