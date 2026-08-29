# Features and current limits

This is the implementation matrix for **0.5.2-alpha+mc26.2**. A feature being present
in source is not a test result. Consult [0.5.2 verification](VERIFICATION_0.5.2.md) for the
specific build, executed checks, observations and outstanding failures. The
alpha does not claim complete coverage of every planned feature or external
integration.

## Backpack tiers

| Tier | Item ID | Storage cells | Upgrade slots |
| --- | --- | ---: | ---: |
| Leather | `backpack` | 27 | 1 |
| Copper | `copper_backpack` | 45 | 1 |
| Iron | `iron_backpack` | 54 | 2 |
| Gold | `gold_backpack` | 81 | 3 |
| Diamond | `diamond_backpack` | 108 | 5 |
| Netherite | `netherite_backpack` | 120 | 7 |

IDs on this page use the `fabricated_backpacks` namespace. These are the
defaults for new bags; [server configuration](CONFIGURATION.md) can set each tier
to 1–144 storage cells and 0–10 upgrade slots. Tanks, batteries and captured mobs
reserve cells, reducing ordinary storage. Grids use nine columns up to 81 cells
and twelve above that, with pages for additional rows. Saved dimensions travel
with the item. Reducing a default does not discard an existing bag's contents.

The registered item catalog contains six backpacks, 54 functional upgrade
variants, ten stack-tier conversion ingredients and `upgrade_base`. Conversion
ingredients are crafting materials, not installable upgrades.

The working branch adds five automation items: `item_conduit`, `fluid_conduit`,
`energy_conduit`, `conduit_wrench` and `steam_engine`. They are independent blocks
and tools, not backpack upgrades. See [native automation](AUTOMATION.md) for
their controls, transport limits and current verification status. These additions
are not present in the existing `v0.5.0-alpha` download.

### Crafting and colors

| Feature | Current implementation |
| --- | --- |
| Starter recipe | Four leather, four string and a chest |
| Tier progression | Copper from leather; iron from leather or copper; gold from iron; diamond from gold; smith the Diamond Backpack directly with the vanilla Netherite Upgrade Smithing Template and a Netherite Ingot |
| Component preservation | Tier crafting and direct Netherite smithing transmute the source backpack, retaining its stored data |
| Dyeing | One backpack plus dyes; dyes left of the backpack color the body, dyes right color the trim, dyes in its column color both |
| Washing | Use a dyed backpack on a water cauldron to reset both colors, consuming one water level |
| Default colors | Leather body `#B97843`, dark trim `#503B36` |
| Creative variants | Infinity and Omega Stack have no survival recipe |

Dye mixing and washing preserve the backpack's other components. Check their
executed cases in the verification record before relying on an alpha build in
an existing world.

## Storage, menus and equipment

