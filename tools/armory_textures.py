"""Generates the Armory GUI texture set.

Run from the repository root:  python tools/armory_textures.py

Art direction is "gunmetal armory": dark oiled steel, a soft top-light bevel,
fine brushed grain and light edge wear. Rarity colour is the only chroma on the
screen, so every sprite here is neutral. The white ones (row, tile_ring,
rule_fade, glow, the icons) are pure white with shaped alpha and get tinted at
draw time through RenderSystem.setShaderColor.

Two constraints come from how Minecraft 1.21.1 draws a nine-slice, and both are
load-bearing:

  * The inner region is always TILED, never stretched. Its content has to be
    near-uniform or the repeat shows across a wide panel. No gradients here -
    the screen draws those with fillGradient instead.
  * Borders clamp to half the target size, so a 12px row with an 8px border
    would crop its own art. Small controls use a 4px border.

Both sprite sizes leave a 128px inner region: 144 - 8 - 8 and 136 - 4 - 4.
"""

import json
import os

import numpy as np
from PIL import Image, ImageDraw

OUT = os.path.join("src", "main", "resources", "assets", "mcpskins",
                   "textures", "gui", "sprites", "armory")

LARGE, LARGE_BORDER = 144, 8
SMALL, SMALL_BORDER = 136, 4

STEEL = (34, 38, 43)
STEEL_LIT = (58, 65, 72)
STEEL_DIM = (13, 15, 18)
INSET = (18, 20, 24)
STAGE = (22, 25, 29)
TILE = (28, 32, 37)
CHIP = (38, 43, 49)
CHIP_LIT = (62, 70, 78)
ACCENT = (120, 170, 200)

SEED = 0x5C17


def canvas(size):
    return Image.new("RGBA", (size, size), (0, 0, 0, 0))


def grain(img, amount=4, seed=SEED):
    """Brushed-steel streaks: strong along y, near-constant along x.

    Constant along x means the inner region tiles horizontally with no seam,
    which is the direction a wide panel repeats in.
    """
    rng = np.random.default_rng(seed)
    a = np.array(img, dtype=np.int16)
    h, w = a.shape[:2]
    streak = rng.integers(-amount, amount + 1, size=(h, 1))
    speckle = rng.integers(-1, 2, size=(h, w))
    delta = (streak + speckle) * (a[:, :, 3] > 0)
    for c in range(3):
        a[:, :, c] = np.clip(a[:, :, c] + delta, 0, 255)
    return Image.fromarray(a.astype(np.uint8), "RGBA")


def bevel(draw, size, radius, light, dark, recessed=False):
    """One-pixel inner highlight and shadow. This is the whole read of raised
    versus sunk, so it goes on last, over the grain."""
    top, bottom = (dark, light) if recessed else (light, dark)
    draw.line([(radius, 1), (size - radius - 1, 1)], fill=top + (150,))
    draw.line([(1, radius), (1, size - radius - 1)], fill=top + (110,))
    draw.line([(radius, size - 2), (size - radius - 1, size - 2)], fill=bottom + (170,))
    draw.line([(size - 2, radius), (size - 2, size - radius - 1)], fill=bottom + (130,))


def rivets(draw, size, inset=4):
    """Corner bosses. A nine-slice draws each corner exactly once, so this is
    the only place a distinctive mark can sit without repeating."""
    corners = ((inset, inset), (size - 1 - inset, inset),
               (inset, size - 1 - inset), (size - 1 - inset, size - 1 - inset))
    for cx, cy in corners:
        draw.ellipse([cx - 1, cy - 1, cx + 1, cy + 1], fill=STEEL_LIT + (210,))
        draw.point((cx, cy), fill=STEEL_DIM + (200,))


def plate(size, radius, base, alpha, recessed=False, grain_amount=4, seed=SEED):
    img = canvas(size)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=base + (alpha,))
    img = grain(img, grain_amount, seed)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius,
                        outline=(6, 7, 9, 235), width=1)
    bevel(d, size, radius, STEEL_LIT, STEEL_DIM, recessed)
    return img


def white_shape(size, radius, ring=False, width=2):
    img = canvas(size)
    d = ImageDraw.Draw(img)
    box = [0, 0, size - 1, size - 1]
    if ring:
        d.rounded_rectangle(box, radius=radius, outline=(255, 255, 255, 255), width=width)
    else:
        d.rounded_rectangle(box, radius=radius, fill=(255, 255, 255, 255))
    return img


