#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() {
  cat <<'USAGE'
Usage: scripts/scores-local-import.sh [options] <pdf-or-image>

Runs a local score-import loop on this machine:
  1. rasterizes PDFs to page PNGs with pdftoppm
  2. runs HOMR locally through uvx
  3. renders resulting MusicXML to SVG with host Verovio
  4. writes XML diagnostics for empty measures and lyrics

Options:
  --out <dir>       Output directory. Default: /tmp/scores-local-import-<timestamp>
  --dpi <number>    PDF rasterization DPI. Default: 300
  --debug           Pass --debug to HOMR.
  --no-cache        Do not pass --cache to HOMR.
  --no-render       Skip Verovio SVG rendering.
  -h, --help        Show this help.

Environment:
  SCORES_LOCAL_HOMR_PACKAGE  uv package spec for HOMR. Default: homr
  SCORES_VEROVIO_HOST_DIR    host Verovio build cache. Default: /tmp/grapheneos-essentials-scores-verovio-host
USAGE
}

if [[ -z "${SCORES_LOCAL_IN_NIX:-}" ]]; then
  missing=()
  for command in uvx pdftoppm cmake ninja; do
    if ! command -v "$command" >/dev/null 2>&1; then
      missing+=("$command")
    fi
  done
  if [[ "${#missing[@]}" -gt 0 || -z "${VEROVIO_SRC_DIR:-}" ]]; then
    exec nix develop .#suite --no-write-lock-file --command env SCORES_LOCAL_IN_NIX=1 "$0" "$@"
  fi
fi

out_dir=""
dpi="${SCORES_LOCAL_DPI:-300}"
homr_debug=0
homr_cache=1
render=1

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --out)
      out_dir="${2:-}"
      if [[ -z "$out_dir" ]]; then
        echo "--out requires a directory." >&2
        exit 2
      fi
      shift 2
      ;;
    --dpi)
      dpi="${2:-}"
      if [[ -z "$dpi" ]]; then
        echo "--dpi requires a value." >&2
        exit 2
      fi
      shift 2
      ;;
    --debug)
      homr_debug=1
      shift
      ;;
    --no-cache)
      homr_cache=0
      shift
      ;;
    --no-render)
      render=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      break
      ;;
  esac
done

if [[ "$#" -ne 1 ]]; then
  usage >&2
  exit 2
fi

input="$1"
if [[ ! -f "$input" ]]; then
  echo "Input file does not exist: $input" >&2
  exit 1
fi

timestamp="$(date +%Y%m%d-%H%M%S)"
out_dir="${out_dir:-/tmp/scores-local-import-$timestamp}"
pages_dir="$out_dir/pages"
xml_dir="$out_dir/musicxml"
svg_dir="$out_dir/svg"
log_dir="$out_dir/logs"
mkdir -p "$pages_dir" "$xml_dir" "$svg_dir" "$log_dir"

input_abs="$(cd "$(dirname "$input")" && pwd)/$(basename "$input")"
input_name="$(basename "$input")"
input_ext="${input_name##*.}"
input_ext="$(printf '%s' "$input_ext" | tr '[:upper:]' '[:lower:]')"

case "$input_ext" in
  pdf)
    pdftoppm -r "$dpi" -png "$input_abs" "$pages_dir/page"
    ;;
  png|jpg|jpeg|webp|tif|tiff|bmp)
    cp "$input_abs" "$pages_dir/page-1.$input_ext"
    ;;
  *)
    echo "Unsupported local import type: .$input_ext" >&2
    exit 1
    ;;
esac

mapfile -t page_images < <(find "$pages_dir" -maxdepth 1 -type f \( -name '*.png' -o -name '*.jpg' -o -name '*.jpeg' -o -name '*.webp' -o -name '*.tif' -o -name '*.tiff' -o -name '*.bmp' \) | sort)
if [[ "${#page_images[@]}" -eq 0 ]]; then
  echo "No page images were produced from $input_abs." >&2
  exit 1
fi

homr_package="${SCORES_LOCAL_HOMR_PACKAGE:-homr}"
homr_args=()
if [[ "$homr_cache" -eq 1 ]]; then
  homr_args+=(--cache)
fi
if [[ "$homr_debug" -eq 1 ]]; then
  homr_args+=(--debug)
fi

generated_xml=()
for page_image in "${page_images[@]}"; do
  page_base="$(basename "$page_image")"
  page_stem="${page_base%.*}"
  page_log="$log_dir/$page_stem.homr.log"
  before_marker="$log_dir/$page_stem.before"
  after_marker="$log_dir/$page_stem.after"
  find "$pages_dir" -maxdepth 1 -type f -name '*.musicxml' -print | sort > "$before_marker"

  (
    cd "$pages_dir"
    uvx --from "$homr_package" homr "${homr_args[@]}" "$page_base"
  ) >"$page_log" 2>&1

  find "$pages_dir" -maxdepth 1 -type f -name '*.musicxml' -print | sort > "$after_marker"
  mapfile -t new_xml < <(comm -13 "$before_marker" "$after_marker")
  if [[ "${#new_xml[@]}" -eq 0 && -f "$pages_dir/$page_stem.musicxml" ]]; then
    new_xml=("$pages_dir/$page_stem.musicxml")
  fi
  if [[ "${#new_xml[@]}" -eq 0 ]]; then
    echo "HOMR did not produce MusicXML for $page_base. See $page_log." >&2
    exit 1
  fi

  for xml in "${new_xml[@]}"; do
    target="$xml_dir/$(basename "$xml")"
    cp "$xml" "$target"
    generated_xml+=("$target")
  done
