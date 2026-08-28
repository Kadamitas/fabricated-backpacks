#!/usr/bin/env python3
"""Standalone resource/geometry/recipe regression checks; no game launch required.

python tools/test_assets.py --minecraft-jar <exact-target-client.jar>
The optional target JAR check reads official vanilla assets without copying them.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import struct
import sys
import unittest
from pathlib import Path
from zipfile import ZipFile

import generate_assets as assets


TARGET_JAR: Path | None = None


class AssetGenerationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.outputs, cls.rasters, cls.models = assets.generate()
        cls.upgrades = assets.read_upgrade_ids()
        cls.items = {tier.item for tier in assets.TIERS} | set(cls.upgrades) | {"upgrade_base"} | {row[2] for row in assets.CONVERSIONS}
        cls.recipes = assets.generate_recipes(cls.upgrades)

    def test_two_generations_are_byte_identical(self):
        another, _, _ = assets.generate()
        self.assertEqual(self.outputs.keys(), another.keys())
        for path, data in self.outputs.items():
            with self.subTest(path=path):
                self.assertEqual(data, another[path])

    def test_checked_in_resources_are_current(self):
        for path, expected in self.outputs.items():
            with self.subTest(path=path):
                target = assets.ROOT / path
                self.assertTrue(target.is_file(), f"Missing generated output: {path}")
                self.assertEqual(expected, target.read_bytes(), f"Stale generated output: {path}")

    def test_every_json_is_valid_and_owned(self):
        for path, content in self.outputs.items():
            with self.subTest(path=path):
                self.assertTrue(path.startswith(assets.ASSET + "/") or path.startswith(assets.DATA + "/")
                                or path == "src/main/resources/data/minecraft/tags/item/piglin_safe_armor.json"
                                or path == assets.PROJECT_ICON)
                if path.endswith(".json"):
                    self.assertIsInstance(json.loads(content), dict)

    def test_registry_resources_exactly_cover_catalog(self):
        self.assertEqual(71, len(self.items))
        definitions = {Path(path).stem for path in self.outputs if path.startswith(assets.ASSET + "/items/")}
        self.assertEqual(self.items, definitions)
        lang = json.loads(self.outputs[assets.ASSET + "/lang/en_us.json"])
        for item in self.items:
            self.assertTrue(lang[f"item.{assets.MOD}.{item}"].strip())
            if item not in {tier.item for tier in assets.TIERS}:
                self.assertTrue(lang[f"tooltip.{assets.MOD}.{item}"].strip())

    def test_model_face_closure_uvs_bounds_and_named_parts(self):
        required = {"body_floor", "body_left", "body_right", "body_back", "body_front", "front_pocket",
                    "flap_top", "flap_lip", "strap_left_long", "strap_right_long", "handle_top"}
        for path, model in self.models.items():
            names = set()
            with self.subTest(model=path):
                for element in model["elements"]:
                    self.assertNotIn(element["name"], names)
                    names.add(element["name"])
                    self.assertEqual(set(assets.SIDES), set(element["faces"]))
                    for i in range(3):
                        self.assertLess(element["from"][i], element["to"][i])
                    for side, face in element["faces"].items():
                        self.assertNotIn("cullface", face, "Sculpted internal faces must not cull against adjacent blocks")
                        self.assertEqual(4, len(face["uv"]))
                        self.assertTrue(all(math.isfinite(v) and 0 <= v <= 16 for v in face["uv"]))
                        self.assertLess(face["uv"][0], face["uv"][2])
                        self.assertLess(face["uv"][1], face["uv"][3])
                        self.assertIn(face.get("tintindex", -1), {-1, 0, 1})
                        resource = model["textures"][face["texture"].removeprefix("#")]
                        self.assertIn(f'{assets.ASSET}/textures/{resource.split(":")[1]}.png', self.outputs)
                        for vertex in assets.vertices(element, side):
                            self.assertTrue(all(math.isfinite(v) and -16 <= v <= 32 for v in vertex))
                    if "rotation" in element:
                        self.assertIn(element["rotation"]["axis"], {"x", "y", "z"})
                        self.assertIn(element["rotation"]["angle"], {-45, -22.5, 0, 22.5, 45})
                expected = required - {"flap_top", "flap_lip"} if path.endswith("_body.json") else required
                self.assertTrue(expected.issubset(names))

    def test_all_blockstates_and_item_color_layers(self):
        for tier in assets.TIERS:
            with self.subTest(tier=tier.item):
                state = json.loads(self.outputs[f"{assets.ASSET}/blockstates/{tier.item}.json"])["variants"]
                self.assertEqual(8, len(state))
                for facing, rotation in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
                    for opened in (False, True):
                        variant = state[f"facing={facing},open={str(opened).lower()}"]
                        self.assertEqual(rotation, variant["y"])
                        self.assertTrue(variant["model"].endswith("_body"))
                        self.assertIn(f'{assets.ASSET}/models/{variant["model"].split(":")[1]}.json', self.models)
                definition = json.loads(self.outputs[f"{assets.ASSET}/items/{tier.item}.json"])["model"]
                self.assertEqual("minecraft:model", definition["type"])
                self.assertEqual([0, 1], [tint["index"] for tint in definition["tints"]])
                self.assertTrue(all(tint["type"] == "minecraft:custom_model_data" for tint in definition["tints"]))

    def test_textures_have_explicit_dimensions_and_alpha(self):
        for path, raster in self.rasters.items():
            with self.subTest(texture=path):
                expected = 512 if path == assets.PROJECT_ICON else 128 if path.endswith("/icon.png") else 64 if "/entity/" in path else 16
                self.assertEqual((expected, expected), (raster.width, raster.height))
                self.assertEqual((expected, expected), struct.unpack(">II", raster.png()[16:24]))
                self.assertTrue(all(pixel[3] in {0, 255} for pixel in raster.pixels))
                if "/block/" in path or "/entity/" in path:
                    self.assertTrue(all(pixel[3] == 255 for pixel in raster.pixels))
                if "/textures/item/" in path:
                    self.assertEqual(0, raster.at(0, 0)[3])
                    self.assertGreater(sum(pixel[3] == 255 for pixel in raster.pixels), 130)
                    self.assertLess(sum(pixel[3] == 255 for pixel in raster.pixels), 230)

    def test_project_icon_is_original_model_branding_with_safe_pixel_clusters(self):
        icon = self.rasters[assets.PROJECT_ICON]
        self.assertEqual((512, 512), (icon.width, icon.height))
        self.assertTrue(all(pixel[3] == 255 for pixel in icon.pixels))
        self.assertGreater(len(set(icon.pixels)), 35)
        self.assertNotEqual(self.outputs[assets.PROJECT_ICON], self.outputs[f"{assets.ASSET}/icon.png"])
        for y in range(0, 512, 4):
            for x in range(0, 512, 4):
                self.assertTrue(all(icon.at(x + dx, y + dy) == icon.at(x, y) for dy in range(4) for dx in range(4)))
        corner = icon.at(0, 0)
        self.assertLess(corner[0], corner[1])
        self.assertLess(corner[0], corner[2])
        warm = sum(pixel[0] > pixel[1] * 1.15 and pixel[1] > pixel[2] * 1.15 for pixel in icon.pixels)
        self.assertGreater(warm, 512 * 512 * .2, "The production leather/fittings must remain the dominant foreground")
        self.assertLess(warm, 512 * 512 * .7, "The silhouette must leave breathing room on its dark teal field")
        manifest = json.loads(self.outputs[f"{assets.ASSET}/asset_manifest.json"])
        self.assertEqual({assets.PROJECT_ICON: hashlib.sha256(self.outputs[assets.PROJECT_ICON]).hexdigest()}, manifest["project_artifacts"])

    def test_icons_are_distinct_and_glyphs_fit_the_drawing_grid(self):
        icons = [data for path, data in self.outputs.items() if "/textures/item/" in path]
        self.assertEqual(65, len(icons))
        self.assertEqual(65, len({hashlib.sha256(data).hexdigest() for data in icons}))
        for name, glyph in assets.GLYPHS.items():
            with self.subTest(glyph=name):
                self.assertEqual(9, len(glyph))
                self.assertTrue(all(len(row) == 9 for row in glyph))

    def test_upgrade_progression_and_creative_exclusions(self):
        craftable = {value["result"]["id"].split(":")[1] for value in self.recipes.values() if "result" in value}
        self.assertEqual(self.items - {"infinity_upgrade", "stack_upgrade_omega_tier"}, craftable)
        for source, target, conversion in assets.CONVERSIONS:
            recipe = self.recipes[f"{conversion}_apply"]
            self.assertEqual([f"{assets.MOD}:{assets.stack_id(source)}", f"{assets.MOD}:{conversion}"], recipe["ingredients"])
            self.assertEqual(f"{assets.MOD}:{assets.stack_id(target)}", recipe["result"]["id"])

    def test_backpack_tier_recipes_and_loot_preserve_storage_ownership(self):
        for tier in assets.TIERS[1:]:
            recipe = self.recipes[tier.item]
            self.assertEqual(f"{assets.MOD}:backpack_smithing" if tier.material == "netherite" else f"{assets.MOD}:backpack_upgrade", recipe["type"])
        for tier in assets.TIERS:
            table = json.loads(self.outputs[f"{assets.DATA}/loot_table/blocks/{tier.item}.json"])
            self.assertEqual([], table["pools"])
        base = self.recipes["backpack"]
        flattened = "".join(base["pattern"])
        self.assertEqual(4, flattened.count("L"))
        self.assertEqual(4, flattened.count("S"))
        self.assertEqual(1, flattened.count("C"))
        self.assertEqual(4, "".join(self.recipes["iron_backpack_from_copper"]["pattern"]).count("I"))
        self.assertEqual({"type": f"{assets.MOD}:dye_backpack"}, self.recipes["dye_backpack"])

    def test_worn_profile_matches_production_cuboids_and_atlas_bounds(self):
        profile = json.loads(self.outputs[f"{assets.ASSET}/backpack_profiles.json"])
        self.assertEqual([64, 64], profile["atlas_size"])
        for tier in profile["tiers"]:
            model = self.models[f'{assets.ASSET}/models/block/{tier["id"]}_closed.json']
            self.assertEqual([part["name"] for part in model["elements"]], [part["name"] for part in tier["parts"]])
            for part, element in zip(tier["parts"], model["elements"]):
                self.assertEqual(element["from"], part["from"])
                self.assertEqual(element["to"], part["to"])
                width, height, depth = [part["to"][i] - part["from"][i] for i in range(3)]
                self.assertLessEqual(2 * (width + depth), 64, "CubeListBuilder unwrapped UV width")
                self.assertLessEqual(height + depth, 64, "CubeListBuilder unwrapped UV height")
                self.assertIn(part["texture"], profile["material_tints"])

    def test_worn_profile_fits_a_torso_with_armor_clearance(self):
        profile = json.loads(self.outputs[f"{assets.ASSET}/backpack_profiles.json"])
        transform = profile["wear_transform"]
        translate, scale = transform["translation_pixels"], transform["scale"]
        offset = profile["source_to_player_body"]["offset"]
        for tier in profile["tiers"]:
            points = [[translate[axis] + (offset[axis] - part[corner][axis]) * scale[axis] for axis in range(3)]
                      for part in tier["parts"] for corner in ("from", "to")]
            for x, y, z in points:
                self.assertGreaterEqual(x, -4)
                self.assertLessEqual(x, 4)
                self.assertGreaterEqual(y, 0)
                self.assertLessEqual(y, 12)
                self.assertGreater(z, 2)
                self.assertLessEqual(z, 8.5)
                self.assertGreater(z + transform["armor_clearance_pixels"], 3)

    def test_gold_extends_piglin_safety_without_replacing_other_armor(self):
        tag = json.loads(self.outputs["src/main/resources/data/minecraft/tags/item/piglin_safe_armor.json"])
        self.assertEqual({"replace": False, "values": ["fabricated_backpacks:gold_backpack"]}, tag)

    def test_utility_tool_rules_are_manual_and_resolve_native_resources(self):
        rules = {Path(path).stem: json.loads(data) for path, data in self.outputs.items() if "/backpack_tools/" in path}
        self.assertEqual({"shearing", "tilling", "flattening"}, set(rules))
        for name, rule in rules.items():
            with self.subTest(rule=name):
                self.assertTrue(rule["manual_only"])
                self.assertFalse(rule["require_correct_tool"])
                self.assertTrue(rule["items"])
                self.assertTrue(rule.get("blocks") or rule.get("entities"))
                self.assertEqual(100 if name == "shearing" else 10, rule["priority"])
                self.assertTrue(all(re.fullmatch(r"#?minecraft:[a-z0-9_/]+", value)
                                    for key in ("items", "blocks", "entities") for value in rule.get(key, [])))
        self.assertEqual(["minecraft:sheep", "minecraft:mooshroom", "minecraft:snow_golem"], rules["shearing"]["entities"])
        if TARGET_JAR is not None:
            with ZipFile(TARGET_JAR) as jar:
                names = set(jar.namelist())
                for rule in rules.values():
                    for item in rule["items"]:
                        resource = item.split(":", 1)[1]
                        path = f"data/minecraft/tags/item/{resource}.json" if item.startswith("#") else f"assets/minecraft/items/{resource}.json"
                        self.assertIn(path, names)
                    for block in rule.get("blocks", []):
                        self.assertIn(f"assets/minecraft/blockstates/{block.split(':', 1)[1]}.json", names)

    def test_static_body_and_animated_flap_exactly_partition_the_source(self):
        profile = json.loads(self.outputs[f"{assets.ASSET}/backpack_profiles.json"])
        hinge = profile["flap_hinge"]
        self.assertEqual({"origin": [8, 11.25, 11.75], "axis": "x", "closed_angle": 0, "open_angle": 45, "duration_ticks": 8}, hinge)
        for tier in profile["tiers"]:
            full = self.models[f'{assets.ASSET}/models/block/{tier["id"]}_closed.json']
            opened = self.models[f'{assets.ASSET}/models/block/{tier["id"]}_open.json']
            body = self.models[f'{assets.ASSET}/models/block/{tier["id"]}_body.json']
            full_parts = {part["name"]: part for part in full["elements"]}
            body_parts = {part["name"]: part for part in body["elements"]}
            moving = set(tier["flap_parts"])
            self.assertEqual(len(moving), len(tier["flap_parts"]))
            self.assertEqual(set(full_parts), set(body_parts) | moving)
            self.assertFalse(set(body_parts) & moving, "A moving flap must not also render in the chunk model")
            for name, part in body_parts.items():
                self.assertEqual(full_parts[name], part)
            for part in opened["elements"]:
                if part["name"] in moving:
                    self.assertEqual(hinge["origin"], part["rotation"]["origin"])
                    self.assertEqual(hinge["open_angle"], part["rotation"]["angle"])
            for progress in (0, .25, .5, .75, 1):
                angle = hinge["open_angle"] * progress * progress * (3 - 2 * progress)
                for name in moving:
                    part = {**full_parts[name], "rotation": {"origin": hinge["origin"], "axis": "x", "angle": angle}}
                    for side in assets.SIDES:
                        self.assertTrue(all(-16 <= coordinate <= 32 for point in assets.vertices(part, side) for coordinate in point))

    def test_offline_renders_are_nonempty_bounded_and_color_separated(self):
        # These checks detect broken geometry/render input; they do not replace
        # inspecting the image or testing the models in an actual game client.
        for tier in assets.TIERS:
            model = self.models[f"{assets.ASSET}/models/block/{tier.item}_closed.json"]
            standard = assets.render_model(model, self.rasters, size=72)
            dyed = assets.render_model(model, self.rasters, size=72, body=0x54839C, trim=0xDBC99B)
            self.assertNotEqual(standard.png(), dyed.png())
            occupied = sum(pixel != assets.rgb("202C36") for pixel in standard.pixels)
            self.assertGreater(occupied, 800)
            self.assertLess(occupied, 72 * 72 * .8)
            for x in range(72):
                self.assertEqual(assets.rgb("202C36"), standard.at(x, 0))
                self.assertEqual(assets.rgb("202C36"), standard.at(x, 71))

    def test_recipe_ingredients_and_parents_exist_in_exact_target(self):
        if TARGET_JAR is None:
            self.skipTest("Pass --minecraft-jar for exact-target vanilla-resource validation")
        with ZipFile(TARGET_JAR) as jar:
            names = set(jar.namelist())
            version = json.loads(jar.read("version.json"))
            self.assertEqual("26.2", version["id"])
            for recipe_id, recipe in self.recipes.items():
                ingredients = list(recipe.get("key", {}).values()) + recipe.get("ingredients", [])
                ingredients.extend(recipe[key] for key in ("template", "base", "addition") if key in recipe)
                for ingredient in ingredients:
                    with self.subTest(recipe=recipe_id, ingredient=ingredient):
                        self.assertIsInstance(ingredient, str)
                        namespace, item = ingredient.split(":")
                        if namespace == "minecraft":
                            self.assertIn(f"assets/minecraft/items/{item}.json", names)
                        else:
                            self.assertEqual(assets.MOD, namespace)
                            self.assertIn(item, self.items)
            self.assertIn("assets/minecraft/models/item/generated.json", names)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft-jar", type=Path)
    arguments, unittest_arguments = parser.parse_known_args()
    TARGET_JAR = arguments.minecraft_jar
    unittest.main(argv=[sys.argv[0], *unittest_arguments], verbosity=2)
