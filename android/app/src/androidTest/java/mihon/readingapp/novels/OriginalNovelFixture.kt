package mihon.readingapp.novels

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Authored HTTP responses exercise the same built-in transport/parser without external execution. */
internal class OriginalNovelFixture(val nonce:String=UUID.randomUUID().toString().take(8),private val distinctivePages:Boolean=false) {
    val id="qa.novels.$nonce"
    val first="Original Lantern Atlas $nonce"
    val second="Original Quiet Circuit $nonce"
    val name="Original novel fixture $nonce"
    val offline=AtomicBoolean(false)
    val failOnce=AtomicBoolean(false)
    val calls=AtomicInteger()
    val urls=java.util.Collections.synchronizedList(mutableListOf<String>())
    fun text()=if(distinctivePages) (1..220).joinToString("") {"<p>Original page marker ${it.toString().padStart(3,'0')}. The lantern keeper records a quiet journey in an imagined city.</p>"}
        else "<p>Original archive reading fixture. The lantern keeper records a quiet journey in an imagined city.</p>".repeat(220)
    private fun chapter(path:String)=buildJsonObject {put("parse",buildJsonObject {put("title",path);put("text","<div class='mw-parser-output'><div class='ws-noexport'>Source navigation</div><div class='prp-pages-output'>${text()}<script>attack()</script><img src='https://bad.invalid/tracker'></div></div>")})}
    private fun detail(path:String)=buildJsonObject {put("parse",buildJsonObject {
        put("title",path)
        put("text","<div class='mw-parser-output'><div class='wst-header'><span class='wst-header-title-text'>$path</span><a href='/wiki/Author:Arkhive'>Arkhive original author</a></div><div class='toc'>"+(1..6).joinToString("") {"<div class='toc-line-entry-text'><a href='/wiki/${java.net.URLEncoder.encode((path+"/Chapter $it").replace(' ','_'),"UTF-8")}'>Chapter $it · original text</a></div>"}+"</div><div class='licenseContainer'>Self-authored Arkhive fixture. No live source.</div></div>")
        put("categories",buildJsonArray {add(buildJsonObject {put("category","Novels")})})
    })}
    private fun catalog(offset:Int,query:String)=buildJsonObject {
        put("query",buildJsonObject {put("search",buildJsonArray {
            val names=if(query.contains("reset")) listOf(second) else (0..47).map {if(it==0) first else if(it==1) second else "Original shelf $nonce item $it"}.drop(offset).take(24)
            names.forEach {add(buildJsonObject {put("title",it)})}
        })})
        if(offset==0 && !query.contains("reset")) put("continue",buildJsonObject {put("sroffset",24)})
    }
    val client=OkHttpClient.Builder().addInterceptor {chain->
        val request=chain.request();urls+=request.url.toString()
        check(request.url.host=="en.wikisource.org")
        check(request.header("Cookie")==null && request.header("Authorization")==null)
        val action=request.url.queryParameter("action")
        val isChapter=action=="parse" && request.url.queryParameter("prop")=="text"
        if(isChapter) calls.incrementAndGet()
        if(offline.get()) throw java.io.IOException("Controlled offline fixture")
        val fail=isChapter && failOnce.compareAndSet(true,false)
        val body=if(fail) "{\"error\":{\"info\":\"Controlled retry fixture\"}}" else if(action=="query")
            catalog(request.url.queryParameter("sroffset")!!.toInt(),request.url.queryParameter("srsearch").orEmpty()).toString()
        else if(isChapter) chapter(request.url.queryParameter("page")!!).toString() else detail(request.url.queryParameter("page")!!).toString()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if(fail)503 else 200).message("Original fixture").body(body.toResponseBody()).build()
    }.build()
    val source:NovelSource=WikisourceNovelSource(client,id,name,0)
}
