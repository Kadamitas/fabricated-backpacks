# Configuration and administration

Fabricated Backpacks reads `config/fabricated_backpacks.json` at startup and
creates a complete default file if none exists. Stop the dedicated server, or
close Minecraft completely for single-player, then back up the world/configuration,
edit the file and restart that process. Leaving and reopening a single-player
world does not reload this file. There is no configuration-reload command.
`/reload` applies data packs, not this JSON file. The server sends relevant rules
and actual saved geometry to clients; a client's local file cannot raise a
server limit.

Files use format `2`, are limited to 1 MiB and may contain only the fields
described below. Partial files inherit defaults. Unknown fields, invalid
values, nulls and unsupported formats reject the new configuration as a whole.
The error is logged, the file remains untouched and the previous configuration
is retained; on initial startup that previous configuration is the defaults.
Check the log after editing.

Format-1 files remain readable. Their historical advanced-jukebox default of
`size:12` is interpreted as 24 slots; other configured sizes are preserved.
Set `format` to `2` before deliberately choosing 12 slots under the new format.

This document describes implemented settings, not completed testing. See
[Verification](VERIFICATION.md) for executed checks.

## Example partial file

```json
{
  "format": 2,
  "capacities": {
    "backpack": {"slots": 36, "upgrades": 2}
  },
  "storage": {
    "onlyWornUpgrades": true,
    "disallowedItems": ["minecraft:barrier"]
  },
  "upgrades": {
    "filters": {
      "advanced_pickup_upgrade": {"slots": 64, "columns": 6}
    },
    "jukebox": {"size": 200, "rowWidth": 6},
    "magnet": {"range": 3, "advancedRange": 5, "activeTicks": 10, "idleTicks": 40},
    "allowAlwaysVoid": false
  }
}
```

Use item path names without `fabricated_backpacks:` inside `capacities`,
`upgrades.filters`, `upgrades.itemLimits` and `upgrades.stack.multipliers`.
Selector lists use full registry IDs such as `minecraft:diamond`, or registry
tags such as `#minecraft:logs` where specified. Item selectors and block/entity
selectors are not interchangeable.

## Storage geometry

| `capacities` key | Default `slots` | Default `upgrades` |
| --- | ---: | ---: |
| `backpack` | 27 | 1 |
| `copper_backpack` | 45 | 1 |
| `iron_backpack` | 54 | 2 |
| `gold_backpack` | 81 | 3 |
| `diamond_backpack` | 108 | 5 |
| `netherite_backpack` | 120 | 7 |

Each tier accepts 1–144 item cells and 0–10 upgrade slots. Grids use nine
columns at up to 81 cells and twelve above 81. Widening preserves existing
row/column positions and associated memory, exclusions, display selection and
capture reservations. Saved dimensions and high slots are retained when a
default shrinks. Tank/battery columns and captured entities reserve cells
inside this geometry; they do not grant additional ordinary storage.

### `storage`

| Key | Default | Meaning |
| --- | --- | --- |
| `itemFluidAccess` | `true` | Allow fluid access through item-held backpack contexts |
| `shareWornBackpacks` | `true` | Server permission for sharing a worn bag; the owner's sharing preference must also allow it |
| `displayItems` | `true` | Show the selected exterior item |
| `onlyWornUpgrades` | `false` | Restrict player-carried upgrade work to the native equipped bag |
| `outerUsesChildren` | `true` | Permit outer Inception upgrades to process child inventories |
| `childUpgrades` | `true` | Permit upgrades inside legal nested bags to run |
| `allowBagInContainerItems` | `false` | Permit backpacks inside supported container items |
| `disallowContainerItems` | `false` | Reject container items from backpack storage |
| `disallowedItems` | `[]` | Item IDs or item tags forbidden from insertion |
| `blockedInteractions` | `[]` | Block IDs or block tags excluded from backpack interactions |
| `blockedConnections` | `[]` | Block IDs or block tags excluded from external connections |
| `disableConnections` | `false` | Disable external block connections |
| `disableDuplicateChecks` | `false` | Disable periodic identity-collision repair; normally leave checks enabled |

