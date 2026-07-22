#!/usr/bin/env python3
"""Render suite launcher resources into repository WebP icons."""

from __future__ import annotations

import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "apps/store/repository-icons"
ANDROID = "{http://schemas.android.com/apk/res/android}"

VECTOR_ICONS = {
    "assistant": "apps/assistant/src/main/res/drawable/ic_launcher_foreground.xml",
    "calendar": "apps/calendar/src/main/res/drawable/ic_launcher_foreground.xml",
    "documents": "apps/documents/src/main/res/drawable/ic_launcher_foreground.xml",
    "keyboard": "apps/keyboard/java/res/drawable/ic_launcher_foreground.xml",
    "learn": "apps/trainer/src/main/res/drawable/ic_launcher_foreground.xml",
    "messages": "apps/messages/presentation/src/main/res/drawable/ic_launcher_foreground.xml",
    "notes": "apps/notes/src/main/res/drawable/ic_launcher_foreground.xml",
    "piano": "apps/pitch/src/main/res/drawable/ic_launcher_foreground.xml",
    "recorder": "apps/recorder/src/main/res/drawable/ic_launcher_foreground.xml",
    "scores": "apps/scores/src/main/res/drawable/ic_launcher_foreground.xml",
    "weather": "apps/weather/src/main/res/drawable/ic_launcher_foreground.xml",
}

RASTER_ICONS = {
    "maps": "apps/maps/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
    "vault": "apps/vault/app/src/libre/res/mipmap-xxxhdpi/ic_launcher.png",
    "wallet": "apps/wallet/app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp",
}


def android_value(element: ET.Element, name: str, default: str | None = None) -> str | None:
    return element.get(f"{ANDROID}{name}", default)


def svg_color(value: str | None, default: str) -> str:
    if value is None:
        return default
    if value == "@android:color/transparent" or value.upper() == "#00000000":
        return "none"
    if value.startswith("#") and len(value) == 9:
        return f"#{value[3:]}"
    return value


def convert_element(source: ET.Element) -> ET.Element:
    tag = source.tag.rsplit("}", 1)[-1]
    if tag == "group":
        target = ET.Element("g")
        pivot_x = float(android_value(source, "pivotX", "0"))
        pivot_y = float(android_value(source, "pivotY", "0"))
        scale_x = float(android_value(source, "scaleX", "1"))
        scale_y = float(android_value(source, "scaleY", "1"))
        translate_x = float(android_value(source, "translateX", "0"))
        translate_y = float(android_value(source, "translateY", "0"))
        target.set(
            "transform",
            f"translate({pivot_x + translate_x} {pivot_y + translate_y}) "
            f"scale({scale_x} {scale_y}) translate({-pivot_x} {-pivot_y})",
        )
        for child in source:
            target.append(convert_element(child))
        return target
    if tag != "path":
        raise ValueError(f"Unsupported Android vector element: {tag}")

    target = ET.Element("path")
    target.set("d", str(android_value(source, "pathData")))
    target.set("fill", svg_color(android_value(source, "fillColor"), "none"))
    target.set("stroke", svg_color(android_value(source, "strokeColor"), "none"))
    for android_name, svg_name in (
        ("strokeWidth", "stroke-width"),
        ("strokeLineCap", "stroke-linecap"),
        ("strokeLineJoin", "stroke-linejoin"),
    ):
        value = android_value(source, android_name)
        if value is not None:
            target.set(svg_name, value)
    if android_value(source, "fillType") == "evenOdd":
        target.set("fill-rule", "evenodd")
    return target


def vector_to_svg(source: Path, destination: Path) -> None:
    vector = ET.parse(source).getroot()
    width = android_value(vector, "viewportWidth", "108")
    height = android_value(vector, "viewportHeight", "108")
    svg = ET.Element(
        "svg",
        {
            "xmlns": "http://www.w3.org/2000/svg",
            "width": "512",
            "height": "512",
            "viewBox": f"0 0 {width} {height}",
        },
    )
    ET.SubElement(svg, "rect", {"width": "100%", "height": "100%", "fill": "#ffffff"})
    for child in vector:
        svg.append(convert_element(child))
    ET.ElementTree(svg).write(destination, encoding="utf-8", xml_declaration=True)


def svg_to_webp(source: Path, destination: Path) -> None:
    quicklook = shutil.which("qlmanage")
    if quicklook is None:
        raise RuntimeError("qlmanage is required to render Android vector icons")
    with tempfile.TemporaryDirectory() as temporary:
        temporary_path = Path(temporary)
        subprocess.run(
            [quicklook, "-t", "-s", "512", "-o", str(temporary_path), str(source)],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        png = temporary_path / f"{source.name}.png"
        with Image.open(png) as image:
            image.convert("RGB").save(destination, "WEBP", lossless=True, method=6)


def raster_to_webp(source: Path, destination: Path) -> None:
    with Image.open(source) as image:
        rgba = image.convert("RGBA")
        flattened = Image.new("RGBA", rgba.size, "white")
        flattened.alpha_composite(rgba)
        flattened.convert("RGB").resize((512, 512), Image.Resampling.LANCZOS).save(
            destination, "WEBP", lossless=True, method=6
        )


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, relative_source in VECTOR_ICONS.items():
        svg = OUTPUT / f"{name}.svg"
        vector_to_svg(ROOT / relative_source, svg)
        svg_to_webp(svg, OUTPUT / f"{name}.webp")
    for name, relative_source in RASTER_ICONS.items():
        raster_to_webp(ROOT / relative_source, OUTPUT / f"{name}.webp")


if __name__ == "__main__":
    main()
