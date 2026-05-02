package eu.kanade.tachiyomi.extension.es.tmohentai

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class TMOHentai : ParsedHttpSource() {

    override val name = "TMOHentai"

    override val baseUrl = "https://tmohentai.app"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimitHost(baseUrl.toHttpUrl(), 1, 2)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/biblioteca?order_item=likes_count&order_dir=desc&title=&page=$page", headers)

    override fun popularMangaSelector() = "div.element-thumbnail"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        title = element.select("div.content-title a").text()
        thumbnail_url = element.select("img.content-thumbnail-cover").attr("abs:src")
        setUrlWithoutDomain(element.select("div.content-title a").attr("href"))
    }

    override fun popularMangaNextPageSelector() = "ul.pagination li.active + li a"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/biblioteca?order_item=creation&order_dir=desc&title=&page=$page", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.select("h3.truncate, h1").text()
        thumbnail_url = document.select("img.content-thumbnail-cover").attr("abs:src")
        description = document.select("h5:contains(Sinopsis) + p").text()

        val type = document.select("li.heading:has(label:contains(Type)) + li span.label").text()
        val tags = document.select("ul#md-tags-list span.label").map { it.text() }
        genre = (listOf(type) + tags).filter { it.isNotBlank() }.joinToString(", ")

        val authorAndArtist = document.select("li.heading:has(label:contains(Uploaded By)) + li span.label").text()
            .replace("TMOHentai", "", ignoreCase = true).trim()
        author = authorAndArtist
        artist = authorAndArtist
        status = SManga.UNKNOWN
    }

    override fun chapterListSelector() = "div.panel-heading a.btn-primary:has(i.fa-play)"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        name = "Leer obra completa"
        setUrlWithoutDomain(element.attr("href"))
    }

    override fun pageListParse(document: Document): List<Page> = document.select("div.reader-img-wrap img").mapIndexed { i, img ->
        val url = img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
        Page(i, "", url)
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/biblioteca".toHttpUrl().newBuilder()

        url.addQueryParameter("title", query)
        url.addQueryParameter("page", page.toString())

        var contentValue = ""

        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
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

        url.addQueryParameter("content", contentValue)

        return GET(url.build(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl/$PREFIX_CONTENTS/$id", headers)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = if (query.startsWith(PREFIX_ID_SEARCH)) {
        val realQuery = query.removePrefix(PREFIX_ID_SEARCH)

        client.newCall(searchMangaByIdRequest(realQuery))
            .asObservableSuccess()
            .map { response ->
                val details = mangaDetailsParse(response)
                details.url = "/$PREFIX_CONTENTS/$realQuery"
                MangasPage(listOf(details), false)
            }
    } else {
        client.newCall(searchMangaRequest(page, query, filters))
            .asObservableSuccess()
            .map { response ->
                searchMangaParse(response)
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
                Pair("Futanari", "futa"),
                Pair("Solo Femenino", "sole_female"),
                Pair("Solo Masculino", "sole_male"),
            ),
        )

    override fun getFilterList() = FilterList(
        Types(),
        Filter.Separator(),
        ContentFilter(),
        Filter.Separator(),
        SortBy(),
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
        Genre("Impregnation", "3"),
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
        const val PREFIX_CONTENTS = "contents"
        const val PREFIX_ID_SEARCH = "id:"

        private val SORTABLES = listOf(
            Pair("Más populares", "likes_count"),
            Pair("Mejor valorados", "score"),
            Pair("Alfabético", "alphabetically"),
            Pair("Más recientes", "creation"),
            Pair("Fecha estreno", "release_date"),
        )
    }
}