done

if [[ "$render" -eq 1 ]]; then
  verovio_bin="$("$repo_root/scripts/build-scores-verovio-host.sh")"
  resource_path="$(cd "$(dirname "$verovio_bin")/../share/verovio" && pwd)"
  for xml in "${generated_xml[@]}"; do
    stem="$(basename "${xml%.*}")"
    "$verovio_bin" \
      --input-from musicxml \
      --output-to svg \
      --all-pages \
      --resource-path "$resource_path" \
      --outfile "$svg_dir/$stem" \
      "$xml" >"$log_dir/$stem.verovio.log" 2>&1
  done
fi

python3 - "$input_abs" "$out_dir" "${generated_xml[@]}" <<'PY'
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

source = Path(sys.argv[1])
out_dir = Path(sys.argv[2])
xml_paths = [Path(path) for path in sys.argv[3:]]

def direct_children(element, name):
    return [child for child in list(element) if child.tag.split("}")[-1] == name]

def first_direct_text(element, name):
    for child in direct_children(element, name):
        if child.text:
            return child.text.strip()
    return None

def all_text(element, name):
    values = []
    for child in element.iter():
        if child.tag.split("}")[-1] == name and child.text:
            values.append(child.text.strip())
    return values

def measure_summary(measure):
    notes = direct_children(measure, "note")
    lyrics = all_text(measure, "text")
    note_count = 0
    rest_count = 0
    measure_rests = 0
    durations = []
    for note in notes:
        is_rest = bool(direct_children(note, "rest"))
        if is_rest:
            rest_count += 1
            rest = direct_children(note, "rest")[0]
            if rest.attrib.get("measure") == "yes":
                measure_rests += 1
        else:
            note_count += 1
        duration = first_direct_text(note, "duration")
        if duration:
            durations.append(duration)
    has_attributes = bool(direct_children(measure, "attributes"))
    suspicious_empty = note_count == 0 and rest_count > 0 and not lyrics and (measure_rests > 0 or not has_attributes)
    return {
        "number": measure.attrib.get("number", "?"),
        "notes": note_count,
        "rests": rest_count,
        "measureRests": measure_rests,
        "durations": durations,
        "lyrics": lyrics,
        "hasAttributes": has_attributes,
        "suspiciousEmpty": suspicious_empty,
    }

report = {
    "source": str(source),
    "musicXml": [],
}

lines = [f"Source: {source}", ""]
for xml_path in xml_paths:
    root = ET.parse(xml_path).getroot()
    title = None
    for tag in ("movement-title", "work-title", "credit-words"):
        found = root.find(f".//{tag}")
        if found is not None and found.text and found.text.strip():
            title = found.text.strip()
            break
    measures = [measure_summary(measure) for measure in root.iter() if measure.tag.split("}")[-1] == "measure"]
    suspicious = [measure for measure in measures if measure["suspiciousEmpty"]]
    lyrics = [lyric for measure in measures for lyric in measure["lyrics"]]
    item = {
        "path": str(xml_path),
        "title": title,
        "measureCount": len(measures),
        "suspiciousEmptyMeasures": suspicious,
        "lyrics": lyrics,
    }
    report["musicXml"].append(item)

    lines.append(f"MusicXML: {xml_path}")
    if title:
        lines.append(f"Title: {title}")
    lines.append(f"Measures: {len(measures)}")
    if suspicious:
        lines.append("Suspicious empty measures:")
        for measure in suspicious:
            index = measures.index(measure)
            previous_lyrics = measures[index - 1]["lyrics"] if index > 0 else []
            next_lyrics = measures[index + 1]["lyrics"] if index + 1 < len(measures) else []
            lines.append(
                f"  - measure {measure['number']}; previous lyrics={previous_lyrics}; next lyrics={next_lyrics}"
            )
    else:
        lines.append("Suspicious empty measures: none")
    lines.append(f"Lyrics ({len(lyrics)}): {' '.join(lyrics[:80])}")
    lines.append("")

(out_dir / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
(out_dir / "report.txt").write_text("\n".join(lines), encoding="utf-8")
print("\n".join(lines))
PY

python3 - "$out_dir/manifest.json" "$input_abs" "$dpi" "$homr_package" "${#page_images[@]}" "$render" <<'PY'
import json
import sys
from pathlib import Path

path, source, dpi, homr_package, page_count, rendered = sys.argv[1:]
Path(path).write_text(
    json.dumps(
        {
            "source": source,
            "dpi": dpi,
            "homrPackage": homr_package,
            "pageCount": int(page_count),
            "rendered": rendered == "1",
        },
        indent=2,
    ),
    encoding="utf-8",
)
PY

printf 'Local score import output: %s\n' "$out_dir"
