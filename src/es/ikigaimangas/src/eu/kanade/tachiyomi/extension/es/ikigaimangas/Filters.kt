package eu.kanade.tachiyomi.extension.es.ikigaimangas

import eu.kanade.tachiyomi.source.model.Filter

class Genre(title: String, val id: Long) : Filter.CheckBox(title)
class GenreFilter(title: String, genres: List<Genre>) : Filter.Group<Genre>(title, genres)

class Status(title: String, val id: Long) : Filter.CheckBox(title)
class StatusFilter(title: String, statuses: List<Status>) : Filter.Group<Status>(title, statuses)

class Team(title: String, val id: Long) : Filter.CheckBox(title)
class TeamFilter(title: String, teams: List<Team>) : Filter.Group<Team>(title, teams)

class SortByFilter(title: String, private val sortProperties: List<SortProperty>) :
    Filter.Sort(
        title,
        sortProperties.map { it.name }.toTypedArray(),
        Selection(2, ascending = false),
    ) {
    val selected: String
        get() = sortProperties[state!!.index].value
}

class SortProperty(val name: String, val value: String) {
    override fun toString(): String = name
}

fun getSortProperties(): List<SortProperty> = listOf(
    SortProperty("Nombre", "name"),
    SortProperty("Creado en", "created_at"),
    SortProperty("Actualización más reciente", "last_chapter_date"),
    SortProperty("Número de favoritos", "bookmark_count"),
    SortProperty("Número de valoración", "rating_count"),
    SortProperty("Número de vistas", "view_count"),
)

fun getStatusFilters(): List<Status> = listOf(
    Status("Abandonada", 906428048651190273L),
    Status("Cancelada", 906426661911756802L),
    Status("Completa", 906409532796731395L),
    Status("En Curso", 911437469204086787L),
    Status("Hiatus", 906409397258190851L),
)

fun getGenreFilters(): List<Genre> = listOf(
    Genre("+18", 906409351272792067L),
    Genre("Acción", 906397904327999491L),
    Genre("Adulto", 906409527934582787L),
    Genre("Apocalíptico", 906409378635186179L),
    Genre("Artes Marciales", 906397904169861123L),
    Genre("Aventura", 906397904061530115L),
    Genre("Boys Love", 906409351330037763L),
    Genre("Ciencia Ficción", 906409468787720195L),
    Genre("Comedia", 906398112851165187L),
    Genre("Demonios", 906397904115531779L),
    Genre("Deportes", 906410143226462211L),
    Genre("Doujinshi", 1187154685166452739L),
    Genre("Drama", 906397903933407235L),
    Genre("Ecchi", 906409370648543235L),
    Genre("Familia", 906409382485884931L),
    Genre("Fantasía", 906397894348570627L),
    Genre("Gender Bender", 1093357252096753667L),
    Genre("Girls Love", 906409644012961795L),
    Genre("Gore", 906409472386203651L),
    Genre("Guideverse", 1182242384692314113L),
    Genre("Harem", 906397904221962243L),
    Genre("Harem Inverso", 906424352006438914L),
    Genre("Histórico", 906398112923385859L),
    Genre("Horror", 906423434084679682L),
    Genre("Isekai", 906409454067646467L),
    Genre("Josei", 906409501957390339L),
    Genre("Maduro", 906409612041551875L),
    Genre("Magia", 906409459593347075L),
    Genre("Manga", 1187155307072782337L),
    Genre("Mecha", 906409472453410819L),
    Genre("Militar", 906409472509739011L),
    Genre("Misterio", 906409374254727171L),
    Genre("Omegaverse", 1182242409543827457L),
    Genre("Psicológico", 906409351382073347L),
    Genre("Realidad Virtual", 906424676182294530L),
    Genre("Recuentos de la vida", 906409508165124099L),
    Genre("Reencarnación", 906409400553046019L),
    Genre("Regresion", 906397894469255171L),
    Genre("Romance", 906397894527549443L),
    Genre("Seinen", 906397903999959043L),
    Genre("Shonen", 906398112991150083L),
    Genre("Shoujo", 906397894408372227L),
    Genre("Shoujo Ai", 1187155022664531971L),
    Genre("Shounen Ai", 1187155082787848194L),
    Genre("Sistema", 906409408107216899L),
    Genre("Smut", 906409419999641603L),
    Genre("Supernatural", 906410027513937923L),
    Genre("Supervivencia", 906409454130921475L),
    Genre("Tragedia", 906409449984655363L),
    Genre("Transmigración", 906409378688663555L),
    Genre("Vida Escolar", 906409508232822787L),
    Genre("Yaoi", 906409432216403971L),
    Genre("Yuri", 906409472567017475L),
)

fun getTeamFilters(): List<Team> = listOf(
    Team("Agus Scan", 1159963897850691587L),
    Team("Ajce Translations", 906409876344340483L),
    Team("Asian Lotus", 1101353340196061186L),
    Team("Baco Scans", 1061110805753331713L),
    Team("BaoBai_Scan", 999770512349265923L),
    Team("Berserker Scan", 906409496056758275L),
    Team("Bichotas Fansub", 961787977711583233L),
    Team("Bighitler", 906409374971101187L),
    Team("Cerberus Scans", 906398106035617795L),
    Team("Churritos Scan", 906409415073759235L),
    Team("Dragon Translation", 906409335735582723L),
    Team("El Grimorio De La Witch", 906424179158581250L),
    Team("Fluxia Scan", 1038427076726095874L),
    Team("GODS OF ARDA SCAN", 906409985632698371L),
    Team("Gu Scan", 1160495390387535873L),
    Team("HadaScan", 906409442634825731L),
    Team("Harem De Kira", 906409722157760515L),
    Team("Ikigai", 1034297331986759681L),
    Team("Inmortales", 1103395843340795905L),
    Team("Invictus Scan", 906409382934151171L),
    Team("Knight No Scanlation", 1085740186537525251L),
    Team("LiontáriScan", 906409916686630915L),
    Team("Lukelun fansub", 906423712055164930L),
    Team("MangoScan", 906409371190722563L),
    Team("MerakiScan", 906397904960651267L),
    Team("Nartag", 1159045379221651459L),
    Team("Neko Kawaii Scan", 906409348073717763L),
    Team("Orckuro Translations", 906410144030064643L),
    Team("Pastelitos fujoshii", 957863066463272961L),
    Team("Pichulas Scan", 908855416685953027L),
    Team("PununiScan", 906409518003585027L),
    Team("Ragnarok Scanlation", 1169607386323714050L),
    Team("RichtoScan", 1041876062917820419L),
    Team("Riomy Scan", 1162488551331069955L),
    Team("Shinra Tensei", 1093282307338108930L),
    Team("Sisu Scan", 906409542107660291L),
    Team("Spectrum Scan", 906409351802290179L),
    Team("Starlight Translations", 906397890719318019L),
    Team("Tecno Scan", 906409362829606915L),
    Team("The Lonely Reader", 906409608911618051L),
    Team("Tiranos Scan", 906409473310162947L),
)
