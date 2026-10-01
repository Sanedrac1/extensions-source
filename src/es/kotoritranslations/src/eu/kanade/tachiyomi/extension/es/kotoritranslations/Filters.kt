package eu.kanade.tachiyomi.extension.es.kotoritranslations

import eu.kanade.tachiyomi.source.model.Filter

class TipoFilter :
    Filter.Select<String>(
        "Tipo",
        arrayOf("Todos", "Manga", "Manhwa"),
    ) {
    val selected: String?
        get() = when (state) {
            1 -> "MANGA"
            2 -> "MANHWA"
            else -> null
        }
}

class EstadoFilter :
    Filter.Select<String>(
        "Estado",
        arrayOf("Todos", "En curso", "Finalizado"),
    ) {
    val selected: String?
        get() = when (state) {
            1 -> "EN_CURSO"
            2 -> "FINALIZADO"
            else -> null
        }
}

class GeneroFilter(genres: Array<String>) :
    Filter.Select<String>(
        "Género",
        arrayOf("Todos") + genres,
    ) {
    val selected: String?
        get() = if (state == 0) null else values[state]
}

class OrderFilter :
    Filter.Select<String>(
        "Ordenar por",
        arrayOf("Por defecto", "Más populares", "Últimos agregados", "Título (A-Z)"),
    )
