package com.andreimikhailov.models

import com.andreimikhailov.formats.YamlConfigInstance
import com.fasterxml.jackson.databind.JsonNode
import org.http4k.format.Jackson
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.time
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter


// This is helpful:
// https://stackoverflow.com/questions/62471193/how-to-adjust-table-name-in-kotlin-exposed-on-runtime
class UsersTableSchema(name: String) {
    val users = Users(name)
}

class Users(name: String) : Table(name) {
    val login = text("login")
    val password = text("password")
}

enum class Visibility {
    HIDE, BUSY, SHOW
}
enum class ShowingTo { ALL, GROUP }

class DispatchException(message: String) : Exception(message)

class EventsTableSchema(name: String) {
    val events = Events(name)
}

data class Situation(val user: String?, val dayFrom: LocalDate, val dayUntil: LocalDate)

class Events(name: String) : IntIdTable(name) {
    val owner = text("owner")
    val startDate = date("start_date")
    val startTime = time("start_time")
    val repeatWeeks = integer("repeat_weeks")
    val description = text("description")
    val link = text("link")
    val showToGroup = integer("show_to_group")
    val showToAll = integer("show_to_all")
}

data class EventData(
    val id: Int,
    val owner: String,
    val startDate: LocalDate,
    val startTime: LocalTime,
    val repeatWeeks: Int,
    val description: String,
    val link: String,
    val showToGroup: Visibility,
    val showToAll: Visibility
)
data class EventDataToShow(
    val id: Int,
    val owner: String,
    val startDateTime: LocalDateTime,
    val startString: String,
    val repeatWeeks: Int,
    val description: String,
    val link: String,
    val showToGroup: Int,
    val showToAll: Int,
    val daysTo: Int
)

data class EventDataToEdit(
    val id: Int,
    val owner: String,
    val startDate: LocalDate,
    val startTime: LocalTime,
    val repeatWeeks: Int,
    val description: String,
    val link: String,
    val showToGroup: Int,
    val showToAll: Int
)

private fun int2vis(i: Int): Visibility {
    return when (i) {
        0 -> Visibility.HIDE
        1 -> Visibility.BUSY
        2 -> Visibility.SHOW
        else -> throw DispatchException("visibility out of range")
    }
}

fun vis2int(v: Visibility): Int {
    return when(v) {
        Visibility.HIDE -> 0
        Visibility.BUSY -> 1
        Visibility.SHOW -> 2
    }
}

fun eventDataToEdit(ev: ResultRow, schema: EventsTableSchema): EventDataToEdit {
    return EventDataToEdit(
        ev[schema.events.id].value,
        ev[schema.events.owner],
        ev[schema.events.startDate],
        ev[schema.events.startTime],
        ev[schema.events.repeatWeeks],
        ev[schema.events.description],
        ev[schema.events.link],
        ev[schema.events.showToGroup],
        ev[schema.events.showToAll]
    )
}

fun eventData(ev: ResultRow, schema: EventsTableSchema): EventData {
    return EventData(
        ev[schema.events.id].value,
        ev[schema.events.owner],
        ev[schema.events.startDate],
        ev[schema.events.startTime],
        ev[schema.events.repeatWeeks],
        ev[schema.events.description],
        ev[schema.events.link],
        int2vis(ev[schema.events.showToGroup]),
        int2vis(ev[schema.events.showToAll])
    )
}

fun eventAsJson(ev: EventDataToShow, schema: EventsTableSchema, conf: YamlConfigInstance): JsonNode  {
    val localZone = ZoneId.of(conf.zoneId)
    val ldtZoned = ev.startDateTime.atZone(localZone).withZoneSameInstant(ZoneId.of("UTC"))
    return Jackson.obj(
        "id" to Jackson.number(ev.id),
        "owner" to Jackson.string(ev.owner),
        "startDateTime" to Jackson.string(ldtZoned.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))),
        "repeatWeeks" to Jackson.number(ev.repeatWeeks),
        "description" to Jackson.string(ev.description),
        "link" to Jackson.string(ev.link),
        "showToGroup" to Jackson.number(ev.showToGroup),
        "showToAll" to Jackson.number(ev.showToAll),
        "daysTo" to Jackson.number(ev.daysTo)
    )
}

