package eu.kanade.tachiyomi.extension.es.kotoritranslations

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable

@Serializable
class SeriesDto(
    val id: Long,
    val titulo: String,
    val sinopsis: String? = null,
    val portada: String? = null,
    val tipo: String? = null,
    val estado: String? = null,
    val vistas: Long = 0L,
    val popular: Boolean = false,
    val publicado: Boolean = true,
    val fechaCreacion: String? = null,
    val generos: List<String> = emptyList(),
    val uploader: String? = null,
) {
    fun toSManga(): SManga = SManga.create().apply {
        title = titulo
        url = "/serie/$id"
        thumbnail_url = portada
    }
}

@Serializable
class SerieDetailDto(
    val serie: SeriesDto,
    val capitulos: List<CapituloDto> = emptyList(),
)

@Serializable
class CapituloDto(
    val id: Long,
    val numero: Double = 0.0,
    val titulo: String? = null,
    val precioPuntos: Int = 0,
    val precioUsd: Double = 0.0,
    val descargaHabilitada: Boolean = false,
    val fechaPublicacion: String? = null,
    val serieId: Long? = null,
    val serieTitulo: String? = null,
)

@Serializable
class CapituloDetailDto(
    val id: Long,
    val numero: Double = 0.0,
    val titulo: String? = null,
    val serieId: Long? = null,
    val tokenLector: String,
    val paginas: List<PaginaDto> = emptyList(),
)

@Serializable
class PaginaDto(
    val id: Long,
    val numero: Int,
)
