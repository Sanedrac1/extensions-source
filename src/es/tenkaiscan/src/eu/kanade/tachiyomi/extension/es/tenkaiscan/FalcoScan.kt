package eu.kanade.tachiyomi.extension.es.tenkaiscan

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class FalcoScan : HttpSource() {
    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }

    override val supportsLatest = true

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("es"))

    private val subClient by lazy {
        network.client.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    override val client = network.client.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .rateLimit(3) { it.host == baseUrlHost }
        .addInterceptor(ImageInterceptor { subClient })
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/comics", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response.asJsoup())

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("section:has(h2:contains(Recientemente)) div.card-grid a.falco-card, div.card-grid a.falco-card, a.falco-card").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                title = element.selectFirst("div.info > h4")?.text() ?: ""
                thumbnail_url = element.selectFirst("div.cover img")?.imgAttr()
            }
        }
        return MangasPage(mangas, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val urlBuilder = "$baseUrl/comics".toHttpUrl().newBuilder()

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("search", query)
        } else {
            filters.firstInstanceOrNull<AlphabeticFilter>()?.let {
                if (it.state != 0) urlBuilder.addQueryParameter("filter", it.toUriPart())
            }
            filters.firstInstanceOrNull<GenreFilter>()?.let {
                if (it.state != 0) urlBuilder.addQueryParameter("gen", it.toUriPart())
            }
            filters.firstInstanceOrNull<StatusFilter>()?.let {
                if (it.state != 0) urlBuilder.addQueryParameter("status", it.toUriPart())
            }
        }

        return GET(urlBuilder.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response.asJsoup())

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select("div.list-grid a.falco-card, a.falco-card").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                title = element.selectFirst("div.info > h4")?.text() ?: ""
                thumbnail_url = element.selectFirst("div.cover img")?.imgAttr()
            }
        }
        return MangasPage(mangas, false)
    }

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("NOTA: Los filtros serán ignorados si se realiza una búsqueda por texto."),
        Filter.Header("Solo se puede aplicar un filtro a la vez."),
        AlphabeticFilter(),
        GenreFilter(),
        StatusFilter(),
    )

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        title = document.selectFirst("div.series-main h1, h1")?.text() ?: ""
        description = document.selectFirst("p.desc")?.text()

        val coverStyle = document.selectFirst("div.series-cover, div.series-hero-bg")?.attr("style")
        val rawCover = coverStyle?.let { style ->
            val clean = style.substringAfter("url(", "").substringBefore(")", "")
            clean.trim('\'', '"').takeIf { it.isNotEmpty() }
        } ?: document.selectFirst("img.cover")?.imgAttr()

        thumbnail_url = rawCover?.let {
            if (it.startsWith("http")) it else "$baseUrl${if (it.startsWith("/")) "" else "/"}$it"
        }

        genre = document.select("div.falco-tags span.falco-tag").joinToString { it.text() }
        author = document.selectFirst("div.info-panel div.info-row:has(span.label:contains(Autor)) span.value")?.text()
        artist = document.selectFirst("div.info-panel div.info-row:has(span.label:contains(Artista)) span.value")?.text()
        status = document.selectFirst("div.info-panel div.info-row:has(span.label:contains(Status)) span.value")?.text()?.parseStatus() ?: SManga.UNKNOWN
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("div.chapters-grid a.chapter-card, a.chapter-card").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                name = element.selectFirst("div.ch-name")?.text() ?: ""
                date_upload = dateFormat.tryParse(element.selectFirst("div.ch-date")?.text())
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val canvases = document.select(".cap-canvas").filter {
            it.hasAttr("data-manifest-src") || it.hasAttr("data-src")
        }
        if (canvases.isNotEmpty()) {
            return canvases.mapIndexed { i, element ->
                val isScrambled = element.attr("data-scrambled") == "1"
                val token = element.attr("data-token")
                if (isScrambled) {
                    val manifestSrc = element.absUrl("data-manifest-src").ifEmpty { element.attr("data-manifest-src") }
                    val fragmentBase = element.absUrl("data-fragment-base").ifEmpty { element.attr("data-fragment-base") }
                    val fragmentDir = element.attr("data-fragment-dir")
                    val cleanBase = URLEncoder.encode(fragmentBase, "UTF-8")
                    val cleanDir = URLEncoder.encode(fragmentDir, "UTF-8")
                    Page(i, imageUrl = "$manifestSrc#scrambled=1&token=$token&base=$cleanBase&dir=$cleanDir")
                } else {
                    val src = element.absUrl("data-src").ifEmpty { element.attr("data-src") }
                    Page(i, imageUrl = "$src#$token")
                }
            }
        }

        return document.select("div.img-blade img, div.reader img, #canvas-reader img").mapIndexed { i, element ->
            Page(i, imageUrl = element.imgAttr())
        }
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl!!
        if (imageUrl.contains("#scrambled=1")) {
            val cleanUrl = imageUrl.substringBefore("#")
            val params = imageUrl.substringAfter("#").split("&").associate {
                val parts = it.split("=", limit = 2)
                parts[0] to (parts.getOrNull(1) ?: "")
            }
            val token = params["token"] ?: ""
            val base = URLDecoder.decode(params["base"] ?: "", "UTF-8")
            val dir = URLDecoder.decode(params["dir"] ?: "", "UTF-8")
            val imageHeaders = headersBuilder()
                .add("X-Requested-With", "XMLHttpRequest")
                .add("X-CSRF-TOKEN", token)
                .add("X-Falco-Scrambled", "1")
                .add("X-Falco-Base", base)
                .add("X-Falco-Dir", dir)
                .build()
            return GET(cleanUrl, imageHeaders)
        } else if (imageUrl.contains("#")) {
            val token = imageUrl.substringAfterLast("#")
            val cleanUrl = imageUrl.substringBeforeLast("#")
            val imageHeaders = headersBuilder()
                .add("X-Requested-With", "XMLHttpRequest")
                .add("X-CSRF-TOKEN", token)
                .build()
            return GET(cleanUrl, imageHeaders)
        }
        return super.imageRequest(page)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun Element.imgAttr(): String = when {
        this.hasAttr("data-src") -> this.absUrl("data-src")
        else -> this.absUrl("src")
    }

    private fun String.parseStatus() = when (this.lowercase()) {
        "en emisión", "en emision" -> SManga.ONGOING
        "finalizado" -> SManga.COMPLETED
        "cancelado" -> SManga.CANCELLED
        "en espera" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }
}
