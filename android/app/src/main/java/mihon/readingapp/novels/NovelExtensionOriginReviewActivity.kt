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
        private const val EXTRA_BUNDLE_SHA256 = "arkhive.extension_origin.bundle_sha256"
        private const val EXTRA_INSTALLED_AT = "arkhive.extension_origin.installed_at"
        private const val EXTRA_APPROVED_ORIGINS = "arkhive.extension_origin.approved_origins"
        private const val EXTRA_SMART_SERIES_KEY = "arkhive.extension_origin.smart_series_key"
        fun intent(
            context: Context,
            sourceId: String,
            origin: String,
            expectedBundleSha256: String? = null,
            smartSeriesKey: String? = null,
            expectedInstalledAt: Long? = null,
            expectedApprovedOrigins: Set<String>? = null,
        ): Intent {
            require(LN_READER_SOURCE_ID.matches(sourceId))
            val contextFields = listOf(expectedBundleSha256, smartSeriesKey, expectedInstalledAt, expectedApprovedOrigins)
            require(contextFields.all { it == null } || contextFields.all { it != null })
            require(expectedBundleSha256 == null || expectedBundleSha256.matches(Regex("[a-f0-9]{64}")))
            require(smartSeriesKey == null || smartSeriesKey.matches(Regex("novel:[a-f0-9]{64}")))
            require(expectedInstalledAt == null || expectedInstalledAt >= 0)
            require(expectedApprovedOrigins == null || expectedApprovedOrigins.isNotEmpty() && expectedApprovedOrigins.size <= 24)
            require(expectedApprovedOrigins == null || expectedApprovedOrigins.all {
                runCatching { normalizePublicHttpsOrigin(it) == it }.getOrDefault(false)
            })
            return Intent(context, NovelExtensionOriginReviewActivity::class.java)
                .putExtra(EXTRA_SOURCE_ID, sourceId)
                .putExtra(EXTRA_ORIGIN, normalizePublicHttpsOrigin(origin))
                .putExtra(EXTRA_BUNDLE_SHA256, expectedBundleSha256)
                .putExtra(EXTRA_INSTALLED_AT, expectedInstalledAt ?: -1L)
                .putExtra(EXTRA_SMART_SERIES_KEY, smartSeriesKey)
                .apply {
                    if (expectedApprovedOrigins != null) {
                        putStringArrayListExtra(EXTRA_APPROVED_ORIGINS, ArrayList(expectedApprovedOrigins))
                    }
                }
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
        val expectedBundleSha256 = intent.getStringExtra(EXTRA_BUNDLE_SHA256)
        val smartSeriesKey = intent.getStringExtra(EXTRA_SMART_SERIES_KEY)
        val expectedInstalledAt = intent.getLongExtra(EXTRA_INSTALLED_AT, -1L).takeIf { it >= 0 }
        val expectedApprovedOrigins = intent.getStringArrayListExtra(EXTRA_APPROVED_ORIGINS)?.toSet()
        val hasSmartContext = expectedBundleSha256 != null || smartSeriesKey != null || expectedInstalledAt != null || expectedApprovedOrigins != null
        if (sourceId == null || origin == null || (hasSmartContext &&
                (expectedBundleSha256?.matches(Regex("[a-f0-9]{64}")) != true ||
                    smartSeriesKey?.matches(Regex("novel:[a-f0-9]{64}")) != true ||
                    expectedInstalledAt == null || expectedApprovedOrigins.isNullOrEmpty() || expectedApprovedOrigins.size > 24 ||
                    expectedApprovedOrigins.any { runCatching { normalizePublicHttpsOrigin(it) != it }.getOrDefault(true) }))
        ) {
            finish()
            return
        }
        setContentView(ComposeView(this).apply {
            setContent {
                Navigator(
                    screen = NovelExtensionsScreen(
                        origin,
                        sourceId,
                        closeOnExit = true,
                        expectedBundleSha256 = expectedBundleSha256,
                        smartSeriesKey = smartSeriesKey,
                        expectedInstalledAt = expectedInstalledAt,
                        expectedApprovedOrigins = expectedApprovedOrigins,
                    ),
                    onBackPressed = { finish(); false },
                ) { navigator ->
                    DefaultNavigatorScreenTransition(navigator = navigator)
                }
            }
        })
    }
}
