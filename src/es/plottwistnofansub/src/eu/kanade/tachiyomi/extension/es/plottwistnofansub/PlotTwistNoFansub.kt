package eu.kanade.tachiyomi.extension.es.plottwistnofansub

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class PlotTwistNoFansub : HttpSource() {

    override val name = "Plot Twist No Fansub"

    override val baseUrl = "https://plotnofansub.com"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularMangaRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("biblioteca3")
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
            addQueryParameter("m_orderby", "trending")
        }.build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select("div.page-content-listing figure, div.manga-grid-v2 figure, figure").map { element ->
            SManga.create().apply {
                val a = element.selectFirst("a")!!
                setUrlWithoutDomain(a.attr("abs:href").ifEmpty { a.attr("href") })
                title = a.attr("title").takeIf { it.isNotEmpty() }
                    ?: element.selectFirst("figcaption")?.text()
                    ?: throw Exception("Missing title for manga at ${a.attr("href")}")
                thumbnail_url = element.selectFirst("img")?.imgAttr()
            }
        }

        val hasNextPage = document.selectFirst("a.next.page-numbers, a.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("biblioteca3")
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
            addQueryParameter("m_orderby", "latest3")
        }.build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =============================== Search ===============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()

        if (query.isNotEmpty()) {
            if (page > 1) {
                url.addPathSegment("page")
                url.addPathSegment(page.toString())
            }
            url.addQueryParameter("s", query)
            url.addQueryParameter("post_type", "wp-manga")
        } else {
            url.addPathSegment("biblioteca3")
            if (page > 1) {
                url.addPathSegment("page")
                url.addPathSegment(page.toString())
            }
            url.addQueryParameter("m_orderby", "views3")
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun getFilterList(): FilterList = FilterList()

    // =========================== Manga Details ============================
    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1.mn-detail-title, .mn-detail-title, p.mn-title-block, p.titleMangaSingle, .post-title h1, .post-title h3")?.text()
                ?: throw Exception("Manga title not found")

            thumbnail_url = document.selectFirst(".mn-detail-cover-frame img, .mn-detail-cover img, .mn-cover-frame img, .thumble-container img, .summary_image img")?.imgAttr()

            description = document.selectFirst(".mn-detail-synopsis, #mn-detail-synopsis")?.text()
                ?: document.selectFirst("h2:contains(Sinopsis) + div")?.text()
                ?: document.selectFirst("#section-sinopsis p.font-light.text-white")?.text()
                ?: document.selectFirst(".summary__content")?.text()

            val genresList = document.select(".mn-detail-genres-desktop a, .mn-detail-genres-mobile a, div.text-white:contains(Gé:) + div a, #section-sinopsis div:contains(Generos:) + div a").map { it.text() }
            genre = if (genresList.isNotEmpty()) {
                genresList.joinToString()
            } else {
                document.select(".genres-content a").joinToString { it.text() }
            }

            author = document.selectFirst("div.mn-detail-pill:contains(Autor) span.mn-detail-pill-value")?.text()
                ?: document.selectFirst("div.text-white:contains(Creación:) + div a, #section-sinopsis div:contains(Autor:) + div a")?.text()
                ?: document.selectFirst(".author-content a")?.text()

            val statusText = (
                document.selectFirst("div.mn-detail-pill:contains(Estado) span.mn-detail-pill-value")?.text()
                    ?: document.selectFirst("button.mn-chip")?.text()
                    ?: document.selectFirst(".btn-completed")?.text()
                    ?: document.selectFirst(".btn-ongoing")?.text()
                    ?: document.selectFirst("button:contains(Finalizado), button:contains(En curso)")?.text()
                    ?: document.selectFirst(".post-status .summary-content")?.text()
                    ?: ""
                ).lowercase()

            status = when {
                statusText.contains("en emisión") || statusText.contains("en curso") || statusText.contains("ongoing") || statusText.contains("emisión") -> SManga.ONGOING
                statusText.contains("finalizado") || statusText.contains("completed") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ============================== Chapters ==============================
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        val mangaId = document.selectFirst("script:containsData(mnWpMangaId)")
            ?.data()
            ?.let { MANGA_ID_REGEX.find(it)?.groupValues?.get(1) }
            ?: document.selectFirst("script:containsData(manga_id)")
                ?.data()
                ?.let { OLD_MANGA_ID_REGEX.find(it)?.groupValues?.get(1) }
            ?: document.selectFirst("#mn-detail-load-more")?.attr("data-manga")
            ?: throw Exception("No se pudo encontrar el ID del manga")

        val scriptData = document.selectFirst("script:containsData(mnSeriesNavBundle)")?.data()

        val chapters = mutableListOf<SChapter>()

        // Fallback for new theme HTML chapters
        document.select("a.mn-detail-chapter-item, .contenedor-capitulo-miniatura a").forEach { a ->
            parseChapterElement(a)?.let { chapters.add(it) }
        }

        val loadMoreBtn = document.selectFirst("#mn-detail-load-more")

        if (loadMoreBtn != null) {
            // Priority 1: AJAX "Cargar más" pagination
            var page = 1
            var hasMore = true

            // Standard AJAX headers to look like a browser request and satisfy Wordfence
            val ajaxHeaders = headers.newBuilder()
                .set("Referer", response.request.url.toString())
                .add("X-Requested-With", "XMLHttpRequest")
                .add("Origin", baseUrl)
                .build()

            while (hasMore) {
                val form = FormBody.Builder()
                    .add("action", "plot_load_chapters")
                    .add("manga_id", mangaId)
                    .add("page", page.toString())
                    .build()

                val apiResponse = client.newCall(
                    POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, form),
                ).execute()

                if (!apiResponse.isSuccessful) {
                    apiResponse.close()
                    hasMore = false
                    break
                }

                val apiData = try {
                    apiResponse.parseAs<LoadChaptersApiResponse>()
                } catch (e: Exception) {
                    apiResponse.close()
                    null
                }

                if (apiData == null || !apiData.success || apiData.data?.html.isNullOrEmpty()) {
                    hasMore = false
                } else {
                    val fragment = Jsoup.parseBodyFragment(apiData.data.html)
                    fragment.select("a.mn-detail-chapter-item, .contenedor-capitulo-miniatura a").forEach { a ->
                        parseChapterElement(a)?.let { chapters.add(it) }
                    }
                    hasMore = apiData.data.has_more
                    page++
                }
            }
        } else if (scriptData != null) {
            // Priority 2: REST batch pagination
            val navCsrf = CSRF_REGEX.find(scriptData)?.groupValues?.get(1)
                ?: throw Exception("No se pudo encontrar el token")

            val batchUrl = BATCH_URL_REGEX.find(scriptData)?.groupValues?.get(1)?.replace("\\/", "/")
                ?: throw Exception("No se pudo encontrar la URL de la API")

            val totalPagesText = document.selectFirst("script:containsData(totalPageCount)")?.data()
            val totalPages = totalPagesText?.let { TOTAL_PAGES_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }

            // First page is already fetched in HTML, begin on Page 2 if needed
            var page = if (chapters.isEmpty()) 1 else 2
            var hasNextPage = true

            while (hasNextPage) {
                val form = FormBody.Builder()
                    .add("page", page.toString())
                    .add("seriesPost", mangaId)
                    .add("navCsrf", navCsrf)
                    .build()

                val apiResponse = client.newCall(
                    POST(batchUrl, headers, form),
                ).execute()

                if (!apiResponse.isSuccessful) {
                    apiResponse.close()
                    hasNextPage = false
                    break
                }

                val apiData = try {
                    apiResponse.parseAs<ChapterApiResponse>()
                } catch (e: Exception) {
                    apiResponse.close()
                    null
                }

                if (apiData == null || apiData.chapters.isEmpty()) {
                    hasNextPage = false
                } else {
                    apiData.chapters.forEach { chapter ->
                        chapters.add(
                            SChapter.create().apply {
                                setUrlWithoutDomain(chapter.link)
                                name = buildString {
                                    append("Capítulo ${chapter.name}")
                                    if (chapter.nameExtend.isNotEmpty()) {
                                        append(" - ${chapter.nameExtend}")
                                    }
                                }
                                date_upload = dateFormat.tryParse(chapter.date.replace(HTML_TAG_REGEX, ""))
                            },
                        )
                    }
                    page++
                    if (totalPages != null && page > totalPages) {
                        hasNextPage = false
                    }
                }
            }
        } else {
            // Priority 3: Old REST API (fallback)
            val getcapsJson = document.selectFirst("script:containsData(plotGetcaps)")?.data()
            if (getcapsJson != null) {
                val secret = SECRET_REGEX.find(getcapsJson)?.groupValues?.get(1)
                    ?: throw Exception("No se pudo encontrar el secreto")

                val apiUrl = REST_URL_REGEX.find(getcapsJson)?.groupValues?.get(1)?.replace("\\/", "/")
                    ?: throw Exception("No se pudo encontrar la URL de la API")

                var page = 1
                var hasNextPage = true

                while (hasNextPage) {
                    val form = FormBody.Builder()
                        .add("action", "plot_anti_hack")
                        .add("page", page.toString())
                        .add("mangaid", mangaId)
                        .add("secret", secret)
                        .build()

                    val apiResponse = client.newCall(
                        POST(apiUrl, headers, form),
                    ).execute()

                    if (!apiResponse.isSuccessful) {
                        apiResponse.close()
                        hasNextPage = false
                        break
                    }

                    val apiData = try {
                        apiResponse.parseAs<ChapterApiResponse>()
                    } catch (e: Exception) {
                        apiResponse.close()
                        null
                    }

                    if (apiData == null || apiData.chapters.isEmpty()) {
                        hasNextPage = false
                    } else {
                        apiData.chapters.forEach { chapter ->
                            chapters.add(
                                SChapter.create().apply {
                                    setUrlWithoutDomain(chapter.link)
                                    name = buildString {
                                        append("Capítulo ${chapter.name}")
                                        if (chapter.nameExtend.isNotEmpty()) {
                                            append(" - ${chapter.nameExtend}")
                                        }
                                    }
                                    date_upload = dateFormat.tryParse(chapter.date.replace(HTML_TAG_REGEX, ""))
                                },
                            )
                        }
                        page++
                    }
                }
            }
        }

        return chapters
    }

    private fun parseChapterElement(a: Element): SChapter? {
        val url = a.attr("abs:href").ifEmpty { a.attr("href") }
        if (url.isEmpty()) return null
        return SChapter.create().apply {
            setUrlWithoutDomain(url)
            val num = a.selectFirst(".mn-detail-chapter-name")?.text()?.trim()
                ?: a.selectFirst("div.text-sm")?.text()?.trim()
                ?: ""
            val title = a.selectFirst(".mn-detail-chapter-extend")?.text()?.trim()
                ?: a.select("div.text-sm").getOrNull(1)?.text()?.trim()
                ?: ""
            name = buildString {
                append("Capítulo $num")
                if (title.isNotEmpty()) append(" - $title")
            }
            date_upload = dateFormat.tryParse(
                a.selectFirst(".mn-detail-chapter-date, time")?.text()?.replace(HTML_TAG_REGEX, ""),
            )
        }
    }

    // =============================== Pages ================================
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("div.cp-frame img, .cp-pic, div.pg-box img, div.page-break img, div.rn-wrap img, img.rn-img, .rn-img, div.rd-panel img, img.rd-pic, .rd-pic").mapIndexed { i, img ->
            Page(i, imageUrl = img.imgAttr())
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================
    private fun Element.imgAttr(): String {
        val url = when {
            hasAttr("data-src") -> attr("data-src")
            hasAttr("data-lazy-src") -> attr("data-lazy-src")
            hasAttr("srcset") -> attr("srcset").substringBefore(" ")
            else -> attr("src")
        }.trim()

        val decodedUrl = url.replace("\\/", "/")
        return if (decodedUrl.startsWith("http")) {
            decodedUrl
        } else if (decodedUrl.startsWith("//")) {
            "https:$decodedUrl"
        } else {
            baseUrl + decodedUrl
        }
    }

    private val dateFormat by lazy {
        SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)
    }

    companion object {
        private val MANGA_ID_REGEX = Regex("""mnWpMangaId\s*=\s*(\d+)""")
        private val OLD_MANGA_ID_REGEX = Regex(""""manga_id"\s*:\s*"(\d+)"""")
        private val TOTAL_PAGES_REGEX = Regex("""totalPageCount\s*=\s*(\d+)""")
        private val SECRET_REGEX = Regex(""""secret"\s*:\s*"([^"]+)"""")
        private val REST_URL_REGEX = Regex(""""restUrl"\s*:\s*"([^"]+)"""")
        private val CSRF_REGEX = Regex(""""navCsrf"\s*:\s*"([^"]+)"""")
        private val BATCH_URL_REGEX = Regex(""""batchUrl"\s*:\s*"([^"]+)"""")
        private val HTML_TAG_REGEX = Regex("<[^>]*>")
    }
}
