# Verification record

Target: **Fabricated Backpacks 0.5.0-alpha**, Minecraft **26.2**, Fabric,
Java **25**. This record covers the tested build, not all possible modpacks or
upgrade combinations.

## Release status

Checkpoint: **2026-08-28**. The current unit/server build, hosted Linux CI,
full client run 14, separate-JVM restart, two-client TCP scenario, targeted
visual review and installed production-JAR acceptance passed. The production
world also survived a separate production JVM restart. The complete release
gate and local bundle passed. The previous candidate's client run 13 **failed** its
rendered search-hint bounds check at GUI scale 3; the replacement correction
passed that same strict check. Earlier results and failures are identified
separately below.

The [GitHub alpha release](https://github.com/Kadamitas/fabricated-backpacks/releases/tag/v0.5.0-alpha)
is public. Its main JAR, sources JAR and both checksum files were downloaded
without authentication and matched the verified local bundle. CurseForge and
Modrinth submissions remain **pending**; neither has a published project or
download for this release. Earlier development runs and interactions with an
earlier JAR do not substitute for checks against the current source snapshot
and artifact.

## Current executed results

The source-input snapshot is verification run
`8aacec34-2532-40de-8e99-226b86ba961f`, started at
`2026-08-28T10:06:13Z`, with 624 hashed inputs. Its automated unit/server gate
passed at `2026-08-28T10:07:30Z`. Commit `3316164` uses the concise existing
Search items hint while retaining the full search help in its tooltip and
narration. Recipe catalog and transfer behavior are unchanged.

| Check | Current result | Local evidence |
| --- | --- | --- |
| JUnit, combined run 19 | **PASS:** 374 invocations, 133 declared test methods, 21 test classes; 0 failures, errors or skips | `build/test-results/test/TEST-*.xml`, `build/test-results/test/unit-execution.json` |
| Server GameTests, combined run 19 | **PASS:** 137 executed, exactly 136 mod tests and 1 vanilla test; 0 failures, errors or skips | `build/gametest-results-run19.xml`, `build/combined-run19.log` |
| Server repeat, run 20 | **PASS:** the bundle's build dependency reran the same 137 tests; 0 failures, errors or skips | `build/gametest-results-run20.xml`, `build/release-bundle-final.log` |
| Deterministic assets | **PASS:** 358 generated files current; 23 Python asset tests passed against the exact Minecraft 26.2 JAR, including exact RGBA round trips for all 94 PNGs | `build/asset-run19.log` |
| Evidence-checker tests | **PASS:** 37 Python tests | `build/gate-tests19.log` |
| Build and automated evidence gate | **PASS:** unit/server discovery, source-input consistency and production-JAR exclusions checked | `build/verification/automated.json` |
| Full client, run 14 | **PASS:** PID 28076, exit code 0, 42 PNG captures; actual rendered hint bounds, full-help tooltip/narration state and existing scenario assertions passed; Gradle run completed in 1 minute 27 seconds | `build/client-evidence/run14/`, `.codex-local/client-evidence/full-pass.json` |
| Multiplayer, run 7 | **PASS:** host PID 7740 and guest PID 11996, both exit codes 0, 22 phase reports and 6 PNG captures | `build/verification/multiplayer.json` |
| Separate-JVM restart, run 4 | **PASS:** writer PID 28076, reader PID 12672, exit code 0, 2 PNG captures; Gradle run completed in 27 seconds | `build/client-evidence/restart4/`, `.codex-local/client-evidence/restart-pass.json` |
| Targeted visual review, run 14 | **PASS:** 16 fresh frames reviewed, including the empty search field at GUI scale 3; no visible blocker in that sample | `build/client-evidence/run14/screenshots/` |
| Installed production JAR and production restart | **PASS:** PID 5600 created and exercised a new Survival world; independent PID 31840 reopened it with contents preserved; both exit codes 0; 13 observations and 28 raw F2 PNGs | `build/verification/manual.json`, `build/production-manual3.log`, `build/production-manual4-restart.log` |
| Complete release gate | **PASS:** current source fingerprint, artifact, automated/client/multiplayer/restart reports and installed-JAR observations checked | `build/verification/release.json` |
| Release bundle | **PASS:** four files produced; Gradle completed in 23 seconds, exit code 0; main JAR unchanged | `release/0.5.0-alpha/`, `build/release-bundle-final.log` |
| Hosted Linux CI for `3316164` | **PASS:** all workflow steps succeeded; 374 unit and 137 server cases, with 0 failures, errors or skips; source-input and artifact checks passed | [Successful run 33162066369](https://github.com/Kadamitas/fabricated-backpacks/actions/runs/33162066369) |
| Public GitHub downloads | **PASS:** public release page and all four assets returned HTTP 200 without authentication; downloaded hashes match the local bundle | [Release v0.5.0-alpha](https://github.com/Kadamitas/fabricated-backpacks/releases/tag/v0.5.0-alpha), `.codex-local/github-publication.json` |
| CurseForge and Modrinth | **PENDING:** prepared project forms have not been submitted; no public downloads | Each platform must be checked separately |

The main artifact is `build/libs/fabricated-backpacks-0.5.0-alpha.jar`, with
1,018,130 bytes and 713 archive entries. Its SHA-256 was checked against the
automated report:

```text
a91475783e00ac3a8596c27fec77fdbfe6145a47b3cd6b306c2764e7180c6c4d
```

The local bundle under `release/0.5.0-alpha/` contains that unchanged main JAR,
the 460,669-byte sources JAR and one SHA-256 file for each. The complete release
gate passed at `2026-08-28T10:32:52Z` for the same 624-input snapshot and main
artifact hash. The bundle is not evidence of a completed platform upload.

GitHub release `v0.5.0-alpha` was published at `2026-08-28T10:38:52Z`, targeting
commit `65319247b032ae7f7e113bb622b4de97b5c62510`. Its
[hosted CI run](https://github.com/Kadamitas/fabricated-backpacks/actions/runs/33164083341)
also completed successfully. That commit adds only the
verification documentation and unedited gameplay gallery to the tested code;
all 624 source inputs are unchanged. Public HTTP downloads at
`2026-08-28T10:39:22Z` verified
the following assets, including their GitHub-reported SHA-256 digests:

| Asset | Bytes | SHA-256 |
| --- | ---: | --- |
| `fabricated-backpacks-0.5.0-alpha.jar` | 1,018,130 | `a91475783e00ac3a8596c27fec77fdbfe6145a47b3cd6b306c2764e7180c6c4d` |
| `fabricated-backpacks-0.5.0-alpha-sources.jar` | 460,669 | `e7841510d60860b63f2631e9658027d220503b577be3bb21b0127261955193ea` |
| `fabricated-backpacks-0.5.0-alpha.jar.sha256` | 103 | `de522aa410949b2cac3ea8faa736604ab740db12d67e322bee33cef77918837f` |
| `fabricated-backpacks-0.5.0-alpha-sources.jar.sha256` | 111 | `cb4888b6398b932f91c301b8743e1a6ed2842670b1b180d15c32d0474b780b72` |

Both downloaded JARs also passed ZIP CRC checks. These requests sent no
Authorization or Cookie header. Selected production captures are available in
the [gameplay gallery](GALLERY.md); local worlds and private run directories
are not release assets.

The tested production-code commit is
[`33161645935b135af2d3ce01ae8caf7fd53cc315`](https://github.com/Kadamitas/fabricated-backpacks/commit/33161645935b135af2d3ce01ae8caf7fd53cc315).
The Linux unit/server workflow does not establish installed-JAR manual or
client acceptance. The SHA-256 above identifies the local Windows artifact;
no cross-platform JAR byte-identity claim is made.

Full client run 14 exercised actual mouse/key controls, twelve physical
records, live sound instances, bucket transfer, native browser transfer and
crafting, ghosts/bookmarks/reload, equipment alongside armor, configured
layouts and save/reopen. It also checked the nine configured chest-loot
tables in a normal world and captured placed animation, all four facings and
body/trim mesh updates. The empty-search regression checks actual text bounds
including shadow at GUI scale 3, plus retained help in the tooltip and
narration state. It does not infer fit from a screenshot alone.

The reviewed run-14 frame IDs are 0001, 0003, 0004, 0007, 0008, 0019, 0020,
0022, 0028, 0029, 0031, 0033, 0034, 0035, 0036 and 0040. This is a targeted
16-frame sample, not a review of all 42 captures or every GUI scale.

Multiplayer run 7 is `d5a82d3e-dab2-4922-8966-029bc6e0ea28`, completed at
`2026-08-28T10:11:16Z`. The actual TCP host and guest had distinct JVMs and
profiles. Both saw 19 emeralds inserted into the shared backpack with real
mouse input. Revocation closed the guest menu, and the server observed and
rejected an actual crouch/right-click reopen at tick 310. The guest's remote
equipment attachment stayed empty while the sanitized public appearance was
present; private contents arrived only through the authorized shared menu.

The guest joined music already playing before connection. Range exit at tick
319 and reentry at tick 323 stopped the old sound and resumed on a new live
instance without changing the song's start/finish ticks, 40/6960. With two
record sources active, stopping the worn source retained the same placed-source
instance; the final scoped stop removed the scenario's remaining record
instance. This tests actual `SoundManager` instances, not global silence.
The 22 reports agree on the run and process IDs, and the launch/runtime
fingerprints passed the read-only multiplayer validation against this source
snapshot. Evidence is under
`.codex-local/client-evidence/multiplayer-d5a82d3e-dab2-4922-8966-029bc6e0ea28/`.

## Installed production-JAR acceptance

Production session 3, PID 5600, loaded the exact main JAR above without the
project test mod. It created **Fabricated Backpacks 0.5.0 Final** through the
normal menus as Survival, Peaceful and Superflat with commands enabled. This
world was never switched to Creative. Twenty vanilla `/give` commands supplied
loose fixtures; no command or save edit populated the backpack's inventories,
upgrade slots, records or fluid.

Actual mouse/key interactions covered the following sequence:

- Installed advanced jukebox, crafting and tank upgrades; inserted 32 stone
  and 8 oak planks; transferred one water bucket into the tank, showing
  1,000 / 36,000 mB with an empty bucket returned.
- Inserted twelve distinct physical records into the 3-by-4 grid. Play
  highlighted Blocks, Next highlighted Cat, Previous returned to Blocks and
  Stop removed the active highlight without losing records.
- Checked the concise empty-search hint at GUI scale 3, transferred one
  complete crafting-table recipe and took the actual output. Four planks
  remained, and the player inventory held one crafted table.
- Equipped the bag in its native slot alongside a diamond chestplate, viewed
  both in third person and opened the worn bag with **B** and empty hands.
- Placed and opened the backpack in Survival, then mined it with the native
  attack toggle. The actual breaking overlay appeared, the block disappeared
  and the recovered item retained its contents. A vanilla teleport positioned
  and aimed the player before placement, and a time command set lighting;
  recovery used no teleport or item-recovery command.

The session reequipped the recovered backpack, saved and quit through normal
menus and exited with code 0. Its Gradle invocation completed in 12 minutes
30 seconds. Independent production JVM PID 31840 then opened the same world
through the world list. **G** showed the equipped bag; **B** reopened all twelve
records, 32 stone, 4 planks and 1,000 mB water. The player inventory retained
the crafted table and empty bucket, and the rear view retained the bag with
the chestplate. Normal save-and-quit and exit completed with code 0 in a
5-minute-19-second Gradle invocation. Both logs record all dimensions saved:
`build/production-manual3.log` and `build/production-manual4-restart.log`.

An intervening launch without `--rerun` was `UP-TO-DATE` and created no Minecraft
process. It is excluded from restart evidence and retained in
`build/production-manual4-up-to-date.log`. The actual restart used task-specific
`runProductionClient --rerun` and the unchanged JAR, with the new PID confirmed.

`build/verification/manual.json` records 13 observations and 28 unedited F2
PNGs under `.codex-local/manual-evidence/0.5.0-alpha-final/`. Every archived PNG
matches its recorded original F2 capture. Targeted review included nine frames
from the initial production session and restart frames 25, 26 and 27 showing
equipment, preserved items/resources and worn armor. This does not claim every
upgrade or every frame was manually reviewed. Playback was observed through
UI state; physical speaker output was not assessed. The live sound-instance
checks belong to the separate automated client and two-client runs. The
offline test profile also does not establish authenticated online or Realms
compatibility.

## Previous checkpoint and production finding

The following results belong to snapshot
`bd62a734-b861-41a2-a2eb-4736ebc25d23` and the earlier JAR with SHA-256
`5133fa6cf9853e8578f5a56f614ff228e9b40e02df79f4a57dc04e7fbb1b9b1d`.
They are retained as prior evidence, not passing receipts for the current JAR.
Combined run 16 passed 374 unit and 137 server cases; server repeat 17 passed
the same 137 cases in 21 seconds. The prior [hosted run 33159328294](https://github.com/Kadamitas/fabricated-backpacks/actions/runs/33159328294)
also passed for commit `d3a4df5`.

Prior multiplayer run 6, `d500a2db-d9a2-4580-b5a6-11b51204b739`, passed with
host PID 16012 and guest PID 1600, both exit codes 0. Its 22 phase reports and
six captures include a four-tick range round trip, ticks 325 to 329, with the
same song start/finish. Reports and captures remain under
`.codex-local/client-evidence/multiplayer-d500a2db-d9a2-4580-b5a6-11b51204b739/`.

Full client run 12, PID 37744, passed in 1 minute 27 seconds, exercising the
client scenario before the added empty-search regression. Its 41 captures are
archived under
`build/client-evidence/run12/screenshots/`. Restart run 3 used writer PID 37744
and a different reader JVM, PID 11548; it passed in 26 seconds with two captures
under `build/client-evidence/restart3/`. Targeted visual review found no blockers
in 15 run-12 frames: 0001, 0003, 0004, 0007, 0018, 0019, 0021, 0027, 0028, 0030,
0032, 0033, 0034, 0035 and 0039. This was not a review of every frame or GUI scale.

The first installed production session, PID 36720, used that earlier JAR
without the project test mod. It created **Fabricated Backpacks 0.5.0 QA**
through normal menus as Superflat, Creative and Peaceful with commands enabled.
Actual mouse/key interactions exercised twelve physical record slots and their
controls, 1,000 mB tank storage, browser ingredient transfer and one crafted
table, worn equipment alongside armor, Survival placement/mining/drop/pickup,
and save-and-quit. The session found an overflowing empty-search hint at GUI
scale 3, so those completed interactions did not close final acceptance.
Evidence remains in `build/production-manual2.log`. The UI correction changes
the hint presentation, not recipe catalog or transfer behavior.

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
The recorded checks used the following commands from the repository root;
the tables above distinguish current, prior and failed outcomes.
`JAVA_HOME` selected the Temurin JDK above; `$Minecraft262ClientJar` represents
the exact downloaded Minecraft 26.2 client JAR, with the machine-specific path
omitted here. Gradle tasks were not run concurrently against the checkout.

```powershell
python tools/verify_evidence.py begin
python tools/generate_assets.py --check
python tools/test_assets.py --minecraft-jar $Minecraft262ClientJar
python tools/test_verify_evidence.py
.\gradlew.bat --no-daemon --console=plain --no-build-cache --rerun-tasks test runGameTest build
.\tools\run-multiplayer.ps1 -RunId d5a82d3e-dab2-4922-8966-029bc6e0ea28
python tools/verify_evidence.py check
.\gradlew.bat runClientGameTest
.\gradlew.bat runClientGameTest -PclientScenario=restart
.\gradlew.bat runProductionClient --rerun
python tools/verify_evidence.py check --release
.\gradlew.bat releaseBundle
```

Combined run 19 forced execution without the build cache and completed in 44
seconds. The prior checkpoint's server repeat 17 used
`.\gradlew.bat --no-daemon --console=plain runGameTest` and completed in 21 seconds.
The multiplayer launcher prepared the two commands and isolated run
directories before starting the host and guest. These commands record the
executed layers. Use task-specific `--rerun` for every manual production launch
or restart, and confirm a fresh process and startup log; it does not force
unchanged build dependencies to rerun. The complete release check passed.
`releaseBundle` then reran 137 server cases, rechecked release evidence and
produced its four files in 23 seconds without changing the main JAR.

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
  Test files coordinate phases; they do not simulate network packets. The
  prepared test classpaths are distinct from installed production-JAR testing.
- Manual acceptance opens the built production JAR without the project test
  mod, creates a fresh world through the normal menus and records specific
  visible interactions. It is not inferred from the automated tests.

Placed-animation tests identify their server-created viewer and breaking
fixtures. Offline asset sheets identify themselves as reconstructions from
the production geometry; they are not in-game screenshots.

## Runtime corrections and their checks

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
  and block state stay unchanged. Full client run 14 observed native mesh
  invalidations for each dye layer, and none for repeated appearance packets,
  display rotation changes or 25 idle client ticks. State assertions and real
  captures passed. Targeted review of 16 fresh client frames, including the
  recolored body and trim, worn model and UI, also passed.

## Earlier failures and their resolution

The [first hosted Linux run](https://github.com/Kadamitas/fabricated-backpacks/actions/runs/33158223653)
failed the exact-byte generated-asset check because
the platform's PNG compressor produced different bytes. The encoder now emits
explicit stored DEFLATE blocks instead of delegating compression choices to
the platform. Exact-byte checks passed with local Python 3.13.2/zlib and
3.14.3/zlib-ng, and the current Linux CI passed. All 94 PNGs retain the same
dimensions and decoded RGBA pixels. The current 23 asset tests include portable
encoding vectors, block boundaries/checksums and exact pixel round trips.

Combined run 15 and [the second hosted run](https://github.com/Kadamitas/fabricated-backpacks/actions/runs/33159054060)
each failed `configured_magnet_experience_cadence`; the hosted run had 374
passing unit cases and 136 of 137 passing server cases. The Minecraft 26.2 XP
orb constructor generates random launch motion even when its direction
argument is `Vec3.ZERO`. The fixture disabled gravity but did not clear that
motion, so an orb waiting for the seven-tick deadline could drift out of range.
The correction changes only the test fixture: explicitly zero its velocity and
assert its position remains fixed while waiting. It retains the exact cadence,
range and resource-conservation assertions, without extending the wait or
changing production behavior. Combined run 16, server repeat 17 and the latest
hosted CI all pass that case.

The production session above exposed empty-search hint overflow at GUI scale
3. The first correction shortened the visible hint and retained the full help
as a tooltip. Its snapshot `b6c12f67-78ef-4056-b5fe-e74fa561d120`, commit
`7f1a034`, passed combined run 18 and [hosted CI 33161671454](https://github.com/Kadamitas/fabricated-backpacks/actions/runs/33161671454),
including 374 unit and 137 server cases. Full client run 13 then **failed** its
new actual rendered-bounds assertion: the hint including shadow was 138 pixels
wide from x=12, overlapping the recipe-title area. No passing client receipt
was written; `build/client-run13.log` records the failure. The replacement
candidate uses the concise Search items hint and preserves the same strict
bounds assertion. Fresh full client run 14 passed it, and the actual empty
search-field capture was included in the visual review.

## Boundaries

No compatibility claim is made for other Minecraft versions, loaders,
third-party accessory APIs, recipe-viewer addons or imported saves from other
backpack mods. Jukebox late listeners join the current track for its remaining
duration; sample-accurate seeking is not implemented. Performance superiority
over another mod has not been measured or claimed.

Public download availability must be checked separately on each release
platform. An upload awaiting moderation is not a public release.
