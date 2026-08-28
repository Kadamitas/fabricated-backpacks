# Minecraft 1.21.1 / Cobblemon compatibility

This is the separate `codex/minecraft-1.21.1-cobblemon` development branch.
The 26.2 working branch remains separate, and its published alpha is not replaced.

## Target

As checked on 2026-08-28, the official latest Cobblemon release is
[1.7.3](https://wiki.cobblemon.com/index.php/1.7.3), for Minecraft 1.21.1.
The [installation guide](https://wiki.cobblemon.com/index.php/Guides/Installation)
also specifies Minecraft 1.21.1. The optional test runtime pins the official
Fabric release ID `kF7CvxTo`, not an ambiguous cross-loader version.

| Component | Pinned development target |
| --- | --- |
| Minecraft | 1.21.1 |
| Java | 21 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.116.15+1.21.1 |
| Team Reborn Energy | 4.1.0, included |
| Optional JEI | 19.44.0.413 |
| Optional Cobblemon | 1.7.3+1.21.1 |

Cobblemon supplies its own nested Fabric Language Kotlin dependency. Neither
Cobblemon nor JEI is embedded in the Fabricated Backpacks artifact.

## Compatibility changes

The port uses native 1.21.1 registries, components, saved-data, menus, recipe
book, rendering and networking APIs. Original generated models/textures remain
in place; item models and recipes use 1.21.1 resource formats. The custom body
and trim colors do not overwrite another mod's vanilla custom-model-data value.
Copper backpacks remain available; their optional mob armor uses chainmail
because this Minecraft version has no copper armor.

Conduits still use Fabric Transfer API and Team Reborn Energy. Searchable
filters work with registered modded items and fluids; transferring a filtered
type retains its original components. Enhanced stacks do not inherit the old
Fabric container adapter's ordinary 64-item cap.

The recipe browser lists registered furnace fuels and assembles real trim
examples with each recipe's template, material and armor type. Pokémon are
excluded from the generic mob catcher by the optional
`fabricated_backpacks:unsupported_capture` entity tag: their party/battle data
belongs to Cobblemon, whose native capture system remains responsible for them.
The optional tag does not make Cobblemon a required dependency.

Opening a worn backpack remains **B**. The built-in browser defaults to **V**
because Cobblemon uses O for its party display. Existing saved key assignments
are respected. Alt+Z/X upgrade shortcuts also hold Cobblemon's riding-freelook
modifier; they can be rebound in Controls.

## Verification status

Fresh local checks on 2026-08-28 passed for the same 692 source/build inputs:

| Actual runtime | Unit tests | Native server GameTests | Result |
| --- | --- | --- | --- |
| Minecraft 1.21.1, no Cobblemon | 502 | 170 | Passed |
| Minecraft 1.21.1 with Cobblemon 1.7.3 | 502 | 172 | Passed |

Each unit run covered 197 methods in 32 classes, with no failures, errors or
skips. The server reports likewise contain no failures or skips. Both builds
produced the identical remapped main JAR:

```text
fabricated-backpacks-0.5.0-alpha+mc1.21.1.jar
SHA-256: 3213f6894d50b7e9545d64b9475a00f8a79beac69286186e9bc2bc22726fd67e
```

The artifact contains 436 project classes targeting Java 21, plus only the
declared Energy API 4.1.0 library (12 classes). No Cobblemon, JEI, test mod or
private files are bundled. The sources archive was also checked: 216 project
Java sources, no compiled classes, nested libraries or test/private content.
All 335 generated files matched their generators; 31 asset tests passed against
the original 1.21.1 client JAR. The existing evidence checker passed 37 tests and
the compatibility checker passed 30 rejection/validation tests.

Verification epochs and actual processes:

- Base: `628c23f0-1d7e-48ef-afe8-dbae7d8ea18f`, unit PID 44304, server PID 39592.
  Log `.codex-local/port-base-verification-7.log`, 1 minute 21 seconds.
- Cobblemon: `eed25fb5-d24f-4813-a656-c41986ddbf03`, unit PID 43020, server PID 27524.
  Log `.codex-local/port-cobblemon-verification-6.log`, 1 minute 47 seconds.

The strict receipts have scope `unit-and-server/compatibility`, not release.
The final base receipt is `build/verification/compatibility.json`; exact reports,
runtime witnesses and receipts from both runs are preserved under
`.codex-local/checkpoints/base-server-7` and `cobblemon-server-6`. These local
diagnostics are not committed or distributed with the mod.

The optional Cobblemon tests fail if its exact mod release or real registered
Poké Ball items are absent. They exercise named/component-bearing item round
trips, item allow/block inversion, ghost filter authority and simultaneous
fluid/energy transfers through all three bundled lanes.
They also instantiate the actual registered Pokémon entity, verify the capture
exclusion and unchanged entity/backpack data with and without an owner flag,
then successfully capture and release an ordinary pig using the same catcher.
That is an exclusion test, not a simulated party or battle integration test.

The server fixture translates the older framework's structure-block origin to
content-relative coordinates. It keeps the real world, collisions, test clock,
native permissions and assertions. The test mod and its accessor are excluded
from the production JAR.

The newer Fabric client GameTest API does not exist on this target. Its 26.2
scenarios remain source references and are not registered, executed or reported
as 1.21.1 passes. `runClientGameTest` fails explicitly instead of claiming success.
The compatibility checker accepts only unit/server evidence and rejects release
mode. Historical 26.2 screenshots and receipts do not validate this artifact.

Outstanding installed-client checks include readable inventory/JEI screens,
worn rear/side views with armor/crouching and while riding Pokémon, actual
conduit hand mining, fresh-world persistence and multiplayer observation.
Computer control was stopped with Escape before the current manual session;
no new manual acceptance receipt was written.

## Safe testing

See [Building and testing](BUILDING.md) for exact commands. Use a fresh 1.21.1
instance and a new or backed-up 1.21.1 world. Do not open a 26.2 world with this
port or mix the 26.2 and 1.21.1 JARs in one instance. There is no cross-version
world or backpack-data downgrade converter.
