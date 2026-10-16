package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    /** Transport completion is not image readiness; this reports the reader's actual decode result. */
    enum class RenderState { PENDING, READY, ERROR }

    private val mutableRenderState = MutableStateFlow(RenderState.PENDING)
    val renderStateFlow = mutableRenderState.asStateFlow()
    var renderState: RenderState
        get() = mutableRenderState.value
        set(value) { mutableRenderState.value = value }

    // ViewModel-owned position survives orientation recreation without changing source page IDs.
    var longPagePosition: eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.LongPagePosition? = null

    open lateinit var chapter: ReaderChapter
}
