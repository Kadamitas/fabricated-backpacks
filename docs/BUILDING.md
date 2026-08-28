# Building and testing the 1.21.1 port

Target: **Minecraft 1.21.1, Fabric, Java 21**. The separate branch is
`codex/minecraft-1.21.1-cobblemon`. The Gradle wrapper and `gradle.properties`
define the exact versions. This build is not the existing published 26.2 alpha.

## Build

Install a Java 21 JDK. From the repository root in PowerShell:

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat build
```

On other systems, use `./gradlew`. Loom remaps the older game's Mojang-named
source to its production namespace. Install only
`build/libs/fabricated-backpacks-0.5.0-alpha+mc1.21.1.jar`, not a development or
sources JAR. Fabric API is required separately; Energy API 4.1.0 is embedded.
Do not use a 26.2 world, a personal instance or the 26.2 JAR for these checks.

## Source and assets

Common/server code is under `src/main`, client code under `src/client`, unit
tests under `src/test`, and the separate server test mod under `src/gametest`.
Neither that test mod nor local evidence belongs in the production artifact.

Generated assets retain their original models and textures but use native
1.21.1 resource formats. Edit the generators, not their generated outputs:

```powershell
python tools/generate_assets.py --check
python tools/test_assets.py --minecraft-jar '<exact Minecraft 1.21.1 client JAR>'
```

Loom's original downloaded client JAR is normally at
`<Gradle user home>/caches/fabric-loom/1.21.1/minecraft-client.jar`.
Review sheets are offline asset renders, not game screenshots.

## Unit and server checks

Run one Gradle invocation at a time. Start the verification epoch only after
source, build, tool and README/changelog edits are complete. Fresh checks must
run without cached task outputs:

```powershell
python tools/test_verify_evidence.py
python tools/test_verify_compatibility.py
python tools/verify_compatibility.py begin
.\gradlew.bat --no-daemon --console=plain --no-build-cache --rerun-tasks test runGameTest build
python tools/verify_compatibility.py check
```

For the exact official Cobblemon 1.7.3 Fabric runtime:

```powershell
python tools/verify_compatibility.py begin --with-cobblemon
.\gradlew.bat --no-daemon --console=plain --no-build-cache --rerun-tasks test runGameTest build -PwithCobblemon=true
python tools/verify_compatibility.py check --with-cobblemon
```

The flag adds the normal external mod at runtime and registers two additional
server tests against its real items. These tests are absent from the base run;
when registered they fail if Cobblemon is missing, rather than silently skip.
The build never includes Cobblemon in our distributable.
Loom strips nested libraries from development dependencies, so this flag also
adds the exact Kotlin runtime bundled by Cobblemon to the development classpath.
It also applies the Kotlin Gradle plugin so Loom remaps Cobblemon's Kotlin
metadata alongside its bytecode. This is a build tool, not a new dependency of
our Java mod. Normal installed clients use Cobblemon's own nested runtime.
If a local cache already contains a Cobblemon JAR remapped before this build
configuration, run once with `--refresh-dependencies` to rebuild that cache.

Inspect `build/test-results/test`, `build/gametest-results.xml` and
`build/verification/compatibility-runtime.json`. The runtime witness records
actual loaded versions and registry presence; it is not itself a passing test.
The strict checker also requires complete source-derived test discovery,
unchanged inputs, fresh successful reports and a clean remapped artifact.
`compatibility-start.json` and `compatibility.json` are separate from historical
26.2 evidence and have unit/server scope only.

## Installed-client acceptance

The 26.2 Fabric client GameTest API is unavailable on 1.21.1. Those source files
are explicitly excluded from this test mod. `runClientGameTest` fails clearly;
it does not execute an empty suite. The 26.2 multiplayer launcher likewise is
not a 1.21.1 acceptance harness.

An isolated installed-JAR client can be started when manual checks are intended:

```powershell
.\gradlew.bat runProductionClient --rerun -PwithCobblemon=true -PwithJei=true
```

This loads the remapped main artifact and optional real dependencies, not the
test mod. It uses `.codex-local/manual-client` and does not select a personal
world. `--rerun` forces the launch task itself; confirm a new Minecraft PID and
fresh log. A Gradle success alone is not evidence that Minecraft opened.

Create a new world through the game's menus. Check B while worn, the readable
backpack/JEI screens and ghost filters, rear/side worn models with armor and
crouching, Pokémon riding, aimed single-strand conduit mining, and exact item,
fluid and energy movement between backpacks. Then test saving/reopening and a
second real network client. Record observations only after they occur.

B remains the open-backpack key. V opens the built-in browser to avoid
Cobblemon's O key. All registered actions can be rebound in Controls; existing
saved choices are not overwritten.

## Publication boundary

The compatibility checker deliberately rejects release mode. The older
`verify_evidence.py --release` gate and historical 26.2 receipts cannot certify
this target. `releaseBundle` is blocked until target-specific rendered-client,
restart, multiplayer and installed-artifact acceptance are implemented and
observed. Do not create a replacement pass JSON or reuse another build's images.

A pushed development branch or CI artifact is not a published playable release.
The original immutable 26.2 alpha must not be overwritten with this port.
