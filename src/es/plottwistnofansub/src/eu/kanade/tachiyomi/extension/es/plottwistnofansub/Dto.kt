package eu.kanade.tachiyomi.extension.es.plottwistnofansub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ChapterApiResponse(
    @SerialName("chapters_to_display") val chaptersToDisplay: List<ChapterDto> = emptyList(),
    @SerialName("nav_items") val navItems: List<ChapterDto> = emptyList(),
) {
    val chapters: List<ChapterDto>
        get() = chaptersToDisplay.ifEmpty { navItems }
}

@Serializable
class ChapterDto(
    val name: String,
    @SerialName("name_extend") val nameExtend: String = "",
    val link: String,
    val date: String,
)

@Serializable
class LoadChaptersApiResponse(
    val success: Boolean,
    val data: LoadChaptersDataDto? = null,
)

@Serializable
class LoadChaptersDataDto(
    val html: String = "",
    @SerialName("has_more") val has_more: Boolean = false,
)
