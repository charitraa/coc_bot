# 🏰 CoC Upgrade Bot — No USB Required

A Flutter + Android Accessibility Service bot that automatically upgrades CoC buildings at midnight. Runs entirely on your phone — no PC, no USB needed.

---

## How It Works

```
Android AlarmManager
      ↓  fires at midnight
CocBotAccessibilityService (Kotlin)
      ↓  simulates taps using Android Gesture API
Clash of Clans game UI
      ↓
Taps ⓘ Info → reads free builders → taps Suggested Upgrades → confirms
```

---

## Bot Flow (matches your screenshots)

1. **Wakes** — AlarmManager fires at scheduled time
2. **Opens CoC** — launches `com.supercell.clashofclans`
3. **Waits** — 4 seconds for game to load
4. **Taps ⓘ Info button** — top center of screen (the middle `i` icon)
5. **Reads builder count** — e.g. `5/5` = 5 free builders
6. **For each free builder** — taps first item in "Suggested upgrades" list → taps OK/Confirm
7. **Goes home** — minimizes CoC, done!

---

## Setup (One-time)

### 1. Build & Install the APK
```bash
flutter build apk --release
# Install on your phone:
adb install build/app/outputs/flutter-apk/app-release.apk
# OR copy the APK to your phone and install manually
```

### 2. Enable Accessibility Service
The app will show a banner if not enabled. Tap it, or go manually:

> **Settings → Accessibility → Downloaded Apps → CoC Bot → Toggle ON → Allow**

That's it! No USB, no PC needed after install.

### 3. Set Your Schedule
- Open the app
- Tap the time (default `00:00`)
- Choose your upgrade time
- Tap **Schedule**
- Press home — the bot runs in the background

---

## App Screen Explained

```
┌─────────────────────────────────────┐
│ 🏰 CoC Upgrade Bot      ACC● BOT●  │  ← green dots = active
├─────────────────────────────────────┤
│  🔨          ⬆️           📱       │
│  Free        Upgrades     CoC       │  ← live stats
│  Builders    Done         Installed │
├─────────────────────────────────────┤
│  ⚠️ Accessibility not enabled       │  ← tap to fix (disappears once on)
├─────────────────────────────────────┤
│  DAILY SCHEDULE                     │
│  [ 00:00 ]  [ Schedule ]           │  ← tap time to change, Schedule to save
│  ● Active — upgrades every day...  │
├─────────────────────────────────────┤
│  [  ▶  Run Now  ]                  │  ← test immediately
├─────────────────────────────────────┤
│  LOG                        Clear  │
│  [08:12:03] 🚀 Bot started         │  ← real-time log
│  [08:12:07] 🏰 CoC is open!        │
│  [08:12:09] 🔨 Builders: 5/5       │
│  [08:12:11] 👆 Tapping upgrade #1  │
│  [08:12:13] ✅ Upgrade 1/5 done!   │
└─────────────────────────────────────┘
```

---

## Files

```
coc_flutter_bot/
├── android/app/src/main/
│   ├── kotlin/com/cocbot/
│   │   ├── CocBotAccessibilityService.kt  ← bot brain (taps CoC)
│   │   ├── MainActivity.kt                ← Flutter ↔ Kotlin bridge
│   │   └── BotAlarmReceiver.kt            ← fires alarm at scheduled time
│   ├── res/xml/
│   │   └── accessibility_service_config.xml
│   └── AndroidManifest.xml
├── lib/
│   └── main.dart                          ← Flutter UI
└── pubspec.yaml
```

---

## Important Notes

- **First time**: CoC must already be logged in (bot won't handle login)
- **Screen lock**: Bot uses `WAKE_LOCK` — phone will wake up automatically
- **Gold check**: Bot taps from suggested list — if you don't have enough gold/elixir, CoC won't start the upgrade (bot won't crash)
- **Builder check**: Bot reads the `X/X` builder count — only starts upgrades equal to free builders
- **Reboot**: Schedule survives phone restart via `BOOT_COMPLETED` broadcast

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Bot doesn't tap anything | Check Accessibility is ON in settings |
| CoC doesn't open | Make sure CoC is installed (`com.supercell.clashofclans`) |
| Wrong taps / misses | CoC may have updated its UI — check `tapInfoButton()` coordinates in Kotlin |
| Schedule not firing | Check "Allow exact alarms" in Android battery settings |
| Bot runs but no upgrades | All builders may be busy, or not enough resources |