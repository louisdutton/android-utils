# Maps

Maps owns map rendering, search, saved places, routing, and location resolution.
It shares location identity with the rest of the app suite through
`:core:locations`.

Calendar integrations should start from Android Calendar Provider event
location strings, then resolve those strings into `MapLocation` records when
the Maps app has coordinates or saved-place metadata.

The current implementation uses MapLibre Native Android with the public
OpenFreeMap Liberty style as a development map source. The product target is an
offline-first Maps app with downloaded map data, search, and routing. Hosted
services are only development or optional fallback paths.

- Rendering: MapLibre `MapView` hosted from Compose.
- Device location: Android framework `LocationManager`.
- Search: Android `Geocoder` resolver.
- Saved places: local app storage.
- Calendar: `geo:` intents from Calendar event locations into Maps.
- Routing: public OSRM development fallback with a direct-line fallback; offline
  routing engine pending.

Future Maps work is tracked in [FEATURES.md](FEATURES.md).
