# Automation revision verification

This record covers the **UNRELEASED** conduit and steam-engine revision for
Minecraft 26.2 on Fabric. The published `v0.5.0-alpha` artifact remains
unchanged. The local candidate below is not a replacement publication,
despite retaining the alpha filename.

## Scope

At the user's request, in-game acceptance is limited to the new conduits,
steam engine, wrench interactions, native transfer interfaces, multiplayer
configuration and save/reload. Existing backpack, cooking and music walkthroughs
are not repeated in the focused client scenarios. The existing full walkthrough
remains available separately.

The complete unit and server suites still run without interactive walkthroughs.
Focused reports are named `automation-pass.json`, `automation-restart-pass.json`
and `multiplayer-automation.json`; they do not replace or impersonate the full
client/release receipts.

## Rendered-client checkpoint — 28 August 2026

Epoch 11 is `7566096f-b918-44d5-8869-7590b7850db9`.
The 749 input hashes matched the files on disk for commit `762375d`.
The later indexed-storage correction below has a separate verification record. Earlier failed attempts are not counted as
passes in this checkpoint.

Candidate: `build/libs/fabricated-backpacks-0.5.0-alpha.jar`, **1,381,657 bytes**.
Its SHA-256 was read from the file and agrees with
`build/verification/automated.json`:

```text
b73714812e0353deced26fff765031770eca71f5f2177eeafc8ede9bf348db56
```

| Check | Final result | Process evidence |
| --- | --- | --- |
| Complete unit suite | 494 invocations, 189 methods, 30 classes; zero failures, errors or skips | PID 42940 |
| Complete server GameTests | 168 cases, including 167 mod cases; zero failures, errors or skips | Actual server XML and execution log |
| Focused automation client | Passed all 10 recorded checks, including same-JVM save/reopen | PID 39920 |
| Separate-JVM restart | Passed all 3 recorded checks | Writer PID 39920; reader PID 42580 |
| Optional JEI integration | Passed all 3 recorded checks with JEI 30.28.0.191 | PID 41720 |
| Focused multiplayer | Both clients passed 8 checks and exited with code 0; 26 phases and 11 screenshots | Host PID 42120; guest PID 5980 |
| Exact-target asset audit | 31 tests passed | `build/automation-assets-final-11.log` |
| Evidence-checker tests | 37 tests passed | `build/automation-evidence-tests-final-11.log` |

The unit and server counts were also checked directly against
`build/test-results/test/TEST-*.xml` and `build/gametest-results.xml`.
Their gate has scope `unit-and-server`, not `release`.

The focused client checks exercise actual mouse/keyboard interactions:
Survival strand mining and reinstallation, engine fuel/water slots and bucket
remainders, natural generation, machine-side permissions, physical conduit
interfaces, disabled-stub restoration, and transfers between real backpacks.
Item/fluid allow and block filters preserve item components and exact
fluid quantities. Save/reopen and the separate JVM restore exact saved
state, then demonstrate new routing and resumed engine generation.

The optional JEI run used the real JEI runtime: native search and
item/fluid drags set canonical ghost IDs without granting or consuming items.
This is evidence for JEI 30.28.0.191, not blanket compatibility with other mods.
The standard runs loaded Fabric Loader 0.19.3; the JEI run loaded 0.19.4.

Multiplayer run `1c84a754-c257-4aa4-85a4-f549a9461aa7` used two distinct
Minecraft JVMs over real TCP. Its receipt completed at
`2026-08-28T20:39:55.0894504Z`. All 11 reported PNGs exist: five host and six
guest captures. The guest's native picker set the east item filter to
`ALLOW`, ghost 0 to `minecraft:cobblestone`; the host's already-open menu
received both values. Both menu instances and the physical conduit were
retained, all 11 other face/resource policies and both cursors were unchanged.
Remote Survival mining removed only the fluid strand, yielded exactly one
fluid conduit, and retained the item/energy strands on both clients and the
server. Subsequent natural routing delivered another 160 FE to the sink.

Primary local client receipts are `automation-pass.json`,
`automation-restart-pass.json` and `automation-jei-pass.json` under
`.codex-local/client-evidence/`. The multiplayer completion receipt is
`build/verification/multiplayer-automation.json`; its run directory contains
the host/guest pass records, phase records and screenshots.

The corresponding execution logs are `build/automation-verification-11.log`
and `build/automation-{client,restart,jei,multiplayer}-final-11.log`.
Screenshots are captures for inspection, not an automated visual-quality or
performance claim.

## Indexed-storage review correction

A subsequent review found that the generic item wrapper could restart a bounded
conduit scan before reaching a large backpack's later slots. Native backpack item
storage now retains indexed slot access through traversal and void wrappers.
Guarded views rebuild when physical owners, order or slot counts change.