def icon(rows):
    """8x8 from an 8-line bitmap. '#' is opaque, '+' is half, space is clear."""
    img = canvas(8)
    px = img.load()
    for y, line in enumerate(rows):
        for x, ch in enumerate(line.ljust(8)):
            if ch == "#":
                px[x, y] = (255, 255, 255, 255)
            elif ch == "+":
                px[x, y] = (255, 255, 255, 128)
    return img


def save(img, name, border=None, size=None):
    os.makedirs(OUT, exist_ok=True)
    img.save(os.path.join(OUT, name + ".png"))
    meta_path = os.path.join(OUT, name + ".png.mcmeta")
    if border is None:
        if os.path.exists(meta_path):
            os.remove(meta_path)
        return
    meta = {"gui": {"scaling": {"type": "nine_slice", "width": size,
                                "height": size, "border": border}}}
    with open(meta_path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(meta, handle, indent=2)
        handle.write("\n")


def build():
    panel = plate(LARGE, 6, STEEL, 246)
    rivets(ImageDraw.Draw(panel), LARGE)
    save(panel, "panel", LARGE_BORDER, LARGE)

    save(plate(LARGE, 4, INSET, 235, recessed=True, grain_amount=3, seed=SEED + 1),
         "inset", LARGE_BORDER, LARGE)

    focus = plate(LARGE, 4, INSET, 235, recessed=True, grain_amount=3, seed=SEED + 1)
    ImageDraw.Draw(focus).rounded_rectangle([0, 0, LARGE - 1, LARGE - 1], radius=4,
                                            outline=ACCENT + (190,), width=1)
    save(focus, "inset_focus", LARGE_BORDER, LARGE)

    save(plate(LARGE, 4, STAGE, 240, recessed=True, grain_amount=3, seed=SEED + 2),
         "stage", LARGE_BORDER, LARGE)

    save(plate(SMALL, 4, TILE, 242, grain_amount=3, seed=SEED + 3),
         "tile", SMALL_BORDER, SMALL)
    save(plate(SMALL, 5, CHIP, 238, grain_amount=3, seed=SEED + 4),
         "chip", SMALL_BORDER, SMALL)

    chip_on = plate(SMALL, 5, CHIP_LIT, 245, grain_amount=3, seed=SEED + 4)
    ImageDraw.Draw(chip_on).rounded_rectangle([0, 0, SMALL - 1, SMALL - 1], radius=5,
                                              outline=ACCENT + (150,), width=1)
    save(chip_on, "chip_on", SMALL_BORDER, SMALL)

    save(white_shape(SMALL, 3), "row", SMALL_BORDER, SMALL)
    save(white_shape(SMALL, 4, ring=True, width=2), "tile_ring", SMALL_BORDER, SMALL)

    glow = np.zeros((32, 64, 4), dtype=np.uint8)
    yy, xx = np.mgrid[0:32, 0:64]
    radius = np.sqrt(((xx - 32) / 32.0) ** 2 + ((yy - 16) / 16.0) ** 2)
    glow[:, :, :3] = 255
    glow[:, :, 3] = np.clip((1.0 - radius) * 255, 0, 255).astype(np.uint8)
    save(Image.fromarray(glow, "RGBA"), "glow")

    rule = np.zeros((1, 64, 4), dtype=np.uint8)
    ramp = np.minimum(np.arange(64), np.arange(64)[::-1]) / 31.0
    rule[0, :, :3] = 255
    rule[0, :, 3] = np.clip(ramp * 255, 0, 255).astype(np.uint8)
    save(Image.fromarray(rule, "RGBA"), "rule_fade")

    save(icon(["  ####  ",
               " ##  ## ",
               " ##  ## ",
               "########",
               "##    ##",
               "## ## ##",
               "##  # ##",
               "########"]), "icon_lock")

    save(icon([" ####   ",
               "##  ##  ",
               "##  ##  ",
               "##  ##  ",
               " ####   ",
               "    ####",
               "     ###",
               "      ##"]), "icon_search")

    save(icon(["       #",
               "      ##",
               "     ## ",
               "##  ##  ",
               "### ##  ",
               " #####  ",
               "  ###   ",
               "   #    "]), "icon_check")


if __name__ == "__main__":
    build()
    print("wrote", len(os.listdir(OUT)), "files to", OUT)
