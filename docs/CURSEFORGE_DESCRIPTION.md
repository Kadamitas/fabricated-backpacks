# Fabricated Backpacks

**Wearable storage, portable machines, and compact logistics for Minecraft 26.2 on Fabric.**

Fabricated Backpacks turns one portable container into a configurable storage and automation toolkit. Carry a backpack in your inventory, equip it without giving up chest armor, or place it in the world as an animated storage block. Six backpack tiers, 54 functional upgrade variants, a built-in item and recipe browser, shared resource conduits, and a steam engine cover travel, processing, and base automation in one mod.

This is an alpha release. Back up important worlds before installing or updating.

## Carry it, wear it, or place it

Press **B** to open the equipped backpack. If no backpack is equipped, the key searches your inventory. Press **G** to open the included equipment panel and place a backpack in its dedicated slot. The backpack remains visible in third person while chest armor stays equipped.

Sneak-use a backpack against a block to place it. Placed backpacks retain their inventory, upgrades, settings, fluids, energy, experience, and appearance. They support hoppers and standard Fabric transfer interfaces, animate their lid while being viewed, and can show a selected stored item on the outside.

Backpack bodies and trim can be dyed separately. Washing a dyed backpack in a water cauldron restores its original colors. The same body, trim, material fittings, and exterior item display appear on held, placed, and worn models.

## Six storage tiers

Each tier adds storage and upgrade capacity:

- **Leather:** 27 storage cells and 1 upgrade slot
- **Copper:** 45 storage cells and 1 upgrade slot
- **Iron:** 54 storage cells and 2 upgrade slots
- **Gold:** 81 storage cells and 3 upgrade slots
- **Diamond:** 108 storage cells and 5 upgrade slots
- **Netherite:** 120 storage cells and 7 upgrade slots

Tier recipes preserve the source backpack and all of its saved state. The final tier is made by smithing a Diamond Backpack with the vanilla Netherite Upgrade Smithing Template and a Netherite Ingot.

Server owners can configure every tier from 1 to 144 storage cells and from 0 to 10 upgrade slots. Large inventories use additional columns and pages instead of overflowing the screen.

## Storage controls

Backpack storage includes tools for large inventories:

- Search by item name, namespace, tooltip text, quoted phrases, and exclusions
- Sort by name, count, namespace, or tag
- Reserve cells with remembered item ghosts
- Exclude selected cells from sorting and bulk removal
- Store or take matching stacks in bulk
- Hold Shift over a backpack to preview its physical contents
- Display complete item counts without abbreviating large values
- Choose a stored item to display on the worn or placed backpack
- Save personal settings templates without copying physical items or resources

Search changes the visible arrangement without changing physical slot ownership. Server checks protect hidden slots, the open backpack, upgrade inventories, and invalid menu actions.

## Upgrade families

Upgrades are installed in the slots along the left side of the backpack. Their compact icon tabs open controls on the right. Hold Shift while hovering settings to see contextual explanations.

### Collection and inventory movement

Pickup and Magnet Upgrades route collected items into storage. The magnet can handle items and experience independently. Filter Upgrades control automated input and output. Deposit, Restock, and Refill Upgrades move selected items between the backpack, player inventory, and nearby containers.

Advanced filters add larger ghost inventories and matching choices for item identity, namespace, tags, durability, and components.

### Portable processing

Crafting, Stonecutting, Anvil, and Smithing Upgrades provide persistent portable workstations. Their inputs remain in the backpack when the screen closes.

Smelting, Smoking, and Blasting Upgrades provide real input, fuel, output, and earned experience. Automatic versions pull valid ingredients from backpack storage and return finished results.

Compacting Upgrades only use recipes with an exact safe reverse recipe. Materials such as iron ingots can become iron blocks, while irreversible outputs such as trapdoors are rejected.

### Player tools and survival support

Feeding Upgrades consume suitable stored food according to their settings. Alchemy Upgrades use filtered consumables under configured conditions. Tool Selection Upgrades choose an appropriate stored tool or weapon and provide a separate manual selection key.

Everlasting protects dropped backpacks from ordinary environmental destruction. Mob Capture Upgrades store eligible entities inside reserved backpack cells and release them after server-side safety checks.

### Capacity and nested storage

Stack Upgrades raise the number of items allowed in each storage cell. Stack Downgrades create controlled smaller limits for automation. Inception permits one guarded level of nested backpacks and can expose nested item, fluid, and energy storage to compatible operations.

Creative and survival Infinity variants provide intentionally unlimited seeded cells for maps or packs that choose to enable them.

### Fluid, energy, and experience

Tank Upgrades add fluid storage and fluid-container processing. Pump Upgrades transfer fluid between tanks, containers, neighboring handlers, and optionally the world.

Battery Upgrades store energy, charge compatible items, and expose standard sided energy access when the backpack is placed. Batteries store energy supplied by another source and do not generate it themselves.

The Experience Pump stores player experience as liquid experience in a tank. It can maintain a chosen experience level and spend stored experience on configurable Mending support.

### Jukebox libraries

