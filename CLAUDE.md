# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A Flutter app + Android Accessibility Service that automatically taps through Clash of Clans'
"Suggested Upgrades" UI to start builder upgrades on a daily schedule, with no PC/USB required.
The Flutter UI is a thin control panel (schedule time, run-now button, live log); all the actual
automation (launching CoC, reading the screen via the Accessibility tree, dispatching tap
gestures) happens in Kotlin on the Android side.

## Commands

```bash
flutter pub get                 # install/update dependencies
flutter analyze                 # static analysis (flutter_lints via analysis_options.yaml)
flutter test                    # run Dart/widget tests
flutter test test/widget_test.dart   # run a single test file
flutter run                     # run on a connected device/emulator (debug)
flutter build apk --release     # build release APK
adb install build/app/outputs/flutter-apk/app-release.apk   # install on device
```

There is no automated test or build step for the Kotlin Accessibility Service — verifying bot
behavior requires installing the APK on a device with Clash of Clans installed, enabling the
Accessibility Service (Settings → Accessibility → CoC Bot), and watching the in-app log / `adb
logcat -s CocBot`.

## Architecture

### Dart ↔ Kotlin bridge (`lib/main.dart` ↔ `MainActivity.kt`)

`lib/main.dart` is a single-file Flutter app (`CocBotApp` / `BotHomePage`). All native
interaction goes through `BotBridge`, which wraps two platform channels defined in
`MainActivity.kt`:

- `MethodChannel('com.cocbot/bot')` — `isAccessibilityEnabled`, `isCocInstalled`, `startBot`,
  `stopBot`, `scheduleBot(hour, minute)`, `cancelSchedule`, `getBotStatus`.
- `EventChannel('com.cocbot/logs')` — broadcast stream of log lines from the Accessibility
  Service, surfaced into the in-app log view. A line starting with `BOT_DONE` flips the UI's
  "running" state back to false.

`BotHomePage` polls `getBotStatus()` every 3 seconds and on app resume to refresh the
free-builders / upgrades-started counters and accessibility/CoC-installed indicators.

### Bot state machine (`Cocbotaccessibilityservice.kt`)

`CocBotAccessibilityService` (an `AccessibilityService`) drives the whole automation as an
explicit `BotStep` enum state machine, advanced via `onAccessibilityEvent` callbacks and
`Handler.postDelayed` timers:

```
WAIT_FOR_COC → (10s settle delay) → CHECK_BUILDERS → TAP_BUILDER_BADGE → DONE
```

- **CoC's accessibility tree exposes zero text.** Confirmed via `adb logcat` tree dump: the
  active window is just `FrameLayout` → `View`, both `text='null' desc='null'`, covering the
  full screen (CoC renders via GPU/SurfaceView). All node-based reading/finding
  (`findNodeWithText`, `findNodeWithPattern`, `dumpNodeTree`) was removed as dead code — do not
  re-add accessibility-tree text search for CoC screens, it cannot work.
- **Builder count is read via on-device OCR** (`checkFreeBuilders` / `onBuilderCountRead`,
  ML Kit `text-recognition` dependency in `android/app/build.gradle.kts`): takes a screenshot
  (`takeScreenshot`, requires `canTakeScreenshot="true"` in `accessibility_service_config.xml`),
  crops the top-center "X/5" builder badge (`~47%-57%` width, `0-9%` height of the screenshot),
  runs `TextRecognizer`, and regex-matches `(\d+)\s*/\s*(\d+)`. If the free count is 0 the bot
  goes straight home; otherwise it proceeds to tap the badge.
- Taps are dispatched via `performTap` (single-point `GestureDescription`) at hardcoded
  screen-percentage coordinates (`tapBuilderBadge`, ~51% width / 1.6% height) calibrated from a
  specific screenshot/screen size — if CoC changes its UI layout, this is the first place to
  look.
- `captureDebugScreenshot(label, tapX?, tapY?)` saves a PNG (with an optional red crosshair at a
  planned tap point) to `getExternalFilesDir(null)`, pullable via
  `adb pull /sdcard/Android/data/com.example.coc_upgrade/files/`. Used throughout the flow for
  calibration — currently the only thing after `TAP_BUILDER_BADGE` is a debug screenshot of the
  resulting "Builders" panel (`tap_builder_panel_*.png`); **no upgrade-tapping logic exists yet**
  for that panel (see "Still outstanding" below).
- Status (`isRunning`, `freeBuilders`, `upgradesStarted`) is exposed via companion-object
  statics that `MainActivity.getBotStatus` reads directly. `upgradesStarted` is currently always
  `0` since the upgrade-tap loop hasn't been implemented yet.
