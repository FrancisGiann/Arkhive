package mihon.readingapp.widget

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Catches views and RemoteViews actions that a launcher cannot inflate. */
@RunWith(AndroidJUnit4::class)
class ContinueReadingWidgetInflationTest {
    @Test
    fun largeAndTwoByTwoLayoutsInflateWithContentThenRefreshToEmpty() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tinyImage = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val portraitCover = Bitmap.createBitmap(240, 340, Bitmap.Config.ARGB_8888)
        val click = PendingIntent.getActivity(
            context, 17, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        for (layout in listOf(R.layout.continue_reading_widget, R.layout.continue_reading_widget_compact)) {
            val content = RemoteViews(context.packageName, layout).apply {
                setTextViewText(R.id.continue_title, "Childhood Friend of the Zenith and a long subtitle")
                setInt(R.id.continue_title, "setMaxLines", 2)
                setTextViewText(R.id.continue_status, "19 / 110 chapters read")
                setViewVisibility(R.id.continue_kind_container, View.GONE)
                setImageViewBitmap(R.id.continue_background, tinyImage)
                setImageViewBitmap(R.id.continue_progress, tinyImage)
                setImageViewBitmap(R.id.continue_arrow_background, tinyImage)
                setImageViewBitmap(R.id.continue_kind_background, tinyImage)
                setImageViewBitmap(R.id.continue_cover, portraitCover)
                setContentDescription(R.id.continue_root, "Resume reading")
                setOnClickPendingIntent(R.id.continue_root, click)
            }
            val rendered = content.apply(context, FrameLayout(context))
            assertNotNull(rendered.findViewById<View>(R.id.continue_cover))
            assertEquals("Childhood Friend of the Zenith and a long subtitle",
                rendered.findViewById<TextView>(R.id.continue_title).text.toString())
            if (layout == R.layout.continue_reading_widget_compact) {
                for ((width, height) in listOf(110 to 110, 160 to 176)) {
                    val density = context.resources.displayMetrics.density
                    val widthPx = (width * density).toInt()
                    val heightPx = (height * density).toInt()
                    rendered.measure(
                        View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
                    )
                    rendered.layout(0, 0, widthPx, heightPx)
                    val cover = rendered.findViewById<View>(R.id.continue_cover)
                    val status = rendered.findViewById<View>(R.id.continue_status)
                    val arrow = rendered.findViewById<View>(R.id.continue_arrow)
                    fun bounds(view: View) = Rect(0, 0, view.width, view.height).also {
                        (rendered as ViewGroup).offsetDescendantRectToMyCoords(view, it)
                    }
                    assertTrue("cover fits the two-column card", bounds(cover).right < bounds(status).left)
                    assertTrue("cover stays inside the card", bounds(cover).bottom <= heightPx)
                    assertTrue("reading status clears the arrow", bounds(status).bottom <= bounds(arrow).top)
                    if (height == 176) {
                        assertTrue("portrait art fills the roomy 2×2 card", cover.height > (80 * density).toInt())
                    }
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
