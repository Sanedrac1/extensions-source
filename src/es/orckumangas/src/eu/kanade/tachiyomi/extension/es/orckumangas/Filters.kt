package eu.kanade.tachiyomi.extension.es.orckumangas

import eu.kanade.tachiyomi.source.model.Filter

class StatusFilter :
    Filter.Select<String>(
        "Estado",
        statuses.map { it.first }.toTypedArray(),
    ) {
    val selected get() = statuses.getOrNull(state)?.second ?: ""

    companion object {
        private val statuses = listOf(
            "Todos" to "",
            "En curso" to "ongoing",
            "Finalizado" to "completed",
            "Hiatus" to "hiatus",
            "Cancelado" to "cancelled",
        )
    }
}

class TypeFilter :
    Filter.Select<String>(
        "Tipo",
        types.map { it.first }.toTypedArray(),
    ) {
    val selected get() = types.getOrNull(state)?.second ?: ""

    companion object {
        private val types = listOf(
            "Todos" to "",
            "Manga" to "manga",
            "Manhwa" to "manhwa",
            "Manhua" to "manhua",
        )
    }
}

class SortFilter :
    Filter.Select<String>(
        "Ordenar por",
        sorts.map { it.first }.toTypedArray(),
    ) {
    val selected get() = sorts.getOrNull(state)?.second ?: ""

    companion object {
        private val sorts = listOf(
            "Más recientes" to "recientes",
            "Más vistos" to "vistas",
            "Mejor puntuados" to "rating",
            "Alfabético (A-Z)" to "alfabetico",
        )
    }
}

class GenreFilter(private val genres: List<Pair<String, String>> = defaultGenres) :
    Filter.Select<String>(
        "Género",
        genres.map { it.first }.toTypedArray(),
    ) {
    val selected get() = genres.getOrNull(state)?.second ?: ""

    companion object {
        val defaultGenres = listOf(
            Pair("Todos", "0"),
            Pair("A color", "39"),
            Pair("Acción", "1"),
            Pair("Adaptación", "36"),
            Pair("Adulto", "22"),
            Pair("Ahegao", "23"),
            Pair("Animales", "51"),
            Pair("Antología", "37"),
            Pair("Artes Marciales", "7"),
            Pair("Aventura", "2"),
            Pair("BDSM", "30"),
            Pair("Bisexual", "47"),
            Pair("Bukkake", "53"),
            Pair("Chantaje", "16"),
            Pair("Cheating", "29"),
            Pair("Chotas", "44"),
            Pair("Comedia", "3"),
            Pair("Creampie", "28"),
            Pair("Crossdressing", "38"),
            Pair("Demonios", "25"),
            Pair("Deportes", "18"),
            Pair("Drama", "4"),
            Pair("Ecchi", "5"),
            Pair("Escolar", "18"),
            Pair("Exhibición", "24"),
            Pair("Fantasía", "6"),
            Pair("Fetish", "45"),
            Pair("Full Color", "30"),
            Pair("Futanari", "26"),
            Pair("Gender Bender", "32"),
            Pair("Gore", "20"),
            Pair("Harem", "8"),
            Pair("Hentai", "21"),
            Pair("Histórico", "19"),
            Pair("Horror", "10"),
            Pair("Incesto", "27"),
            Pair("Isekai", "17"),
            Pair("Josei", "16"),
            Pair("MILFS", "21"),
            Pair("Misterio", "11"),
            Pair("NTR", "17"),
            Pair("Psicológico", "12"),
            Pair("RAPE", "29"),
            Pair("Recuentos de la Vida", "20"),
            Pair("Romance", "13"),
            Pair("Seinen", "14"),
            Pair("Shota", "31"),
            Pair("Shoujo", "15"),
            Pair("Shounen", "9"),
            Pair("Sobrenatural", "28"),
            Pair("Supervivencia", "33"),
            Pair("Tragedia", "35"),
            Pair("Vampiros", "31"),
            Pair("Vanilla", "34"),
            Pair("Ventanas", "13"),
            Pair("Webtoon", "27"),
            Pair("Yaoi", "49"),
            Pair("Yuri", "48"),
        )
    }
}