Inception also has saved per-backpack processing/order switches. Both server
permission and that bag's setting must allow the operation. Connections apply
to native hopper and supported API routes, including nested bags.

`storage.burden` defaults to `{"enabled":false,"freeBackpacks":3,
"levelsPerExtra":1,"effect":"minecraft:slowness"}`. It counts directly carried
and equipped backpacks, not the contents of nested bags. `freeBackpacks` is
0–144; `levelsPerExtra` is 1–10. The applied level is capped at 10. The configured
effect must resolve in the current effect registry. The short effect expires
naturally; lowering the count does not strip unrelated potion effects.

Identity repair assigns distinct IDs to separate physical stacks; it does not
delete their contents or assume that equal items were duplicated. It inspects
loaded player equipment/inventories, legal nested bags and bounded nearby drops
on a 20-tick cadence. It is not an unloaded-world scan.

## Upgrade limits and layouts

`upgrades.itemLimits` contains each of the 54 installable upgrade item IDs.
`upgrades.groupLimits` contains family names (`pickup`, `filter`, `magnet`,
`feeding`, `compacting`, `void`, `restock`, `deposit`, `refill`, `inception`,
`everlasting`, `cooking`, `crafting`, `stonecutter`, `jukebox`, `tool_swapper`,
`tank`, `battery`, `pump`, `xp_pump`, `anvil`, `smithing`, `infinity`, `alchemy`,
`mob_catcher`, `stack`). Values are 0–10; zero prevents new installation. Both
per-item and family limits apply. Defaults are one, except stack modifiers
allow three and tanks two. Hard constraints still apply: at most two tanks,
one battery, one legal nesting layer, and Infinity's incompatibilities.

Existing installed items are not silently deleted when a limit is lowered.
Unsafe removal/replacement that would strand items or resources is rejected.

`upgrades.filters.<item>.slots` accepts 1–64 and `.columns` accepts 1–6 for
upgrades with ordinary filters. Basic layouts default to three columns,
advanced layouts to four. Automatic cooking uses its separate settings below.

| Family | Basic filter count | Advanced filter count |
| --- | ---: | ---: |
| Pickup, filter, magnet, feeding, compacting, void, restock, deposit | 9 | 16 |
| Refill | 6 | 12 |
| Alchemy | 4 | 8 |
| Tool swapper | None | 8 |
| Pump | None | 4 |

Saved filters remain accessible if defaults shrink. Real record inventories
also retain owned high slots. Ghost filters copy choices, not item quantities.
An empty allow list normally accepts nothing; an empty block list normally
accepts everything otherwise eligible. Automatic cooking fuel has its explicit
empty-allow-list any-fuel exception. Void starts with an empty allow list.

## Upgrade behavior settings

All following paths are below `upgrades`. Tick values accept 1–1200 unless a
different bound is shown; 20 ticks is one second at normal server speed.

