# Verification: 0.5.2-alpha+mc26.2

Target: **Fabricated Backpacks 0.5.2-alpha+mc26.2**, Minecraft **26.2**, Fabric
Loader **0.19.3**, Fabric API **0.158.0+26.2**, and Java **25**.

This record covers the exact production JAR packaged for the 0.5.2 alpha. It
does not claim exhaustive coverage of every Minecraft interaction, mod
combination, GPU, server topology, or long-running world.

## Artifact

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `fabricated-backpacks-0.5.2-alpha+mc26.2.jar` | 1,393,908 | `fffa0c462888d0f83656887e4795ffd8b4178b134c4462fc4c5bab7a2e43964b` |
| `fabricated-backpacks-0.5.2-alpha+mc26.2-sources.jar` | 613,137 | `93f10285c27e9f6bc99699332d4e9804211b1c9f4e82dc1e4adb958960d9b493` |

The production JAR embeds mod version `0.5.2-alpha+mc26.2`, has 908 entries,
and contains no GameTest classes, acceptance scenarios, test-mod metadata, or
test assets. Its only nested library is the included Energy API.

## Executed checks

| Check | Result |
| --- | --- |
| Deterministic generator check | **PASS:** all 410 generated asset/data files matched their sources |
| Exact-target Python asset suite | **PASS:** 31/31 tests against the Minecraft 26.2 client JAR |
| Clean Java unit/build run | **PASS:** `clean test jar sourcesJar` |
| Server GameTests | **PASS:** all 170 required tests |
| Client input isolation | **PASS:** all 12 keyboard, mouse and queued gameplay bindings stayed inactive in menus; typing B in search did not open a backpack |
| Client jukebox paging | **PASS:** 200 physical slots, first/middle/last pages, wraparound, slot 199 interaction and sparse 0/99/199 playback through Minecraft's sound manager |
| Repository whitespace audit | **PASS:** `git diff --check` |

Both focused client scenarios launched Minecraft, created a new integrated
world, performed their assertions, saved the world, and exited successfully.
The 170 server tests ran before a final client-only tooltip/test refinement; no
server gameplay code changed afterward, and both client scenarios plus the
clean unit/build run were then repeated against the final sources.

The older [0.5.0 verification record](VERIFICATION.md) remains historical and
does not attest to this artifact.
