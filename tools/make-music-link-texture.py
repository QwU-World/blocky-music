#!/usr/bin/env python3
"""
Draws the 16x16 item sprite for the Blocky Music Link: a beamed pair of eighth notes.

    python3 tools/make-music-link-texture.py
    python3 tools/make-music-link-texture.py --preview build/music-link-preview.png

The sprite exists because this script does. The file that shipped before had no source and nobody could say
who drew it, so it could not honestly be licensed; this one is composed here out of the mod's own palette
tokens, which makes it QwU World's own work and lets NOTICE.md and LICENSE-ASSETS.md say so by naming this
script. Keep that true: change the drawing here and run it again, never paint over the PNG.

Two notes joined by one beam is what the item does. Tapping a mob with it marks that mob, tapping a second
one starts their duel, so the sprite shows the pair already tied together rather than a chain or a plug. The
earlier attempt at this file drew a chain link with a note hung through it, and at sixteen pixels across the
link's hole could not be both wide enough to read and walled thickly enough to survive a light slot; a beam
needs no hole.

An item sprite is read against an inventory slot that may be light (vanilla, #8B8B8B inside #C6C6C6) or dark
(a resource pack, or the mod's own panels), so a light silhouette alone would vanish on one and a dark one on
the other. Every shape here is a near-black outline around a purple fill: the outline carries it on a light
slot, the fill on a dark one. --preview writes the sprite on four slot colours at 1x, 4x and 8x so that can be
checked by eye instead of taken on trust.

Needs nothing but the standard library, on purpose: rebuilding a shipped asset should not wait on an image
library being installed.
"""
import argparse
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEXTURE = ROOT / "src/main/resources/assets/music_battle/textures/item/music_link.png"

SIZE = 16

# ru.gora.musicbattle.client.ui.Palette at hue 265. The notes take the accent ramp, so the item sits in the
# same family as the Workshop screens it belongs to and follows them if the hue ever moves.
ROLES = {
    ".": None,
    "o": 0x110F14,  # BG_0, the outline every shape carries
    "d": 0x5D2E9E,  # ACCENT_DEEP, where a head turns under
    "s": 0x8349D4,  # ACCENT_STRONG, the shaded right side
    "n": 0xBE8FFF,  # ACCENT, body
    "l": 0xC7B0E8,  # LINK, the lit left edge
}

OUTLINE = "o"

# Only the fill is drawn here; the outline is laid around it below, which is what keeps a grid this small
# readable while editing. Light falls from the top left, so the left edge of every shape is the lit role and
# the right edge the shaded one. The drawing has to stay one pixel clear of the border on all four sides,
# because that pixel is where its outline goes.
ART = (
    "................",
    "................",
    "................",
    "...lllllllllll..",
    "...nnnnnnnnnss..",
    "...ln.......ns..",
    "...ln.......ns..",
    "...ln.......ns..",
    "...ln.......ns..",
    "...ln.......ns..",
    "..lnn......lnn..",
    ".lnnns....lnnns.",
    ".nnnd.....nnnd..",
    "................",
    "................",
    "................",
)


def grid() -> dict:
    """The art as role per pixel, after checking it is the shape and vocabulary the texture needs."""
    if len(ART) != SIZE or any(len(row) != SIZE for row in ART):
        raise SystemExit(f"the art must be {SIZE} rows of {SIZE} characters")
    unknown = {c for row in ART for c in row} - set(ROLES)
    if unknown:
        raise SystemExit(f"the art uses roles that have no colour: {sorted(unknown)}")
    if OUTLINE in {c for row in ART for c in row}:
        raise SystemExit(f"the art must not place '{OUTLINE}' by hand; the outline is laid around the fill")
    return {(x, y): role for y, row in enumerate(ART) for x, role in enumerate(row) if ROLES[role] is not None}


def around(x: int, y: int):
    """The eight neighbours inside the canvas; corners included, so a diagonal edge is outlined too."""
    for nx in range(max(x - 1, 0), min(x + 2, SIZE)):
        for ny in range(max(y - 1, 0), min(y + 2, SIZE)):
            if (nx, ny) != (x, y):
                yield nx, ny