The basic Jukebox Upgrade holds two physical music discs. The advanced version holds 24 by default and supports libraries from 1 to 256 slots through server configuration. A 200-disc library is supported and tested.

Controls include play, stop, previous, next, shuffle, and repeat. Large libraries use previous and next pages. Music follows a carried backpack and is synchronized for nearby eligible listeners.

## Built-in item and recipe browser

Press **O** outside menus or use the Recipe Browser button inside supported workstation screens. No separate recipe-viewer mod is required.

The browser provides:

- Item search by name, namespace, tooltip, phrase, and exclusion
- Recipe and usage views
- Recipe categories and ingredient layouts
- Back and forward history
- Item and recipe bookmarks
- Ghost item selection for supported filters
- Transfer of one recipe set or the maximum complete sets into an already open compatible workstation

Transfers work with crafting tables, stonecutters, smithing tables, furnaces, smokers, blast furnaces, and their backpack upgrade equivalents. The server rechecks the recipe, active menu, available ingredients, ownership, and unlock rules before moving anything. The browser never grants missing ingredients or crafts the result automatically.

## Shared item, fluid, and energy conduits

Fabricated Backpacks includes three conduit types for base automation. Item, fluid, and energy strands can share one block position while keeping independent routes and endpoint settings. A bundle can turn corners, branch, and connect placed backpacks to compatible storage blocks and machines.

Open a physical connection plate to configure that face. The center of the conduit bundle intentionally has no menu. Each endpoint can be set to Extract, Insert, Both, or Disabled.

The Conduit Wrench changes external connections, reconnects disabled links, and configures machine sides. Normal mining removes only the strand under the crosshair, even when all three conduit types occupy the same block. Sneak-use with the wrench also removes one selected conduit type while leaving the others and their settings intact.

Item and fluid interfaces each provide nine ghost filter cells with Off, Allow, and Block policies. The included picker searches registered items or fluids by name and registry ID. Fluid filters can distinguish water from lava, while item filters can distinguish cobblestone from iron. Optional JEI integration also permits dragging compatible item and fluid ingredients into these ghost cells.

Transfers use the standard Fabric item and fluid APIs plus Team Reborn Energy. Source and destination changes occur in the same transaction so rejected or partial transfers do not delete resources. Conduit routing stays within loaded chunks and uses configurable topology and transfer budgets.

## Steam engine

The Steam Engine converts water and ordinary furnace fuel into energy. Its original 3D model includes a boiler, chimney, brass fittings, flywheel, crank, connecting rod, and piston. The mechanism animates while the engine is producing power.

The engine keeps dedicated slots for fuel, water containers, fuel remainders, and empty containers. Its six faces have separate item, water, and energy permissions. These settings, stored resources, and unfinished fuel work survive pickup and world reload.

Connect an energy conduit to move generated energy into a backpack battery or another compatible receiver.

## World and server features

Configured vanilla loot chests can contain backpacks and selected upgrades. Eligible hostile mobs may rarely spawn carrying tiered backpacks with saved colors, loot, equipment bonuses, or music. Server settings control the chance, tier weights, loot tables, drops, colors, effects, and exclusions.

Operator commands provide backpack archive recovery and whole-backpack templates. Ordinary player templates copy only settings and filter choices. Recovery and operator template delivery create new independent backpack identities.

Server configuration controls tier sizes, upgrade limits, filter layouts, jukebox capacity, processing rates, tank and battery capacity, conduit throughput, steam-engine production, mob carriers, loot, and individual safety policies.

## Controls

All gameplay controls can be changed in Minecraft's Controls screen:

- **B:** Open backpack
- **G:** Open backpack equipment
- **O:** Open the item and recipe browser
- **C:** Use the first available Deposit or Restock Upgrade on the targeted container
- **K:** Manually select a stored tool
- Dedicated Deposit and Restock controls are available but unbound by default

Gameplay bindings remain inactive while a menu is open, including when typing in a search field.

## Requirements and compatibility

Fabricated Backpacks 0.5.2-alpha requires:

- Minecraft Java Edition 26.2
- Java 25
- Fabric Loader 0.19.3 or newer
- Fabric API 0.158.0+26.2 or a newer compatible Minecraft 26.2 build

**Fabric API is the only required external mod dependency.** It supplies registry, networking, rendering, item-transfer, and fluid-transfer APIs used by the mod.

The Team Reborn Energy API is included inside the Fabricated Backpacks JAR. JEI is optional and is not bundled. Accessories, Trinkets, EMI, REI, and other equipment or recipe-viewer mods are not required because the mod includes its own equipment slot and browser.

Install the same Fabricated Backpacks version on the client and server. This release does not import inventories or settings from other backpack mods.

## Alpha status and support

This is an alpha build intended for testing. Make a world backup before installing it and report reproducible problems through the issue tracker.

- [Source code](https://github.com/Kadamitas/fabricated-backpacks)
- [Report a bug](https://github.com/Kadamitas/fabricated-backpacks/issues)
- [Full feature reference](https://github.com/Kadamitas/fabricated-backpacks/blob/v0.5.2-alpha%2Bmc26.2/docs/FEATURES.md)

Code and project artwork are available under the MIT License.
