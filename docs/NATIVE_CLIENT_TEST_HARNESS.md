# Native client-test harness

The test-only `com.kadamitas.fabricatedbackpacks.testplatform` implementation is
adapted from Fabric API's `fabric-client-gametest-api-v1` version
`6.0.7+4be74c3f5d`, obtained from the official Fabric Maven source artifact.
Original copyright notices and the Apache-2.0 license are retained; the license
is in the test resources under `META-INF/LICENSE-native-client-test-harness.txt`.

Changes replace Fabric Loader entrypoint discovery with this test mod's explicit
client scenario, use a development-only Forge payload channel for its sync packet, and
resolve the game directory through FML. The upstream tick scheduler, real-world
creation, SDL input, screenshots and assertion machinery are retained, relocated
to a project-owned test namespace. The test infrastructure is not a Fabric
runtime compatibility layer and is never part of the production JAR.

`runClientGameTest` opts into the test mixins and runs the copied acceptance
scenario. `-PclientScenario=restart` selects the existing separate-JVM restart
check. Ordinary development runs and dedicated-server GameTests do not enable
the client harness. This document describes the implementation, not a passing
verification result; the native runs must complete before release.
