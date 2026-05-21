package digital.dutton.essentials.calendar.sync

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class IcsCalendarParser {
    fun parse(content: String): IcsCalendarFeed {
        val lines = content.lineSequence().toList().unfoldIcsLines()
        if (lines.none { it.equals("BEGIN:VCALENDAR", ignoreCase = true) }) {
            throw IllegalArgumentException("Calendar feed is not an iCalendar file.")
        }

        val displayName = lines
            .asSequence()
            .mapNotNull(::parseProperty)
            .firstOrNull { it.name == "X-WR-CALNAME" }
            ?.decodedValue()

        val events = buildList {
            var inEvent = false
            val eventLines = mutableListOf<String>()

            lines.forEach { line ->
                when {
                    line.equals("BEGIN:VEVENT", ignoreCase = true) -> {
                        inEvent = true
                        eventLines.clear()
                    }
                    line.equals("END:VEVENT", ignoreCase = true) && inEvent -> {
                        parseEvent(eventLines)?.let(::add)
                        inEvent = false
                        eventLines.clear()
                    }
                    inEvent -> eventLines += line
                }
            }
        }

        return IcsCalendarFeed(
            displayName = displayName?.takeIf { it.isNotBlank() },
            events = events,
        )
    }

    private fun parseEvent(lines: List<String>): IcsCalendarEvent? {
        val properties = lines.mapNotNull(::parseProperty)
        val uid = properties.firstValue("UID")
            ?: properties.firstValue("URL")
            ?: return null
        val start = properties.firstProperty("DTSTART")?.toEventDateTime() ?: return null
        val end = properties.firstProperty("DTEND")?.toEventDateTime()
        val recurrenceId = properties.firstProperty("RECURRENCE-ID")?.rawIdentityValue()
        val duration = properties.firstValue("DURATION")?.parseIcsDuration()
        val title = properties.firstValue("SUMMARY")
            ?.ifBlank { null }
            ?: "Untitled"

        return IcsCalendarEvent(
            uid = uid,
            recurrenceId = recurrenceId,
            title = title,
            description = properties.firstValue("DESCRIPTION")?.ifBlank { null },
            location = properties.firstValue("LOCATION")?.ifBlank { null },
            start = start,
            end = end,
            duration = duration,
            recurrenceRule = properties.firstRawValue("RRULE"),
            recurrenceDates = properties.allRawValues("RDATE").joinToString(",").ifBlank { null },
            exceptionRule = properties.firstRawValue("EXRULE"),
            exceptionDates = properties.allRawValues("EXDATE").joinToString(",").ifBlank { null },
            transparency = properties.firstRawValue("TRANSP"),
        )
    }

    private fun IcsProperty.toEventDateTime(): IcsEventDateTime? {
        val valueType = parameters["VALUE"]?.uppercase(Locale.US)
        if (valueType == "DATE" || !value.contains("T")) {
            val date = runCatching {
                LocalDate.parse(value, DateFormatter)
            }.getOrNull() ?: return null
            return IcsEventDateTime(
                epochMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                allDay = true,
                timeZone = ZoneOffset.UTC.id,
            )
        }

        val isUtc = value.endsWith("Z", ignoreCase = true)
        val dateTimeValue = if (isUtc) value.dropLast(1) else value
        val formatter = if (dateTimeValue.length == DateTimeWithSecondsLength) {
            DateTimeSecondsFormatter
        } else {
            DateTimeMinutesFormatter
        }
        val localDateTime = runCatching {
            LocalDateTime.parse(dateTimeValue, formatter)
        }.getOrNull() ?: return null
        val zone = if (isUtc) {
            ZoneOffset.UTC
        } else {
            parameters["TZID"]?.toZoneIdOrNull() ?: ZoneId.systemDefault()
        }

        return IcsEventDateTime(
            epochMillis = localDateTime.atZone(zone).toInstant().toEpochMilli(),
            allDay = false,
            timeZone = zone.id,
        )
    }

    private fun IcsProperty.decodedValue(): String = value.decodeIcsText()

    private fun IcsProperty.rawIdentityValue(): String = value
}

private data class IcsProperty(
    val name: String,
    val parameters: Map<String, String>,
    val value: String,
)

