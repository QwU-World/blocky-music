#!/usr/bin/env python3
"""
Builds the Workshop UI fonts: static Nunito 600/700/800 instances, subset, TrueType outlines, plus the
font JSONs Minecraft loads (one per text style and oversample tier). The outputs are committed; run this
only when a style, a tier or the character set changes.

    python3 tools/fonts/build-fonts.py                     # downloads the pinned Google Fonts files
    python3 tools/fonts/build-fonts.py --source Nunito[wght].ttf --ofl OFL.txt

Why each step (docs: blocky-music-workshop/docs/research/mc-gui-feasibility.md, section 4):
- Minecraft's TTF provider cannot choose a weight on a variable font, and Nunito[wght].ttf defaults to
  wght 200 (ExtraLight), so every weight is instanced to its own static file.
- FreeType in 1.21.1 refuses anything that is not "TrueType", so outlines stay quadratic (glyf).
- Minecraft ignores kerning and OpenType layout, so GSUB/GPOS/GDEF and hinting are dropped.
- Nunito has no arrows, star or heart. The build draws simple outlines for U+2190-2193, U+2605 and
  U+2665 so "4.52 ★" or "♥ 12" render in the same font instead of the pixel fallback. This makes the
  files a Modified Version under the SIL OFL 1.1; Nunito has no Reserved Font Name.

Needs fontTools (pip install fonttools). Needs Python 3.9+.
"""
import argparse
import hashlib
import json
import math
import sys
import urllib.request
from pathlib import Path

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.subset import Options, Subsetter
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/music_battle/font"
CACHE = ROOT / "build/fonts-cache"

# google/fonts main, fetched 17 September 2026. Pinned by hash so a silent upstream change fails loudly.
SOURCE_URL = "https://github.com/google/fonts/raw/main/ofl/nunito/Nunito%5Bwght%5D.ttf"
SOURCE_SHA256 = "bb55a5ca5c2042335b3991af27c4d0705d0ef41cac6164ac737fd8f2a1e85207"
OFL_URL = "https://github.com/google/fonts/raw/main/ofl/nunito/OFL.txt"
OFL_SHA256 = "580df76c95a1ec5ab878ceb25bb3d85c6a076804e9c970c8c6972aea775fdf65"

# weight -> (file stem, style name). Headings 800, titles 700, labels and body 600 (03 section 3.2).
WEIGHTS = {600: ("nunito-semibold", "SemiBold"), 700: ("nunito-bold", "Bold"), 800: ("nunito-extrabold", "ExtraBold")}

# Keep in step with ru.gora.musicbattle.client.ui.TextStyle (the ui-toolkit check compares them).
# id -> (weight, size in GUI units). Sizes follow osu! lazer's card at 1 osu px = 0.7 GUI units.
STYLES = {
    "display": (800, 16.0),
    "title": (700, 12.5),
    "body": (600, 10.0),
    "strong": (700, 10.0),
    "caption": (600, 8.0),
    "label": (700, 8.0),
    "pill": (800, 7.5),
}
TIERS = range(1, 10)  # oversample = physical pixels per GUI unit; Minecraft GUI scale is an integer 1..9+

UNICODES = (
    list(range(0x0020, 0x007F))      # Basic Latin
    + list(range(0x00A0, 0x0100))    # Latin-1 Supplement
    + list(range(0x0100, 0x0180))    # Latin Extended-A (Polish, Czech, Baltic names)
    + list(range(0x0400, 0x0500))    # Cyrillic
    + list(range(0x2010, 0x2028))    # dashes, quotes, bullet, ellipsis
    + list(range(0x2030, 0x203B))    # per mille, primes, angle quotes
    + [0x2044, 0x20AC, 0x20BD, 0x2116, 0x2122, 0x2212]
    + [0x2190, 0x2191, 0x2192, 0x2193, 0x2605, 0x2665]
)
SYMBOLS = {0x2190: "arrowleft", 0x2191: "arrowup", 0x2192: "arrowright", 0x2193: "arrowdown",
           0x2605: "blackstar", 0x2665: "heartBlackSuit"}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fetch(url: str, target: Path, expected: str) -> Path:
    if not target.is_file():
        target.parent.mkdir(parents=True, exist_ok=True)
        print(f"download {url}")
        with urllib.request.urlopen(url, timeout=60) as response:
            target.write_bytes(response.read())
    actual = sha256(target)
    if actual != expected:
        sys.exit(f"{target.name}: sha256 {actual}, expected {expected}. Upstream changed; review and re-pin.")
    return target


def glyph_bounds(font: TTFont, name: str):
    pen = BoundsPen(font.getGlyphSet())
    font.getGlyphSet()[name].draw(pen)
    return pen.bounds


