"""Original native automation cuboids, pixel materials and mechanically linked profiles.

Called by generate_assets.py; no independent writes or external image inputs.
Model coordinates are pixels, +Y up, with the engine's working side facing -Z.
"""
from __future__ import annotations

import copy
import math

ITEMS = ("item_conduit", "fluid_conduit", "energy_conduit", "steam_engine", "conduit_wrench")
KINDS = ("item", "fluid", "energy")
MATERIALS = ("iron", "brass", "dark", "firebox")
WHEEL_CENTER = (10.75, 8.0, 4.25)
CRANK_RADIUS = 1.25
ROD_LENGTH = 5.0
ROD_Z = 3.0


def rounded(values):
    return [round(value, 6) for value in values]


def box(api, name, start, end, material, rotation=None):
    return api.cuboid(name, rounded(start), rounded(end), material, None, rotation=rotation)


def ring(api, prefix, center, normal, outer, inner, depth, material):
    """Eight physical rim segments; the central hole remains real empty geometry."""
    parts = []
    plane = {"x": (1, 2), "y": (2, 0), "z": (0, 1)}[normal]
    axis = {"x": 0, "y": 1, "z": 2}[normal]
    for index in range(8):
        degrees = index * 45
        angle = math.radians(degrees)
        radial = (outer + inner) / 2
        point = list(center)
        point[plane[0]] += radial * math.cos(angle)
        point[plane[1]] += radial * math.sin(angle)
        width = 2 * outer * math.tan(math.pi / 8) + .03
        size = [0.0, 0.0, 0.0]
        size[axis] = depth
        size[plane[0]] = outer - inner
        size[plane[1]] = width
        # A symmetric box can exchange its axes instead of using unsupported
        # 90/135-degree JSON element rotations.
        quarter = (degrees + 45) // 90
        remainder = degrees - quarter * 90
        if quarter % 2:
            size[plane[0]], size[plane[1]] = size[plane[1]], size[plane[0]]
        pivot = rounded(point)
        rotation = {"origin": pivot, "axis": normal, "angle": remainder, "rescale": False} if remainder else None
        parts.append(box(api, f"{prefix}_{index}", [point[i] - size[i] / 2 for i in range(3)],
                         [point[i] + size[i] / 2 for i in range(3)], material, rotation))
    return parts


