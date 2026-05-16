# Maps

Maps owns map rendering, search, saved places, routing, and location resolution.
It shares location identity with the rest of the app suite through
`:core:locations`.

Calendar integrations should start from Android Calendar Provider event
location strings, then resolve those strings into `MapLocation` records when
the Maps app has coordinates or saved-place metadata.

The current implementation uses MapLibre Native Android with the public
OpenFreeMap Liberty style as a development map source. Service-facing pieces are
behind small interfaces so online tile, geocoding, and routing providers can be
replaced by self-hosted or offline implementations without changing the app UI.

- Rendering: MapLibre `MapView` hosted from Compose.
- Device location: Android framework `LocationManager`.
- Search: Android `Geocoder` resolver, treated as best effort.
- Saved places: local app storage.
- Calendar: `geo:` intents from Calendar event locations into Maps.
- Routing: route-preview contract in place; production routing backend pending.