- Log lines are pushed via `LocalBroadcastManager` (`ACTION_LOG`) to `MainActivity`, which
  forwards them to Dart over the event channel.

### Scheduling (`MainActivity.kt` + `Botalarmreceiver.kt`)

`scheduleBot`/`cancelSchedule` use `AlarmManager.setRepeating` (RTC_WAKEUP, daily interval) via
`BotAlarmReceiver`, which also handles `BOOT_COMPLETED` to survive reboots. The alarm receiver
starts `CocBotAccessibilityService` with `ACTION_START_BOT`.

## Current state / known gaps

This tree was originally mid-rename from an older "coc_bot" / `com.cocbot` package layout to
`coc_upgrade` / `com.example.coc_upgrade`. `flutter build apk --release` now succeeds; the
following were fixed to get there and are good reference points if similar issues resurface:

- `AndroidManifest.xml` was missing the `<meta-data android:name="flutterEmbedding"
  android:value="2"/>` tag (required, otherwise Flutter refuses to build with "deleted Android
  v1 embedding").
- `lib/main.dart` imports `package:shared_preferences/shared_preferences.dart`, which was not
  declared in `pubspec.yaml` — added via `flutter pub add shared_preferences`.
- `AndroidManifest.xml` referenced `@xml/accessibility_service_config` for
  `CocBotAccessibilityService`, but that resource didn't exist — added at
  `android/app/src/main/res/xml/accessibility_service_config.xml` (scoped to
  `com.supercell.clashofclans`, with `canRetrieveWindowContent`/`canPerformGestures` enabled for
  the tap/read flow) plus its `@string/accessibility_service_description` in
  `res/values/strings.xml`.
- Package/namespace mismatch: `build.gradle.kts` sets `namespace`/`applicationId` to
  `com.example.coc_upgrade` (matching the Kotlin source directory
  `android/app/src/main/kotlin/com/example/coc_upgrade/`), but the manifest had
  `package="com.cocbot"` and all three Kotlin files declared `package com.cocbot`. Removed the
  manifest `package` attribute (AGP no longer allows it) and changed the Kotlin files to
  `package com.example.coc_upgrade`, including the accessibility-service-enabled check string in
  `MainActivity.isAccessibilityEnabled()`. The `com.cocbot/bot` and `com.cocbot/logs` platform
  channel names and the `com.cocbot.LOG`/`START`/`STOP` intent action constants are arbitrary
  string identifiers shared between Dart and Kotlin (not Android package paths) and were left
  unchanged.
- `androidx.localbroadcastmanager.content.LocalBroadcastManager` (used by `MainActivity` and
  `CocBotAccessibilityService`) had no corresponding Gradle dependency — added
  `implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")` to
  `android/app/build.gradle.kts`.
- **`startBot()`/`stopBot()` were never invoked**: `MainActivity` sends `ACTION_START_BOT` /
  `ACTION_STOP_BOT` intents to `CocBotAccessibilityService` via `startService()`, but the service
  had no `onStartCommand()` override to receive those intents. Fixed by adding
  `onStartCommand()`.
- `takeScreenshot()` threw `SecurityException: Services don't have the capability of taking the
  screenshot` — fixed by adding `android:canTakeScreenshot="true"` to
  `accessibility_service_config.xml`. **Note:** after install, the Accessibility Service must be
  toggled off/on in Settings for a new capability flag to take effect (Android caches
  capabilities at bind time).

Still outstanding (not yet fixed):

- **`pubspec.yaml` package name vs. test import mismatch**: `pubspec.yaml` declares
  `name: coc_bot`, but `test/widget_test.dart` imports `package:coc_upgrade/main.dart` — `flutter
  test` will fail to resolve this import until one side is renamed to match.
- **`test/widget_test.dart` is the unmodified Flutter template test** (expects a counter app
  `MyApp` with a `+` button), which has nothing to do with `CocBotApp` in `lib/main.dart`. It
  will fail regardless of the naming fix above and needs to be rewritten for the actual UI.
- **No upgrade-tapping logic after the builder panel opens**: `tapBuilderBadge()` taps the
  builder badge (opens the "Builders" panel) and saves a debug screenshot
  (`tap_builder_panel_*.png`), then goes home. The next step is to pull that screenshot,
  calibrate tap coordinates for "Suggested upgrades" / "Go" / confirm buttons in that panel, and
  implement the actual upgrade-tap loop (looping `freeBuilders` times).

When making changes in this repo, treat the README's described flow as the *intended* design —
the code above is the closest thing to ground truth for what's actually implemented, including
the remaining gaps.