def engine_parts(api):
    body = []
    def add(name, start, end, material="iron", rotation=None):
        body.append(box(api, name, start, end, material, rotation))
    add("plinth", (.75, 0, .75), (15.25, 1.25, 15.25), "dark")
    add("plinth_cap", (1, 1.25, 1), (15, 1.75, 15), "iron")
    for x in (2.0, 7.0):
        add(f"boiler_foot_{int(x)}", (x, 1.75, 8), (x + 1, 4.5, 12.5), "dark")
    # Two crossed rectangles and four diagonal rectangles form a filled exact
    # octagon. Their inward halves overlap, with no gaps or square corner lobes.
    radius = 3.0
    tangent = radius * math.tan(math.pi / 8)
    add("boiler_horizontal", (1.5, 7.25 - tangent, 7.25), (8.25, 7.25 + tangent, 13.25))
    add("boiler_vertical", (1.5, 4.25, 10.25 - tangent), (8.25, 10.25, 10.25 + tangent))
    center_offset = (radius + 3 * tangent) / 4
    radial_depth = (radius - tangent) / math.sqrt(2)
    tangent_width = (radius - tangent) * math.sqrt(2)
    for index, (sy, sz) in enumerate(((1, 1), (1, -1), (-1, 1), (-1, -1))):
        center = (4.875, 7.25 + sy * center_offset, 10.25 + sz * center_offset)
        add(f"boiler_chamfer_{index}", (1.5, center[1] - radial_depth / 2, center[2] - tangent_width / 2),
            (8.25, center[1] + radial_depth / 2, center[2] + tangent_width / 2), "iron",
            {"origin": rounded(center), "axis": "x", "angle": 45 if sy == sz else -45, "rescale": False})
    for x, name in ((1.9, "rear_band"), (7.85, "front_band")):
        body.extend(ring(api, name, (x, 7.25, 10.25), "x", 3.2, 2.6, .7, "brass"))
    add("firebox", (2.5, 2.0, 8.25), (7.0, 4.5, 12.25), "dark")
    add("firebox_door", (2.35, 2.3, 8.8), (2.5, 4.15, 11.7), "firebox")
    add("chimney_base", (2.75, 10, 9.25), (5.25, 10.75, 11.75), "brass")
    add("chimney_north", (3, 10.5, 9.5), (5, 15, 9.9), "dark")
    add("chimney_south", (3, 10.5, 11.1), (5, 15, 11.5), "dark")
    add("chimney_west", (3, 10.5, 9.9), (3.4, 15, 11.1), "dark")
    add("chimney_east", (4.6, 10.5, 9.9), (5, 15, 11.1), "dark")
    # Four rim walls retain the chimney opening instead of painting a black top.
    for name, start, end in (
            ("north", (2.75, 14.5, 9.25), (5.25, 15.4, 9.9)),
            ("south", (2.75, 14.5, 11.1), (5.25, 15.4, 11.75)),
            ("west", (2.75, 14.5, 9.9), (3.4, 15.4, 11.1)),
            ("east", (4.6, 14.5, 9.9), (5.25, 15.4, 11.1))):
        add("chimney_rim_" + name, start, end, "iron")
    add("steam_pipe_riser", (6.2, 8.0, 6), (7.0, 11, 6.8), "brass")
    add("steam_pipe_to_boiler", (6.2, 8.0, 6), (7.0, 8.8, 7.6), "brass")
    add("steam_pipe_top", (3, 10.2, 6), (7, 11, 6.8), "brass")
    add("steam_pipe_drop", (2.6, 8.25, 3), (3.4, 11, 3.8), "brass")
    add("steam_pipe_elbow", (2.6, 10.2, 3), (3.4, 11, 6.8), "brass")
    add("cylinder_mount", (1.5, 1.75, 2), (4.5, 6.85, 4), "dark")
    add("working_cylinder", (1.5, 7, 2), (4.5, 9, 4), "iron")
    add("cylinder_cap", (4.35, 6.9, 1.9), (4.8, 9.1, 4.1), "brass")
    add("cylinder_rear_cap", (1.2, 6.9, 1.9), (1.7, 9.1, 4.1), "brass")
    add("slide_rail", (4.5, 7.1, 2.4), (7.5, 7.45, 3.6), "dark")
    add("axle_support", (9.85, 1.75, 5.25), (11.65, 8.65, 6.75), "dark")
    add("axle_bearing", (9.5, 7.05, 4.85), (12, 8.95, 6.9), "brass")
    add("output_socket", (10, 1.65, 13.75), (12, 3.65, 15.4), "brass")
    add("water_socket", (.6, 5.5, 9.25), (1.6, 7.5, 11.25), "brass")
    for x in (1.75, 13.5):
        for z in (1.75, 13.5):
            add(f"bolt_{int(x)}_{int(z)}", (x, 1.65, z), (x + .65, 2.0, z + .65), "dark")

    wheel = ring(api, "flywheel_rim", (0, 0, 0), "z", 4.1, 3.5, .8, "brass")
    # Four continuous bars form eight thin spokes; large open sectors remain.
    for i, angle in enumerate((0, 45, 90, 135)):
        start, end = (-3.6, -.18, -.20), (3.6, .18, .20)
        rotation = None
        if angle == 90:
            start, end = (-.18, -3.6, -.20), (.18, 3.6, .20)
        elif angle == 135:
            start, end = (-.18, -3.6, -.20), (.18, 3.6, .20)
            rotation = {"origin": [0, 0, 0], "axis": "z", "angle": 45, "rescale": False}
        elif angle:
            rotation = {"origin": [0, 0, 0], "axis": "z", "angle": angle, "rescale": False}
        wheel.append(box(api, f"flywheel_spoke_{i}", start, end, "iron", rotation))
    wheel.append(box(api, "flywheel_hub", (-.75, -.75, -.65), (.75, .75, .85), "brass"))
    wheel.append(box(api, "flywheel_axle", (-.30, -.30, -.85), (.30, .30, 2), "iron"))
    wheel.append(box(api, "crank_web", (0, -.35, -.85), (1.5, .35, -.45), "dark"))
    wheel.append(box(api, "crank_pin", (1.0, -.25, -1.5), (1.5, .25, -.45), "brass"))
    rod = [box(api, "connecting_rod", (0, -.22, -.18), (ROD_LENGTH, .22, .18), "iron"),
           box(api, "rod_slider_eye", (-.38, -.38, -.22), (.38, .38, .22), "brass"),
           box(api, "rod_crank_eye", (ROD_LENGTH - .38, -.38, -.22), (ROD_LENGTH + .38, .38, .22), "brass")]
    piston = [box(api, "piston_shaft", (-3, -.18, -.18), (.25, .18, .18), "iron"),
              box(api, "crosshead", (-.35, -.40, -.40), (.25, .40, .40), "dark")]
    return {"body": body, "wheel": wheel, "rod": rod, "piston": piston}


