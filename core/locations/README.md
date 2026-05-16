# Locations

Shared location models for the GrapheneOS Essentials apps.

Calendar events continue to use Android's `CalendarContract.Events.EVENT_LOCATION`
field as the canonical local provider value. Maps can resolve that provider string
into a richer `MapLocation` with coordinates, saved-place metadata, or a search
result without Calendar depending on the Maps app directly.
