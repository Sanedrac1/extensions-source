package eu.kanade.tachiyomi.extension.es.lectorhentai

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Source
abstract class LectorHentai : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2)

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(OrderFilter(1)))

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(OrderFilter(0)))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val urlBuilder = "$baseUrl/tipo/all".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("s", query.trim())
        }

        filters.forEach { filter ->
            when (filter) {
                is OrderFilter -> {
                    urlBuilder.addQueryParameter("order", filter.toUriPart())
                }
                is LanguageFilter -> {
                    val lang = filter.toUriPart()
                    if (lang != "all") {
                        urlBuilder.addQueryParameter("lenguaje", lang)
                    }
                }
                is GenreListFilter -> {
                    filter.state.filter { it.state }.forEach { genre ->
                        urlBuilder.addQueryParameter("genre[]", genre.value)
                    }
                }
                else -> {}
            }
        }

        val document = client.get(urlBuilder.build()).asJsoup()
        val mangas = document.select("div.listupd div.bsx").mapNotNull { element ->
            val link = element.selectFirst("a[href]") ?: return@mapNotNull null
            val rawHref = link.attr("href")
            if (!rawHref.contains("/manga/")) return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(rawHref)
                title = element.selectFirst("div.bigor div.tt")?.text()?.trim()
                    ?: link.attr("title").trim()
                thumbnail_url = element.selectFirst("img.ts-post-image")?.let { img ->
                    val src = img.attr("src").ifEmpty { img.attr("data-src") }
                    if (src.startsWith("//")) "https:$src" else img.absUrl("src")
                }
            }
        }

        val hasNextPage = document.selectFirst("div.hpage a.r, div.pagination a.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        OrderFilter(),
        LanguageFilter(),
        Filter.Separator(),
        GenreListFilter(genresList),
    )

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val mangaPath = when {
            url.encodedPath.startsWith("/read/") -> url.encodedPath.replaceFirst("/read/", "/manga/")
            url.encodedPath.startsWith("/manga/") -> url.encodedPath
            else -> return null
        }
        return SManga.create().apply {
            this.url = mangaPath
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val details = if (fetchDetails) {
            SManga.create().apply {
                title = document.selectFirst("div.infox div.wd-full:contains(Título) span")?.text()?.trim()
                    ?: document.selectFirst("h1.entry-title")?.text()
                        ?.removeSuffix(" en Español | Leer Online Gratis")
                        ?.trim()
                    ?: manga.title

                author = document.select("div.infox div.wd-full:contains(Artista) span.mgen a")
                    .joinToString { it.text().trim() }
                    .ifEmpty { null }
                artist = author

                genre = document.select(
                    "div.infox div.wd-full:contains(Generos) span.mgen a, " +
                        "div.infox div.wd-full:contains(Tags) span.mgen a",
                )
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString()
                    .ifEmpty { null }

                description = document.selectFirst("div.infox div.entry-content, div.infox div.desc")?.text()?.trim()
                    ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

                thumbnail_url = document.selectFirst("div.thumbook div.thumb img")?.let { img ->
                    val src = img.attr("src").ifEmpty { img.attr("data-src") }
                    if (src.startsWith("//")) "https:$src" else img.absUrl("src")
                }

                status = SManga.COMPLETED
            }
        } else {
            manga
        }

        val chapterList = if (fetchChapters) {
            val readUrl = document.selectFirst("a.leer, div.eplister a[href*=/read/]")?.attr("href")
                ?: manga.url.replaceFirst("/manga/", "/read/")

            val normalizedUrl = if (readUrl.startsWith("http")) {
                readUrl.substringAfter(baseUrl)
            } else {
                readUrl
            }

            listOf(
                SChapter.create().apply {
                    url = normalizedUrl
                    name = "Capítulo Completo"
                    chapter_number = 1f
                },
            )
        } else {
            chapters
        }

        return SMangaUpdate(details, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val script = document.select("script:containsData(ts_reader.run)").firstOrNull()?.data()
            ?: throw Exception("No se encontró el script ts_reader")

        val match = JSON_IMAGE_LIST_REGEX.find(script)
            ?: throw Exception("No se pudo extraer la lista de imágenes")

        val urls = IMAGE_URL_REGEX.findAll(match.groupValues[1])
            .map { it.groupValues[1] }
            .toList()

        if (urls.isEmpty()) {
            throw Exception("No se encontraron páginas en el capítulo")
        }

        return urls.mapIndexed { index, rawUrl ->
            val imageUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
            Page(index, imageUrl = imageUrl)
        }
    }

    companion object {
        private val JSON_IMAGE_LIST_REGEX = """"images"\s*:\s*\[([^\]]+)\]""".toRegex()
        private val IMAGE_URL_REGEX = """"([^"]+)"""".toRegex()
    }
}
