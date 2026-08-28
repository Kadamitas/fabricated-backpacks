# Changelog

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
