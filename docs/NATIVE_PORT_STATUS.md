# Native NeoForge port

Target: Fabricated Backpacks **1.0.0+mc26.3**, Minecraft **26.3**, NeoForge **26.3.0.7-beta**.

This branch is an unfinished source port, not a release artifact. It is not a
Fabric JAR relabeled for another loader. The native build intentionally has no
Fabric Loader, Fabric API, or Team Reborn Energy runtime dependency.

## Implemented

- Real native Gradle toolchain, loader descriptor, Java 25, and loader-specific artifact name.
- Production includes both former Fabric source sets. Every copied server/client test source remains present; `check` also requires test-mod compilation.
- Native FML config/bookmark paths preserve the existing config filenames.
- Conduit accounting now uses NeoForge `SnapshotJournal` and native transactions.
- Battery persistence now journals through the native transaction; its capability boundary keeps stored counts as 64-bit values.
- Native item/fluid handler boundary preserves long counts and 81-droplet millibucket conversion, rolling back fractional transfers before retrying a whole native unit.
- A focused native-transaction regression suite is available with `./gradlew -p tools/native-transfer-tests test`. It exercises real NeoForge transaction classes but is not a substitute for the full build or Minecraft GameTests.

## Still required before release

- Port the mod entrypoint, deferred registries, attachments, lifecycle/player events, menu opening, and typed networking.
- Wire every internal storage/capability provider and external item/fluid/energy adapter to the native loader. Do not replace rollback-safe transfers with irreversible execute/simulate calls.
- Port client model/renderer/screen/tooltip registrations and preserve the entire visible feature set.
- Adapt copied GameTest registration and client input/evidence collection to a native launch; retain all original assertions and fixtures.
- Update CI asset discovery and evidence parsing to native reports. The current Fabric-oriented evidence checker must not be bypassed or interpreted as native success.
- Copy the final, verified main-branch test fixes into this branch before final verification.

The root `build` and `releaseBundle` gates remain mandatory. A passing focused
transfer test does **not** establish that this mod loads or that it is ready to
publish.
