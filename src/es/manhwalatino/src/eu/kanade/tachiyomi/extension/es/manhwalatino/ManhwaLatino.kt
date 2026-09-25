package eu.kanade.tachiyomi.extension.es.manhwalatino

import eu.kanade.tachiyomi.multisrc.madara.MadaraNoAjax
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import org.jsoup.nodes.Document
import java.text.Normalizer
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class ManhwaLatino : MadaraNoAjax() {
    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor { chain ->
            val request = chain.request()

            // Only modify Accept-Encoding for image requests to preserve Cloudflare fingerprint
            val isImageRequest = request.url.toString().substringBefore("?").let {
                it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) ||
                    it.endsWith(".png", true) || it.endsWith(".webp", true)
            }

            val newRequest = if (isImageRequest) {
                request.newBuilder().removeHeader("Accept-Encoding").build()
            } else {
                request
            }

            val response = chain.proceed(newRequest)

            if (isImageRequest && response.header("Content-Type")?.contains("application/octet-stream", true) == true) {
                val orgBody = response.body
                val newBody = orgBody.source().asResponseBody("image/jpeg".toMediaType())
                return@addInterceptor response.newBuilder()
                    .header("Content-Type", "image/jpeg")
                    .body(newBody)
                    .build()
            }

            return@addInterceptor response
        }
        rateLimit(1, 2.seconds)
    }

    override val chapterUrlSelector = "div.mini-letters > a"

    override val mangaDetailsSelectorStatus = "div.post-content_item:contains(Estado del comic) > div.summary-content"
    override val mangaDetailsSelectorDescription = "div.post-content_item:contains(Resumen) div.summary-container"
    override val pageListParseSelector = "div.page-break img.wp-manga-chapter-img"

    private var mbkToken = "43a824e1"

    override fun parseArchive(document: Document): List<SManga> {
        extractMbkToken(document)
        return super.parseArchive(document)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) {
            return super.getSearchMangaList(page, query, filters)
        }

        val slug = slugify(query)
        if (slug.isBlank()) {
            return MangasPage(emptyList(), false)
        }

        var document = fetchSearchDocument(slug, page)
        if (document == null) {
            updateMbkToken()
            document = fetchSearchDocument(slug, page) ?: return MangasPage(emptyList(), false)
        }

        return MangasPage(
            parseArchive(document),
            document.selectFirst("div.nav-previous, a.nextpostslink") != null,
        )
    }

    private suspend fun fetchSearchDocument(slug: String, page: Int): Document? {
        val pageSegment = if (page > 1) "page/$page/" else ""
        val url = "$baseUrl/search/$mbkToken/$slug/$pageSegment".toHttpUrl()
        val response = client.get(url, ensureSuccess = false)
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            if (code == 404) return null
            throw HttpException(code)
        }
        return response.asJsoup()
    }

    private suspend fun updateMbkToken(): Boolean = runCatching {
        val homeDoc = client.get(baseUrl).asJsoup()
        extractMbkToken(homeDoc)
    }.getOrDefault(false)

    private fun extractMbkToken(document: Document): Boolean {
        val inputToken = document.selectFirst("input[name=mbk_token]")?.attr("value")?.takeIf(String::isNotBlank)
        if (inputToken != null) {
            val changed = inputToken != mbkToken
            mbkToken = inputToken
            return changed
        }
        val scriptToken = tokenRegex.find(document.html())?.groupValues?.get(1)?.takeIf(String::isNotBlank)
        if (scriptToken != null) {
            val changed = scriptToken != mbkToken
            mbkToken = scriptToken
            return changed
        }
        return false
    }

    private fun slugify(text: String): String = Normalizer.normalize(text.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        .replace("[^a-z0-9]+".toRegex(), "-")
        .trim('-')

    companion object {
        private val tokenRegex = Regex("""var\s+MBK_TOKEN\s*=\s*["']([^"']+)["']""")
    }
}
