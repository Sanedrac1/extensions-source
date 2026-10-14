package eu.kanade.tachiyomi.extension.es.lectorhentai

import android.util.Base64
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
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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
        val html = document.html()

        // 1. Try Dean Edwards unpack on any packed script or full HTML
        val unpacked = unpackDeanEdwards(html)

        // 2. Try ReaderBlob AES-GCM decryption
        val k = BLOB_K_REGEX.find(unpacked)?.groupValues?.get(1)
        val iv = BLOB_IV_REGEX.find(unpacked)?.groupValues?.get(1)
        val c = BLOB_C_REGEX.find(unpacked)?.groupValues?.get(1)

        if (k != null && iv != null && c != null) {
            val decrypted = runCatching { decryptBlob(k, iv, c) }.getOrNull()
            if (decrypted != null) {
                val urls = IMAGE_URL_REGEX.findAll(decrypted)
                    .map { it.groupValues[1] }
                    .filter { it.startsWith("http") && !it.endsWith(".svg") }
                    .toList()
                if (urls.isNotEmpty()) {
                    return urls.mapIndexed { index, rawUrl ->
                        val imageUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
                        Page(index, imageUrl = imageUrl)
                    }
                }
            }
        }

        // 3. Fallback to ts_reader.run
        val tsMatch = JSON_IMAGE_LIST_REGEX.find(unpacked) ?: JSON_IMAGE_LIST_REGEX.find(html)
        if (tsMatch != null) {
            val urls = IMAGE_URL_REGEX.findAll(tsMatch.groupValues[1])
                .map { it.groupValues[1] }
                .filter { it.startsWith("http") && !it.endsWith(".svg") }
                .toList()
            if (urls.isNotEmpty()) {
                return urls.mapIndexed { index, rawUrl ->
                    val imageUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
                    Page(index, imageUrl = imageUrl)
                }
            }
        }

        // 4. Fallback to div#readerarea img
        val imgElements = document.select("div#readerarea img, div.entry-content img")
        val imgUrls = imgElements.mapNotNull { img ->
            val src = img.attr("src").ifEmpty { img.attr("data-src") }
            src.takeIf { it.isNotBlank() && !it.endsWith(".svg") }
        }
        if (imgUrls.isNotEmpty()) {
            return imgUrls.mapIndexed { index, rawUrl ->
                val imageUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
                Page(index, imageUrl = imageUrl)
            }
        }

        throw Exception("No se encontraron páginas en el capítulo")
    }

    private fun unpackDeanEdwards(script: String): String {
        val match = PACKED_REGEX.find(script) ?: return script
        val p = match.groupValues[1]
        val a = match.groupValues[2].toIntOrNull() ?: return script
        val c = match.groupValues[3].toIntOrNull() ?: return script
        val k = match.groupValues[4].split("|")

        val kMap = HashMap<String, String>(c)
        for (i in 0 until c) {
            val key = getBaseStr(i, a)
            val value = k.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: key
            kMap[key] = value
        }

        return WORD_REGEX.replace(p) { m ->
            kMap[m.value] ?: m.value
        }
    }

    private fun getBaseStr(c: Int, a: Int): String {
        val prefix = if (c < a) "" else getBaseStr(c / a, a)
        val rem = c % a
        val char = if (rem > 35) (rem + 29).toChar() else rem.toString(36)[0]
        return prefix + char
    }

    private fun decryptBlob(keyBase64: String, ivBase64: String, cipherBase64: String): String {
        val keyBytes = Base64.decode(keyBase64, Base64.DEFAULT)
        val ivBytes = Base64.decode(ivBase64, Base64.DEFAULT)
        val cipherBytes = Base64.decode(cipherBase64, Base64.DEFAULT)

        val keySpec = SecretKeySpec(keyBytes, "AES")
        val gcmSpec = GCMParameterSpec(128, ivBytes)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        }

        val decryptedBytes = cipher.doFinal(cipherBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    companion object {
        private val PACKED_REGEX = """eval\(function\(p,a,c,k,e,d\).*?return p\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val WORD_REGEX = """\b\w+\b""".toRegex()
        private val BLOB_K_REGEX = """"k"\s*:\s*"([^"]+)"""".toRegex()
        private val BLOB_IV_REGEX = """"iv"\s*:\s*"([^"]+)"""".toRegex()
        private val BLOB_C_REGEX = """"c"\s*:\s*"([^"]+)"""".toRegex()
        private val JSON_IMAGE_LIST_REGEX = """"images"\s*:\s*\[([^\]]+)\]""".toRegex()
        private val IMAGE_URL_REGEX = """"([^"]+)"""".toRegex()
    }
}
