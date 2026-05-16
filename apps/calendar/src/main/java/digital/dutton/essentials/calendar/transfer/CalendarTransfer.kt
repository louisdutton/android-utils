package digital.dutton.essentials.calendar.transfer

import android.net.Uri

interface CalendarImporter {
    suspend fun importFrom(uri: Uri): CalendarImportResult
}

interface CalendarExporter {
    suspend fun exportTo(uri: Uri): CalendarExportResult
}

data class CalendarImportResult(
    val calendarsImported: Int,
    val eventsImported: Int,
    val skipped: Int,
)

data class CalendarExportResult(
    val calendarsExported: Int,
    val eventsExported: Int,
)