private fun List<String>.unfoldIcsLines(): List<String> {
    val unfolded = mutableListOf<String>()

    forEach { rawLine ->
        val line = rawLine.trimEnd('\r')
        if ((line.startsWith(" ") || line.startsWith("\t")) && unfolded.isNotEmpty()) {
            unfolded[unfolded.lastIndex] += line.drop(1)
        } else {
            unfolded += line
        }
    }

    return unfolded
}

private fun parseProperty(line: String): IcsProperty? {
    val separatorIndex = line.indexOfUnquoted(':')
    if (separatorIndex <= 0) return null

    val header = line.substring(0, separatorIndex)
    val value = line.substring(separatorIndex + 1)
    val headerParts = header.splitUnquoted(';')
    val name = headerParts.firstOrNull()
        ?.uppercase(Locale.US)
        ?: return null
    val parameters = headerParts
        .drop(1)
        .mapNotNull { parameter ->
            val equalsIndex = parameter.indexOf('=')
            if (equalsIndex <= 0) return@mapNotNull null

            val key = parameter.substring(0, equalsIndex).uppercase(Locale.US)
            val parameterValue = parameter.substring(equalsIndex + 1).trim('"')
            key to parameterValue
        }
        .toMap()

    return IcsProperty(
        name = name,
        parameters = parameters,
        value = value,
    )
}

private fun String.indexOfUnquoted(target: Char): Int {
    var quoted = false
    forEachIndexed { index, char ->
        when (char) {
            '"' -> quoted = !quoted
            target -> if (!quoted) return index
        }
    }
    return -1
}

private fun String.splitUnquoted(separator: Char): List<String> {
    val parts = mutableListOf<String>()
    var quoted = false
    var start = 0

    forEachIndexed { index, char ->
        when (char) {
            '"' -> quoted = !quoted
            separator -> if (!quoted) {
                parts += substring(start, index)
                start = index + 1
            }
        }
    }

    parts += substring(start)
    return parts
}

private fun List<IcsProperty>.firstProperty(name: String): IcsProperty? {
    return firstOrNull { it.name == name }
}

private fun List<IcsProperty>.firstValue(name: String): String? {
    return firstProperty(name)?.value?.decodeIcsText()
}

private fun List<IcsProperty>.firstRawValue(name: String): String? {
    return firstProperty(name)?.value
}

private fun List<IcsProperty>.allRawValues(name: String): List<String> {
    return filter { it.name == name }.map { it.value }
}

private fun String.decodeIcsText(): String {
    val output = StringBuilder(length)
    var escaped = false

    forEach { char ->
        if (escaped) {
            output.append(
                when (char) {
                    'n', 'N' -> '\n'
                    ',', ';', '\\' -> char
                    else -> char
                },
            )
            escaped = false
        } else if (char == '\\') {
            escaped = true
        } else {
            output.append(char)
        }
    }

    if (escaped) output.append('\\')
    return output.toString()
}

private fun String.toZoneIdOrNull(): ZoneId? {
    return runCatching {
        ZoneId.of(trim('/'))
    }.getOrNull()
}

private fun String.parseIcsDuration(): Duration? {
    val match = DurationPattern.matchEntire(trim().uppercase(Locale.US)) ?: return null
    val sign = if (match.groupValues[1] == "-") -1 else 1
    val weeks = match.groupValues[2].toLongOrNull() ?: 0L
    val days = match.groupValues[3].toLongOrNull() ?: 0L
    val hours = match.groupValues[4].toLongOrNull() ?: 0L
    val minutes = match.groupValues[5].toLongOrNull() ?: 0L
    val seconds = match.groupValues[6].toLongOrNull() ?: 0L

    return Duration.ZERO
        .plusDays((weeks * 7L + days) * sign)
        .plusHours(hours * sign)
        .plusMinutes(minutes * sign)
        .plusSeconds(seconds * sign)
}

private val DateFormatter = DateTimeFormatter.BASIC_ISO_DATE
private val DateTimeSecondsFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
private val DateTimeMinutesFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm")
private const val DateTimeWithSecondsLength = 15
private val DurationPattern = Regex("""([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""")