| Area | Present behavior | Current boundary |
| --- | --- | --- |
| Held and inventory use | Open a held item or use the backpack key | The opening key prefers the dedicated equipment slot, then inventory order |
| Placed use | Facing, waterlogging, comparator/hopper access, viewer-controlled lid motion, pickup and stored-stack drops | Targeted rendered checks passed; broader lighting and GUI-scale coverage remains limited |
| Equipment | One persistent native backpack slot alongside chest armor, with third-person rendering | No external accessory API adapter is supplied |
| Gold equipment | An equipped gold backpack satisfies vanilla piglin-safe armor checks through the native item tag | Holding it does not count; theft, attacks and existing anger still follow vanilla rules |
| Death handling | Equipped backpack follows `keepInventory`; ordinary drops respect equipment-drop prevention | Multiplayer, respawn and reconnect results must be checked in the evidence record |
| Inventory movement | Ordinary clicks, shift-click, page selection and protected source slots | The open backpack cannot be moved through its own menu |
| Sorting | Name, count, namespace and tag order; compatible memory cells fill first, then ordinary cells pack | Components are not merged across distinct stacks; excluded/blocked cells and infinite seeds remain protected |
| Search inside storage | Name, `@namespace`, `#tooltip`, phrases and exclusions; matching cells reflow into a filtered view | Physical slot ownership does not change; hidden and malformed click requests are rejected by the server |
| Memory | Per-cell ghost reservations, optional component matching, remember-occupied and clear-all controls | Remembering creates no items and clearing memory removes no physical contents |
| Sort exclusions | Per-cell editing, select-all/clear-all and an RGB overlay color | Exclusions protect cells from sorting and bulk take operations |
| Bulk transfer | Store/take matching stacks, or hold Shift for all eligible stacks | Acts on the 27 main-inventory cells; hotbar, armor, offhand and the owning backpack stay put |
| Upgrade panels | Real inventories, ghost filters, tag editing and paged controls; up to 64 filters and 256 configured record slots | Effective saved extents remain accessible after configured defaults shrink |
| Resources | Tank/power columns with values and cursor-container actions | Very large capacities require the numeric value to interpret small amounts |
| Captured mobs | Reserved rectangular cell areas, a model preview and release action | Capture eligibility and collision checks remain server decisions |
| Contents preview | Hold Shift over a backpack for its physical storage grid, retaining enhanced counts | Every stack label and the total use full exact digits; memory ghosts do not count as physical items |
| Exterior item | Show one selected storage cell on the placed or worn pack, with memory fallback, rotation and depth controls | Empty, unremembered cells show nothing; no additional inventory is created |
| Settings persistence | Item settings, player preference defaults, up to 32 private templates and settings data-pack loading/export | Templates contain settings and ghost choices, not physical items or resources; export requires operator permission |
| Automation | Vanilla hoppers, Fabric item/fluid adapters, Energy API, nested routing and input/output filters | Mod-specific protection or automation integrations are not implied |
| Identity and archives | Distinct physical bags with colliding identities receive independent IDs; accessed/carried/placed bags have recovery snapshots | Repair preserves each bag's items; it is not a global unloaded-world scan or an item-duplication detector |

Use the **Mode** button to cycle ordinary interaction, memory editing and
sort-exclusion editing. In memory mode, left click assigns an item from the
cursor or existing cell; right click clears the reservation. In exclusion mode,
click cells to toggle their exclusion.

The **Tools** panel adds bulk memory/exclusion editing, sorting, overlay color
and inventory transfers. Search changes presentation, not storage coordinates.
The native menu continues to validate page, cell, source bag and session on the
server while a filtered view is open.

Default keys are **B** to open a backpack, **G** for equipment, **O** for the
recipe browser and **K** for manual tool selection. **C** transfers against the
looked-at inventory using the first active deposit/restock upgrade in the first
resolved backpack. Separate deposit and restock bindings are available but
unbound by default. All these keys can be changed in Minecraft's controls.

Ghost filters represent choices, not physical inventory. Clicking a filter with
an item on the cursor copies its identity without consuming the stack; right
click clears it. Clicking with an empty cursor opens the item browser's filter
picker. The server checks the selected upgrade, duplicate choices and slot
bounds.

### Preferences and templates

The **Prefs** panel provides a name field, retained search/tab preferences,
memory component matching, shift-transfer behavior, optional worn-bag sharing,
and personal defaults. Individual backpack preferences override those defaults.

Exterior slot numbers are 1-based in this panel; 0 disables the display. Rotation
advances by 45 degrees. Depth ranges from -16 to 16 in steps of 1/16 of a texture
pixel; positive depth moves the icon outward. The renderer fits the selected
item model against the front pocket before applying that adjustment.

Save, preview, load or delete a named personal settings template. Templates
include compatible upgrade settings, filters, memory and sort exclusions; they
do not duplicate items, fluid, energy, experience or captured entities. Names
such as `1` through `10` can be used for numbered presets. A template referenced
as `namespace:name` loads from an enabled data pack's
`data/namespace/backpack_settings/name.snbt`. Operator-only export creates a new
pack in the world's data-pack directory and refuses to replace an existing
export. Enable the exported pack and reload it before using its resource name.

## Upgrade catalog

