# Native NeoForge port

Target: Fabricated Backpacks **1.0.0+mc26.3**, Minecraft **26.3**, NeoForge **26.3.0.7-beta**.

This branch is a native port, not a Fabric JAR relabeled for another loader. The build has
no Fabric Loader, Fabric API, or Team Reborn Energy runtime dependency, and the production
JAR carries only `META-INF/neoforge.mods.toml`, the two production mixin configs and the
mod's own classes.

## Loader wiring

- Items, fluids and energy are exposed through `Capabilities.Item.BLOCK/ITEM`,
  `Capabilities.Fluid.BLOCK/ITEM` and `Capabilities.Energy.BLOCK/ITEM`, registered in
  `RegisterCapabilitiesEvent` (`platform/transfer/NativeCapabilities`, `BlockLookup`,
  `ItemLookup`). The mod's long-count storages are exported as `ResourceHandler` and
  `EnergyHandler` implementations (`platform/neoforge/NativeResourceHandler`,
  `NativeEnergyHandler`); fluids convert at 81 droplets per millibucket and roll back a
  fractional attempt before retrying a whole native unit. Other mods' handlers are adopted
  through the same boundary; the mod's own exported handlers come back unchanged.
- `invalidateCapabilities()` runs wherever a block's exposed handlers change: steam engine
  side configuration, conduit bundle configuration, placed battery support changes, backpack
  block content or upgrade changes, and world fluid changes (`level.invalidateCapabilities`).
- Storage participants journal through NeoForge transactions. `TransactionLedger` keeps one
  ordered frame per transaction depth so reverts unwind last-in, first-out, which the shared
  storage code and its bag aliases rely on. The mod's own containers (`OwnedContainer`)
  persist tentatively and restore the persisted component on abort.
- Payloads are registered in `RegisterPayloadHandlersEvent` through `PayloadRegistrar`
  (`platform/network/NativeNetworking`), sent with `PacketDistributor`, and gated with
  `hasChannel` per payload.
- Lifecycle and player callbacks come from NeoForge events: server start/stop, datapack
  sync, command registration, tick, level and chunk events, player login/logout, tracking,
  `PlayerEvent.Clone` plus `PlayerRespawnEvent` for respawns, `LivingDropsEvent` for death
  drops, and `LootTableLoadEvent` for chest loot. Conduit bundles register through the
  block entity's own `onLoad`/`setRemoved`. Mixins remain only where no native hook exists:
  pick-block on the server packet handler, hopper routing to the sided handler, the
  loot-resource observer (the loot event does not expose a table's resource origin),
  and the generic foreign block-entity load/unload observer.
- The test-only client harness (`docs/NATIVE_CLIENT_TEST_HARNESS.md`) and the copied server
  GameTests live in the separate `fabricated_backpacks_tests` mod. GameTest fixtures
  negotiate the mod's payloads on their mock connections with NeoForge's own
  `NetworkRegistry.configureMockConnection`, leaving NeoForge's config sync unnegotiated.
  `NativeHandlerGameTests` exercises the handlers other mods see through the loader's own
  capability objects.

## Verification

`.github/workflows/verify.yml` runs the unit tests, the native GameTest server (vanilla
JUnit-like report), the exact-target asset audit, the evidence checker with
`neoforge.mods.toml` auditing, the rendered client and separate-JVM restart runs, the
two-JVM multiplayer run and `releaseBundle`. The release receipt describes exactly what
that workflow ran; nothing in this document is evidence on its own.
