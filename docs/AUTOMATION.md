# Native conduits and steam engine

This describes the automation feature on the Minecraft 1.21.1 compatibility
branch. It is not included in the immutable 26.2 `v0.5.0-alpha` download.
Executed checks and remaining client work are recorded in
[1.21.1 compatibility](COMPATIBILITY_1_21_1.md), separately from the behavior below.

## Shared conduit bundles

The item, fluid and energy conduits occupy one block space together. Install
one of each type by using the next conduit on the existing bundle. A single
type is centered; several types have separate visible strands. Each type
connects independently, including around corners and through junctions.
Using an already installed type extends that type into the adjacent block.

Right-click a physical connection plate to configure that interface. The
bundle's center does not open a menu, and an interface menu cannot switch to
another face. Directions refer to the network:

| Mode | Adjacent machine interaction |
| --- | --- |
| Extract | Pull from the machine into the network |
| Insert | Deliver from the network into the machine |
| Both | Permit both directions |
| Disabled | Disconnect that type on this face |

Item and fluid endpoints initially insert, so placing a conduit does not
unexpectedly empty a chest or tank. Set the source face to Extract. Energy
endpoints initially permit both directions. Interior conduit links remain
connected unless a facing side is disabled.

Use the wrench on an external tube to cycle its connection mode. Disabling an
external connection removes its interface plate but retains a thin tube;
wrench that tube to restore the connection. Wrenching a link between conduits
cuts it, and wrenching the facing side of a hub reconnects a disabled link
without opening a menu. Sneak-use the wrench to remove the targeted type
without removing the other strands. Normal mining, including with an empty
hand, removes only the strand under the crosshair and drops that conduit.
The other types and their settings remain in place. Mining the final strand
removes the block and restores any water it contained. Creative mining also
removes one targeted strand at a time, without drops. Destruction of the whole
block by the world still returns all installed types when drops are enabled.
Resources remain in their machines; conduits do not store resources in transit.

### Item and fluid filters

Open a connection plate, then the filter icon on its item or fluid row. Each
type has nine ghost cells on that face; energy has no type filter. The mode
button cycles through these policies:

| Mode | What may pass | With no entries |
| --- | --- | --- |
| Off (default) | Every type | Every type |
| Allow | Only listed types | Nothing |
| Block | Every type except those listed | Every type |

Left-click a ghost cell to open the built-in search picker. Search by name or
registry ID, `@namespace`, `#tooltip`, quoted phrases or `-exclusions`.
Right-click a cell, or focus it and press Delete, to clear it. Escape returns
from the picker to the same open interface. No real item or fluid is placed
in a ghost cell, and choosing an entry does not turn filtering on automatically.

For example, Allow with `minecraft:cobblestone` passes cobblestone but not iron;
Allow with `minecraft:water` passes water but not lava. Both the source and
receiving face's filters must permit a transfer. Matching uses registry IDs,
not stack components; transferred items and fluids retain their components.
Flowing fluid aliases use the source fluid's identity. The picker includes
registered modded types, including fluids without bucket items.

With optional JEI installed, open the desired filter panel and drag an item or
fluid from JEI's list onto a ghost cell. In a fluid panel, a Water Bucket also
selects water without consuming the bucket. Both pickers use the same server
validation. JEI is not needed for the built-in picker; see
[integration](INTEGRATION.md#conduit-filters-and-ingredient-browsers).

## Steam engine

The engine includes its own boiler. Supply water and any normal furnace fuel.
Its physical slots hold fuel, a water container, fuel remainders and emptied
water containers. The engine exports energy through the standard sided API.

Default productive-tick rates are 1 mB of water and 40 energy, with a 4,000 mB
tank, 32,000 energy buffer and up to 256 energy output per tick. Generation
pauses when disabled, dry or unable to fit a complete energy quantum. Pausing
retains unfinished fuel work. Configuring smaller capacities must not delete
previously stored resources.

The model has an iron boiler, brass fittings, chimney, open spoked flywheel,
crank, connecting rod and piston. Only productive work animates the mechanism.
Breaking the engine returns one item containing its inventory, resources and
remaining fuel work.

Use the wrench on the engine to open its machine-side settings. Each of the
six faces has separate item and water input/output permissions; energy faces
can be Output or Disabled. Disabled faces also stop retained API handles and
automatic pushes. Side settings survive pickup, replacement and world reload.
The engine's own fuel and water-container processing continues independently
of external port settings. Ordinary engine interaction still opens its fuel
and water slots.

## Interoperability and routing

Items and fluids use Fabric Transfer API; energy uses the included Team Reborn
Energy API. No additional logistics mod or private transfer protocol is needed.
These same interfaces connect the engine and conduits to compatible external
machines and placed backpacks. For backpack-to-backpack fluid transfer,
install a tank upgrade in each backpack, connect them with fluid conduits,
set the source interface to Extract and the destination to Insert, and leave
room in the receiving tank. For energy, install a battery in each backpack
and use energy conduits with the same explicit Extract/Insert roles. Keep the
source battery's external output On; turn the receiving battery's external
output Off to make it input-only. Resource and connection settings still apply.

Routing uses transactions for both endpoints. A rejected destination, partial
acceptance or aborted simulation must conserve the source and destination.
Bandwidth is shared across the faces of each physical endpoint. Loaded
networks rotate their sources and destinations and use bounded topology and
endpoint work. Routing never deliberately loads an unloaded chunk.
Automatic Both routes do not shuttle energy between storage endpoints; choose
explicit Extract and Insert roles when transferring between batteries.

Default conduit limits are 8 items per 10 ticks, 100 mB per tick, and 256 energy
per tick. Server configuration lives under `automation.conduits` and
`automation.engine` in `config/fabricated_backpacks.json`.

This is the base transport feature with registry-type filters. It does not
claim every advanced routing, priority, cover or redstone system of other mods.
Compatibility with a specific external mod requires testing its actual API
implementation; the common interface alone is not an executed compatibility
test.
