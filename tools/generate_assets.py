#!/usr/bin/env python3
"""Generate Fabricated Backpacks' original Minecraft assets, using only Python's stdlib.

Run from any directory: python tools/generate_assets.py [--check] [--review]
The generator writes only its declared resources and project icon. It never deletes files.
Review images are offline orthographic renders of the production cuboids/textures,
not Minecraft screenshots. All geometry, palettes, pixel clusters and prose here
are original project work, covered by the project's MIT license.
PNG streams use explicitly encoded stored DEFLATE blocks, independent of zlib's compressor.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import struct
import sys
import zlib
import generate_automation_assets as automation
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MOD = "fabricated_backpacks"
ASSET = f"src/main/resources/assets/{MOD}"
DATA = f"src/main/resources/data/{MOD}"
PROJECT_ICON = "docs/media/project-icon.png"
SIDES = ("north", "south", "east", "west", "up", "down")
BODY_COLOR = 0xB97843
TRIM_COLOR = 0x503B36


@dataclass(frozen=True)
class Tier:
    item: str
    name: str
    material: str
    palette: tuple[str, str, str, str]


TIERS = (
    Tier("backpack", "Leather Backpack", "leather", ("503B32", "866344", "BC9661", "E7C78C")),
    Tier("copper_backpack", "Copper Backpack", "copper", ("653B35", "A85E41", "E19760", "F6C38A")),
    Tier("iron_backpack", "Iron Backpack", "iron", ("444C59", "808C9A", "C6CFCE", "F0EFE0")),
    Tier("gold_backpack", "Gold Backpack", "gold", ("796032", "BE9039", "F4CC58", "FFF1AC")),
    Tier("diamond_backpack", "Diamond Backpack", "diamond", ("24596B", "3B9EAA", "81DDCE", "D7FFF0")),
    Tier("netherite_backpack", "Netherite Backpack", "netherite", ("27252F", "49404E", "797079", "BAA096")),
)

# Each glyph is a deliberately drawn 9x9 pixel cluster. Frame, accent, and small
# indicators communicate progression without replacing the recognizable glyph.
GLYPHS = {
    "pickup": ("....H....", "....H....", "..H.H.H..", "...HHH...", "....H....", ".........", ".H.....H.", ".HHHHHHH.", "........."),
    "filter": ("HHHHHHHHH", ".HHHHHHH.", "..HHHHH..", "...HHH...", "....H....", "....H....", "...HH....", "...H.....", "........."),
    "magnet": ("RR.....HH", "RR.....HH", "RR.....HH", "HH.....HH", "HH.....HH", ".HH...HH.", "..HHHHH..", "...HHH...", "........."),
    "feeding": (".....G...", "....G....", ".RRR.RRR.", "RRRRRRRRR", "RRHHRRRRR", "RRHRRRRRR", ".RRRRRRR.", "..RR.RR..", "........."),
    "compacting": ("H.......H", ".H.....H.", "..H...H..", "...HHH...", "...HHH...", "...HHH...", "..H...H..", ".H.....H.", "H.......H"),
    "void": ("..PPPPP..", ".PP...PP.", "PP.PPP.PP", "P.P...P.P", "P.P.P.P.P", "P.P...P.P", "PP.PPP.PP", ".PP...PP.", "..PPPPP.."),
    "restock": ("......H..", ".....HHH.", "....HHHHH", "......H..", ".HHH..H..", ".H.H..H..", ".HHH..H..", ".HHHHHH..", "........."),
    "deposit": ("..HHHHH..", "..H...H..", "HHHH..H..", ".HHHH.H..", "HHHH..H..", "..H...H..", "..HHHHH..", ".........", "........."),
    "refill": ("..HHHH...", ".H...HH..", ".....HHH.", ".........", "...HHH...", "...H.H...", "..HH.HH..", "..HCCCH..", "..HHHHH.."),
    "inception": ("HHHHHHHHH", "H.......H", "H.HHHHH.H", "H.H...H.H", "H.H.H.H.H", "H.H...H.H", "H.HHHHH.H", "H.......H", "HHHHHHHHH"),
    "everlasting": ("....H....", ".HHHHHHH.", ".H.HHH.H.", ".H..H..H.", ".H.HHH.H.", "..H.H.H..", "..H...H..", "...H.H...", "....H...."),
    "smelting": ("HHHHHHHHH", "H.......H", "H.HHHHH.H", "H.H...H.H", "H.H.R.H.H", "H..RRR..H", "H.RRORR.H", "HHHHHHHHH", "........."),
    "smoking": ("...H.H...", "..H.H....", "...H.H...", ".........", ".HHHHHHH.", ".H.....H.", ".H.RRR.H.", ".HHHHHHH.", "........."),
    "blasting": ("HHHHHHHHH", "H.H.H.H.H", "HHHHHHHHH", "H.O...O.H", "H..ORO..H", "H...R...H", "H..ORO..H", "HHHHHHHHH", "........."),
    "crafting": ("HH.HH.HH.", "HH.HH.HH.", ".........", "HH.HH.HH.", "HH.HH.HH.", ".........", "HH.HH.HH.", "HH.HH.HH.", "........."),
    "stonecutter": ("...H.H...", "..HHHHH..", ".HHH.HHH.", "HHH...HHH", ".HHH.HHH.", "..HHHHH..", "...H.H...", "HHHHHHHHH", "........."),
    "jukebox": ("..HHHHH..", ".HKKKKKH.", "HKKKKKKKH", "HKKOOOKKH", "HKKOHOKKH", "HKKOOOKKH", "HKKKKKKKH", ".HKKKKKH.", "..HHHHH.."),
    "tool_swapper": ("HHHHH..H.", "..H.H.HH.", "..H..HHH.", "...HHHH..", "...HHH...", "..HH.HH..", ".HH...HH.", "HH.....HH", "........."),
    "tank": ("..HHHHH..", ".H.....H.", ".H.H...H.", ".H.H...H.", ".H.CCC.H.", ".HCCCCCH.", ".HCCCCCH.", ".HCCCCCH.", "..HHHHH.."),
    "battery": ("...HHH...", "..HHHHH..", "..H.H.H..", "..HHHHH..", "..H.H.H..", "..HGGGH..", "..HGGGH..", "..HHHHH..", "........."),
    "pump": ("HHH......", "..H......", "..HHHHH..", "..HCCCH..", "..HCCCH..", "..HHHHH..", "......H..", ".....HHH.", "......H.."),
    "xp_pump": ("....G....", "...GGG...", "....G....", ".........", "..GGGGG..", ".GGHHHGG.", ".GHGGGHG.", ".GGHHHGG.", "..GGGGG.."),
    "anvil": ("HHHHHHHHH", ".HHHHHHH.", "...HHH...", "...HHH...", "...HHH...", "..HHHHH..", ".HHHHHHH.", ".........", "........."),
    "smithing": ("..HHHHHH.", "..HHHHHH.", "..HHHHHH.", "....O....", "....O....", "...OO....", "..OO.....", ".OO......", "........."),
    "infinity": (".........", ".........", ".HH...HH.", "H..H.H..H", "H...H...H", "H..H.H..H", ".HH...HH.", ".........", "........."),
    "survival_infinity": ("....O....", ".........", ".HH...HH.", "H..H.H..H", "H...H...H", "H..H.H..H", ".HH...HH.", ".........", "....O...."),
    "alchemy": ("...HHH...", "....H....", "...H.H...", "...H.H...", "..H...H..", ".H.PPP.H.", "H.PPPPP.H", "HPPPPPPPH", ".HHHHHHH."),
    "mob_catcher": ("OO.....OO", "O.G...G.O", "..GGGGG..", "..GHHHG..", "..GHGHG..", "..GGGGG..", "...GGG...", "O.......O", "OO.....OO"),
}

DESCRIPTIONS = {
    "pickup": "Collect matching items when you walk over them.",
    "filter": "Control which items may enter or leave the backpack.",
    "magnet": "Draw nearby matching items into the backpack.",
    "feeding": "Eat selected food from the backpack when you are hungry.",
    "compacting": "Combine stored materials into their compact crafting forms.",
    "void": "Discard selected incoming items according to your filter.",
    "restock": "Top up matching inventory stacks from the backpack.",
    "deposit": "Move selected backpack items into a connected inventory.",
    "refill": "Keep selected hotbar stacks supplied from the backpack.",
    "inception": "Carry backpacks inside this backpack with safe nesting limits.",
    "everlasting": "Protect this backpack from environmental item damage.",
    "smelting": "Smelt ingredients with fuel in a built-in furnace.",
    "smoking": "Cook food with fuel in a built-in smoker.",
    "blasting": "Process ores and metals with fuel in a built-in blast furnace.",
    "crafting": "Craft with a full three-by-three workbench.",
    "stonecutter": "Cut stone using recipes from a built-in stonecutter.",
    "jukebox": "Play from a two-disc playlist in the carried or placed backpack.",
    "tool_swapper": "Switch to a suitable tool stored in the backpack.",
    "tank": "Store fluid in a tank, with dedicated container slots.",
    "battery": "Store energy and charge compatible items.",
    "pump": "Transfer fluid between the backpack tank and its surroundings.",
    "xp_pump": "Store experience and manage your chosen experience level.",
    "anvil": "Repair and rename items with a built-in anvil.",
    "smithing": "Apply smithing recipes at a built-in smithing table.",
    "infinity": "Provide unlimited copies of stored items. Creative access only.",
    "survival_infinity": "Preserve permitted stored items when they are supplied.",
    "alchemy": "Apply selected potions from the backpack.",
    "mob_catcher": "Capture an eligible creature and release it later.",
}

UPGRADE_NAMES = {
    "xp_pump": "Experience Pump", "tool_swapper": "Tool Swapper", "mob_catcher": "Mob Catcher",
    "stonecutter": "Stonecutter", "survival_infinity": "Survival Infinity",
}

# An original progression: a framed module, a tool that embodies its purpose,
# and accessible materials. Advanced modules build on the corresponding basic.
INGREDIENTS = {
    "pickup": ("hopper", "copper_ingot", "redstone"),
    "filter": ("paper", "iron_ingot", "string"),
    "magnet": ("compass", "iron_ingot", "redstone"),
    "feeding": ("golden_carrot", "wheat", "bowl"),
    "compacting": ("piston", "iron_ingot", "redstone"),
    "void": ("lava_bucket", "obsidian", "redstone"),
    "restock": ("barrel", "copper_ingot", "redstone"),
    "deposit": ("dropper", "copper_ingot", "redstone"),
    "refill": ("dispenser", "iron_ingot", "redstone"),
    "inception": ("ender_chest", "diamond", "ender_pearl"),
    "everlasting": ("nether_star", "netherite_ingot", "obsidian"),
    "smelting": ("furnace", "copper_ingot", "coal"),
    "smoking": ("smoker", "copper_ingot", "coal"),
    "blasting": ("blast_furnace", "iron_ingot", "coal"),
    "crafting": ("crafting_table", "iron_ingot", "stick"),
    "stonecutter": ("stonecutter", "iron_ingot", "smooth_stone"),
    "jukebox": ("jukebox", "copper_ingot", "amethyst_shard"),
    "tool_swapper": ("iron_pickaxe", "iron_ingot", "redstone"),
    "tank": ("bucket", "copper_ingot", "glass"),
    "battery": ("lightning_rod", "copper_ingot", "redstone_block"),
    "pump": ("bucket", "iron_ingot", "piston"),
    "xp_pump": ("experience_bottle", "gold_ingot", "lapis_lazuli"),
    "anvil": ("anvil", "iron_ingot", "smooth_stone"),
    "smithing": ("smithing_table", "iron_ingot", "diamond"),
    "survival_infinity": ("nether_star", "netherite_ingot", "diamond_block"),
    "alchemy": ("brewing_stand", "gold_ingot", "glass_bottle"),
    "mob_catcher": ("ender_pearl", "gold_ingot", "lead"),
}


def rgb(value: str | int) -> tuple[int, int, int, int]:
    number = int(value, 16) if isinstance(value, str) else value
    return (number >> 16 & 255, number >> 8 & 255, number & 255, 255)


def stored_zlib(data: bytes) -> bytes:
    """Encode an RFC 1950 stream with deterministic RFC 1951 stored blocks.

    Compression libraries may choose different matches or Huffman trees across
    platforms. Stored blocks specify every output byte and remain valid PNG IDAT
    data. A zero-length input still needs one final block.
    """
    result = bytearray(b"\x78\x01")  # DEFLATE, 32 KiB window, no dictionary, valid FCHECK.
    for start in range(0, max(1, len(data)), 65535):
        block = data[start:start + 65535]
        size = len(block)
        result.append(int(start + size == len(data)))  # BFINAL; BTYPE=00 and zero padding.
        result.extend(struct.pack("<HH", size, size ^ 0xffff))
        result.extend(block)

    # Python integers do not overflow; reducing these sums at the end is exact.
    low, high = 1, 0
    for value in data:
        low += value
        high += low
    result.extend(struct.pack(">I", (high % 65521) << 16 | low % 65521))
    return bytes(result)


class Raster:
    def __init__(self, width: int, height: int, color=(0, 0, 0, 0)):
        self.width, self.height = width, height
        self.pixels = [color] * (width * height)

    def put(self, x: int, y: int, color):
        if 0 <= x < self.width and 0 <= y < self.height:
            self.pixels[y * self.width + x] = rgb(color) if isinstance(color, (str, int)) else color

    def at(self, x: int, y: int):
        return self.pixels[max(0, min(y, self.height - 1)) * self.width + max(0, min(x, self.width - 1))]

    def rect(self, x0, y0, x1, y1, color):
        for y in range(y0, y1):
            for x in range(x0, x1):
                self.put(x, y, color)

    def line(self, x0, y0, x1, y1, color):
        count = max(abs(x1 - x0), abs(y1 - y0), 1)
        for i in range(count + 1):
            self.put(round(x0 + (x1 - x0) * i / count), round(y0 + (y1 - y0) * i / count), color)

    def paste(self, source: Raster, x0: int, y0: int, scale=1):
        for y in range(source.height):
            for x in range(source.width):
                color = source.at(x, y)
                if color[3]:
                    self.rect(x0 + x * scale, y0 + y * scale, x0 + (x + 1) * scale, y0 + (y + 1) * scale, color)

    def png(self) -> bytes:
        def chunk(kind, contents):
            return struct.pack(">I", len(contents)) + kind + contents + struct.pack(">I", zlib.crc32(kind + contents))
        rows = b"".join(b"\0" + bytes(channel for pixel in self.pixels[y * self.width:(y + 1) * self.width] for channel in pixel)
                        for y in range(self.height))
        return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", self.width, self.height, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", stored_zlib(rows)) + chunk(b"IEND", b""))


FONT = {
    "A": "010101111101101", "B": "110101110101110", "C": "011100100100011", "D": "110101101101110",
    "E": "111100110100111", "F": "111100110100100", "G": "011100101101011", "H": "101101111101101",
    "I": "111010010010111", "J": "001001001101010", "K": "101101110101101", "L": "100100100100111",
    "M": "101111111101101", "N": "101111111111101", "O": "010101101101010", "P": "110101110100100",
    "Q": "010101101111011", "R": "110101110101101", "S": "011100010001110", "T": "111010010010010",
    "U": "101101101101111", "V": "101101101101010", "W": "101101111111101", "X": "101101010101101",
    "Y": "101101010010010", "Z": "111001010100111", "0": "111101101101111", "1": "010110010010111",
    "2": "110001010100111", "3": "110001010001110", "4": "101101111001001", "5": "111100110001110",
    "6": "011100111101111", "7": "111001010010010", "8": "111101111101111", "9": "111101111001110",
    "-": "000000111000000", "+": "000010111010000", ".": "000000000000010", "/": "001001010100100",
    ":": "000010000010000", " ": "000000000000000",
}


def lettering(image: Raster, label: str, x: int, y: int, color="D6D6D6", scale=1):
    for letter in label.upper():
        for index, on in enumerate(FONT.get(letter, FONT[" "])):
            if on == "1":
                dx, dy = index % 3, index // 3
                image.rect(x + dx * scale, y + dy * scale, x + (dx + 1) * scale, y + (dy + 1) * scale, color)
        x += 4 * scale


def read_upgrade_ids() -> list[str]:
    source = (ROOT / "src/main/java/com/kadamitas/fabricatedbackpacks/domain/UpgradeKind.java").read_text(encoding="utf-8")
    ids = re.findall(r'^\s+[A-Z][A-Z_0-9]*\("([a-z_0-9]+)"', source, re.MULTILINE)
    if len(ids) != 54 or len(set(ids)) != 54:
        raise ValueError(f"Expected 54 unique UpgradeKind identifiers, found {len(ids)}")
    return ids


def stack_id(level: int) -> str:
    return "stack_upgrade_starter_tier" if level == 0 else f"stack_upgrade_tier_{level}"


def conversion_id(source: int, target: int) -> str:
    return f"{stack_id(source)}_to_tier_{target}_conversion"


CONVERSIONS = tuple((start, end, conversion_id(start, end)) for start in range(4) for end in range(start + 1, 5))


def identity(item: str) -> tuple[str, bool, bool]:
    advanced, automatic = item.startswith("advanced_"), item.startswith("auto_")
    family = item.removeprefix("advanced_").removeprefix("auto_").removesuffix("_upgrade")
    return family, advanced, automatic


def item_icon(item: str) -> Raster:
    image = Raster(16, 16)
    family, advanced, automatic = identity(item)
    border = "C9A663" if advanced else "87959D"
    if item.startswith("stack_"):
        border = "C38454" if "downgrade" in item else "7BC6CA"
    if item in ("infinity_upgrade", "stack_upgrade_omega_tier"):
        border = "C39DEA"
    image.rect(3, 1, 13, 15, "293039")
    image.rect(1, 3, 15, 13, "293039")
    image.rect(2, 3, 14, 13, border)
    image.rect(3, 2, 13, 14, border)
    image.rect(3, 4, 13, 12, "273A43")
    image.rect(4, 3, 12, 13, "273A43")
    image.line(4, 2, 11, 2, "EDF0D7" if advanced else "C2D6D2")
    image.line(4, 13, 11, 13, "6C583D" if advanced else "4B5964")
    image.put(2, 5, "C38B59")
    image.put(13, 10, "C38B59")
    colors = {"H": "D5E7D9", "O": "F0BE55", "C": "5DC2D3", "P": "B48BDF", "G": "8CCB65", "R": "E97B59", "K": "141D27"}
    if item == "upgrade_base":
        image.line(4, 7, 7, 4, "B78054")
        image.line(7, 4, 11, 8, "E6C38D")
        image.line(11, 8, 8, 11, "B78054")
        image.line(8, 11, 4, 7, "B78054")
        image.rect(6, 6, 9, 9, "D5E7D9")
    elif item.endswith("_conversion"):
        match = next(c for c in CONVERSIONS if c[2] == item)
        lettering(image, "S" if match[0] == 0 else str(match[0]), 4, 4, "E0D6AA")
        lettering(image, str(match[1]), 9, 4, "D5E7D9")
        image.line(4, 11, 10, 11, "79CBC1")
        image.line(8, 9, 10, 11, "79CBC1")
        image.line(8, 13, 10, 11, "79CBC1")
    elif item.startswith("stack_"):
        downgrade = "downgrade" in item
        if "omega" in item:
            pattern = GLYPHS["infinity"]
            for y, row in enumerate(pattern):
                for x, value in enumerate(row):
                    if value != ".":
                        image.put(x + 4, y + 3, "D3ACF5")
            image.put(7, 3, "FFFFFF")
        else:
            label = "S" if "starter" in item else item[-1]
            lettering(image, label, 8, 5, "E8D6AA" if downgrade else "D5E7D9")
            image.line(4, 5, 4, 11, "EAA779" if downgrade else "79CBC1")
            point, back = (11, 9) if downgrade else (5, 7)
            image.line(2, back, 4, point, "EAA779" if downgrade else "79CBC1")
            image.line(6, back, 4, point, "EAA779" if downgrade else "79CBC1")
    else:
        if family not in GLYPHS:
            raise ValueError(f"No original icon glyph for {item} ({family})")
        for y, row in enumerate(GLYPHS[family]):
            for x, value in enumerate(row):
                if value != ".":
                    image.put(x + 4, y + 3, colors[value])
    if advanced:
        image.rect(11, 1, 14, 4, "594234")
        image.put(12, 1, "FFF1AC")
        image.rect(11, 2, 14, 3, "F4CC58")
        image.put(12, 3, "BE9039")
    if automatic:
        image.rect(10, 10, 14, 14, "1B4854")
        image.line(11, 11, 13, 11, "87E3D6")
        image.line(13, 11, 13, 13, "87E3D6")
        image.put(11, 13, "87E3D6")
        image.put(10, 12, "87E3D6")
    return image


def leather_texture(kind: str) -> Raster:
    image = Raster(16, 16)
    base = {"leather_body": 222, "leather_trim": 239, "leather_lining": 99, "leather_pocket": 222}[kind]
    for y in range(16):
        for x in range(16):
            # Sparse hand-positioned grain, with no seeded/random state.
            change = -9 if (x * 7 + y * 11) % 37 == 0 else 4 if (x * 13 + y * 3) % 41 == 0 else 0
            value = max(0, min(base + change, 255))
            image.put(x, y, (value, value, value, 255))
    if kind == "leather_pocket":
        image.rect(0, 0, 16, 1, "ABABAB")
        image.rect(0, 15, 16, 16, "A4A4A4")
        for point in range(3, 14, 3):
            image.put(point, 13, "F0F0F0")
            image.put(2, point, "F0F0F0")
            image.put(13, point, "F0F0F0")
    if kind == "leather_trim":
        for point in range(1, 16, 4):
            image.put(3, point, "FFFFFF")
            image.put(12, point, "FFFFFF")
    return image


def fitting_texture(tier: Tier) -> Raster:
    image = Raster(16, 16, rgb(tier.palette[2]))
    image.rect(0, 0, 16, 2, tier.palette[3])
    image.rect(0, 0, 2, 16, tier.palette[1])
    image.rect(0, 14, 16, 16, tier.palette[0])
    image.rect(14, 2, 16, 14, tier.palette[1])
    image.rect(2, 12, 14, 14, tier.palette[1])
    image.line(3, 3, 10, 3, tier.palette[3])
    image.line(3, 4, 6, 4, tier.palette[3])
    return image


def cuboid(name, start, end, texture="body", tint=0, overrides=None, rotation=None):
    faces = {side: {"uv": [0, 0, 16, 16], "texture": f"#{texture}"} for side in SIDES}
    if tint is not None:
        for face in faces.values():
            face["tintindex"] = tint
    for side, (override_texture, override_tint) in (overrides or {}).items():
        faces[side]["texture"] = f"#{override_texture}"
        faces[side].pop("tintindex", None)
        if override_tint is not None:
            faces[side]["tintindex"] = override_tint
    element = {"name": name, "from": list(start), "to": list(end), "faces": faces}
    if rotation:
        element["rotation"] = rotation
    return element


def backpack_elements(tier: Tier, opened: bool):
    parts = []
    def add(name, start, end, texture="body", tint=0, **kwargs):
        parts.append(cuboid(name, start, end, texture, tint, **kwargs))
    # Hollow leather shell: interior faces and floor remain opaque and complete.
    add("body_floor", (3, .5, 5), (13, 2, 12), overrides={"up": ("lining", 0)})
    add("body_left", (3, 2, 5), (4, 11, 12), overrides={"east": ("lining", 0)})
    add("body_right", (12, 2, 5), (13, 11, 12), overrides={"west": ("lining", 0)})
    add("body_back", (4, 2, 11), (12, 11, 12), overrides={"north": ("lining", 0)})
    add("body_front", (4, 2, 5), (12, 10.5, 6), overrides={"south": ("lining", 0)})
    add("base_welt_front", (2.875, .5, 4.875), (13.125, 1.25, 5.375), "trim", 1)
    add("base_welt_back", (2.875, .5, 11.625), (13.125, 1.25, 12.125), "trim", 1)
    add("base_welt_left", (2.875, .5, 5.375), (3.375, 1.25, 11.625), "trim", 1)
    add("base_welt_right", (12.625, .5, 5.375), (13.125, 1.25, 11.625), "trim", 1)
    add("front_pocket", (4, 2.125, 2), (12, 6.625, 5.125), overrides={"north": ("pocket", 0)})
    add("pocket_flap", (3.75, 6.625, 1.75), (12.25, 7.375, 5.125), "trim", 1)
    add("pocket_latch", (7.375, 5.75, 1.625), (8.625, 7.375, 2), "fittings", None)
    # Attached lower side pouches give the pack a readable rear/side silhouette.
    # Their caps stay below the moving lid; mirroring preserves torso centering.
    for side in ("left", "right"):
        for name, start, end, texture, tint in (
                ("body", (1, 2.125, 5.75), (3.25, 6.625, 10.75), "body", 0),
                ("cap", (.875, 6.625, 5.5), (3.25, 7.25, 11), "trim", 1),
                ("strap", (.875, 3.125, 7.625), (1.125, 7, 8.875), "trim", 1),
                ("clasp", (.75, 4.875, 7.375), (1, 5.625, 9.125), "fittings", None),
                ("welt", (.875, 2.125, 5.625), (3.25, 2.625, 10.875), "trim", 1)):
            if side == "right":
                start, end = (16 - end[0], *start[1:]), (16 - start[0], *end[1:])
            add(f"side_pocket_{side}_{name}", start, end, texture, tint)
    # Shoulder straps form a real gap against the rear shell.
    for side, x in (("left", 4), ("right", 10.5)):
        add(f"strap_{side}_upper", (x, 9.5, 11.875), (x + 1.5, 11, 13), "trim", 1)
        add(f"strap_{side}_long", (x, 3.5, 12.375), (x + 1.5, 9.5, 13), "trim", 1)
        add(f"strap_{side}_lower", (x, 2, 11.875), (x + 1.5, 3.5, 13), "trim", 1)
        add(f"strap_{side}_adjuster", (x - .125, 3.5, 12.875), (x + 1.625, 4.375, 13.125), "fittings", None)
    add("handle_left", (6, 11, 10.5), (6.75, 13.5, 11.25), "trim", 1)
    add("handle_right", (9.25, 11, 10.5), (10, 13.5, 11.25), "trim", 1)
    add("handle_top", (6.75, 12.75, 10.5), (9.25, 13.5, 11.25), "trim", 1)
    hinge = {"origin": [8, 11.25, 11.75], "axis": "x", "angle": 45, "rescale": False} if opened else None
    add("flap_top", (2.75, 10.75, 4.5), (13.25, 12.125, 12.25), rotation=hinge)
    add("flap_lip", (3, 9.125, 4.25), (13, 10.75, 5.25), "trim", 1, rotation=hinge)
    for side, x in (("left", 5), ("right", 9.5)):
        add(f"flap_tab_{side}", (x, 8.25, 4), (x + 1.5, 11.375, 4.5), "trim", 1, rotation=hinge)
        # A framed buckle, not an opaque square printed on the bag.
        for label, a, b in (
                ("top", (x - .125, 9.75, 3.75), (x + 1.625, 10.125, 4.125)),
                ("bottom", (x - .125, 8.625, 3.75), (x + 1.625, 9, 4.125)),
                ("left", (x - .125, 9, 3.75), (x + .25, 9.75, 4.125)),
                ("right", (x + 1.25, 9, 3.75), (x + 1.625, 9.75, 4.125))):
            add(f"buckle_{side}_{label}", a, b, "fittings", None, rotation=hinge)
    # Progression alters real geometry in addition to the material palette.
    if tier.material != "leather":
        for side, x in (("left", 3), ("right", 12)):
            add(f"corner_{side}_front", (x, 1.25, 4.75), (x + 1, 3, 5.25), "fittings", None)
            add(f"corner_{side}_back", (x, 1.25, 11.75), (x + 1, 3, 12.25), "fittings", None)
    if tier.material in ("iron", "gold", "diamond", "netherite"):
        add("side_clip_left", (2.75, 6.125, 7.125), (3.25, 8.125, 9.125), "fittings", None)
        add("side_clip_right", (12.75, 6.125, 7.125), (13.25, 8.125, 9.125), "fittings", None)
    if tier.material == "gold":
        add("gold_pocket_edge", (4, 2.125, 1.75), (12, 2.625, 2.125), "fittings", None)
    if tier.material == "diamond":
        add("diamond_pocket_seal", (7.375, 3.75, 1.5), (8.625, 5, 2), "fittings", None,
            rotation={"origin": [8, 4.375, 1.75], "axis": "z", "angle": 45, "rescale": False})
    if tier.material == "netherite":
        add("netherite_pocket_guard", (4, 2.125, 1.625), (12, 2.875, 2.125), "fittings", None)
        add("netherite_flap_guard", (3, 11.75, 4.375), (13, 12.25, 5.375), "fittings", None, rotation=hinge)
        add("netherite_side_guard_left", (.75, 2, 7.875), (1.25, 4.375, 8.625), "fittings", None)
        add("netherite_side_guard_right", (14.75, 2, 7.875), (15.25, 4.375, 8.625), "fittings", None)
    return parts


DISPLAY = {
    "gui": {"rotation": [24, 152, 0], "translation": [0, 1, 0], "scale": [.88, .88, .88]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2.5, 0], "scale": [.55, .55, .55]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [.82, .82, .82]},
    "thirdperson_righthand": {"rotation": [75, 0, 0], "translation": [0, 2.25, 1.5], "scale": [.55, .55, .55]},
    "thirdperson_lefthand": {"rotation": [75, 0, 0], "translation": [0, 2.25, 1.5], "scale": [.55, .55, .55]},
    "firstperson_righthand": {"rotation": [0, 155, 0], "translation": [1.25, 1.5, .5], "scale": [.7, .7, .7]},
    "firstperson_lefthand": {"rotation": [0, 205, 0], "translation": [1.25, 1.5, .5], "scale": [.7, .7, .7]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
}


def backpack_model(tier: Tier, opened: bool):
    return {
        "ambientocclusion": True,
        "textures": {
            "particle": f"{MOD}:block/leather_body", "body": f"{MOD}:block/leather_body",
            "trim": f"{MOD}:block/leather_trim", "lining": f"{MOD}:block/leather_lining",
            "pocket": f"{MOD}:block/leather_pocket", "fittings": f"{MOD}:block/fittings/{tier.material}",
        },
        "elements": backpack_elements(tier, opened),
        "display": DISPLAY,
    }


def flap_parts(tier: Tier):
    return [part["name"] for part in backpack_elements(tier, True)
            if part.get("rotation", {}).get("axis") == "x"
            and part["rotation"]["origin"] == [8, 11.25, 11.75]]


def body_model(tier: Tier):
    model = backpack_model(tier, False)
    moving = set(flap_parts(tier))
    model["elements"] = [part for part in model["elements"] if part["name"] not in moving]
    return model


def shaped(result: str, pattern, key, count=1, recipe_type="minecraft:crafting_shaped", **extra):
    return {"type": recipe_type, "category": "equipment", "pattern": pattern, "key": key,
            "result": {"id": f"{MOD}:{result}", "count": count}, **extra}


def ring_recipe(result: str, center: str, ring: str, **extra):
    return shaped(result, ["MMM", "MUM", "MMM"], {"M": ring, "U": center}, **extra)


def generate_recipes(upgrades):
    recipes = {
        "backpack": shaped("backpack", ["SLS", "LCL", "SLS"], {"S": "minecraft:string", "L": "minecraft:leather", "C": "minecraft:chest"}),
        "dye_backpack": {"type": f"{MOD}:dye_backpack"},
        "upgrade_base": shaped("upgrade_base", ["SCS", "IPI", "SCS"], {"S": "minecraft:string", "C": "minecraft:copper_ingot", "I": "minecraft:iron_nugget", "P": "minecraft:paper"}),
    }
    for result, source, material in (
            ("copper_backpack", "backpack", "copper_ingot"), ("iron_backpack", "backpack", "iron_ingot"),
            ("gold_backpack", "iron_backpack", "gold_ingot"), ("diamond_backpack", "gold_backpack", "diamond")):
        recipes[result] = ring_recipe(result, f"{MOD}:{source}", f"minecraft:{material}",
                                      recipe_type=f"{MOD}:backpack_upgrade", source=f"{MOD}:{source}")
    recipes["iron_backpack_from_copper"] = shaped("iron_backpack", [" I ", "IBI", " I "], {"I": "minecraft:iron_ingot", "B": f"{MOD}:copper_backpack"},
                                                       recipe_type=f"{MOD}:backpack_upgrade", source=f"{MOD}:copper_backpack")
    recipes["netherite_backpack"] = {
        "type": f"{MOD}:backpack_smithing", "template": "minecraft:netherite_upgrade_smithing_template",
        "base": f"{MOD}:diamond_backpack", "addition": "minecraft:netherite_ingot", "result": {"id": f"{MOD}:netherite_backpack", "count": 1},
    }
    for item in upgrades:
        if item in ("infinity_upgrade", "stack_upgrade_omega_tier"):
            continue
        if item.startswith("stack_"):
            if "downgrade" in item:
                level = int(item[-1])
                center = "upgrade_base" if level == 1 else f"stack_downgrade_tier_{level - 1}"
                recipes[item] = shaped(item, [" S ", "SUS", " C "], {"S": "minecraft:string", "U": f"{MOD}:{center}", "C": "minecraft:iron_chain"})
            elif item == "stack_upgrade_starter_tier":
                recipes[item] = shaped(item, [" I ", "IUI", " R "], {"I": "minecraft:iron_ingot", "U": f"{MOD}:upgrade_base", "R": "minecraft:redstone"})
            else:
                level = int(item[-1])
                material = ("iron_ingot", "gold_ingot", "diamond", "netherite_ingot")[level - 1]
                recipes[item] = ring_recipe(item, f"{MOD}:{stack_id(level - 1)}", f"minecraft:{material}")
            continue
        family, advanced, automatic = identity(item)
        if advanced:
            recipes[item] = shaped(item, [" G ", "GUG", " R "], {"G": "minecraft:gold_ingot", "U": f"{MOD}:{family}_upgrade", "R": "minecraft:comparator"})
        elif automatic:
            recipes[item] = shaped(item, [" H ", "RUR", " C "], {"H": "minecraft:hopper", "R": "minecraft:redstone", "U": f"{MOD}:{family}_upgrade", "C": "minecraft:comparator"})
        else:
            tool, material, support = INGREDIENTS[family]
            recipes[item] = shaped(item, [" M ", "TUT", " S "], {"M": f"minecraft:{tool}", "T": f"minecraft:{material}", "U": f"{MOD}:upgrade_base", "S": f"minecraft:{support}"})
    for source, target, item in CONVERSIONS:
        material = ("iron_ingot", "gold_ingot", "diamond", "netherite_ingot")[target - 1]
        center = "upgrade_base" if target == source + 1 else conversion_id(source, target - 1)
        recipes[item] = ring_recipe(item, f"{MOD}:{center}", f"minecraft:{material}")
        recipes[f"{item}_apply"] = {"type": "minecraft:crafting_shapeless", "category": "equipment",
            "ingredients": [f"{MOD}:{stack_id(source)}", f"{MOD}:{item}"], "result": {"id": f"{MOD}:{stack_id(target)}", "count": 1}}
    return recipes


def translations(upgrades):
    lang = {
        "itemGroup.fabricated_backpacks": "Fabricated Backpacks",
        "itemGroup.fabricated_backpacks.main": "Fabricated Backpacks",
        "container.fabricated_backpacks.backpack": "Backpack",
        "container.fabricated_backpacks.equipment": "Backpack Equipment",
        "key.category.fabricated_backpacks": "Fabricated Backpacks",
        "key.fabricated_backpacks.open": "Open Backpack",
        "key.fabricated_backpacks.equip": "Equip or Unequip Backpack",
        "key.fabricated_backpacks.browser": "Open Recipe Browser",
        "key.fabricated_backpacks.sort": "Sort Backpack",
        "key.fabricated_backpacks.toggle_upgrade": "Toggle Backpack Upgrades",
        "tooltip.fabricated_backpacks.capacity": "%s storage slots",
        "tooltip.fabricated_backpacks.upgrade_slots": "%s upgrade slots",
        "tooltip.fabricated_backpacks.open": "Use to open; sneak-use on a block to place.",
        "tooltip.fabricated_backpacks.equip": "Equip in the backpack slot alongside chest armor.",
        "tooltip.fabricated_backpacks.dye": "Body and trim can be dyed independently.",
        "tooltip.fabricated_backpacks.contents_hint": "Hold Shift to preview contents.",
        "tooltip.fabricated_backpacks.contents_summary": "%s items in %s / %s slots",
        "tooltip.fabricated_backpacks.contents_empty": "The backpack is empty.",
        "tooltip.fabricated_backpacks.contents_counts": "Large counts use k / M / B; total above is exact.",
        "tooltip.fabricated_backpacks.netherite_progression": "Smith with the vanilla Netherite Upgrade Smithing Template + Netherite Ingot.",
        "tooltip.fabricated_backpacks.upgrade_base": "A blank frame for crafting backpack upgrades.",
        "tooltip.fabricated_backpacks.advanced": "Expanded controls and matching options.",
        "tooltip.fabricated_backpacks.automatic": "Automatically supplies inputs and collects results.",
        "tooltip.fabricated_backpacks.creative_only": "Creative access only",
        "item.fabricated_backpacks.upgrade_base": "Upgrade Frame",
    }
    for tier in TIERS:
        lang[f"item.{MOD}.{tier.item}"] = tier.name
        lang[f"block.{MOD}.{tier.item}"] = tier.name
    for item in upgrades:
        family, advanced, automatic = identity(item)
        if item.startswith("stack_"):
            if "omega" in item:
                name, description = "Omega Stack Upgrade", "Set the stack limit to its maximum. Creative access only."
            elif "downgrade" in item:
                level = int(item[-1])
                name = f"Stack Downgrade {('I', 'II', 'III')[level - 1]}"
                description = f"Limit each storage slot to one {8 * 2 ** (level - 1)}th of its usual stack size."
            else:
                level = 0 if "starter" in item else int(item[-1])
                name = "Starter Stack Upgrade" if level == 0 else f"Stack Upgrade {('I', 'II', 'III', 'IV')[level - 1]}"
                multiplier = "1.5" if level == 0 else str(2 ** level)
                description = f"Multiply the backpack's storage stack limits by {multiplier}."
        else:
            display = UPGRADE_NAMES.get(family, family.replace("_", " ").title())
            name = ("Advanced " if advanced else "Automatic " if automatic else "") + display + " Upgrade"
            description = DESCRIPTIONS[family]
            if advanced and family == "jukebox":
                description = "Manage a twenty-four-disc paged playlist with track, shuffle and repeat controls."
            elif advanced:
                description += " Expanded controls and matching options."
            elif automatic:
                description += " Automatically supplies inputs and collects results."
        lang[f"item.{MOD}.{item}"] = name
        lang[f"tooltip.{MOD}.{item}"] = description
    for source, target, item in CONVERSIONS:
        first = "Starter" if source == 0 else ("I", "II", "III", "IV")[source - 1]
        second = ("I", "II", "III", "IV")[target - 1]
        lang[f"item.{MOD}.{item}"] = f"Stack Conversion: {first} to {second}"
        lang[f"tooltip.{MOD}.{item}"] = f"Combine with a {first} stack upgrade to obtain tier {second}."
    # UI prose lives in one explicit input, so regeneration never discards it.
    for filename in ("ui_strings.json", "browser_strings.json", "automation_strings.json"):
        ui_path = ROOT / "tools/assets" / filename
        if ui_path.exists():
            additions = json.loads(ui_path.read_text(encoding="utf-8"))
            if not isinstance(additions, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in additions.items()):
                raise ValueError("UI strings must be a JSON object containing only string values")
            lang.update(additions)
    return dict(sorted(lang.items()))


def logo() -> Raster:
    image = Raster(32, 32)
    image.rect(7, 1, 25, 31, "203E43")
    image.rect(3, 5, 29, 27, "203E43")
    image.rect(1, 9, 31, 23, "203E43")
    image.rect(4, 8, 28, 25, "31575A")
    image.rect(7, 5, 25, 28, "31575A")
    image.rect(13, 4, 19, 6, "EFBC76")
    image.rect(11, 6, 13, 10, "C1874D")
    image.rect(19, 6, 21, 10, "C1874D")
    image.rect(8, 10, 24, 26, "513930")
    image.rect(7, 10, 23, 25, "AD693F")
    image.rect(9, 9, 22, 23, "CD8C51")
    image.rect(7, 8, 24, 12, "EDB373")
    image.rect(8, 11, 23, 14, "754D38")
    image.rect(10, 17, 22, 24, "784C37")
    image.rect(11, 17, 21, 22, "CD8C51")
    image.rect(10, 16, 22, 18, "E7A35E")
    for x in (10, 19):
        image.rect(x, 10, x + 2, 16, "593C32")
        image.rect(x - 1, 12, x + 3, 15, "F6D78C")
        image.rect(x, 13, x + 2, 14, "754D38")
    image.rect(15, 16, 17, 19, "F6D78C")
    for x in (12, 15, 18):
        image.put(x, 21, "F4D29A")
    big = Raster(128, 128)
    big.paste(image, 0, 0, 4)
    return big


def entity_atlas(texture: Raster) -> Raster:
    image = Raster(64, 64)
    for y in range(64):
        for x in range(64):
            image.put(x, y, texture.at(x % 16, y % 16))
    return image


def generate():
    outputs: dict[str, bytes] = {}
    rasters: dict[str, Raster] = {}
    models = {}
    def put_json(path, value):
        outputs[path] = (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    def put_png(path, value):
        outputs[path] = value.png()
        rasters[path] = value
    upgrades = read_upgrade_ids()
    misc_items = ["upgrade_base", *upgrades, *(value[2] for value in CONVERSIONS)]
    for kind in ("leather_body", "leather_trim", "leather_lining", "leather_pocket"):
        put_png(f"{ASSET}/textures/block/{kind}.png", leather_texture(kind))
    for tier in TIERS:
        put_png(f"{ASSET}/textures/block/fittings/{tier.material}.png", fitting_texture(tier))
        put_png(f"{ASSET}/textures/entity/backpack/{tier.material}_fittings.png", entity_atlas(fitting_texture(tier)))
        for state in ("closed", "open"):
            value = backpack_model(tier, state == "open")
            name = f"{ASSET}/models/block/{tier.item}_{state}.json"
            put_json(name, value)
            models[name] = value
        name = f"{ASSET}/models/block/{tier.item}_body.json"
        value = body_model(tier)
        put_json(name, value)
        models[name] = value
        put_json(f"{ASSET}/blockstates/{tier.item}.json", {"variants": {
            f"facing={facing},open={opened}": {"model": f"{MOD}:block/{tier.item}_body", "y": angle, "uvlock": False}
            for facing, angle in (("north", 0), ("east", 90), ("south", 180), ("west", 270)) for opened in ("false", "true")}})
        put_json(f"{ASSET}/models/item/{tier.item}.json", {"parent": f"{MOD}:block/{tier.item}_closed"})
        put_json(f"{ASSET}/items/{tier.item}.json", {"model": {"type": "minecraft:model", "model": f"{MOD}:item/{tier.item}", "tints": [
            {"type": "minecraft:custom_model_data", "index": 0, "default": BODY_COLOR},
            {"type": "minecraft:custom_model_data", "index": 1, "default": TRIM_COLOR},
        ]}})
        # Block code owns the persisted ItemStack drop. A second loot drop would
        # duplicate the backpack; an empty table is deliberate and audited.
        put_json(f"{DATA}/loot_table/blocks/{tier.item}.json", {"type": "minecraft:block", "pools": []})
    for kind, texture in (("body", "leather_body"), ("trim", "leather_trim"), ("lining", "leather_lining"), ("pocket", "leather_pocket")):
        put_png(f"{ASSET}/textures/entity/backpack/{kind}.png", entity_atlas(leather_texture(texture)))
    for item in misc_items:
        put_png(f"{ASSET}/textures/item/{item}.png", item_icon(item))
        put_json(f"{ASSET}/models/item/{item}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/{item}"}})
        put_json(f"{ASSET}/items/{item}.json", {"model": {"type": "minecraft:model", "model": f"{MOD}:item/{item}"}})
    for recipe, value in generate_recipes(upgrades).items():
        put_json(f"{DATA}/recipe/{recipe}.json", value)
    utility_tools = {
        "shearing": {"items": ["minecraft:shears"], "entities": ["minecraft:sheep", "minecraft:mooshroom", "minecraft:snow_golem"], "priority": 100},
        "tilling": {"items": ["#minecraft:hoes"], "blocks": ["minecraft:dirt", "minecraft:grass_block", "minecraft:dirt_path", "minecraft:coarse_dirt", "minecraft:rooted_dirt"], "priority": 10},
        "flattening": {"items": ["#minecraft:shovels"], "blocks": ["minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:podzol", "minecraft:mycelium", "minecraft:rooted_dirt"], "priority": 10},
    }
    for name, rule in utility_tools.items():
        put_json(f"{DATA}/backpack_tools/{name}.json", {**rule, "manual_only": True, "require_correct_tool": False})
    put_json(f"{DATA}/tags/item/backpacks.json", {"replace": False, "values": [f"{MOD}:{tier.item}" for tier in TIERS]})
    put_json(f"{DATA}/tags/item/upgrades.json", {"replace": False, "values": [f"{MOD}:{item}" for item in upgrades]})
    put_json(f"{DATA}/tags/item/stack_conversions.json", {"replace": False, "values": [f"{MOD}:{item}" for _, _, item in CONVERSIONS]})
    put_json(f"{DATA}/tags/block/backpacks.json", {"replace": False, "values": [f"{MOD}:{tier.item}" for tier in TIERS]})
    put_json("src/main/resources/data/minecraft/tags/item/piglin_safe_armor.json",
             {"replace": False, "values": [f"{MOD}:gold_backpack"]})
    put_json(f"{ASSET}/lang/en_us.json", translations(upgrades))
    put_png(f"{ASSET}/icon.png", logo())
    for name, item in (("upgrades", "upgrade_base"), ("recipes", "crafting_upgrade"), ("sort", "compacting_upgrade"),
                       ("filter", "filter_upgrade"), ("settings", "advanced_tool_swapper_upgrade"), ("music", "jukebox_upgrade")):
        put_png(f"{ASSET}/textures/gui/sprites/{name}.png", item_icon(item))
    slot = Raster(16, 16)
    slot.rect(4, 5, 12, 13, "78828A")
    slot.rect(5, 6, 11, 12, "464D55")
    slot.rect(6, 2, 10, 5, "78828A")
    slot.rect(7, 3, 9, 5, (0, 0, 0, 0))
    slot.rect(3, 5, 13, 7, "89929A")
    slot.rect(6, 9, 10, 12, "78828A")
    put_png(f"{ASSET}/textures/gui/sprites/backpack_slot.png", slot)
    profiles = {
        "schema": 1, "coordinate_system": "block units; +Y up; backpack front -Z; straps +Z",
        "body_color": BODY_COLOR, "trim_color": TRIM_COLOR,
        "atlas_size": [64, 64], "worn_uv_origin": [0, 0],
        "material_textures": {name: f"{MOD}:entity/backpack/{name}" for name in ("body", "trim", "lining", "pocket")},
        "material_tints": {"body": 0, "trim": 1, "lining": 0, "pocket": 0, "fittings": -1},
        "source_to_player_body": {
            "note": "Suggested unscaled player-body local cuboids: native_from=offset-source_to; native_to=offset-source_from. Native +Y is down and +Z is behind the wearer. Verify armor clearance and pose in game.",
            "axis_sign": [-1, -1, -1], "offset": [8, 13.75, 15.375],
        },
        "wear_transform": {"translation_pixels": [0, 0, .70], "scale": [.90, 1.00, .70], "armor_clearance_pixels": 1},
        "flap_hinge": {"origin": [8, 11.25, 11.75], "axis": "x", "closed_angle": 0, "open_angle": 45, "duration_ticks": 8},
        "tiers": [{"id": tier.item, "material": tier.material,
                   "closed_model": f"{MOD}:block/{tier.item}_closed", "open_model": f"{MOD}:block/{tier.item}_open",
                   "body_model": f"{MOD}:block/{tier.item}_body", "flap_parts": flap_parts(tier),
                   "fittings_texture": f"{MOD}:entity/backpack/{tier.material}_fittings",
                   "parts": [{"name": element["name"], "from": element["from"], "to": element["to"],
                              "texture": element["faces"]["north"]["texture"].removeprefix("#"),
                              **({"rotation": element["rotation"]} if "rotation" in element else {})}
                             for element in backpack_elements(tier, False)]} for tier in TIERS],
    }
    put_json(f"{ASSET}/backpack_profiles.json", profiles)
    automation.generate(sys.modules[__name__], put_json, put_png, models)
    put_png(PROJECT_ICON, project_icon(rasters, models))
    manifest = {
        "schema": 1, "generator": "tools/generate_assets.py", "license": "MIT",
        "registered_item_count": 76, "backpack_tier_count": 6, "upgrade_count": 54, "conversion_count": 10,
        "automation_items": list(automation.ITEMS),
        "creative_only": ["infinity_upgrade", "stack_upgrade_omega_tier"],
        "loot_policy": "Backpack block code drops its persisted ItemStack; generated block loot pools are empty to prevent duplicate drops.",
        "texture_policy": "16x16 pixel-cluster item and block textures; 64x64 tiled material atlases; 128x128 mod emblem; 512x512 original production-model project icon. No generated file depends on an external image.",
        "inputs": {str(path.relative_to(ROOT)).replace("\\", "/"): hashlib.sha256(path.read_bytes()).hexdigest()
                   for path in (Path(__file__).resolve(), ROOT / "tools/generate_automation_assets.py", ROOT / "tools/assets/automation_strings.json", ROOT / "tools/assets/ui_strings.json", ROOT / "tools/assets/browser_strings.json",
                                ROOT / "src/main/java/com/kadamitas/fabricatedbackpacks/domain/UpgradeKind.java") if path.exists()},
        "files": {path.removeprefix("src/main/resources/"): hashlib.sha256(contents).hexdigest()
                  for path, contents in sorted(outputs.items()) if path.startswith("src/main/resources/")},
        "project_artifacts": {PROJECT_ICON: hashlib.sha256(outputs[PROJECT_ICON]).hexdigest()},
    }
    put_json(f"{ASSET}/asset_manifest.json", manifest)
    return outputs, rasters, models


def rotated_vertex(point, rotation):
    if not rotation:
        return point
    origin = rotation["origin"]
    p = [point[i] - origin[i] for i in range(3)]
    angle = math.radians(rotation["angle"])
    c, s = math.cos(angle), math.sin(angle)
    index = {"x": 0, "y": 1, "z": 2}[rotation["axis"]]
    first, second = ((1, 2), (2, 0), (0, 1))[index]
    p[first], p[second] = p[first] * c - p[second] * s, p[first] * s + p[second] * c
    return [p[i] + origin[i] for i in range(3)]


def vertices(element, face):
    a, b = element["from"], element["to"]
    x0, y0, z0 = a
    x1, y1, z1 = b
    corners = {
        "north": [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
        "south": [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
        "east": [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
        "west": [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
        "up": [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        "down": [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
    }
    return [rotated_vertex(point, element.get("rotation")) for point in corners[face]]


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def render_model(model, rasters, azimuth=-30, elevation=20, size=160, body=BODY_COLOR, trim=TRIM_COLOR,
                 *, background="202C36", span=23, center=(8, 8, 8)):
    """Small orthographic Z-buffer rasterizer, reading the actual model's face UVs."""
    image = Raster(size, size, rgb(background) if isinstance(background, (str, int)) else background)
    depth = [-float("inf")] * (size * size)
    yaw, pitch = math.radians(azimuth), math.radians(elevation)
    scale = size / span
    facing = (math.sin(yaw) * math.cos(pitch), math.sin(pitch), -math.cos(yaw) * math.cos(pitch))
    right = (math.cos(yaw), 0, math.sin(yaw))
    up = (-math.sin(yaw) * math.sin(pitch), math.cos(pitch), math.cos(yaw) * math.sin(pitch))
    def project(point):
        local = [point[i] - center[i] for i in range(3)]
        return (size / 2 + sum(local[i] * right[i] for i in range(3)) * scale,
                size / 2 - sum(local[i] * up[i] for i in range(3)) * scale,
                sum(local[i] * facing[i] for i in range(3)))
    def triangle(points, uv, texture, tint, shade):
        (ax, ay, az), (bx, by, bz), (cx, cy, cz) = points
        denominator = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
        if abs(denominator) < 1e-8:
            return
        x0, x1 = max(0, math.floor(min(ax, bx, cx))), min(size - 1, math.ceil(max(ax, bx, cx)))
        y0, y1 = max(0, math.floor(min(ay, by, cy))), min(size - 1, math.ceil(max(ay, by, cy)))
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                px, py = x + .5, y + .5
                a = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / denominator
                b = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / denominator
                c = 1 - a - b
                if min(a, b, c) < -1e-7:
                    continue
                z = az * a + bz * b + cz * c
                if z < depth[y * size + x] - 1e-6:
                    continue
                u = uv[0][0] * a + uv[1][0] * b + uv[2][0] * c
                v = uv[0][1] * a + uv[1][1] * b + uv[2][1] * c
                pixel = texture.at(min(15, int(u)), min(15, int(v)))
                if not pixel[3]:
                    continue
                color = tuple(max(0, min(255, int(pixel[i] * tint[i] / 255 * shade))) for i in range(3)) + (255,)
                image.put(x, y, color)
                depth[y * size + x] = z
    for element in model["elements"]:
        for face_name, face in element["faces"].items():
            points = vertices(element, face_name)
            edge1 = [points[1][i] - points[0][i] for i in range(3)]
            edge2 = [points[2][i] - points[0][i] for i in range(3)]
            normal = cross(edge1, edge2)
            length = math.sqrt(sum(v * v for v in normal))
            normal = [v / length for v in normal]
            # Winding above is clockwise from outside, so its normal is inward.
            normal = [-v for v in normal]
            if sum(normal[i] * facing[i] for i in range(3)) <= 0:
                continue
            resource = model["textures"][face["texture"].removeprefix("#")]
            texture = rasters[f"{ASSET}/textures/{resource.split(':', 1)[1]}.png"]
            tint = rgb(body if face.get("tintindex") == 0 else trim) if "tintindex" in face else (255, 255, 255, 255)
            shade = .72 + .28 * max(0, normal[1] * .8 + normal[0] * -.3 + normal[2] * -.5)
            u0, v0, u1, v1 = face["uv"]
            uv = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
            projected = [project(p) for p in points]
            for order in ((0, 1, 2), (0, 2, 3)):
                triangle([projected[i] for i in order], [uv[i] for i in order], texture, tint, shade)
    return image


