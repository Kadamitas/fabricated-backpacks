# Verification record

Target: **Fabricated Backpacks 0.5.0-alpha**, Minecraft **26.2**, Fabric,
Java **25**. This record covers the tested build, not all possible modpacks or
upgrade combinations.

## Release status

Checkpoint: **2026-08-28**. The recorded unit/server build, full client scenario,
two-client TCP scenario and separate-JVM restart passed. Targeted visual
review of the client captures is complete. The first hosted Linux run found
platform-dependent PNG compression. The corrected encoder passes exact-byte
asset checks under both local Python 3.13.2/zlib and 3.14.3/zlib-ng; all 94 PNGs
retain identical dimensions and decoded RGBA pixels. The next local and hosted
server runs exposed random launch motion in the XP cadence fixture; its actual
velocity is now cleared without relaxing the deadline or conservation checks.
Fresh release checks are underway. Installed-JAR acceptance is pending. The complete
release gate and public downloads remain pending. This is not a completed
release approval. Earlier development runs
do not substitute for checks against the current source snapshot and artifact.

## Current executed results

The source-input snapshot is verification run
`1d5545a7-e2b3-4486-9a34-a835abc451c0`, started at
`2026-08-28T09:00:53Z`, with 624 hashed inputs. Its automated unit/server gate
passed at `2026-08-28T09:04:05Z`.

