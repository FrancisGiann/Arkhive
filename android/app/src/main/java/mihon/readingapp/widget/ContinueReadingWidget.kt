package mihon.readingapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.presentation.theme.getThemeColorScheme
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Path
import android.view.View
import androidx.core.graphics.drawable.toBitmap
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import mihon.app.di.appGraph
import mihon.readingapp.books.BookReaderActivity
import mihon.readingapp.novels.NovelReaderActivity
import java.util.Locale

internal data class ContinueStory(val key: String, val title: String, val author: String,
    val kind: String, val cover: Any?, val progress: Double, val status: String, val lastRead: Long)

/** Launcher content is derived from visible library membership; no private snapshot on disk. */
open class ContinueReadingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { refresh(context) } finally { pending.finish() }
        }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }
    companion object {
        const val ACTION = "arkhive.CONTINUE_READING"
        const val KEY = "continue_story"

        fun observe(context: Context, scope: CoroutineScope) {
            val g = context.appGraph
            combine(
                g.mangaRepository.getLibraryMangaAsFlow(), g.localBookRepository.observeBooks(),
                g.novelRepository.state, g.libraryVisibility.hiddenSeries.changes(),
                g.libraryShelfMembership.removedBookIds,
            ) { _, _, _, _, _ -> Unit }
                .mergeWith(g.securityPreferences.useAuthenticator.changes().map { Unit })
                .mergeWith(g.uiPreferences.themeMode.changes().map { Unit })
                .mergeWith(g.uiPreferences.appTheme.changes().map { Unit })
                .debounce(150).onEach { runCatching { refresh(context) } }.launchIn(scope)
        }

        private fun <T> Flow<T>.mergeWith(other: Flow<T>) = merge(this, other)

        private suspend fun stories(context: Context): List<ContinueStory> {
            val g = context.appGraph
            val hidden = g.libraryVisibility.hiddenSeries.get()
            val removed = g.libraryShelfMembership.removedBookIds.value
            val manga = g.mangaRepository.getLibraryMangaAsFlow().first().map { m ->
                ContinueStory("manga:${m.id}", m.manga.title, m.manga.author.orEmpty(), mihon.readingapp.discovery.MangaContentClassification.fromGenres(m.manga.genre).label.uppercase(Locale.getDefault()), m.manga,
                    if (m.totalChapters > 0) m.readCount.toDouble() / m.totalChapters else 0.0,
                    "${m.readCount} / ${m.totalChapters} chapters read", m.lastRead?.toEpochMilliseconds() ?: 0)
            }
            val books = g.localBookRepository.observeBooks().first().filterNot { it.id in removed }.map { b ->
                ContinueStory("book:${b.id}", b.title, b.author.orEmpty(), b.format.name,
                    g.localBookImporter.coverFile(b), b.progression, "${(b.progression * 100).toInt()}% · Saved offline",
                    if (b.locator != null) b.updatedAt else 0)
            }
            val novels = g.novelRepository.state.value.novels.map { n ->
                ContinueStory("novel:${n.id}", n.detail.name, n.detail.author.orEmpty(), "NOVEL", n.detail.cover,
                    n.progression, "${n.readChapters} / ${n.totalChapters} read · " +
                        if (n.savedChapters > 0) "${n.savedChapters} saved" else "Not saved offline", n.lastRead)
            }
            return (manga + books + novels).filter { it.lastRead > 0 && it.key !in hidden }
        }

        suspend fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val largeIds = manager.getAppWidgetIds(ComponentName(context, ContinueReadingWidget::class.java))
            val compactIds = manager.getAppWidgetIds(ComponentName(context, ContinueReadingCompactWidget::class.java))
            if (largeIds.isEmpty() && compactIds.isEmpty()) return
            val g = context.appGraph
            val locked = g.securityPreferences.useAuthenticator.get()
            val story = if (locked) null else runCatching { selectContinueStory(stories(context), g.libraryVisibility.hiddenSeries.get()) }.getOrNull()
            val bitmap = story?.cover?.let { cover -> runCatching {
                // Never fetch cover art from the network just to render a launcher widget.
                context.imageLoader.execute(ImageRequest.Builder(context).data(cover).size(240, 340)
                    .networkCachePolicy(CachePolicy.DISABLED).build()).image?.asDrawable(context.resources)?.toBitmap()
            }.getOrNull() }
            // Recheck after suspend work, so enabling privacy lock cannot publish an old cover.
            if (!locked && g.securityPreferences.useAuthenticator.get()) { refresh(context); return }
            if (story != null && g.libraryVisibility.isHidden(story.key)) { refresh(context); return }
            (largeIds.map { it to false } + compactIds.map { it to true }).forEach { (id, compact) ->
                val view = RemoteViews(context.packageName, if (compact) R.layout.continue_reading_widget_compact else R.layout.continue_reading_widget)
                val dark = when (g.uiPreferences.themeMode.get()) {
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                    ThemeMode.SYSTEM -> context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
                val scheme = getThemeColorScheme(context, g.uiPreferences.appTheme.get(), dark, g.uiPreferences.themeDarkAmoled.get())
                val ink = scheme.onPrimary.toArgb()
                val options = manager.getAppWidgetOptions(id)
                val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, if (compact) 110 else 280)
                    .coerceIn(if (compact) 110 else 250, 600)
                val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, if (compact) 110 else 220)
                    .coerceIn(if (compact) 110 else 200, 400)
                val background = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                Canvas(background).apply {
                    drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 20f, 20f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = scheme.primary.toArgb() })
                    clipPath(Path().apply { addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 20f, 20f, Path.Direction.CW) })
                    // The same two slashes as the home Continue Reading card.
                    val unit = width / 364f
                    for (offset in listOf(0f, 48f)) drawPath(Path().apply {
                        moveTo((281f + offset) * unit, 0f)
                        lineTo((227f + offset) * unit, 70f * unit)
                        lineTo((265f + offset) * unit, 70f * unit)
                        lineTo((319f + offset) * unit, 0f)
                        close()
                    }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (ink and 0x00FFFFFF) or 0x29000000 })
                }
                view.setImageViewBitmap(R.id.continue_background, background)
                listOf(R.id.continue_label, R.id.continue_title, R.id.continue_author,
                    R.id.continue_status).forEach { view.setTextColor(it, ink) }
                val pill = Bitmap.createBitmap(88, 28, Bitmap.Config.ARGB_8888)
                Canvas(pill).drawRoundRect(RectF(0f, 0f, 88f, 28f), 8f, 8f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
                view.setImageViewBitmap(R.id.continue_kind_background, pill)
                view.setTextColor(R.id.continue_kind, scheme.primary.toArgb())
                val arrow = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
                Canvas(arrow).drawCircle(20f, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
                view.setImageViewBitmap(R.id.continue_arrow_background, arrow)
                view.setTextColor(R.id.continue_arrow, scheme.primary.toArgb())
                val largeText = context.resources.configuration.fontScale > 1.3f
                view.setInt(R.id.continue_title, "setMaxLines", if (compact || largeText) 2 else 3)
                view.setViewVisibility(R.id.continue_label, if (compact && largeText) View.GONE else View.VISIBLE)
                view.setViewVisibility(R.id.continue_author, if (compact || largeText) View.GONE else View.VISIBLE)
                view.setViewVisibility(R.id.continue_kind_container, if (compact || largeText) View.GONE else View.VISIBLE)
                val title = story?.title ?: if (locked) "YOUR LIBRARY IS LOCKED" else "YOUR NEXT CHAPTER"
                view.setTextViewText(R.id.continue_title, if (compact) title else title.uppercase(Locale.getDefault()))
                view.setTextViewText(R.id.continue_author, story?.author ?: if (locked) "Open Arkhive to unlock" else "Start a story in Arkhive")
                view.setTextViewText(R.id.continue_kind, story?.kind ?: "ARKHIVE")
                view.setTextViewText(R.id.continue_status, story?.status ?: "Pick up where you left off")
                // A bounded native meter keeps app palette colors on API 26+ as well as modern launchers.
                val meter = Bitmap.createBitmap(320, 3, Bitmap.Config.ARGB_8888)
                meter.eraseColor(androidx.core.graphics.ColorUtils.blendARGB(scheme.primary.toArgb(), ink, .3f))
                Canvas(meter).drawRect(0f, 0f, (320 * (story?.progress ?: 0.0).coerceIn(0.0, 1.0)).toFloat(), 3f,
                    Paint().apply { color = ink })
                view.setImageViewBitmap(R.id.continue_progress, meter)
                view.setImageViewResource(R.id.continue_cover, R.drawable.readingapp_icon)
                if (bitmap != null) view.setImageViewBitmap(R.id.continue_cover, bitmap)
                view.setContentDescription(R.id.continue_cover, story?.let { "Cover of ${it.title}" } ?: "Arkhive")
                view.setContentDescription(R.id.continue_root, story?.let { "Resume ${it.title}. ${it.status}" } ?: "Open Arkhive library")
                val intent = Intent(context, MainActivity::class.java).setAction(ACTION)
                    .setData(android.net.Uri.parse("arkhive://continue/$id"))
                    .putExtra(KEY, story?.key).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                view.setOnClickPendingIntent(R.id.continue_root, PendingIntent.getActivity(context, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                manager.updateAppWidget(id, view)
            }
        }

        /** Called only through the normal MainActivity. Revalidate stale launcher intents. */
        suspend fun resume(context: Context, key: String?, detail: (eu.kanade.presentation.util.Screen) -> Unit) {
            val g = context.appGraph
            if (key == null || g.securityPreferences.useAuthenticator.get() || g.libraryVisibility.isHidden(key)) return
            if (stories(context).none { it.key == key }) return
            val id = key.substringAfter(':')
            when (key.substringBefore(':')) {
                "manga" -> {
                    val mangaId = id.toLongOrNull() ?: return
                    val latest = g.widgetHistory.await(mangaId).maxByOrNull { it.readAt?.toEpochMilliseconds() ?: 0 }
                    val chapter = selectContinueChapter(g.getNextChapters.await(mangaId, onlyUnread = false), latest?.chapterId)
                    if (g.securityPreferences.useAuthenticator.get() || g.libraryVisibility.isHidden(key)) return
                    if (chapter == null) detail(eu.kanade.tachiyomi.ui.manga.MangaScreen(mangaId))
                    else context.startActivity(ReaderActivity.newIntent(context, mangaId, chapter.id))
                }
                "book" -> if (g.localBookRepository.get(id) != null) context.startActivity(
                    Intent(context, BookReaderActivity::class.java).putExtra(BookReaderActivity.BOOK_ID, id))
                "novel" -> g.novelRepository.get(id)?.let { n ->
                    val chapters = g.novelRepository.chapters(id)
                    if (chapters.isEmpty()) { detail(mihon.readingapp.novels.NovelDetailScreen(n.sourceId, n.detail.path)); return }
                    val current = chapters.firstOrNull { it.id == n.lastChapter }
                        ?: chapters.firstOrNull { it.progression < 1.0 } ?: chapters.first()
                    val next = if (current != null && current.progression >= 1.0)
                        chapters.firstOrNull { it.position > current.position && it.progression < 1.0 } ?: current else current
                    context.startActivity(NovelReaderActivity.intent(context, id, next?.id))
                }
            }
        }
    }
}

/** A separate launcher picker option sharing selection, refresh, and resume behavior. */
class ContinueReadingCompactWidget : ContinueReadingWidget()