def linkage(angle):
    pin_x = WHEEL_CENTER[0] + CRANK_RADIUS * math.cos(angle)
    pin_y = WHEEL_CENTER[1] + CRANK_RADIUS * math.sin(angle)
    slider_x = pin_x - math.sqrt(ROD_LENGTH ** 2 - (pin_y - WHEEL_CENTER[1]) ** 2)
    rod_angle = math.atan2(pin_y - WHEEL_CENTER[1], pin_x - slider_x)
    return pin_x, pin_y, slider_x, rod_angle


def placed_parts(parts, offset, angle=0):
    result = copy.deepcopy(parts)
    for part in result:
        # Review frames may use arbitrary rotation angles; shipped item JSON
        # uses the exact rest pose (angle zero) and native allowed rotations.
        if angle and "rotation" in part:
            part["rotation"]["angle"] += math.degrees(angle)
        elif angle:
            part["rotation"] = {"origin": [0, 0, 0], "axis": "z", "angle": math.degrees(angle), "rescale": False}
        if angle and part["rotation"]["origin"] != [0, 0, 0]:
            # Rim parts rotate about their own center at rest; transform the
            # center then rotate its already-oriented box about that center.
            center = part["rotation"]["origin"]
            c, s = math.cos(angle), math.sin(angle)
            moved = [center[0] * c - center[1] * s, center[0] * s + center[1] * c, center[2]]
            delta = [moved[i] - center[i] for i in range(3)]
            for key in ("from", "to"):
                part[key] = [part[key][i] + delta[i] for i in range(3)]
            part["rotation"]["origin"] = moved
        for key in ("from", "to"):
            part[key] = rounded([part[key][i] + offset[i] for i in range(3)])
        if "rotation" in part:
            part["rotation"]["origin"] = rounded([part["rotation"]["origin"][i] + offset[i] for i in range(3)])
    return result


def engine_model(api, parts, angle=0, body_only=False):
    elements = copy.deepcopy(parts["body"])
    if not body_only:
        _, _, slider, rod_angle = linkage(angle)
        elements += placed_parts(parts["wheel"], WHEEL_CENTER, angle)
        elements += placed_parts(parts["rod"], (slider, WHEEL_CENTER[1], ROD_Z), rod_angle)
        elements += placed_parts(parts["piston"], (slider, WHEEL_CENTER[1], ROD_Z))
    return {"ambientocclusion": True, "textures": {**{name: f"{api.MOD}:block/automation/engine_{name}" for name in MATERIALS},
                                                   "particle": f"{api.MOD}:block/automation/engine_iron"},
            "display": {"gui": {"rotation": [28, 220, 0], "translation": [0, 0, 0], "scale": [.72, .72, .72]},
                        "ground": {"translation": [0, 3, 0], "scale": [.35, .35, .35]},
                        "fixed": {"rotation": [0, 180, 0], "scale": [.75, .75, .75]}},
            "elements": elements}