Fresh epoch `2f78d8cb-344e-4e90-b9bb-e04100df9236` passed **494 unit tests and
170 server cases (169 mod cases)** with no failures, errors or skips. New actual
backpack regressions cover slot73 and the final slot, allow filters after64denied
slots, fresh API lookups, transaction rollback, nested reorder/replacement,
detach/re-attach, changed access and root replacement. Unit execution PID37788.

The open production client held the canonical JAR in use, so only Gradle's JAR
output directory was redirected for the successful58-second rerun. No source,
test selection or discovery expectation was changed. Log:
`build/automation-verification-12b.log`. Scoped audit:
`build/verification/indexed-resources.json` (`unit-and-server-isolated-output`).
All749 source hashes, exact test discovery, freshness, and production archive
checks passed. This scoped audit does not replace the release gate.

Indexed-storage candidate at that checkpoint:
`build/index-verification-libs/fabricated-backpacks-0.5.0-alpha.jar`:

```text
97cfc3dcf2446c08f2312158ea16392c843a8978d4c5895947047c49e67de6d9
```

The rendered-client, restart, JEI and multiplayer observations above precede
this three-file storage correction. They are not relabeled as tests of the new
candidate. A Minecraft1.21.1/Cobblemon compatibility port is maintained on a
separate branch; none of this26.2 evidence establishes that port's compatibility.

## Grounded conduit routing correction

Solid blocks beneath conduits previously consumed whole routing turns despite
having no transfer interface. Source and destination scans now skip inert
faces within the existing shared work budget. The scheduler counts visited
sources per connected component, so a small empty component cannot repeatedly
take turns before a larger component's remaining sources are visited.
Physical candidates remain available for fresh API lookups when a machine
starts exposing a handler without a block replacement or neighbor update.

Fresh epoch `90d1ae8f-e334-4e3f-9309-2fafaca82fee` passed **494 unit tests and
170 server cases (169 mod cases)**, with no failures, errors or skips.
The existing conduit scenarios now include stone underneath the routes and
an energy interface enabled in place. Their same-tick push/pull, exact amount,
shared limit, rollback and conservation assertions remain in force.
Unit execution PID: 42736. Execution log:
`build/automation-verification-grounded-14.log` (56 seconds).

`build/verification/grounded-conduits.json` checks all 749 source/build hashes,
fresh reports, exact test identities and the isolated production JAR. Its scope
is `unit-and-server-isolated-output`; the open client still holds the canonical
JAR, so the same output-only Gradle redirect was used. Current candidate SHA-256:

```text
e9d69b4b242507035265d9e0c405a87428bc236ca24218c0a4ff469324dffd08
```

No rendered-client or manual receipt was created for this correction. The
earlier client observations remain tied to their original checkpoint.

CI exposed a timing assumption in the lane-isolation fixture: one run had
delivered 32 of 33 items at its fixed 45-tick checkpoint while the other run
passed. That scenario now uses the native test sequence to await exact item
and energy completion within its unchanged 160-tick timeout before reconnecting
fluid. It retains every quantity and conservation assertion; the separate
same-tick forwarding and bandwidth checks are unchanged.

The resulting fresh epoch `4c83e554-a2a1-46e1-8c4a-365a59e6358c` again passed
494 unit and 170 server tests in 54 seconds, with unit PID 8920. Log:
`build/automation-verification-grounded-15.log`; scoped audit:
`build/verification/grounded-conduits-fair-wait.json`. The production JAR hash
remains the value above because this follow-up changes only tests and documentation.

## Focused commands

```powershell
.\gradlew.bat test runGameTest build --no-build-cache --rerun-tasks
.\gradlew.bat runClientGameTest -PclientScenario=automation
.\gradlew.bat runClientGameTest -PclientScenario=automation_restart
.\gradlew.bat runClientGameTest -PclientScenario=automation_jei -PwithJei=true
.\tools\run-multiplayer.ps1 -Scenario automation
```

## Remaining manual and publication work

The packaged client was launched with `runProductionClient -PwithJei=true`;
`build/automation-production-client-11.log` records mod/JEI resource and audio
initialization. The manual walkthrough was stopped before any hands-on
observations: computer control was cancelled with the physical Escape key.
**The native packaged-JAR manual check is not done, and no current manual pass
or manual receipt is claimed.** Older manual receipts do not cover this epoch.

These focused results do not meet the full-release gate. The full backpack,
cooking and music walkthroughs were not rerun in this chain, and a focused
pass must not be substituted for their receipts or for the pending manual check.

The original prepared CurseForge and Modrinth forms remain **unsubmitted**.
Submitting either requires confirmation at the time of that action. No new
platform publication or replacement of the immutable published alpha is claimed.