The table groups related registered variants. Basic and advanced versions have
separate items and recipes. Most upgrade families admit one installed member;
tanks admit two, and stack modifiers admit three. Incompatible upgrades or
capacity reductions that would strand contents are rejected.

| Family | Registered variants | Behavior and controls |
| --- | --- | --- |
| Pickup | `pickup_upgrade`, `advanced_pickup_upgrade` | Route eligible collected items into the backpack; basic/advanced ghost filters |
| Filter | `filter_upgrade`, `advanced_filter_upgrade` | Control storage input, output or both with item filters |
| Magnet | `magnet_upgrade`, `advanced_magnet_upgrade` | Attract nearby eligible items and XP; separate item/XP toggles |
| Feeding | `feeding_upgrade`, `advanced_feeding_upgrade` | Consume suitable stored food; advanced hunger policy and hurt-state options |
| Compacting | `compacting_upgrade`, `advanced_compacting_upgrade` | Apply exact reversible packing recipes with bounded work; configurable shapes and work-in-menu setting |
| Void | `void_upgrade`, `advanced_void_upgrade` | Explicitly filtered storage overflow, representation overflow or always-void modes |
| Restock | `restock_upgrade`, `advanced_restock_upgrade` | Pull matching items from an interacted container or shortcut target |
| Deposit | `deposit_upgrade`, `advanced_deposit_upgrade` | Send matching backpack contents to an interacted container or shortcut target |
| Refill | `refill_upgrade`, `advanced_refill_upgrade` | Replenish selected items; advanced rows can target hands or specific hotbar slots |
| Tool selection | `tool_swapper_upgrade`, `advanced_tool_swapper_upgrade` | Select stored tools for block/entity interactions; advanced manual/automatic and weapon options |
| Smelting | `smelting_upgrade`, `auto_smelting_upgrade` | Furnace recipes, physical input/fuel/output and earned XP; automatic variant draws from storage |
| Smoking | `smoking_upgrade`, `auto_smoking_upgrade` | Smoking recipes with the corresponding physical slots and automatic variant |
| Blasting | `blasting_upgrade`, `auto_blasting_upgrade` | Blasting recipes with the corresponding physical slots and automatic variant |
| Crafting | `crafting_upgrade` | Persistent 3×3 inputs, real remainders, optional grid refill, result destination, a searchable picker for conflicting valid recipes and browser ingredient transfer |
| Stonecutting | `stonecutter_upgrade` | Persistent input, result destination/refill, per-player input-specific recent recipes and a searchable 9×5 result picker |
| Anvil | `anvil_upgrade` | Native naming, repair/enchantment rules and experience costs |
| Smithing | `smithing_upgrade` | Native template/base/addition slots and smithing result rules |
| Jukebox | `jukebox_upgrade`, `advanced_jukebox_upgrade` | Two/basic or twenty-four/advanced physical record slots by default; advanced size 1–256 with previous/next pages and columns 1–6; play/stop, previous/next, shuffle and repeat |
| Tank | `tank_upgrade` | A fluid compartment, four container-processing slots and Fabric fluid access; reserves two storage columns |
| Battery | `battery_upgrade` | Stored energy, two container-processing slots and Team Reborn Energy access; reserves two storage columns |
| Pump | `pump_upgrade`, `advanced_pump_upgrade` | Input/output direction and neighboring handlers; advanced adds hand containers, optional world transfer and fluid filters |
| XP pump | `xp_pump_upgrade` | Store/take experience using tank fluid, level targets and mending options |
| Inception | `inception_upgrade` | One level of nesting, guarded removal, child-first/outer-first order, separate inner-upgrade and outer-processing switches, aggregated item/fluid/energy access |
| Everlasting | `everlasting_upgrade` | Dropped-item lifetime and environmental protection, including recovery from below the world |
| Infinity | `infinity_upgrade`, `survival_infinity_upgrade` | Seeded slots intentionally supply copies; the creative variant requires game-master authority, and infinity excludes other installed upgrades |
| Alchemy | `alchemy_upgrade`, `advanced_alchemy_upgrade` | Four/eight conditional consumable rows; advanced target and effect-matching policies |
| Mob capture | `mob_catcher_upgrade`, `advanced_mob_catcher_upgrade` | Capture eligible mobs into real storage rectangles and release saved entities; advanced has a larger allowance |
| Stack increase | `stack_upgrade_starter_tier`, `stack_upgrade_tier_1` through `stack_upgrade_tier_4` | Multipliers 1.5×, 2×, 4×, 8× and 16× |
| Stack decrease | `stack_downgrade_tier_1` through `stack_downgrade_tier_3` | Multipliers 1/8, 1/16 and 1/32, with a minimum capacity of one |
| Creative stack | `stack_upgrade_omega_tier` | Very large bounded storage counts; no survival recipe |

