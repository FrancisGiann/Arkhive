package mihon.readingapp.novels.lnreader

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LnReaderThumbnailOriginApprovalTest {
    @TempDir
    lateinit var appFiles: File

    @Test
    fun `one explicit grant is exact additive and persists`() = runBlocking {
        val (manager, transport, context) = installFixture("additive")
        val installed = manager.snapshot().installedPlugins.single()

        val blocked = blockedThumbnail(manager, installed.sourceId, "https://cdn.fixture.invalid:8443/covers/one.png")
        assertEquals("https://cdn.fixture.invalid:8443", blocked.origin)
        assertFalse(blocked.origin in requireNotNull(manager.currentInstalled(installed.sourceId)).approvedOrigins)

        manager.addThumbnailOrigin(blocked)
        val expected = installed.approvedOrigins + "https://cdn.fixture.invalid:8443"
        assertEquals(expected, requireNotNull(manager.currentInstalled(installed.sourceId)).approvedOrigins)

        val reloaded = LnReaderExtensionManager(context, transport, LnReaderSandboxRuntime(context, transport))
            .snapshot().installedPlugins.single()
        assertEquals(expected, reloaded.approvedOrigins)
    }

    @Test
    fun `redirect target is reported but never added until explicit action`() = runBlocking {
        val (manager, _, _) = installFixture("redirect")
        val installed = manager.snapshot().installedPlugins.single()

        val blocked = try {
            manager.coverFile(installed.sourceId, "https://site.fixture.invalid/redirect-cover.png")
            error("Expected the controlled redirect destination to be blocked.")
        } catch (failure: LnReaderUnapprovedCoverOriginException) {
            failure
        }
        assertEquals("https://redirected-cdn.fixture.invalid:9443", blocked.origin)
        assertEquals(installed.approvedOrigins, requireNotNull(manager.currentInstalled(installed.sourceId)).approvedOrigins)

        manager.addThumbnailOrigin(blocked.approvalSnapshot)
        assertEquals(
            installed.approvedOrigins + blocked.origin,
            requireNotNull(manager.currentInstalled(installed.sourceId)).approvedOrigins,
        )
    }

    @Test
    fun `stale trust and changed bundle snapshots cannot add an origin`() = runBlocking {
        val (manager, _, _) = installFixture("stale")
        val installed = manager.snapshot().installedPlugins.single()
        val stale = blockedThumbnail(manager, installed.sourceId, "https://old-cdn.fixture.invalid/cover.png")
        val fresh = blockedThumbnail(manager, installed.sourceId, "https://fresh-cdn.fixture.invalid/cover.png")

        manager.addThumbnailOrigin(fresh)
        assertFailsWithMessage("approved origins changed") { manager.addThumbnailOrigin(stale) }
        assertFalse("https://old-cdn.fixture.invalid:443" in requireNotNull(manager.currentInstalled(installed.sourceId)).approvedOrigins)

        val afterFreshGrant = blockedThumbnail(manager, installed.sourceId, "https://tamper-cdn.fixture.invalid/cover.png")
        val bundleFile = File(
            File(appFiles, "stale"),
            "lnreader-compatible/bundles/${installed.sourceId}/${installed.sha256}.js",
        )
        val original = bundleFile.readBytes()
        bundleFile.writeBytes(original + byteArrayOf(0))
        try {
            assertFailsWithMessage("integrity check") { manager.addThumbnailOrigin(afterFreshGrant) }
        } finally {
            bundleFile.writeBytes(original)
        }
        assertFalse("https://tamper-cdn.fixture.invalid:443" in requireNotNull(manager.currentInstalled(installed.sourceId)).approvedOrigins)
        assertTrue("https://fresh-cdn.fixture.invalid:443" in requireNotNull(manager.currentInstalled(installed.sourceId)).approvedOrigins)
    }

    @Test
    fun `origin cap rejects an otherwise valid thumbnail approval`() = runBlocking {
        val transport = FixtureTransport(REPOSITORY_URL)
        val context = context("limit")
        val manager = LnReaderExtensionManager(context, transport, LnReaderSandboxRuntime(context, transport))
        val review = stage(manager)
        val fullGrantSet = review.requiredOrigins + (0 until LN_READER_MAX_APPROVED_ORIGINS - review.requiredOrigins.size)
            .map { "https://retained-$it.fixture.invalid:443" }
        manager.installReviewed(review.reviewToken, review.sha256, fullGrantSet)
        val installed = manager.snapshot().installedPlugins.single()
        val blocked = blockedThumbnail(manager, installed.sourceId, "https://one-more.fixture.invalid/cover.png")

        assertFailsWithMessage("origin limit") { manager.addThumbnailOrigin(blocked) }
        assertEquals(fullGrantSet, requireNotNull(manager.currentInstalled(installed.sourceId)).approvedOrigins)
    }

    private suspend fun blockedThumbnail(
        manager: LnReaderExtensionManager,
        sourceId: String,
        url: String,
    ): LnReaderThumbnailOriginApprovalSnapshot = try {
        manager.coverFile(sourceId, url)
        error("Expected an unapproved thumbnail origin for $url.")
    } catch (failure: LnReaderUnapprovedCoverOriginException) {
        failure.approvalSnapshot
    }

    private suspend fun installFixture(name: String): Triple<LnReaderExtensionManager, FixtureTransport, Context> {
        val transport = FixtureTransport(REPOSITORY_URL)
        val context = context(name)
        val manager = LnReaderExtensionManager(context, transport, LnReaderSandboxRuntime(context, transport))
        val review = stage(manager)
        manager.installReviewed(review.reviewToken, review.sha256, review.requiredOrigins)
        return Triple(manager, transport, context)
    }

    private suspend fun stage(manager: LnReaderExtensionManager): LnReaderInstallReview {
        manager.addRepository("https://repo.fixture.invalid/manifest.json")
        // All test helpers use one repository identity; refresh the exact URL held by the fixture.
        return manager.snapshot().repositories.singleOrNull { it.url == "https://repo.fixture.invalid/manifest.json" }
            ?.let { manager.refreshRepository(it.url) }
            ?.let { manager.stagePlugin(it.url, "origin-safety-source") }
            ?: error("The source repository fixture was not staged.")
    }

    private fun context(name: String): Context = mockk<Context>().also { context ->
        every { context.applicationContext } returns context
        every { context.filesDir } returns File(appFiles, name).apply { mkdirs() }
    }

    private suspend fun assertFailsWithMessage(text: String, block: suspend () -> Unit) {
        try {
            block()
            error("Expected the operation to fail with a message containing '$text'.")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains(text), failure.message)
        }
    }

    private class FixtureTransport(private val repositoryUrl: String) : LnReaderHostFetch {
        private val manifest = """[
            {"id":"origin-safety-source","name":"Origin Safety Fixture","site":"https://site.fixture.invalid/","lang":"en","version":"1","url":"https://bundle.fixture.invalid/plugin.js"}
        ]""".trimIndent().toByteArray()
        private val bundle = "module.exports.default = { id: 'origin-safety-source' };".toByteArray()

        override suspend fun fetch(request: LnReaderHttpRequest, allowedOrigins: Set<String>, maxBytes: Int): LnReaderHttpResponse {
            if (request.url == repositoryUrl) return response(request.url, manifest, "application/json")
            if (request.url == "https://bundle.fixture.invalid/plugin.js") return response(request.url, bundle)
            if (request.url == "https://site.fixture.invalid/redirect-cover.png") {
                throw LnReaderUnapprovedSourceOriginException("https://redirected-cdn.fixture.invalid:9443")
            }
            error("Unexpected fixture request: ${request.url}")
        }

        private fun response(url: String, body: ByteArray, contentType: String = "text/plain") =
            LnReaderHttpResponse(url, 200, "OK", mapOf("content-type" to contentType), body)
    }

    private companion object {
        const val REPOSITORY_URL = "https://repo.fixture.invalid/manifest.json"
    }
}
