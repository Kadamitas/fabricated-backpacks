# Native Forge port

Target: Fabricated Backpacks **1.0.0+mc26.3**, Minecraft **26.3**, Forge **66.0.2**.

This branch is a native port, not a Fabric JAR relabeled for another loader. The build has
no Fabric Loader, Fabric API, or Team Reborn Energy runtime dependency, and the production
JAR carries only `META-INF/mods.toml`, the two production mixin configs (declared through
the JAR manifest's `MixinConfigs`) and the mod's own classes.

## Loader wiring

- Items, fluids and energy are exposed through `ForgeCapabilities.ITEM_HANDLER`,
  `FLUID_HANDLER`, `FLUID_HANDLER_ITEM` and `ENERGY` with `IItemHandler`, `IFluidHandler`,
  `IFluidHandlerItem` and `IEnergyStorage` implementations
  (`platform/transfer/ForgeResourceAdapters`), attached to block entities and item stacks in
  `AttachCapabilitiesEvent` (`platform/transfer/NativeCapabilities`). Fluids convert at 81
  droplets per millibucket. Forge's simulate/execute handlers are staged inside the mod's
  transactions until root commit, so an abort never invokes an external mutator.
- The `LazyOptional`s handed to other mods are tracked per block entity and invalidated when
  a block's exposed handlers change (steam engine side configuration, conduit bundle
  configuration, placed battery support changes, backpack block content or upgrade changes)
  and when the block entity is removed, which is Forge's counterpart of a capability
  invalidation.
- Payloads travel on one Forge payload channel (`platform/network/NativeNetworking`,
  `ChannelBuilder`), sent with `PacketDistributor`; `canSend` checks the remote's registered
  channel for the exact payload, as a real Forge client registers every payload id.
- Lifecycle and player callbacks come from Forge events: server start/stop, datapack sync,
  command registration, tick, level and chunk events, player login/logout, tracking,
  `PlayerEvent.Clone` plus `PlayerRespawnEvent` for respawns, `LivingDropsEvent` for death
  drops, `LootTableLoadEvent` for chest loot, `PlayerInteractEvent.LeftClickBlock`,
  `AttackEntityEvent` and `EntityInteractSpecific` for tool and backpack interactions.
  Conduit bundles register through the block entity's own `onLoad`/`setRemoved`. Mixins
  remain only where no native hook exists: pick-block on the server packet handler, hopper
  routing to the sided handler, the loot-resource observer (the loot event does not expose a
  table's resource origin), and the generic foreign block-entity load/unload observer.
- Forge 66 ships upstream Mixin 0.8.7, whose highest recognized compatibility level is
  `JAVA_21`; the Java 25 mixin classes are accepted under that declaration.
- The test-only client harness and the copied server GameTests live in the separate
  `fabricated_backpacks_tests` mod. GameTest fixtures advertise channels through the real
  `minecraft:register` path. `NativeHandlerGameTests` exercises the handlers other mods see
  through `ForgeCapabilities` on the block entity.

## Verification

`.github/workflows/verify.yml` runs the unit tests, the native GameTest server (vanilla
JUnit-like report), the exact-target asset audit, the evidence checker with `mods.toml`
and manifest mixin auditing, the rendered client and separate-JVM restart runs, the two-JVM
multiplayer run (`tools/multiplayer.gradle` records ForgeGradle's exact client command
through a launcher stand-in) and `releaseBundle`. The release receipt describes exactly
what that workflow ran; nothing in this document is evidence on its own.
