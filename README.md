# Atlas (Android)

Personal assistant with an always-on ember avatar, chat, memory, reminders and check-ins, and an on-device model slot (llama.cpp, GGUF).

- Engine: `app/src/main/java/com/anjy/atlas/core/Engine.kt` (pure Kotlin, unit-tested in CI)
- Reminders: one exact alarm, rebuilt from saved state after every change, reboot and clock change
- Model: any GGUF chat model, loaded from Settings, runs fully offline

Builds run on GitHub Actions on every push to `main`. The APK is attached to a release and copied to the `apk-builds` branch.
