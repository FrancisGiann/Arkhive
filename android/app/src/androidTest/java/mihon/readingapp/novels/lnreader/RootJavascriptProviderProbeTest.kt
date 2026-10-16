package mihon.readingapp.novels.lnreader

import android.webkit.WebView
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Provider prerequisites, using authored arithmetic/byte probes only. No extension/network code. */
@RunWith(AndroidJUnit4::class)
class RootJavascriptProviderProbeTest {
    @Test fun isolatedProviderSupportsRequiredBoundedAsyncFeatures() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val report=JSONObject().put("provider",WebView.getCurrentWebViewPackage()?.packageName)
            .put("version",WebView.getCurrentWebViewPackage()?.versionName)
            .put("coarseSupported",JavaScriptSandbox.isSupported())
        val output=File(context.cacheDir,"qa-lnreader-provider.json")
        output.writeText(report.toString(2))
        assertTrue("A supporting system WebView provider is required",JavaScriptSandbox.isSupported())
        val connection=JavaScriptSandbox.createConnectedInstanceAsync(context)
        val sandbox=try { connection.get(10,TimeUnit.SECONDS) } catch(failure:Exception) {
            // A late service connection must be released even if this probe's wait expired.
            connection.addListener({runCatching {connection.get().close()}},Executor {it.run()})
            throw failure
        }
        try {
            val required=listOf(JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN,
                JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE,
                JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER,
                JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)
            val supported=required.associateWith {sandbox.isFeatureSupported(it)}
            report.put("features",JSONObject(supported))
                .put("isolateTermination",sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION))
            output.writeText(report.toString(2))
            assertTrue("Missing required provider features: $supported",supported.values.all {it})
            val parameters=IsolateStartupParameters().apply {
                setMaxHeapSizeBytes(64L*1024*1024)
                setMaxEvaluationReturnSizeBytes(2_000_000)
            }
            sandbox.createIsolate(parameters).use {isolate->
                assertEquals("42",isolate.evaluateJavaScriptAsync("Promise.resolve(String(6 * 7))").get(5,TimeUnit.SECONDS))
                isolate.provideNamedData("arkhive-authored-probe",byteArrayOf(42))
                assertEquals("42",isolate.evaluateJavaScriptAsync(
                    "android.consumeNamedDataAsArrayBuffer('arkhive-authored-probe').then(bytes => String(new Uint8Array(bytes)[0]))"
                ).get(5,TimeUnit.SECONDS))
            }
            report.put("authoredPromiseAndNamedDataPassed",true)
        } finally {
            sandbox.close()
            report.put("sandboxClosed",true)
            output.writeText(report.toString(2))
        }
    }
}
