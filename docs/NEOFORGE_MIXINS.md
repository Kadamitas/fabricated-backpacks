# NeoForge mixin audit

The 1.0.0 NeoForge port keeps only the remaining behavior-sensitive hooks listed below. This audit compares with `v0.5.2-alpha+mc26.2`; it does not claim that the loader or test-harness dependencies themselves are mixin-free.

## Replaced with native APIs or owned code

- Dropped-backpack protection and damage immunity now use `BackpackItem.onEntityItemUpdate` and `canBeHurtBy`. Pickup uses `ItemEntityPickupEvent.Pre`; the existing stack reference is retained so vanilla cannot pick up the original count a second time. Item ownership uses NeoForge's public `ItemEntity.getTarget()`.
- Foreign conduit endpoint replacement uses native capability-invalidation subscriptions. Listeners are owned by live conduit nodes, and refresh waits until chunk installation/removal completes.
- Conduit client prediction uses the owned block's native `onDestroyedByPlayer` override, retaining the block entity until the server confirms the surviving lanes.
- Hopper routing naturally selects the native transactional capability because the owned backpack block entity no longer exposes a competing raw `Container` interface. Explicit manual deposit/restock still adapts the same inventory with its distance checks.
- UI acceptance uses NeoForge's public container-screen getters, an ordinary icon-button getter, and test-only read-only tooltip reflection. No production reflection replaces the removed access mixins.
- The copied test input driver now supplies modifier flags directly instead of mixing into our own class. The earlier key-binding accessor was also removed in favor of NeoForge's public API.

## Retained production hooks

| Hook | Behavior preserved / reason retained |
| --- | --- |
| `ServerPlayerGameModeMixin` | Reads the actual private mining-in-progress state for conditional upgrades. |
| `ZombieVillagerMixin` | Invokes vanilla's private curing transition; does not duplicate its state machine. |
| `ExperienceOrbAccessor` | Reads and updates merged-orb count/value so partial XP transfers conserve experience. |
| `PiglinSafetyMixin` | Extends only the safe-armor predicate for the independent backpack slot, preserving theft and existing anger rules. Native item armor callbacks do not visit this slot. |
| `MobBackpackMixin` | Covers generic spawn, carrier ticking, and every `Mob.convertTo` path with its actual conversion parameters. Native conversion events do not cover every generic conversion call. |
| `CreeperCarrierMixin` | Removes carrier buffs immediately before cloud creation, not before explosion combat or by dropping all cloud effects. |
| `ConduitMiningMixin` | Splits only the targeted lane after protection/restriction checks but before tool wear and whole-block removal. The native destruction override runs after tool wear. |
| `NativePickBlockMixin` | Applies the upgrade inside the real server pick-block packet handler before vanilla inventory selection, retaining packet validation and ordering. |
| `NativeLootTableMixin` | Observes table resource provenance so external/experimental datapack replacements remain untouched. Native loot-load events expose the table, not its resource origin; tables are frozen before ordinary reload listeners run. |
| `SlotPositionAccess` | Updates vanilla's final slot coordinates when the owned menu layout changes, while preserving slot identities and server indices. |

## Test-only hooks

The relocated Apache-2.0 Fabric client harness retains 24 upstream hooks for deterministic client/server scheduling, virtual input/window handling, world setup, and screenshot capture. They are in the separate test mod, never the production JAR. The remaining project-owned test hook observes actual client mesh invalidation without changing rendering. Removing these would require redesigning the non-invasive test harness or weakening its observations; neither is part of this release cleanup.

The existing test-only `MainMixin` also prevents a second NeoForge mod-construction pass when that harness starts its real dedicated server inside the already-initialized host client JVM. It is restricted to an active harness server start in client distribution; ordinary dedicated launches retain the native loader bootstrap. The actual dedicated-server implementation, socket connections, registration results, and multiplayer assertions are unchanged.

The native test harness uses an ordinary configuration-task event to isolate that embedded server's static tag bindings from its host client's frozen-registry remap. It snapshots the actual loaded server tags and sends the restored bindings through the normal tag packet after the native handshake. This applies only to the embedded host profile; the separate guest receives the unchanged native protocol. No production registry behavior or handshake acknowledgment is bypassed.

Release verification still requires the real unit, server, rendered-client, separate-JVM restart, and two-client multiplayer checks. A compile-only result is not a claim that those runtime gates passed.
