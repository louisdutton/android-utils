#!/usr/bin/env python3
"""Build a compact offline Cornwall rail schedule package from public GTFS.

The source feed is intentionally treated as build input, not repository content.
By default the script caches downloaded source archives under /tmp and writes
generated artifacts to an explicit output directory.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import gzip
import hashlib
import io
import json
import os
import shutil
import sqlite3
import tempfile
import urllib.request
import zipfile
from collections.abc import Iterable, Iterator
from pathlib import Path


DEFAULT_SOURCE_URL = "https://api.transitous.org/gtfs/gb_great-britain.gtfs.zip"
DEFAULT_CACHE_DIR = Path("/tmp/grapheneos-essentials-rail-cache")

REGION_ID = "gb-cornwall-rail"
SCHEMA_VERSION = 2
PACKAGE_VERSION = 1
MIN_LAT = 49.90
MAX_LAT = 50.95
MIN_LON = -5.85
MAX_LON = -4.00

RAIL_STOP_PREFIX = "910"
RAIL_ROUTE_TYPES = {
    "2",    # GTFS rail.
    "100",  # Extended railway service.
    "101",  # High speed rail.
    "102",  # Long distance trains.
    "103",  # Inter regional rail.
    "106",  # Regional rail.
    "107",  # Tourist railway.
    "109",  # Suburban railway.
}

COPY_OPTIONAL_FILES = {
    "feed_info.txt",
}

ATTRIBUTION = [
    "Contains public transport data from Transitous/Aubin MaaS.",
    "Contains National Rail timetable information where present in the source feed.",
    "Contains OpenStreetMap-derived location data under ODbL where present in the source feed.",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build an offline Cornwall rail schedule SQLite package from a GTFS zip.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--source-zip", type=Path, help="Existing GTFS zip to use as input.")
    parser.add_argument("--source-url", default=DEFAULT_SOURCE_URL, help="Public GTFS zip URL.")
    parser.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE_DIR, help="Download cache directory.")
    parser.add_argument("--output-dir", type=Path, required=True, help="Directory for generated artifacts.")
    parser.add_argument("--emit-filtered-gtfs", action="store_true", help="Also emit the filtered GTFS zip.")
    parser.add_argument("--omit-sqlite", action="store_true",
                        help="Delete the uncompressed SQLite after packaging; useful for Android asset roots.")
    parser.add_argument("--keep-temp", action="store_true", help="Keep temporary work directory for inspection.")
    return parser.parse_args()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def now_utc() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def download_source(url: str, cache_dir: Path) -> Path:
    cache_dir.mkdir(parents=True, exist_ok=True)
    name = url.rstrip("/").split("/")[-1] or "source.gtfs.zip"
    target = cache_dir / name
    if target.exists() and target.stat().st_size > 0:
        return target

    tmp = target.with_suffix(target.suffix + ".tmp")
    with urllib.request.urlopen(url) as response, tmp.open("wb") as out:
        shutil.copyfileobj(response, out, 1024 * 1024)
    tmp.replace(target)
    return target


def open_text(zf: zipfile.ZipFile, name: str) -> io.TextIOWrapper:
    return io.TextIOWrapper(zf.open(name), encoding="utf-8-sig", newline="")


def rows(zf: zipfile.ZipFile, name: str) -> Iterator[dict[str, str]]:
    if name not in zf.namelist():
        return
    with open_text(zf, name) as handle:
        yield from csv.DictReader(handle)


def write_csv(path: Path, fieldnames: list[str], items: Iterable[dict[str, str]]) -> int:
    count = 0
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore", lineterminator="\n")
        writer.writeheader()
        for item in items:
            writer.writerow(item)
            count += 1
    return count


def read_fieldnames(zf: zipfile.ZipFile, name: str) -> list[str]:
    with open_text(zf, name) as handle:
        reader = csv.reader(handle)
        return next(reader)


def in_cornwall(lat_text: str, lon_text: str) -> bool:
    try:
        lat = float(lat_text)
        lon = float(lon_text)
    except ValueError:
        return False
    return MIN_LAT <= lat <= MAX_LAT and MIN_LON <= lon <= MAX_LON


def is_cornwall_rail_stop(row: dict[str, str]) -> bool:
    stop_id = row.get("stop_id", "")
    return stop_id.startswith(RAIL_STOP_PREFIX) and in_cornwall(row.get("stop_lat", ""), row.get("stop_lon", ""))


def date_range(calendar_rows: Iterable[dict[str, str]], calendar_date_rows: Iterable[dict[str, str]]) -> tuple[str, str]:
    dates: list[str] = []
    for row in calendar_rows:
        if row.get("start_date"):
            dates.append(row["start_date"])
        if row.get("end_date"):
            dates.append(row["end_date"])
    for row in calendar_date_rows:
        if row.get("date"):
            dates.append(row["date"])
    return (min(dates), max(dates)) if dates else ("", "")


def seconds_since_midnight(time_text: str) -> int | None:
    if not time_text:
        return None
    parts = time_text.split(":")
    if len(parts) != 3:
        return None
    try:
        hours, minutes, seconds = (int(part) for part in parts)
    except ValueError:
        return None
    return hours * 3600 + minutes * 60 + seconds


class FilterResult:
    def __init__(self) -> None:
        self.station_ids: set[str] = set()
        self.target_stop_ids: set[str] = set()
        self.matched_trip_ids: set[str] = set()
        self.route_ids: set[str] = set()
        self.service_ids: set[str] = set()
        self.shape_ids: set[str] = set()
        self.stop_ids: set[str] = set()
        self.agency_ids: set[str] = set()
        self.counts: dict[str, int] = {}
        self.valid_from = ""
        self.valid_until = ""


def filter_feed(source_zip: Path, work_dir: Path) -> FilterResult:
    result = FilterResult()
    gtfs_dir = work_dir / "gtfs"
    gtfs_dir.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(source_zip) as zf:
        stop_fields = read_fieldnames(zf, "stops.txt")
        route_fields = read_fieldnames(zf, "routes.txt")
        trip_fields = read_fieldnames(zf, "trips.txt")
        stop_time_fields = read_fieldnames(zf, "stop_times.txt")

        stops_by_id: dict[str, dict[str, str]] = {}
        children_by_parent: dict[str, set[str]] = {}
        for row in rows(zf, "stops.txt"):
            stop_id = row["stop_id"]
            stops_by_id[stop_id] = row
            parent = row.get("parent_station", "")
            if parent:
                children_by_parent.setdefault(parent, set()).add(stop_id)
            if is_cornwall_rail_stop(row):
                result.station_ids.add(stop_id)
                result.target_stop_ids.add(stop_id)

        for stop_id in list(result.station_ids):
            parent = stops_by_id.get(stop_id, {}).get("parent_station", "")
            if parent:
                result.station_ids.add(parent)
                result.target_stop_ids.add(parent)
            result.target_stop_ids.update(children_by_parent.get(stop_id, set()))

        rail_routes: dict[str, dict[str, str]] = {}
        for row in rows(zf, "routes.txt"):
            if row.get("route_type", "") in RAIL_ROUTE_TYPES:
                rail_routes[row["route_id"]] = row

        rail_trips: dict[str, dict[str, str]] = {}
        for row in rows(zf, "trips.txt"):
            if row.get("route_id") in rail_routes:
                rail_trips[row["trip_id"]] = row

        for row in rows(zf, "stop_times.txt"):
            trip_id = row.get("trip_id", "")
            if trip_id in rail_trips and row.get("stop_id", "") in result.target_stop_ids:
                result.matched_trip_ids.add(trip_id)

        matched_trips = {trip_id: rail_trips[trip_id] for trip_id in result.matched_trip_ids}
        result.route_ids = {row["route_id"] for row in matched_trips.values()}
        result.service_ids = {row["service_id"] for row in matched_trips.values()}
        result.shape_ids = {row.get("shape_id", "") for row in matched_trips.values() if row.get("shape_id", "")}

        retained_routes = {route_id: rail_routes[route_id] for route_id in result.route_ids}
        result.agency_ids = {row.get("agency_id", "") for row in retained_routes.values()}

        result.counts["routes"] = write_csv(gtfs_dir / "routes.txt", route_fields, retained_routes.values())
        result.counts["trips"] = write_csv(gtfs_dir / "trips.txt", trip_fields, matched_trips.values())

        stop_times_path = gtfs_dir / "stop_times.txt"
        result.counts["stop_times"] = 0
        with stop_times_path.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=stop_time_fields, extrasaction="ignore", lineterminator="\n")
            writer.writeheader()
            for row in rows(zf, "stop_times.txt"):
                if row.get("trip_id", "") in result.matched_trip_ids:
                    result.stop_ids.add(row.get("stop_id", ""))
                    writer.writerow(row)
                    result.counts["stop_times"] += 1

        result.stop_ids.update(result.station_ids)
        for stop_id in list(result.stop_ids):
            parent = stops_by_id.get(stop_id, {}).get("parent_station", "")
            if parent:
                result.stop_ids.add(parent)
            result.stop_ids.update(children_by_parent.get(stop_id, set()))

        retained_stops = [stops_by_id[stop_id] for stop_id in result.stop_ids if stop_id in stops_by_id]
        result.counts["stops"] = write_csv(gtfs_dir / "stops.txt", stop_fields, retained_stops)

        if "agency.txt" in zf.namelist():
            agency_fields = read_fieldnames(zf, "agency.txt")
            agencies = [
                row for row in rows(zf, "agency.txt")
                if not result.agency_ids or row.get("agency_id", "") in result.agency_ids
            ]
            result.counts["agencies"] = write_csv(gtfs_dir / "agency.txt", agency_fields, agencies)

        if "calendar.txt" in zf.namelist():
            calendar_fields = read_fieldnames(zf, "calendar.txt")
            calendar_rows = [row for row in rows(zf, "calendar.txt") if row.get("service_id", "") in result.service_ids]
            result.counts["calendar"] = write_csv(gtfs_dir / "calendar.txt", calendar_fields, calendar_rows)
        else:
            calendar_rows = []

        if "calendar_dates.txt" in zf.namelist():
            calendar_date_fields = read_fieldnames(zf, "calendar_dates.txt")
            calendar_date_rows = [
                row for row in rows(zf, "calendar_dates.txt") if row.get("service_id", "") in result.service_ids
            ]
            result.counts["calendar_dates"] = write_csv(gtfs_dir / "calendar_dates.txt", calendar_date_fields, calendar_date_rows)
        else:
            calendar_date_rows = []

        result.valid_from, result.valid_until = date_range(calendar_rows, calendar_date_rows)

        if "shapes.txt" in zf.namelist() and result.shape_ids:
            shape_fields = read_fieldnames(zf, "shapes.txt")
            result.counts["shapes"] = write_csv(
                gtfs_dir / "shapes.txt",
                shape_fields,
                (row for row in rows(zf, "shapes.txt") if row.get("shape_id", "") in result.shape_ids),
            )

        if "transfers.txt" in zf.namelist():
            transfer_fields = read_fieldnames(zf, "transfers.txt")
            result.counts["transfers"] = write_csv(
                gtfs_dir / "transfers.txt",
                transfer_fields,
                (
                    row for row in rows(zf, "transfers.txt")
                    if row.get("from_stop_id", "") in result.stop_ids and row.get("to_stop_id", "") in result.stop_ids
                ),
            )

        if "frequencies.txt" in zf.namelist():
            frequency_fields = read_fieldnames(zf, "frequencies.txt")
            result.counts["frequencies"] = write_csv(
                gtfs_dir / "frequencies.txt",
                frequency_fields,
                (row for row in rows(zf, "frequencies.txt") if row.get("trip_id", "") in result.matched_trip_ids),
            )

        for name in COPY_OPTIONAL_FILES:
            if name in zf.namelist():
                fields = read_fieldnames(zf, name)
                result.counts[name.removesuffix(".txt")] = write_csv(gtfs_dir / name, fields, rows(zf, name))

    return result


def create_filtered_gtfs(work_dir: Path, output_path: Path) -> None:
    gtfs_dir = work_dir / "gtfs"
    with zipfile.ZipFile(output_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for file in sorted(gtfs_dir.glob("*.txt")):
            out.write(file, file.name)


def create_sqlite(work_dir: Path, sqlite_path: Path, result: FilterResult, source_url: str, source_sha256: str) -> None:
    if sqlite_path.exists():
        sqlite_path.unlink()
    db = sqlite3.connect(sqlite_path)
    try:
        db.executescript(
            """
            PRAGMA journal_mode = OFF;
            PRAGMA synchronous = OFF;
            CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE agencies(
              agency_id TEXT PRIMARY KEY,
              agency_name TEXT NOT NULL,
              agency_url TEXT,
              agency_timezone TEXT,
              agency_lang TEXT
            );
            CREATE TABLE stations(
              stop_id TEXT PRIMARY KEY,
              stop_code TEXT,
              stop_name TEXT NOT NULL,
              stop_lat REAL NOT NULL,
              stop_lon REAL NOT NULL,
              location_type INTEGER,
              parent_station TEXT,
              is_region_station INTEGER NOT NULL
            );
            CREATE TABLE routes(
              route_id TEXT PRIMARY KEY,
              agency_id TEXT,
              route_short_name TEXT,
              route_long_name TEXT,
              route_type INTEGER NOT NULL,
              route_color TEXT,
              route_text_color TEXT
            );
            CREATE TABLE trips(
              trip_id TEXT PRIMARY KEY,
              route_id TEXT NOT NULL,
              service_id TEXT NOT NULL,
              trip_headsign TEXT,
              trip_short_name TEXT,
              direction_id INTEGER,
              shape_id TEXT
            );
            CREATE TABLE stop_times(
              trip_id TEXT NOT NULL,
              arrival_time TEXT,
              departure_time TEXT,
              arrival_seconds INTEGER,
              departure_seconds INTEGER,
              stop_id TEXT NOT NULL,
              stop_sequence INTEGER NOT NULL,
              stop_headsign TEXT,
              pickup_type INTEGER,
              drop_off_type INTEGER,
              PRIMARY KEY(trip_id, stop_sequence)
            );
            CREATE TABLE calendar(
              service_id TEXT PRIMARY KEY,
              monday INTEGER NOT NULL,
              tuesday INTEGER NOT NULL,
              wednesday INTEGER NOT NULL,
              thursday INTEGER NOT NULL,
              friday INTEGER NOT NULL,
              saturday INTEGER NOT NULL,
              sunday INTEGER NOT NULL,
              start_date TEXT NOT NULL,
              end_date TEXT NOT NULL
            );
            CREATE TABLE calendar_dates(
              service_id TEXT NOT NULL,
              date TEXT NOT NULL,
              exception_type INTEGER NOT NULL,
              PRIMARY KEY(service_id, date)
            );
            CREATE INDEX idx_stations_parent ON stations(parent_station);
            CREATE INDEX idx_stations_region_location ON stations(is_region_station, stop_lat, stop_lon);
            CREATE INDEX idx_trips_service ON trips(service_id);
            CREATE INDEX idx_trips_route ON trips(route_id);
            CREATE INDEX idx_stop_times_stop_departure ON stop_times(stop_id, departure_seconds);
            CREATE INDEX idx_calendar_dates_date ON calendar_dates(date);
            """
        )

        gtfs_dir = work_dir / "gtfs"

        def insert_csv(name: str, sql: str, mapper) -> int:
            path = gtfs_dir / name
            if not path.exists():
                return 0
            count = 0
            with path.open("r", encoding="utf-8", newline="") as handle:
                for row in csv.DictReader(handle):
                    db.execute(sql, mapper(row))
                    count += 1
            return count

        insert_csv(
            "agency.txt",
            "INSERT OR REPLACE INTO agencies VALUES (?, ?, ?, ?, ?)",
            lambda r: (
                r.get("agency_id", ""),
                r.get("agency_name", ""),
                r.get("agency_url", ""),
                r.get("agency_timezone", ""),
                r.get("agency_lang", ""),
            ),
        )
        insert_csv(
            "stops.txt",
            "INSERT OR REPLACE INTO stations VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            lambda r: (
                r["stop_id"],
                r.get("stop_code", ""),
                r.get("stop_name", ""),
                float(r.get("stop_lat") or 0),
                float(r.get("stop_lon") or 0),
                int(r.get("location_type") or 0),
                r.get("parent_station", ""),
                1 if r["stop_id"] in result.station_ids else 0,
            ),
        )
        insert_csv(
            "routes.txt",
            "INSERT OR REPLACE INTO routes VALUES (?, ?, ?, ?, ?, ?, ?)",
            lambda r: (
                r["route_id"],
                r.get("agency_id", ""),
                r.get("route_short_name", ""),
                r.get("route_long_name", ""),
                int(r.get("route_type") or 0),
                r.get("route_color", ""),
                r.get("route_text_color", ""),
            ),
        )
        insert_csv(
            "trips.txt",
            "INSERT OR REPLACE INTO trips VALUES (?, ?, ?, ?, ?, ?, ?)",
            lambda r: (
                r["trip_id"],
                r["route_id"],
                r["service_id"],
                r.get("trip_headsign", ""),
                r.get("trip_short_name", ""),
                int(r["direction_id"]) if r.get("direction_id") not in (None, "") else None,
                r.get("shape_id", ""),
            ),
        )
        insert_csv(
            "stop_times.txt",
            "INSERT OR REPLACE INTO stop_times VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            lambda r: (
                r["trip_id"],
                r.get("arrival_time", ""),
                r.get("departure_time", ""),
                seconds_since_midnight(r.get("arrival_time", "")),
                seconds_since_midnight(r.get("departure_time", "")),
                r["stop_id"],
                int(r["stop_sequence"]),
                r.get("stop_headsign", ""),
                int(r["pickup_type"]) if r.get("pickup_type") not in (None, "") else None,
                int(r["drop_off_type"]) if r.get("drop_off_type") not in (None, "") else None,
            ),
        )
        insert_csv(
            "calendar.txt",
            "INSERT OR REPLACE INTO calendar VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            lambda r: (
                r["service_id"],
                int(r.get("monday") or 0),
                int(r.get("tuesday") or 0),
                int(r.get("wednesday") or 0),
                int(r.get("thursday") or 0),
                int(r.get("friday") or 0),
                int(r.get("saturday") or 0),
                int(r.get("sunday") or 0),
                r.get("start_date", ""),
                r.get("end_date", ""),
            ),
        )
        insert_csv(
            "calendar_dates.txt",
            "INSERT OR REPLACE INTO calendar_dates VALUES (?, ?, ?)",
            lambda r: (r["service_id"], r["date"], int(r["exception_type"])),
        )

        metadata = {
            "schema_version": str(SCHEMA_VERSION),
            "package_version": str(PACKAGE_VERSION),
            "region_id": REGION_ID,
            "source_url": source_url,
            "source_sha256": source_sha256,
            "generated_at": now_utc(),
            "valid_from": result.valid_from,
            "valid_until": result.valid_until,
            "attribution": json.dumps(ATTRIBUTION),
        }
        db.executemany("INSERT INTO metadata(key, value) VALUES (?, ?)", sorted(metadata.items()))
        db.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
        db.commit()
        db.execute("VACUUM")
        db.commit()
    finally:
        db.close()


def gzip_file(source: Path, target: Path) -> None:
    with source.open("rb") as src, target.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as dst:
            shutil.copyfileobj(src, dst, 1024 * 1024)


def write_manifest(output_dir: Path, sqlite_gz: Path, result: FilterResult, source_url: str, source_sha256: str) -> Path:
    manifest = {
        "schema_version": SCHEMA_VERSION,
        "package_version": PACKAGE_VERSION,
        "region_id": REGION_ID,
        "generated_at": now_utc(),
        "valid_from": result.valid_from,
        "valid_until": result.valid_until,
        "source": {
            "url": source_url,
            "sha256": source_sha256,
        },
        "package": {
            "file": sqlite_gz.name,
            "compression": "gzip",
            "bytes": sqlite_gz.stat().st_size,
            "sha256": sha256_file(sqlite_gz),
        },
        "counts": result.counts,
        "attribution": ATTRIBUTION,
    }
    manifest_path = output_dir / f"{REGION_ID}.manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest_path


def main() -> None:
    args = parse_args()
    source_zip = args.source_zip if args.source_zip else download_source(args.source_url, args.cache_dir)
    source_zip = source_zip.resolve()
    source_sha256 = sha256_file(source_zip)
    args.output_dir.mkdir(parents=True, exist_ok=True)

    work_parent = Path(tempfile.mkdtemp(prefix="ge-rail-schedule.", dir="/tmp"))
    try:
        result = filter_feed(source_zip, work_parent)

        sqlite_path = args.output_dir / f"{REGION_ID}.sqlite"
        sqlite_gz_path = sqlite_path.with_name(sqlite_path.name + ".gzip")
        create_sqlite(work_parent, sqlite_path, result, args.source_url, source_sha256)
        gzip_file(sqlite_path, sqlite_gz_path)

        if args.emit_filtered_gtfs:
            create_filtered_gtfs(work_parent, args.output_dir / f"{REGION_ID}.gtfs.zip")

        manifest_path = write_manifest(args.output_dir, sqlite_gz_path, result, args.source_url, source_sha256)
        if args.omit_sqlite:
            sqlite_path.unlink()
        print(json.dumps({
            "manifest": str(manifest_path),
            "package": str(sqlite_gz_path),
            "valid_from": result.valid_from,
            "valid_until": result.valid_until,
            "counts": result.counts,
        }, indent=2, sort_keys=True))
    finally:
        if args.keep_temp:
            print(f"kept temp directory: {work_parent}")
        else:
            shutil.rmtree(work_parent)


if __name__ == "__main__":
    main()
