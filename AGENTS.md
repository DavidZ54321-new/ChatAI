# ChatAI

Single-module Jetpack Compose template (Android Studio "Empty Activity" scaffold).
`app/` is the only module; `MainActivity.kt` + `ui/theme/` are the entire source.

There is **no** architecture yet — no DI, navigation, networking, persistence, or
domain/data layers. Don't assume layers exist or invent them unprompted.

## Build & test

```pwsh
.\gradlew.bat assembleDebug        # build only, no device needed
.\gradlew.bat installDebug         # build + install to the connected device
.\gradlew.bat test                 # JVM unit tests (app/src/test)
.\gradlew.bat connectedAndroidTest # instrumented tests (app/src/androidTest), needs a device
.\gradlew.bat testDebugUnitTest --tests "com.zcw.chatai.ExampleUnitTest"
.\gradlew.bat lint                 # AGP default; no formatter or typecheck task is configured
```

The only tests are stubs: `ExampleUnitTest.kt` and `ExampleInstrumentedTest.kt`.

## AGP 9 DSL — differs from most examples you'll find

AGP 9.4.0 / Gradle 9.6.0 / Kotlin 2.2.10. AGP 9 changed the build DSL:

- `compileSdk { version = release(37) }` — not `compileSdk = 37`
- R8 is `buildTypes { release { optimization { enable = false } } }` — not
  `isMinifyEnabled` / `minifyEnabled`
- R8 keep rules live in `app/src/main/keepRules/*.keep` (currently `rules.keep`),
  **not** `proguard-rules.pro`

## Versions

All dependency and plugin versions live in `gradle/libs.versions.toml` and are
referenced via `libs.*` in `app/build.gradle.kts`. There are no hardcoded versions
in the build script — keep it that way.

## Android skills are installed project-locally

24 official Google skills (from `android/skills`) live in `.claude/skills/`.
OpenCode reads that path, so they're available through the `skill` tool — prefer
them over guessing at Android best practice (`edge-to-edge`, `agp-9-upgrade`,
`r8-analyzer`, `testing-setup`, `navigation-3`, `styles`, `adaptive`, ...).

```pwsh
android skills add --all --agent=claude-code --project=.
```

`--agent=claude-code` deliberately keeps them from being duplicated into other
agents' directories. A skill edited in place is overwritten on update — rename it
if you need to customize one.

## Driving an emulator / device

`opencode.json` configures the project-scoped `scrcpy` MCP server: screenshot, tap,
swipe, `input_text`, `ui_find_element`, logcat, file push/pull. AVDs on this machine:
`Pixel_4`, `Pixel_6a`.

Gotchas that cost real debugging time:

- `opencode.json` hardcodes machine paths (`ADB_PATH`, `SCRCPY_SERVER_PATH`). `adb`
  is not on PATH — use `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.
- **A scrcpy session caches screen size and rotation at startup.** After a rotation
  change, screenshots come back silently rotated 90° and mis-sized, and
  `start_session` keeps reporting the physical panel size as `screenSize`. Run
  `stop_session` then `start_session` to recover; don't trust the reported
  `screenSize` for orientation.
- To verify orientation against the real framebuffer, bypass the MCP:
  `adb exec-out screencap -p > frame.png`, and cross-check with
  `adb shell dumpsys window displays` (`cur=` field).
- The `Pixel_4` AVD emulates a display cutout (171px). `enableEdgeToEdge()` does
  **not** inset for a cutout, so a window that doesn't opt in leaves an
  undrawn black band (top in portrait, left in landscape). `Theme.ChatAI` in
  `app/src/main/res/values/themes.xml` sets
  `android:windowLayoutInDisplayCutoutMode=shortEdges` — removing it brings the
  band back.

## Tracked-file trap

`.kotlin/` matches no `.gitignore` entry, so `git add .` will commit JetBrains
session caches. `app/build/`, `build/`, `.codegraph/` and `.idea/workspace.xml` are
correctly ignored. `.claude/skills/` is tracked on purpose.
