#!/usr/bin/env python3
"""Reject APK suites that bundle multiple versions of the same dependency."""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path
from zipfile import BadZipFile, ZipFile


EMPTY_CONFLICT_SHIMS = {
    "com.google.guava:listenablefuture": "9999.0-empty-to-avoid-conflict-with-guava"
}


def marker_for(coordinate: str) -> str:
    group, module = coordinate.split(":", 1)
    if group == "org.jetbrains.kotlinx" and module.startswith("kotlinx-coroutines-"):
        return f"kotlinx_coroutines_{module.removeprefix('kotlinx-coroutines-')}"
    return f"{group}_{module}"


def read_versions(apks: list[Path]) -> dict[str, dict[str, set[str]]]:
    dependencies: dict[str, dict[str, set[str]]] = defaultdict(lambda: defaultdict(set))
    for apk in apks:
        try:
            with ZipFile(apk) as archive:
                for entry in archive.namelist():
                    if not entry.startswith("META-INF/") or not entry.endswith(".version"):
                        continue
                    marker = entry.removeprefix("META-INF/").removesuffix(".version")
                    version = archive.read(entry).decode("utf-8").strip()
                    dependencies[marker][version].add(apk.stem)
        except BadZipFile as error:
            raise SystemExit(f"Invalid APK: {apk}: {error}") from error
    return dependencies


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk_paths", nargs="+", type=Path)
    parser.add_argument("--policy", required=True, type=Path)
    parser.add_argument("--reports-dir", type=Path)
    args = parser.parse_args()

    apks: list[Path] = []
    for path in args.apk_paths:
        if path.is_dir():
            apks.extend(path.glob("*.apk"))
        elif path.is_file():
            apks.append(path)
    apks = sorted(set(apks))
    if not apks:
        raise SystemExit("No APKs found")

    policy = json.loads(args.policy.read_text(encoding="utf-8"))
    dependencies = read_versions(apks)
    errors: list[str] = []

    if args.reports_dir is not None:
        resolved: dict[str, dict[str, set[str]]] = defaultdict(lambda: defaultdict(set))
        reports = sorted(args.reports_dir.glob("*.tsv"))
        expected_reports = {apk.stem for apk in apks if apk.stem.startswith("digital.dutton.")}
        available_reports = {report.stem for report in reports}
        for missing in sorted(expected_reports - available_reports):
            errors.append(f"missing resolved dependency report for {missing}")
        for report in reports:
            for line in report.read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                try:
                    coordinate, version = line.split("\t", 1)
                except ValueError:
                    errors.append(f"invalid dependency report line in {report}: {line}")
                    continue
                resolved[coordinate][version].add(report.stem)
        for coordinate, versions in sorted(resolved.items()):
            implementation_versions = {
                version: apps
                for version, apps in versions.items()
                if version != EMPTY_CONFLICT_SHIMS.get(coordinate)
            }
            if len(implementation_versions) > 1:
                details = "; ".join(
                    f"{version}: {', '.join(sorted(apps))}"
                    for version, apps in sorted(implementation_versions.items())
                )
                errors.append(f"resolved {coordinate}: {details}")
            required = policy.get(coordinate)
            if required is not None:
                for actual, apps in sorted(versions.items()):
                    if actual != required:
                        errors.append(
                            f"resolved {coordinate} must be {required}, found {actual}: "
                            f"{', '.join(sorted(apps))}"
                        )

    for marker, versions in sorted(dependencies.items()):
        if len(versions) > 1:
            details = "; ".join(
                f"{version}: {', '.join(sorted(apps))}"
                for version, apps in sorted(versions.items())
            )
            errors.append(f"{marker}: {details}")

    for coordinate, required_version in sorted(policy.items()):
        marker = marker_for(coordinate)
        for actual_version, apps in sorted(dependencies.get(marker, {}).items()):
            if actual_version != required_version:
                errors.append(
                    f"{coordinate} must be {required_version}, found {actual_version}: "
                    f"{', '.join(sorted(apps))}"
                )

    if errors:
        print("Dependency version policy violations:")
        for error in errors:
            print(f"  - {error}")
        raise SystemExit(1)

    print(f"Verified one dependency version across {len(apks)} APKs.")


if __name__ == "__main__":
    main()