| Section | Keys and defaults | Bounds/behavior |
| --- | --- | --- |
| `cooking` | `speed:1`, `fuelEfficiency:1` | Each 0.25–4. Item settings cannot override these server values |
| `cooking` | `inputFilters:8`, `fuelFilters:4`, `filterColumns:4` | Each filter category 1–32; columns 1–6. Existing categories/template rows are remapped separately |
| `cooking` | `retryMinimum:10`, `retryMaximum:60`, `idleTicks:10` | Separate input/fuel/output retry backoff, with maximum at least minimum |
| `compacting` | `interval:5`, `maximumOperations:64` | Operations 1–256 per bounded work call |
| `magnet` | `range:3`, `advancedRange:5`, `activeTicks:10`, `idleTicks:40` | Radii 1–32; item and XP work have separate deadlines |
| `feeding` | `range:3`, `idleTicks:100`, `hungryTicks:10` | Radius 1–32; shorter delay after a feed that leaves the player hungry |
| `alchemy` | `range:3`, `interval:5` | Radius 1–32; consumables still take their actual use duration |
| `refill` | `range:3`, `interval:5` | Radius 1–32 for placed bags |
| `tank` | `capacityPerRow:4000`, `stackRatio:1`, `transferPerRow:20`, `minimumTransfer:1000`, `containerTicks:20` | Capacity 500–20000 mB/row; ratio 0–1; transfer/minimum each 1–20000 mB |
| `battery` | `capacityPerRow:10000`, `stackRatio:1`, `transferPerRow:20` | Capacity 500–50000 energy/row; ratio 0–1; transfer 1–50000/row |
| `pump` | `playerRange:3`, `worldRange:4` | Player radius 1–32; world search range 1–16 |
| `pump` | `handTicks:3`, `handlerTicks:20`, `idleTicks:40`, `handGraceTicks:60` | Grace 0–1200; world work also accounts for distance |
| `experience` | `range:3`, `interval:5`, `transferPoints:50`, `allowMending:true`, `mendingPoints:5` | Radius 1–32; transfer 1–10000 XP points; mending 1–20. Saved player budgets are capped by these settings |
| `jukebox` | `size:24`, `rowWidth:4` | Advanced size 1–256, columns 1–6. The paged UI supports 200-disc libraries; basic holds two records |
| `allowAlwaysVoid` | `true` | If false, a saved ALWAYS setting falls back to storage-overflow behavior |

Tank and battery resource capacity/rate use the resolved backpack row count
and stack multiplier. `stackRatio:0` removes stack-upgrade scaling; `1` applies
it fully, with intermediate values interpolating its contribution. Rates do
not make a full tank or battery accept more than its capacity. Existing excess
after changed configuration can be extracted without being discarded.

Placed batteries additionally share their configured output rate across
neighboring receivers per server tick. The per-backpack **External energy
output** switch defaults to On; Off allows external charging but prevents
external discharge. Item charging/discharging remains separate. See
[Item, fluid and energy integration](INTEGRATION.md) for the shared APIs,
equipment context and connection rules in the unreleased working revision.

### Stack multipliers

`upgrades.stack.baseMultiplier` defaults to 1 and accepts 1/64–64.
`multipliers` contains all nine stack modifier IDs, each accepting 1/64 through
2,147,483,647. Installed factors multiply together with the base. Item capacity
is bounded to an integer, with a minimum of one. Backpack carrier items remain
single items per cell.

| Modifier | Default factor |
| --- | ---: |
| `stack_upgrade_starter_tier` | 1.5 |
| `stack_upgrade_tier_1` / `_2` / `_3` / `_4` | 2 / 4 / 8 / 16 |
| `stack_downgrade_tier_1` / `_2` / `_3` | 0.125 / 0.0625 / 0.03125 |
| `stack_upgrade_omega_tier` | 2147483647 |

`excludedItems` defaults to `[]` and accepts item IDs/tags that should not gain
enhanced stack capacity. Lowering capacity does not erase existing excess.

### Compacting shapes

`compacting.extraShapes` defaults to
`[{"width":3,"height":3,"pattern":"111101111"}]`. Patterns run left-to-right,
top-to-bottom; `1` places the ingredient and `0` leaves the cell empty. Each
dimension is 1–3, with at least two occupied cells. Lists accept up to 64 shapes.
The basic upgrade still cannot use shapes exceeding 2×2.

`compacting.itemOverrides` maps full item IDs to shape lists, up to 1024 items.
For example, `{"example:material":[{"width":2,"height":2,"pattern":"1110"}]}`
selects a three-cell shape for that item. Shapes require actual current recipes;
they do not define outputs or bypass the reversible-recipe check. Every selected
recipe must have an exact one-item unpacking recipe that restores the same item,
components and quantity. Both directions must be free of crafting remainders.
Legacy “compact anything” data is ignored, so lossy recipes such as iron ingots
into iron trapdoors are never used.

