# Dolphin

Android and iOS app (Kotlin Multiplatform) for composing music with an AI voice assistant. Architecture: `docs/architecture.md`.

## Working in this repository

- Read `docs/architecture.md` before adding a module, a screen, a repository or platform code.
- Toolchain: `export JAVA_HOME="<Android Studio>/Contents/jbr/Contents/Home"`; `sdk.dir` in `local.properties`.

## Rules

- **Fix the code, not the rule.** When a test, detekt, lint, Konsist or the module graph fails, change the code.
  Do not weaken a test, delete an assertion, raise a tolerance, add a baseline, an ignore or a suppression.
  A rule changes only in its own PR.
- **TDD.** The failing test comes first. Logic lives in `core/domain` and `feature/*` and is tested in `commonTest`; platform
  code is a thin adapter behind an interface. Test fakes for the domain ports live in `core/testing`.
- **No comments in code**, in any language or config file.
- Gradle configuration lives in the convention plugins in `build-logic`; module build files only declare dependencies.
- Keep iOS compiling: every KMP module builds for the iOS targets.

## Checks

- **Before each commit, seconds:** `./gradlew spotlessApply`, then the tests of the modules you touched:
  `./gradlew :core:domain:jvmTest :feature:producer:testAndroidHostTest :androidApp:testDebugUnitTest`.
- **Before a PR, minutes:** `./gradlew verify`; if KMP code changed, also `./gradlew iosSimulatorArm64Test`.

## Visual review

- After a UI change: `./gradlew :androidApp:recordRoborazziDebug` (goldens in `androidApp/src/test/screenshots/`),
  then build one contact sheet and look at it once: `python3 scripts/contact_sheet.py /tmp/sheet.png` (needs Pillow). For a failed comparison, sheet the `*_compare.png` files from `androidApp/build/outputs/roborazzi/`.
- A screenshot is reviewed before its golden is committed. Never commit goldens you have not looked at.
- When CI has re-recorded goldens on your PR: pull the branch, build the sheet, review, push.
- Look at screenshots as one contact sheet per review, opened once, not image by image.

## Git

- Never push to `main`. Work on a short branch from `main` named as in "Branching" of `docs/architecture.md`;
  git worktrees are fine for parallel tasks. One PR per task.
- Conventional, atomic commits: `feat(core-domain): ...`, `fix(androidApp): ...`. The PR title follows the same format.
- Run `git branch --show-current` before committing.

## Context economy

- Do not read a whole large file to find one thing: search, then read the range. Do not re-read a file you just wrote.
- Run Gradle quietly and report counts and failures, not logs: `./gradlew <task> -q --console=plain 2>&1 | tail`.
  For test results, count the XML in `build/test-results` instead of reading reports.
- When work is delegated to agents, give each a self-contained task, keep their reports under 30 lines, and run
  the checks yourself; do not re-read what an agent already summarised.

## Proxy and agent

- Change the voice agent in `supabase/functions/_shared/agent/` (`prompt.ts`, `agent.ts`, `tools.ts`); merging to
  `main` deploys the functions, and `agent-token` syncs the definition to the live agent on the next token request.
- Never commit or print secret values (`local.properties`, `.env`, `iosApp/Secrets.xcconfig`).
- Never generate music without the user asking for it.

## Commands

```bash
./gradlew verify                                  # format, detekt, lint, tests and screenshots
./gradlew detekt                                  # static analysis of every module
./gradlew :androidApp:lintDebug                   # Android Lint
./gradlew spotlessApply                           # format (ktlint + Compose rules)
./gradlew :core:domain:jvmTest                    # domain logic tests (fast)
./gradlew :feature:producer:testAndroidHostTest   # producer presentation tests on the Android host
./gradlew :androidApp:testDebugUnitTest           # Robolectric: Koin graph, preview screenshots
./gradlew :androidApp:recordRoborazziDebug        # re-record screenshot goldens
./gradlew :androidApp:verifyRoborazziDebug        # compare previews with the goldens (part of verify)
python3 scripts/contact_sheet.py /tmp/sheet.png   # one image of all goldens
./gradlew :androidApp:assembleDebug :androidApp:installDebug
./gradlew iosSimulatorArm64Test                   # every KMP module's tests on the iOS simulator
./gradlew :composeApp:compileKotlinIosSimulatorArm64  # keep iOS compiling
(cd iosApp && xcodegen generate)                  # then open iosApp/iosApp.xcodeproj; see iosApp/README.md
./gradlew :konsist-test:test :androidApp:assertModuleGraph  # architecture rules
deno test supabase/functions                      # proxy tests
supabase functions deploy --no-verify-jwt        # deploy the proxy by hand; merging to main deploys it
```
