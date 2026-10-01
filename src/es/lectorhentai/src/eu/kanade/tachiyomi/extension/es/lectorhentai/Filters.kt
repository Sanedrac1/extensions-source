package eu.kanade.tachiyomi.extension.es.lectorhentai

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(
    displayName: String,
    private val vals: Array<Pair<String, String>>,
    defaultState: Int = 0,
) : Filter.Select<String>(
    displayName,
    vals.map { it.first }.toTypedArray(),
    defaultState,
) {
    fun toUriPart() = vals[state].second
}

class OrderFilter(defaultState: Int = 0) :
    UriPartFilter(
        "Ordenar por",
        arrayOf(
            Pair("Últimos agregados", "latest"),
            Pair("Populares", "popular"),
            Pair("Título (A-Z)", "title"),
            Pair("Título (Z-A)", "titlereverse"),
        ),
        defaultState,
    )

class LanguageFilter :
    UriPartFilter(
        "Lenguaje",
        arrayOf(
            Pair("Todos", "all"),
            Pair("Español", "Español"),
            Pair("Inglés", "Ingles"),
            Pair("Portugués", "Português"),
            Pair("Francés", "Français"),
        ),
    )

class Genre(name: String, val value: String = name) : Filter.CheckBox(name)

class GenreListFilter(genres: List<Genre>) : Filter.Group<Genre>("Géneros", genres)

val genresList = listOf(
    Genre("3D"),
    Genre("Adultery"),
    Genre("Adventure"),
    Genre("Ahegao"),
    Genre("Anal"),
    Genre("Bestiality"),
    Genre("Big Breasts"),
    Genre("BlowJob"),
    Genre("Bondage"),
    Genre("Bukkake"),
    Genre("Cheating"),
    Genre("Colour"),
    Genre("Comedy"),
    Genre("Domination"),
    Genre("Fantasy"),
    Genre("Femdom"),
    Genre("Fetish"),
    Genre("FootJob"),
    Genre("Forced"),
    Genre("Furry"),
    Genre("Futanari"),
    Genre("Harem"),
    Genre("Horror"),
    Genre("Incest"),
    Genre("Lolicon"),
    Genre("Mature"),
    Genre("Milf"),
    Genre("Monsters"),
    Genre("Netorare"),
    Genre("Nympho"),
    Genre("Orgy"),
    Genre("Parody"),
    Genre("Pregnant"),
    Genre("Public Sex"),
    Genre("Rape"),
    Genre("Romance"),
    Genre("Shotacon"),
    Genre("Small Breast"),
    Genre("Sport"),
    Genre("Student"),
    Genre("Tentacles"),
    Genre("Toys"),
    Genre("Tsundere"),
    Genre("Uncensored"),
    Genre("Vanilla"),
    Genre("Virgin"),
    Genre("Yandere"),
)