## Capture and monster carriers

`capture.passiveLimit` defaults to 18 and `hostileLimit` to 72; each accepts
1–120 reserved cells. `excludeInventories` defaults to false. `blockedEntities`,
`passiveEntities` and `hostileEntities` default to empty entity ID/tag lists;
the latter two augment classification. A limit does not truncate an entity's
required rectangular footprint. Normal eligibility, available cells and safe
release/collision checks still apply.

### `carriers`

Tier arrays are ordered leather, copper, iron, gold, diamond, netherite.

| Key | Default | Allowed values/meaning |
| --- | --- | --- |
| `spawnChance` | `0.01` | Probability 0–1 for eligible monster spawns |
| `tierWeights` | `[625,250,125,25,5,1]` | Six weights 0–1000000; at least one positive |
| `midDifficulty`, `highDifficulty` | `2`, `4` | Thresholds 0–100, ordered low to high |
| `midMinimumTier`, `highMinimumTier` | `1`, `2` | Tier indices 0–5, ordered low to high; lower tiers are excluded from the draw |
| `loot`, `effects`, `health`, `armor`, `enchantments`, `music` | All `true` | Independent carrier features |
| `fakePlayerDrops` | `false` | Whether qualifying fake-player kills may roll a bag drop |
| `dropChance`, `lootingBonus` | `0.5`, `0.15` | Each 0–1 |
| `dropMultipliers` | `[1,1.25,1.5,3,4.5,6]` | Six factors, each 0–64 |
| `healthPerTier` | `5` | Added health per tier step, 0–1024 |
| `musicChance`, `advancedMusicChance` | `0.25`, `0.25` | Each 0–1 |
| `maximumDiscs` | `4` | 1–12, further limited by available discs/record cells |
| `blockedDiscs` | `botania:record_gaia_1`, `botania:record_gaia_2` | Item IDs/tags excluded from carrier music; absent items are harmless |
| `lootTables` | Table below | Complete entity-ID → loot-table-ID map |
| `colors` | Generated default palette | Complete entity-ID → `{"body":RGB,"trim":RGB}` map; RGB integers 0–16777215 |

Effective drop probability is
`min(1, (dropChance + lootingBonus × Looting level) × tier multiplier)`.
The mob-drops game rule and a qualifying player kill remain necessary. Existing
armor/chest equipment is not overwritten by a spawn roll; active raid members
are excluded. Tier effects add Speed from copper, Resistance from iron,
Strength from diamond and Fire Resistance at netherite when enabled.

Default loot mappings below use the `minecraft` namespace; table paths have
the `chests/` prefix:

| Entity IDs | Loot table |
| --- | --- |
| `creeper`, `husk` | `desert_pyramid` |
| `drowned` | `shipwreck_treasure` |
| `enderman` | `end_city_treasure` |
| `evoker`, `vex`, `vindicator` | `woodland_mansion` |
| `piglin` / `piglin_brute` | `bastion_bridge` / `bastion_treasure` |
| `pillager` | `pillager_outpost` |
| `skeleton`, `zombie` | `simple_dungeon` |
| `stray` | `igloo_chest` |
| `witch` | `buried_treasure` |
| `wither_skeleton` | `nether_bridge` |
| `zombie_villager` | `village/village_armorer` |
| `zombified_piglin` | `bastion_other` |

Supplying `lootTables`, `colors` or `compacting.itemOverrides` replaces that
entire map; `{}` intentionally clears it. Other known configuration maps merge
with their defaults. Loot is materialized from a saved seed on first supported
access; excess items are retained or delivered rather than dropped from the
saved result silently.

## Chest loot

Top-level `chestLoot` defaults to true. For builtin vanilla tables, one extra
roll uses these raw weights. All item names below use `fabricated_backpacks`;
all table names use `minecraft:chests/`.

