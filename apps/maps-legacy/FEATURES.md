# Maps Features

## Current Direction

Maps should work well without a network connection. Offline map viewing,
offline search, and offline routing are primary product goals, not premium or
advanced modes.

The MapLibre implementation in this repository is a prototype only. It proves
suite integration points such as `geo:` intents, Calendar location handoff,
saved places, Android location permissions, and route UI plumbing, but it is
not the product architecture for a serious Maps replacement.

The product direction is now a CoMaps-derived Maps app. CoMaps already provides
the offline map storage, regional downloads, rendering, search, routing,
navigation, bookmarks, tracks, and map update machinery that this app needs.
The work here should focus on adapting that mature stack into the GrapheneOS
Essentials suite instead of reimplementing the full offline maps platform from
scratch.

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
for development comparisons, but they should not become the production routing
path.

The current app may use the public FOSSGIS OSRM service at
`routing.openstreetmap.de` as a low-volume development fallback. That service is
not a production dependency: it requires attribution, a valid user agent, at
most one request per second, and no scraping or heavy usage. Route requests sent
there are calculated server-side and therefore disclose route endpoints to that
server.

The next implementation spike should focus on adapting CoMaps as the production
Maps base. GraphHopper-style routing remains useful as a comparison point only
if the CoMaps-derived path becomes technically or operationally blocked.

Evaluation criteria:

- Works offline after regional map download.
- Supports walking, cycling, and driving profiles.
- Provides route geometry, distance, duration, and turn instructions.
- Fits Android storage, memory, and battery constraints.
- Uses open data and licenses compatible with the app suite.
- Can share downloaded region data with offline map rendering/search where
  practical.

## CoMaps / Organic Maps Adoption

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
library for the current MapLibre implementation. The Maps app should instead be
based on a deliberate CoMaps fork/adaptation that keeps the native core,
storage format, downloader, generated map data, routing engine, and map UI
coherent.

CoMaps is Apache 2.0 licensed, so forking and modifying the application is
permitted if the license obligations are met. The fork must preserve applicable
license and notice files, carry notices for modified files, keep third-party
attributions intact, and avoid using upstream trademarks or branding as if this
were the CoMaps app. OpenStreetMap data attribution and any map-data download
terms must be handled separately from the application source license.

The adoption spike should answer:

- Can the Android app build reliably from source on macOS using this
  repository's Nix/Android setup?
- What is the cleanest upstream-tracking structure: separate fork repository,
  git subtree, or nested source tree under `apps/maps`?
- Which CoMaps services and URLs are needed for map metadata, downloads,
  updates, and support screens?
- Whether using CoMaps-hosted map downloads is acceptable for a derivative app,
  or whether we need our own map distribution pipeline later.
- How to rename the package, app label, icon, and branding to `Maps` without
  breaking the native core assumptions.
- How to preserve GrapheneOS Essentials integrations: Calendar `geo:` intents,
  shared location identity, native Material-style theming, and suite-level
  packaging.

## Remaining Functional Areas

- CoMaps-derived source import or upstream-tracking fork.
- Android/macOS build integration for the CoMaps-derived Maps app.
- GrapheneOS Essentials branding, package name, icon, and launcher integration.
- Map metadata/download/update strategy for downloaded `.mwm` regions.
- Persistent saved places database.
- Calendar-to-Maps and Maps-to-Calendar location linking.
- No-network, no-location, and unavailable-service states.
