package eu.kanade.tachiyomi.extension.es.aedex

import android.util.LruCache
import eu.kanade.tachiyomi.multisrc.catharsis.Catharsis
import eu.kanade.tachiyomi.multisrc.catharsis.ChapterDto
import eu.kanade.tachiyomi.multisrc.catharsis.MangaDto
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.fetch
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.Instant

@Source
abstract class Aedex : Catharsis() {

    private val lectorSessions = LruCache<String, LectorSession>(16)

    override val apiUrl get() = "$baseUrl/api/backend"

    override val apiHeaders get() = headersBuilder()
        .set("System", "aedex")
        .set("X-FK-Sistema", "1")
        .build()

    override val mangaPath = "comic"

    override fun mangaKey(manga: MangaDto) = manga.slug

    override fun OkHttpClient.Builder.addReaderInterceptors(): OkHttpClient.Builder = addInterceptor(ImageInterceptor(lectorSessions))

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/$mangaPath/${chapter.url}"

    // fecha_publicacion has no offset; the site shows date_created
    override fun chapterDate(chapter: ChapterDto) = Instant.tryParse(chapter.dateCreated)

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val fetch = Fetch.XHR.from(getChapterUrl(chapter))
        val freeReader = FreeReader()
        val readerHeaders = apiHeaders.newBuilder()
            .set("X-Catharsis-Reader-Pass", readerPass(fetch).pass)
            .set("X-Catharsis-Ws-Pub", freeReader.publicKey)
            .build()
        val chapterUrl = "$apiUrl/mangas/${chapter.url}".toHttpUrl().newBuilder()
            .addQueryParameter("modo", "gratis")
            .build()
        val free = client.get(chapterUrl, readerHeaders, fetch, ensureSuccess = false).ensureSiteSuccess().parseAs<FreeReaderDto>()
        // WebSockets stay on OkHttp: Cronet can't upgrade a connection
        val ready = freeReader.countdown(network.client, headersBuilder().set("Origin", baseUrl).build(), free.wsUrl, free.wsTicket)
        val reader = client.post(
            ready.redirectUrl!!,
            Fetch.DOCUMENT.from(getChapterUrl(chapter)),
            FormBody.Builder().add("data", ready.redirectData!!).build(),
        ).asJsoup()
        val lectorSession = LectorSession.fromDocument(reader)
        if (lectorSession != null) {
            lectorSessions.put(reader.location(), lectorSession)
            val handles = reader.select("canvas.manga-page-canvas[data-h]")
            if (handles.isNotEmpty()) {
                return handles.mapIndexed { index, canvas ->
                    val handle = canvas.attr("data-h")
                    val imageUrl = "${lectorSession.origin}/lector/p/$handle/".toHttpUrl().newBuilder()
                        .addQueryParameter("q", "hq")
                        .fragment(lectorSession.xorByte.toString())
                        .build()
                        .toString()
                    Page(index, url = reader.location(), imageUrl = imageUrl)
                }
            }
        }
        val xorKey = reader.select("script:not([src])").firstNotNullOfOrNull { XOR_KEY_REGEX.find(it.data()) }?.groupValues?.get(1).orEmpty()
        return reader.select("canvas.manga-page-canvas").mapIndexed { index, canvas ->
            // XOR key and scramble key for ImageInterceptor; pages without data-k aren't shuffled
            val imageUrl = canvas.absUrl("data-src").toHttpUrl().newBuilder()
                .fragment("$xorKey:${canvas.attr("data-k")}")
                .build()
                .toString()
            Page(index, url = reader.location(), imageUrl = imageUrl)
        }
    }

    override fun getImageRequest(page: Page): Request = super.getImageRequest(page).newBuilder()
        .tag(LectorPageRef::class.java, LectorPageRef(page.url))
        .fetch(Fetch.XHR.from(page.url))
        .build()

    companion object {
        private val XOR_KEY_REGEX = Regex("""\bvar XOR_KEY\s*=\s*(\d+)""")
    }
}

class LectorPageRef(val readerUrl: String)
