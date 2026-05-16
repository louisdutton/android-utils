# Locations

Shared location models for the GrapheneOS Essentials apps.

Calendar events continue to use Android's `CalendarContract.Events.EVENT_LOCATION`
field as the canonical local provider value. Maps can resolve that provider string
into a richer `MapLocation` with coordinates, saved-place metadata, or a search
result without Calendar depending on the Maps app directly.

Calendar opens Maps with a standard `ACTION_VIEW` `geo:0,0?q=<provider location>`
intent so the handoff still degrades to any map app. When targeting the suite Maps
package, Calendar also includes `digital.dutton.essentials.locations.extra.SOURCE`
with value `calendar` and the raw provider location in
`digital.dutton.essentials.locations.extra.RAW_PROVIDER_LOCATION`. Maps uses those
extras to treat the location as a query-only search instead of centering the search
viewport on `0,0`.
