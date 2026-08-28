# Building and testing

Target: **Minecraft 26.2, Fabric, Java 25**. The checked-in Gradle wrapper and
`gradle.properties` define the toolchain and dependency versions.

## Local build

Install a Java 25 JDK and make it available to Gradle. On Windows, use
PowerShell from the repository root:

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat build
```

On systems with a POSIX shell, use `./gradlew` in place of `gradlew.bat`.
Gradle may download the configured toolchain and dependencies on the first run.
Do not point development runs at an existing personal world.

The main artifact is
`build/libs/fabricated-backpacks-0.5.0-alpha.jar`. The adjacent sources JAR is
for development and is not installed in Minecraft. The runtime JAR includes
the configured Team Reborn Energy dependency; Fabric Loader and Fabric API
remain instance dependencies.

## Source layout

| Path | Purpose |
| --- | --- |
| `src/main/java` | Common/server rules, registries, storage, upgrades, menus and protocol |
| `src/client/java` | Client screens, recipe browser, geometry and sound |
| `src/main/resources` | Production metadata, generated assets, recipes and tags |
| `src/test/java` | Unit, protocol and resource audits |
| `src/gametest` | Separate server/client acceptance test mod and fixtures |
| `tools` | Deterministic asset generation and standalone resource tests |

Client-only classes must not be loaded by production server entry points.
The GameTest mod and its fixtures must not appear in the production JAR.

## Deterministic assets

Python 3.10 or newer is sufficient; the asset tools use the standard library.

```powershell
python tools/generate_assets.py
python tools/generate_assets.py --check --review
python tools/test_assets.py --minecraft-jar '<path to the exact Minecraft 26.2 client JAR>'
```

The exact game JAR check resolves vanilla recipe ingredients and model parents;
use the matching 26.2 JAR, not a different version. Loom stores its downloaded
game files in Gradle's cache.

Edit the generator or its explicit language inputs, not generated files.
Regenerate after changes to the upgrade catalog,
`tools/assets/ui_strings.json` or `tools/assets/browser_strings.json`.
The generated manifest records input and output hashes; a stale manifest is a
test failure, not something to bypass.

Offline review sheets are written to `build/reports/asset-audit`. Their contents
are rendered from production cuboids and textures. They are not in-game
screenshots. Geometry, UVs and tint contracts are documented in
[Asset pipeline](../tools/ASSET_PIPELINE.md).

## Verification layers

Run each layer explicitly and inspect its output. Avoid simultaneous Gradle
runs against the same checkout or shared development world.

```powershell
.\gradlew.bat test
.\gradlew.bat runGameTest
.\gradlew.bat runClientGameTest
.\gradlew.bat runClientGameTest -PclientScenario=restart
.\tools\run-multiplayer.ps1
.\gradlew.bat build
```

Stop and investigate a failing command before treating later output as a
release result. The unit-test HTML/XML reports are under
`build/reports/tests/test` and `build/test-results/test`. Development game
logs and screenshots live under the configured Loom/Fabric run directories;
acceptance captures may also be collected under `build/client-evidence`.
The full client test archives its closed world and expectations under
`.codex-local/client-evidence`. The `restart` scenario must run in a subsequent
JVM: it verifies that archived world, equipment, stored items and bookmarks.
Loom clears the normal test run directory before a new client run, so preserve
failure logs and screenshots before retrying.

The PowerShell multiplayer launcher prepares both launch commands in one
Gradle invocation, then starts two separate Minecraft JVMs with different
usernames and run directories. It requires both processes to finish
successfully and matching reports from the real TCP session. A server player
fixture is not a substitute for that check. `-PrepareOnly` validates the launch
commands without starting Minecraft and never creates a passing result.

| Layer | What it establishes | What it does not establish |
| --- | --- | --- |
| Unit/resource tests | Exercised rules, protocol bounds, generated-file hashes and resource structure | Actual GUI or dedicated-server behavior |
| Server GameTests | Exercised Minecraft inventory, recipe, entity and transfer behavior | Human client interaction or a second real client |
| Client GameTests | The actual client actions and frames captured by that run | Every upgrade combination or multiplayer scenario |
| Manual acceptance | The specifically recorded observations | Unrecorded features or blanket compatibility |
| Profiling | Measurements for a documented workload and environment | Superiority to other software without a fair comparison |

Server connection fixtures are identified as fixtures in the tests. A fixture
receiving a packet is not evidence that a real client rendered the result.
Likewise, opening a screen successfully does not establish inventory
conservation or persistence.

Maintain the build's [Verification record](VERIFICATION.md) with commands,
toolchain, exact artifact hash, discovered/executed/skipped test counts,
failures, screenshot paths and remaining acceptance work. Do not replace it
with a cumulative pass count from earlier edits.

## Packaging and release

After source, tools, CI, README and changelog files are final, start a fresh
verification record.
Then rerun all required checks; cached reports from an earlier edit do not count:

The evidence checker requires **Python 3.11 or newer** and uses only the standard
library. The asset generator itself also supports Python 3.10.

```powershell
python tools/verify_evidence.py begin
.\gradlew.bat test build --rerun-tasks
.\gradlew.bat runGameTest
.\gradlew.bat runClientGameTest
.\gradlew.bat runClientGameTest -PclientScenario=restart
.\tools\run-multiplayer.ps1
python tools/verify_evidence.py check
```

Inspect the built JAR in a separate Minecraft instance with a newly created
world and no development test mod. Record the exact artifact hash, specific
observations and real screenshots in `build/verification/manual.json`.
That record is written only after the observed checks, never before them.

Its required fields are `passed` (true only after completion),
`artifact_sha256` (the main JAR's lowercase SHA-256), `observations` (a nonempty
list of specific observed behaviors) and `screenshots` (a nonempty list of
distinct project-relative PNG paths, for example
`.codex-local/manual-evidence/01-new-world.png`). Absolute paths and `..` are
rejected. Capture the actual game window after verification starts; do not use
an asset render or an earlier build's screenshot. Record any failed observation
as a failure and resolve it before setting `passed` to true.

Use the task-specific `--rerun` option for every manual launch and restart:

```powershell
.\gradlew.bat runProductionClient --rerun
```

Without this option, Gradle can mark `runProductionClient` as `UP-TO-DATE` and
finish without starting a JVM. `--rerun` forces the launch task to execute
without forcing its build dependencies to rerun; do not substitute the global
`--rerun-tasks` option. Confirm a new Minecraft process/PID and fresh startup
log for the intended JAR. A successful Gradle exit alone is not a launch check.

The task opens an isolated instance under `.codex-local/manual-client`.
It loads the built main JAR and the declared
Fabric API JAR using Loom's
[production client task](https://docs.fabricmc.net/develop/loom/production-run-tasks).
It does not include this project's test mod or substitute compiled source
directories for the distributable. Create a fresh world there through the
normal game menus, exercise the features and exit the game normally.

Collect the release artifacts after the recorded checks pass:

```powershell
.\gradlew.bat releaseBundle
```

This task writes main/sources JARs and SHA-256 files under
`release/0.5.0-alpha`. It runs `verifyReleaseEvidence`, which requires fresh,
nonempty passing unit/server reports, unchanged source inputs, separate-JVM
restart evidence, both multiplayer process results and the installed-JAR
observations. It does not run those client checks for you. Failed, skipped,
missing or stale evidence blocks the bundle.

Inspect the final JAR contents, metadata and hash. Only production code,
production resources and declared bundled dependencies belong in it. Test
fixtures, local evidence, private planning files and development caches must
remain outside the distributable.

Publishing is a separate step. A local artifact, successful upload request or
pending moderation state is not confirmation that a release is publicly
available.
