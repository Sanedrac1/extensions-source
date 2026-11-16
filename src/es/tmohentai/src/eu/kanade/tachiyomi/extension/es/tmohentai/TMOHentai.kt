package eu.kanade.tachiyomi.extension.es.tmohentai

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class TMOHentai : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2)

    // ============================== Popular ==============================
    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaList(client.get("$baseUrl/biblioteca?order_item=likes_count&order_dir=desc&title=&page=$page", Fetch.DOCUMENT))

    // ============================== Latest ==============================
    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaList(client.get("$baseUrl/biblioteca?order_item=creation&order_dir=desc&title=&page=$page", Fetch.DOCUMENT))

    private fun parseMangaList(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("a.manga-card").map { mangaFromElement(it) }
        val hasNextPage = document.selectFirst("ul.pagination li.active + li a") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.select("h3.manga-card__title").text()
        thumbnail_url = element.select("img.manga-card__cover").attr("abs:src")
        setUrlWithoutDomain(element.attr("abs:href"))
    }

    // ============================== Deeplink ==============================
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val host = url.host.removePrefix("www.")
        val sourceHost = baseUrl.toHttpUrl().host.removePrefix("www.")
        val firstSegment = url.pathSegments.firstOrNull()

        return when {
            host == sourceHost && firstSegment == "library" -> {
                val document = client.get(url, Fetch.DOCUMENT).asJsoup()
                parseMangaDetails(document).apply {
                    setUrlWithoutDomain(url.toString())
                }
            }
            (host == sourceHost || host == "tmohentai.com") && firstSegment == "contents" -> {
                val id = url.pathSegments.getOrNull(1) ?: return null
                val mangaPath = "/$PREFIX_CONTENTS/$id/a"
                val document = client.get(baseUrl + mangaPath, Fetch.DOCUMENT).asJsoup()
                parseMangaDetails(document).apply {
                    this.url = mangaPath
                }
            }
            else -> null
        }
    }

    // ============================== Details & Chapters ==============================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        val details = if (fetchDetails) parseMangaDetails(document) else manga
        val chapterList = if (fetchChapters) parseChapterList(document) else chapters
        return SMangaUpdate(details, chapterList)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
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

    private fun parseChapterList(document: Document): List<SChapter> = document.select("a.md-preview-read-btn").map { element ->
        SChapter.create().apply {
            name = "Leer obra completa"
            setUrlWithoutDomain(element.attr("abs:href").substringBefore('#'))
        }
    }

    // ============================== Pages ==============================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
        return document.select("div.reader-img-wrap img").mapIndexed { i, img ->
            val url = img.attr("abs:data-src")
                .ifEmpty { img.attr("abs:data-original") }
                .ifEmpty { img.attr("abs:src") }
            Page(i, imageUrl = url)
        }
    }

    // ============================== Search ==============================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.startsWith(PREFIX_ID_SEARCH)) {
            val realQuery = query.removePrefix(PREFIX_ID_SEARCH)
            val mangaPath = "/$PREFIX_CONTENTS/$realQuery/a"
            val document = client.get(baseUrl + mangaPath, Fetch.DOCUMENT).asJsoup()
            val details = parseMangaDetails(document).apply {
                url = mangaPath
            }
            return MangasPage(listOf(details), false)
        }

        if (query.startsWith(PREFIX_GROUP_SEARCH)) {
            val realQuery = query.removePrefix(PREFIX_GROUP_SEARCH).trim()
            return parseMangaList(client.get("$baseUrl/groups/$realQuery/a?page=$page", Fetch.DOCUMENT))
        }

        val url = "$baseUrl/biblioteca".toHttpUrl().newBuilder()

        var groupVal = ""
        var contentValue = ""

        (if (filters.isEmpty()) getFilterList(null) else filters).forEach { filter ->
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
            return parseMangaList(client.get("$baseUrl/groups/$groupVal/a?page=$page", Fetch.DOCUMENT))
        }

        url.addQueryParameter("title", query)
        url.addQueryParameter("page", page.toString())
        url.addQueryParameter("content", contentValue)

        return parseMangaList(client.get(url.build(), Fetch.DOCUMENT))
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

    override fun getFilterList(data: JsonElement?) = FilterList(
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
