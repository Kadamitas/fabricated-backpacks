# Quilt development

Target: Minecraft 26.3, Quilt Loader 0.30.1, Java 25, Fabric API 0.161.0+26.3.
Quilt 0.30.1 exposes Fabric Loader 0.19.3 compatibility. The Quilt artifact
therefore has its own loader constraints; the Fabric branch requires 0.19.5.

This branch retains the main branch's complete source and test suites.
The unit-test session uses Quilt's transformed class loader. Unit checks still
do not replace the real server and rendered-client game-launch tests.
Run `./gradlew test runGameTest runClientGameTest` before a release.

The development launch translates Fabric Loom's settings to Quilt's loader
settings and replaces Fabric Loader on the runtime classpath.
This branch is not a verified release until those runs pass.

## Development bootstrap compatibility

Quilt Loader 0.30.1's development transform path can discard its own patched
Minecraft startup classes when no access or side-stripping visitors are needed.
The development mod's `quilt-development-bootstrap.accesswidener` names already-public startup
classes so Quilt retains its own initialization hooks. It does not invoke mod
entrypoints itself, unfreeze registries, or change assertions, and it is not
included in the release JAR. The production transform path retains those hooks
without this development-only workaround. The same development bootstrap is
used by ordinary client/server runs, game tests, IDE launches, and the separate
JVM multiplayer launcher. Neither it nor the test mod is included in release
artifacts. The installed-JAR production task launches Quilt directly.
