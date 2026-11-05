#!/usr/bin/env bash
# Emulator smoke test: install, open every screen in Night and Day, talk to Atlas offline, run a scripted Gemini
# conversation that uses phone control (against a stand-in server), open the Hey Atlas panel, and check reminders
# and the floating creature. Writes screenshots and logs to smoke-out/. Non-zero exit if Atlas crashed.
set -u
# no single adb call may hang the whole run
ADB_BIN=$(type -P adb)
adb() { timeout 120 "$ADB_BIN" "$@"; }
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
owner() { adb shell stat -c %u /data/data/$PKG | tr -d '\r'; }
put_file() { # local-file app-relative-path
  adb push "$1" /data/local/tmp/put.tmp >/dev/null
  adb shell "mkdir -p \$(dirname /data/data/$PKG/$2) && cp /data/local/tmp/put.tmp /data/data/$PKG/$2 && chown -R $(owner):$(owner) /data/data/$PKG/$(dirname $2) && chmod 600 /data/data/$PKG/$2 && restorecon -R /data/data/$PKG"
}
seed() {
  NOW=$(( $(adb shell date +%s | tr -d '\r') * 1000 ))
  python3 tools/seed_state.py "$NOW" "$1" /tmp/state.json
  adb shell am force-stop $PKG
  put_file /tmp/state.json files/atlas-state.json
  prefs ""
}
# Reduce Motion is on from here on: the screen reader used to find buttons only reads a screen that holds still
prefs() { # writes Atlas's small settings file: $1 = extra <string> lines
  printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n    <boolean name="reduceMotion" value="true" />\n%s\n</map>\n' "$1" > /tmp/atlas_ui.xml
  adb shell am force-stop $PKG
  put_file /tmp/atlas_ui.xml shared_prefs/atlas_ui.xml
}
tap_desc() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  python3 - "$1" <<'PY' > /tmp/xy || return 1
import re, sys
x = open('/tmp/ui.xml', encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    want = sys.argv[1]
    hit = ('class="android.widget.EditText"' in n) if want == 'EDIT' else (f'content-desc="{want}"' in n or f'text="{want}"' in n)
    if hit:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            a, c, d, e = map(int, b.groups()); print((a + d) // 2, (c + e) // 2); break
PY
  [ -s /tmp/xy ] && adb shell input tap $(cat /tmp/xy)
}
FAILS=0
say() {
  adb shell cmd statusbar collapse >/dev/null 2>&1; sleep 1
  tap_desc EDIT || { FAILS=$((FAILS+1)); log "no ask field"; cp /tmp/ui.xml "$OUT/ui-fail-$FAILS.xml" 2>/dev/null; shot "ui-fail-$FAILS"; }
  adb shell input text "$(echo "$1" | sed "s/ /%s/g; s/'//g")"; sleep 1
  tap_desc "Send" || log "no send button"; sleep "${2:-3}"
}

log "first launch, empty state"
launch atlas; shot 00-first-launch
if crashed || ! alive; then log "CRASH ON FIRST LAUNCH"; fi

for theme in dark light; do
  seed $theme
  for tab in atlas log me; do
    log "open $tab ($theme)"
    launch $tab; shot "$theme-$tab"
    if crashed || ! alive; then log "CRASH on $tab ($theme)"; fi
  done
  launch me; adb shell input swipe 500 1800 500 400 300; sleep 1; shot "$theme-me-2"
  adb shell input swipe 500 1800 500 400 300; sleep 1; shot "$theme-me-3"
  adb shell input swipe 500 1800 500 400 300; sleep 1; shot "$theme-me-4"
  launch log; adb shell input swipe 500 1800 500 400 300; sleep 1; shot "$theme-log-2"
done

# Offline: no Gemini key, so the fixed commands answer
log "offline chat commands"
seed dark
launch atlas
sleep 6   # the seeded check-in posts a reminder; let its pop-up pass
say "help"
say "remember my bike is blue"
say "remind me to stretch at 23:30 for 10m"
say "whats my day"
adb shell input keyevent 4; sleep 1
shot dark-chat-offline
if crashed || ! alive; then log "CRASH in offline chat"; fi

# Online, against a stand-in Gemini on this machine (forwarded to the phone's 127.0.0.1): phone control and the Log
log "gemini conversation with phone control (stand-in server)"
mkdir -p "$OUT/gemini"
python3 tools/mock_gemini.py 8765 "$OUT/gemini" > "$OUT/gemini/server.txt" 2>&1 &
MOCK=$!
sleep 1
adb reverse tcp:8765 tcp:8765
seed dark
prefs '    <string name="gemini_key">test-key-for-the-emulator-0000</string>
    <string name="gemini_endpoint">http://127.0.0.1:8765/v1beta/models/</string>'
launch me; shot gemini-me-connected
launch atlas
# phone control on (after the last restart: stopping an app unbinds its accessibility service)
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.AtlasAccess
adb shell settings put secure accessibility_enabled 1
for i in $(seq 1 20); do adb logcat -d | grep -q "AtlasAccess: connected" && break; sleep 1; done
adb logcat -d | grep -q "AtlasAccess: connected" && log "phone control connected" || log "phone control NOT connected"
adb shell dumpsys accessibility | grep -iE "atlas|Bound services|Enabled services" > "$OUT/accessibility.txt"
say "open settings and tap network and come back" 15
shot gemini-after-phone-task
launch atlas; shot gemini-chat-after-phone-task
say "remind me to pick up my sister" 6
shot gemini-after-reminder
launch log; shot gemini-log-after-reminder
adb shell "cat /data/data/$PKG/files/chat.json" > "$OUT/gemini/chat.json" 2>/dev/null
adb shell "cat /data/data/$PKG/files/atlas-state.json" > "$OUT/gemini/state.json" 2>/dev/null
kill $MOCK 2>/dev/null
if crashed || ! alive; then log "CRASH in gemini conversation"; fi

# The Hey Atlas panel, opened the way the wake word opens it
log "assist panel"
adb shell am start -n $PKG/.AssistActivity >> "$OUT/steps.txt" 2>&1; sleep 4; shot assist-panel
adb shell input keyevent 4; sleep 1
if crashed; then log "CRASH in assist panel"; fi

# Reminders: alarm path, notification buttons, reboot recovery
log "reminder engine"
seed dark
adb shell am broadcast -n $PKG/.AlarmReceiver -a com.anjy.atlas.TICK >> "$OUT/steps.txt" 2>&1; sleep 4
adb shell dumpsys notification --noredact > "$OUT/notifications.txt"
adb shell dumpsys alarm | grep -A3 "$PKG" > "$OUT/alarms.txt"
adb shell am broadcast -n $PKG/.BootReceiver -a android.intent.action.BOOT_COMPLETED >> "$OUT/steps.txt" 2>&1; sleep 4
adb shell dumpsys alarm | grep -A3 "$PKG" > "$OUT/alarms-after-boot.txt"
if crashed; then log "CRASH in reminder path"; fi

# Creature and wake word: overlay allowed, Hey Atlas on (this emulator has no microphone input, so it can load but not hear)
log "creature and wake word"
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
launch atlas; sleep 8
adb shell input keyevent 3; sleep 4; shot creature-home
adb shell dumpsys activity services $PKG > "$OUT/services.txt"
adb shell "ls -la /data/data/$PKG/files/" > "$OUT/files.txt" 2>&1
if crashed; then log "CRASH in creature or wake word"; fi

# Hey Atlas: switching it on downloads its model; then the presence service loads it and opens the microphone
log "hey atlas model download"
launch me
FOUND=0
for i in 1 2 3 4 5 6 7 8; do if tap_desc '“Hey Atlas” (beta)'; then FOUND=1; break; fi; adb shell input swipe 500 1500 500 900 300; sleep 1; done
log "hey atlas switch found: $FOUND"
cp /tmp/ui.xml "$OUT/ui-hey-atlas.xml" 2>/dev/null
sleep 4; shot hey-atlas-downloading
for i in $(seq 1 100); do adb shell "test -f /data/data/$PKG/files/vosk-small-en-us-0.15/.complete" && break; sleep 3; done
adb shell "test -f /data/data/$PKG/files/vosk-small-en-us-0.15/.complete" && log "hey atlas model ready" || log "hey atlas model NOT ready"
sleep 12; shot hey-atlas-on
adb shell "ls -laR /data/data/$PKG/files/vosk-small-en-us-0.15/" > "$OUT/vosk-files.txt" 2>&1
adb shell dumpsys activity services $PKG > "$OUT/services-hey-atlas.txt"
adb shell dumpsys notification --noredact | grep -A12 "$PKG" > "$OUT/notifications-hey-atlas.txt"
adb logcat -d | grep -E "AtlasHotword|AtlasPresence" > "$OUT/hotword-log.txt"
if crashed; then log "CRASH in hey atlas"; fi

adb logcat -d -b crash > "$OUT/crash.txt"
adb logcat -d > "$OUT/logcat.txt"
grep -E "AndroidRuntime|FATAL|$PKG|Atlas" "$OUT/logcat.txt" | head -600 > "$OUT/logcat-atlas.txt"
if [ -s "$OUT/crash.txt" ] && grep -q "$PKG" "$OUT/crash.txt"; then log "RESULT: CRASHED"; exit 1; fi
log "RESULT: NO CRASHES"
