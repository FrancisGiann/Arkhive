package mihon.readingapp.novels

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebView
import android.graphics.Rect
import android.app.ActivityManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import androidx.test.core.app.ActivityScenario
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.*
import mihon.app.di.appGraph
import mihon.readingapp.books.BookAutoController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.regex.Pattern
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class NovelReaderRuntimeTest {
    private val novelInputTrace=Collections.synchronizedList(mutableListOf<String>())
    private data class PageViewport(val x:Int,val endX:Int,val width:Int,val height:Int)
    private class ScrollProbe {
        val scrollY=AtomicInteger(-1)
        val scrollX=AtomicInteger(-1)
        val end=AtomicInteger(-1)
        val endX=AtomicInteger(-1)
        val height=AtomicInteger(-1)
        val centerX=AtomicInteger(-1)
        val centerY=AtomicInteger(-1)
        @Volatile var visible=false
        @Volatile var focused=false
        @Volatile var lifecycle="unknown"
        @Volatile var automationMode="unknown"
        @Volatile var automationRunning=false
        @Volatile var continuousAvailable=false
        @Volatile var automationMessage="unknown"
        @Volatile var refreshSnapshot:(()->Unit)?=null
        val scrollHistory=Collections.synchronizedList(mutableListOf<Int>())
        fun diagnostic()="scrollX=${scrollX.get()},endX=${endX.get()},scrollY=${scrollY.get()},endY=${end.get()},height=${height.get()},visible=$visible,focus=$focused,"+
            "lifecycle=$lifecycle,mode=$automationMode,running=$automationRunning,continuousAvailable=$continuousAvailable,"+
            "message=$automationMessage"
    }
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private val device get()=UiDevice.getInstance(instrument)
    private fun findWeb(view:View):NovelReaderActivity.TextWebView? {
        if(view is NovelReaderActivity.TextWebView) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) findWeb(view.getChildAt(i))?.let {return it}
        return null
    }
    private fun modeRowDiagnostic(label:UiObject2?, row:UiObject2?):String =
        "label=${describeModeNode(label)},row=${describeModeNode(row)}"
    private fun describeModeNode(node:UiObject2?):String = runCatching { node?.let {
        "enabled=${it.isEnabled},clickable=${it.isClickable},checkable=${it.isCheckable},checked=${it.isChecked},"+
            "bounds=${it.visibleBounds.flattenToString()}"
    } ?: "missing" }.getOrDefault("stale")
    private suspend fun awaitStableContinuousModeRow():Pair<UiObject2,String> {
        var lastBounds:String?=null
        var stable=0
        var lastDiagnostic="label not found"
        var result:Pair<UiObject2,String>?=null
        withTimeoutOrNull(10_000) {
            while(result==null) {
                val label=device.findObject(By.text("Continuous scroll"))
                var row:UiObject2?=label
                var depth=0
                while(row!=null && depth<8 && !runCatching { row!!.isClickable }.getOrDefault(false)) {
                    val current=row ?: break
                    row=runCatching { current.parent }.getOrNull()
                    depth++
                }
                lastDiagnostic=modeRowDiagnostic(label,row)
                val bounds=runCatching {
                    row?.takeIf { it.isEnabled && it.isClickable }
                        ?.visibleBounds?.takeIf { it.width()>0 }?.flattenToString()
                }.getOrNull()
                if(row!=null && bounds!=null) {
                    if(bounds==lastBounds) stable++ else stable=0
                    lastBounds=bounds
                    if(stable>=2) result=row!! to lastDiagnostic
                } else { lastBounds=null;stable=0 }
                if(result==null) delay(100)
            }
        }
        return result ?: throw AssertionError("Continuous scroll mode row never became enabled, clickable, and stable: $lastDiagnostic")
    }
    private fun geometry(reader:ActivityScenario<NovelReaderActivity>):Triple<Int,Int,Int> {
        var result=Triple(-1,-1,-1)
        reader.onActivity {activity->findWeb(activity.window.decorView)?.let {web->if(web.visibility==View.VISIBLE) result=Triple(web.scrollY,web.end(),web.height)}}
        return result
    }
    private fun horizontalGeometry(reader:ActivityScenario<NovelReaderActivity>):Triple<Int,Int,Int> {
        var result=Triple(-1,-1,-1)
        reader.onActivity {activity->findWeb(activity.window.decorView)?.let {web->if(web.visibility==View.VISIBLE) result=Triple(web.scrollX,web.endX(),web.width)}}
        return result
    }
    private fun currentNovelChapterId(reader:ActivityScenario<NovelReaderActivity>):String {
        var id=""
        reader.onActivity {activity->
            val url=activity.javaClass.getDeclaredField("expectedUrl").apply {isAccessible=true}.get(activity) as String
            id=url.substringAfter("/chapter/").substringBefore('?')
        }
        return id
    }
    private fun tapNovelRatio(reader:ActivityScenario<NovelReaderActivity>,ratio:Float) {
        val point=IntArray(2)
        var target="unavailable"
        reader.onActivity {activity->val web=requireNotNull(findWeb(activity.window.decorView));web.getLocationOnScreen(point)
            point[0]+=(web.width*ratio).toInt();point[1]+=web.height/2
            target="${point[0]},${point[1]} web=${web.width}x${web.height} shown=${web.isShown} focus=${web.hasFocus()} url=${web.url}"}
        val started=android.os.SystemClock.uptimeMillis()
        device.click(point[0],point[1])
        novelInputTrace += "ratio=$ratio screen=${point[0]},${point[1]} target={$target} dispatchedAt=$started finishedAt=${android.os.SystemClock.uptimeMillis()}"
    }
    /** Read the visible paragraph only from the isolated authored fixture; production JS is restored immediately. */
    private fun visibleNovelPassage(
        reader:ActivityScenario<NovelReaderActivity>,
        expectedNovelId:String,
        expectedSourceId:String,
    ):String {
        var expectedChapterId=""
        var lastRaw="no JavaScript callback yet"
        try {
            reader.onActivity { activity ->
                val savedNovel=(activity.javaClass.getDeclaredField("novel\$delegate").apply {isAccessible=true}.get(activity)
                    as androidx.compose.runtime.State<*>).value as? SavedNovel
                assertEquals("Only the authored novel fixture may enable JavaScript for a read-only passage probe",
                    expectedNovelId,savedNovel?.id)
                assertEquals("The passage probe stays scoped to its registered fixture source",expectedSourceId,savedNovel?.sourceId)
                val web=requireNotNull(findWeb(activity.window.decorView))
                assertFalse("The production online reader keeps JavaScript disabled before passage measurement",web.settings.javaScriptEnabled)
                val current=(activity.javaClass.getDeclaredField("current\$delegate").apply {isAccessible=true}.get(activity)
                    as androidx.compose.runtime.State<*>).value as? ChapterState
                expectedChapterId=requireNotNull(current).id
                val uri=android.net.Uri.parse(activity.javaClass.getDeclaredField("expectedUrl").apply {isAccessible=true}.get(activity) as String)
                assertEquals("https",uri.scheme);assertEquals("arkhive.local",uri.host)
                assertEquals(listOf("chapter",expectedChapterId),uri.pathSegments)
                assertTrue(uri.getQueryParameter("render")?.toLongOrNull()!=null)
                web.settings.javaScriptEnabled=true
            }
            val deadline=android.os.SystemClock.uptimeMillis()+5_000
            while(android.os.SystemClock.uptimeMillis()<deadline) {
                val latch=java.util.concurrent.CountDownLatch(1)
                var raw:String?=null
                var expectedUrl=""
                reader.onActivity { activity ->
                    val current=(activity.javaClass.getDeclaredField("current\$delegate").apply {isAccessible=true}.get(activity)
                        as androidx.compose.runtime.State<*>).value as? ChapterState
                    assertEquals("The measured paragraph stays in the same authored chapter",expectedChapterId,current?.id)
                    expectedUrl=activity.javaClass.getDeclaredField("expectedUrl").apply {isAccessible=true}.get(activity) as String
                    val web=requireNotNull(findWeb(activity.window.decorView))
                    assertTrue("JavaScript is enabled only during this test-only fixture observation",web.settings.javaScriptEnabled)
                    web.evaluateJavascript(
                        """(()=>{const ps=Array.from(document.querySelectorAll('p'));const candidates=[];ps.forEach((p,index)=>{const range=document.createRange();range.selectNodeContents(p);for(const r of Array.from(range.getClientRects()))if(r.width>0&&r.height>0&&r.right>0&&r.left<innerWidth&&r.bottom>0&&r.top<innerHeight)candidates.push({index,text:p.innerText.trim(),top:r.top,left:r.left})});candidates.sort((a,b)=>a.top-b.top||a.left-b.left);const p=candidates[0];return JSON.stringify({base:document.baseURI,index:p?.index??-1,text:p?.text??''})})()""",
                    ) { value -> raw=value;latch.countDown() }
                }
                latch.await(1_500,java.util.concurrent.TimeUnit.MILLISECONDS)
                lastRaw=raw ?: "no JavaScript result before the bounded callback wait"
                val json=runCatching {
                    when(val value=org.json.JSONTokener(raw ?: "null").nextValue()) {
                        is org.json.JSONObject -> value
                        is String -> org.json.JSONObject(value)
                        else -> null
                    }
                }.getOrNull()
                if(json!=null && json.optInt("index",-1)>=0 && json.optString("text").isNotBlank()) {
                    val actual=android.net.Uri.parse(json.optString("base"))
                    val expected=android.net.Uri.parse(expectedUrl)
                    assertEquals("The measured text comes from the local fixture origin",expected.scheme,actual.scheme)
                    assertEquals("The measured text comes from the local fixture host",expected.host,actual.host)
                    assertEquals("The measured text comes from the current chapter",expected.pathSegments,actual.pathSegments)
                    assertEquals("The measured text comes from the current WebView render",expected.getQueryParameter("render"),actual.getQueryParameter("render"))
                    return "${json.getInt("index")}:${json.getString("text")}"
                }
                android.os.SystemClock.sleep(80)
            }
            throw AssertionError("No visible authored novel paragraph returned before timeout: $lastRaw")
        } finally {
            reader.onActivity { activity ->
                val web=requireNotNull(findWeb(activity.window.decorView))
                web.settings.javaScriptEnabled=false
                assertFalse("Every authored fixture passage probe restores the production JavaScript-disabled posture",web.settings.javaScriptEnabled)
            }
        }
    }

    private fun pageViewport(reader:ActivityScenario<NovelReaderActivity>):PageViewport {
        var result=PageViewport(-1,-1,-1,-1)
        reader.onActivity {activity->findWeb(activity.window.decorView)?.let {web->if(web.visibility==View.VISIBLE)
            result=PageViewport(web.scrollX,web.endX(),web.width,web.height)}}
        return result
    }
    private suspend fun awaitStablePageViewport(reader:ActivityScenario<NovelReaderActivity>):PageViewport {
        var previous:PageViewport?=null
        var stable=0
        var latest=PageViewport(-1,-1,-1,-1)
        withTimeout(10_000) {
            while(stable<3) {
                latest=pageViewport(reader)
                if(viewportSettled(reader) && latest.endX>0 && latest.width>0 && latest.height>0 && latest==previous) stable++ else stable=0
                previous=latest
                if(stable<3) delay(100)
            }
        }
        return latest
    }
    private fun viewportSettled(reader:ActivityScenario<NovelReaderActivity>):Boolean {
        var settled=false
        reader.onActivity {activity->
            val ready=(activity.javaClass.getDeclaredField("ready\$delegate").apply {isAccessible=true}.get(activity)
                as androidx.compose.runtime.State<*>).value==true
            val pending=activity.javaClass.getDeclaredField("viewportReflowPending").apply {isAccessible=true}.getBoolean(activity)
            settled=ready && !pending && findWeb(activity.window.decorView)?.visibility==View.VISIBLE
        }
        return settled
    }
    private fun assertPageProgressPreserved(before:PageViewport,after:PageViewport,stage:String) {
        val beforeProgress=before.x.toDouble()/before.endX
        val afterProgress=after.x.toDouble()/after.endX
        val onePage=maxOf(before.width.toDouble()/before.endX,after.width.toDouble()/after.endX)
        assertTrue("$stage should preserve normalized chapter position within a snapped page: before=$before($beforeProgress),after=$after($afterProgress)",
            kotlin.math.abs(afterProgress-beforeProgress)<=onePage*.51+.005)
        assertTrue("$stage should leave the current column aligned: before=$before,after=$after",
            after.x==after.endX || after.x%after.width<=2)
    }
    private fun assertVerticalProgressPreserved(before:Triple<Int,Int,Int>,after:Triple<Int,Int,Int>,stage:String) {
        val beforeProgress=before.first.toDouble()/before.second
        val afterProgress=after.first.toDouble()/after.second
        assertEquals("$stage should preserve normalized chapter position: before=$before($beforeProgress),after=$after($afterProgress)",
            beforeProgress,afterProgress,.01)
    }
    private fun observeScroll(reader:ActivityScenario<NovelReaderActivity>):ScrollProbe {
        val probe=ScrollProbe()
        reader.onActivity {activity->
            val web=requireNotNull(findWeb(activity.window.decorView))
            val auto=runCatching {
                val field=activity.javaClass.declaredFields.first {it.name=="auto\$delegate"}
                field.isAccessible=true
                (field.get(activity) as Lazy<*>).value as BookAutoController
            }.getOrNull()
            fun capture() {
                val location=IntArray(2);web.getLocationOnScreen(location)
                val y=web.scrollY
                val previousY=probe.scrollY.getAndSet(y)
                if(previousY!=y) synchronized(probe.scrollHistory) {
                    if(probe.scrollHistory.lastOrNull()!=y) probe.scrollHistory.add(y)
                }
                probe.scrollX.set(web.scrollX);probe.end.set(web.end());probe.endX.set(web.endX());probe.height.set(web.height)
                probe.centerX.set(location[0]+web.width/2);probe.centerY.set(location[1]+web.height/2)
                probe.visible=web.visibility==View.VISIBLE;probe.focused=activity.hasWindowFocus()
                probe.lifecycle=activity.lifecycle.currentState.name
                auto?.state?.let {state->
                    probe.automationMode=state.settings.mode.name
                    probe.automationRunning=state.running
                    probe.continuousAvailable=state.continuousAvailable
                    probe.automationMessage=state.message.take(120)
                }
            }
            probe.refreshSnapshot={capture()}
            val observer=web.viewTreeObserver
            if(observer.isAlive) observer.addOnScrollChangedListener(ViewTreeObserver.OnScrollChangedListener {capture()})
            capture()
        }
        return probe
    }
    private suspend fun ready(reader:ActivityScenario<NovelReaderActivity>) {
        withTimeout(20000) {while(geometry(reader).second<3000 || geometry(reader).third<200) delay(100)}
        reader.onActivity {activity->val web=findWeb(activity.window.decorView)!!;assertFalse(web.settings.javaScriptEnabled);assertFalse(web.settings.allowFileAccess);assertFalse(web.settings.allowContentAccess);assertTrue(web.settings.blockNetworkLoads)}
    }
    private suspend fun awaitStableVerticalGeometry(reader:ActivityScenario<NovelReaderActivity>):Triple<Int,Int,Int> {
        var previous:Triple<Int,Int,Int>?=null
        var stableObservations=0
        var settled=Triple(-1,-1,-1)
        withTimeout(10_000) {
            while(stableObservations<3) {
                settled=geometry(reader)
                if(viewportSettled(reader) && settled.second>3000 && settled.third>200 && settled==previous) stableObservations++
                else stableObservations=0
                previous=settled
                if(stableObservations<3) delay(100)
            }
        }
        return settled
    }
    private fun awaitNovelVisualState(reader:ActivityScenario<NovelReaderActivity>) {
        val drawn=java.util.concurrent.CountDownLatch(1)
        var requestId=0L
        reader.onActivity { activity->
            val web=requireNotNull(findWeb(activity.window.decorView))
            requestId=android.os.SystemClock.uptimeMillis()+1
            web.postVisualStateCallback(requestId,object:WebView.VisualStateCallback() {
                override fun onComplete(completedRequestId:Long) {
                    if(completedRequestId==requestId) drawn.countDown()
                }
            })
        }
        assertTrue("The online novel WebView reached a painted visual state",drawn.await(5,java.util.concurrent.TimeUnit.SECONDS))
        instrument.waitForIdleSync()
        android.os.SystemClock.sleep(750)
    }
    private fun readerWindowDiagnostics(reader:ActivityScenario<NovelReaderActivity>):String {
        var result="unavailable"
        reader.onActivity { activity ->
            val decor=activity.window.decorView
            val root=activity.javaClass.getDeclaredField("readerRoot").apply {isAccessible=true}.get(activity) as ViewGroup
            val web=requireNotNull(findWeb(decor))
            val location=IntArray(2)
            root.getLocationOnScreen(location)
            val webLocation=IntArray(2)
            web.getLocationOnScreen(webLocation)
            val insets=ViewCompat.getRootWindowInsets(decor)
            val system=insets?.getInsets(WindowInsetsCompat.Type.systemBars())
            val stable=insets?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            result="root=${root.measuredWidth}x${root.measuredHeight}@${location[0]},${location[1]}"+
                ",padding=${root.paddingLeft},${root.paddingTop},${root.paddingRight},${root.paddingBottom}"+
                ",web=${web.measuredWidth}x${web.measuredHeight}@${webLocation[0]},${webLocation[1]}"+
                ",scroll=${web.scrollX},${web.scrollY},end=${web.endX()},${web.end()},layerType=${web.layerType}"+
                ",decorUi=0x${decor.systemUiVisibility.toString(16)}"+
                ",barsVisible=${insets?.isVisible(WindowInsetsCompat.Type.statusBars())}/${insets?.isVisible(WindowInsetsCompat.Type.navigationBars())}"+
                ",systemInsets=${system?.left},${system?.top},${system?.right},${system?.bottom}"+
                ",stableInsets=${stable?.left},${stable?.top},${stable?.right},${stable?.bottom}"+
                ",window=${activity.window.attributes.width}x${activity.window.attributes.height}"+
                ",rootWindowInsets=${root.rootWindowInsets?.systemWindowInsetTop},${root.rootWindowInsets?.systemWindowInsetBottom}"
        }
        return result
    }

    private fun readerOverlayDiagnostics(reader:ActivityScenario<NovelReaderActivity>):String {
        var result="unavailable"
        reader.onActivity { activity->
            val web=requireNotNull(findWeb(activity.window.decorView))
            val root=activity.javaClass.getDeclaredField("readerRoot").apply {isAccessible=true}.get(activity) as ViewGroup
            result=(0 until root.childCount).joinToString("; ") { index->
                val child=root.getChildAt(index)
                "${child.javaClass.simpleName}=${child.left},${child.top},${child.right},${child.bottom},"+
                    "visible=${child.visibility==View.VISIBLE},alpha=${child.alpha},z=${child.z},web=${child===web}"
            }
        }
        return result
    }
    private fun assertReaderOverlaysCompact(reader:ActivityScenario<NovelReaderActivity>,stage:String) {
        var detail="unavailable"
        var overlayCount=-1
        var overlaysCompact=false
        reader.onActivity { activity->
            val web=requireNotNull(findWeb(activity.window.decorView))
            val root=activity.javaClass.getDeclaredField("readerRoot").apply {isAccessible=true}.get(activity) as ViewGroup
            val overlays=(0 until root.childCount).map(root::getChildAt).filter {it!==web && it.visibility==View.VISIBLE}
            overlayCount=overlays.size
            overlaysCompact=overlays.all {it.height*2<web.height}
            detail=(0 until root.childCount).joinToString("; ") { index->
                val child=root.getChildAt(index)
                "${child.javaClass.simpleName}=${child.left},${child.top},${child.right},${child.bottom},"+
                    "visible=${child.visibility==View.VISIBLE},alpha=${child.alpha},z=${child.z},web=${child===web}"
            }
        }
        assertEquals("The stable reader has one WebView and two chrome overlays at $stage: $detail",2,overlayCount)
        assertTrue("Chrome overlays stay compact and leave the center of the publication visible at $stage: $detail",overlaysCompact)
    }
    private suspend fun readyPages(reader:ActivityScenario<NovelReaderActivity>) {
        var last=Triple(-1,-1,-1)
        withTimeout(20000) {
            while(last.second<1000 || last.third<200) { last=horizontalGeometry(reader);delay(100) }
        }
        awaitStablePageViewport(reader)
        reader.onActivity {activity->
            val web=findWeb(activity.window.decorView)!!
            assertFalse("Pages layout must hide its horizontal scrollbar",web.isHorizontalScrollBarEnabled)
            assertFalse("Pages layout must hide its vertical scrollbar",web.isVerticalScrollBarEnabled)
            assertFalse(web.settings.javaScriptEnabled)
            assertFalse(web.settings.allowFileAccess)
            assertFalse(web.settings.allowContentAccess)
            assertTrue(web.settings.blockNetworkLoads)
        }
    }
    private suspend fun awaitScroll(probe:ScrollProbe,before:Int,stage:String) {
        val moved=withTimeoutOrNull(6000) {while(probe.scrollY.get()<=before+20) delay(100);true}==true
        if(!moved) throw AssertionError("$stage: expected scroll beyond ${before+20}; ${probe.diagnostic()}")
    }
    private suspend fun awaitAutomationMode(
        probe:ScrollProbe,
        expectedMode:String,
        expectedRunning:Boolean,
        stage:String,
        row:UiObject2?=null,
    ) {
        val reached=withTimeoutOrNull(6000) {
            while(true) {
                instrument.runOnMainSync { probe.refreshSnapshot?.invoke() }
                if(probe.automationMode==expectedMode && probe.automationRunning==expectedRunning) return@withTimeoutOrNull true
                delay(50)
            }
            false
        }==true
        if(!reached) throw AssertionError("$stage: expected mode=$expectedMode running=$expectedRunning; "+
            "${probe.diagnostic()},modeRow=${describeModeNode(row)}")
    }
    private fun pageTurnDiagnostic(reader:ActivityScenario<NovelReaderActivity>,stage:String,target:Int?,stride:Int,tapPoint:Pair<Int,Int>?=null):String {
        var snapshot="activity unavailable"
        reader.onActivity { activity->
            val web=findWeb(activity.window.decorView)
            val auto=runCatching {
                val field=activity.javaClass.getDeclaredField("auto\$delegate").apply {isAccessible=true}
                (field.get(activity) as Lazy<*>).value as BookAutoController
            }.getOrNull()
            val state=auto?.state
            val layout=runCatching {activity.javaClass.getDeclaredField("readingLayout").apply {isAccessible=true}.get(activity)}.getOrNull()
            val ready=runCatching {activity.javaClass.getDeclaredField("ready").apply {isAccessible=true}.getBoolean(activity)}.getOrNull()
            val bounds=android.graphics.Rect().also {web?.getGlobalVisibleRect(it)}
            snapshot="actualX=${web?.scrollX},endX=${web?.endX()},width=${web?.width},"+
                "actualY=${web?.scrollY},endY=${web?.end()},height=${web?.height},"+
                "targetX=$target,stride=$stride,layout=$layout,ready=$ready,webBounds=$bounds,tapPoint=$tapPoint,"+
                "focus=${activity.hasWindowFocus()},lifecycle=${activity.lifecycle.currentState},"+
                "mode=${state?.settings?.mode},running=${state?.running},message=${state?.message}"
        }
        return "$stage: $snapshot"
    }
    private suspend fun awaitPageTurn(
        reader:ActivityScenario<NovelReaderActivity>,
        target:Int,
        stride:Int,
        stage:String,
    ) {
        try {
            withTimeout(6000) {while(horizontalGeometry(reader).first!=target) delay(50)}
        } catch(error:TimeoutCancellationException) {
            throw AssertionError(pageTurnDiagnostic(reader,stage,target,stride),error)
        }
    }
    private suspend fun awaitVerticalMotion(
        reader:ActivityScenario<NovelReaderActivity>,
        before:Int,
        stage:String,
        tapPoint:Pair<Int,Int>?=null,
    ) {
        try {
            withTimeout(6000) {while(geometry(reader).first<=before+40) delay(100)}
        } catch(error:TimeoutCancellationException) {
            throw AssertionError("${pageTurnDiagnostic(reader,stage,null,0,tapPoint)}; expectedY>${before+40}",error)
        }
    }
    private fun tapCenter(probe:ScrollProbe) {
        require(probe.centerX.get()>=0 && probe.centerY.get()>=0) {"The reader viewport was not observed."}
        device.click(probe.centerX.get(),probe.centerY.get())
    }
    private fun tapNovelCenter(reader:ActivityScenario<NovelReaderActivity>) {
        val chromeWasVisible=device.hasObject(By.text("Aa"))
        val point=IntArray(2)
        reader.onActivity {activity->
            val web=requireNotNull(findWeb(activity.window.decorView))
            web.getLocationOnScreen(point)
            point[0]+=web.width/2
            point[1]+=web.height/2
        }
        device.click(point[0],point[1])
        assertTrue("The actual center tap must toggle the reader controls at (${point[0]},${point[1]})",
            device.wait(if(chromeWasVisible) Until.gone(By.text("Aa")) else Until.hasObject(By.text("Aa")),5_000))
    }
    private suspend fun assertChapterPercentAtBottom(
        reader: ActivityScenario<NovelReaderActivity>,
        currentGeometry: () -> Triple<Int,Int,Int>,
    ) {
        var expected = "unknown"
        var geometry = Triple(-1,-1,-1)
        var label: UiObject2? = null
        val matched = withTimeoutOrNull(5_000) {
            while (label == null) {
                geometry = currentGeometry()
                if (geometry.second > 0) {
                    val progress = (geometry.first.toDouble() / geometry.second).coerceIn(0.0, 1.0)
                    expected = "Chapter progress, ${(progress * 100).toInt()} percent"
                    label = device.findObject(By.desc(expected))
                }
                if (label == null) delay(50)
            }
            true
        } == true
        if (!matched) {
            var actualViewport = "unavailable"
            reader.onActivity { activity ->
                val web = findWeb(activity.window.decorView)
                actualViewport = web?.let { "scrollX=${it.scrollX},endX=${it.endX()},scrollY=${it.scrollY},endY=${it.end()},size=${it.width}x${it.height}" } ?: "missing WebView"
            }
            val actualLabels = device.findObjects(By.desc(Pattern.compile("^Chapter progress, \\d{1,3} percent$")))
                .joinToString { "${it.contentDescription}@${it.visibleBounds.flattenToString()}" }
            throw AssertionError("Chapter progress did not settle to visible geometry: expected=$expected,geometry=$geometry,"+
                "viewport=$actualViewport,visibleLabels=$actualLabels")
        }
        val visibleLabel = requireNotNull(label)
        assertTrue("Progress percentage should remain at the bottom of the reader: ${visibleLabel.visibleBounds}",
            visibleLabel.visibleBounds.bottom>device.displayHeight*3/4)
    }
    private fun assertAnyChapterPercentAtBottom() {
        val label=requireNotNull(device.wait(Until.findObject(By.desc(Pattern.compile("^Chapter progress, \\d{1,3} percent$"))),5_000)) {
            "The chapter-relative percentage disappeared from the reader"
        }
        assertTrue("Progress percentage should remain at the bottom of the reader: ${label.visibleBounds}",
            label.visibleBounds.bottom>device.displayHeight*3/4)
    }
    private fun assertQuietChapterProgressOnly(reader:ActivityScenario<NovelReaderActivity>) {
        assertAnyChapterPercentAtBottom()
        assertFalse("Quiet chapter progress has no permanent auto-read status text",device.hasObject(By.textContains("Auto read")))
        assertFalse("Quiet chapter progress has no permanent play or pause button",
            device.hasObject(By.desc("Resume auto read")) || device.hasObject(By.desc("Pause auto read")))
        val label=requireNotNull(device.findObject(By.desc(Pattern.compile("^Chapter progress, \\d{1,3} percent$"))))
        var webBounds=android.graphics.Rect()
        instrument.runOnMainSync {
            val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<NovelReaderActivity>().single()
            requireNotNull(findWeb(activity.window.decorView)).getGlobalVisibleRect(webBounds)
        }
        assertTrue("The content viewport ends before the quiet progress row: viewport=$webBounds,label=${label.visibleBounds}",
            webBounds.bottom<=label.visibleBounds.top)
    }
    private fun tapForward(reader:ActivityScenario<NovelReaderActivity>):Pair<Int,Int> {
        val target=IntArray(2)
        reader.onActivity {activity->val web=findWeb(activity.window.decorView)!!;web.getLocationOnScreen(target);target[0]+=(web.width*.9f).toInt();target[1]+=web.height/2}
        device.click(target[0],target[1])
        return target[0] to target[1]
    }
    private fun tapBackward(reader:ActivityScenario<NovelReaderActivity>) {
        val target=IntArray(2)
        reader.onActivity {activity->val web=findWeb(activity.window.decorView)!!;web.getLocationOnScreen(target);target[0]+=(web.width*.1f).toInt();target[1]+=web.height/2}
        device.click(target[0],target[1])
    }
    private fun click(text:String) {requireNotNull(device.wait(Until.findObject(By.text(text)),10000)){"Missing UI text: $text"}.click()}
    private fun clickDescription(description:String) {requireNotNull(device.wait(Until.findObject(By.desc(description)),10000)){"Missing UI description: $description"}.click()}
    private fun clickContaining(text:String) {requireNotNull(device.wait(Until.findObject(By.textContains(text)),10000)){"Missing UI text containing: $text"}.click()}
    private fun captureFailureEvidence(label:String,details:String) {
        val dir=File(context.cacheDir,"qa-0.4.3-failure-evidence").apply {mkdirs()}
        val stem="$label-${android.os.SystemClock.uptimeMillis()}"
        File(dir,"$stem.txt").writeText("package=${device.currentPackageName}\n$details\nscrollables="+
            device.findObjects(By.scrollable(true)).joinToString {runCatching {it.visibleBounds.flattenToString()}.getOrDefault("stale")})
        runCatching {device.dumpWindowHierarchy(File(dir,"$stem-hierarchy.xml"))}
        runCatching {device.takeScreenshot(File(dir,"$stem.png"))}
    }
    private fun clickAutoReadFromAppearancePanel() {
        fun failWithEvidence(message:String,dialog:Rect?=null,allScrollables:List<UiObject2> = emptyList()):Nothing {
            val dir=File(context.cacheDir,"qa-0.4.3-auto-read-panel").apply {mkdirs()}
            val hierarchy=File(dir,"hierarchy.xml")
            val screenshot=File(dir,"appearance-panel.png")
            runCatching {device.dumpWindowHierarchy(hierarchy)}
            runCatching {device.takeScreenshot(screenshot)}
            val scrollState=allScrollables.joinToString {runCatching {it.visibleBounds.flattenToString()}.getOrDefault("stale")}
            val xml=runCatching {hierarchy.readText().take(10_000)}.getOrDefault("unavailable")
            throw AssertionError("$message; dialog=$dialog; scrollables=$scrollState; screenshot=${screenshot.absolutePath}; hierarchy=${hierarchy.absolutePath}; $xml")
        }
        val title=device.wait(Until.findObject(By.text("Reading layout & appearance")),5_000)
            ?: failWithEvidence("The shared appearance dialog title is missing")
        val titleBounds=title.visibleBounds
        var ancestor:UiObject2?=title
        var dialogBounds:Rect?=null
        for(depth in 0 until 12) {
            val parent=runCatching {ancestor?.parent}.getOrNull() ?: break
            ancestor=parent
            val bounds=runCatching {parent.visibleBounds}.getOrNull() ?: break
            if(bounds.contains(titleBounds) && bounds.width()>=device.displayWidth*3/4 &&
                bounds.height() in (device.displayHeight/3)..(device.displayHeight*4/5)) {
                dialogBounds=bounds
                break
            }
        }
        val panel=dialogBounds ?: failWithEvidence("Could not resolve the visible appearance dialog bounds from its title")
        fun panelScrollables():List<UiObject2> = device.findObjects(By.scrollable(true)).filter {node->
            runCatching {
                val bounds=node.visibleBounds
                panel.contains(bounds) && bounds.width()>=panel.width()*3/4 &&
                    bounds.height()>=panel.height()/3 && bounds.top>=titleBounds.bottom
            }.getOrDefault(false)
        }
        repeat(16) {
            val action=device.findObject(By.text("Auto read"))
            if(action!=null && panel.contains(action.visibleBounds)) {action.click();return}
            val scoped=panelScrollables()
            val scroller=scoped.maxByOrNull {runCatching {it.visibleBounds.height()*it.visibleBounds.width()}.getOrDefault(0)}
                ?: failWithEvidence("The appearance dialog exposes no scrollable node within its own bounds",panel,
                    device.findObjects(By.scrollable(true)))
            scroller.scroll(Direction.DOWN,.18f,350)
            android.os.SystemClock.sleep(180)
        }
        val remaining=device.findObjects(By.scrollable(true))
        failWithEvidence("Auto read was not reachable after bounded short scrolls of the dialog's actual scrollable content",
            panel,remaining)
    }
    private suspend fun renderedNovelAppearance(
        reader:ActivityScenario<NovelReaderActivity>,stage:String,expectedNovelId:String,expectedFixtureId:String,
    ):org.json.JSONObject {
        var lastRaw="no JavaScript callback yet"
        var lastParsed="no parsed CSS yet"
        var lastReaderState="activity not inspected"
        var expectedDocumentUrl=""
        var expectedChapterId=""
        fun currentFixtureUrl(activity:NovelReaderActivity):String {
            val current=(activity.javaClass.getDeclaredField("current\$delegate").apply {isAccessible=true}.get(activity)
                as androidx.compose.runtime.State<*>).value as? ChapterState
            assertEquals("The current rendered chapter belongs to this authored title",expectedNovelId,current?.novelId)
            val chapterId=requireNotNull(current).id
            if(expectedChapterId.isNotEmpty()) assertEquals("A CSS sample never crosses to another chapter",expectedChapterId,chapterId)
            else expectedChapterId=chapterId
            val url=activity.javaClass.getDeclaredField("expectedUrl").apply {isAccessible=true}.get(activity) as String
            val uri=android.net.Uri.parse(url)
            assertEquals("The current fixture document has the local isolated origin","https",uri.scheme)
            assertEquals("The current fixture document has the local isolated host","arkhive.local",uri.host)
            assertEquals("The current fixture URL is bound to the displayed chapter",listOf("chapter",chapterId),uri.pathSegments)
            assertTrue("The generated base URL includes the reader render identity",uri.getQueryParameter("render")?.toLongOrNull()!=null)
            return url
        }
        try {
            reader.onActivity {activity->
                val savedNovel=(activity.javaClass.getDeclaredField("novel\$delegate").apply {isAccessible=true}.get(activity)
                    as androidx.compose.runtime.State<*>).value as? SavedNovel
                assertEquals("Only the uniquely authored local fixture may enable JavaScript for a read-only CSS probe",
                    expectedNovelId,savedNovel?.id)
                assertEquals("The CSS probe remains scoped to its registered authored fixture source",expectedFixtureId,savedNovel?.sourceId)
                expectedDocumentUrl=currentFixtureUrl(activity)
                val web=requireNotNull(findWeb(activity.window.decorView))
                assertFalse("The production reader keeps JavaScript disabled before a test-only observation",web.settings.javaScriptEnabled)
                assertFalse(web.settings.allowFileAccess);assertFalse(web.settings.allowContentAccess);assertTrue(web.settings.blockNetworkLoads)
                // Do not reload: observe only the already-sanitized, self-authored local fixture document.
                web.settings.javaScriptEnabled=true
            }
            return withTimeout(5_000) {
                while(true) {
                    val result=CompletableDeferred<String?>()
                    reader.onActivity {activity->
                        val web=requireNotNull(findWeb(activity.window.decorView))
                        assertTrue("Only the explicit fixture observation window enables JavaScript",web.settings.javaScriptEnabled)
                        expectedDocumentUrl=currentFixtureUrl(activity)
                        val ready=runCatching {
                            (activity.javaClass.getDeclaredField("ready\$delegate").apply {isAccessible=true}.get(activity)
                                as androidx.compose.runtime.State<*>).value
                        }.getOrNull()
                        val layout=runCatching {
                            (activity.javaClass.getDeclaredField("readingLayout\$delegate").apply {isAccessible=true}.get(activity)
                                as androidx.compose.runtime.State<*>).value
                        }.getOrNull()
                        val reflow=runCatching {
                            activity.javaClass.getDeclaredField("viewportReflowPending").apply {isAccessible=true}.getBoolean(activity)
                        }.getOrNull()
                        lastReaderState="ready=$ready,layout=$layout,reflowPending=$reflow,"+
                            "geometry=(x=${web.scrollX},y=${web.scrollY},endX=${web.endX()},endY=${web.end()},width=${web.width},height=${web.height}),"+
                            "url=${web.url},visible=${web.visibility==View.VISIBLE},focused=${activity.hasWindowFocus()}"
                        web.evaluateJavascript(
                            """JSON.stringify((()=>{const b=document.body,h=document.documentElement,s=getComputedStyle(b),rect=e=>{const r=e.getBoundingClientRect();return{x:r.x,y:r.y,w:r.width,h:r.height}},ps=[...document.querySelectorAll('p')];return{base:document.baseURI,font:s.fontSize,line:s.lineHeight,margin:s.paddingLeft,paper:s.backgroundColor,viewport:{width:innerWidth,height:innerHeight,vw:visualViewport?visualViewport.width:null,vh:visualViewport?visualViewport.height:null,scale:visualViewport?visualViewport.scale:null},html:{cw:h.clientWidth,sw:h.scrollWidth,ch:h.clientHeight,sh:h.scrollHeight},body:{...rect(b),display:s.display,columns:s.columnCount,columnWidth:s.columnWidth,columnGap:s.columnGap,overflow:s.overflow,color:s.color},scroll:{x:scrollX,y:scrollY},markers:ps.filter(p=>p.innerText.includes('Original page marker')).map(p=>({text:p.innerText.trim(),...rect(p),visibility:getComputedStyle(p).visibility,opacity:getComputedStyle(p).opacity})).slice(0,12)}})())""",
                        ) { value -> result.complete(value) }
                    }
                    val raw=withTimeoutOrNull(1_500){result.await()}
                    lastRaw=raw ?: "no JavaScript result before the bounded callback wait"
                    val parsed=runCatching {
                        when(val value=org.json.JSONTokener(raw?:"null").nextValue()) {
                            is org.json.JSONObject -> value
                            is String -> org.json.JSONObject(value)
                            else -> null
                        }
                    }.getOrNull()
                    lastParsed=parsed?.toString() ?: "invalid or absent CSS object"
                    if(parsed!=null && parsed.optString("font").isNotBlank()) {
                        val measuredBase=android.net.Uri.parse(parsed.optString("base"))
                        val expectedBase=android.net.Uri.parse(expectedDocumentUrl)
                        assertEquals("Computed styles come from the local isolated origin","https",measuredBase.scheme)
                        assertEquals("Computed styles come from the local fixture host","arkhive.local",measuredBase.host)
                        assertEquals("Computed styles belong to the displayed fixture chapter",expectedBase.pathSegments,measuredBase.pathSegments)
                        if(expectedBase.getQueryParameter("render")!=measuredBase.getQueryParameter("render")) {
                            lastParsed="discarded stale-render CSS sample: expected=$expectedBase actual=$measuredBase"
                            delay(80)
                            continue
                        }
                        return@withTimeout parsed
                    }
                    delay(80)
                }
                error("Rendered reader appearance probe did not return valid CSS")
            }
        } catch(error:TimeoutCancellationException) {
            throw AssertionError("Timed out after the unchanged 5s budget reading online appearance stage '$stage'; "+
                "lastRaw=$lastRaw; lastParsed=$lastParsed; $lastReaderState",error)
        } finally {
            reader.onActivity {activity->
                val web=requireNotNull(findWeb(activity.window.decorView))
                web.settings.javaScriptEnabled=false
                assertFalse("Every authored fixture CSS observation restores the production JavaScript-disabled posture",web.settings.javaScriptEnabled)
            }
        }
    }
    private fun openNovelAutoReadOptions(reader:ActivityScenario<NovelReaderActivity>) {
        if(!device.hasObject(By.text("Aa"))) tapNovelCenter(reader)
        click("Aa")
        clickAutoReadFromAppearancePanel()
    }
    private suspend fun setVisibleSpeed(reader:ActivityScenario<NovelReaderActivity>,fraction: Float): Int {
        openNovelAutoReadOptions(reader)
        val slider = requireNotNull(device.wait(Until.findObject(By.desc("Continuous scroll speed")), 10000))
        val bounds = slider.visibleBounds
        device.click(bounds.left + (bounds.width() * fraction).toInt(), bounds.centerY())
        val expected = if (fraction < .5f) 16..60 else 190..240
        var speed = -1
        var lastLabel = "missing"
        withTimeout(5_000) {
            while (speed !in expected) {
                lastLabel = device.findObject(By.textContains("Scroll speed ·"))?.text ?: "missing"
                speed = Regex("Scroll speed · (\\d+)").find(lastLabel)?.groupValues?.get(1)?.toInt() ?: -1
                if (speed !in expected) delay(50)
            }
        }
        assertTrue("The visible speed label settles after the slider tap: $lastLabel", speed in expected)
        click("Done")
        return speed
    }
    private suspend fun measureContinuousRun(reader:ActivityScenario<NovelReaderActivity>,probe:ScrollProbe): Int {
        openNovelAutoReadOptions(reader)
        val before = probe.scrollY.get()
        click("Start auto read")
        delay(850)
        val after = probe.scrollY.get()
        assertAnyChapterPercentAtBottom()
        assertFalse("Quiet continuous playback has no permanent Pause control",device.hasObject(By.desc("Pause auto read")))
        tapCenter(probe)
        assertTrue("A content touch pauses quiet playback and reveals accessible Resume",
            device.wait(Until.hasObject(By.desc("Resume auto read")), 5_000))
        awaitAutomationMode(probe,"CONTINUOUS",false,"A content touch pauses the measured continuous run")
        val settled = probe.scrollY.get()
        delay(300)
        assertEquals("A content touch stops movement after its touch-up", settled, probe.scrollY.get())
        return after - before
    }
    private fun restoreAutomation(prefs: android.content.SharedPreferences, keys: Set<String>, saved: Map<String, *>) {
        prefs.edit().apply {
            keys.forEach(::remove)
            saved.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Float -> putFloat(key, value)
                    else -> Unit
                }
            }
        }.commit()
    }

    @Test fun readerLoadingEmptyErrorAndContentStatesRenderInsideMeasuredChrome() = runBlocking {
        val gate = CompletableDeferred<String>()
        val retryCount = AtomicInteger()
        val emptyResponseCount = AtomicInteger()
        val fixtureId = "qa.novels.status-${java.util.UUID.randomUUID()}"
        val chapterBody = (1..90).joinToString("") {
            "<p>Status fixture paragraph $it. The chapter remains readable after the loading surface leaves.</p>"
        }
        val source = object : NovelSource {
            override val id = fixtureId
            override val name = "Reader status fixture"
            override val site = "https://fixture.invalid"
            override suspend fun catalog(page: Int, search: String, genre: String?) =
                NovelCatalogPage(emptyList(), null, "Authored reader status fixture")
            override suspend fun detail(path: String) = SourceNovel(
                path = path,
                name = name,
                // Keep each test novel to one chapter so normal (non-incognito) reader
                // navigation has no adjacent chapter to speculatively preload.
                chapters = listOf(
                    when (path) {
                        "status-delayed" -> NovelChapter("delayed", "Delayed chapter")
                        "status-empty" -> NovelChapter("empty", "Empty chapter")
                        "status-retry" -> NovelChapter("retry", "Retry chapter")
                        else -> error("Unexpected status fixture novel: $path")
                    },
                ),
                provenanceUrl = site,
                rights = "Authored instrumentation fixture",
            )
            override suspend fun chapter(path: String): String = when (path) {
                "delayed" -> gate.await()
                "empty" -> if (emptyResponseCount.getAndIncrement() == 0) "  \n  " else chapterBody
                "retry" -> if (retryCount.getAndIncrement() == 0) error("Controlled status error") else chapterBody
                else -> error("Unexpected fixture chapter: $path")
            }
        }
        val repo = context.appGraph.novelRepository
        val wasPaused = repo.state.value.paused
        val novelIds = mutableListOf<String>()
        repo.sources.fixtures[fixtureId] = source
        try {
            repo.pause(true)
            val delayedNovel = repo.load(fixtureId, "status-delayed").also { novelIds += it.id }
            val delayedChapter = repo.chapters(delayedNovel.id).single()
            val emptyNovel = repo.load(fixtureId, "status-empty").also { novelIds += it.id }
            val emptyChapter = repo.chapters(emptyNovel.id).single()
            val retryNovel = repo.load(fixtureId, "status-retry").also { novelIds += it.id }
            val retryChapter = repo.chapters(retryNovel.id).single()

            suspend fun assertStatusGeometry(label: UiObject2, reader: ActivityScenario<NovelReaderActivity>) {
                val rootBounds = Rect()
                val headerBounds = Rect()
                val footerBounds = Rect()
                var footerHeight = 0
                reader.onActivity { activity ->
                    val root = activity.javaClass.getDeclaredField("readerRoot").apply { isAccessible = true }
                        .get(activity) as ViewGroup
                    root.getGlobalVisibleRect(rootBounds)
                    root.getChildAt(2).getGlobalVisibleRect(headerBounds)
                    val footer = root.getChildAt(3)
                    footerHeight = footer.height
                    footer.getGlobalVisibleRect(footerBounds)
                }
                val bounds = label.visibleBounds
                assertTrue("Status content is placed below the measured header: status=$bounds header=$headerBounds", bounds.top >= headerBounds.bottom)
                val contentBottom = if (footerHeight > 0) footerBounds.top else rootBounds.bottom
                assertTrue("Status content is placed above the measured footer and safe root edge: status=$bounds footer=$footerBounds root=$rootBounds",
                    bounds.bottom <= contentBottom)
            }

            ActivityScenario.launch<NovelReaderActivity>(
                NovelReaderActivity.intent(context, delayedNovel.id, delayedChapter.id),
            ).use { reader ->
                val opening = requireNotNull(device.wait(Until.findObject(By.text("Opening chapter…")), 10_000))
                assertStatusGeometry(opening, reader)
                assertTrue("Capture the rendered delayed-loading surface", device.takeScreenshot(File(context.cacheDir, "qa-reader-status-loading.png")))
                reader.onActivity { activity ->
                    assertEquals(View.INVISIBLE, findWeb(activity.window.decorView)?.visibility)
                }
                gate.complete(chapterBody)
                withTimeout(20_000) {
                    while (readerStatusViewVisible(reader) || geometry(reader).second <= 3_000) delay(100)
                }
                awaitNovelVisualState(reader)
                assertFalse("Loaded content removes the status overlay", readerStatusViewVisible(reader))
                val loadedGeometry = geometry(reader)
                assertTrue("The authored chapter is actually rendered in the WebView", loadedGeometry.second > 3_000 && loadedGeometry.third > 200)
                assertTrue("Capture the loaded chapter after the status overlay leaves", device.takeScreenshot(File(context.cacheDir, "qa-reader-status-content.png")))
                val headerVisible = device.hasObject(By.text("Aa"))
                tapNovelCenter(reader)
                assertEquals("A center tap after loading only toggles chrome and preserves the content viewport",
                    loadedGeometry, geometry(reader))
                assertEquals("The center tap toggles the reader header", !headerVisible, device.hasObject(By.text("Aa")))
            }

            ActivityScenario.launch<NovelReaderActivity>(
                NovelReaderActivity.intent(context, emptyNovel.id, emptyChapter.id),
            ).use { reader ->
                val emptyResponse = requireNotNull(device.wait(Until.findObject(By.text("The source returned no readable chapter text.")), 15_000))
                val retry = requireNotNull(device.findObject(By.text("Retry chapter")))
                assertStatusGeometry(emptyResponse, reader)
                assertStatusGeometry(retry, reader)
                assertTrue("Capture the rendered no-readable-text error for an empty source response", device.takeScreenshot(File(context.cacheDir, "qa-reader-status-empty-response.png")))
                reader.onActivity { activity ->
                    assertEquals(View.INVISIBLE, findWeb(activity.window.decorView)?.visibility)
                }
                retry.click()
                withTimeout(20_000) {
                    while (readerStatusViewVisible(reader) || geometry(reader).second <= 3_000) delay(100)
                }
                awaitNovelVisualState(reader)
                assertFalse("Retrying an empty source response clears the error once readable text loads", readerStatusViewVisible(reader))
            }

            ActivityScenario.launch<NovelReaderActivity>(
                NovelReaderActivity.intent(context, retryNovel.id, retryChapter.id),
            ).use { reader ->
                val error = requireNotNull(device.wait(Until.findObject(By.text("Controlled status error")), 15_000))
                val retry = requireNotNull(device.findObject(By.text("Retry chapter")))
                assertStatusGeometry(error, reader)
                assertStatusGeometry(retry, reader)
                assertTrue("Capture the rendered chapter error and retry action", device.takeScreenshot(File(context.cacheDir, "qa-reader-status-error.png")))
                retry.click()
                withTimeout(20_000) {
                    while (readerStatusViewVisible(reader) || geometry(reader).second <= 3_000) delay(100)
                }
                awaitNovelVisualState(reader)
                assertFalse("A successful retry removes the error overlay", readerStatusViewVisible(reader))
                assertTrue("The retried chapter is rendered", geometry(reader).second > 3_000)
            }
        } finally {
            for (novelId in novelIds) repo.remove(novelId)
            repo.sources.fixtures.remove(fixtureId)
            repo.pause(wasPaused)
        }
    }

    private fun readerStatusViewVisible(reader: ActivityScenario<NovelReaderActivity>): Boolean {
        var visible = false
        reader.onActivity { activity ->
            val root = activity.javaClass.getDeclaredField("readerRoot").apply { isAccessible = true }
                .get(activity) as ViewGroup
            visible = root.getChildAt(1).visibility == View.VISIBLE
        }
        return visible
    }

    @Test fun renderedTextRestoresProgressBookmarkAndPausesOnTouchBackgroundAndChapter():Unit=runBlocking {
        val fixture=OriginalNovelFixture();val repo=context.appGraph.novelRepository
        val readerPreferences=context.getSharedPreferences("arkhive_book_reader",0)
        val oldPrivate=context.appGraph.basePreferences.incognitoMode.get();val oldMotion=context.appGraph.uiPreferences.arkhiveReducedMotion.get()
        val automationPreferences=context.getSharedPreferences("arkhive_book_automation",0)
        val automationKeys=setOf("online-novel_mode","online-novel_speed","online-novel_interval")
        val previousAutomation=automationPreferences.all.filterKeys { it in automationKeys }
        val previousIdleTimeout=Configurator.getInstance().waitForIdleTimeout
        val originalPaused=repo.state.value.paused
        var id:String?=null
        var oldReturnChapter:String?=null;var oldReturnProgress:Float?=null
        var hadReturnChapter=false;var hadReturnProgress=false
        repo.sources.fixtures[fixture.id]=fixture.source
        context.appGraph.basePreferences.incognitoMode.set(false);context.appGraph.uiPreferences.arkhiveReducedMotion.set(false)
        try {
            // Continuous WebView scrolling emits accessibility updates. Avoid UiAutomator's
            // default idle wait consuming the bounded movement window or chapter itself.
            Configurator.getInstance().waitForIdleTimeout=0L
            automationPreferences.edit().putString("online-novel_mode","TIMED_STEP")
                .putInt("online-novel_speed",60).putInt("online-novel_interval",8).commit()
            repo.pause(true);val novel=repo.load(fixture.id,fixture.first);id=novel.id;repo.favorite(novel.id,true)
            val returnChapterKey="novel-return:${novel.id}:chapter"
            val returnProgressKey="novel-return:${novel.id}:progress"
            hadReturnChapter=readerPreferences.contains(returnChapterKey);oldReturnChapter=readerPreferences.getString(returnChapterKey,null)
            hadReturnProgress=readerPreferences.contains(returnProgressKey);oldReturnProgress=readerPreferences.getFloat(returnProgressKey,Float.NaN)
            val chapters=repo.chapters(novel.id);val intent=NovelReaderActivity.intent(context,novel.id,chapters[0].id)
            var stored=0.0
            ActivityScenario.launch<NovelReaderActivity>(intent).use {reader->
                ready(reader);assertEquals(0,geometry(reader).first)
                // Capture immutable task/activity identity while the reader is paused. A later
                // ActivityScenario callback during continuous motion can wait through the chapter.
                var taskId=-1
                var instanceId=0
                reader.onActivity {activity->taskId=activity.taskId;instanceId=System.identityHashCode(activity)}
                val probe=observeScroll(reader)
                reader.onActivity {activity->
                    val web=requireNotNull(findWeb(activity.window.decorView))
                    assertTrue("The authored chapter must have a measurable scroll range",web.end()>1000)
                    web.scrollTo(0,(web.end()*.08).toInt())
                }
                clickDescription("Bookmark");assertNotNull(device.wait(Until.findObject(By.desc("Remove bookmark")),10000))
                delay(1900);stored=repo.get(novel.id)!!.chapterProgress;assertTrue(stored>.01)
                openNovelAutoReadOptions(reader)
                val (continuousMode,modeState)=awaitStableContinuousModeRow()
                assertTrue("Continuous mode row must be settled before selection: $modeState",continuousMode.isEnabled && continuousMode.isClickable)
                continuousMode.click()
                assertNotNull("Continuous mode must be selected before playback",device.wait(Until.findObject(By.textContains("Scroll speed ·")),10000))
                awaitAutomationMode(probe,"CONTINUOUS",false,"Continuous selection before Start",continuousMode)
                val before=probe.scrollY.get();click("Start auto read")
                awaitAutomationMode(probe,"CONTINUOUS",true,"Continuous Start",continuousMode)
                awaitScroll(probe,before,"Continuous playback after mode selection")
                assertAnyChapterPercentAtBottom()
                tapCenter(probe)
                delay(300);val paused=probe.scrollY.get();delay(1100);assertEquals(paused,probe.scrollY.get())
                assertAnyChapterPercentAtBottom()
                val quietBefore=probe.scrollY.get()
                assertNotNull("Center tap reveals the accessible Resume action",device.wait(Until.findObject(By.desc("Resume auto read")),5_000))
                requireNotNull(device.wait(Until.findObject(By.desc("Resume auto read")),5_000)).click()
                awaitAutomationMode(probe,"CONTINUOUS",true,"Reader controls resume continuous playback",continuousMode)
                awaitScroll(probe,quietBefore,"Continuous playback with main controls hidden")
                assertAnyChapterPercentAtBottom()
                assertFalse("Main reader controls remain hidden during quiet playback",device.hasObject(By.text("Aa")))
                assertQuietChapterProgressOnly(reader)
                assertTrue("Capture actual continuous playback with the bottom chapter percentage",
                    device.takeScreenshot(File(context.cacheDir,"qa-novel-continuous-percent.png")))
                assertTrue("Capture current 0.4.2 quiet continuous playback with the chapter percentage",
                    device.takeScreenshot(File(context.cacheDir,"qa-0.4.2-novel-continuous-percent-current.png")))
                val beforeManualPauseGeometry=Triple(probe.scrollY.get(),probe.end.get(),probe.height.get())
                val beforeManualPauseProgress=beforeManualPauseGeometry.first.toDouble()/beforeManualPauseGeometry.second
                tapCenter(probe)
                awaitAutomationMode(probe,"CONTINUOUS",false,"Manual center tap pauses continuous playback",continuousMode)
                assertTrue("The revealed controls expose Resume after a manual touch pause",device.wait(Until.hasObject(By.desc("Resume auto read")),5_000))
                assertTrue("Manual pause retains the chapter percentage",device.hasObject(By.desc(Pattern.compile("^Chapter progress, \\d{1,3} percent$"))))
                val afterManualPause=geometry(reader)
                delay(450)
                assertEquals("Manual pause leaves the actual viewport stable",afterManualPause.first,geometry(reader).first)
                assertTrue("Revealing controls preserves the story position: before=$beforeManualPauseGeometry,after=$afterManualPause",
                    kotlin.math.abs(beforeManualPauseProgress-afterManualPause.first.toDouble()/afterManualPause.second)<.05)
                val beforeBackground=probe.scrollY.get()
                openNovelAutoReadOptions(reader)
                click("Start auto read")
                awaitAutomationMode(probe,"CONTINUOUS",true,"Continuous restart after touch pause",continuousMode)
                awaitScroll(probe,beforeBackground,"Continuous playback after touch pause")
                var backgroundPaused=-1
                var backgroundAfter=-1
                var backgroundState="not sampled"
                try {
                    device.pressHome();assertTrue(device.wait(Until.hasObject(By.pkg("com.android.launcher3")),10000))
                    withTimeout(3_000) {
                        while(true) {
                            instrument.runOnMainSync { probe.refreshSnapshot?.invoke() }
                            if(probe.lifecycle!="RESUMED" && !probe.focused && !probe.automationRunning) break
                            delay(50)
                        }
                    }
                    backgroundPaused=probe.scrollY.get();backgroundState=probe.diagnostic()
                    delay(900)
                    instrument.runOnMainSync { probe.refreshSnapshot?.invoke() }
                    backgroundAfter=probe.scrollY.get()
                    assertEquals("Auto reading stays paused for 900ms after the reader loses foreground focus: $backgroundState",
                        backgroundPaused,backgroundAfter)
                } catch(error:Throwable) {
                    runCatching {captureFailureEvidence("novel-background-pause", "before=$backgroundPaused,after=$backgroundAfter,initialState=$backgroundState,"+
                        "finalState=${probe.diagnostic()},scrollHistory=${synchronized(probe.scrollHistory) {probe.scrollHistory.toList()}}")}
                    throw error
                }
                val manager=context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                requireNotNull(manager.appTasks.firstOrNull {it.taskInfo?.taskId==taskId}) {"Original reader task unavailable"}.moveToFront()
                assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)),10000));delay(300)
                reader.onActivity {activity->assertEquals("Return must reuse the same reader",instanceId,System.identityHashCode(activity));assertTrue("Original reader must have foreground window focus",activity.hasWindowFocus())}
                instrument.runOnMainSync { probe.refreshSnapshot?.invoke() }
                assertFalse("Returning to the foreground does not restart automatic reading",probe.automationRunning)
                assertEquals("Returning to the foreground preserves the paused viewport",backgroundPaused,probe.scrollY.get())
                val diagnostics=File(context.getExternalFilesDir(null),"arkhive-qa").apply {mkdirs()}
                device.dumpWindowHierarchy(File(diagnostics,"novel-return-hierarchy.xml"))
                device.takeScreenshot(File(diagnostics,"novel-return.png"))
                if(!device.hasObject(By.desc("Next chapter"))) tapCenter(probe)
                assertNotNull(device.wait(Until.findObject(By.desc("Next chapter")),10000))
                val priorChapter=currentNovelChapterId(reader)
                val priorProgress=geometry(reader).first.toDouble()/geometry(reader).second.coerceAtLeast(1)
                assertFalse("Ordinary reading before a deliberate jump does not offer Return",device.hasObject(By.text("Return")))
                clickDescription("Next chapter");ready(reader)
                assertNotEquals("Next chapter creates a distinct current chapter",priorChapter,currentNovelChapterId(reader))
                val jumpedY=geometry(reader).first;delay(700);assertEquals("Chapter-boundary navigation remains paused",jumpedY,geometry(reader).first)
                assertNotNull("A successful deliberate chapter jump exposes one contextual Return",device.wait(Until.findObject(By.text("Return")),5000))
                click("Return");ready(reader)
                assertEquals("Return restores the prior online chapter",priorChapter,currentNovelChapterId(reader))
                withTimeout(8_000) {
                    while(kotlin.math.abs(geometry(reader).first.toDouble()/geometry(reader).second.coerceAtLeast(1)-priorProgress)>.06) delay(80)
                }
                assertTrue("A successful online Return consumes its one saved point",device.wait(Until.gone(By.text("Return")),5000))
                val dir=File(context.getExternalFilesDir(null),"arkhive-qa").apply {mkdirs()};device.takeScreenshot(File(dir,"Arkhive-online-novel-reader.png"))
            }
            fixture.offline.set(true);val calls=fixture.calls.get()
            ActivityScenario.launch<NovelReaderActivity>(intent).use {reader->ready(reader);assertTrue(geometry(reader).first>1000);assertNotNull(device.wait(Until.findObject(By.desc("Remove bookmark")),10000))}
            assertEquals(calls,fixture.calls.get());assertTrue(repo.get(novel.id)!!.chapterProgress>stored*.8)
        } finally {
            Configurator.getInstance().waitForIdleTimeout=previousIdleTimeout
            id?.let { novelId ->
                val chapterKey="novel-return:$novelId:chapter";val progressKey="novel-return:$novelId:progress"
                readerPreferences.edit().apply {
                    if(hadReturnChapter) putString(chapterKey,oldReturnChapter) else remove(chapterKey)
                    if(hadReturnProgress) putFloat(progressKey,oldReturnProgress?:Float.NaN) else remove(progressKey)
                }.commit()
                repo.remove(novelId)
            }
            repo.sources.fixtures.remove(fixture.id);repo.pause(originalPaused)
            context.appGraph.basePreferences.incognitoMode.set(oldPrivate);context.appGraph.uiPreferences.arkhiveReducedMotion.set(oldMotion)
            restoreAutomation(automationPreferences,automationKeys,previousAutomation)
        }
    }

    @Test fun manualNovelPagesAndVerticalScrollPreserveStoryProgressAndProfile():Unit=runBlocking {
        val fixture=OriginalNovelFixture(distinctivePages=true);val repo=context.appGraph.novelRepository;val graph=context.appGraph
        val appearancePreferences=context.getSharedPreferences("arkhive_book_reader",0)
        val hadFont=appearancePreferences.contains("font");val oldFont=appearancePreferences.getFloat("font",100f)
        val hadLine=appearancePreferences.contains("line");val oldLine=appearancePreferences.getFloat("line",33f/21f)
        val hadMargin=appearancePreferences.contains("margin");val oldMargin=appearancePreferences.getFloat("margin",1f)
        val previewFont=if(kotlin.math.abs(oldFont-145f)<.1f) 155f else 145f
        val previewLine=if(kotlin.math.abs(oldLine-1.9f)<.01f) 2.1f else 1.9f
        val previewMargin=if(kotlin.math.abs(oldMargin-1.7f)<.01f) 2.0f else 1.7f
        val oldPaused=repo.state.value.paused
        val oldStatsEnabled=graph.libraryToolsStore.state.value.statsEnabled
        val oldPrivate=graph.basePreferences.incognitoMode.get()
        val oldIdleTimeout=Configurator.getInstance().waitForIdleTimeout
        var novelId:String?=null
        var profileKey:String?=null
        var hadPriorProfile=false
        var priorProfile=mihon.readingapp.library.ReadingProfile()
        repo.sources.fixtures[fixture.id]=fixture.source
        try {
            graph.basePreferences.incognitoMode.set(false)
            check(!graph.basePreferences.incognitoMode.get()) { "The saved-progress test requires normal mode" }
            Configurator.getInstance().waitForIdleTimeout=0L
            repo.pause(true)
            graph.libraryToolsStore.update {it.copy(statsEnabled=false)}
            val novel=repo.load(fixture.id,fixture.first);novelId=novel.id;repo.favorite(novel.id,true)
            profileKey="novel:${novel.id}"
            graph.libraryToolsStore.state.value.profiles[profileKey!!]?.let {hadPriorProfile=true;priorProfile=it}
            graph.libraryToolsStore.setProfile(profileKey!!,priorProfile.copy(layout="pages",room=mihon.readingapp.library.ReadingRoom.INK_BLACK))
            val chapter=repo.chapters(novel.id).first()
            val intent=NovelReaderActivity.intent(context,novel.id,chapter.id)

            ActivityScenario.launch<NovelReaderActivity>(intent).use {reader->
                readyPages(reader)
                var pages=horizontalGeometry(reader)
                assertTrue("Pages layout must expose a horizontal page range: $pages",pages.second>1000)
                val storedBeforeTapSequence=repo.chapters(novel.id).first().progression
                val viewportBeforeTapSequence=pages
                val openingChapterId=currentNovelChapterId(reader)
                tapNovelRatio(reader,.50f)
                awaitStablePageViewport(reader)
                tapNovelRatio(reader,.75f)
                awaitStablePageViewport(reader)
                assertEquals("Center and near-center-right Pages taps only toggle chrome; they do not change chapters",
                    openingChapterId,currentNovelChapterId(reader))
                assertTrue("Center and near-center-right taps leave the chapter viewport on its original resource",
                    horizontalGeometry(reader).second>1000)
                val viewportAfterTapSequence=horizontalGeometry(reader)
                reader.onActivity {activity->
                    val web=findWeb(activity.window.decorView)!!
                    web.scrollTo((pages.second*.28f).toInt(),0)
                    assertEquals("Pages layout must not vertically scroll",0,web.scrollY)
                }
                val viewportAfterManualScroll=horizontalGeometry(reader)
                val storedAfterManualScroll=repo.chapters(novel.id).first().progression
                delay(1800)
                assertChapterPercentAtBottom(reader) { horizontalGeometry(reader) }
                val initialProgress=repo.chapters(novel.id).first().progression
                if(initialProgress !in .18..0.40) {
                    var runtime="unavailable"
                    reader.onActivity {activity->
                        val web=findWeb(activity.window.decorView)
                        val expected=runCatching {activity.javaClass.getDeclaredField("expectedUrl").apply {isAccessible=true}.get(activity) as String}.getOrDefault("unavailable")
                        val root=activity.window.decorView
                        runtime="activity=${activity.javaClass.name},lifecycle=${activity.lifecycle.currentState},windowFocus=${activity.hasWindowFocus()},rootShown=${root.isShown},rootFocus=${root.hasFocus()},root=${root.width}x${root.height},"+
                            "expectedUrl=$expected,chapter=${expected.substringAfterLast("/chapter/").substringBefore('?')},webUrl=${web?.url},webShown=${web?.isShown},webFocus=${web?.hasFocus()},"+
                            "webGeometry=${web?.scrollX}/${web?.width}/${web?.endX()} x ${web?.scrollY}/${web?.height}/${web?.end()},"+
                            "js=${web?.settings?.javaScriptEnabled}"
                    }
                    captureFailureEvidence("novel-pages-progress", "fixture=${fixture.id},novel=${novel.id},chapter=${chapter.id},"+
                        "incognitoBeforeTest=$oldPrivate,incognitoDuringTest=${graph.basePreferences.incognitoMode.get()},storedBeforeTapSequence=$storedBeforeTapSequence,storedAfterTapSequence=$storedAfterManualScroll,storedAfterSettle=$initialProgress,"+
                        "repoNovel=${repo.get(novel.id)},profile=${graph.libraryToolsStore.state.value.profiles[profileKey!!]},inputs=${novelInputTrace.toList()},runtime=$runtime,"+
                        "viewportBeforeTaps=$viewportBeforeTapSequence,viewportAfterTaps=$viewportAfterTapSequence,viewportAfterManualScroll=$viewportAfterManualScroll,viewportAtFailure=${pageViewport(reader)},"+
                        "percentLabel=${device.findObjects(By.desc(Pattern.compile("^Chapter progress, \\d{1,3} percent$"))).joinToString {"${it.contentDescription}@${it.visibleBounds.flattenToString()}"}}")
                }
                assertTrue("Horizontal page position must persist as chapter progress: $initialProgress",initialProgress in .18..0.40)

                // Exercise finger-follow with fullscreen chrome hidden, then restore the
                // controls before checking the ordinary settings and chapter actions below.
                tapNovelCenter(reader)
                assertTrue("The Pages drag probe runs with the reader chrome hidden",device.wait(Until.gone(By.desc("Bookmark")),3_000))
                val hiddenChromeViewport=awaitStablePageViewport(reader)
                // Hold a finger between page columns. The WebView must expose its translated
                // scroll position before release, then ACTION_CANCEL must restore the committed
                // page without saving the transient drag position.
                val dragOrigin=hiddenChromeViewport.x
                val storedProgressBeforeDrag=repo.chapters(novel.id).first().progression
                reader.onActivity {activity->
                    val web=requireNotNull(findWeb(activity.window.decorView));val now=android.os.SystemClock.uptimeMillis()
                    fun dispatch(action:Int,time:Long,x:Float)=MotionEvent.obtain(now,time,action,x,web.height*.5f,0).also {
                        web.dispatchTouchEvent(it);it.recycle()
                    }
                    dispatch(MotionEvent.ACTION_DOWN,now,web.width*.82f)
                    dispatch(MotionEvent.ACTION_MOVE,now+80,web.width*.47f)
                }
                val held=horizontalGeometry(reader)
                assertTrue("A held Pages drag follows the finger and exposes part of the adjacent page: origin=$dragOrigin, held=$held",
                    held.first-dragOrigin in (held.third*.25).toInt()..(held.third*.42).toInt())
                assertTrue("Save a synthetic self-authored partial-page drag frame",
                    device.takeScreenshot(File(context.cacheDir,"qa-0.4.2-novel-held-partial-page.png")))
                reader.onActivity {activity->
                    val web=requireNotNull(findWeb(activity.window.decorView));val now=android.os.SystemClock.uptimeMillis()
                    val cancel=MotionEvent.obtain(now,now+1,MotionEvent.ACTION_CANCEL,web.width*.47f,web.height*.5f,0)
                    web.dispatchTouchEvent(cancel);cancel.recycle()
                }
                assertEquals("ACTION_CANCEL restores the exact committed Pages position",dragOrigin,horizontalGeometry(reader).first)
                assertEquals("A transient held drag does not persist reading progress",storedProgressBeforeDrag,
                    repo.chapters(novel.id).first().progression,.001)
                tapNovelCenter(reader)
                assertNotNull("The online reader controls return after the hidden-chrome drag probe",
                    device.wait(Until.findObject(By.text("Aa")),3_000))
                // Revealing Pages chrome changes the WebView height and can legitimately
                // reflow its horizontal columns. Anchor the reversal probe only after that
                // reflow settles; the earlier hidden-chrome drag origin is no longer valid.
                val reversalOrigin = awaitStablePageViewport(reader).x

                // A reversal that returns near the origin snaps back instead of committing the
                // intermediate page or treating the release as a page turn.
                var reversalGestureOrigin = -1
                reader.onActivity {activity->
                    val web=requireNotNull(findWeb(activity.window.decorView));val now=android.os.SystemClock.uptimeMillis()
                    reversalGestureOrigin = web.scrollX
                    fun dispatch(action:Int,time:Long,x:Float)=MotionEvent.obtain(now,time,action,x,web.height*.5f,0).also {
                        web.dispatchTouchEvent(it);it.recycle()
                    }
                    dispatch(MotionEvent.ACTION_DOWN,now,web.width*.82f)
                    dispatch(MotionEvent.ACTION_MOVE,now+80,web.width*.45f)
                    dispatch(MotionEvent.ACTION_MOVE,now+160,web.width*.72f)
                    dispatch(MotionEvent.ACTION_UP,now+180,web.width*.72f)
                }
                assertEquals("The reversal probe begins at the settled post-chrome page",reversalOrigin,reversalGestureOrigin)
                delay(350)
                val settledAfterReversal = horizontalGeometry(reader).first
                File(context.cacheDir,"qa-0.4.2-novel-reversal-origin.txt").writeText(
                    "hiddenChromeDragOrigin=$dragOrigin\nshownChromeSettledOrigin=$reversalOrigin\n"+
                        "reversalGestureStart=$reversalGestureOrigin\nreversalSettledX=$settledAfterReversal\n",
                )
                assertEquals("Reversing a held page drag back toward its origin snaps back",reversalOrigin,settledAfterReversal)

                // A position opened from saved progress can lie within a page. First turn snaps
                // it to the next page boundary; each later gesture must move exactly one column.
                val expectedStride=horizontalGeometry(reader).third.coerceAtLeast(1)
                val density=context.resources.displayMetrics.density
                fun pageTarget(offset:Int,direction:Int):Int {
                    val position=offset.toDouble()/expectedStride
                    val nearest=Math.round(position).toInt()
                    val aligned=kotlin.math.abs(offset-nearest*expectedStride)<=maxOf(2,(2*density).toInt())
                    val page=when {
                        direction>0 -> if(aligned) nearest+1 else kotlin.math.floor(position).toInt()+1
                        aligned -> nearest-1
                        else -> kotlin.math.ceil(position).toInt()-1
                    }
                    val end=horizontalGeometry(reader).second
                    return (page.coerceIn(0,kotlin.math.ceil(end.toDouble()/expectedStride).toInt())*expectedStride).coerceAtMost(end)
                }
                val beforeSnap=horizontalGeometry(reader).first
                val snappedTarget=pageTarget(beforeSnap,1)
                tapForward(reader)
                awaitPageTurn(reader,snappedTarget,expectedStride,"Initial partial page snap")
                val beforeQuietToggle=awaitStablePageViewport(reader)
                tapNovelCenter(reader)
                assertTrue("The quiet Pages layout should hide the reader controls",device.wait(Until.gone(By.desc("Bookmark")),3_000))
                assertQuietChapterProgressOnly(reader)
                val quietPagesViewport=awaitStablePageViewport(reader)
                assertEquals("Hiding Pages controls leaves the publication viewport unchanged",beforeQuietToggle,quietPagesViewport)
                assertPageProgressPreserved(beforeQuietToggle,quietPagesViewport,"Showing the quiet Pages footer")
                assertChapterPercentAtBottom(reader) { horizontalGeometry(reader) }
                assertTrue("Save the quiet Pages percentage after the first full-column snap for visual review",
                    device.takeScreenshot(File(context.cacheDir,"qa-novel-quiet-percent.png")))
                tapNovelCenter(reader)
                val menuPagesViewport=awaitStablePageViewport(reader)
                assertEquals("Restoring Pages controls leaves the publication viewport unchanged",quietPagesViewport,menuPagesViewport)
                assertPageProgressPreserved(quietPagesViewport,menuPagesViewport,"Restoring the Pages controls")
                tapNovelCenter(reader)
                val quietPagesAgain=awaitStablePageViewport(reader)
                assertEquals("A repeated quiet toggle leaves the publication viewport unchanged",beforeQuietToggle,quietPagesAgain)
                assertPageProgressPreserved(beforeQuietToggle,quietPagesAgain,"Repeating the quiet Pages toggle")
                tapNovelCenter(reader)
                val menuPagesAgain=awaitStablePageViewport(reader)
                assertEquals("Repeated Pages toggles keep the same full-column viewport: before=$beforeQuietToggle,quiet=$quietPagesViewport,menu=$menuPagesViewport,quietAgain=$quietPagesAgain,menuAgain=$menuPagesAgain",
                    beforeQuietToggle,menuPagesAgain)
                assertNotNull("The center tap should restore reader controls",device.wait(Until.findObject(By.text("Aa")),5_000))
                fun swipePage(forward:Boolean) {
                    val origin=IntArray(2);val size=IntArray(2)
                    reader.onActivity {activity->findWeb(activity.window.decorView)!!.let {it.getLocationOnScreen(origin);size[0]=it.width;size[1]=it.height}}
                    if(forward) device.swipe(origin[0]+(size[0]*.88f).toInt(),origin[1]+size[1]/2,
                        origin[0]+(size[0]*.12f).toInt(),origin[1]+size[1]/2,12)
                    else device.swipe(origin[0]+(size[0]*.12f).toInt(),origin[1]+size[1]/2,
                        origin[0]+(size[0]*.88f).toInt(),origin[1]+size[1]/2,12)
                }
                val partialOffset=(expectedStride*2.8f).toInt().coerceAtMost(horizontalGeometry(reader).second)
                reader.onActivity {activity->findWeb(activity.window.decorView)!!.scrollTo(partialOffset,0)}
                val partialForward=pageTarget(partialOffset,1)
                swipePage(true)
                awaitPageTurn(reader,partialForward,expectedStride,"Forward swipe from an 0.8-column restored offset")
                assertEquals("Forward swipe from 0.8 column settles at the next boundary, without skipping it",
                    partialForward,horizontalGeometry(reader).first)
                reader.onActivity {activity->findWeb(activity.window.decorView)!!.scrollTo(partialOffset,0)}
                val partialBackward=pageTarget(partialOffset,-1)
                swipePage(false)
                awaitPageTurn(reader,partialBackward,expectedStride,"Back swipe from an 0.8-column restored offset")
                assertEquals("Back swipe from 0.8 column settles at the preceding boundary",
                    partialBackward,horizontalGeometry(reader).first)
                val beforeSwipe=horizontalGeometry(reader).first
                val afterSwipeTarget=pageTarget(beforeSwipe,1)
                swipePage(true)
                awaitPageTurn(reader,afterSwipeTarget,expectedStride,"Forward page swipe")
                var afterSwipe=horizontalGeometry(reader).first
                assertEquals("A left page swipe advances by exactly one column",expectedStride.toDouble(),(afterSwipe-beforeSwipe).toDouble(),4.0)
                assertEquals("Pages mode must not acquire vertical scroll",0,geometry(reader).first)

                val beforeReverseSwipe=afterSwipe
                val reverseSwipeTarget=pageTarget(beforeReverseSwipe,-1)
                swipePage(false)
                awaitPageTurn(reader,reverseSwipeTarget,expectedStride,"Reverse page swipe")
                afterSwipe=horizontalGeometry(reader).first
                assertEquals("A right page swipe returns by exactly one column",expectedStride.toDouble(),(beforeReverseSwipe-afterSwipe).toDouble(),4.0)
                val beforeBackTap=afterSwipe
                val backTapTarget=pageTarget(beforeBackTap,-1)
                tapBackward(reader)
                awaitPageTurn(reader,backTapTarget,expectedStride,"Back-edge page tap")
                assertEquals("The left-edge tap turns back one page",expectedStride.toDouble(),(beforeBackTap-horizontalGeometry(reader).first).toDouble(),4.0)
                val beforeForwardTap=horizontalGeometry(reader).first
                val forwardTapTarget=pageTarget(beforeForwardTap,1)
                tapForward(reader)
                awaitPageTurn(reader,forwardTapTarget,expectedStride,"Forward-edge page tap")
                assertEquals("The right-edge tap turns forward one page",expectedStride.toDouble(),(horizontalGeometry(reader).first-beforeForwardTap).toDouble(),4.0)
                assertTrue("Capture self-authored text on a later page for visual verification",device.takeScreenshot(File(context.cacheDir,"qa-manual-novel-later-page.png")))

                reader.onActivity {activity->findWeb(activity.window.decorView)!!.let {web->web.scrollTo(web.endX(),0)}}
                awaitStablePageViewport(reader)
                val lastPage=horizontalGeometry(reader).first
                val lastChapterId=currentNovelChapterId(reader)
                tapNovelRatio(reader,.50f)
                awaitStablePageViewport(reader)
                tapNovelRatio(reader,.75f)
                awaitStablePageViewport(reader)
                assertEquals("At the Pages chapter boundary, center and near-center-right taps cannot open the next chapter",
                    lastChapterId,currentNovelChapterId(reader))
                reader.onActivity {activity->findWeb(activity.window.decorView)!!.let {web->web.scrollTo(web.endX(),0)}}
                tapForward(reader);delay(300)
                assertEquals("A final partial page must stop at the horizontal boundary",lastPage,horizontalGeometry(reader).first)
                tapForward(reader);delay(150)
                assertEquals("Rapid edge taps at the final Pages boundary cannot advance into another chapter",
                    lastChapterId,currentNovelChapterId(reader))
                val previousPageTarget=pageTarget(lastPage,-1)
                tapBackward(reader)
                awaitPageTurn(reader,previousPageTarget,expectedStride,"Back from final partial page")
                val pagesAtBoundary=horizontalGeometry(reader)
                val pageProgress=pagesAtBoundary.first.toDouble()/pagesAtBoundary.second
                assertTrue("Page turns and the final partial page retain normalized progress",pageProgress>initialProgress+.2)

                clickDescription("Bookmark")
                assertNotNull(device.wait(Until.findObject(By.desc("Remove bookmark")),10000))
                click("Aa")
                assertNotNull(device.wait(Until.findObject(By.text("Reading layout & appearance")),10000))
                assertNotNull("The shared appearance panel keeps its two-line live text sample visible",device.wait(Until.findObject(By.textContains("Quiet pages.")),5000))
                assertTrue("Save the authored Pages/Vertical selector for visual review",device.takeScreenshot(File(context.cacheDir,"qa-manual-novel-layout.png")))
                val beforeAppearance=renderedNovelAppearance(reader,"initial effective paper and typography",novel.id,fixture.id)
                assertEquals("A per-title paper is applied after the novel loads instead of leaving the legacy fallback cached",
                    "rgb(0, 0, 0)",beforeAppearance.optString("paper"))
                reader.onActivity {activity->
                    fun preview(name:String,value:Float) {
                        activity.javaClass.getDeclaredMethod(name,Float::class.javaPrimitiveType!!).apply {isAccessible=true}.invoke(activity,value)
                    }
                    preview("previewFontSize",previewFont);preview("previewLineSpacing",previewLine);preview("previewPageMargin",previewMargin)
                }
                var liveAppearance=beforeAppearance
                try {
                    withTimeout(10_000) {
                        while(liveAppearance.optString("font")==beforeAppearance.optString("font") ||
                            liveAppearance.optString("line")==beforeAppearance.optString("line") ||
                            liveAppearance.optString("margin")==beforeAppearance.optString("margin")) {
                            delay(100);liveAppearance=renderedNovelAppearance(reader,"live font, line spacing, and margin preview",novel.id,fixture.id)
                        }
                    }
                } catch(error:TimeoutCancellationException) {
                    throw AssertionError("Timed out after the unchanged 10s budget waiting for live online typography CSS; "+
                        "before=$beforeAppearance; latest=$liveAppearance",error)
                }
                assertNotEquals("The live online preview changes rendered font size",beforeAppearance.optString("font"),liveAppearance.optString("font"))
                assertNotEquals("The live online preview changes rendered line spacing",beforeAppearance.optString("line"),liveAppearance.optString("line"))
                assertNotEquals("The live online preview changes rendered margins",beforeAppearance.optString("margin"),liveAppearance.optString("margin"))
                assertTrue("The compact two-line preview remains present as the lower controls scroll",device.hasObject(By.textContains("Quiet pages.")))
                assertTrue("Capture the authored online live appearance preview",device.takeScreenshot(File(context.cacheDir,"qa-0.4.3-online-appearance-preview.png")))
                fun selectRoom(room:mihon.readingapp.library.ReadingRoom) {
                    reader.onActivity {activity->activity.javaClass.getDeclaredMethod("selectReadingRoom",room.javaClass)
                        .apply {isAccessible=true}.invoke(activity,room)}
                }
                selectRoom(mihon.readingapp.library.ReadingRoom.WARM_PAPER);readyPages(reader)
                val warmPaper=renderedNovelAppearance(reader,"Warm Paper selection",novel.id,fixture.id)
                selectRoom(mihon.readingapp.library.ReadingRoom.MIDNIGHT_VIOLET);readyPages(reader)
                val midnightPaper=renderedNovelAppearance(reader,"Midnight Violet selection",novel.id,fixture.id)
                selectRoom(mihon.readingapp.library.ReadingRoom.INK_BLACK);readyPages(reader)
                val changedPaper=renderedNovelAppearance(reader,"Ink Black selection after dark paper",novel.id,fixture.id)
                assertNotEquals("Warm paper updates the actual online reader page",beforeAppearance.optString("paper"),warmPaper.optString("paper"))
                assertNotEquals("Switching between dark papers updates the actual online reader page",midnightPaper.optString("paper"),changedPaper.optString("paper"))
                assertEquals("The selected per-title paper remains the effective page color", "rgb(0, 0, 0)",changedPaper.optString("paper"))
                assertEquals("Live typography values are persisted when paper selection settles the preview",previewFont,
                    appearancePreferences.getFloat("font",100f),.1f)
                assertEquals("Line spacing is persisted through paper selection",previewLine,appearancePreferences.getFloat("line",0f),.01f)
                assertEquals("Margins are persisted through paper selection",previewMargin,appearancePreferences.getFloat("margin",0f),.01f)
                clickContaining("Vertical scroll");click("Done")
                ready(reader)
                assertEquals("Vertical layout selection should persist per story","scroll",graph.libraryToolsStore.profile(profileKey!!).layout)
                val vertical=awaitStableVerticalGeometry(reader)
                val verticalProgress=vertical.first.toDouble()/vertical.second
                assertEquals("Switching axes should keep the same normalized reading position",pageProgress,verticalProgress,.08)
                assertEquals("The vertical layout should leave no horizontal offset",0,horizontalGeometry(reader).first)

                reader.onActivity {activity->findWeb(activity.window.decorView)!!.let {web->web.scrollTo(0,web.end())}}
                val beforeContinuousTap=awaitStableVerticalGeometry(reader)
                val continuousChapterId=currentNovelChapterId(reader)
                tapNovelRatio(reader,.50f)
                awaitStableVerticalGeometry(reader)
                tapNovelRatio(reader,.75f)
                val afterContinuousTap=awaitStableVerticalGeometry(reader)
                assertEquals("Continuous center and near-center-right taps toggle controls without navigating chapters",
                    continuousChapterId,currentNovelChapterId(reader))
                assertEquals("Continuous content taps do not page through the chapter",beforeContinuousTap.first.toDouble(),
                    afterContinuousTap.first.toDouble(),maxOf(2.0,beforeContinuousTap.second*.02))
                assertChapterPercentAtBottom(reader) { geometry(reader) }
                val beforeQuietVertical=awaitStableVerticalGeometry(reader)
                tapNovelCenter(reader)
                assertTrue("The quiet Vertical layout should hide the reader controls",device.wait(Until.gone(By.text("Aa")),3_000))
                assertQuietChapterProgressOnly(reader)
                val quietVertical=awaitStableVerticalGeometry(reader)
                assertTrue("Hiding Vertical controls should change the reader viewport: $beforeQuietVertical → $quietVertical",
                    beforeQuietVertical.third!=quietVertical.third)
                assertVerticalProgressPreserved(beforeQuietVertical,quietVertical,"Showing the quiet Vertical footer")
                assertChapterPercentAtBottom(reader) { geometry(reader) }
                tapNovelCenter(reader)
                val menuVertical=awaitStableVerticalGeometry(reader)
                assertTrue("Restoring Vertical controls should change the reader viewport: $quietVertical → $menuVertical",
                    quietVertical.third!=menuVertical.third)
                assertVerticalProgressPreserved(quietVertical,menuVertical,"Restoring the Vertical controls")
                tapNovelCenter(reader)
                val quietVerticalAgain=awaitStableVerticalGeometry(reader)
                assertVerticalProgressPreserved(beforeQuietVertical,quietVerticalAgain,"Repeating the quiet Vertical reflow")
                tapNovelCenter(reader)
                val menuVerticalAgain=awaitStableVerticalGeometry(reader)
                assertEquals("A repeated Vertical reflow should return to the same scroll anchor",
                    beforeQuietVertical.first.toDouble(),menuVerticalAgain.first.toDouble(),2.0)
                assertNotNull("The center tap should restore reader controls",device.wait(Until.findObject(By.text("Aa")),5_000))
                click("Aa");clickContaining("Pages");click("Done")
                readyPages(reader)
                pages=horizontalGeometry(reader)
                assertEquals("Switching back should retain reading progress",repo.chapters(novel.id).first().progression,
                    pages.first.toDouble()/pages.second,.08)
                assertEquals("The selected manual layout is saved","pages",graph.libraryToolsStore.profile(profileKey!!).layout)
                assertNotNull(device.wait(Until.findObject(By.desc("Remove bookmark")),10000))
            }

            ActivityScenario.launch<NovelReaderActivity>(intent).use {reader->
                readyPages(reader)
                val pages=horizontalGeometry(reader)
                val saved=repo.chapters(novel.id).first().progression
                    var reopenedPalette:Any?=null
                    reader.onActivity {activity->reopenedPalette=activity.javaClass.getDeclaredMethod("readerPalette").apply {isAccessible=true}.invoke(activity)}
                    assertEquals("Reopening uses the saved per-title room",graph.libraryToolsStore.profile(profileKey!!).room,reopenedPalette)
                assertEquals("Pages layout must survive reader recreation","pages",graph.libraryToolsStore.profile(profileKey!!).layout)
                    click("Aa")
                    assertNotNull("The appearance panel opens with the restored per-title room",device.wait(Until.findObject(By.textContains("Quiet pages.")),5000))
                    assertTrue("Capture the reopened per-title paper with matching reader chrome",device.takeScreenshot(File(context.cacheDir,"qa-0.4.3-online-room-reopen.png")))
                    click("Done")
                assertEquals("Saved chapter progress restores on the horizontal axis",saved,pages.first.toDouble()/pages.second,.08)
                assertNotNull(device.wait(Until.findObject(By.desc("Remove bookmark")),10000))
            }
        } finally {
            val privateRestoreAtEntry=runCatching {
                graph.basePreferences.incognitoMode.set(oldPrivate)
                graph.basePreferences.incognitoMode.get()
            }
            try {
                Configurator.getInstance().waitForIdleTimeout=oldIdleTimeout
                novelId?.let {repo.remove(it)}
                profileKey?.let {key->graph.libraryToolsStore.update {state->
                    state.copy(profiles=if(hadPriorProfile) state.profiles+(key to priorProfile) else state.profiles-key)
                }}
                graph.libraryToolsStore.update {it.copy(statsEnabled=oldStatsEnabled)}
                repo.sources.fixtures.remove(fixture.id);repo.pause(oldPaused)
                appearancePreferences.edit().apply {
                    if(hadFont) putFloat("font",oldFont) else remove("font")
                    if(hadLine) putFloat("line",oldLine) else remove("line")
                    if(hadMargin) putFloat("margin",oldMargin) else remove("margin")
                }.commit()
            } finally {
                val restoredPrivate=runCatching {graph.basePreferences.incognitoMode.get()}
                runCatching {
                    File(context.cacheDir,"qa-0.4.3-failure-evidence/incognito-restore.txt").apply {parentFile?.mkdirs()}
                        .writeText("before=$oldPrivate atFinally=${privateRestoreAtEntry.getOrNull()} final=${restoredPrivate.getOrNull()}")
                }
                check(privateRestoreAtEntry.getOrNull()==oldPrivate && restoredPrivate.getOrNull()==oldPrivate) {
                    "Incognito preference was not restored: before=$oldPrivate atFinally=${privateRestoreAtEntry.getOrNull()} final=${restoredPrivate.getOrNull()}"
                }
            }
        }
    }

    @Test fun continuousControlSwitchesPagedNovelAndTimedTapUsesCancellableMotion():Unit=runBlocking {
        val fixture=OriginalNovelFixture();val repo=context.appGraph.novelRepository;val graph=context.appGraph
        val oldPaused=repo.state.value.paused;val oldMotion=graph.uiPreferences.arkhiveReducedMotion.get()
        val automationPreferences=context.getSharedPreferences("arkhive_book_automation",0)
        val automationKeys=setOf("online-novel_mode","online-novel_speed","online-novel_interval")
        val previousAutomation=automationPreferences.all.filterKeys { it in automationKeys }
        val previousIdleTimeout=Configurator.getInstance().waitForIdleTimeout
        val previousProfile=graph.libraryToolsStore.profile("novel:${NovelRepository.novelId(fixture.id,fixture.first)}")
        var novelId:String?=null
        repo.sources.fixtures[fixture.id]=fixture.source
        try {
            Configurator.getInstance().waitForIdleTimeout=0L
            automationPreferences.edit().putString("online-novel_mode","TIMED_STEP")
                .putInt("online-novel_speed",60).putInt("online-novel_interval",8).commit()
            repo.pause(true);graph.uiPreferences.arkhiveReducedMotion.set(false)
            val novel=repo.load(fixture.id,fixture.first);novelId=novel.id;repo.favorite(novel.id,true)
            val profileKey="novel:${novel.id}"
            graph.libraryToolsStore.setProfile(profileKey,graph.libraryToolsStore.profile(profileKey).copy(layout="pages"))
            val chapter=repo.chapters(novel.id).first()
            ActivityScenario.launch<NovelReaderActivity>(NovelReaderActivity.intent(context,novel.id,chapter.id)).use {reader->
                readyPages(reader)
                val probe=observeScroll(reader)
                openNovelAutoReadOptions(reader)
                val (continuous,rowState)=awaitStableContinuousModeRow()
                assertTrue("Paged text must offer an explicit switch to vertical scrolling: $rowState",continuous.isEnabled && continuous.isClickable)
                assertTrue("Save the authored Auto-read sheet for visual review",device.takeScreenshot(File(context.cacheDir,"qa-novel-auto-sheet.png")))
                continuous.click()
                assertNotNull("The chosen mode must survive the layout rebind",device.wait(Until.findObject(By.textContains("Scroll speed ·")),10000))
                assertEquals("scroll",graph.libraryToolsStore.profile(profileKey).layout)
                awaitAutomationMode(probe,"CONTINUOUS",false,"Continuous mode retained after Pages-to-Vertical rebind",continuous)
                ready(reader)
                click("Done")
                val before=probe.scrollY.get()
                synchronized(probe.scrollHistory) { probe.scrollHistory.clear() }
                tapForward(reader)
                delay(700)
                instrument.runOnMainSync { probe.refreshSnapshot?.invoke() }
                val positions=synchronized(probe.scrollHistory) { probe.scrollHistory.toList() }
                val movedPositions=positions.filter {it>before}.distinct()
                assertTrue("A screen tap should animate through intermediate positions; start=$before,trace=${positions.take(40)},${probe.diagnostic()}",
                    movedPositions.size>=3 && movedPositions.last()>movedPositions.first())
                assertTrue("The step should finish smoothly and monotonically; start=$before,trace=${positions.take(40)},${probe.diagnostic()}",
                    positions.zipWithNext().all { (a,b)->b>=a } && movedPositions.last()>before)

                val slowSpeed = setVisibleSpeed(reader,.1f)
                assertTrue("The visible slider should select a slow rate", slowSpeed in 16..60)
                val slowDistance = measureContinuousRun(reader,probe)
                assertTrue("Continuous mode should move at its selected low speed", slowDistance > 20)

                val fastSpeed = setVisibleSpeed(reader,.9f)
                assertTrue("The visible slider should select a faster rate", fastSpeed >= 190)
                val fastDistance = measureContinuousRun(reader,probe)
                assertTrue("A faster selected speed should move farther in the same interval: $slowDistance → $fastDistance",
                    fastDistance > slowDistance * 2)

                graph.uiPreferences.arkhiveReducedMotion.set(true)
                val reducedBefore = geometry(reader).first
                tapForward(reader)
                val reducedAfter = geometry(reader).first
                delay(100)
                assertTrue("Reduced motion should keep the manual step immediate", reducedAfter > reducedBefore && geometry(reader).first == reducedAfter)
            }
        } finally {
            Configurator.getInstance().waitForIdleTimeout=previousIdleTimeout
            novelId?.let {repo.remove(it)}
            graph.libraryToolsStore.setProfile("novel:${NovelRepository.novelId(fixture.id,fixture.first)}",previousProfile)
            repo.sources.fixtures.remove(fixture.id);repo.pause(oldPaused);graph.uiPreferences.arkhiveReducedMotion.set(oldMotion)
            restoreAutomation(automationPreferences,automationKeys,previousAutomation)
        }
    }
    @Test fun privateOnlineReturnPreservesStoredSentinelAndSuppressesSpeculativePreload():Unit=runBlocking {
        val fixture=OriginalNovelFixture();val repo=context.appGraph.novelRepository;val graph=context.appGraph
        val oldPaused=repo.state.value.paused;val oldPrivate=graph.basePreferences.incognitoMode.get()
        val preferences=context.getSharedPreferences("arkhive_book_reader",0)
        var novelId:String?=null;var hadChapter=false;var oldChapter:String?=null
        var hadProgress=false;var oldProgress=Float.NaN
        repo.sources.fixtures[fixture.id]=fixture.source
        try {
            repo.pause(true)
            val novel=repo.load(fixture.id,fixture.first);novelId=novel.id
            val chapters=repo.chapters(novel.id);assertTrue("The authored source has a following chapter",chapters.size>=2)
            val chapterKey="novel-return:${novel.id}:chapter";val progressKey="novel-return:${novel.id}:progress"
            hadChapter=preferences.contains(chapterKey);oldChapter=preferences.getString(chapterKey,null)
            hadProgress=preferences.contains(progressKey);oldProgress=preferences.getFloat(progressKey,Float.NaN)
            val sentinel=chapters.last().id
            preferences.edit().putString(chapterKey,sentinel).putFloat(progressKey,.43f).commit()
            graph.basePreferences.incognitoMode.set(true)
            val callsBeforeReader=fixture.calls.get()
            ActivityScenario.launch<NovelReaderActivity>(NovelReaderActivity.intent(context,novel.id,chapters.first().id)).use {reader->
                ready(reader)
                assertEquals("The first chapter is fetched only by explicit foreground reading in private mode",
                    callsBeforeReader+1,fixture.calls.get())
                delay(1_200)
                assertEquals("Private reading does not start a speculative following-chapter request",
                    callsBeforeReader+1,fixture.calls.get())
                if(!device.hasObject(By.desc("Next chapter"))) tapNovelCenter(reader)
                clickDescription("Next chapter");ready(reader)
                assertEquals("An explicit next-chapter action still loads its foreground text",callsBeforeReader+2,fixture.calls.get())
                delay(1_200)
                assertEquals("Private foreground navigation does not schedule another preload",callsBeforeReader+2,fixture.calls.get())
                assertEquals("A private deliberate jump leaves the saved normal-mode point untouched",sentinel,preferences.getString(chapterKey,null))
                assertEquals(.43f,preferences.getFloat(progressKey,Float.NaN),.0001f)
                click("Return");ready(reader)
                assertEquals("Private Return restores the in-memory prior chapter",chapters.first().id,currentNovelChapterId(reader))
                withTimeout(8_000) {while(device.hasObject(By.text("Return"))) delay(50)}
                assertEquals("Consuming private Return preserves the pre-existing normal-mode point",sentinel,preferences.getString(chapterKey,null))
                assertEquals(.43f,preferences.getFloat(progressKey,Float.NaN),.0001f)
            }
        } finally {
            novelId?.let {id->
                val chapterKey="novel-return:$id:chapter";val progressKey="novel-return:$id:progress"
                preferences.edit().apply {
                    if(hadChapter) putString(chapterKey,oldChapter) else remove(chapterKey)
                    if(hadProgress) putFloat(progressKey,oldProgress) else remove(progressKey)
                }.commit()
                repo.remove(id)
            }
            graph.basePreferences.incognitoMode.set(oldPrivate)
            repo.sources.fixtures.remove(fixture.id);repo.pause(oldPaused)
        }
    }

    @Test fun catalogueDetailLibraryBatchQueueAndSavedRemovalUseActualScreens():Unit=runBlocking {
        val fixture=OriginalNovelFixture();val repo=context.appGraph.novelRepository;val previous=repo.state.value.paused
        val originalWifi=context.appGraph.downloadPreferences.downloadOnlyOverWifi.get()
        repo.sources.fixtures[fixture.id]=fixture.source
        val ids=mutableListOf<String>()
        try {
            repo.pause(true)
            ActivityScenario.launch(MainActivity::class.java).use {
                click("Browse");click("Extensions");click("Novels")
                assertNotNull("Browse → Extensions → Novels opens the inline novel manager",
                    device.wait(Until.findObject(By.text("LNReader JSON plugin repository manifest")),10_000))
                assertNotNull("The manager explains that Mihon manga repositories are incompatible",
                    device.wait(Until.findObject(By.textContains("Mihon manga extension repositories are incompatible")),10_000))
                click("Sources");click("Novels");click(fixture.name)
                assertNotNull(device.wait(Until.findObject(By.text(fixture.first)),10000))
                device.swipe(650,1600,650,800,25)
                val input=requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")),10000));input.text="reset";click("Search")
                assertNotNull(device.wait(Until.findObject(By.text(fixture.second)),10000));assertFalse(device.hasObject(By.textContains("item 20")))
                input.text="";click("Search");click(fixture.first)
                assertNotNull(device.wait(Until.findObject(By.text("Start reading")),10000));click("Add to library")
                val a=repo.get(NovelRepository.novelId(fixture.id,fixture.first))!!;ids+=a.id
                val other=repo.load(fixture.id,fixture.second);ids+=other.id;repo.favorite(other.id,true)
                val firstBatch=repo.chapters(a.id).map {it.id}
                repo.queue(a.id,firstBatch.take(1))
                click("Queue");assertNotNull(device.wait(Until.findObject(By.textContains("Paused")),10000))
                // Permit the QA emulator's connected network; restore this preference after the check.
                context.appGraph.downloadPreferences.downloadOnlyOverWifi.set(false)
                click("Resume")
                withTimeout(60000){while(repo.chapters(a.id).count {it.downloaded}<1) delay(150)}
                repo.pause(true)
                repo.queue(a.id,firstBatch.drop(1))
                val otherBatch=repo.chapters(other.id).map {it.id}
                repo.queue(other.id,otherBatch)
                val failedChapter=firstBatch.drop(1).first()
                SQLiteDatabase.openDatabase(context.getDatabasePath("arkhive-novels.db").path,null,SQLiteDatabase.OPEN_READWRITE).use { database ->
                    val failed=ContentValues().apply {put("state","failed");put("attempts",3);put("error","Authored UI state fixture")}
                    assertEquals("Only the authored fixture chapter becomes the synthetic failed queue row",
                        1,database.update("novel_queue",failed,"id=?",arrayOf(failedChapter)))
                }
                repo.flush()
                assertTrue("The queue projection exposes the authored failed row",repo.state.value.downloads.any {it.id==failedChapter && it.state=="failed"})
                assertEquals("A partial durable copy and paused queued chapters remain distinct title states",
                    1,repo.get(a.id)?.savedChapters)
                suspend fun captureLibraryFailure(reason:String) {
                    val authoredIds=listOf(a.id,other.id)
                    val flow=repo.state.value
                    val flowRows=flow.novels.filter {it.id in authoredIds}
                    val flowQueue=flow.downloads.filter {it.novelId in authoredIds}
                    val repositoryRows=authoredIds.map {repo.get(it)}
                    val marks=authoredIds.joinToString(",") {"?"}
                    val databaseRows=runCatching {
                        SQLiteDatabase.openDatabase(context.getDatabasePath("arkhive-novels.db").path,null,SQLiteDatabase.OPEN_READONLY).use {database->
                            val novels=database.rawQuery("SELECT id,name,favorite,last_chapter,last_read FROM novels WHERE id IN ($marks)",authoredIds.toTypedArray()).use {cursor->
                                buildList {while(cursor.moveToNext()) add("${cursor.getString(0)}|${cursor.getString(1)}|favorite=${cursor.getInt(2)}|lastChapter=${cursor.getString(3)}|lastRead=${cursor.getLong(4)}")}
                            }
                            val chapters=database.rawQuery("SELECT novel_id,COUNT(*),SUM(saved) FROM novel_chapters WHERE novel_id IN ($marks) GROUP BY novel_id",authoredIds.toTypedArray()).use {cursor->
                                buildList {while(cursor.moveToNext()) add("${cursor.getString(0)}|chapters=${cursor.getInt(1)}|saved=${cursor.getInt(2)}")}
                            }
                            val queues=database.rawQuery("SELECT c.novel_id,q.state,COUNT(*) FROM novel_queue q JOIN novel_chapters c ON c.id=q.id WHERE c.novel_id IN ($marks) GROUP BY c.novel_id,q.state",authoredIds.toTypedArray()).use {cursor->
                                buildList {while(cursor.moveToNext()) add("${cursor.getString(0)}|${cursor.getString(1)}=${cursor.getInt(2)}")}
                            }
                            "novels=$novels chapters=$chapters queues=$queues"
                        }
                    }.getOrElse {"databaseReadError=$it"}
                    val visibleRows=runCatching {device.findObjects(By.clazz("android.widget.TextView")).joinToString {"${it.text}@${it.visibleBounds.flattenToString()}"}}.getOrElse {"visibleTextReadError=$it"}
                    val relevantRows=runCatching {device.findObjects(By.clazz("android.widget.TextView")).filter {
                        val text=it.text.orEmpty();text.contains("saved",true)||text.contains("queued",true)||text.contains("failed",true)||text.contains(fixture.first,true)||text.contains(fixture.second,true)
                    }.joinToString {"${it.text}@${it.visibleBounds.flattenToString()}"}}.getOrElse {"relevantRowsReadError=$it"}
                    val scrollBounds=runCatching {device.findObjects(By.scrollable(true)).joinToString {it.visibleBounds.flattenToString()}}.getOrElse {"scrollBoundsReadError=$it"}
                    val libraryTools=context.appGraph.libraryToolsStore.state.value
                    captureFailureEvidence("library-novel-sequence", "reason=$reason,fixtureTitles=${fixture.first}|${fixture.second},ids=$authoredIds,"+
                        "paused=${flow.paused},flowNovels=$flowRows,flowQueue=$flowQueue,repositoryRows=$repositoryRows,database=$databaseRows,"+
                        "statsEnabled=${libraryTools.statsEnabled},profiles=${authoredIds.map {"novel:$it" to libraryTools.profiles["novel:$it"]}},"+
                        "visibleTextViews=$visibleRows,relevantRows=$relevantRows,scrollBounds=$scrollBounds")
                }
                try {
                    device.pressBack();assertNotNull(device.wait(Until.findObject(By.text("Start reading")),10000))
                    device.pressBack();device.pressBack();device.pressBack();click("Library");click("Novels")
                    clickDescription("Search library")
                    val librarySearch=requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")),10_000))
                    librarySearch.text=fixture.first
                    assertEquals("Filter the actual Library grid to the partial-download fixture title",fixture.first,librarySearch.text)
                    var imeVisible=false
                    instrument.runOnMainSync {
                        imeVisible=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<MainActivity>().singleOrNull()?.let {activity->
                                ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true
                            }==true
                    }
                    if(imeVisible) device.pressBack()
                    val firstTitle=requireNotNull(device.wait(Until.findObject(By.text(fixture.first)),10_000))
                    assertTrue("The exact filtered authored title is visible in the Library grid",firstTitle.visibleBounds.width()>0)
                    fun mixedStatusVisible()=device.hasObject(By.textContains("1/6 saved")) &&
                        device.hasObject(By.textContains("queued · paused")) && device.hasObject(By.textContains("1 failed"))
                    if(!mixedStatusVisible()) {
                        val grid=requireNotNull(device.findObjects(By.scrollable(true)).maxByOrNull {node->
                            runCatching {node.visibleBounds.width()*node.visibleBounds.height()}.getOrDefault(0)
                        }) {"The filtered Library grid exposes no scrollable viewport"}
                        val bounds=grid.visibleBounds
                        assertTrue("The actual Library grid has a usable visible viewport: $bounds",bounds.width()>0 && bounds.height()>200)
                        val x=bounds.centerX()
                        device.swipe(x,bounds.bottom-bounds.height()/6,x,bounds.top+bounds.height()/6,25)
                    }
                    assertNotNull("The saved-title card exposes its partial durable chapter count",
                        device.wait(Until.findObject(By.textContains("1/6 saved")),10_000))
                    assertNotNull("The saved-title card says paused work is queued, not downloading",
                        device.wait(Until.findObject(By.textContains("queued · paused")),10_000))
                    assertNotNull("The same saved-title card exposes its failed queue row",device.wait(Until.findObject(By.textContains("1 failed")),10_000))
                    val evidence=File(context.getExternalFilesDir(null),"arkhive-qa").apply {mkdirs()}
                    assertTrue("Capture authored mixed offline/paused-queue Library state",device.takeScreenshot(File(evidence,"library-novel-mixed-offline-queue.png")))
                    repo.retry(failedChapter);repo.flush();repo.pause(false)
                    withTimeout(60_000) {
                        while(repo.chapters(a.id).count {it.downloaded}<firstBatch.size ||
                            repo.chapters(other.id).count {it.downloaded}<otherBatch.size ||
                            repo.state.value.downloads.any {it.novelId==a.id || it.novelId==other.id}) delay(150)
                    }
                    assertEquals("Resuming the authored batch saves every chapter in its first title",
                        firstBatch.size,repo.chapters(a.id).count {it.downloaded})
                    assertEquals("Resuming the authored batch saves every chapter in its second title",
                        otherBatch.size,repo.chapters(other.id).count {it.downloaded})
                    assertFalse("Both authored download queues drain completely",repo.state.value.downloads.any {it.novelId==a.id || it.novelId==other.id})
                    repo.pause(true)
                    click(fixture.first);assertNotNull(device.wait(Until.findObject(By.text("Start reading")),10_000))
                    click("Select");click("Select all");click("Remove saved");click("Remove");click("Done")
                    assertEquals(0,repo.chapters(a.id).count {it.cached})
                } catch(error:Throwable) {
                    runCatching {captureLibraryFailure(error.toString())}
                    throw error
                }
            }
        } finally {ids.forEach {repo.remove(it)};repo.sources.fixtures.remove(fixture.id);repo.pause(previous);context.appGraph.downloadPreferences.downloadOnlyOverWifi.set(originalWifi)}
    }

    @Test fun onlineNovelCenterChromePreservesVisiblePassageInPagesAndContinuous()=runBlocking {
        val fixture=OriginalNovelFixture(distinctivePages=true)
        val appearancePreferences=context.getSharedPreferences("arkhive_book_reader",0)
        val hadFont=appearancePreferences.contains("font")
        val oldFont=appearancePreferences.getFloat("font",100f)
        val testFont=if(kotlin.math.abs(oldFont-145f)<.1f) 155f else 145f
        appearancePreferences.edit().putFloat("font",testFont).commit()
        val graph=context.appGraph
        val repo=graph.novelRepository
        val wasPaused=repo.state.value.paused
        val profileKey="novel:${NovelRepository.novelId(fixture.id,fixture.first)}"
        val hadPriorProfile=graph.libraryToolsStore.state.value.profiles.containsKey(profileKey)
        val priorProfile=graph.libraryToolsStore.profile(profileKey)
        var novelId:String?=null
        repo.sources.fixtures[fixture.id]=fixture.source
        try {
            repo.pause(true)
            val novel=repo.load(fixture.id,fixture.first)
            novelId=novel.id
            repo.favorite(novel.id,true)
            val chapter=repo.chapters(novel.id).first()
            val intent=NovelReaderActivity.intent(context,novel.id,chapter.id)
            for(layout in listOf("pages","scroll")) {
                graph.libraryToolsStore.setProfile(profileKey,priorProfile.copy(layout=layout,room=mihon.readingapp.library.ReadingRoom.INK_BLACK))
                ActivityScenario.launch<NovelReaderActivity>(intent).use { reader ->
                    if(layout=="pages") readyPages(reader) else ready(reader)
                    val rendered=renderedNovelAppearance(reader,"center-toggle regression at ${testFont.toInt()}% reader font",novel.id,fixture.id)
                    val actualFont=rendered.optString("font").removeSuffix("px").toFloatOrNull()
                    var fontScale=1f
                    reader.onActivity { fontScale=it.resources.configuration.fontScale }
                    assertNotNull("The authored fixture reports its rendered CSS font size: $rendered",actualFont)
                    assertEquals("The center-toggle regression really uses the changed ${testFont.toInt()}% reader font",
                        21f*testFont/100f*fontScale,requireNotNull(actualFont),.5f)
                    assertNotNull("The overlaid reader header exposes its appearance action",device.wait(Until.findObject(By.text("Aa")),3_000))
                    click("Aa")
                    assertNotNull("The appearance action remains clickable above the publication",device.wait(Until.findObject(By.text("Reading layout & appearance")),3_000))
                    click("Done")
                    assertNotNull("The overlaid chapter controls expose Contents",device.wait(Until.findObject(By.text("Contents")),3_000))
                    click("Contents")
                    assertNotNull("The footer action opens the chapter picker",device.wait(Until.findObject(By.text("Chapters")),3_000))
                    click("Close")
                    reader.onActivity { activity ->
                        val web=requireNotNull(findWeb(activity.window.decorView))
                        if(layout=="pages") web.scrollTo(web.width.coerceAtLeast(1),0)
                        else web.scrollTo(0,(web.height*2).coerceAtMost(web.end()))
                    }
                    if(layout=="pages") awaitStablePageViewport(reader) else awaitStableVerticalGeometry(reader)
                    awaitNovelVisualState(reader)
                    assertReaderOverlaysCompact(reader,"before $layout toggle")
                    val beforeGeometry=if(layout=="pages") horizontalGeometry(reader) else geometry(reader)
                    val beforePageViewport=if(layout=="pages") pageViewport(reader) else null
                    assertTrue("The authored fixture begins away from its opening position in $layout: $beforeGeometry",
                        beforeGeometry.first>0)
                    val before=visibleNovelPassage(reader,novel.id,fixture.id)
                    awaitNovelVisualState(reader)
                    assertTrue("Capture the exact-passage starting state for $layout",
                        device.takeScreenshot(File(context.cacheDir,"qa-novel-center-$layout-before.png")))
                    repeat(2) { toggle ->
                        tapNovelCenter(reader)
                        val afterGeometry=if(layout=="pages") {
                            awaitStablePageViewport(reader).let { Triple(it.x,it.endX,it.width) }
                        } else awaitStableVerticalGeometry(reader)
                        awaitNovelVisualState(reader)
                        assertReaderOverlaysCompact(reader,"$layout toggle $toggle")
                        File(context.cacheDir,"qa-novel-center-painted-state.txt").appendText(
                            "$layout toggle $toggle paragraph=$before geometry=$afterGeometry layers=${readerOverlayDiagnostics(reader)}\n")
                        if(layout=="pages") assertEquals("Pages chrome toggle $toggle does not resize or repaginate the publication",
                            beforePageViewport,pageViewport(reader))
                        else assertEquals("Continuous chrome toggle $toggle leaves the viewport and scroll offset unchanged",
                            beforeGeometry,afterGeometry)
                        var reflowPending=true
                        reader.onActivity { activity->reflowPending=activity.javaClass.getDeclaredField("viewportReflowPending").apply {isAccessible=true}.getBoolean(activity) }
                        assertFalse("Center chrome toggle $toggle does not leave a phantom viewport reflow pending",reflowPending)
                        assertEquals("Center chrome toggle $toggle preserves the exact visible authored paragraph in $layout",
                            before,visibleNovelPassage(reader,novel.id,fixture.id))
                        if(layout=="scroll") assertEquals("Continuous center chrome toggle $toggle preserves exact scrollY",
                            beforeGeometry.first,afterGeometry.first)
                        assertEquals("Center chrome toggle $toggle does not change the current chapter",
                            chapter.id,currentNovelChapterId(reader))
                        awaitNovelVisualState(reader)
                        assertTrue("Capture center chrome toggle $toggle state for $layout",
                            device.takeScreenshot(File(context.cacheDir,"qa-novel-center-$layout-toggle-$toggle.png")))
                    }
                }
            }
        } finally {
            novelId?.let { repo.remove(it) }
            graph.libraryToolsStore.update { state -> state.copy(profiles=if(hadPriorProfile)
                state.profiles+(profileKey to priorProfile) else state.profiles-profileKey) }
            repo.sources.fixtures.remove(fixture.id)
            repo.pause(wasPaused)
            appearancePreferences.edit().apply {
                if(hadFont) putFloat("font",oldFont) else remove("font")
            }.commit()
        }
    }

    @Test fun onlineNovelCenterCssLayoutDiagnosticsUseSeparateFixtureSessions()=runBlocking {
        val fixture=OriginalNovelFixture(distinctivePages=true)
        val graph=context.appGraph
        val repo=graph.novelRepository
        val wasPaused=repo.state.value.paused
        var novelId:String?=null
        var profileKey:String?=null
        var hadPriorProfile=false
        var priorProfile=mihon.readingapp.library.ReadingProfile()
        repo.sources.fixtures[fixture.id]=fixture.source
        val report=File(context.cacheDir,"qa-novel-center-css-layout.txt")
        report.writeText("Separate authored-fixture CSS layout diagnostic; JavaScript is enabled only inside renderedNovelAppearance and restored before each center tap.\n")
        try {
            repo.pause(true)
            val novel=repo.load(fixture.id,fixture.first)
            novelId=novel.id
            profileKey="novel:${novel.id}"
            hadPriorProfile=graph.libraryToolsStore.state.value.profiles.containsKey(requireNotNull(profileKey))
            priorProfile=graph.libraryToolsStore.profile(requireNotNull(profileKey))
            repo.favorite(novel.id,true)
            val chapter=repo.chapters(novel.id).first()
            val intent=NovelReaderActivity.intent(context,novel.id,chapter.id)
            for(layout in listOf("pages","scroll")) {
                graph.libraryToolsStore.setProfile(requireNotNull(profileKey),priorProfile.copy(
                    layout=layout,room=mihon.readingapp.library.ReadingRoom.INK_BLACK))
                ActivityScenario.launch<NovelReaderActivity>(intent).use { reader ->
                    if(layout=="pages") readyPages(reader) else ready(reader)
                    reader.onActivity { activity ->
                        val web=requireNotNull(findWeb(activity.window.decorView))
                        if(layout=="pages") web.scrollTo(web.width.coerceAtLeast(1),0)
                        else web.scrollTo(0,(web.height*2).coerceAtMost(web.end()))
                    }
                    if(layout=="pages") awaitStablePageViewport(reader) else awaitStableVerticalGeometry(reader)
                    awaitNovelVisualState(reader)
                    val nativeBefore=if(layout=="pages") horizontalGeometry(reader) else geometry(reader)
                    val cssBefore=renderedNovelAppearance(reader,"$layout CSS before center tap",novel.id,fixture.id)
                    tapNovelCenter(reader)
                    if(layout=="pages") awaitStablePageViewport(reader) else awaitStableVerticalGeometry(reader)
                    awaitNovelVisualState(reader)
                    val cssAfter=renderedNovelAppearance(reader,"$layout CSS after center tap",novel.id,fixture.id)
                    val nativeAfter=if(layout=="pages") horizontalGeometry(reader) else geometry(reader)
                    report.appendText("\n$layout nativeBefore=$nativeBefore nativeAfter=$nativeAfter\nCSS_BEFORE=$cssBefore\nCSS_AFTER=$cssAfter\n")
                    assertEquals("CSS diagnostic center tap leaves native $layout viewport geometry unchanged",nativeBefore,nativeAfter)
                    reader.onActivity { activity ->
                        val web=requireNotNull(findWeb(activity.window.decorView))
                        assertFalse("Separate CSS diagnostic restores the production JS-disabled posture",web.settings.javaScriptEnabled)
                    }
                }
            }
        } finally {
            novelId?.let { repo.remove(it) }
            profileKey?.let { key -> graph.libraryToolsStore.update { state -> state.copy(
                profiles=if(hadPriorProfile) state.profiles+(key to priorProfile) else state.profiles-key) } }
            repo.sources.fixtures.remove(fixture.id)
            repo.pause(wasPaused)
        }
    }

    @Test fun onlineNovelCenterChromePaintsInFreshJavaScriptDisabledSessions()=runBlocking {
        val fixture=OriginalNovelFixture(distinctivePages=true)
        val appearancePreferences=context.getSharedPreferences("arkhive_book_reader",0)
        val hadFont=appearancePreferences.contains("font")
        val oldFont=appearancePreferences.getFloat("font",100f)
        val testFont=if(kotlin.math.abs(oldFont-145f)<.1f) 155f else 145f
        appearancePreferences.edit().putFloat("font",testFont).commit()
        val graph=context.appGraph
        val repo=graph.novelRepository
        val wasPaused=repo.state.value.paused
        var novelId:String?=null
        var profileKey:String?=null
        var hadPriorProfile=false
        var priorProfile=mihon.readingapp.library.ReadingProfile()
        repo.sources.fixtures[fixture.id]=fixture.source
        val screenshots=File(context.cacheDir,"qa-novel-pure-no-js.txt")
        screenshots.writeText("font=${testFont.toInt()}%\n")
        try {
            repo.pause(true)
            val novel=repo.load(fixture.id,fixture.first)
            novelId=novel.id
            profileKey="novel:${novel.id}"
            hadPriorProfile=graph.libraryToolsStore.state.value.profiles.containsKey(requireNotNull(profileKey))
            priorProfile=graph.libraryToolsStore.profile(requireNotNull(profileKey))
            repo.favorite(novel.id,true)
            val chapter=repo.chapters(novel.id).first()
            val intent=NovelReaderActivity.intent(context,novel.id,chapter.id)
            for(layout in listOf("pages","scroll")) {
                graph.libraryToolsStore.setProfile(requireNotNull(profileKey),priorProfile.copy(
                    layout=layout,room=mihon.readingapp.library.ReadingRoom.INK_BLACK))
                ActivityScenario.launch<NovelReaderActivity>(intent).use { reader ->
                    if(layout=="pages") readyPages(reader) else ready(reader)
                    reader.onActivity { activity ->
                        val web=requireNotNull(findWeb(activity.window.decorView))
                        assertFalse("A fresh rendered-only session keeps production JavaScript disabled",web.settings.javaScriptEnabled)
                        if(layout=="pages") web.scrollTo(web.width.coerceAtLeast(1),0)
                        else web.scrollTo(0,(web.height*2).coerceAtMost(web.end()))
                    }
                    val before=if(layout=="pages") awaitStablePageViewport(reader).let { Triple(it.x,it.endX,it.width) }
                        else awaitStableVerticalGeometry(reader)
                    assertTrue("The fresh $layout paint begins away from the opening position: $before",before.first>0)
                    awaitNovelVisualState(reader)
                    screenshots.appendText("$layout before ${readerWindowDiagnostics(reader)}\n")
                    assertReaderOverlaysCompact(reader,"pure $layout before toggle")
                    assertTrue("Capture clean production-state starting paint for $layout",
                        device.takeScreenshot(File(context.cacheDir,"qa-novel-pure-$layout-before.png")))
                    val beforeShellPath="/sdcard/Download/qa-novel-pure-$layout-before-surface.png"
                    val beforeShellResult=device.executeShellCommand("screencap -p $beforeShellPath")
                    screenshots.appendText("$layout before shellCapture=$beforeShellPath result=$beforeShellResult\n")
                    repeat(2) { toggle ->
                        tapNovelCenter(reader)
                        val after=if(layout=="pages") awaitStablePageViewport(reader).let { Triple(it.x,it.endX,it.width) }
                            else awaitStableVerticalGeometry(reader)
                        awaitNovelVisualState(reader)
                        reader.onActivity { activity ->
                            val web=requireNotNull(findWeb(activity.window.decorView))
                            assertFalse("Production JavaScript stays disabled after the $layout center tap",web.settings.javaScriptEnabled)
                        }
                        assertEquals("The no-probe center toggle $toggle preserves $layout geometry",before,after)
                        assertReaderOverlaysCompact(reader,"pure $layout toggle $toggle")
                        screenshots.appendText("$layout toggle $toggle geometry=$after window=${readerWindowDiagnostics(reader)} layers=${readerOverlayDiagnostics(reader)}\n")
                        assertTrue("Capture clean production-state $layout toggle $toggle paint",
                            device.takeScreenshot(File(context.cacheDir,"qa-novel-pure-$layout-toggle-$toggle.png")))
                        val shellPath="/sdcard/Download/qa-novel-pure-$layout-toggle-$toggle-surface.png"
                        val shellResult=device.executeShellCommand("screencap -p $shellPath")
                        screenshots.appendText("$layout toggle $toggle shellCapture=$shellPath result=$shellResult\n")
                    }
                    assertEquals("The clean no-probe $layout run stays on its opened chapter",chapter.id,currentNovelChapterId(reader))
                    reader.onActivity { activity ->
                        val web=requireNotNull(findWeb(activity.window.decorView))
                        web.setLayerType(View.LAYER_TYPE_SOFTWARE,null)
                    }
                    awaitNovelVisualState(reader)
                    assertTrue("Capture the test-only software-layer $layout starting paint",
                        device.takeScreenshot(File(context.cacheDir,"qa-novel-pure-$layout-software-before.png")))
                    val softwareBeforeShell="/sdcard/Download/qa-novel-pure-$layout-software-before-surface.png"
                    device.executeShellCommand("screencap -p $softwareBeforeShell")
                    tapNovelCenter(reader)
                    if(layout=="pages") awaitStablePageViewport(reader) else awaitStableVerticalGeometry(reader)
                    awaitNovelVisualState(reader)
                    screenshots.appendText("$layout software-layer toggle geometry="+
                        (if(layout=="pages") horizontalGeometry(reader) else geometry(reader))+
                        " window=${readerWindowDiagnostics(reader)}\n")
                    assertTrue("Capture the test-only software-layer $layout hidden-chrome paint",
                        device.takeScreenshot(File(context.cacheDir,"qa-novel-pure-$layout-software-toggle.png")))
                    val softwareAfterShell="/sdcard/Download/qa-novel-pure-$layout-software-toggle-surface.png"
                    device.executeShellCommand("screencap -p $softwareAfterShell")
                }
            }
        } finally {
            novelId?.let { repo.remove(it) }
            profileKey?.let { key -> graph.libraryToolsStore.update { state -> state.copy(
                profiles=if(hadPriorProfile) state.profiles+(key to priorProfile) else state.profiles-key) } }
            repo.sources.fixtures.remove(fixture.id)
            repo.pause(wasPaused)
            appearancePreferences.edit().apply { if(hadFont) putFloat("font",oldFont) else remove("font") }.commit()
        }
    }

}