def compose() -> dict:
    """
    The fill, then one pixel of near-black around every edge of it.

    Every empty pixel that touches the fill becomes outline, including the ones inside an enclosed gap. An
    outline laid only around the silhouette would leave such a gap showing bare slot against bare fill, which
    is the one way a sprite of two light shapes goes illegible on a light slot.
    """
    fill = grid()
    pixels = {cell: ROLES[role] for cell, role in fill.items()}
    for (x, y) in list(fill):
        for neighbour in around(x, y):
            if neighbour not in fill:
                pixels[neighbour] = ROLES[OUTLINE]
    return pixels


def unoutlined(pixels: dict) -> list:
    """
    Every fill pixel the slot behind the item can still reach. Because the outline is laid around all four
    sides, the only way to get one is to draw into the border row or column, where the outline would have
    fallen off the canvas; the generator refuses rather than shipping a sprite with a bare edge.
    """
    outline = {cell for cell, colour in pixels.items() if colour == ROLES[OUTLINE]}
    reached = set()
    stack = [(x, y) for x in range(SIZE) for y in (0, SIZE - 1)]
    stack += [(x, y) for y in range(SIZE) for x in (0, SIZE - 1)]
    bare = []
    while stack:
        cell = stack.pop()
        if cell in reached or cell in outline:
            continue
        reached.add(cell)
        x, y = cell
        if cell in pixels:
            bare.append(cell)
            continue  # a drawn pixel is a wall: the slot stops at it rather than leaking inside
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if 0 <= nx < SIZE and 0 <= ny < SIZE:
                stack.append((nx, ny))
    return sorted(bare)


def png(width: int, height: int, rows: list) -> bytes:
    """A plain RGBA PNG: one IHDR, one IDAT, no ancillary chunks, so the file stays small and dull."""
    raw = b"".join(b"\x00" + bytes(value for pixel in row for value in pixel) for row in rows)

    def chunk(tag: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + tag + body + struct.pack(">I", zlib.crc32(tag + body))

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


def rgba(pixels: dict, width: int, height: int, background=None) -> list:
    rows = []
    for y in range(height):
        row = []
        for x in range(width):
            colour = pixels.get((x, y), background)
            row.append((0, 0, 0, 0) if colour is None
                       else (colour >> 16 & 255, colour >> 8 & 255, colour & 255, 255))
        rows.append(row)
    return rows


def preview(pixels: dict, path: Path) -> None:
    """The sprite on the slot colours it has to survive, at the size it is read and large enough to see."""
    slots = (0xC6C6C6, 0x8B8B8B, 0x3A3A3A, 0x110F14)
    scales = (1, 4, 8)
    pad = 2
    width = pad + sum(SIZE * s + pad for s in scales)
    height = pad + len(slots) * (SIZE * max(scales) + pad)
    canvas = {}
    for row, slot in enumerate(slots):
        top = pad + row * (SIZE * max(scales) + pad)
        left = pad
        for scale in scales:
            for y in range(SIZE * scale):
                for x in range(SIZE * scale):
                    canvas[(left + x, top + y)] = pixels.get((x // scale, y // scale), slot)
            left += SIZE * scale + pad
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png(width, height, rgba(canvas, width, height, background=0x6E6E6E)))
    print(f"{path}  {width}x{height} preview on {len(slots)} slot colours")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=TEXTURE, help="where to write the texture")
    parser.add_argument("--preview", type=Path, help="also write the light-slot/dark-slot contact sheet")
    args = parser.parse_args()

    pixels = compose()
    bare = unoutlined(pixels)
    if bare:
        raise SystemExit(f"these pixels meet the slot without an outline: {bare}")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(png(SIZE, SIZE, rgba(pixels, SIZE, SIZE)))
    print(f"{args.out}  {args.out.stat().st_size} B  {len(pixels)} of {SIZE * SIZE} pixels drawn")
    if args.preview:
        preview(pixels, args.preview)


if __name__ == "__main__":
    main()
