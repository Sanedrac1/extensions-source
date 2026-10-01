package eu.kanade.tachiyomi.extension.es.kotoritranslations

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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class KotoriTranslations : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(3)

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0L
        return runCatching {
            dateFormat.parse(dateStr.substringBefore("."))?.time ?: 0L
        }.getOrDefault(0L)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val series = client.get("$baseUrl/api/series").parseAs<List<SeriesDto>>()
            .filter { it.publicado }
            .sortedByDescending { it.vistas }
            .map { it.toSManga() }
        return MangasPage(series, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val series = client.get("$baseUrl/api/series").parseAs<List<SeriesDto>>()
            .filter { it.publicado }
            .sortedByDescending { parseDate(it.fechaCreacion) }
            .map { it.toSManga() }
        return MangasPage(series, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        var list = client.get("$baseUrl/api/series").parseAs<List<SeriesDto>>()
            .filter { it.publicado }

        if (query.isNotBlank()) {
            list = list.filter { it.titulo.contains(query, ignoreCase = true) }
        }

        val tipo = filters.firstInstanceOrNull<TipoFilter>()?.selected
        if (!tipo.isNullOrBlank()) {
            list = list.filter { it.tipo.equals(tipo, ignoreCase = true) }
        }

        val estado = filters.firstInstanceOrNull<EstadoFilter>()?.selected
        if (!estado.isNullOrBlank()) {
            list = list.filter { it.estado.equals(estado, ignoreCase = true) }
        }

        val genero = filters.firstInstanceOrNull<GeneroFilter>()?.selected
        if (!genero.isNullOrBlank()) {
            list = list.filter { s ->
                s.generos.any { it.trim().equals(genero.trim(), ignoreCase = true) }
            }
        }

        val order = filters.firstInstanceOrNull<OrderFilter>()?.state ?: 0
        list = when (order) {
            1 -> list.sortedByDescending { it.vistas }
            2 -> list.sortedByDescending { parseDate(it.fechaCreacion) }
            3 -> list.sortedBy { it.titulo.lowercase(Locale.ROOT) }
            else -> list
        }

        return MangasPage(list.map { it.toSManga() }, false)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        TipoFilter(),
        EstadoFilter(),
        GeneroFilter(genresList),
        Filter.Separator(),
        OrderFilter(),
    )

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val mangaPath = when {
            url.encodedPath.startsWith("/serie/") -> url.encodedPath
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
        val id = manga.url.substringAfterLast("/")
        val detail = client.get("$baseUrl/api/series/$id/detalle").parseAs<SerieDetailDto>()
        val serie = detail.serie

        val details = if (fetchDetails) {
            SManga.create().apply {
                title = serie.titulo
                url = "/serie/${serie.id}"
                thumbnail_url = serie.portada
                description = serie.sinopsis?.takeIf { it.isNotBlank() }
                author = serie.uploader
                artist = serie.uploader
                genre = serie.generos.joinToString(", ")
                status = when (serie.estado) {
                    "EN_CURSO" -> SManga.ONGOING
                    "FINALIZADO" -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            }
        } else {
            manga
        }

        val chapterList = if (fetchChapters) {
            detail.capitulos.map { ch ->
                SChapter.create().apply {
                    val numStr = if (ch.numero % 1.0 == 0.0) ch.numero.toInt().toString() else ch.numero.toString()
                    name = buildString {
                        append("Capítulo $numStr")
                        if (!ch.titulo.isNullOrBlank() && ch.titulo != serie.titulo) {
                            append(": ${ch.titulo}")
                        }
                    }
                    url = "/lector/${ch.id}"
                    chapter_number = ch.numero.toFloat()
                    date_upload = parseDate(ch.fechaPublicacion)
                }
            }.reversed()
        } else {
            chapters
        }

        return SMangaUpdate(details, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.url.substringAfterLast("/")
        val detail = client.get("$baseUrl/api/capitulos/$id/detalle").parseAs<CapituloDetailDto>()
        val token = URLEncoder.encode(detail.tokenLector, "UTF-8")
        return detail.paginas.map { pagina ->
            Page(
                pagina.numero - 1,
                imageUrl = "$baseUrl/api/lector/imagen/${pagina.id}?token=$token",
            )
        }
    }

    companion object {
        private val genresList = arrayOf(
            "Acción",
            "Ahegao",
            "Anal",
            "Aventura",
            "Big ass",
            "Big breasts",
            "Blowjob",
            "Cheating",
            "Cheating Waifu",
            "Comedia",
            "Cowgirl Position",
            "Creampie",
            "Cum",
            "Cum In Pussy",
            "DoggyStyle Position",
            "Drama",
            "Ecchi",
            "Elf",
            "Escolar",
            "Fantasía",
            "Femdom",
            "Futanari",
            "Gal",
            "Gangbang",
            "Gender bender",
            "Goblin",
            "Gyaru",
            "Impregnation",
            "Incesto",
            "Kissing",
            "Magia",
            "Masturbation",
            "Mature",
            "Milf",
            "Mind Control",
            "Mother",
            "NTR",
            "Netorare",
            "Oral Sex",
            "Orgia",
            "Orgy",
            "Pregnant",
            "Pregnant Sex",
            "Rape",
            "Reencarnación",
            "Romance",
            "Sexy Girl",
            "Shota",
            "Sin Censura",
            "Succubus",
            "Tomboy",
            "Trio",
            "Ugly Bastard",
            "Yandere",
        )
    }
}