def polygon(pen: TTGlyphPen, points):
    pen.moveTo(points[0])
    for p in points[1:]:
        pen.lineTo(p)
    pen.closePath()


def clockwise(points):
    area = sum(x0 * y1 - x1 * y0 for (x0, y0), (x1, y1) in zip(points, points[1:] + points[:1]))
    return points if area < 0 else list(reversed(points))


def rounded(points):
    return [(round(x), round(y)) for x, y in points]


def arrow_right(length: float, thickness: float, cy: float, left: float):
    half = thickness / 2.0
    head_len = max(thickness * 3.2, length * 0.40)
    head_half = max(thickness * 3.0, length * 0.33)
    tip = left + length
    neck = tip - head_len
    return [(left, cy + half), (neck, cy + half), (neck, cy + head_half), (tip, cy),
            (neck, cy - head_half), (neck, cy - half), (left, cy - half)]


def add_symbols(font: TTFont, cap: float):
    """Draws the six symbols into glyf/hmtx/cmap. Shapes sit on the baseline and reach cap height."""
    minus = glyph_bounds(font, "minus")
    thickness = minus[3] - minus[1]
    math_axis = (minus[1] + minus[3]) / 2.0
    side = 60.0
    shapes = {}

    horizontal = 620.0
    right = arrow_right(horizontal, thickness, math_axis, side)
    shapes["arrowright"] = (right, horizontal + 2 * side)
    shapes["arrowleft"] = ([(2 * side + horizontal - x, y) for x, y in right], horizontal + 2 * side)

    vertical = cap
    up_src = arrow_right(vertical, thickness, 0.0, 0.0)  # along +x, centred on y = 0
    head_half = max(p[1] for p in up_src)
    width = 2 * head_half + 2 * side
    cx = width / 2.0
    shapes["arrowup"] = ([(cx - y, x) for x, y in up_src], width)
    shapes["arrowdown"] = ([(cx + y, vertical - x) for x, y in up_src], width)

    outer = cap / (1.0 + math.cos(math.radians(36)))
    inner = outer * 0.43
    star_cy = cap - outer
    star_half_width = outer * math.sin(math.radians(72))
    star_cx = star_half_width + side
    star = []
    for k in range(10):
        angle = math.radians(90 - 36 * k)
        r = outer if k % 2 == 0 else inner
        star.append((star_cx + r * math.cos(angle), star_cy + r * math.sin(angle)))
    shapes["blackstar"] = (star, 2 * star_half_width + 2 * side)

    samples = []
    for i in range(44):
        t = 2 * math.pi * i / 44
        samples.append((16 * math.sin(t) ** 3,
                        13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t)))
    y_min = min(y for _, y in samples)
    y_max = max(y for _, y in samples)
    scale = cap / (y_max - y_min)
    heart_half = 16 * scale
    heart = [(side + heart_half + x * scale, (y - y_min) * scale) for x, y in samples]
    shapes["heartBlackSuit"] = (heart, 2 * heart_half + 2 * side)

    glyf = font["glyf"]
    hmtx = font["hmtx"]
    order = font.getGlyphOrder()
    for cp, name in SYMBOLS.items():
        points, advance = shapes[name]
        pen = TTGlyphPen(None)
        pts = rounded(clockwise(points))
        if name == "heartBlackSuit":
            # All off-curve points: a closed quadratic B-spline, which rounds the heart like Nunito's
            # other shapes.
            pen.qCurveTo(*pts, None)
            pen.closePath()
        else:
            polygon(pen, pts)
        glyph = pen.glyph()
        if name not in order:
            order.append(name)
        glyf[name] = glyph
        glyph.recalcBounds(glyf)
        hmtx[name] = (round(advance), glyph.xMin)
        for table in font["cmap"].tables:
            if table.isUnicode():
                table.cmap[cp] = name
    font.setGlyphOrder(order)
    glyf.glyphOrder = order


def set_names(font: TTFont, style: str):
    name = font["name"]
    full = f"Nunito {style}"
    postscript = f"Nunito-{style}"
    for name_id in {record.nameID for record in name.names}:
        # 1-6, 16, 17 are rewritten below; 25 and 256+ described the variable axes and named instances.
        if name_id in (1, 2, 3, 4, 6, 16, 17, 25) or name_id >= 256:
            name.removeNames(nameID=name_id)
    for name_id, value in ((1, full), (2, "Regular"), (4, full), (6, postscript), (16, "Nunito"), (17, style),
                           (3, f"3.602;BlockyMusic;{postscript}-subset")):
        name.setName(value, name_id, 3, 1, 0x409)
        name.setName(value, name_id, 1, 0, 0)


