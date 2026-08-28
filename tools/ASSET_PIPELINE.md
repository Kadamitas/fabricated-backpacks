# Original asset pipeline

`generate_assets.py` uses Python 3.10+ and the standard library. It generates the
project's Minecraft cuboid models, blockstates, item definitions, pixel textures,
names, descriptions, crafting recipes, tool-selection rules, tags and deliberately empty block loot.
It also renders `docs/media/project-icon.png`, a 512×512 publishing icon showing
the original gold backpack on dark teal. The small in-game mod emblem is retained.
It does not fetch, read or depend on an external mod's artwork or code.

```powershell
python tools/generate_assets.py
python tools/generate_assets.py --check --review
python tools/test_assets.py --minecraft-jar '<Minecraft 26.2 client JAR>'
```

`--check` compares bytes without changing resources. The generator writes only
its declared output paths and never deletes files. The manifest stores SHA-256
hashes of all generated files except itself, plus the generator, UI-language
inputs and upgrade catalog. The Java resource audit checks those hashes without
requiring Python. `tools/assets/ui_strings.json` and
`tools/assets/browser_strings.json` are the explicit UI language inputs; edit
those files instead of the generated English language file.
Public branding is listed separately under `project_artifacts` in the manifest;
its only declared output is the project icon, not arbitrary documentation files.

## Materials and geometry

Body and trim have separate grayscale material textures and independent tint
indices, 0 and 1. Untinted material fittings distinguish all six tiers. The item
definitions read two `minecraft:custom_model_data` colors, with fallback colors
`#B97843` and `#503B36`. Placed-block rendering must supply the same two colors.

North is the front of a placed model. Eight facing/open variants per tier omit
waterlogged from the selector so both water states use the same geometry. Every
cuboid has all six opaque faces, with no neighboring-block culling. Full closed
and open models remain available for items, geometry comparison and review. The
open model rotates its flap by 45 degrees around `[8, 11.25, 11.75]`; shell walls
and the floor expose an actual lined interior. Open bounds may exceed one block
while remaining within Minecraft's `[-16, 32]` model coordinate range.

Placed blockstates render a separate static body model. The profile lists the
flap, tabs, buckles and applicable guard that the block-entity renderer bakes as
native cuboids in block coordinates. The two groups exactly partition the full
source model: no moving face remains in the chunk model. The client observes
the server's `OPEN` state, interpolates over eight ticks, and applies smoothstep
easing around the shared hinge. Body and trim colors apply to both groups.
Items and worn backpacks keep their closed shape. Animation progress is local
visual state; it never enters saved contents or a client-to-server payload.
The renderer uses normal section visibility and a conservative bound around
the full hinge sweep, with a maximum distance of 128 blocks capped by the
configured render distance. It does not register every backpack as global geometry.

`assets/fabricated_backpacks/backpack_profiles.json` contains the same named
cuboids for a Minecraft-native worn mesh. For each cuboid, `CubeListBuilder`
can use UV origin `[0, 0]` on the corresponding 64×64 material atlas. Every
unwrapped cube fits the atlas. Material tint indices and atlas resources are
explicit in the profile. The suggested coordinate map to player-body model
units is `native_from = [8,13.75,15.375] - source_to`, and
`native_to = [8,13.75,15.375] - source_from`; this maps the front outward and
places the straps behind the torso. The profile's `wear_transform` then applies
translation `[0,1,1]` in model pixels and scale `[0.72,0.72,0.55]`, plus one pixel
of clearance when chest armor is present. The runtime reads this same transform.
Bounds tests keep the mesh within the torso's width and height; armor, animation
and apparent proportions still require checking in the actual client.

The native item resolver draws exterior items against the front pocket on both
placed and worn packs. Model bounds determine icon size and contact depth;
rotation and bounded depth settings are applied afterward. A separate client
tooltip component renders immutable storage snapshots with full underlying
counts. Neither feature depends on a flat backpack sprite.

Upgrade icons are drawn as distinct 16×16 pixel clusters. A golden corner and
frame identify advanced variants; a small cyan return arrow identifies automatic
machines. Stack conversions show both source and destination tiers. Creative
infinity and omega upgrades have no survival recipe.

## Recipe and drop ownership

`fabricated_backpacks:backpack_upgrade` recipes use modern shaped recipe fields
plus a `source` item ID. Their serializer must copy the input backpack's complete
component state when changing tiers. `fabricated_backpacks:backpack_smithing`
uses `template`, `base`, `addition` and `result` and must preserve the base stack.
The generated tables intentionally do not substitute ordinary lossy crafting
for these transitions.

`fabricated_backpacks:dye_backpack` is a special recipe with no static result.
The runtime recipe computes body or trim colors from the actual input while
preserving all other backpack components.

Backpack block code is responsible for dropping the exact stored ItemStack.
The six generated block loot tables contain no pools, preventing a second,
uninitialized backpack from dropping alongside it.

The three generated `backpack_tools` rules describe manual shearing, tilling
and path-tool selection using native items, tags and targets. They select an
available physical tool; vanilla still performs its actual use interaction.
Resource audits check the schema and exact-target item, tag and block IDs.

## Visual review and acceptance

`--review` renders the production cuboids using their real textures and face UVs
with a small orthographic Z-buffer renderer. The review images appear in
`build/reports/asset-audit`: all tiers from the front and rear, opened and dyed,
six orthographic faces, five flap-interpolation frames, all upgrade icons, and
the logo. These images are offline renders, not Minecraft screenshots. Image
difference and nonempty-pixel checks detect regressions but do not establish
visual quality.

The publishing icon uses the same production gold model, face UVs and textures,
an original teal backdrop, and exact 4× pixel enlargement from a 128-pixel render.
It is branding artwork, not a game screenshot or evidence of runtime rendering.

Before release, inspect placed, opened, held, dropped, inventory and worn bags
in the actual 26.2 client, including armor, dyes, lighting and another player's
view. Check the bottom, sides and facing transitions. Inspect icons at ordinary
GUI scales and verify that resource loading produces no missing-model warnings.
For flap captures, an observing client can inspect
`BackpackBlockEntity.lidOpenness(1F)` after another viewer changes `OPEN`:
beginning, midpoint and end are 0, 0.5 and 1 after 0, 4 and 8 client ticks.
Verify both opening and closing, rapid reversal, each facing, breaking overlays
and resource reload. The offline frames do not replace these client checks.
