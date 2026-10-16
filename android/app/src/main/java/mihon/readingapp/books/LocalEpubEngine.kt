package mihon.readingapp.books

import android.content.Context
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

class LocalEpubEngine(context: Context) {
    private val http = object : HttpClient {
        override suspend fun stream(request: HttpRequest): Try<HttpStreamResponse, HttpError> =
            Try.failure(HttpError.Redirection(DebugError("Imported books cannot request external network resources.")))
    }
    private val assets = AssetRetriever(context.contentResolver, http)
    private val opener = PublicationOpener(DefaultPublicationParser(context, httpClient = http, assetRetriever = assets, pdfFactory = null))
    suspend fun open(file: File): Publication {
        val asset = assets.retrieve(file.toUrl(isDirectory = false)).getOrElse { error("This local publication could not be opened.") }
        val publication = opener.open(asset, allowUserInteraction = false).getOrElse {
            asset.close(); error("This EPUB is unsupported, damaged or protected.")
        }
        require(publication.conformsTo(Publication.Profile.EPUB)) { "This file is not a supported EPUB publication." }
        return publication
    }
}