def material(api, kind):
    colors = {"iron": ("4D575C", "7F8C90", "AAB5B4", "D8DDD0"),
              "brass": ("6B4B29", "A87938", "D6AF59", "F0D793"),
              "dark": ("222A2F", "36434A", "526168", "73818A"),
              "firebox": ("242529", "41444A", "616165", "8A7862")}[kind]
    image = api.Raster(16, 16, api.rgb(colors[1]))
    image.rect(0, 0, 16, 1, colors[2]); image.rect(0, 0, 1, 16, colors[2])
    image.rect(0, 15, 16, 16, colors[0]); image.rect(15, 0, 16, 16, colors[0])
    for y in (4, 10):
        for x in (3, 10):
            image.rect(x, y, x + 3, y + 1, colors[2 if (x + y) % 2 else 0])
    if kind == "firebox":
        for y in (4, 7, 10):
            image.rect(3, y, 13, y + 1, colors[0])
        image.rect(11, 4, 13, 12, colors[3])
    return image


def conduit_texture(api, kind, role):
    palettes = {"item": ("C9D2CF", "E8E9DD", "397E83", "245259"),
                "fluid": ("34475D", "657697", "42A6C4", "21313F"),
                "energy": ("963D39", "D45E4D", "E3A85E", "432C30")}
    base, light, stripe, dark = palettes[kind]
    image = api.Raster(16, 16, api.rgb(base if role == "tube" else dark))
    if role == "tube":
        image.rect(0, 0, 16, 2, light); image.rect(0, 14, 16, 16, dark)
        image.rect(0, 6, 16, 10, stripe)
        image.rect(0, 6, 16, 7, light)
    else:
        image.rect(1, 1, 15, 15, base)
        image.rect(2, 2, 14, 14, dark)
        image.rect(3, 3, 13, 13, stripe)
        image.rect(5, 5, 11, 11, base)
        if role.startswith("endpoint"):
            image.rect(2, 2, 14, 14, dark)
            # Distinct, discrete mode indicators; these never imply flowing items.
            inward = role in ("endpoint_insert", "endpoint_both")
            outward = role in ("endpoint_extract", "endpoint_both")
            for direction, on, color in ((1, inward, "83C46D"), (-1, outward, "E7AF5B")):
                if on:
                    for y in range(4, 12):
                        x = 6 + (y - 4 if y < 8 else 11 - y) // 2
                        if direction < 0: x = 15 - x
                        image.rect(x, y, x + 2, y + 1, color)
    return image


def conduit_model(api, kind):
    return {"ambientocclusion": True,
            "textures": {"tube": f"{api.MOD}:block/automation/{kind}_tube", "collar": f"{api.MOD}:block/automation/{kind}_collar",
                         "particle": f"{api.MOD}:block/automation/{kind}_tube"},
            "display": {"gui": {"rotation": [28, 225, 0], "scale": [1, 1, 1]},
                        "ground": {"translation": [0, 3, 0], "scale": [.5, .5, .5]},
                        "fixed": {"rotation": [0, 90, 0], "scale": [.8, .8, .8]},
                        "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [.4, .4, .4]},
                        "firstperson_lefthand": {"rotation": [0, 225, 0], "scale": [.4, .4, .4]}},
            # The opaque collars cover the inset caps; two materials never compete on one outer plane.
            "elements": [box(api, "pipe", (6.75, 6.75, .125), (9.25, 9.25, 15.875), "tube"),
                         box(api, "collar_north", (6.25, 6.25, 0), (9.75, 9.75, 1.25), "collar"),
                         box(api, "collar_south", (6.25, 6.25, 14.75), (9.75, 9.75, 16), "collar")]}


