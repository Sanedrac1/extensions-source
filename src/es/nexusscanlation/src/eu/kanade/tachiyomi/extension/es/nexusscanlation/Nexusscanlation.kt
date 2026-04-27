package eu.kanade.tachiyomi.extension.es.nexusscanlation

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Nexusscanlation :
    HttpSource(),
    ConfigurableSource {

    override val name = "NexusScanlation"
    override val baseUrl = "https://nexusscanlation.com"
    override val lang = "es"
    override val supportsLatest = true

    private val apiBaseUrl = "https://api.nexusscanlation.com/api/v1"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(::authInterceptor)
        .build()

    override fun headersBuilder(): Headers.Builder {
        val builder = super.headersBuilder()
            .add("Referer", baseUrl)
            .add("Origin", "https://nexusscanlation.com")
            .add("Accept", "application/json, text/plain, */*")
            .add("Accept-Language", "es-ES,es;q=0.9")

        val userAgent = preferences.getString(PREF_USER_AGENT, "") ?: ""
        if (userAgent.isNotBlank()) {
            builder.set("User-Agent", userAgent)
        }

        val customCookie = preferences.getString(PREF_CUSTOM_COOKIE, "") ?: ""
        val adultCookie = "adult_content_bypass=1; age_gate_bypassed=true"
        val finalCookie = if (customCookie.isNotBlank()) {
            "$adultCookie; $customCookie"
        } else {
            adultCookie
        }
        builder.add("Cookie", finalCookie)

        return builder
    }

    private fun authInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (!request.url.host.contains("api.nexusscanlation.com")) {
            return chain.proceed(request)
        }

        if (request.url.encodedPath.contains("/auth/login")) {
            return chain.proceed(request)
        }

        var token = preferences.getString(PREF_ACCESS_TOKEN, "") ?: ""
        val expiresAtStr = preferences.getString(PREF_EXPIRES_AT, "") ?: ""
        val manualToken = preferences.getString(PREF_MANUAL_TOKEN, "") ?: ""

        if (manualToken.isNotBlank()) {
            token = manualToken
        } else {
            val isExpired = isTokenExpired(expiresAtStr)
            if (token.isEmpty() || isExpired) {
                token = performLogin() ?: ""
            }
        }

        if (token.isEmpty()) {
            return chain.proceed(request)
        }

        val authRequest = request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        val response = chain.proceed(authRequest)

        if (response.code == 401 && manualToken.isBlank()) {
            response.close()
            val newToken = performLogin() ?: ""
            if (newToken.isNotEmpty()) {
                val retryRequest = request.newBuilder()
                    .header("Authorization", "Bearer $newToken")
                    .build()
                return chain.proceed(retryRequest)
            }
        }

        return response
    }

    private fun isTokenExpired(expiresAtStr: String): Boolean {
        if (expiresAtStr.isBlank()) return false
        return try {
            val timestamp = expiresAtStr.toLongOrNull()
            if (timestamp != null) {
                val timeMs = if (timestamp < 1000000000000L) timestamp * 1000 else timestamp
                System.currentTimeMillis() >= timeMs
            } else {
                val date = dateFormat.parse(expiresAtStr)
                date != null && System.currentTimeMillis() >= date.time
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun performLogin(): String? {
        val email = preferences.getString(PREF_EMAIL, "") ?: ""
        val password = preferences.getString(PREF_PASSWORD, "") ?: ""

        if (email.isBlank() || password.isBlank()) {
            return null
        }

        val payload = """{"email":"$email","password":"$password"}"""
        val requestBody = payload.toRequestBody("application/json".toMediaTypeOrNull())

        val loginUrl = "$apiBaseUrl/auth/login"
        val request = Request.Builder()
            .url(loginUrl)
            .post(requestBody)
            .headers(headersBuilder().build())
            .build()

        return try {
            val response = network.client.newCall(request).execute()
            if (response.isSuccessful) {
                val authResponse = response.parseAs<AuthResponseDto>()
                val token = authResponse.access_token ?: return null
                val expiresAt = authResponse.expires_at?.jsonPrimitive?.content ?: ""

                preferences.edit()
                    .putString(PREF_ACCESS_TOKEN, token)
                    .putString(PREF_EXPIRES_AT, expiresAt)
                    .apply()

                token
            } else {
                response.close()
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val emailPref = EditTextPreference(screen.context).apply {
            key = PREF_EMAIL
            title = "Email"
            summary = "Email para iniciar sesión en la web"
            setDefaultValue("")
        }
        screen.addPreference(emailPref)

        val passwordPref = EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD
            title = "Contraseña"
            summary = "Contraseña para iniciar sesión"
            setDefaultValue("")
        }
        screen.addPreference(passwordPref)

        val manualTokenPref = EditTextPreference(screen.context).apply {
            key = PREF_MANUAL_TOKEN
            title = "Token Manual (Opcional)"
            summary = "Útil para entornos sin WebView (ej: Suwayomi). Sobrescribe el login automático."
            setDefaultValue("")
        }
        screen.addPreference(manualTokenPref)

        val userAgentPref = EditTextPreference(screen.context).apply {
            key = PREF_USER_AGENT
            title = "User-Agent"
            summary = "Para evadir Cloudflare. Ej. de navegador de PC."
            setDefaultValue("")
        }
        screen.addPreference(userAgentPref)

        val customCookiePref = EditTextPreference(screen.context).apply {
            key = PREF_CUSTOM_COOKIE
            title = "Cookie Manual (cf_clearance)"
            summary = "Valor de la cookie cf_clearance si es requerida por Cloudflare."
            setDefaultValue("")
        }
        screen.addPreference(customCookiePref)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String {
        val (seriesSlug, chapterSlug) = chapter.url.split('/', limit = 2)
        return "$baseUrl/series/$seriesSlug/chapter/$chapterSlug"
    }

    override fun popularMangaRequest(page: Int): Request {
        val url = apiBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("catalog")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("orden", "popular")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val root = response.parseAs<CatalogResponseDto>()
        return MangasPage(root.data.orEmpty().mapNotNull(::catalogToManga), root.meta?.hasNext ?: false)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = apiBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("catalog")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("orden", "nuevo")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val urlBuilder = apiBaseUrl.toHttpUrl().newBuilder()

        if (query.isBlank()) {
            urlBuilder.addPathSegment("catalog")
        } else {
            urlBuilder
                .addPathSegment("catalog")
                .addPathSegment("search")
                .addQueryParameter("q", query)
        }

        urlBuilder.addQueryParameter("page", page.toString())
        return GET(urlBuilder.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = apiBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("series")
            .addPathSegment(manga.url)
            .build()
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val root = response.parseAs<SeriesPayloadDto>()
        return seriesToManga(root.serie)
    }

    override fun chapterListRequest(manga: SManga): Request {
        val url = apiBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("series")
            .addPathSegment(manga.url)
            .build()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val payload = response.parseAs<SeriesPayloadDto>()
        val seriesSlug = payload.serie.slug

        return payload.capitulos.orEmpty()
            .map { chapterToModel(seriesSlug, it) }
            .toList()
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val (seriesSlug, chapterSlug) = chapter.url.split('/', limit = 2)

        val url = apiBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("series")
            .addPathSegment(seriesSlug)
            .addPathSegment("capitulos")
            .addPathSegment(chapterSlug)
            .build()

        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val payload = response.parseAs<ChapterPagesPayloadDto>()
        return payload.data?.paginas.orEmpty()
            .mapIndexed { index, page ->
                // Reemplazo dinámico de forma eficiente
                val fixedUrl = page.url.replace(r2Regex, "https://cdn.nexusscanlation.com")
                Page(index, imageUrl = fixedUrl)
            }
            .toList()
    }

    private fun catalogToManga(item: CatalogEntryDto): SManga? {
        if (item.slug.isBlank() || item.titulo.isBlank()) return null
        return SManga.create().apply {
            url = item.slug
            title = item.titulo
            thumbnail_url = item.portadaUrl
        }
    }

    private fun chapterToModel(seriesSlug: String, chapter: ChapterEntryDto): SChapter {
        val chapterNumber = chapter.numero.toString().removeSuffix(".0")

        return SChapter.create().apply {
            url = "$seriesSlug/${chapter.slug}"
            name = "Capitulo $chapterNumber"
            chapter_number = chapter.numero
            date_upload = dateFormat.tryParse(chapter.publishedAt)
        }
    }

    private fun seriesToManga(series: SeriesDto): SManga = SManga.create().apply {
        title = series.titulo
        thumbnail_url = series.portadaUrl
        description = series.descripcion
        genre = series.generos
            ?.mapNotNull { it.nombre.takeIf { name -> name.isNotBlank() } }
            ?.joinToString()

        status = when (series.estado.lowercase(Locale.ROOT)) {
            "en_emision" -> SManga.ONGOING
            "finalizado" -> SManga.COMPLETED
            "pausado" -> SManga.ON_HIATUS
            "cancelado" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        val credits = series.autores.orEmpty().mapNotNull { credit ->
            credit.nombre.takeIf { it.isNotBlank() }?.trim()?.let { it to credit.rol?.lowercase(Locale.ROOT) }
        }

        author = credits
            .filter { (_, role) -> role != "artista" }
            .map { (name) -> name }
            .distinct()
            .joinToString()
            .ifBlank { null }

        artist = credits
            .filter { (_, role) -> role == "artista" }
            .map { (name) -> name }
            .distinct()
            .joinToString()
            .ifBlank { null }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    companion object {
        private const val PREF_EMAIL = "email"
        private const val PREF_PASSWORD = "password"
        private const val PREF_MANUAL_TOKEN = "manual_token"
        private const val PREF_ACCESS_TOKEN = "access_token"
        private const val PREF_EXPIRES_AT = "expires_at"
        private const val PREF_USER_AGENT = "user_agent"
        private const val PREF_CUSTOM_COOKIE = "custom_cookie"
        
        private val r2Regex = Regex("""https://[a-zA-Z0-9\-]+\.r2\.cloudflarestorage\.com""")
    }
}
