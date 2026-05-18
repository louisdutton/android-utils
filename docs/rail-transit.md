# Rail Transit

Maps should treat rail as an offline-first feature. The application should not
depend on a hosted routing service for normal train timetable or journey
planning, and the downloaded map data should eventually carry the rail transit
sections needed by the native routing engine.

## Findings

The inherited CoMaps code already contains a public transport pipeline:

- `apps/maps/libs/transit/world_feed/gtfs_converter` converts GTFS feeds into
  generator JSON.
- `apps/maps/generator/transit_generator_experimental.cpp` serializes that JSON
  into the `TRANSIT_FILE_TAG` section in generated `.mwm` files.
- Android already exposes a transit routing mode and transit route details UI.

The existing converter expects real GTFS shape data. Feeds without `shapes.txt`
are skipped before agency, route, trip, stop, or stop-time parsing. Any UK rail
source we use must therefore either provide shapes or pass through a
shape-generation step before the CoMaps converter sees it.

## Data Sources

Primary official rail sources are not shaped GTFS feeds:

- Network Rail `SCHEDULE` gives train schedules in JSON and CIF format. Access
  requires a Network Rail Open Data account.
- National Rail / RDG timetable and Darwin data are available through NRDP or
  Rail Data Marketplace registrations. These are free/open feeds for registered
  users, but not anonymous public zip URLs.
- NaPTAN is an anonymous public source for stop and station geography. For
  Cornwall (`atcoAreaCodes=080`), it currently returns 36 active National Rail
  station entrances plus four Bodmin & Wenford Railway entries.

The most practical no-auth GTFS source found during the spike is the Transitous
Great Britain feed:

- `https://api.transitous.org/gtfs/gb_great-britain.gtfs.zip`
- It is built from Aubin MaaS data, includes Department for Transport BODS,
  converted National Rail timetable information, and OSM-derived location data.
- It includes `shapes.txt`, so it matches the inherited CoMaps converter much
  better than raw Network Rail or National Rail timetable feeds.
- It is large: about 962 MB compressed for the processed feed. We should never
  bundle or vendor the whole feed. Use it as build-time input and filter it to
  the rail/Cornwall subset.

The Transitland UK rail feed also exists, but direct access requires an API
token. It is useful as a reference, not as the default dependency for a no-account
build path.

## Recommended Path

Start with the Transitous/Aubin Great Britain GTFS feed as a build-time source,
then create a deterministic filter stage that outputs a Cornwall rail GTFS
artifact into a generated-data directory outside the source tree.

The first filter should:

1. Keep rail routes only, starting with route type `2` and relevant extended rail
   route types.
2. Keep Cornwall National Rail station stops by parent station or coordinates.
3. Retain trips and stop times where the train serves at least one Cornwall rail
   station.
4. Retain the referenced calendars, calendar dates, shapes, agencies, transfers,
   and feed metadata.
5. Emit a small GTFS zip that can be fed into `gtfs_converter`.

Once that works, wire the generated transit JSON into our map generation path via
`--transit_path_experimental` and regenerate the relevant UK/Cornwall `.mwm`
files with transit sections enabled.

## Scheduled Timetable Updates

Scheduled rail data should be updated independently from OSM map data. Map
regions can tolerate a slower cadence, while rail timetables and planned
engineering changes need a faster refresh. Treat the timetable as a separate
downloadable data product, even if we later also compile some transit geometry
into `.mwm` files for native route planning.

The first production shape should be:

1. Build job fetches the public Great Britain static GTFS source.
2. Build job filters it to Cornwall rail services and stations.
3. Build job writes a compact schedule package, not the full GTFS feed, with:
   - station records keyed by GTFS stop id and `stop_code` where provided;
   - routes/operators;
   - service calendars and calendar exceptions;
   - trips and stop times for retained rail services;
   - feed validity range and attribution text.
4. Build job publishes a small JSON manifest plus gzip-compressed SQLite
   package files with byte-size and SHA-256 integrity metadata.
5. Android checks the manifest on a regular cadence and downloads updated
   timetable packages into app storage.
6. The app uses the downloaded schedule package for offline station boards.

The implemented first pass is:

- `scripts/build-cornwall-rail-schedule.py` streams the public Great Britain
  GTFS zip, filters rail services that serve Cornwall rail stops, and emits
  `gb-cornwall-rail.sqlite`, `gb-cornwall-rail.sqlite.gzip`, and
  `gb-cornwall-rail.manifest.json`.
- The source GTFS cache defaults to `/tmp/grapheneos-essentials-rail-cache`,
  and generated artifacts go only to the requested output directory.
- The Android app receives the manifest URL at build time with
  `-PrailScheduleManifestUrl=https://.../gb-cornwall-rail.manifest.json`.
- The same package can be bundled into the APK for a fully offline first run:
  build the schedule to a directory such as `/tmp/ge-rail-assets/rail-schedules`,
  using `--omit-sqlite`, then build Maps with
  `-PrailScheduleAssetDir=/tmp/ge-rail-assets`. The app installs
  `rail-schedules/gb-cornwall-rail.manifest.json` and the referenced gzip
  package from assets when present.
