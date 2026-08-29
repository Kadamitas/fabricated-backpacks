# Changelog

## 0.5.1-alpha+mc26.2

Experimental alpha for Minecraft 26.2. Back up worlds before testing it. This
coordinate separates the current changes from the immutable published
`0.5.0-alpha` artifact. Fresh full-client, separate-JVM restart, multiplayer and
manual installed-JAR evidence remains required for the exact rebuilt artifact
before the full-release verification gate can pass.

- Skip unsupported solid neighbors while selecting conduit destinations, within
  the shared lookup budget, so grounded pipes retain their transfer cadence.
  Keep dynamic handlers discoverable on unchanged machine blocks.

- Add native item, fluid and energy conduit lanes in a shared bundle, with
  directional endpoint controls, redstone gates, per-endpoint transfer limits,
  loaded-network routing and atomic resource transfers.
- Add nine ghost-filter positions per item/fluid interface, with Off, Allow
  and Block modes. Search registered items and fluids by name, ID or namespace;
  right-click a ghost to clear it without consuming an item or fluid container.
- Support optional JEI 26.2 ingredient dragging into conduit filters, including
  native fluid ingredients and filled fluid containers. JEI is not required or bundled.
- Apply both endpoint filters to live and retained transfer handlers, including
  rollback when a filter changes during a transaction. Keep item components and
  exact fluid droplets intact when routing between upgraded backpacks.
- Open conduit settings only from physical interface plates. A wrench cycles
  external tube connections, restores disabled stubs and reconnects internal
  links; the bundle center does not open a menu. Normal mining removes only the
  aimed conduit, including by hand, while preserving the other strands.
- Add a steam engine that consumes furnace fuel and water, exposes standard
  sided APIs, retains unfinished fuel while paused, and preserves its contents
  in one stateful drop. Its original model has an animated flywheel and piston.
- Configure the engine's six faces with the wrench, with independent item,
  water and energy permissions that persist through pickup and world reload.
- Add real-client, restart and multiplayer acceptance scenarios for engine
  controls and resources, shared conduit placement and actual network transfers.
- Avoid synchronous chunk lookups while saved conduit bundles are loading;
  pending chunks and missing machine registrations cannot create resource ports.
- Compact leather and amber backpack frames, gray inventory cells, physical
  upgrade slots on the left, and item-icon tabs on the right. Main-screen
  controls use icons with complete hover help and accessible labels.
- Storage rows adapt to the viewport; narrow windows reflow upgrade panels
  into fewer columns with paging. Search expands from its icon or Ctrl+F.
- Match the reference body proportions and attached upgrade panels, join the
  Inventory bar to the storage frame, and slim resource gauges to 16 pixels.
  Remove dark heading shadows and separate sorting from sort-order selection.
- Give automatic cooking its four reference-style filter controls: allow/block,
  item/mod/tag identity, durability and components. Colored state icons and hover
  help follow the actual saved settings; additional controls remain on a second page.
- Expose carried backpack contents through Fabric's item storage API, alongside
  existing item-context fluid and energy access, with an explicit context for
  the native equipment slot.
- Push stored energy from placed batteries into compatible neighboring receivers,
  with a saved output switch, sided connection checks and a shared transfer budget.
- Fuller original worn models with two side pouches, deeper front pockets,
  torso-to-hip coverage, armor clearance, and matching placed interaction shapes.
- Preserve drag ownership across layout changes, reserve the native click
  packet's changed-slot budget, synchronize selected tabs before their contents,
  and expose retained upgrade inventories installed after a menu opens.

The historical [UI revision record](docs/UI_REVISION.md) and
[automation verification record](docs/AUTOMATION_VERIFICATION.md) retain the
hashes and test status of their recorded artifacts; they do not attest to this
new coordinate.

## 0.5.0-alpha

Initial development build for Minecraft 26.2 on Fabric. This entry describes
implemented work, not a certification that all acceptance checks have passed.
See [Verification](docs/VERIFICATION.md) and
[Features and current limits](docs/FEATURES.md).

### Added

- Six backpack tiers with persistent item components, tier-preserving crafting
  and smithing, filtered storage pages, memory-first sorting, bulk transfers,
  memory cells and sort exclusions.
- Original generated cuboid models, tier fittings, independently tinted body
  and trim, upgrade icons, material atlases and branding.
- A native equipment slot with a worn backpack model alongside chest armor.
- Selected-item displays on placed and worn backpacks, independent body/trim
  dyeing, cauldron washing, animated placed flaps and native gold-backpack
  piglin safety. Remote appearance packets exclude private inventory contents.
- Shift contents previews with preserved enhanced counts, per-player defaults,
  personal settings templates and operator-controlled settings-pack export.
- The functional upgrade catalog, including collection/filtering, item
  transfers, cooking, crafting/stonecutting/anvil/smithing workstations,
  resources, stacking, protection, alchemy and mob capture.
- Standard and advanced jukeboxes with one and twelve physical record slots,
  playback controls, shuffle and repeat.
- Fabric item/fluid adapters and Team Reborn Energy battery access.
- A native item/recipe/uses browser with literal search, categories, history,
  persistent bookmarks, ingredient layouts and ghost-filter selection.
- Server-checked transfer of one or the maximum complete recipe sets into
  compatible open portable and vanilla workstations, including correlated
  responses, component preservation and stale-menu rejection.
- Deterministic asset generation, model/UV/resource audits, unit tests and
  separate server/client acceptance test infrastructure, including separate-JVM
  world restart and two-client multiplayer scenarios.
- Validated server configuration, configurable inventory/filter/record layouts,
  tool-selection data packs, chest loot, world carriers, recovery archives and
  operator commands.

### Alpha boundaries

External equipment and recipe-viewer adapters are not included. The native
equipment slot and recipe browser provide their own supported features.
Save compatibility, multiplayer coverage, performance and publication status
must be assessed from the verification record for the exact artifact.
