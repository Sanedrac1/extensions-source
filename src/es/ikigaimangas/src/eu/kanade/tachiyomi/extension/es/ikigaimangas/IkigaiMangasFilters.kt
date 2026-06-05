package eu.kanade.tachiyomi.extension.es.ikigaimangas

import eu.kanade.tachiyomi.source.model.Filter

class Genre(title: String, val id: Long) : Filter.CheckBox(title)
class GenreFilter(title: String, genres: List<Genre>) : Filter.Group<Genre>(title, genres)

class Status(title: String, val id: Long) : Filter.CheckBox(title)
class StatusFilter(title: String, statuses: List<Status>) : Filter.Group<Status>(title, statuses)

class SortByFilter(title: String, private val sortProperties: List<SortProperty>) :
    Filter.Sort(
        title,
        sortProperties.map { it.name }.toTypedArray(),
        Selection(0, ascending = true),
    ) {
    val selected: String
        get() = sortProperties[state!!.index].value
}

class SortProperty(val name: String, val value: String) {
    override fun toString(): String = name
}

class TypeFilter(title: String, private val types: List<Pair<String, String>>) : Filter.Select<String>(title, types.map { it.first }.toTypedArray()) {
    val selected: String
        get() = types[state].second
}

class Team(title: String, val id: Long) : Filter.CheckBox(title)
class TeamFilter(title: String, teams: List<Team>) : Filter.Group<Team>(title, teams)
