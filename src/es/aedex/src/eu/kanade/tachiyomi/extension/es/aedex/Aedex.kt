package eu.kanade.tachiyomi.extension.es.aedex

import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.catharsis.Catharsis
import eu.kanade.tachiyomi.multisrc.catharsis.ChapterDto
import eu.kanade.tachiyomi.multisrc.catharsis.MangaDto
import eu.kanade.tachiyomi.multisrc.catharsis.ReaderPassDto
import eu.kanade.tachiyomi.multisrc.catharsis.ReaderPassRequest
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.fetch
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.tryParse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Instant

@Source
abstract class Aedex :
    Catharsis(),
    ConfigurableSource {

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val apiUrl get() = "$baseUrl/api/backend"

    override val apiHeaders get() = headersBuilder()
        .set("System", "aedex")
        .set("X-FK-Sistema", "1")
        .build()

    override val mangaPath = "comic"

    override fun mangaKey(manga: MangaDto) = manga.slug

    override fun OkHttpClient.Builder.addReaderInterceptors(): OkHttpClient.Builder = addInterceptor(ImageInterceptor(::renewSession))

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/$mangaPath/${chapter.url}"

    // fecha_publicacion has no offset; the site shows date_created
    override fun chapterDate(chapter: ChapterDto) = Instant.tryParse(chapter.dateCreated)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_EMAIL
            title = "Correo electrónico"
            summary = "Correo de tu cuenta de Aedex (requerido después del capítulo 3)"
            dialogTitle = "Correo electrónico"
            setDefaultValue("")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD
            title = "Contraseña"
            summary = "Contraseña de tu cuenta de Aedex"
            dialogTitle = "Contraseña"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    private val loginLock = Mutex()

    @Volatile
    private var lastLoggedInEmail: String? = null

    private fun credentials(): Pair<String, String>? {
        val email = preferences.getString(PREF_EMAIL, "").orEmpty().trim().lowercase()
        val password = preferences.getString(PREF_PASSWORD, "").orEmpty()
        return if (email.isNotEmpty() && password.isNotEmpty()) email to password else null
    }

    private fun hasSessionCookie(): Boolean = client.cookieJar.loadForRequest("$baseUrl/".toHttpUrl())
        .any { it.name.contains(SESSION_COOKIE_MARKER) }

    private suspend fun ensureLoggedIn(force: Boolean = false) {
        val (email, password) = credentials() ?: return
        loginLock.withLock {
            if (!force && hasSessionCookie() && lastLoggedInEmail == email) return@withLock
            val authFetch = Fetch.XHR.from("$baseUrl/auth/login")
            val csrf = client.get("$baseUrl/api/auth/csrf", authFetch)
                .parseAs<CsrfTokenDto>()
                .csrfToken
            val form = FormBody.Builder()
                .add("csrfToken", csrf)
                .add("username", email)
                .add("password", password)
                .add("json", "true")
                .add("redirect", "false")
                .add("callbackUrl", "$baseUrl/")
                .build()
            client.post("$baseUrl/api/auth/callback/credentials", authFetch, form, ensureSuccess = false).use { response ->
                val bodyText = response.body.string()
                when {
                    bodyText.contains("EmailNotVerified") -> throw IOException("Verifica tu correo en aedexnox.com antes de iniciar sesión")
                    response.code == 401 || bodyText.contains("CredentialsSignin") -> throw IOException("Correo o contraseña incorrectos en Aedex")
                    !response.isSuccessful -> throw IOException("Error al iniciar sesión en Aedex (HTTP ${response.code})")
                }
            }
            if (!hasSessionCookie()) {
                throw IOException("No se recibió la cookie de sesión al iniciar sesión en Aedex")
            }
            lastLoggedInEmail = email
        }
    }

    // Signs the site's reader calls; its keys come with the chapter page
    @Volatile
    private var signer: ReaderSigner? = null

    // The reader pass is the site's `ax_rp` cookie, bound to the connection's IP
    override fun storedReaderPass(): ReaderPassDto? = client.cookieJar.loadForRequest("$baseUrl/lector/".toHttpUrl())
        .firstOrNull { it.name == PASS_COOKIE }
        ?.let { ReaderPassDto(it.value, it.expiresAt) }

    override suspend fun redeemReaderPass(capToken: String, sitekey: String, fetch: Fetch): ReaderPassDto {
        val signer = signer ?: throw IOException("Reader keys not found")
        val headers = headersBuilder().set(signer.buildName, signer.buildId).build()
        client.post(
            "$baseUrl/lector/pase",
            headers,
            fetch,
            ReaderPassRequest(capToken, sitekey).toJsonRequestBody(),
            ensureSuccess = false,
        ).use { response ->
            if (response.code == 409) throw OutdatedReaderException()
            if (!response.isSuccessful) throw response.siteError(response.apiError()?.code)
        }
        return storedReaderPass() ?: throw IOException("The site didn't hand out a reader pass")
    }

    // 409: the site rotated its reader keys, the chapter page has the new ones
    private class OutdatedReaderException : IOException("The site's reader changed, try again")

    private suspend fun requestFreeReader(chapter: SChapter, fetch: Fetch, freeReader: FreeReader): FreeReaderDto {
        ensureLoggedIn()
        val slug = chapter.url.substringBeforeLast('/')
        // The site sends the number as parseFloat does
        val number = chapter.url.substringAfterLast('/').let { it.toDoubleOrNull()?.toString()?.removeSuffix(".0") ?: it }
        val body = FreeReaderRequest(slug, number, "gratis").toJsonRequestBody()
        var renewPass = false
        for (attempt in 1..MAX_ATTEMPTS) {
            val retry = attempt < MAX_ATTEMPTS
            try {
                val readerSigner = signer ?: loadSigner(chapter).also { signer = it }
                readerPass(fetch, renewPass)
                val time = Clock.System.now().toEpochMilliseconds()
                val (proofName, proof) = readerSigner.proof("c", "$slug/$number", time)
                val headers = headersBuilder()
                    .set("X-Catharsis-Ws-Pub", freeReader.publicKey)
                    .set(readerSigner.buildName, readerSigner.buildId)
                    .set(proofName, proof)
                    .build()
                client.post("$baseUrl/lector/capitulo", headers, fetch, body, ensureSuccess = false).use { response ->
                    when {
                        response.isSuccessful -> return response.parseAs<FreeReaderDto>()
                        response.code == 409 && retry -> signer = null
                        else -> {
                            val code = response.apiError()?.code
                            when {
                                // The pass is gone, or was given to another IP: solve a new one
                                response.code == 403 && code == "READER_PASS_REQUIRED" && retry -> renewPass = true
                                code == "LOGIN_REQUIRED" && retry && credentials() != null -> ensureLoggedIn(force = true)
                                code == "LOGIN_REQUIRED" -> throw LoginRequiredException()
                                else -> throw response.siteError(code)
                            }
                        }
                    }
                }
            } catch (_: OutdatedReaderException) {
                if (!retry) throw OutdatedReaderException()
                signer = null
            }
        }
        throw IOException("Couldn't open the reader")
    }

    private suspend fun loadSigner(chapter: SChapter): ReaderSigner {
        val doc = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
        doc.selectFirst("[data-t][data-s]")?.let { vault ->
            return ReaderSigner("ax", vault.attr("data-t"), vault.attr("data-s"))
        }
        val hexAttrs = doc.select("[hidden]").firstNotNullOfOrNull { el ->
            el.attributes()
                .filter { it.key.startsWith("data-") && HEX_32_REGEX.matches(it.value) }
                .takeIf { it.size == 2 }
        } ?: throw IOException("Reader keys not found")
        return ReaderSigner("ax", hexAttrs[0].value, hexAttrs[1].value)
    }

    private suspend fun openReaderOnce(chapter: SChapter): Document {
        val fetch = Fetch.XHR.from("${getChapterUrl(chapter)}?modo=gratis")
        val freeReader = FreeReader()
        val free = requestFreeReader(chapter, fetch, freeReader)
        // WebSockets stay on OkHttp: Cronet can't upgrade a connection
        val wsHeaders = headersBuilder().set("Origin", baseUrl).build()
        val ready = freeReader.countdown(network.client, wsHeaders, free.wsUrl, free.wsTicket)
        return client.post(
            ready.redirectUrl!!,
            Fetch.DOCUMENT.from(getChapterUrl(chapter)),
            FormBody.Builder().add("data", ready.redirectData!!).build(),
        ).asJsoup()
    }

    private suspend fun openReader(chapter: SChapter): Document = try {
        openReaderOnce(chapter)
    } catch (e: LoginRequiredException) {
        if (credentials() == null) throw e
        ensureLoggedIn(force = true)
        openReaderOnce(chapter)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val session = readerSession(openReader(chapter))
        sessionLock.withLock {
            sessions[chapter.url] = session
        }
        return List(session.pageCount) { index ->
            Page(index, url = session.readerUrl, imageUrl = session.imageUrl(index, chapter.url))
        }
    }

    private fun readerSession(reader: Document): ReaderSession {
        val lector = reader.select("script").firstNotNullOfOrNull { script ->
            LECTOR_REGEX.findAll(script.data()).firstNotNullOfOrNull { match ->
                val raw = match.groupValues[1].takeIf { it.isNotEmpty() }
                    ?.let { Base64.decode(it, Base64.DEFAULT).decodeToString() }
                    ?: match.groupValues[2]
                runCatching { raw.parseAs<LectorDto>() }.getOrNull()
            }
        } ?: throw IOException("Reader keys not found")
        val seed = reader.select("meta[content]").firstNotNullOfOrNull { el ->
            el.attr("content").takeIf { HEX_32_REGEX.matches(it) }
        } ?: reader.select("[hidden]").firstNotNullOfOrNull { el ->
            el.attributes().firstOrNull { it.key.startsWith("data-") && HEX_32_REGEX.matches(it.value) }?.value
        } ?: reader.allElements.firstNotNullOfOrNull { el ->
            el.attributes().firstOrNull { it.key.startsWith("data-") && it.key != "data-page" && HEX_32_REGEX.matches(it.value) }?.value
        } ?: lector.seed?.takeIf { HEX_32_REGEX.matches(it) }
            ?: throw IOException("Reader seed not found")
        val handles = lector.pages?.takeIf { it.isNotEmpty() }
            ?: reader.select("canvas.manga-page-canvas, canvas[data-page]").mapNotNull { canvas ->
                canvas.attributes().firstOrNull { attr ->
                    attr.key.startsWith("data-") && attr.key != "data-page" && attr.value.length > 16
                }?.value
            }.ifEmpty { throw IOException("Reader pages not found") }
        return ReaderSession(
            readerUrl = reader.location(),
            keys = VisitKeys(lector.token, seed, lector.liveKey, lector.now - Clock.System.now().toEpochMilliseconds()),
            handles = handles,
        )
    }

    private val sessions = object : LinkedHashMap<String, ReaderSession>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReaderSession>?): Boolean = size > 10
    }
    private val sessionLock = Mutex()

    // The page list Mihon caches can hold the keys of a visit the site forgot:
    // a new visit replaces it, unless another page request already made one
    private fun renewSession(chapterKey: String, staleSeed: String): ReaderSession? = runBlocking {
        sessionLock.withLock {
            sessions[chapterKey]?.takeIf { it.keys.seed != staleSeed }
                ?: readerSession(openReader(SChapter.create().apply { url = chapterKey })).also { sessions[chapterKey] = it }
        }
    }

    override fun getImageRequest(page: Page): Request = super.getImageRequest(page).newBuilder()
        .fetch(Fetch.XHR.from(page.url))
        .build()

    companion object {
        private const val PREF_EMAIL = "aedex_email"
        private const val PREF_PASSWORD = "aedex_password"
        private const val SESSION_COOKIE_MARKER = "next-auth.session-token"
        private const val PASS_COOKIE = "ax_rp"
        private const val MAX_ATTEMPTS = 3
        private val HEX_32_REGEX = Regex("^[0-9a-f]{32}$")
        private val LECTOR_REGEX = Regex("""window\.\w+\s*=\s*(?:JSON\.parse\(atob\("([^"]+)"\)\)|(\{[^;]+\}))""")
    }
}

internal const val LOGIN_REQUIRED_MESSAGE = "Inicia sesión en los ajustes de la extensión (Correo y Contraseña) o en WebView para leer este capítulo"
