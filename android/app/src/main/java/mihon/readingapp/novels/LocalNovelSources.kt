package mihon.readingapp.novels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import mihon.app.di.appGraph

/** Optional normal Compose dependency seam; production screens fall back to the app graph. */
internal val LocalNovelSources = staticCompositionLocalOf<NovelSources?> { null }

@Composable
internal fun currentNovelSources(): NovelSources =
    LocalNovelSources.current ?: LocalContext.current.appGraph.novelRepository.sources
