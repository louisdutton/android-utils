# Legacy Maps

This is the previous MapLibre-based Maps prototype. It is retained as
`apps/maps-legacy` for reference while the production Maps app moves to the
CoMaps-derived offline implementation in `apps/maps`.

Legacy Maps owns map rendering, search, saved places, routing, and location resolution.
It shares location identity with the rest of the app suite through
`:core:locations`.

Calendar integrations should start from Android Calendar Provider event
location strings, then resolve those strings into `MapLocation` records when
the Maps app has coordinates or saved-place metadata.

The current implementation uses MapLibre Native Android with the public
OpenFreeMap Liberty style as a development prototype. It exists to exercise
suite integration points, not as the long-term Maps architecture.

The product target is a CoMaps-derived offline Maps app with downloaded map
data, local search, local routing, and navigation. Hosted services are only
development or optional fallback paths.

- Rendering: MapLibre `MapView` hosted from Compose.
- Device location: Android framework `LocationManager`.
- Search: Android `Geocoder` resolver.
- Saved places: local app storage.
- Calendar: `geo:` intents from Calendar event locations into Maps.
- Routing: public OSRM development fallback with a direct-line fallback in the
  prototype; CoMaps-derived offline routing is the production target.

Future Maps work is tracked in [FEATURES.md](FEATURES.md).