def build_weight(source: Path, weight: int, stem: str, style: str) -> dict:
    font = TTFont(str(source), recalcTimestamp=False)
    font = instantiateVariableFont(font, {"wght": weight})
    font["OS/2"].usWeightClass = weight
    upm = font["head"].unitsPerEm
    cap = font["OS/2"].sCapHeight
    add_symbols(font, float(cap))
    set_names(font, style)

    options = Options()
    options.layout_features = []
    options.hinting = False
    options.glyph_names = False
    options.legacy_kern = False
    options.name_IDs = ["*"]
    options.name_languages = ["*"]
    options.notdef_outline = True
    options.recalc_timestamp = False
    options.prune_unicode_ranges = True
    options.drop_tables += ["GSUB", "GPOS", "GDEF", "STAT", "DSIG", "kern", "BASE", "JSTF", "MATH", "gasp", "prep"]
    subsetter = Subsetter(options)
    subsetter.populate(unicodes=UNICODES)
    subsetter.subset(font)

    out = ASSETS / f"{stem}.ttf"
    font.save(str(out), reorderTables=True)
    check = TTFont(str(out))
    assert check.sfntVersion == "\x00\x01\x00\x00", "not TrueType"
    assert "glyf" in check and "CFF " not in check, "missing glyf outlines"
    missing = [hex(cp) for cp in (0x0410, 0x044F, 0x0401, 0x0451, 0x2605, 0x2665, 0x2192) if cp not in check.getBestCmap()]
    assert not missing, f"missing {missing}"
    print(f"{out.relative_to(ROOT)}  {out.stat().st_size} B  {len(check.getGlyphOrder())} glyphs  sha256 {sha256(out)}")
    return {"upm": upm, "cap": cap, "ascent": check["hhea"].ascent, "descent": check["hhea"].descent}


def font_json(style: str, weight: int, size: float, tier: int, cap_em: float) -> dict:
    stem = WEIGHTS[weight][0]
    # Minecraft puts a glyph's top at y + 7 - bearingTop. Shifting by cap*size - 7 puts cap height at the draw y.
    shift_y = math.floor((cap_em * size - 7.0) * 100.0 + 0.5) / 100.0  # same rounding as TextStyle.shiftY
    return {
        "providers": [
            {"type": "ttf", "file": f"music_battle:{stem}.ttf", "size": size, "oversample": float(tier),
             "shift": [0.0, shift_y], "skip": ""},
            {"type": "reference", "id": "minecraft:default"},
        ]
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", type=Path, help="Nunito[wght].ttf (default: download the pinned file)")
    parser.add_argument("--ofl", type=Path, help="Nunito OFL.txt (default: download the pinned file)")
    args = parser.parse_args()

    source = args.source or fetch(SOURCE_URL, CACHE / "Nunito[wght].ttf", SOURCE_SHA256)
    if args.source and sha256(source) != SOURCE_SHA256:
        sys.exit(f"{source}: not the pinned Nunito[wght].ttf ({SOURCE_SHA256})")
    ofl = args.ofl or fetch(OFL_URL, CACHE / "OFL.txt", OFL_SHA256)
    if args.ofl and sha256(ofl) != OFL_SHA256:
        sys.exit(f"{ofl}: not the pinned OFL.txt ({OFL_SHA256})")

    ASSETS.mkdir(parents=True, exist_ok=True)
    metrics = {}
    for weight, (stem, style) in WEIGHTS.items():
        metrics[weight] = build_weight(source, weight, stem, style)
    # Minecraft rejects a resource path with an uppercase letter inside a namespace and logs an error for every
    # player, so the licence sits next to the TTFs under a lowercase name, and a copy left by an older run goes.
    (ASSETS / "license-ofl.txt").write_bytes(ofl.read_bytes())
    stale = ASSETS / "OFL.txt"
    if stale.exists() and stale.name in {p.name for p in ASSETS.iterdir()}:
        stale.unlink()

    cap_em = metrics[600]["cap"] / metrics[600]["upm"]
    for m in metrics.values():
        assert m["cap"] / m["upm"] == cap_em, "cap height differs between weights"
    for old in ASSETS.glob("ui_*_x*.json"):
        old.unlink()
    for style, (weight, size) in STYLES.items():
        for tier in TIERS:
            path = ASSETS / f"ui_{style}_x{tier}.json"
            path.write_text(json.dumps(font_json(style, weight, size, tier, cap_em), indent=2) + "\n", encoding="utf-8")
    print(f"{len(STYLES) * len(TIERS)} font JSONs, cap height {cap_em} em")


if __name__ == "__main__":
    main()
