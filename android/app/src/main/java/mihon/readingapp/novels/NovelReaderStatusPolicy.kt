package mihon.readingapp.novels

internal enum class NovelReaderStatus { LOADING, ERROR, CONTENT }

internal fun novelReaderStatus(ready: Boolean, error: Boolean): NovelReaderStatus = when {
    ready -> NovelReaderStatus.CONTENT
    error -> NovelReaderStatus.ERROR
    else -> NovelReaderStatus.LOADING
}
