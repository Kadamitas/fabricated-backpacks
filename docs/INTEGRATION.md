# Item, fluid and energy integration

This document describes the Minecraft 1.21.1 port. These changes are not in the
existing 26.2 `v0.5.0-alpha` download. Target-specific status is recorded in
[1.21.1 compatibility](COMPATIBILITY_1_21_1.md).

## Shared interfaces

| Resource | Placed backpack | Backpack in an item context |
| --- | --- | --- |
| Items | Fabric `ItemStorage.SIDED` | Fabric `ItemStorage.ITEM` |
| Fluids | Fabric `FluidStorage.SIDED` | Fabric `FluidStorage.ITEM` |
| Energy | Team Reborn `EnergyStorage.SIDED` | Team Reborn `EnergyStorage.ITEM` |

Fabric API supplies the item/fluid interfaces. Team Reborn Energy API 4.1.0 is
included in the mod JAR. No separate Fabricated Backpacks pipe protocol or
energy unit is required. See the upstream [Fabric Transfer API](https://wiki.fabricmc.net/tutorial:transfer-api)
and [Team Reborn Energy contract](https://github.com/TechReborn/Energy/blob/master/src/main/java/team/reborn/energy/api/EnergyStorage.java).

Item access exposes permitted backpack contents, not its upgrade inventory or
the backpack item itself. Filters, remembered slots, enhanced stack capacity,
Infinity permissions and enabled Inception traversal still apply. Vanilla
hoppers can also use the placed backpack's container interface.

Stored fluid requires an installed tank. An enabled fluid-void policy can
intentionally accept and discard matching fluid without storing it. Variants retain their components;
fluids with different components are not silently combined. Fabric measures
fluid in droplets: one bucket is 81,000 droplets, equivalent to 1,000 mB in the
backpack display. `storage.itemFluidAccess` controls item-context fluid access.
Fluid containers are discovered through the shared API rather than a list of
specific third-party item IDs.

Energy access requires an installed battery. It stores energy supplied by a
compatible source; it does not generate energy. Its item slots support charging
and discharging compatible energy items.

## Placed connections

All six faces use sided lookups. A machine should query the backpack face
touching that machine. A backpack pushing to a neighboring receiver queries
the receiver's opposite face. Unsided access uses a null direction.

`storage.disableConnections` disables placed external connections.
`storage.blockedConnections` blocks configured neighboring block IDs or tags.
No transfer loads a neighboring chunk. Cached adapters recheck their owner,
installed resources and connection policy, so removing or replacing a backpack
does not leave a usable handle into its former contents.

Client energy lookups expose connection capabilities for cable rendering, but
not private stored quantities. Their amount/capacity are zero; insertion and
extraction return zero. Query the server for actual amounts and mutations.

Placed batteries push to compatible adjacent receivers, following Team Reborn
Energy's source-push convention. **External energy output** defaults to On in
the battery panel. Switch it Off to make that battery an input-only external
port; this does not erase energy or disable its item charging slots.

The output budget is shared across the six faces for each installed battery
per server tick. Enabled Inception resource traversal visits nested batteries
once. Two output-enabled backpack batteries do not continually send energy
back and forth: make the receiving battery input-only to transfer between them.
Carried and worn backpacks do not automatically drain into nearby machines.

Tank and battery capacity/rates follow the row count, stack multiplier and
server configuration. The working branch's [native conduits and steam engine](AUTOMATION.md)
use these public APIs too. Their own routing and throughput limits are configured
under `automation`; external pipes and machines retain their own rules.

## Conduit filters and ingredient browsers

The unreleased conduit interface has separate nine-cell item and fluid
filters for the physically opened face. Off is the default. Allow
passes only listed registry IDs, so an empty Allow list denies everything.
Block rejects listed IDs, so an empty Block list allows everything.
Energy has no type filter. Filters at both ends apply; they select resource
types without stripping components from transferred item or fluid variants.

The built-in picker searches registered types by name, ID, `@namespace`,
`#tooltip`, quoted phrase and `-exclusion`. It also lists fluids without
buckets. Left-click a ghost to search; right-click or Delete clears it.
See [the player guide](AUTOMATION.md#item-and-fluid-filters) for examples.

The optional adapter targets **JEI 19.44.0.413 for Fabric 1.21.1** through its
public `IGuiContainerHandler` and `IGhostIngredientHandler` APIs. JEI is neither
bundled nor a required runtime dependency. Its drag targets use
`ConduitScreen.acceptItem` / `acceptFluid`, the same server-bound path as the
built-in picker. The server checks the current menu, its physical face,
installed conduit type, slot and registered identity before changing a filter.
Selecting an ingredient does not grant or consume it.

For fluid ghosts, the adapter accepts JEI fluid ingredients and filled
containers exposing Fabric `FluidStorage.ITEM`. It only reads a constant copy
of the container: a Water Bucket selects water without emptying or consuming
the bucket. A container reporting several different fluid types is not an
unambiguous selector. These controls configure identities, not transfer amounts.

Development runtime and test opt-in commands are in
[Building](BUILDING.md#optional-jei-development-and-tests). This API description
is not a claim that the new JEI scenario has passed; executed results belong
in [the automation revision record](AUTOMATION_VERIFICATION.md).

## Carried and equipped backpacks

For a held, inventory or cursor item, construct the appropriate Fabric
`ContainerItemContext` and query `context.find(ItemStorage.ITEM)`,
`context.find(FluidStorage.ITEM)` or `context.find(EnergyStorage.ITEM)`.
Use the actual mutable context. Fabric's constant context can report accepted
virtual exchanges while leaving its fixed item unchanged; it is a probe, not
a persistent transfer endpoint. A context that prohibits replacement rejects
the backpack mutation and preserves its resources.

The native equipment slot has an explicit server-side bridge:

```java
ContainerItemContext context = ResourceRuntime.equippedContext(serverPlayer);
Storage<ItemVariant> items = context.find(ItemStorage.ITEM);
Storage<FluidVariant> fluids = context.find(FluidStorage.ITEM);
EnergyStorage energy = context.find(EnergyStorage.ITEM);
```

`ResourceRuntime` is in
`com.kadamitas.fabricatedbackpacks.resource`. The context addresses that
player's current equipped backpack, while its physical equipment slot remains
non-extractable. Resource changes use the same inventory as an open backpack
menu and synchronize committed changes. A handle to removed/replaced equipment,
a dead player or a spectator becomes inert. The caller is responsible for
checking player authorization and range before selecting a context.

This is an explicit integration point, not a global player-fluid or player-energy
lookup. It does not implement Accessories or Trinkets' equipment APIs.

## Transactions and scope

Use Fabric transactions for transfers. A successful simulated insert/extract
does not persist unless the enclosing transaction commits. When moving between
two storages, perform both operations in the same transaction; failed capacity
or item replacement must leave both sides unchanged. Components and resource
totals must survive an aborted nested transaction as well as a top-level abort.

The regression suite exercises shared API contracts, including rejected
transfers, rollback, side rules, stale handles and resource conservation.
Passing those tests is not a claim that every mod's cable, custom machine,
container, protection system or network topology has been tested. An external
mod should be listed as verified only with its exact version and an executed
integration scenario.