### Filters and intentional deletion

Basic filters generally provide nine entries and advanced filters sixteen.
Refill uses six/twelve entries; alchemy uses four/eight. Automatic cooking uses
twelve filter entries split into eight input and four fuel choices. Advanced matching can
distinguish namespace, tags, damage and components where the upgrade exposes
those controls. Filter dimensions are configurable; automatic cooking keeps its
two categories separate when layouts or templates change.

An empty allow list rejects items; an empty block list permits eligible items.
Void upgrades are destructive by design. Their default empty allow list does
not match everything. Check the filter and deletion mode before enabling one.
Void can match explicit fluid choices or fluids contained by item ghosts, with
component matching and the same allow/block policy. Slot overflow, whole-storage
overflow and immediate deletion are distinct modes. Server policy can disable
immediate deletion. Manual-slot processing is separately opt-in.

### Tools and portable workstations

Basic tool selection is automatic. Advanced selection has **AUTO**, **ONLY_TOOLS**
and **MANUAL** modes, a weapon switch and ghost filters. Only-tools mode leaves
swords, food and empty hands alone during automatic block selection. Manual mode
does not perform automatic block or entity swaps. The rebindable tool-cycle key
(default **K**) still requests a deliberate choice. Automatic entity selection
switches from a held tool to a suitable weapon; it does not replace a held sword.
Swaps return the previous stack to owned storage or fail without overwriting it.

