# Maps Features

## Current Direction

Maps should work well without a network connection. Offline map viewing,
offline search, and offline routing are primary product goals, not premium or
advanced modes.

The app should still work for most users out of the box. It should prefer
native Android framework capabilities, bundled/downloaded open map data, and
public open map services before expecting anyone to provide self-hosted
infrastructure.

Hosted services can be useful during development or as optional fallback paths,
but the main Maps experience must not depend on an account, API key, or
always-available server.

## Geocoding

The current geocoding implementation uses Android `Geocoder`. Keep that as the
default path for now.

Additional geocoding providers can be considered later if the native provider
does not give enough coverage, quality, or place-search behavior for a
replacement Maps app. Any future provider should be optional and replaceable,
not a requirement for normal users.

Candidate future options include public open geocoding services, bundled data,
or self-hosted Nominatim, Photon, or Pelias deployments.

## Offline Routing

Offline routing is the target architecture. The app should eventually calculate
routes on-device from downloaded map/routing data for normal walking, cycling,
and driving use cases.

The current route-preview implementation is only a placeholder. Hosted routing
engines such as openrouteservice, OSRM, Valhalla, or GraphHopper can be useful
for development comparisons, but they should not become the only production
routing path.

The current app may use the public FOSSGIS OSRM service at
`routing.openstreetmap.de` as a low-volume development fallback. That service is
not a production dependency: it requires attribution, a valid user agent, at
most one request per second, and no scraping or heavy usage. Route requests sent
there are calculated server-side and therefore disclose route endpoints to that
server.

The next implementation spike should compare:

- Reusing or adapting the CoMaps/Organic Maps offline routing stack.
- Embedding an Android-capable offline routing engine such as GraphHopper.
- Using hosted routing only as an optional fallback while offline routing is
  unavailable for a region.

Evaluation criteria:

- Works offline after regional map download.
- Supports walking, cycling, and driving profiles.
- Provides route geometry, distance, duration, and turn instructions.
- Fits Android storage, memory, and battery constraints.
- Uses open data and licenses compatible with the app suite.
- Can share downloaded region data with offline map rendering/search where
  practical.

## CoMaps / Organic Maps Routing Findings

CoMaps is offline-first because its downloaded regional `.mwm` files contain
the data used for rendering, search, and routing. Its Android routing layer is a
thin JNI wrapper over the native C++ core: the UI selects a routing profile,
sets route points, and calls into `Framework` / `RoutingManager`.

The actual router is built around native `DataSource` / `MwmSet` storage and an
`IndexRouter` that runs local graph search over generated routing sections. The
map generator writes those routing sections, cross-region transitions, and
cross-region weights into the `.mwm` files. Missing regions are surfaced as a
`NeedMoreMaps` routing result, which the app turns into a prompt to download
the maps along the route.

This means `libs/routing` should not be treated as a small drop-in routing
library for the current MapLibre implementation. The realistic choices are:

- Adopt or fork more of the CoMaps native core, storage format, downloader, and
  generator pipeline.
- Keep the current MapLibre-based app and embed a separate offline routing
  engine, accepting that map rendering/search/routing data may need careful
  coordination or duplication.

Before implementing production routing UI, run a focused architecture spike
that compares those two paths on build complexity, APK/storage size, route
quality, Android integration effort, and long-term maintenance.

## Remaining Functional Areas

- Offline routing engine and route instructions.
- Offline map/routing region downloads and update management.
- Persistent saved places database.
- Calendar-to-Maps and Maps-to-Calendar location linking.
- MapLibre annotation/source-layer migration for markers and routes.
- No-network, no-location, and unavailable-service states.
