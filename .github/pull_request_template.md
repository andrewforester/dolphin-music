## What and why

<!-- One or two sentences: the change and the reason. Link the issue or spec. -->

## How

<!-- The approach, and anything a reviewer would not guess from the diff: trade-offs, rejected options. -->

## Screenshots

<!-- For UI changes: the re-recorded goldens are in the diff (androidApp/src/test/screenshots). Point at the ones to look at, or write "No UI change". -->

## Checks

- [ ] `./gradlew verify` passes locally
- [ ] iOS still builds (`./gradlew iosSimulatorArm64Test :composeApp:linkDebugFrameworkIosSimulatorArm64`) if shared code changed
- [ ] `docs/architecture.md` updated if structure or a decision changed
