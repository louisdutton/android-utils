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
