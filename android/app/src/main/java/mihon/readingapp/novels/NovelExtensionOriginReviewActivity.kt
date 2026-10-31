package mihon.readingapp.novels

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import mihon.readingapp.novels.lnreader.normalizePublicHttpsOrigin

internal val LN_READER_SOURCE_ID = Regex("lnreader\\.[a-f0-9]{32}")

/** Internal handoff for an observed extension origin; only the existing explicit trust review can approve it. */
class NovelExtensionOriginReviewActivity : BaseActivity() {
    companion object {
        private const val EXTRA_SOURCE_ID = "arkhive.extension_origin.source_id"
        private const val EXTRA_ORIGIN = "arkhive.extension_origin.origin"
        fun intent(context: Context, sourceId: String, origin: String): Intent {
            require(LN_READER_SOURCE_ID.matches(sourceId))
            return Intent(context, NovelExtensionOriginReviewActivity::class.java)
                .putExtra(EXTRA_SOURCE_ID, sourceId)
                .putExtra(EXTRA_ORIGIN, normalizePublicHttpsOrigin(origin))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this)
        enableEdgeToEdge()
        super.onCreate(null)
        val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID)?.takeIf(LN_READER_SOURCE_ID::matches)
        val origin = intent.getStringExtra(EXTRA_ORIGIN)?.let { value ->
            runCatching { normalizePublicHttpsOrigin(value) }.getOrNull()
        }
        if (sourceId == null || origin == null) {
            finish()
            return
        }
        setContentView(ComposeView(this).apply {
            setContent {
                Navigator(
                    screen = NovelExtensionsScreen(origin, sourceId, closeOnExit = true),
                    onBackPressed = { finish(); false },
                ) { navigator ->
                    DefaultNavigatorScreenTransition(navigator = navigator)
                }
            }
        })
    }
}
