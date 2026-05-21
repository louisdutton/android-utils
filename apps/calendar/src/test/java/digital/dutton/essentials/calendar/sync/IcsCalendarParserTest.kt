package digital.dutton.essentials.calendar.sync

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsCalendarParserTest {
    private val parser = IcsCalendarParser()

    @Test
    fun parsesTimedEventWithFoldedDescription() {
        val feed = parser.parse(
            """
            BEGIN:VCALENDAR
            X-WR-CALNAME:Team Calendar
            BEGIN:VEVENT
            UID:event-1@example.com
            DTSTART:20260521T090000Z
            DTEND:20260521T100000Z
            SUMMARY:Planning
            DESCRIPTION:First line\nsecond
             folded line
            LOCATION:Meeting room
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        )

        assertEquals("Team Calendar", feed.displayName)
        assertEquals(1, feed.events.size)

        val event = feed.events.single()
        assertEquals("event-1@example.com", event.uid)
        assertEquals("Planning", event.title)
        assertEquals("First line\nsecondfolded line", event.description)
        assertEquals("Meeting room", event.location)
        assertFalse(event.start.allDay)
        assertEquals(Instant.parse("2026-05-21T09:00:00Z").toEpochMilli(), event.start.epochMillis)
        assertEquals(Instant.parse("2026-05-21T10:00:00Z").toEpochMilli(), event.end?.epochMillis)
    }

    @Test
    fun parsesAllDayRecurringEvent() {
        val feed = parser.parse(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:event-2@example.com
            DTSTART;VALUE=DATE:20260522
            DTEND;VALUE=DATE:20260523
            SUMMARY:Public holiday
            RRULE:FREQ=YEARLY
            TRANSP:TRANSPARENT
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        )

        val event = feed.events.single()
        assertTrue(event.start.allDay)
        assertEquals("Public holiday", event.title)
        assertEquals("FREQ=YEARLY", event.recurrenceRule)
        assertEquals("TRANSPARENT", event.transparency)
        assertNull(event.description)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonCalendarContent() {
        parser.parse("<html>Not a calendar</html>")
    }
}