| Check | Current result | Local evidence |
| --- | --- | --- |
| JUnit, combined run 14 | **PASS:** 374 invocations, 133 declared test methods, 21 test classes; 0 failures, errors or skips | `build/test-results/test/TEST-*.xml`, `build/test-results/test/unit-execution.json` |
| Server GameTests, combined run 14 | **PASS:** 137 executed, exactly 136 mod tests and 1 vanilla test; 0 failures, errors or skips | `build/gametest-results-run14.xml`, `build/combined-run14.log` |
| Deterministic assets | **PASS:** 358 generated files current; 18 Python asset tests passed against the exact Minecraft 26.2 JAR | `build/asset-run14.log` |
| Evidence-checker tests | **PASS:** 37 Python tests | `build/gate-tests14.log` |
| Build and automated evidence gate | **PASS:** unit/server discovery, source-input consistency and production-JAR exclusions checked | `build/verification/automated.json` |
| Full client, run 11 | **PASS:** actual client scenario, PID 38724, 41 PNG captures; placed-state and mesh-observer assertions passed | `build/client-evidence/run11/`, `.codex-local/client-evidence/full-pass.json` |
| Multiplayer, run 5 | **PASS:** two actual Minecraft JVMs over TCP, both exit codes 0 | `build/verification/multiplayer.json` |
| Separate-JVM restart, run 2 | **PASS:** writer PID 38724, reader PID 34432, reader exit code 0, 2 PNG captures | `.codex-local/client-evidence/restart-pass.json`, `build/client-evidence/restart2/`, `build/client-restart2.log` |
| Targeted visual review | **PASS:** 15 fresh client frames reviewed, including the corrected body/trim recolor, worn model and UI | `build/client-evidence/run11/screenshots/` |
| Installed production JAR | **PENDING:** standalone startup checked, but new-world acceptance paused for the asset correction | `build/production-manual1.log`, `build/verification/manual.json` when complete |
| Complete release gate and bundle | **PENDING** | `build/verification/release.json` when complete |
| Hosted CI | **FAILED:** PNG check and 374 unit cases passed; 1 of 137 server cases failed because the XP cadence fixture could drift | [Second hosted run](https://github.com/Kadamitas/fabricated-backpacks/actions/runs/33159054060) |
| Public platform downloads | **PENDING:** no public CurseForge or Modrinth download verified | Each platform must be checked separately |

The main artifact is `build/libs/fabricated-backpacks-0.5.0-alpha.jar`, with
713 archive entries. Its SHA-256 was checked against the automated report:

```text
25f6196280f3c243ed07b2727eff2c62c452f6e6b4ffdf504845d90dbd7135b3
```

Multiplayer run ID: `984562b1-a4ab-4d73-b143-8adc05348d9e`.
The host PID was 38696 and guest PID 36012. Both reports agree on
that run and both processes exited successfully. The guest joined an already
playing source, used real crouch/right-click and mouse input to insert 19
emeralds into the shared backpack, and both clients observed that inventory.
The run also checked revoked access, owner-only equipment contents versus the
sanitized public appearance, moving audio, an out-of-range/reentry round trip
of four server ticks, and stopping one source without stopping the other.
Reports and captures are under
`.codex-local/client-evidence/multiplayer-984562b1-a4ab-4d73-b143-8adc05348d9e/`.

The full client run exercised a new world, actual mouse and keyboard controls,
twelve physical records, live sound channels, bucket transfer, recipe-browser
transfer/crafting/ghosts/bookmarks/reload, equipment with armor, and save/reopen
checks. It also checked the nine configured chest-loot tables in a normal
world and captured placed animation and appearance. Its save/reopen phase is
not the separate-JVM restart check. The 41 captures are archived under
`build/client-evidence/run11/screenshots/` and mirrored under
`.codex-local/client-evidence/full-screenshots/`.

## Executed toolchain

| Component | Version |
| --- | --- |
| Java runtime and compilation target | Eclipse Temurin 25.0.3+9, Windows x64; Java 25 target |
| Gradle wrapper | 9.5.1 |
| Fabric Loom | 1.17.20 |
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.158.0+26.2 |
| Bundled Team Reborn Energy | 5.0.0 |
| JUnit | 6.1.0 |
| Local Python | 3.14.3 |

## Reproducible checks

See [Building and testing](BUILDING.md) for commands and isolation rules.
The completed checks used the following commands from the repository root.
`JAVA_HOME` selected the Temurin JDK above; `$Minecraft262ClientJar` represents
the exact downloaded Minecraft 26.2 client JAR, with the machine-specific path
omitted here. Gradle tasks were not run concurrently against the checkout.

```powershell
python tools/verify_evidence.py begin
python tools/generate_assets.py --check
python tools/test_assets.py --minecraft-jar $Minecraft262ClientJar
python tools/test_verify_evidence.py
.\gradlew.bat --no-daemon --console=plain --no-build-cache --rerun-tasks test runGameTest build
.\tools\run-multiplayer.ps1 -RunId 984562b1-a4ab-4d73-b143-8adc05348d9e
python tools/verify_evidence.py check
.\gradlew.bat runClientGameTest
.\gradlew.bat runClientGameTest -PclientScenario=restart
```

The combined Gradle command forced execution without the build cache.
The multiplayer launcher prepared the two commands and isolated run
directories before starting the host and guest. These commands record the
completed layers; `check --release` and `releaseBundle` have not passed at
this checkpoint.

The authoritative local machine-readable reports are:

| Evidence | Path |
| --- | --- |
| JUnit cases and failures | `build/test-results/test/TEST-*.xml` |
| JUnit method identities and executions | `build/test-results/test/unit-execution.json` |
| Actual server GameTest cases | `build/gametest-results.xml` |
| Source fingerprint and start time | `build/verification/start.json` |
| Automated report and main-JAR SHA-256 | `build/verification/automated.json` |
| Full client run and captures | `.codex-local/client-evidence/full-pass.json`, `.codex-local/client-evidence/full-screenshots/` |
| Separate-JVM restart | `.codex-local/client-evidence/restart-pass.json`, `.codex-local/client-evidence/restart-screenshots/` |
| Both multiplayer JVM outcomes | `build/verification/multiplayer.json` |
| Installed-JAR observations | `build/verification/manual.json` |
| Complete release gate | `build/verification/release.json` |

The checker rejects changed source/build inputs, empty or stale reports,
skipped/failed cases, missing declared test methods or server test IDs, test
code in the production JAR, mismatched multiplayer run IDs or PIDs, invalid
PNG evidence and a manual record for another artifact. Local test worlds and
machine-specific logs remain outside the published repository.

## What each layer covers

- Unit tests exercise capacity arithmetic, filtering, playlist transitions,
  recipe ingredient assignment, configuration, serialization, protocol bounds
  and generated resources.
- Server GameTests use real Minecraft inventories, recipes, entities,
  block entities and ticking. They cover conservation, persistence, filters,
  resources, upgrades, permissions and rejected actions. Connection fixtures
  capture server packets but do not prove client rendering or audible output.
- Client GameTests create and render a new world, use actual Minecraft input
  handlers, wait for server/client state and capture frames. The pinned Fabric
  test driver's missing keyboard/mouse modifier flags are corrected only in
  the separate test mod; production input is unchanged.
- Restart acceptance opens the archived world in a different JVM and compares
  the recorded backpack, equipment and browser state.
- Multiplayer acceptance uses two distinct Minecraft JVMs and a real local TCP
  connection, shared inventory interactions, privacy checks and moving audio.
  Test files coordinate phases; they do not simulate network packets.
- Manual acceptance opens the built production JAR without the project test
  mod, creates a fresh world through the normal menus and records specific
  visible interactions. It is not inferred from the automated tests.

Placed-animation tests identify their server-created viewer and breaking
fixtures. Offline asset sheets identify themselves as reconstructions from
the production geometry; they are not in-game screenshots.

## Corrections covered by this checkpoint

- Jukebox packets carry bounded registry song identifiers instead of a holder
  from a potentially different registry. Protocol tests exercise actual byte
  encoding/decoding, named songs and duration limits; the real TCP run also
  delivered music to a late listener.
- Rapid tracking removal and reentry invalidate only the affected audio
  listener. The server regression drives real `ChunkMap` pairing callbacks
  with an embedded recipient before the periodic reconciliation boundary.
  It checks scoped stop, pairing before one replay, unchanged start/finish
  times, bounded remaining duration, an untouched second source and no
  resurrection after explicit stop. The separate two-JVM run confirms a
  four-tick range round trip resumes on a new live client sound instance.
- Placed body/trim changes invalidate the cached chunk mesh even when facing
  and block state stay unchanged. Full client run 11 observed native mesh
  invalidations for each dye layer, and none for repeated appearance packets,
  display rotation changes or 25 idle client ticks. State assertions and real
  captures passed. Targeted review of 15 fresh client frames, including the
  recolored body and trim, worn model and UI, also passed.

## Boundaries

No compatibility claim is made for other Minecraft versions, loaders,
third-party accessory APIs, recipe-viewer addons or imported saves from other
backpack mods. Jukebox late listeners join the current track for its remaining
duration; sample-accurate seeking is not implemented. Performance superiority
over another mod has not been measured or claimed.

Public download availability must be checked separately on each release
platform. An upload awaiting moderation is not a public release.
