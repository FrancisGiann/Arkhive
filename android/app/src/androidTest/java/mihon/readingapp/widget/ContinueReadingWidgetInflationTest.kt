package mihon.readingapp.widget

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Catches views and RemoteViews actions that a launcher cannot inflate. */
@RunWith(AndroidJUnit4::class)
class ContinueReadingWidgetInflationTest {
    @Test
    fun largeAndTwoByTwoLayoutsInflateWithContentThenRefreshToEmpty() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tinyImage = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
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
                setImageViewResource(R.id.continue_cover, R.drawable.readingapp_icon)
                setContentDescription(R.id.continue_root, "Resume reading")
                setOnClickPendingIntent(R.id.continue_root, click)
            }
            val rendered = content.apply(context, FrameLayout(context))
            assertNotNull(rendered.findViewById<View>(R.id.continue_cover))
            assertEquals("Childhood Friend of the Zenith and a long subtitle",
                rendered.findViewById<TextView>(R.id.continue_title).text.toString())

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
