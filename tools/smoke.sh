#!/usr/bin/env bash
# Emulator smoke test: install, open every screen in Night and Day, exercise reminders, chat and the avatar.
# Writes screenshots and logs to smoke-out/. Exit code is non-zero if Atlas crashed or failed to install.
set -u
PKG=com.anjy.atlas
OUT=smoke-out
mkdir -p "$OUT"
APK=$(ls apk/*.apk | head -1)
log() { echo "== $*" | tee -a "$OUT/steps.txt"; }

adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell getprop ro.product.cpu.abilist > "$OUT/abi.txt"

log "install $APK"
if ! adb install -r -g "$APK" > "$OUT/install.txt" 2>&1; then
  cat "$OUT/install.txt"; log "INSTALL FAILED"; exit 2
fi
adb logcat -c

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
launch() { adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.MainActivity --es tab "$1" >> "$OUT/steps.txt" 2>&1; sleep 5; }
alive() { adb shell pidof $PKG >/dev/null; }
crashed() { adb logcat -d -b crash | grep -q "$PKG"; }
seed() {
  NOW=$(( $(adb shell date +%s | tr -d '\r') * 1000 ))
  python3 tools/seed_state.py "$NOW" "$1" /tmp/state.json
  adb shell am force-stop $PKG
  adb push /tmp/state.json /data/local/tmp/state.json >/dev/null
  adb shell "cp /data/local/tmp/state.json /data/data/$PKG/files/atlas-state.json && chown \$(stat -c %u /data/data/$PKG) /data/data/$PKG/files/atlas-state.json && chmod 600 /data/data/$PKG/files/atlas-state.json"
}

log "first launch, empty state"
launch chat; shot 00-first-launch
if crashed || ! alive; then log "CRASH ON FIRST LAUNCH"; fi

for theme in dark light; do
  seed $theme
  for tab in chat today memory settings; do
    log "open $tab ($theme)"
    launch $tab; shot "$theme-$tab"
    if crashed || ! alive; then log "CRASH on $tab ($theme)"; fi
  done
  # scroll the long pages
  launch settings; adb shell input swipe 500 1800 500 400 300; sleep 1; shot "$theme-settings-2"
  adb shell input swipe 500 1800 500 400 300; sleep 1; shot "$theme-settings-3"
done

# Chat: type fixed commands and send them
tap_desc() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  python3 - "$1" <<'PY' > /tmp/xy || return 1
import re, sys
x = open('/tmp/ui.xml', encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    if f'content-desc="{sys.argv[1]}"' in n or f'text="{sys.argv[1]}"' in n:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            a, c, d, e = map(int, b.groups()); print((a + d) // 2, (c + e) // 2); break
PY
  [ -s /tmp/xy ] && adb shell input tap $(cat /tmp/xy)
}
say() {
  tap_desc "Message Atlas" || log "no chat field"
  adb shell input text "$(echo "$1" | sed 's/ /%s/g')"; sleep 1
  tap_desc "Send" || log "no send button"; sleep 3
}
log "chat commands"
launch chat
say "help"
say "remember my bike is blue"
say "remind me to stretch at 23:30 for 10m"
say "whats my day"
adb shell input keyevent 4; sleep 1
shot light-chat-after
if crashed || ! alive; then log "CRASH in chat"; fi

# Reminders: alarm path, notification buttons, reboot recovery
log "reminder engine"
seed dark
adb shell am broadcast -n $PKG/.AlarmReceiver -a com.anjy.atlas.TICK >> "$OUT/steps.txt" 2>&1; sleep 4
adb shell dumpsys notification --noredact > "$OUT/notifications.txt"
adb shell dumpsys alarm | grep -A3 "$PKG" > "$OUT/alarms.txt"
adb shell am broadcast -n $PKG/.BootReceiver -a android.intent.action.BOOT_COMPLETED >> "$OUT/steps.txt" 2>&1; sleep 4
adb shell dumpsys alarm | grep -A3 "$PKG" > "$OUT/alarms-after-boot.txt"
if crashed; then log "CRASH in reminder path"; fi

# Avatar: grant overlay, launch, go home, look at it
log "avatar"
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
launch today
adb shell input keyevent 3; sleep 4; shot avatar-home
adb shell dumpsys activity services $PKG > "$OUT/services.txt"
if crashed; then log "CRASH in avatar"; fi

adb logcat -d -b crash > "$OUT/crash.txt"
adb logcat -d > "$OUT/logcat.txt"
grep -E "AndroidRuntime|FATAL|$PKG|Atlas" "$OUT/logcat.txt" | head -400 > "$OUT/logcat-atlas.txt"
if [ -s "$OUT/crash.txt" ] && grep -q "$PKG" "$OUT/crash.txt"; then log "RESULT: CRASHED"; exit 1; fi
log "RESULT: NO CRASHES"
