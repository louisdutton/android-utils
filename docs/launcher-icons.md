# Launcher Icon Spec

GrapheneOS Essentials launcher icons use one shared adaptive icon treatment.
This keeps first-party apps visually native and avoids upstream branding drift.

## Adaptive Icon

- Canvas: vector drawable, `108dp` by `108dp`, `viewportWidth="108"`,
  `viewportHeight="108"`.
- Background: solid white, `#FFFFFFFF`.
- Foreground color: solid black, `#FF000000`.
- Monochrome icon: use the same foreground geometry as the launcher foreground.
- Do not use gradients, shadows, colored fills, text, logos, or decorative
  badges.

## Foreground Group

Every foreground vector must place app-specific geometry inside this exact
group:

```xml
<group
    android:pivotX="54"
    android:pivotY="54"
    android:scaleX="0.60"
    android:scaleY="0.54"
    android:translateY="-1.25">
```

The visible glyph should be optically centered around source coordinate
`54,54`. This transform produces the same launcher padding as the system Camera
icon on the target Pixel launcher: roughly a `78px` wide glyph inside a `131px`
white adaptive-icon tile.

## Stroke Geometry

- Primary stroke width: `7`.
- Stroke caps: `round`.
- Stroke joins: `round` unless the shape needs a folded corner or equivalent
  hard structural detail.
- Internal detail strokes use the same width as the outline. If the icon cannot
  fit multiple details at `7`, reduce detail count rather than reducing stroke
  width.
- Filled shapes are avoided except when Android vector limitations make a tiny
  dot impractical; prefer `strokeLineCap="round"` zero-length strokes for dots.

## Source Bounds

Most icons should stay inside this source-space optical box before the group
transform:

- Left/right: `22..86`
- Top/bottom: `23..89`

Narrow icons, such as a microphone or pin, may occupy less horizontal width, but
they must keep the same group transform and vertical optical center.

For text-like internal marks, align the left edge exactly and use consistent
vertical rhythm. If a folded document corner is present, shorten the top line so
it clears the fold instead of crossing into it.
