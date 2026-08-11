package eu.kanade.tachiyomi.extension.es.tmohentai

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
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
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import rx.Observable

@Source
open class TMOHentai : HttpSource() {

    override val name = "TMOHentai"

    override val baseUrl = "https://tmohentai.app"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ============================== Popular ==============================
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/biblioteca?order_item=likes_count&order_dir=desc&title=&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("a.manga-card").map { popularMangaFromElement(it) }
        val hasNextPage = document.selectFirst("ul.pagination li.active + li a") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.select("h3.manga-card__title").text()
        thumbnail_url = element.select("img.manga-card__cover").attr("abs:src")
        setUrlWithoutDomain(element.attr("href"))
    }

    // ============================== Latest ==============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/biblioteca?order_item=creation&order_dir=desc&title=&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================== Details ==============================
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.select("h1#md-title").text()
            thumbnail_url = document.select("img#md-cover").attr("abs:src")
            description = document.select("div.md-info-row--synopsis div.md-info-row__value").text()

            val type = document.select("span.md-badge--type").text()
            val tags = document.select("ul#md-tags-list a span, ul#md-tags-list span.label").map { it.text() }
            genre = (listOf(type) + tags).filter { it.isNotBlank() }.joinToString(", ")

            val authorName = document.select("span.md-badge--author").text().trim()
            val artistName = document.select("a.md-badge--uploader").text()
                .replace("TMOHentai", "", ignoreCase = true).trim()
            author = authorName.ifEmpty { artistName }
            artist = artistName.ifEmpty { authorName }
            status = SManga.UNKNOWN
        }
    }

    // ============================== Chapters ==============================
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("a.md-preview-read-btn").map { element ->
            SChapter.create().apply {
                name = "Leer obra completa"
                setUrlWithoutDomain(element.attr("href"))
            }
        }
    }

    // ============================== Pages ==============================
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("div.reader-img-wrap img").mapIndexed { i, img ->
            val url = img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
            Page(i, "", url)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Search ==============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/biblioteca".toHttpUrl().newBuilder()

        var groupVal = ""
        var contentValue = ""

        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is GroupFilter -> {
                    groupVal = filter.state.trim()
                }

                is Types -> {
                    if (filter.toUriPart() != "all") {
                        url.addQueryParameter("type", filter.toUriPart())
                    }
                }

                is ContentFilter -> {
                    contentValue = filter.toUriPart()
                }

                is GenreList -> {
                    filter.state
                        .filter { genre -> genre.state }
                        .forEach { genre -> url.addQueryParameter("tags[]", genre.id) }
                }

                is SortBy -> {
                    if (filter.state != null) {
                        url.addQueryParameter("order_item", SORTABLES[filter.state!!.index].second)
                        url.addQueryParameter(
                            "order_dir",
                            if (filter.state!!.ascending) {
                                "asc"
                            } else {
                                "desc"
                            },
                        )
                    }
                }

                else -> {}
            }
        }

        if (groupVal.isNotEmpty()) {
            return GET("$baseUrl/groups/$groupVal/a?page=$page", headers)
        }

        url.addQueryParameter("title", query)
        url.addQueryParameter("page", page.toString())
        url.addQueryParameter("content", contentValue)

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl/$PREFIX_CONTENTS/$id/a", headers)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = when {
        query.startsWith(PREFIX_ID_SEARCH) -> {
            val realQuery = query.removePrefix(PREFIX_ID_SEARCH)
            client.newCall(searchMangaByIdRequest(realQuery))
                .asObservableSuccess()
                .map { response ->
                    val details = mangaDetailsParse(response)
                    details.url = "/$PREFIX_CONTENTS/$realQuery/a"
                    MangasPage(listOf(details), false)
                }
        }
        query.startsWith(PREFIX_GROUP_SEARCH) -> {
            val realQuery = query.removePrefix(PREFIX_GROUP_SEARCH).trim()
            client.newCall(GET("$baseUrl/groups/$realQuery/a?page=$page", headers))
                .asObservableSuccess()
                .map { response ->
                    searchMangaParse(response)
                }
        }
        else -> {
            client.newCall(searchMangaRequest(page, query, filters))
                .asObservableSuccess()
                .map { response ->
                    searchMangaParse(response)
                }
        }
    }

    private class Genre(name: String, val id: String) : Filter.CheckBox(name)

    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Tags", genres)

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class Types :
        UriPartFilter(
            "Filtrar por tipo",
            arrayOf(
                Pair("Ver todos", "all"),
                Pair("Hentai", "hentai"),
                Pair("Light Hentai", "light-hentai"),
                Pair("Doujinshi", "doujinshi"),
                Pair("One-shot", "one-shot"),
                Pair("Other", "otro"),
            ),
        )

    private class ContentFilter :
        UriPartFilter(
            "Filtrar por contenido",
            arrayOf(
                Pair("Todos", ""),
                Pair("Yaoi", "yaoi"),
                Pair("Yuri", "yuri"),
                Pair("Futanari", "futanari"),
                Pair("Solo Femenino", "sole-female"),
                Pair("Solo Masculino", "sole-male"),
                Pair("Vanilla", "vanilla"),
                Pair("NTR / Netorare", "ntr"),
                Pair("Sin censura (Uncensored)", "uncensored"),
            ),
        )

    private class GroupFilter : Filter.Text("ID de Grupo (ej. 43)")

    override fun getFilterList() = FilterList(
        Types(),
        Filter.Separator(),
        ContentFilter(),
        Filter.Separator(),
        SortBy(),
        Filter.Separator(),
        GroupFilter(),
        Filter.Separator(),
        GenreList(getGenreList()),
    )

    class SortBy :
        Filter.Sort(
            "Ordenar por",
            SORTABLES.map { it.first }.toTypedArray(),
            Selection(0, false),
        )

    private fun getGenreList() = listOf(
        Genre("Ahegao", "7"),
        Genre("Anal", "16"),
        Genre("Bbw", "23"),
        Genre("Bestiality", "51"),
        Genre("Big Ass", "27"),
        Genre("Big Boobs", "28"),
        Genre("Bisexual", "72"),
        Genre("Blowjob", "8"),
        Genre("Bondage", "60"),
        Genre("Bukkake", "52"),
        Genre("Cheating", "9"),
        Genre("Colour", "31"),
        Genre("Comedy", "71"),
        Genre("Creampie", "38"),
        Genre("Dark Skin", "29"),
        Genre("Deepthroat", "42"),
        Genre("Domination", "30"),
        Genre("Double Penetration", "49"),
        Genre("Exhibitionism", "34"),
        Genre("Fantasy", "50"),
        Genre("Femdom", "59"),
        Genre("Fetish", "61"),
        Genre("Ffm Threesome", "46"),
        Genre("Filming", "65"),
        Genre("Forced", "39"),
        Genre("Furry", "32"),
        Genre("Futanari", "44"),
        Genre("Group", "26"),
        Genre("Gyaru", "45"),
        Genre("Harem", "13"),
        Genre("Humiliation", "36"),
        Genre("Incest", "12"),
        Genre("Kissing", "43"),
        Genre("Loli", "57"),
        Genre("Mature", "10"),
        Genre("Milf", "2"),
        Genre("Mmf Threesome", "47"),
        Genre("Monsters", "35"),
        Genre("Mother", "54"),
        Genre("Netorare", "4"),
        Genre("Netorase", "73"),
        Genre("Nympho", "40"),
        Genre("Orgy", "48"),
        Genre("Oyakodon", "53"),
        Genre("Pregnant", "33"),
        Genre("Rape", "21"),
        Genre("Romance", "64"),
        Genre("Shota", "37"),
        Genre("Small Boobs", "67"),
        Genre("Sole Female", "25"),
        Genre("Sole Male", "24"),
        Genre("Student", "58"),
        Genre("Tall Girl", "56"),
        Genre("Tomboy", "55"),
        Genre("Toys", "41"),
        Genre("Tsundere", "74"),
        Genre("Uncensored", "63"),
        Genre("Virgin", "69"),
        Genre("Yaoi", "18"),
        Genre("Yuri", "17"),
    )

    companion object {
        const val PREFIX_CONTENTS = "library/manga"
        const val PREFIX_ID_SEARCH = "id:"
        const val PREFIX_GROUP_SEARCH = "group:"

        private val SORTABLES = listOf(
            Pair("Más populares", "likes_count"),
            Pair("Mejor valorados", "score"),
            Pair("Alfabético", "alphabetically"),
            Pair("Más recientes", "creation"),
            Pair("Fecha estreno", "release_date"),
        )
    }
}