| Table | No item weight | Item weights |
| --- | ---: | --- |
| `spawn_bonus_chest` | 0 | backpack 100 |
| `simple_dungeon` | 90 | backpack 5, copper 3, pickup 2 |
| `abandoned_mineshaft` | 84 | backpack 7, copper 5, iron 3, gold 1, magnet 2 |
| `desert_pyramid` | 89 | copper 5, iron 3, gold 1, magnet 2 |
| `shipwreck_treasure`, `woodland_mansion` | 92 | iron 4, gold 2, advanced magnet 2 |
| `nether_bridge` | 90 | iron 5, gold 3, feeding 2 |
| `bastion_treasure` | 90 | iron 3, gold 5, feeding 2 |
| `end_city_treasure` | 90 | diamond 3, gold 5, advanced magnet 2 |

Tier shorthand means `<tier>_backpack`; upgrade shorthand means its
`<name>_upgrade` ID. Weights are relative, not all percentages. External and
experimental data packs replacing these tables are left unchanged. Disabling
the setting affects future loot-table loading, not items already generated.

## Tool rules

Add JSON files at `data/<namespace>/backpack_tools/<name>.json` in a server data
pack. Higher-priority packs can replace the same resource path. After a
successful data-pack reload, valid rules publish as one complete catalog.
Invalid rules are logged and retain the previous catalog; an invalid first
load has no custom rules. Vanilla tool behavior remains available.

```json
{
  "items": ["minecraft:shears"],
  "entities": ["minecraft:sheep"],
  "priority": 100,
  "manual_only": true,
  "require_correct_tool": false
}
```

`items` is required and nonempty. At least one of `blocks` or `entities` must
be nonempty. All three arrays accept exact IDs or `#tags` from their respective
registries, up to 64 unique selectors each. IDs must include a namespace.
Absent optional-mod IDs do not match another registry entry.

`priority` is an integer 0–1000, default 0. Matching rules outrank native tool
preference; larger priority wins, with deterministic resource-ID order for
ties. `manual_only` defaults to false. `require_correct_tool` defaults to true
and keeps the correct-drops requirement for blocks; set it false only when
deliberately selecting a utility tool for a manual interaction. Each resource
is limited to 64 KiB and the catalog to 1024 rules. Unknown fields and malformed
values are rejected.

Bundled rules cover manual shearing, tilling and path flattening. Rules choose
an owned eligible item; they do not perform block modifications, shearing or
attacks. Advanced ghost filters, mode/weapon controls, output permissions and
safe storage of the old held stack still apply.

## Administrator commands

All commands require `COMMANDS_GAMEMASTER` permission. `/fabricatedbackpacks`
is an alias for `/fb`. Player-only authoring commands require an actual player;
recovery/template delivery accepts vanilla player selectors.

| Command | Action |
| --- | --- |
| `/fb list [page <n>]` | List archived bags, 20 per page |
| `/fb list player <name-or-id> [page]` | Filter archived owner records |
| `/fb recover <uuid> <players>` | Deliver independent copies of the archived snapshot |
| `/fb give <uuid> <players>` | Alias of recover |
| `/fb cleanup nonplayer [empty]` | Remove nonplayer archive records, optionally only empty ones; never remove live bags or player archive records |
| `/fb template list` | List local and enabled data-pack whole-backpack templates |
| `/fb template create <name> [overwrite]` | Snapshot exactly one held backpack as a local template |
| `/fb template delete <name>` | Delete a local template |
| `/fb template give <template> <players>` | Give independent copies without changing the source template |
| `/fb template export <template> [export_name]` | Create a new data pack; refuses existing destinations |
| `/fb dynamic start <tier>` | Start a virtual draft using a tier path or tier name |
| `/fb dynamic base <template>` | Start a draft from a whole-backpack template |
| `/fb dynamic item <item> [count] [slot]` | Queue an item request; optional explicit slots are zero-based |
| `/fb dynamic upgrade <item>` | Add an eligible upgrade to the draft |
| `/fb dynamic preview` | Validate placement and report remaining item requests |
| `/fb dynamic end <name> [overwrite]` | Save only when every queued item fits; otherwise keep the draft |
| `/fb dynamic cancel` | Discard the virtual draft without changing live inventories |

