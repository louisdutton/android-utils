# Calendar

Calendar uses Android's Calendar Provider as the canonical local store. The app
keeps provider access behind typed repository boundaries so UI, CalDAV sync, and
file import/export do not depend directly on `ContentResolver` or provider
column names.

Planned data flow:

```text
Calendar Provider
  -> CalendarRepository
  -> Calendar UI models
  -> Compose screens
```

CalDAV sync and iCalendar import/export should write through the same provider
boundary unless there is a specific reason to bypass system calendar storage.

Public read-only calendars are supported as ICS subscriptions from `https://`,
`webcal://`, and `webcals://` URLs. The app creates one read-only Android
Calendar Provider calendar per subscription, keeps remote event identity in
provider sync columns, and refreshes feeds with WorkManager using `ETag` /
`Last-Modified` when the server provides them.

CalDAV accounts are supported as writable server-backed calendars. The app
discovers calendars from an HTTPS CalDAV server URL, stores events offline in
Android's Calendar Provider, syncs remote changes with WorkManager, and pushes
local create/update/delete changes back to the server using CalDAV `PUT` /
`DELETE` with entity tags when available.
