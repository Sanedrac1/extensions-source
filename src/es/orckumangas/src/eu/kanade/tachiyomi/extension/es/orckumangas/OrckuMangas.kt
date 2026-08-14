package eu.kanade.tachiyomi.extension.es.orckumangas

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.util.Calendar
import kotlin.time.Duration.Companion.seconds

@Source
abstract class OrckuMangas : HttpSource() {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(3, 1.seconds)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Cookie", "orcku_mayor_edad=1")

    // ============================== Popular ==============================
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/biblioteca?sort=vistas&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    // ============================== Latest ==============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/biblioteca?sort=recientes&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================== Search ==============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
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

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("a[href*='ficha?id=']").mapNotNull { element ->
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
                    ?: bgUrl.takeIf { it.isNotBlank() }

                thumbnail_url = when {
                    rawImg.isNullOrBlank() -> null
                    rawImg.startsWith("http") -> rawImg
                    else -> "$baseUrl/${rawImg.removePrefix("/")}"
                }
            }
        }.distinctBy { it.url }

        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = document.selectFirst("a[href*='page=${page + 1}']") != null ||
            document.selectFirst("a:contains(Siguiente)") != null

        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Details ==============================
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text()?.trim() ?: ""

            val coverImg = document.selectFirst("img[src*='uploads/covers/']")?.attr("abs:src")
                ?: document.selectFirst("img[src*='uploads/']")?.attr("abs:src")
            thumbnail_url = coverImg

            description = document.selectFirst("p.text-gray-300, div.card p")?.text()?.trim()

            author = document.selectFirst("div:contains(Autor:), p:contains(Autor:)")?.text()
                ?.substringAfter("Autor:")?.trim()
            artist = document.selectFirst("div:contains(Artista:), p:contains(Artista:)")?.text()
                ?.substringAfter("Artista:")?.trim()

            val statusText = document.selectFirst("div:contains(Estado:), p:contains(Estado:)")?.text()
                ?.substringAfter("Estado:")?.trim()
            status = parseStatus(statusText)

            genre = document.select("a[href*='genre=']").joinToString { it.text().trim() }
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
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("a[href*='capitulo?id=']").map { element ->
            SChapter.create().apply {
                val fullText = element.text()
                val chapMatch = Regex("""Cap\.?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(fullText)
                name = chapMatch?.value ?: element.selectFirst("span, div")?.text()?.trim() ?: fullText.trim()
                setUrlWithoutDomain(element.attr("abs:href"))

                date_upload = parseRelativeDate(fullText)
            }
        }.distinctBy { it.url }
    }

    private fun parseRelativeDate(dateStr: String): Long {
        val lowercase = dateStr.lowercase()
        val calendar = Calendar.getInstance()

        val amount = Regex("""\d+""").find(lowercase)?.value?.toIntOrNull() ?: return 0L

        return when {
            "minuto" in lowercase -> calendar.apply { add(Calendar.MINUTE, -amount) }.timeInMillis
            "hora" in lowercase -> calendar.apply { add(Calendar.HOUR_OF_DAY, -amount) }.timeInMillis
            "día" in lowercase || "dia" in lowercase -> calendar.apply { add(Calendar.DAY_OF_MONTH, -amount) }.timeInMillis
            "semana" in lowercase -> calendar.apply { add(Calendar.WEEK_OF_YEAR, -amount) }.timeInMillis
            "mes" in lowercase -> calendar.apply { add(Calendar.MONTH, -amount) }.timeInMillis
            "año" in lowercase || "ano" in lowercase -> calendar.apply { add(Calendar.YEAR, -amount) }.timeInMillis
            else -> 0L
        }
    }

    // ============================== Page List (Reader) ==============================
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("img[src*='uploads/chapters/']").mapIndexed { index, element ->
            Page(index, imageUrl = element.attr("abs:src"))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Filters ==============================
    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Los filtros se ignoran si se realiza una búsqueda por texto"),
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
        SortFilter(),
    )

    private class GenreFilter :
        SelectFilter(
            "Género",
            arrayOf(
                Pair("Todos", "0"),
                Pair("A color", "39"),
                Pair("Acción", "1"),
                Pair("Adaptación", "36"),
                Pair("Adulto", "22"),
                Pair("Ahegao", "23"),
                Pair("Animales", "51"),
                Pair("Antología", "37"),
                Pair("Artes Marciales", "7"),
                Pair("Aventura", "2"),
                Pair("Bisexual", "47"),
                Pair("Bukkake", "53"),
                Pair("Cheating", "29"),
                Pair("Chotas", "44"),
                Pair("Comedia", "3"),
                Pair("Creampie", "28"),
                Pair("Crossdressing", "38"),
                Pair("Demonios", "25"),
                Pair("Deportes", "18"),
                Pair("Drama", "4"),
                Pair("Ecchi", "5"),
                Pair("Fantasía", "6"),
                Pair("Fetish", "45"),
                Pair("Full Color", "30"),
                Pair("Futanari", "26"),
                Pair("Gender Bender", "32"),
                Pair("Gore", "20"),
                Pair("Harem", "8"),
                Pair("Hentai", "21"),
                Pair("Histórico", "19"),
                Pair("Horror", "10"),
                Pair("Incesto", "27"),
                Pair("Isekai", "17"),
                Pair("Josei", "16"),
                Pair("Misterio", "11"),
                Pair("Psicológico", "12"),
                Pair("Romance", "13"),
                Pair("Seinen", "14"),
                Pair("Shoujo", "15"),
                Pair("Shounen", "9"),
                Pair("Supervivencia", "33"),
                Pair("Tragedia", "35"),
                Pair("Vampiros", "31"),
                Pair("Vanilla", "34"),
                Pair("Yaoi", "49"),
                Pair("Yuri", "48"),
            ),
        )

    private class TypeFilter :
        SelectFilter(
            "Tipo",
            arrayOf(
                Pair("Todos", ""),
                Pair("Manga", "manga"),
                Pair("Manhwa", "manhwa"),
                Pair("Manhua", "manhua"),
            ),
        )

    private class StatusFilter :
        SelectFilter(
            "Estado",
            arrayOf(
                Pair("Todos", ""),
                Pair("En curso", "ongoing"),
                Pair("Finalizado", "completed"),
                Pair("Hiatus", "hiatus"),
                Pair("Cancelado", "cancelled"),
            ),
        )

    private class SortFilter :
        SelectFilter(
            "Ordenar por",
            arrayOf(
                Pair("Más recientes", "recientes"),
                Pair("Más vistos", "vistas"),
                Pair("Mejor puntuados", "rating"),
                Pair("Alfabético (A-Z)", "alfabetico"),
            ),
        )

    private open class SelectFilter(name: String, private val options: Array<Pair<String, String>>) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val selected: String
            get() = options[state].second
    }
}
