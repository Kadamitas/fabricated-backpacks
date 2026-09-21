# Automated release verification

The owner requested no manual Minecraft testing. Stable releases use the
`release-automated` evidence scope: fresh unit and server tests, production-JAR
audit, full automated client acceptance, a separate-JVM saved-world restart,
and actual two-JVM multiplayer acceptance remain required.

CI runs clients on a virtual display without controlling the owner's desktop.
Software audio checks decoding and client sound channels, not human-audible
output. Manual installed-JAR observations are explicitly absent, never marked
as passing. The optional `--release` checker retains its stricter manual gate.