Local whole-backpack names accept 1–64 lowercase letters, digits, `_` and `-`,
starting with a letter or digit. Up to 256 local whole-backpack templates are
stored per world. Plain references select local templates; explicit
`namespace:name` references select data-pack files at
`data/namespace/backpack_templates/name.json`. Files use the mod's format-1
envelope containing a native `backpack` item stack and are limited to 4 MiB.
Export a template to obtain the correct component/registry encoding rather
than inventing another storage schema. Enable the exported pack and `/reload`
before using its namespaced reference.

**Recovery and whole-backpack template delivery intentionally copy items.**
They keep the source archive/template and assign independent identities to
delivered backpacks and children. Do not use repeated recovery as a substitute
for deciding whether the original bag still exists.

## Player preferences and settings templates

The in-game **Prefs** panel stores per-bag preferences and player defaults.
The **Tools** panel controls bulk memory/exclusions, sort mode, overlay RGB color
and main-inventory transfers. No-sort color accepts `RRGGBB` or `#RRGGBB`.
These choices are not server configuration fields.

The **Slot rules** panel edits Alchemy conditions and advanced refill targets
per ghost-filter row. Alchemy's **Hurt** threshold defaults to 75% health and
changes in five-point steps from 0% to 100%. Advanced Alchemy also exposes
target groups and effect/duration/amplifier matching. These preferences travel
with the upgrade; server range and timing bounds still apply.

Minecraft's Controls screen can rebind **B** (open), **G** (equipment), **O**
(browser), **C** (contextual deposit/restock) and **K** (manual tool selection).
Dedicated deposit/restock keys are initially unbound. The browser's **Transfer
recipe** and **Transfer max** buttons request one set or at most 64 complete
sets in the current compatible station. There is no configuration option that
allows a client to create missing ingredients or overwrite cooking output.

Up to 32 personal settings templates contain filters, reservations and
compatible upgrade preferences, never physical items, fluids, energy or
captured mobs. Personal names accept 1–48 letters, digits, spaces, `_` and `-`.
Enabled data packs can provide `data/<namespace>/backpack_settings/<name>.snbt`;
operator export creates a new pack and never overwrites an existing one. These
bounded settings resources are distinct from administrator whole-backpack
JSON templates. Browser bookmarks are client-local in
`config/fabricated-backpacks-browser.json`.

## Native automation (working branch)

The existing configuration file accepts an `automation` section. Older files
inherit its defaults; invalid or unknown fields fail validation before rules
are applied. The following values are defaults, not additional configuration
files:

```json
{
  "automation": {
    "conduits": {
      "itemsPerOperation": 8,
      "itemIntervalTicks": 10,
      "fluidMbPerTick": 100,
      "energyPerTick": 256,
      "maximumNetworkNodes": 2048,
      "maximumEndpointVisitsPerTick": 128
    },
    "engine": {
      "waterCapacityMb": 4000,
      "energyCapacity": 32000,
      "waterMbPerTick": 1,
      "energyPerTick": 40,
      "energyOutputPerTick": 256,
      "containerTransferMbPerTick": 1000
    }
  }
}
```

Conduit bandwidth is shared across the faces of each physical endpoint.
The network-size limit rejects oversized connected networks; the endpoint
work limit bounds routing work per dimension/tick. Resources stay in their
machines while a route is unavailable or being rebuilt. These are endpoint
bandwidth limits, not simulated per-segment pipe pressure or cable resistance.

Engine water is configured in mB but persisted in exact Fabric droplets;
fractional transfers are retained. Both generation quanta must fit their
configured capacity. Reducing capacity preserves stored excess; filling and
generation stop until the excess is recovered. The engine's On/Off control
pauses generation, not external item/fluid access or output of stored energy.

See [Native conduits and steam engine](AUTOMATION.md) for the player controls.