def project_icon(rasters, models):
    """An original square publishing icon rendered from the actual closed gold model.

    Work at 128px and enlarge exact pixel clusters: the silhouette stays crisp in
    launcher listings, while the production UVs, cuboids and material colors remain visible.
    This is branding artwork, not evidence of a Minecraft client run.
    """
    image = Raster(128, 128)
    shadow, light = rgb("10292F"), rgb("244D50")
    for y in range(128):
        for x in range(128):
            # A few deliberate color bands keep the background quiet at small sizes.
            distance = math.sqrt(((x - 61.5) / 75) ** 2 + ((y - 52.5) / 78) ** 2)
            strength = math.floor(max(0, 1 - distance) * 6) / 6
            image.put(x, y, tuple(round(shadow[c] + (light[c] - shadow[c]) * strength) for c in range(3)) + (255,))
    rim = ((8, 20), (20, 8), (108, 8), (120, 20), (120, 108), (108, 120), (20, 120), (8, 108))
    for start, end in zip(rim, rim[1:] + rim[:1]):
        image.line(*start, *end, "345D60")
    for x, y in ((18, 15), (109, 15), (18, 112), (109, 112)):
        image.rect(x, y, x + 2, y + 2, "6B8580")
    model = models[f"{ASSET}/models/block/gold_backpack_closed.json"]
    pack = render_model(model, rasters, -28, 20, 112, BODY_COLOR, TRIM_COLOR,
                        background=(0, 0, 0, 0), span=17.5, center=(8, 6.5, 8))
    occupied = [(x + 8, y + 6) for y in range(pack.height) for x in range(pack.width) if pack.at(x, y)[3]]
    if not occupied or min(x for x, _ in occupied) < 10 or max(x for x, _ in occupied) > 118 \
            or min(y for _, y in occupied) < 10 or max(y for _, y in occupied) > 118:
        raise ValueError("The project icon's production model must fit within its safe margin")
    for x, y in occupied:
        image.put(x + 2, y + 3, "0B2328")
    for x, y in occupied:
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            image.put(x + dx, y + dy, "0A2227")
    image.paste(pack, 8, 6)
    result = Raster(512, 512)
    result.paste(image, 0, 0, 4)
    return result


