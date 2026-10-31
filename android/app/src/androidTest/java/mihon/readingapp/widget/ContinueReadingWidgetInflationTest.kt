package mihon.readingapp.widget

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Catches views and RemoteViews actions that a launcher cannot inflate. */
@RunWith(AndroidJUnit4::class)
class ContinueReadingWidgetInflationTest {
    @Test
    fun hardwareCoverCanBeDrawnInBothCompactDesigns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val hardwareCover = source.copy(Bitmap.Config.HARDWARE, false)
        assertNotNull(hardwareCover)
        val art = ContinueReadingWidget.compactBackground(context, hardwareCover, 180, 180, Color.MAGENTA)
        val hybrid = ContinueReadingWidget.hybridCover(context, hardwareCover, 80, 120, false)
        assertEquals(Color.RED, art.getPixel(30, 30))
        assertEquals(Color.RED, hybrid.getPixel(40, 60))
    }

    @Test
    fun hybridTwoByTwoKeepsCoverDetailsAndResumeButtonReadable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tinyImage = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val click = PendingIntent.getActivity(
            context, 18, Intent(context, MainActivity::class.java).setAction(ContinueReadingWidget.ACTION)
                .putExtra(ContinueReadingWidget.KEY, "manga:42"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val layout = R.layout.continue_reading_widget_hybrid
        val content = RemoteViews(context.packageName, layout).apply {
            setImageViewBitmap(R.id.continue_hybrid_background, tinyImage)
            setImageViewBitmap(R.id.continue_hybrid_cover, tinyImage)
            setImageViewBitmap(R.id.continue_hybrid_progress, tinyImage)
            setTextViewText(R.id.continue_hybrid_title, "Childhood Friend of the Zenith and a long subtitle")
            setTextViewText(R.id.continue_hybrid_status, "19 / 110 read")
            setTextViewText(R.id.continue_hybrid_resume_text, "Resume reading")
            setContentDescription(R.id.continue_root, "Resume reading this story")
            setOnClickPendingIntent(R.id.continue_root, click)
            setOnClickPendingIntent(R.id.continue_hybrid_resume_button, click)
        }
        val rendered = content.apply(context, FrameLayout(context))
        val cover = rendered.findViewById<View>(R.id.continue_hybrid_cover)
        val title = rendered.findViewById<TextView>(R.id.continue_hybrid_title)
        val status = rendered.findViewById<View>(R.id.continue_hybrid_status)
        val progress = rendered.findViewById<View>(R.id.continue_hybrid_progress)
        val button = rendered.findViewById<View>(R.id.continue_hybrid_resume_button)
        val label = rendered.findViewById<TextView>(R.id.continue_hybrid_resume_text)
        for ((width, height) in listOf(110 to 110, 140 to 140, 160 to 160, 180 to 220)) {
            RemoteViews(context.packageName, layout).apply {
                setInt(R.id.continue_hybrid_title, "setMaxLines", ((height - 82) / 15).coerceIn(2, 5))
                setTextViewText(R.id.continue_hybrid_resume_text, if (width == 110) "Resume" else "Resume reading")
            }.reapply(context, rendered)
            val density = context.resources.displayMetrics.density
            val widthPx = (width * density).toInt()
            val heightPx = (height * density).toInt()
            rendered.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            rendered.layout(0, 0, widthPx, heightPx)
            fun bounds(view: View) = Rect(0, 0, view.width, view.height).also {
                (rendered as ViewGroup).offsetDescendantRectToMyCoords(view, it)
            }
            assertTrue("portrait cover has useful width", cover.width > (if (width == 110) 30 else 50) * density)
            assertTrue("cover sits beside the title", bounds(cover).right < bounds(title).left)
            assertTrue("title does not collide with progress", bounds(title).bottom <= bounds(progress).top)
            assertTrue("long title never overlaps reading status", bounds(title).bottom <= bounds(status).top)
            if (height >= 160) assertTrue("roomier 2x2 cards allow the title to wrap", title.maxLines >= 4)
            assertTrue("status clears the progress meter", bounds(status).bottom <= bounds(progress).top)
            assertTrue("cover clears the button", bounds(cover).bottom <= bounds(button).top)
            assertTrue("button stays inside the card", bounds(button).bottom <= heightPx)
            assertEquals(if (width == 110) "Resume" else "Resume reading", label.text.toString())
        }
        RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.continue_hybrid_title, "Your next chapter")
            setTextViewText(R.id.continue_hybrid_status, "Start reading")
            setTextViewText(R.id.continue_hybrid_resume_text, "Open library")
        }.reapply(context, rendered)
        assertEquals("Your next chapter", title.text.toString())
        assertEquals("Open library", label.text.toString())
    }

    @Test
    fun bookplateFitsLongTitleProgressAndResumeAt160And180Dp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tinyImage = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val portraitCover = Bitmap.createBitmap(240, 340, Bitmap.Config.ARGB_8888)
        val click = PendingIntent.getActivity(
            context, 19, Intent(context, MainActivity::class.java).setAction(ContinueReadingWidget.ACTION)
                .putExtra(ContinueReadingWidget.KEY, "manga:42"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        for ((size, layout) in listOf(
            160 to R.layout.continue_reading_widget_compact_small,
            180 to R.layout.continue_reading_widget_compact,
        )) {
            val metrics = compactBookplateMetrics(size, size)
            val content = RemoteViews(context.packageName, layout).apply {
                setImageViewBitmap(R.id.continue_bookplate_background, tinyImage)
                setImageViewBitmap(R.id.continue_bookplate_cover, portraitCover)
                setImageViewBitmap(R.id.continue_bookplate_ring, tinyImage)
                setTextViewText(R.id.continue_bookplate_title, "Childhood Friend of the Zenith")
                setInt(R.id.continue_bookplate_title, "setMaxLines", metrics.maxTitleLines)
                setTextViewText(R.id.continue_bookplate_percent, "17%")
                setTextViewText(R.id.continue_bookplate_count, "19 / 110")
                setTextViewText(R.id.continue_bookplate_read_label, "read")
                setTextViewText(R.id.continue_bookplate_resume_text, "Resume reading")
                setViewVisibility(R.id.continue_bookplate_progress, View.VISIBLE)
                setContentDescription(R.id.continue_root, "Resume reading Childhood Friend of the Zenith. 19 / 110 read")
                setContentDescription(R.id.continue_bookplate_resume, "Resume reading")
                setOnClickPendingIntent(R.id.continue_root, click)
                setOnClickPendingIntent(R.id.continue_bookplate_resume, click)
            }
            val rendered = content.apply(context, FrameLayout(context))
            val cover = rendered.findViewById<View>(R.id.continue_bookplate_cover)
            val title = rendered.findViewById<TextView>(R.id.continue_bookplate_title)
            val progress = rendered.findViewById<View>(R.id.continue_bookplate_progress)
            val ring = rendered.findViewById<View>(R.id.continue_bookplate_ring)
            val count = rendered.findViewById<View>(R.id.continue_bookplate_count)
            val resume = rendered.findViewById<View>(R.id.continue_bookplate_resume)
            val action = rendered.findViewById<TextView>(R.id.continue_bookplate_resume_text)
            val density = context.resources.displayMetrics.density
            val widthPx = (size * density).toInt()
            val heightPx = (size * density).toInt()
            rendered.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            rendered.layout(0, 0, widthPx, heightPx)
            fun bounds(view: View) = Rect(0, 0, view.width, view.height).also {
                (rendered as ViewGroup).offsetDescendantRectToMyCoords(view, it)
            }

            assertTrue("cover scales with the roomy compact widget", cover.width / density >= 68f)
            assertTrue("cover stays left of the title", bounds(cover).right < bounds(title).left)
            assertTrue("title clears the progress stack", bounds(title).bottom <= bounds(progress).top)
            assertTrue("ring clears the count", bounds(ring).bottom <= bounds(count).top)
            assertTrue("progress stays above the action", bounds(progress).bottom <= bounds(resume).top)
            assertTrue("action stays inside the card", bounds(resume).bottom <= heightPx)
            assertEquals("Childhood Friend of the Zenith", title.text.toString())
            assertEquals("Resume reading", action.text.toString())
            assertEquals(11.5f, action.textSize / context.resources.displayMetrics.scaledDensity, .1f)
            assertNotNull(resume.contentDescription)

            RemoteViews(context.packageName, layout).apply {
                setTextViewText(R.id.continue_bookplate_title, "Your next chapter")
                setTextViewText(R.id.continue_bookplate_resume_text, "Open library")
                setContentDescription(R.id.continue_root, "Open library")
                setViewVisibility(R.id.continue_bookplate_progress, View.GONE)
            }.reapply(context, rendered)
            assertEquals("Your next chapter", title.text.toString())
            assertEquals("Open library", action.text.toString())
            assertEquals(View.GONE, progress.visibility)
        }
    }

    @Test
    fun widgetRequestCarriesTheResumeKeyOnlyOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).setAction(ContinueReadingWidget.ACTION)
            .putExtra(ContinueReadingWidget.KEY, "manga:42")
        assertEquals("manga:42", intent.takeContinueReadingRequest()?.key)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertNull(intent.getStringExtra(ContinueReadingWidget.KEY))
        assertNull(intent.takeContinueReadingRequest())
        val empty = Intent(context, MainActivity::class.java).setAction(ContinueReadingWidget.ACTION)
        assertNull(empty.takeContinueReadingRequest()?.key)
        assertEquals(Intent.ACTION_MAIN, empty.action)
    }

    @Test
    fun largeAndTwoByTwoLayoutsInflateWithContentThenRefreshToEmpty() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tinyImage = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val portraitCover = Bitmap.createBitmap(240, 340, Bitmap.Config.ARGB_8888)
        val click = PendingIntent.getActivity(
            context, 17, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        for (layout in listOf(R.layout.continue_reading_widget, R.layout.continue_reading_widget_compact_legacy)) {
            val content = RemoteViews(context.packageName, layout).apply {
                setTextViewText(R.id.continue_title, "Childhood Friend of the Zenith and a long subtitle")
                setInt(R.id.continue_title, "setMaxLines", 2)
                setTextViewText(R.id.continue_status, "19 / 110 chapters read")
                setViewVisibility(R.id.continue_kind_container, View.GONE)
                setImageViewBitmap(R.id.continue_background, tinyImage)
                setImageViewBitmap(R.id.continue_progress, tinyImage)
                setImageViewBitmap(R.id.continue_arrow_background, tinyImage)
                setImageViewBitmap(R.id.continue_kind_background, tinyImage)
                if (layout == R.layout.continue_reading_widget_compact_legacy) {
                    setViewVisibility(R.id.continue_cover, View.GONE)
                    setViewVisibility(R.id.continue_label, View.GONE)
                } else setImageViewBitmap(R.id.continue_cover, portraitCover)
                setContentDescription(R.id.continue_root, "Resume reading")
                setOnClickPendingIntent(R.id.continue_root, click)
            }
            val rendered = content.apply(context, FrameLayout(context))
            assertNotNull(rendered.findViewById<View>(R.id.continue_cover))
            assertEquals("Childhood Friend of the Zenith and a long subtitle",
                rendered.findViewById<TextView>(R.id.continue_title).text.toString())
            if (layout == R.layout.continue_reading_widget_compact_legacy) {
                for ((width, height) in listOf(110 to 110, 160 to 176)) {
                    RemoteViews(context.packageName, layout).apply {
                        setInt(R.id.continue_title, "setMaxLines", if (width == 110) 1 else 2)
                        setViewVisibility(R.id.continue_status, if (width == 110) View.GONE else View.VISIBLE)
                    }.reapply(context, rendered)
                    val density = context.resources.displayMetrics.density
                    val widthPx = (width * density).toInt()
                    val heightPx = (height * density).toInt()
                    rendered.measure(
                        View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
                    )
                    rendered.layout(0, 0, widthPx, heightPx)
                    val title = rendered.findViewById<View>(R.id.continue_title)
                    val progress = rendered.findViewById<View>(R.id.continue_progress)
                    val status = rendered.findViewById<View>(R.id.continue_status)
                    val arrow = rendered.findViewById<View>(R.id.continue_arrow)
                    fun bounds(view: View) = Rect(0, 0, view.width, view.height).also {
                        (rendered as ViewGroup).offsetDescendantRectToMyCoords(view, it)
                    }
                    assertEquals("art is embedded in the rounded compact background", View.GONE,
                        rendered.findViewById<View>(R.id.continue_cover).visibility)
                    assertTrue("title stays inside the card", bounds(title).bottom <= heightPx)
                    assertTrue("progress stays inside the card", bounds(progress).bottom <= heightPx)
                    assertTrue("arrow stays inside the card", bounds(arrow).bottom <= heightPx)
                    assertTrue("progress clears the arrow", bounds(progress).right < bounds(arrow).left)
                    if (width == 110) assertTrue("one-line title clears the action", bounds(title).bottom <= bounds(arrow).top)
                    else assertTrue("status clears the meter", bounds(status).bottom <= bounds(progress).top)
                }
            }

            val empty = RemoteViews(context.packageName, layout).apply {
                setTextViewText(R.id.continue_title, "YOUR NEXT CHAPTER")
                setTextViewText(R.id.continue_status, "Pick up where you left off")
            }
            empty.reapply(context, rendered)
            assertEquals("YOUR NEXT CHAPTER", rendered.findViewById<TextView>(R.id.continue_title).text.toString())
            assertEquals("Pick up where you left off", rendered.findViewById<TextView>(R.id.continue_status).text.toString())
        }
    }
}