def generate(api, put_json, put_png, models):
    for name in MATERIALS:
        image = material(api, name)
        put_png(f"{api.ASSET}/textures/block/automation/engine_{name}.png", image)
        put_png(f"{api.ASSET}/textures/entity/automation/engine_{name}.png", api.entity_atlas(image))
    parts = engine_parts(api)
    for suffix, body_only in (("", False), ("_body", True)):
        path = f"{api.ASSET}/models/block/steam_engine{suffix}.json"
        value = engine_model(api, parts, body_only=body_only)
        put_json(path, value); models[path] = value
    put_json(f"{api.ASSET}/blockstates/steam_engine.json", {"variants": {
        f"facing={face},active={active}": {"model": f"{api.MOD}:block/steam_engine_body", "y": angle}
        for face, angle in (("north", 0), ("east", 90), ("south", 180), ("west", 270)) for active in ("false", "true")}})
    put_json(f"{api.ASSET}/models/item/steam_engine.json", {"parent": f"{api.MOD}:block/steam_engine"})
    put_json(f"{api.ASSET}/steam_engine_profiles.json", {
        "schema": 1, "coordinate_system": "model pixels; +Y up; engine front -Z; wheel axis +Z",
        "atlas_size": [64, 64], "wheel_center": list(WHEEL_CENTER), "crank_radius": CRANK_RADIUS,
        "rod_length": ROD_LENGTH, "rod_z": ROD_Z, "phase_units": "radians", "animation": "client ACTIVE ticks only; hold phase while stopped",
        "material_textures": {name: f"{api.MOD}:entity/automation/engine_{name}" for name in MATERIALS},
        "groups": {name: [{"name": part["name"], "from": part["from"], "to": part["to"],
                            "texture": part["faces"]["north"]["texture"].removeprefix("#"),
                            **({"rotation": part["rotation"]} if "rotation" in part else {})} for part in group]
                   for name, group in parts.items()}})
    for kind in KINDS:
        for role in ("tube", "collar", "endpoint", "endpoint_insert", "endpoint_extract", "endpoint_both"):
            put_png(f"{api.ASSET}/textures/block/automation/{kind}_{role}.png", conduit_texture(api, kind, role))
        path = f"{api.ASSET}/models/item/{kind}_conduit.json"
        model = conduit_model(api, kind); models[path] = model; put_json(path, model)
    fallback = conduit_model(api, "item")
    fallback["elements"] = [box(api, "unloaded_core", (6.25, 6.25, 6.25), (9.75, 9.75, 9.75), "collar")]
    # Texture aliases make every dynamic material part of the normal atlas bake.
    fallback["textures"].update({f"{kind}_{role}": f"{api.MOD}:block/automation/{kind}_{role}"
                                for kind in KINDS for role in ("tube", "collar", "endpoint", "endpoint_insert", "endpoint_extract", "endpoint_both")})
    path = f"{api.ASSET}/models/block/conduit_bundle.json"
    models[path] = fallback; put_json(path, fallback)
    put_json(f"{api.ASSET}/blockstates/conduit_bundle.json", {"variants": {"": {"model": f"{api.MOD}:block/conduit_bundle"}}})
    wrench = {"parent": "minecraft:item/handheld", "textures": {"layer0": f"{api.MOD}:item/conduit_wrench"}}
    image = api.Raster(16, 16)
    for x, y in ((4, 10), (5, 9), (6, 8), (7, 7), (8, 6)):
        image.rect(x, y, x + 3, y + 3, "6A4C32"); image.put(x + 1, y, "D7AF63")
    image.rect(2, 12, 5, 15, "3D5259"); image.rect(3, 12, 4, 14, "93B0AE")
    image.rect(8, 2, 10, 7, "C3D0CC"); image.rect(8, 5, 13, 8, "829C9C")
    image.rect(12, 2, 14, 6, "C3D0CC"); image.rect(9, 6, 12, 7, "E2E6D7")
    put_png(f"{api.ASSET}/textures/item/conduit_wrench.png", image)
    put_json(f"{api.ASSET}/models/item/conduit_wrench.json", wrench)
    for block in ("steam_engine", "conduit_bundle"):
        put_json(f"{api.DATA}/loot_table/blocks/{block}.json", {"type": "minecraft:block", "pools": []})
    put_json(f"{api.DATA}/tags/item/conduits.json", {"replace": False, "values": [f"{api.MOD}:{kind}_conduit" for kind in KINDS]})
    put_json("src/main/resources/data/minecraft/tags/block/mineable/pickaxe.json",
             {"replace": False, "values": [f"{api.MOD}:conduit_bundle", f"{api.MOD}:steam_engine"]})
    for name, recipe in recipes(api).items():
        put_json(f"{api.DATA}/recipe/{name}.json", recipe)