Vanilla tool components supply ordinary harvesting behavior. Server data packs
can add item/tag rules for block and entity targets. The bundled manual utility
rules cover shearing, tilling and path flattening; selecting a tool does not
perform the interaction for the player. See [tool rules](CONFIGURATION.md#tool-rules).

Crafting, stonecutting, anvils and smithing use native recipe/result rules with
persistent input inventories. Result transfer defaults to backpack storage and
can be switched to player inventory. Crafting and stonecutting expose refill;
smithing does not auto-refill. Recipe pickers submit an exact recipe identity,
which the server rechecks against current ingredients, enabled features and
applicable unlock rules. Opening a child backpack from a workstation retains
the owning session rather than detaching its input stack.

### Resources and music

Tanks use Fabric's precise fluid units internally and display millibuckets.
Default capacity is 4,000 mB per backpack row before stack modifiers. Batteries
start at 10,000 energy units per row. Per-row capacities, transfer rates and
the contribution of stack upgrades are server settings. Existing excess after
a configuration reduction remains extractable; new insertion cannot increase
it. Fabric's water-bottle unit is one third of a bucket, so its fractional
millibucket remainder is retained internally.

XP storage uses the mod's liquid-experience resource in a tank. The battery is
an energy store, not a generator; actual charge/discharge requires a compatible
item or source. API fixtures do not establish compatibility with every other
mod's machines. One XP point represents 20 mB. Magnet item/XP work uses separate
switches and configured active/idle cadence. Mending and automatic player XP
transfer have server-side budgets; a saved item setting cannot exceed them.

Playlist controls operate on physical record slots, including current
`JukeboxPlayable` registry components. Audio instances are scoped to their bag,
move with their carrier and are sent to eligible observers. Resizing the live
record inventory keeps an unchanged active track and valid queue/history;
removing the active disc stops it. Stored records, shuffle and repeat persist.
Runtime history and audio do not resume after a restart.

Cooking stores burn/cook progress, recipes used and earned XP. Removing or
disabling an upgrade pauses work. Automatic variants independently pull input,
pull fuel and push output/remainders, with bounded retry delays. Cooking speed
and fuel efficiency come from the server file, not untrusted item settings.

## Native item and recipe browser

| Capability | Current implementation |
| --- | --- |
| Item catalog | Enabled registry items with cached localized names and tooltip text |
| Search | Case-insensitive words, `@namespace`, `#tooltip`, quoted phrases and `-exclusions`; terms combine with AND |
| Recipes and uses | Separate result and ingredient indexes built from server recipe displays |
| Layouts | Crafting, furnace-family, stonecutting and smithing layouts; generic ingredients for other available displays |
| Categories | Filter the selected item's recipes by recipe type |
| Navigation | Bounded back/forward history, item pages and recipe pages |
| Bookmarks | Item and recipe bookmarks in `config/fabricated-backpacks-browser.json`, up to 512 of each |
| Ghost selection | Choose a registered item for a supported upgrade's ghost slot without acquiring the item |
| Transfer | One set or the maximum complete sets, capped at 64, into the already-open compatible crafting, stonecutting, smithing or cooking workstation |
| Authority | Server rechecks recipe identity, catalog epoch, active menu, ownership, ingredients and applicable recipe unlocks |
| Reload | Recipe/tag/resource changes invalidate the relevant caches; Reload requests a fresh local view |
| Responsiveness | Incremental client indexing, cached queries and bounded catalog pages; measured timings are available in the title tooltip |

Opening the browser never grants an item. All enabled static recipes may be
viewed, including recipes absent from the player's recipe book. Servers using
limited crafting still require the appropriate unlock for transfer.

Transfers support the native crafting table, stonecutter, smithing table,
furnace, smoker and blast furnace, plus the corresponding backpack workstations
and all six cooking upgrade panels. They use actual inputs, player inventory
and the current backpack's permitted storage. Matching keeps component variants
separate and handles alternative ingredients without spending a later
ingredient's only choice. Previous inputs must fit back into owned storage or
the transfer leaves every slot unchanged. Unstackable ingredients reduce the
maximum set count.

Cooking transfer changes its input only: fuel and existing output remain in
place. No station is opened automatically. Anvil operations, campfire recipes
and unsupported custom station categories remain browsing-only.

The server catalog is bounded to 100,000 displays, 64 per page, with an encoded
entry-size bound. Dynamic recipes without a static display and entries outside
the bounds are reported as unshown. The browser does not invent a fixed output
for dynamic dyeing. Default registry stacks are the item list; it is not a
complete enumeration of every possible component or potion variant.

The title tooltip exposes indexing/search timings for the current run. Those
measurements are not comparative performance claims or a modpack benchmark.

## World loot and carriers

Eligible monster spawns can receive a backpack, with a default 1% chance and
weighted tier selection. Existing chest equipment and active raid participants
are excluded. The deferred spawn check runs once, including across save/load.
Difficulty can exclude low tiers; configuration controls the weights, colors,
health/effects, added armor/enchantments, music, loot tables and drops.

Carrier backpacks can contain deferred loot from configured vanilla or custom
tables. The saved seed is consumed once on materialization. Overflow remains
stored or is delivered to the opening player rather than silently discarded.
Conversion carries the backpack forward; carrier music stops on death. Drops
respect the mob-drops game rule, a player kill, the configured Looting formula
and the separate fake-player permission.

Nine supported builtin vanilla chest tables receive one additional weighted
backpack/upgrade roll when `chestLoot` is enabled. External or experimental data
packs replacing a table retain control of that table and do not receive the
extra pool. The exact tables and weights are in
[Configuration](CONFIGURATION.md#chest-loot).

## Administration and recovery

`/fb` and `/fabricatedbackpacks` are aliases requiring game-master/operator
permission. They list archived bags, create recovery copies, remove eligible
nonplayer archives and manage whole-backpack templates. Dynamic drafts can
assemble a template from explicit item and upgrade requests, reporting
unplaced items before any template is saved.

Ordinary settings templates copy preferences and ghosts only. **Administrator
whole-backpack templates and recovery commands intentionally create items**:
each delivery receives independent backpack identities, while the source
archive/template remains unchanged. They are recovery/authoring tools, not a
withdrawal from a live inventory. Use them with world backups and verify the
recipient/source before issuing copies.

Archives track bags encountered through supported access and loaded
carried/placed lifecycle paths. They are latest snapshots, not a complete
historical or unloaded-world backup. See the full
[command reference](CONFIGURATION.md#administrator-commands).

## Original visuals

All backpack cuboids, tier fittings, pixel textures, upgrade glyphs and the logo
are produced by the project's deterministic generator. Body and trim tint
separately. The native worn mesh uses the same source geometry as the placed
models and follows the player's body pose.

Placed backpacks animate the lid from 0 to 45 degrees over eight client ticks
in each direction, following the authoritative viewer state. Frame
interpolation also handles reversal while opening or closing. Item and worn
models remain closed. Targeted rendered acceptance, including motion reversal,
all four facings and body/trim changes without changing facing, is recorded in
[Verification](VERIFICATION.md).

Offline contact sheets and UV/face checks help catch resource mistakes. The
recorded client and visual checks cover selected lighting, armor overlap and UI
views; the two-client run checks synchronized shared access, appearance privacy
and audio tracking. These results do not cover every GUI scale, graphics setup
or observer combination. See [Asset pipeline](../tools/ASSET_PIPELINE.md).

## Compatibility boundary

| Interface | Included | What this does not establish |
| --- | --- | --- |
| Fabric item storage | Placed backpack adapter, carried item contexts and vanilla container behavior | Every automation mod's routing or permissions |
| Fabric fluid storage | Installed tanks on placed and item backpacks, plus supported containers | Every modded fluid container or world-fluid interaction |
| Team Reborn Energy | Installed battery access on placed and item backpacks; placed output to adjacent receivers | Energy generation or universal machine compatibility |
| Native equipment | This mod's own slot, attachment, menus, rendering and an explicit shared-resource context | Another equipment mod's API, plug-ins or save format |
| Native browser | This mod's own search, display, bookmark and transfer protocol | External recipe-viewer plug-ins or API compatibility |
| Data packs | Recipes/tags, server recipe displays, tool rules, settings templates and administrator whole-backpack templates | Other mods' template schemas or storage save formats |

The shared resource adapters and their connection rules are documented in
[Item, fluid and energy integration](INTEGRATION.md). The additional item
contexts and placed battery output belong to the unreleased working revision.

No external recipe-viewer, accessory, conditional workstation or storage-mod
adapter is currently supplied. Compatibility claims need an exact-version
integration and an executed test. There is no automatic migration from another
mod's item IDs or storage data.

## Remaining alpha work

The current unit/server build, full client scenario, separate-JVM restart,
two-client TCP scenario and targeted visual review passed, including the
empty-search hint correction at GUI scale 3. Installed production-JAR
acceptance, its independent production restart and the complete release gate
also passed for the recorded scenarios; the local bundle was built. Public
download availability is tracked separately. See
[Verification](VERIFICATION.md) for the current artifact and exact outcomes.
These are the broader remaining coverage and scope limits, not a declaration
that the complete planned feature set has been delivered:

- Broader visual coverage of exterior displays, worn fit and contents previews
  across GUI scales, graphics configurations, lighting and observer combinations.
- Additional ghost/memory drag targets and configurable internal shortcuts.
- Full-process tests for interrupted in-progress operations and broader
  dedicated-server, concurrent-user and failure-recovery combinations beyond
  the completed restart and two-client scenarios.
- Individually verified external integrations and modpack measurements.

The verification record is the authority for what has actually been exercised.
