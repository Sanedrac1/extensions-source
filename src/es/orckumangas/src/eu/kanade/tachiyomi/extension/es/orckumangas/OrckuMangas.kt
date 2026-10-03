package eu.kanade.tachiyomi.extension.es.orckumangas

import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.addCookie
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlin.time.Duration.Companion.seconds

@Source
abstract class OrckuMangas : KeiSource() {

    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    override fun Headers.Builder.configureHeaders() = apply {
        add("Cookie", "orcku_mayor_edad=1")
    }

    override fun OkHttpClient.Builder.configureClient() = apply {
        addCookie("orcku_mayor_edad" to "1")
        rateLimit(3, 1.seconds)
        addInterceptor(::ageGateInterceptor)
    }

    private fun ageGateInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = baseUrl.toHttpUrl()
        val cookie = Cookie.Builder()
            .name("orcku_mayor_edad")
            .value("1")
            .domain(url.host)
            .path("/")
            .build()
        client.cookieJar.saveFromResponse(url, listOf(cookie))

        val response = chain.proceed(request)
        if (!request.url.encodedPath.contains("confirmar_edad.php") &&
            response.header("Content-Type")?.contains("text/html") == true
        ) {
            val bodyPeek = response.peekBody(4096).string()
            if (bodyPeek.contains("confirmar_edad.php")) {
                val retPath = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
                val formBody = FormBody.Builder()
                    .add("confirmar", "1")
                    .add("ret", retPath)
                    .build()
                val confirmRequest = POST("$baseUrl/confirmar_edad.php", headers, formBody)
                client.newCall(confirmRequest).execute().close()
                return chain.proceed(request)
            }
        }
        return response
    }

    // ============================== Popular ==============================
    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/biblioteca?sort=vistas&page=$page").asJsoup()
        return parseSearch(document, page)
    }

    // ============================== Latest ==============================
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/biblioteca?sort=recientes&page=$page").asJsoup()
        return parseSearch(document, page)
    }

    // ============================== Deeplink ==============================
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        url.queryParameter("id") ?: return null
        return getDetails(
            SManga.create().apply {
                setUrlWithoutDomain(url.toString())
            },
        )
    }

    // ============================== Search ==============================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/biblioteca".toHttpUrl().newBuilder()
        url.addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("search", query)
        } else {
            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> if (filter.selected.isNotEmpty() && filter.selected != "0") url.addQueryParameter("genre", filter.selected)
                    is TypeFilter -> if (filter.selected.isNotEmpty()) url.addQueryParameter("type", filter.selected)
                    is StatusFilter -> if (filter.selected.isNotEmpty()) url.addQueryParameter("status", filter.selected)
                    is SortFilter -> if (filter.selected.isNotEmpty()) url.addQueryParameter("sort", filter.selected)
                    else -> {}
                }
            }
        }

        val document = client.get(url.build()).asJsoup()
        return parseSearch(document, page)
    }

    private fun parseSearch(document: Document, page: Int = 1): MangasPage {
        val mangas = document.select("a[href*='ficha?id='], div.card > a").mapNotNull { element ->
            val titleText = element.selectFirst("h2, h3, div.font-bold")?.text()?.trim()
                ?: element.text().trim()
            if (titleText.isBlank()) return@mapNotNull null

            SManga.create().apply {
                title = titleText
                setUrlWithoutDomain(element.attr("abs:href"))

                val imgElement = element.selectFirst("img")
                val styleAttr = element.selectFirst("div[style*='background-image']")?.attr("style") ?: ""
                val bgUrl = if (styleAttr.contains("url(")) {
                    styleAttr.substringAfter("url(").substringBefore(")").removeSurrounding("'").removeSurrounding("\"")
                } else {
                    ""
                }

                val rawImg = imgElement?.attr("abs:src")
                    ?.takeIf { it.isNotBlank() && !it.contains("default-cover") }
                    ?: imgElement?.attr("abs:data-src")
                        ?.takeIf { it.isNotBlank() && !it.contains("default-cover") }
                    ?: bgUrl.takeIf { it.isNotBlank() }

                thumbnail_url = when {
                    rawImg.isNullOrBlank() -> null
                    rawImg.startsWith("http") -> rawImg
                    else -> "$baseUrl/${rawImg.removePrefix("/")}"
                }
            }
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst("a[href*='page=${page + 1}']") != null ||
            document.selectFirst("a:contains(Siguiente), div.flex > a:containsOwn(Siguiente)") != null

        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Details ==============================
    override val supportRelatedMangasBySearch = true

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ) = coroutineScope {
        val details = async { if (fetchDetails) getDetails(manga) else manga }
        val chaps = async { if (fetchChapters) getChapters(manga) else chapters }
        SMangaUpdate(details.await(), chaps.await())
    }

    private suspend fun getDetails(manga: SManga): SManga {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val cardElement = document.selectFirst("main div.card:has(h1), main div.card") ?: document

        return SManga.create().apply {
            url = manga.url
            title = cardElement.selectFirst("h1")?.text()?.trim() ?: ""

            val coverImg = cardElement.selectFirst("img[src*='uploads/covers/']")?.attr("abs:src")
                ?: cardElement.selectFirst("img[src*='nsfw_cover.php']")?.attr("abs:src")
                ?: cardElement.selectFirst("img[src*='uploads/']")?.attr("abs:src")
                ?: cardElement.selectFirst("div[class*='aspect'] img, div.card img, img")?.attr("abs:src")
            thumbnail_url = coverImg

            description = cardElement.selectFirst("p.text-gray-300, div.card p, p.text-gray-400, p")?.text()?.trim()

            author = cardElement.selectFirst("div:has(> span:contains(Autor:)), div:has(> span:containsOwn(Autor))")?.ownText()?.trim()
                ?.ifBlank { null }
                ?: cardElement.selectFirst("span:contains(Autor:)")?.parent()?.ownText()?.trim()
                    ?.ifBlank { null }

            artist = cardElement.selectFirst("div:has(> span:contains(Artista:)), div:has(> span:containsOwn(Artista))")?.ownText()?.trim()
                ?.ifBlank { null }
                ?: cardElement.selectFirst("span:contains(Artista:)")?.parent()?.ownText()?.trim()
                    ?.ifBlank { null }

            val statusText = cardElement.selectFirst("div:has(> span:contains(Estado:)), div:has(> span:containsOwn(Estado))")?.ownText()?.trim()
                ?: cardElement.selectFirst("span:contains(Estado:)")?.parent()?.ownText()?.trim()
            status = parseStatus(statusText)

            genre = cardElement.select("a[href*='genre']").joinToString { it.text().trim() }
            initialized = true
        }
    }

    private fun parseStatus(status: String?): Int = when (status?.lowercase()) {
        "ongoing", "en curso" -> SManga.ONGOING
        "completed", "finalizado" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "cancelled", "cancelado" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // ============================== Chapter List ==============================
    private suspend fun getChapters(manga: SManga): List<SChapter> = coroutineScope {
        val queryId = manga.url.substringAfter("id=").substringBefore("&")
        val baseHttpUrl = "$baseUrl/ficha".toHttpUrl().newBuilder()
            .addQueryParameter("id", queryId)
            .addQueryParameter("order", "desc")
            .build()

        val document = client.get(baseHttpUrl.newBuilder().setQueryParameter("page", "1").build()).asJsoup()
        val pages = document.select("div > a[href*=page=], a[href*='page=']").mapNotNull {
            it.attr("abs:href").toHttpUrlOrNull()?.queryParameter("page")?.toIntOrNull()
        }.maxOrNull() ?: 1

        val firstPageChapters = parseChapters(document)

        if (pages <= 1) {
            firstPageChapters.distinctBy { it.url }
        } else {
            val remainingChapters = (2..pages).map { page ->
                async {
                    val pageUrl = baseHttpUrl.newBuilder().setQueryParameter("page", page.toString()).build()
                    parseChapters(client.get(pageUrl).asJsoup())
                }
            }.awaitAll().flatten()

            (firstPageChapters + remainingChapters).distinctBy { it.url }
        }
    }

    private fun parseChapters(document: Document): List<SChapter> = document.select("a[href*='capitulo?id='], .cap-grid > a.cap-card").map { element ->
        SChapter.create().apply {
            val fullText = element.text()
            val chapMatch = Regex("""Cap\.?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(fullText)
            name = chapMatch?.value
                ?: element.selectFirst(".cap-num")?.ownText()?.trim()
                ?: element.selectFirst("span, div")?.text()?.trim()
                ?: fullText.trim()

            setUrlWithoutDomain(element.attr("abs:href"))

            val dateText = element.selectFirst(".cap-date")?.text()?.trim() ?: fullText
            date_upload = parseRelativeDate(dateText)
        }
    }

    private val nonDigitRegex = Regex("""\D""")

    private fun parseRelativeDate(dateStr: String): Long {
        val lowercase = dateStr.lowercase()
        if (!lowercase.contains("hace")) {
            return dateFormat.tryParseDate(dateStr)
        }

        val number = lowercase.replace(nonDigitRegex, "").toIntOrNull() ?: return 0L
        val calendar = Calendar.getInstance()

        when {
            "segundo" in lowercase -> calendar.add(Calendar.SECOND, -number)
            "minuto" in lowercase -> calendar.add(Calendar.MINUTE, -number)
            "hora" in lowercase -> calendar.add(Calendar.HOUR_OF_DAY, -number)
            "día" in lowercase || "dia" in lowercase -> calendar.add(Calendar.DAY_OF_MONTH, -number)
            "semana" in lowercase -> calendar.add(Calendar.WEEK_OF_YEAR, -number)
            "mes" in lowercase -> calendar.add(Calendar.MONTH, -number)
            "año" in lowercase || "ano" in lowercase -> calendar.add(Calendar.YEAR, -number)
        }

        return calendar.timeInMillis
    }

    // ============================== Page List (Reader) ==============================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val images = document.select("img[src*='uploads/chapters/'], div.chapter-images img")
        return images.mapIndexed { index, element ->
            Page(index, imageUrl = element.attr("abs:src"))
        }
    }

    // ============================== Filters ==============================
    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/biblioteca").asJsoup()
        .select("select[name=genre] option")
        .filter { it.attr("value").isNotBlank() && it.attr("value") != "0" }
        .associate { it.text().trim() to it.attr("value") }
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        buildList {
            add(Filter.Header("Los filtros son ignorados si se realiza una búsqueda por texto"))
            val dynamicGenres = data?.parseAs<Map<String, String>>().orEmpty()
            val genres = if (dynamicGenres.isNotEmpty()) {
                listOf("Todos" to "0") + dynamicGenres.toList()
            } else {
                GenreFilter.defaultGenres
            }
            add(GenreFilter(genres))
            add(TypeFilter())
            add(StatusFilter())
            add(SortFilter())
        },
    )
}
