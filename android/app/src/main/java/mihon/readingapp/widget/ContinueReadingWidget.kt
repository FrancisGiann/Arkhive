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
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Path
import android.util.TypedValue
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
import kotlin.math.roundToInt

internal data class ContinueStory(val key: String, val title: String, val author: String,
    val kind: String, val cover: Any?, val progress: Double, val status: String, val lastRead: Long)

internal data class ContinueReadingRequest(val key: String?)

private data class WidgetSelection(val locked: Boolean, val story: ContinueStory?)

/** Coil can return hardware pixels that a software widget Canvas cannot draw or parcel safely. */
internal fun Bitmap.forWidgetCanvas(): Bitmap =
    if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false)
        ?: error("Could not copy widget cover to a software bitmap") else this

/** Consume a widget launch before navigation so warm and restored activities cannot replay it. */
internal fun Intent.takeContinueReadingRequest(): ContinueReadingRequest? {
    if (action != ContinueReadingWidget.ACTION) return null
    val request = ContinueReadingRequest(getStringExtra(ContinueReadingWidget.KEY))
    removeExtra(ContinueReadingWidget.KEY)
    action = Intent.ACTION_MAIN
    return request
}

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
        private val refreshCoordinator = ContinueReadingRefreshCoordinator<WidgetSelection, Bitmap>()

        /** A cover-led compact card with an opaque footer, drawn as one rounded bitmap. */
        internal fun compactBackground(context: Context, cover: Bitmap?, width: Int, height: Int, accent: Int): Bitmap {
            val density = context.resources.displayMetrics.density.coerceIn(1f, 2.5f)
            val result = Bitmap.createBitmap((width * density).roundToInt(), (height * density).roundToInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.scale(density, density)
            canvas.clipPath(Path().apply {
                addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 18f, 18f, Path.Direction.CW)
            })
            if (cover != null && cover.width > 0 && cover.height > 0) {
                val canvasCover = cover.forWidgetCanvas()
                // Center-crop without stretching the book cover.
                val sourceAspect = canvasCover.width.toFloat() / canvasCover.height
                val targetAspect = width.toFloat() / height
                val cropWidth = if (sourceAspect > targetAspect) (canvasCover.height * targetAspect).roundToInt().coerceIn(1, canvasCover.width) else canvasCover.width
                val cropHeight = if (sourceAspect > targetAspect) canvasCover.height else (canvasCover.width / targetAspect).roundToInt().coerceIn(1, canvasCover.height)
                val left = (canvasCover.width - cropWidth) / 2
                val top = (canvasCover.height - cropHeight) / 2
                canvas.drawBitmap(canvasCover, Rect(left, top, left + cropWidth, top + cropHeight),
                    RectF(0f, 0f, width.toFloat(), height.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            } else {
                canvas.drawColor(androidx.core.graphics.ColorUtils.blendARGB(accent, Color.rgb(34, 29, 54), .62f))
                runCatching { context.getDrawable(R.drawable.readingapp_icon)?.toBitmap(42, 42) }.getOrNull()?.let { icon ->
                    val centerX = width / 2f
                    val centerY = (height - 61f) / 2f
                    canvas.drawBitmap(icon, null, RectF(centerX - 21f, centerY - 21f, centerX + 21f, centerY + 21f),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
            }
            canvas.drawRect(0f, height - 61f, width.toFloat(), height.toFloat(),
                Paint().apply { color = Color.rgb(37, 30, 60) })
            return result
        }

        private fun compactBookplateBackground(context: Context, width: Int, height: Int, metrics: CompactBookplateMetrics): Bitmap {
            val density = context.resources.displayMetrics.density.coerceIn(1f, 2.5f)
            val result = Bitmap.createBitmap((width * density).roundToInt(), (height * density).roundToInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result).apply { scale(density, density) }
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), metrics.cornerRadius,
                metrics.cornerRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(49, 40, 69) })
            return result
        }

        private fun compactBookplateCover(context: Context, cover: Bitmap?, metrics: CompactBookplateMetrics): Bitmap {
            val density = context.resources.displayMetrics.density.coerceIn(1f, 2.5f)
            val width = (metrics.coverWidth * density).roundToInt().coerceAtLeast(1)
            val height = (metrics.coverHeight * density).roundToInt().coerceAtLeast(1)
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.scale(density, density)
            canvas.clipPath(Path().apply {
                addRoundRect(RectF(0f, 0f, metrics.coverWidth, metrics.coverHeight), 7f * metrics.scale,
                    7f * metrics.scale, Path.Direction.CW)
            })
            val source = cover?.takeIf { it.width > 0 && it.height > 0 }?.forWidgetCanvas()
            if (source != null) {
                val sourceAspect = source.width.toFloat() / source.height
                val targetAspect = metrics.coverWidth / metrics.coverHeight
                val cropWidth = if (sourceAspect > targetAspect) (source.height * targetAspect).roundToInt().coerceIn(1, source.width) else source.width
                val cropHeight = if (sourceAspect > targetAspect) source.height else (source.width / targetAspect).roundToInt().coerceIn(1, source.height)
                val left = (source.width - cropWidth) / 2
                val top = (source.height - cropHeight) / 2
                canvas.drawBitmap(source, Rect(left, top, left + cropWidth, top + cropHeight),
                    RectF(0f, 0f, metrics.coverWidth, metrics.coverHeight),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            } else {
                canvas.drawColor(Color.rgb(73, 57, 95))
                runCatching { context.getDrawable(R.drawable.readingapp_icon)?.toBitmap(42, 42) }
                    .getOrNull()?.forWidgetCanvas()?.let { icon ->
                        val side = minOf(metrics.coverWidth, metrics.coverHeight) * .48f
                        val insetX = (metrics.coverWidth - side) / 2f
                        val insetY = (metrics.coverHeight - side) / 2f
                        canvas.drawBitmap(icon, null, RectF(insetX, insetY, insetX + side, insetY + side),
                            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    }
            }
            return result
        }

        private fun compactBookplateRing(context: Context, progress: Float, metrics: CompactBookplateMetrics): Bitmap {
            val density = context.resources.displayMetrics.density.coerceIn(1f, 2.5f)
            val side = (metrics.ringDiameter * density).roundToInt().coerceAtLeast(1)
            val result = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result).apply { scale(density, density) }
            val inset = metrics.ringStroke / 2f
            val bounds = RectF(inset, inset, metrics.ringDiameter - inset, metrics.ringDiameter - inset)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = metrics.ringStroke }
            paint.color = Color.rgb(112, 97, 126)
            canvas.drawArc(bounds, 0f, 360f, false, paint)
            paint.color = Color.rgb(216, 163, 255)
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(bounds, -90f, 360f * progress.coerceIn(0f, 1f), false, paint)
            return result
        }

        /** The hybrid keeps a real portrait cover and a separate, full-width resume affordance. */
        private fun hybridBackground(context: Context, width: Int, height: Int, dark: Boolean): Bitmap {
            val density = context.resources.displayMetrics.density.coerceIn(1f, 2.5f)
            val result = Bitmap.createBitmap((width * density).roundToInt(), (height * density).roundToInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result).apply { scale(density, density) }
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 18f, 18f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.rgb(49, 40, 69) else Color.rgb(237, 228, 250) })
            canvas.drawRoundRect(RectF(6f, height - 36f, width - 6f, height - 6f), 10f, 10f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.rgb(196, 162, 247) else Color.rgb(45, 34, 70) })
            return result
        }

        internal fun hybridCover(context: Context, cover: Bitmap?, width: Int, height: Int, dark: Boolean): Bitmap {
            val density = context.resources.displayMetrics.density.coerceIn(1f, 2.5f)
            val result = Bitmap.createBitmap((width * density).roundToInt(), (height * density).roundToInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result).apply { scale(density, density) }
            canvas.clipPath(Path().apply {
                addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 7f, 7f, Path.Direction.CW)
            })
            if (cover != null && cover.width > 0 && cover.height > 0) {
                val canvasCover = cover.forWidgetCanvas()
                val targetAspect = width.toFloat() / height
                val coverAspect = canvasCover.width.toFloat() / canvasCover.height
                val cropWidth = if (coverAspect > targetAspect) (canvasCover.height * targetAspect).roundToInt().coerceIn(1, canvasCover.width) else canvasCover.width
                val cropHeight = if (coverAspect > targetAspect) canvasCover.height else (canvasCover.width / targetAspect).roundToInt().coerceIn(1, canvasCover.height)
                val left = (canvasCover.width - cropWidth) / 2
                val top = (canvasCover.height - cropHeight) / 2
                canvas.drawBitmap(canvasCover, Rect(left, top, left + cropWidth, top + cropHeight),
                    RectF(0f, 0f, width.toFloat(), height.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            } else {
                canvas.drawColor(if (dark) Color.rgb(73, 57, 103) else Color.rgb(197, 176, 225))
                runCatching { context.getDrawable(R.drawable.readingapp_icon)?.toBitmap(42, 42) }.getOrNull()?.let { icon ->
                    val side = minOf(width, height) * .58f
                    canvas.drawBitmap(icon, null,
                        RectF((width - side) / 2f, (height - side) / 2f, (width + side) / 2f, (height + side) / 2f),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
            }
            return result
        }

        private fun hybridStatus(story: ContinueStory?, locked: Boolean, tight: Boolean): String {
            if (story == null) return if (locked) "Unlock to resume" else "Start reading"
            if (tight) return "${(story.progress.coerceIn(0.0, 1.0) * 100).roundToInt()}% read"
            return when (story.key.substringBefore(':')) {
                "manga" -> story.status.substringBefore(" chapters read") + " read"
                "novel" -> story.status.substringBefore(" · ")
                "book" -> "${(story.progress.coerceIn(0.0, 1.0) * 100).roundToInt()}% read"
                else -> story.status
            }
        }

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
            val hybridIds = manager.getAppWidgetIds(ComponentName(context, ContinueReadingHybridWidget::class.java))
            if (largeIds.isEmpty() && compactIds.isEmpty() && hybridIds.isEmpty()) return
            val g = context.appGraph
            refreshCoordinator.refresh(
                select = {
                    val locked = g.securityPreferences.useAuthenticator.get()
                    val story = if (locked) null else try {
                        selectContinueStory(stories(context), g.libraryVisibility.hiddenSeries.get())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    WidgetSelection(locked, story)
                },
                loadCover = { selection -> selection.story?.cover?.let { cover ->
                    // Never fetch cover art from the network just to render a launcher widget.
                    context.imageLoader.execute(ImageRequest.Builder(context).data(cover).size(240, 340)
                        .networkCachePolicy(CachePolicy.DISABLED).build()).image?.asDrawable(context.resources)?.toBitmap()?.forWidgetCanvas()
                } },
                sameSelection = { before, after ->
                    before.locked == after.locked &&
                        before.story?.key == after.story?.key &&
                        before.story?.title == after.story?.title &&
                        before.story?.author == after.story?.author &&
                        before.story?.kind == after.story?.kind &&
                        before.story?.progress == after.story?.progress &&
                        before.story?.status == after.story?.status &&
                        before.story?.lastRead == after.story?.lastRead
                },
                publish = { selection, bitmap ->
            val locked = selection.locked
            val story = selection.story
            (largeIds.map { Triple(it, false, false) } + compactIds.map { Triple(it, true, false) } +
                hybridIds.map { Triple(it, true, true) }).forEach { (id, compact, hybrid) ->
                val options = manager.getAppWidgetOptions(id)
                val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, if (compact) 110 else 280)
                    .coerceIn(if (compact) 110 else 250, 600)
                val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, if (compact) 110 else 220)
                    .coerceIn(if (compact) 110 else 200, 400)
                val fontScale = context.resources.configuration.fontScale
                val tightCompact = compact && isTightCompactWidget(width, height, fontScale)
                val bookplate = compact && !hybrid && usesCompactBookplate(width, height, fontScale)
                val layout = when {
                    hybrid -> R.layout.continue_reading_widget_hybrid
                    bookplate && (width < 170 || height < 170) -> R.layout.continue_reading_widget_compact_small
                    bookplate -> R.layout.continue_reading_widget_compact
                    compact -> R.layout.continue_reading_widget_compact_legacy
                    else -> R.layout.continue_reading_widget
                }
                val view = RemoteViews(context.packageName, layout)
                val dark = when (g.uiPreferences.themeMode.get()) {
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                    ThemeMode.SYSTEM -> context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
                val scheme = getThemeColorScheme(context, g.uiPreferences.appTheme.get(), dark, g.uiPreferences.themeDarkAmoled.get())
                val ink = scheme.onPrimary.toArgb()
                if (hybrid) {
                    val tight = width < 145 || height < 140 || context.resources.configuration.fontScale > 1.3f
                    val text = if (dark) Color.WHITE else Color.rgb(43, 32, 66)
                    val buttonText = if (dark) Color.rgb(34, 25, 49) else Color.WHITE
                    val title = story?.title ?: if (locked) "Your library is locked" else "Your next chapter"
                    view.setImageViewBitmap(R.id.continue_hybrid_background, hybridBackground(context, width, height, dark))
                    view.setImageViewBitmap(R.id.continue_hybrid_cover,
                        hybridCover(context, bitmap, ((width - 18) * .43f).roundToInt().coerceAtLeast(1), (height - 48).coerceAtLeast(1), dark))
                    view.setTextViewText(R.id.continue_hybrid_title, title)
                    // Reserve status/meter space at 110dp, then use the extra height on roomier launchers.
                    // The title and status sit in separate weighted rows so long text cannot overlap them.
                    val titleLines = ((height - 82) / 15).coerceIn(2, 5)
                    view.setInt(R.id.continue_hybrid_title, "setMaxLines", titleLines)
                    view.setTextColor(R.id.continue_hybrid_title, text)
                    view.setTextViewText(R.id.continue_hybrid_status, hybridStatus(story, locked, tight))
                    view.setTextColor(R.id.continue_hybrid_status, text)
                    val meter = Bitmap.createBitmap(320, 3, Bitmap.Config.ARGB_8888)
                    meter.eraseColor(if (dark) Color.rgb(103, 83, 126) else Color.rgb(191, 173, 219))
                    Canvas(meter).drawRect(0f, 0f, (320 * (story?.progress ?: 0.0).coerceIn(0.0, 1.0)).toFloat(), 3f,
                        Paint().apply { color = if (dark) Color.rgb(221, 187, 255) else Color.rgb(111, 67, 185) })
                    view.setImageViewBitmap(R.id.continue_hybrid_progress, meter)
                    val action = if (story == null) {
                        if (locked) "Unlock Arkhive" else "Open library"
                    } else "Resume reading"
                    view.setTextViewText(R.id.continue_hybrid_resume_text, if (tight) action.substringBefore(' ') else action)
                    view.setTextColor(R.id.continue_hybrid_resume_text, buttonText)
                    view.setTextColor(R.id.continue_hybrid_arrow, buttonText)
                    val description = story?.let { "Resume reading ${it.title}. ${it.status}" } ?: action
                    view.setContentDescription(R.id.continue_root, description)
                    view.setContentDescription(R.id.continue_hybrid_resume_button, description)
                    val intent = Intent(context, MainActivity::class.java).setAction(ACTION)
                        .setData(android.net.Uri.parse("arkhive://continue/$id"))
                        .putExtra(KEY, story?.key).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    val click = PendingIntent.getActivity(context, id, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    view.setOnClickPendingIntent(R.id.continue_root, click)
                    view.setOnClickPendingIntent(R.id.continue_hybrid_resume_button, click)
                    manager.updateAppWidget(id, view)
                    return@forEach
                }
                if (bookplate) {
                    val metrics = compactBookplateMetrics(width, height, fontScale)
                    val copy = compactBookplateCopy(story, locked)
                    view.setImageViewBitmap(R.id.continue_bookplate_background,
                        compactBookplateBackground(context, width, height, metrics))
                    view.setImageViewBitmap(R.id.continue_bookplate_cover,
                        compactBookplateCover(context, bitmap, metrics))
                    view.setTextViewText(R.id.continue_bookplate_title, copy.title)
                    view.setInt(R.id.continue_bookplate_title, "setMaxLines", metrics.maxTitleLines)
                    view.setTextViewTextSize(R.id.continue_bookplate_title, TypedValue.COMPLEX_UNIT_SP, 14f * metrics.scale)
                    view.setTextViewTextSize(R.id.continue_bookplate_percent, TypedValue.COMPLEX_UNIT_SP, 8.5f * metrics.scale)
                    view.setTextViewTextSize(R.id.continue_bookplate_count, TypedValue.COMPLEX_UNIT_SP, 12f * metrics.scale)
                    view.setTextViewTextSize(R.id.continue_bookplate_read_label, TypedValue.COMPLEX_UNIT_SP, 8.5f * metrics.scale)
                    view.setContentDescription(R.id.continue_bookplate_cover, "Cover of ${copy.title}")
                    view.setContentDescription(R.id.continue_root, copy.contentDescription)
                    view.setViewVisibility(R.id.continue_bookplate_progress, if (copy.hasProgress) View.VISIBLE else View.GONE)
                    if (copy.progress != null) {
                        val percent = (copy.progress * 100f).roundToInt().coerceIn(0, 100)
                        view.setImageViewBitmap(R.id.continue_bookplate_ring,
                            compactBookplateRing(context, copy.progress, metrics))
                        view.setTextViewText(R.id.continue_bookplate_percent, "$percent%")
                    }
                    view.setViewVisibility(R.id.continue_bookplate_count,
                        if (copy.count != null) View.VISIBLE else View.GONE)
                    view.setViewVisibility(R.id.continue_bookplate_read_label,
                        if (copy.count != null) View.VISIBLE else View.GONE)
                    if (copy.count != null) {
                        view.setTextViewText(R.id.continue_bookplate_count, copy.count)
                        view.setTextViewText(R.id.continue_bookplate_read_label, "read")
                    }
                    val actionText = when {
                        story != null -> context.getString(R.string.continue_widget_resume_reading)
                        locked -> context.getString(R.string.continue_widget_unlock)
                        else -> context.getString(R.string.continue_widget_open_library)
                    }
                    view.setTextViewText(R.id.continue_bookplate_resume_text, actionText)
                    view.setContentDescription(R.id.continue_bookplate_resume, actionText)
                    val intent = Intent(context, MainActivity::class.java).setAction(ACTION)
                        .setData(android.net.Uri.parse("arkhive://continue/$id"))
                        .putExtra(KEY, story?.key).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    val click = PendingIntent.getActivity(context, id, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    view.setOnClickPendingIntent(R.id.continue_root, click)
                    view.setOnClickPendingIntent(R.id.continue_bookplate_resume, click)
                    manager.updateAppWidget(id, view)
                    return@forEach
                }
                val background = if (compact) compactBackground(context, bitmap, width, height, scheme.primary.toArgb())
                    else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { largeBackground -> Canvas(largeBackground).apply {
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
                } }
                view.setImageViewBitmap(R.id.continue_background, background)
                listOf(R.id.continue_label, R.id.continue_title, R.id.continue_author,
                    R.id.continue_status).forEach { view.setTextColor(it, if (compact) Color.WHITE else ink) }
                val pill = Bitmap.createBitmap(88, 28, Bitmap.Config.ARGB_8888)
                Canvas(pill).drawRoundRect(RectF(0f, 0f, 88f, 28f), 8f, 8f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
                view.setImageViewBitmap(R.id.continue_kind_background, pill)
                view.setTextColor(R.id.continue_kind, scheme.primary.toArgb())
                val arrow = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
                Canvas(arrow).drawCircle(20f, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (compact) scheme.primary.toArgb() else ink
                })
                view.setImageViewBitmap(R.id.continue_arrow_background, arrow)
                view.setTextColor(R.id.continue_arrow, if (compact) ink else scheme.primary.toArgb())
                val largeText = fontScale > 1.3f
                view.setInt(R.id.continue_title, "setMaxLines", if (tightCompact) 1 else if (compact || largeText) 2 else 3)
                view.setViewVisibility(R.id.continue_label, if (compact) View.GONE else View.VISIBLE)
                view.setViewVisibility(R.id.continue_author, if (compact || largeText) View.GONE else View.VISIBLE)
                val showKind = !compact && !largeText
                view.setViewVisibility(R.id.continue_kind_container, if (showKind) View.VISIBLE else View.GONE)
                view.setViewVisibility(R.id.continue_status, if (tightCompact) View.GONE else View.VISIBLE)
                val title = story?.title ?: if (locked) "YOUR LIBRARY IS LOCKED" else "YOUR NEXT CHAPTER"
                view.setTextViewText(R.id.continue_title, if (compact) title else title.uppercase(Locale.getDefault()))
                view.setTextViewText(R.id.continue_author, story?.author ?: if (locked) "Open Arkhive to unlock" else "Start a story in Arkhive")
                view.setTextViewText(R.id.continue_kind, story?.kind ?: "ARKHIVE")
                view.setTextViewText(R.id.continue_status, story?.status ?: "Pick up where you left off")
                // A bounded native meter keeps app palette colors on API 26+ as well as modern launchers.
                val meter = Bitmap.createBitmap(320, 3, Bitmap.Config.ARGB_8888)
                meter.eraseColor(if (compact) Color.rgb(90, 76, 112) else
                    androidx.core.graphics.ColorUtils.blendARGB(scheme.primary.toArgb(), ink, .3f))
                Canvas(meter).drawRect(0f, 0f, (320 * (story?.progress ?: 0.0).coerceIn(0.0, 1.0)).toFloat(), 3f,
                    Paint().apply { color = if (compact) Color.rgb(216, 163, 255) else ink })
                view.setImageViewBitmap(R.id.continue_progress, meter)
                view.setImageViewResource(R.id.continue_cover, R.drawable.readingapp_icon)
                if (bitmap != null) view.setImageViewBitmap(R.id.continue_cover, bitmap)
                // Compact artwork and its empty-state icon are part of the rounded background.
                if (compact) view.setViewVisibility(R.id.continue_cover, View.GONE)
                view.setContentDescription(R.id.continue_cover, story?.let { "Cover of ${it.title}" } ?: "Arkhive")
                view.setContentDescription(R.id.continue_root, story?.let { "Resume ${it.title}. ${it.status}" } ?: "Open Arkhive library")
                val intent = Intent(context, MainActivity::class.java).setAction(ACTION)
                    .setData(android.net.Uri.parse("arkhive://continue/$id"))
                    .putExtra(KEY, story?.key).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                view.setOnClickPendingIntent(R.id.continue_root, PendingIntent.getActivity(context, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                manager.updateAppWidget(id, view)
            }
                },
            )
        }

        /** Called only through the normal MainActivity. Revalidate stale launcher intents. */
        suspend fun resume(context: Context, key: String?, detail: (eu.kanade.presentation.util.Screen) -> Unit): Boolean {
            val g = context.appGraph
            if (key == null || g.securityPreferences.useAuthenticator.get() || g.libraryVisibility.isHidden(key)) return false
            if (stories(context).none { it.key == key }) return false
            val id = key.substringAfter(':')
            return when (key.substringBefore(':')) {
                "manga" -> {
                    val mangaId = id.toLongOrNull() ?: return false
                    val latest = g.widgetHistory.await(mangaId).maxByOrNull { it.readAt?.toEpochMilliseconds() ?: 0 }
                    val chapter = selectContinueChapter(g.getNextChapters.await(mangaId, onlyUnread = false), latest?.chapterId)
                    if (g.securityPreferences.useAuthenticator.get() || g.libraryVisibility.isHidden(key)) return false
                    if (chapter == null) detail(eu.kanade.tachiyomi.ui.manga.MangaScreen(mangaId))
                    else context.startActivity(ReaderActivity.newIntent(context, mangaId, chapter.id))
                    true
                }
                "book" -> if (g.localBookRepository.get(id) != null) {
                    context.startActivity(Intent(context, BookReaderActivity::class.java).putExtra(BookReaderActivity.BOOK_ID, id))
                    true
                } else false
                "novel" -> g.novelRepository.get(id)?.let { n ->
                    val chapters = g.novelRepository.chapters(id)
                    if (chapters.isEmpty()) {
                        detail(mihon.readingapp.novels.NovelDetailScreen(n.sourceId, n.detail.path))
                        return true
                    }
                    val current = chapters.firstOrNull { it.id == n.lastChapter }
                        ?: chapters.firstOrNull { it.progression < 1.0 } ?: chapters.first()
                    val next = if (current.progression >= 1.0)
                        chapters.firstOrNull { it.position > current.position && it.progression < 1.0 } ?: current else current
                    context.startActivity(NovelReaderActivity.intent(context, id, next.id))
                    true
                } ?: false
                else -> false
            }
        }
    }
}

/** A separate launcher picker option sharing selection, refresh, and resume behavior. */
class ContinueReadingCompactWidget : ContinueReadingWidget()

/** A second 2×2 picker option with a larger portrait cover and explicit resume button. */
class ContinueReadingHybridWidget : ContinueReadingWidget()