- `RailScheduleUpdateWork` checks once per day when networking is available.
  It downloads only when the manifest package hash differs from the installed
  package, verifies the byte count and SHA-256, validates the SQLite metadata,
  and swaps the database into place with rollback.
- `RailScheduleStore` opens the installed package read-only and answers station
  board queries from local calendar, exception, trip, and stop-time tables.
- Rail station place pages in the Cornwall region match the selected map feature
  to the nearest retained GTFS station and show the next offline departures.

## Local Build Scripts

Use the wrapper scripts for normal local work instead of retyping the Python and
Gradle commands.

Build or refresh the Android asset bundle only:

```sh
./scripts/build-cornwall-rail-assets.sh
```

That writes generated files outside the repository by default:

- `/tmp/grapheneos-essentials-rail-assets/rail-schedules/gb-cornwall-rail.manifest.json`
- `/tmp/grapheneos-essentials-rail-assets/rail-schedules/gb-cornwall-rail.sqlite.gzip`

Build the Maps debug APK with those rail assets bundled:

```sh
./scripts/build-maps-android-with-rail.sh
```

With no arguments, the Maps wrapper builds the fdroid arm64 debug APK. Any
arguments are passed through to the Maps Gradle wrapper, so a narrower compile
check is:

```sh
./scripts/build-maps-android-with-rail.sh :app:compileFdroidDebugJavaWithJavac -Parm64
```

Useful environment overrides:

- `RAIL_SCHEDULE_ASSET_ROOT=/tmp/somewhere` changes the Android asset root.
  Generated files are written below its `rail-schedules` directory so the APK
  asset paths stay stable.
- `RAIL_SCHEDULE_SOURCE_ZIP=/path/to/feed.gtfs.zip` uses an existing GTFS zip
  instead of downloading the public source.
- `RAIL_SCHEDULE_SOURCE_URL=https://.../feed.gtfs.zip` changes the source URL.
- `RAIL_SCHEDULE_CACHE_DIR=/tmp/somewhere` changes the source download cache.
- `RAIL_SCHEDULE_SKIP_BUILD=1` makes the Maps wrapper reuse an existing asset
  bundle.
- `RAIL_SCHEDULE_EMIT_FILTERED_GTFS=1` also emits the filtered GTFS zip for
  inspection.

The default source is the full public Great Britain feed, so the first real run
is intentionally heavier than a normal app build. Day-to-day Android iteration
can use `RAIL_SCHEDULE_SKIP_BUILD=1` once the local asset bundle exists.

The Nix development shell sets the default asset root and source cache paths, so
normal local use should not need either path variable:

```sh
nix develop --no-write-lock-file --command ./scripts/build-cornwall-rail-assets.sh
nix develop --no-write-lock-file --command ./scripts/build-maps-android-with-rail.sh
```

Package signing is still a separate hardening step. The current mechanism gives
us reproducible packages and transport/integrity checks without committing to
key storage before the publishing location is chosen.

This should not initially depend on the inherited CoMaps `TRANSIT_FILE_TAG`
section. That section is tied to generated `.mwm` files and currently feeds the
native routing/rendering graph. It is useful later, but it is the wrong first
place to solve station departure boards because:

- timetable refresh cadence is faster than regional map refresh cadence;
- route planning needs map-matched geometry, while station boards mostly need
  calendar and stop-time queries;
- the current transit router still treats waiting time mostly as headway-based
  frequency rather than exact train departure selection;
- separating the timetable package lets us update rail data without forcing a
  map redownload.

The app-side store should be query-oriented. A compact SQLite database is the
most pragmatic first format because Android can query it directly without custom
native readers. For each station board query, the app can select active services
for the requested date, apply calendar exceptions, and list the next departures
from `stop_times`. Later realtime updates can overlay the same trip/service
records without changing the offline package format.

The first update policy should be simple and conservative:

- check for a new manifest at most once per day on unmetered or normal network;
- keep the last valid package if update fails;
- show package validity in diagnostics;
- warn or degrade when the timetable validity window has expired;
- never write downloaded source GTFS archives into the repository.

This gives us useful offline train boards before we solve full multimodal rail
routing. Once the schedule package is stable, we can decide whether to generate
CoMaps transit sections from the same filtered feed for map rendering and
route-planning experiments.

## Longer-Term Official Path

The official-data path is still worth owning, but it is a larger converter:

1. Fetch Network Rail `SCHEDULE` or National Rail/RDG timetable data with a
   registered account.
2. Map TIPLOC/CRS/station identifiers to station coordinates using NaPTAN,
   KnowledgeBase stations, or CORPUS/reference data.
3. Convert timetable records into GTFS `agency`, `routes`, `trips`,
   `stop_times`, `calendar`, and `calendar_dates`.
4. Generate or map-match `shapes.txt` from OSM railway geometry.
5. Feed the generated GTFS into the same CoMaps transit converter.

That path gives us stronger source provenance and avoids relying on a third-party
aggregated GTFS, but it is not the fastest route to getting Cornwall train
routing working inside the app.

## Attribution

Any production use must surface required attributions in-app and in generated map
metadata. The candidate Transitous Great Britain feed includes National Rail
Enquiries powered-by requirements and OSM ODbL-derived location data. That is
compatible with the current Maps direction, but it must not be hidden.