def recipes(api):
    def shaped(pattern, key, result, count=1):
        return {"type": "minecraft:crafting_shaped", "category": "redstone", "pattern": pattern,
                "key": {symbol: api.ingredient("minecraft:" + item) for symbol, item in key.items()},
                "result": {"id": api.MOD + ":" + result, "count": count}}
    return {
        "item_conduit": shaped(["III", "RGR", "III"], {"I": "iron_ingot", "R": "redstone", "G": "glass"}, "item_conduit", 8),
        "fluid_conduit": shaped(["CCC", "GGG", "CCC"], {"C": "copper_ingot", "G": "glass"}, "fluid_conduit", 8),
        "energy_conduit": shaped(["CCC", "RRR", "CCC"], {"C": "copper_ingot", "R": "redstone"}, "energy_conduit", 8),
        "steam_engine": shaped(["IFI", "CBC", "III"], {"I": "iron_ingot", "F": "furnace", "C": "copper_ingot", "B": "bucket"}, "steam_engine"),
        "conduit_wrench": shaped(["I I", " C ", " C "], {"I": "iron_ingot", "C": "copper_ingot"}, "conduit_wrench"),
    }


def review(api, rasters, models, output):
    sheet = api.Raster(4 * 220 + 20, 2 * 250 + 40, api.rgb("16212B"))
    api.lettering(sheet, "ORIGINAL STEAM ENGINE - OFFLINE GEOMETRY REVIEW", 15, 12, "E3C69E")
    parts = engine_parts(api)
    for index, (yaw, pitch) in enumerate(((-28, 20), (40, 18), (150, 18), (-90, 0))):
        for row, phase in enumerate((0, math.pi / 2)):
            model = engine_model(api, parts, phase)
            sheet.paste(api.render_model(model, rasters, yaw, pitch, 210), 10 + index * 220, 35 + row * 250)
            api.lettering(sheet, f"VIEW {index + 1} PHASE {row * 90}", 20 + index * 220, 249 + row * 250)
    (output / "steam-engine-models.png").write_bytes(sheet.png())
    face_sheet = api.Raster(6 * 170 + 20, 210, api.rgb("16212B"))
    model = models[f"{api.ASSET}/models/block/steam_engine.json"]
    for index, (yaw, pitch, label) in enumerate(((0, 0, "NORTH"), (90, 0, "EAST"), (180, 0, "SOUTH"), (-90, 0, "WEST"), (0, 89.99, "TOP"), (0, -89.99, "BOTTOM"))):
        face_sheet.paste(api.render_model(model, rasters, yaw, pitch, 160), 10 + index * 170, 8)
        api.lettering(face_sheet, label, 20 + index * 170, 180)
    (output / "steam-engine-six-faces.png").write_bytes(face_sheet.png())
    conduit_sheet = api.Raster(3 * 180 + 20, 215, api.rgb("16212B"))
    api.lettering(conduit_sheet, "ORIGINAL CONDUITS - OFFLINE ITEM REVIEW", 10, 12, "E3C69E")
    for index, kind in enumerate(KINDS):
        conduit_sheet.paste(api.render_model(models[f"{api.ASSET}/models/item/{kind}_conduit.json"], rasters, -32, 22, 160),
                            10 + index * 180, 30)
        api.lettering(conduit_sheet, kind.upper(), 25 + index * 180, 195)
    (output / "conduit-items.png").write_bytes(conduit_sheet.png())
    return ["steam-engine-models.png", "steam-engine-six-faces.png", "conduit-items.png"]