def review_images(rasters, models):
    output = ROOT / "build/reports/asset-audit"
    output.mkdir(parents=True, exist_ok=True)
    sheet = Raster(6 * 170 + 20, 4 * 190 + 55, rgb("16212B"))
    lettering(sheet, "FABRICATED BACKPACKS - PRODUCTION MODEL REVIEW", 15, 12, "E3C69E", 2)
    views = ((-30, 20, False, BODY_COLOR, TRIM_COLOR, "FRONT"), (155, 20, False, BODY_COLOR, TRIM_COLOR, "BACK"),
             (-35, 30, True, BODY_COLOR, TRIM_COLOR, "OPEN"), (-35, 20, False, 0x547F96, 0xDCC79A, "DYED"))
    for row, (yaw, pitch, opened, body, trim, label) in enumerate(views):
        for column, tier in enumerate(TIERS):
            model = models[f"{ASSET}/models/block/{tier.item}_{'open' if opened else 'closed'}.json"]
            render = render_model(model, rasters, yaw, pitch, 160, body, trim)
            sheet.paste(render, 15 + column * 170, 43 + row * 190)
            lettering(sheet, f"{tier.material} {label}", 15 + column * 170, 210 + row * 190, "D6D6C9")
    (output / "backpack-models.png").write_bytes(sheet.png())
    # Face views deliberately include underneath and top to expose missing faces.
    faces = Raster(6 * 164 + 20, 200, rgb("16212B"))
    for column, (yaw, pitch, label) in enumerate(((0, 0, "NORTH"), (90, 0, "EAST"), (180, 0, "SOUTH"), (-90, 0, "WEST"), (0, 89.99, "TOP"), (0, -89.99, "BOTTOM"))):
        faces.paste(render_model(models[f"{ASSET}/models/block/backpack_closed.json"], rasters, yaw, pitch, 154), 10 + column * 164, 12)
        lettering(faces, label, 15 + column * 164, 178)
    (output / "backpack-six-faces.png").write_bytes(faces.png())
    animation = Raster(5 * 170 + 20, 230, rgb("16212B"))
    lettering(animation, "ORIGINAL FLAP - OFFLINE INTERPOLATION REVIEW", 15, 12, "E3C69E")
    animation_frames = []
    tier = next(tier for tier in TIERS if tier.material == "gold")
    moving = set(flap_parts(tier))
    for index, progress in enumerate((0, .25, .5, .75, 1)):
        model = backpack_model(tier, False)
        angle = 45 * progress * progress * (3 - 2 * progress)
        for part in model["elements"]:
            if part["name"] in moving:
                part["rotation"] = {"origin": [8, 11.25, 11.75], "axis": "x", "angle": angle, "rescale": False}
        animation.paste(render_model(model, rasters, -32, 24, 160), 15 + index * 170, 36)
        lettering(animation, f"TICK {round(progress * 8)} OF 8", 23 + index * 170, 207, "D6D6C9")
        animation_frames.append({"tick": progress * 8, "angle_degrees": angle})
    (output / "flap-animation.png").write_bytes(animation.png())
    items = ["upgrade_base", *read_upgrade_ids(), *(value[2] for value in CONVERSIONS)]
    icons = Raster(8 * 144 + 12, math.ceil(len(items) / 8) * 103 + 46, rgb("16212B"))
    lettering(icons, "ORIGINAL UPGRADE ICONS - 16PX CLUSTERS", 12, 12, "E3C69E", 2)
    for index, item in enumerate(items):
        x, y = 12 + (index % 8) * 144, 42 + (index // 8) * 103
        icons.paste(rasters[f"{ASSET}/textures/item/{item}.png"], x + 33, y, 4)
        short = item.removesuffix("_upgrade").replace("_upgrade_", "_").replace("_conversion", "").replace("starter_tier", "starter")
        words = short.replace("_", " ")
        lettering(icons, words[:33], x, y + 70, "C8D7D4")
        if len(words) > 33:
            lettering(icons, words[33:], x, y + 79, "C8D7D4")
    (output / "upgrade-icons.png").write_bytes(icons.png())
    (output / "logo.png").write_bytes(logo().png())
    (output / "project-icon.png").write_bytes(rasters[PROJECT_ICON].png())
    report = {"kind": "offline_production_asset_render", "minecraft_client_capture": False,
              "model_views": 35, "upgrade_icons": len(items), "animation_frames": animation_frames,
              "images": ["backpack-models.png", "backpack-six-faces.png", "flap-animation.png", "upgrade-icons.png", "logo.png", "project-icon.png"]}
    report["images"].extend(automation.review(sys.modules[__name__], rasters, models, output))
    report["automation_model_views"] = 17
    (output / "review.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="check every declared output without changing resources")
    parser.add_argument("--review", action="store_true", help="render production models into build/reports/asset-audit")
    options = parser.parse_args()
    outputs, rasters, models = generate()
    stale = []
    for relative, contents in sorted(outputs.items()):
        path = ROOT / relative
        if options.check:
            if not path.exists() or path.read_bytes() != contents:
                stale.append(relative)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            if not path.exists() or path.read_bytes() != contents:
                path.write_bytes(contents)
    if stale:
        print("Generated resources are missing or stale:\n" + "\n".join(stale), file=sys.stderr)
        return 1
    print(f"{'Verified' if options.check else 'Generated'} {len(outputs)} deterministic asset/data files.")
    if options.review:
        print(f"Offline production-model review: {review_images(rasters, models)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
