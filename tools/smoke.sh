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

# the screen's real size: swipes are given in its pixels
read W H < <(adb shell wm size | tail -1 | sed 's/.*: //; s/x/ /' | tr -d '\r')
up() { adb shell input swipe $((W/2)) $((H*4/5)) $((W/2)) $((H/4)) 300; sleep 1; }
echo "screen ${W}x${H}" >> "$OUT/steps.txt"
log "install $APK"
if ! adb install -r -g "$APK" > "$OUT/install.txt" 2>&1; then
  cat "$OUT/install.txt"; log "INSTALL FAILED"; exit 2
fi
adb logcat -c

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
launch() { adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.MainActivity --es tab "$1" >> "$OUT/steps.txt" 2>&1; sleep 5; }
alive() { adb shell pidof $PKG >/dev/null; }
# Crashes, except one known emulator limit: the wake word's speech engine is ARM code, and this x86 emulator runs ARM
# code through a translator that can't execute some of its instructions (SIGILL on the atlas-hotword thread).
# A real ARM phone runs it natively. Every other crash counts.
real_crashes() {
  adb logcat -d -b crash > /tmp/crash.txt
  python3 - "$PKG" <<'PY'
import re, sys
pkg = sys.argv[1]
lines = open('/tmp/crash.txt', encoding='utf-8', errors='replace').read().splitlines()
real = 0
for l in lines:
    if 'Fatal signal' in l and pkg in l:
        if 'SIGILL' in l and '(atlas-hotword)' in l: continue
        real += 1
    if 'AndroidRuntime' in l and 'Process: ' + pkg in l:
        real += 1
print(real)
PY
}
crashed() { [ "$(real_crashes)" != "0" ]; }
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
  launch me; up; shot "$theme-me-2"
  up; shot "$theme-me-3"
  up; shot "$theme-me-4"
  launch log; up; shot "$theme-log-2"
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

# Cloud AI through the key pool, and Atlas Bot acting on a website, against a stand-in service and a test shop on
# this machine (tools/mock_ai.py, reached at the phone's 127.0.0.1 through adb reverse). The first key is turned down,
# so Atlas has to move to the second; one message meets a busy service twice; one bot orders from the shop (and must
# ask before placing the order); one bot meets a password field (and must leave that to the user).
log "cloud AI and Atlas Bot (stand-in service and test shop)"
AI="$OUT/ai"; mkdir -p "$AI"
python3 tools/mock_ai.py 8766 "$AI" > "$AI/server.txt" 2>&1 &
AIPID=$!
sleep 1
adb reverse tcp:8766 tcp:8766
bot_state() { # goal-substring -> "id state" of the newest matching job
  adb shell "cat /data/data/$PKG/files/bots.json" 2>/dev/null | python3 -c "
import json,sys
try: d=json.load(sys.stdin)
except Exception: print('- none'); sys.exit()
jobs=d.get('jobs',d) if isinstance(d,dict) else d
m=[j for j in jobs if sys.argv[1].lower() in j.get('goal','').lower()]
m.sort(key=lambda j:j.get('createdAt',0))
print((m[-1]['id']+' '+m[-1]['state']) if m else '- none')" "$1"
}
wait_bot() { # goal-substring state seconds
  for i in $(seq 1 "$3"); do set -- "$1" "$2" "$3" $(bot_state "$1"); [ "${5:-}" = "$2" ] && return 0; sleep 1; done
  return 1
}
site_has() { [ -f "$AI/site-log.jsonl" ] && grep -c "$1" "$AI/site-log.jsonl"; true; }
# opens a tab without restarting Atlas (a restart would end a running bot's browser)
goto() { adb shell am start -n $PKG/.MainActivity --es tab "$1" >/dev/null 2>&1; sleep 3; }
seed dark
prefs '    <string name="provider">custom</string>
    <string name="base_custom">http://127.0.0.1:8766/v1</string>
    <string name="model_custom">mock-model</string>
    <string name="keys_custom">[{&quot;key&quot;:&quot;bad-key-1&quot;},{&quot;key&quot;:&quot;good-key-2&quot;}]</string>'
launch atlas
say "hello there" 8
say "busy test" 25
say "remind me to water the plants" 8
adb shell "cat /data/data/$PKG/files/chat.json" > "$AI/chat-1.json" 2>/dev/null
python3 - "$AI" >> "$OUT/steps.txt" <<'PY'
import json, glob, sys
d = sys.argv[1]
reqs = [json.load(open(f))["meta"] for f in sorted(glob.glob(d + "/req-*.json"))]
chat = open(d + "/chat-1.json", errors="replace").read()
first = reqs[0] if reqs else {}
print("== AI: first request used key %s and got %s" % (first.get("key"), first.get("status")))
print("== AI: later requests used: %s" % sorted({r["key"] for r in reqs[1:]}))
print("== AI: key switch %s" % ("WORKED" if first.get("key") == "bad-key-1" and first.get("status") == 401 and any(r["key"] == "good-key-2" and r["status"] == 200 for r in reqs) else "FAILED"))
print("== AI: plain reply %s" % ("arrived" if "here" in chat else "MISSING"))
print("== AI: busy service (two 429s) %s" % ("answered after waiting" if "Through after the wait" in chat else "NO ANSWER"))
print("== AI: reminder by tool %s" % ("logged" if "Logged it" in chat else "MISSING"))
PY
adb shell "cat /data/data/$PKG/files/atlas-state.json" | grep -q "Water the plants" && log "reminder from the cloud model is in the Log" || log "reminder from the cloud model NOT in the Log"
shot ai-chat

# bot 1: sent from the chat, orders from the shop; the order must wait for the user's OK
say "send a bot to order a blue widget from the test shop" 10
shot ai-chat-bot-sent
if wait_bot "blue widget" awaiting_approval 150; then log "order bot: reached the shop and is waiting for the OK to place the order"; else log "order bot: did NOT reach the approval (state: $(bot_state 'blue widget'))"; fi
log "order bot: searched the shop for the widget: $(grep -c 'blue widget' "$AI/site-log.jsonl" 2>/dev/null)"
log "order bot: orders sent BEFORE the OK (must be 0): $(site_has '"/shop/order"')"
adb shell dumpsys notification --noredact | grep -A3 "needs your OK" | head -8 > "$AI/approval-notification.txt"
grep -q "needs your OK" "$AI/approval-notification.txt" && log "order bot: approval notification shown" || log "order bot: approval notification NOT found"
goto bots; shot bots-tab
set -- $(bot_state "blue widget"); JOB=$1
asks() { # how many times the order bot has asked for the OK so far
  adb shell "cat /data/data/$PKG/files/bots.json" 2>/dev/null | python3 -c "
import json,sys
try: d=json.load(sys.stdin)
except Exception: print(0); sys.exit()
jobs=d.get('jobs',d) if isinstance(d,dict) else d
print(sum(str(s).count('Waiting for your OK') for j in jobs if 'blue widget' in j.get('goal','').lower() for s in j.get('steps',[])))"
}
allow() { adb shell am start -n $PKG/.BotActivity --es bot_id "$JOB" >> "$OUT/steps.txt" 2>&1; sleep 4; tap_desc "Allow Once" || tap_desc "Approve"; }
# First, the hard case: Atlas is closed and reopened while the bot waits. Its page is gone, so saying yes must not
# click anything blind and must not leave the job hanging: the bot goes back to the page and asks again.
adb shell am force-stop $PKG; sleep 2
adb shell am start -W -n $PKG/.MainActivity --es tab bots >> "$OUT/steps.txt" 2>&1; sleep 6
set -- $(bot_state "blue widget"); log "order bot: after Atlas was closed and reopened it is: ${2:-?}"
shot bot-job-approval-after-restart
if allow; then log "order bot: allowed after the restart"; else log "order bot: allow button NOT found after the restart"; cp /tmp/ui.xml "$AI/ui-approve-1.xml"; fi
for i in $(seq 1 150); do [ "$(asks)" -ge 2 ] && break; sleep 1; done
log "order bot: times it has asked for the OK (should be 2: it went back to the page and asked again): $(asks)"
set -- $(bot_state "blue widget"); log "order bot: state after the restart and the yes: ${2:-?}"
log "order bot: orders sent so far (must still be 0): $(site_has '"/shop/order"')"
# Then the normal case: Atlas stays open, the page is still there, yes places the order
sleep 2; shot bot-job-approval
if allow; then log "order bot: allowed on the job page"; else log "order bot: allow button NOT found"; cp /tmp/ui.xml "$AI/ui-approve-2.xml"; fi
if wait_bot "blue widget" completed 120; then log "order bot: COMPLETED"; else log "order bot: did NOT complete (state: $(bot_state 'blue widget'))"; fi
python3 - "$AI" >> "$OUT/steps.txt" <<'PY'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1] + "/site-log.jsonl")]
orders = [r for r in rows if r["path"] == "/shop/order"]
print("== order bot: order reached the shop: %s" % (json.dumps(orders[0]["form"]) if orders else "NO"))
print("== order bot: the shop got size Large: %s" % ("yes" if orders and orders[0]["form"].get("size") == ["Large"] else "NO"))
print("== order bot: orders the shop received (must be exactly 1): %d" % len(orders))
PY
sleep 3; shot bot-job-done
adb shell "cat /data/data/$PKG/files/chat.json" | grep -q "Ordered" && log "order bot: report posted in the chat" || log "order bot: report NOT in the chat"

# bot 2: sent from the Bots tab, meets a password field: it must stop and leave the sign-in to the user
launch bots
tap_desc EDIT || log "Bots tab: no goal field"
adb shell input text "Sign%sin%sto%sthe%stest%sshop"; sleep 1
if tap_desc "Send a Bot"; then log "sign-in bot: sent from the Bots tab"; else log "sign-in bot: Send a Bot NOT found"; cp /tmp/ui.xml "$AI/ui-bots-tab.xml"; fi
sleep 2; shot bots-tab-sent
if wait_bot "sign in to the test shop" awaiting_answer 120; then log "sign-in bot: stopped at the password and is waiting for the user"; else log "sign-in bot: did NOT wait for the user (state: $(bot_state 'sign in to the test shop'))"; fi
log "sign-in bot: sign-ins sent by the bot (must be 0): $(site_has '"POST", "path": "/login"')"
grep -q "hunter2" "$AI/site-log.jsonl" && log "sign-in bot: PASSWORD REACHED THE SITE" || log "sign-in bot: the password never reached the site"
set -- $(bot_state "sign in to the test shop"); JOB2=$1
adb shell am start -n $PKG/.BotActivity --es bot_id "$JOB2" >> "$OUT/steps.txt" 2>&1; sleep 4; shot bot-job-signin
if tap_desc "Open the Browser"; then log "sign-in bot: opened its browser"; else log "sign-in bot: Open the Browser NOT found"; cp /tmp/ui.xml "$AI/ui-open-browser.xml"; fi
sleep 5; shot bot-browser-watch
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$AI/ui-browser.xml" >/dev/null 2>&1
log "sign-in bot: the sign-in page was loaded by the bot's browser: $(site_has '"GET", "path": "/login"')"  # what the page shows is in bot-browser-watch.png
if tap_desc "Hand Back"; then log "sign-in bot: handed back"; else log "sign-in bot: Hand Back NOT found"; fi
if wait_bot "sign in to the test shop" completed 90; then log "sign-in bot: COMPLETED after the hand-back"; else log "sign-in bot: did NOT complete (state: $(bot_state 'sign in to the test shop'))"; fi
adb shell "cat /data/data/$PKG/files/bots.json" > "$AI/bots.json" 2>/dev/null
adb shell "cat /data/data/$PKG/files/chat.json" > "$AI/chat-2.json" 2>/dev/null
adb logcat -d -s AtlasBot:V AtlasBotBrowser:V chromium:V cr_AwContents:V > "$AI/bot-log.txt" 2>&1
kill $AIPID 2>/dev/null
if crashed || ! alive; then log "CRASH in cloud AI / Atlas Bot"; fi

# The Hey Atlas panel, opened the way the wake word opens it
log "assist panel"
adb shell am start -n $PKG/.AssistActivity >> "$OUT/steps.txt" 2>&1; sleep 4; shot assist-panel
adb shell input keyevent 4; sleep 1
if crashed; then log "CRASH in assist panel"; fi

# Reminders: alarm path, notification buttons, reboot recovery
log "alarm"
seed dark-alarm
adb shell am broadcast -n $PKG/.AlarmReceiver -a com.anjy.atlas.TICK >> "$OUT/steps.txt" 2>&1; sleep 5
shot alarm-popup
adb shell dumpsys notification --noredact > "$OUT/notifications.txt"
adb shell dumpsys audio | grep -iE "usage=USAGE_ALARM|player.*state:started" | head -5 > "$OUT/alarm-sound.txt"
grep -q "Drink water" "$OUT/notifications.txt" && log "lighter reminder came as a notification" || log "lighter reminder notification NOT found"
# "Doing It Now": the focus screen opens with its timer; End finishes it (and marks the reminder done)
if tap_desc "Doing It Now"; then log "alarm answered: doing it now"; else log "alarm pop-up NOT found"; cp /tmp/ui.xml "$OUT/ui-alarm.xml"; fi
sleep 4; shot focus-session
adb shell input keyevent 4; sleep 1; shot focus-after-back
if tap_desc "End the session"; then log "focus session ended"; else log "focus End NOT found"; cp /tmp/ui.xml "$OUT/ui-focus.xml"; fi
sleep 3
adb shell "cat /data/data/$PKG/files/atlas-state.json" | python3 -c "
import json,sys; s=json.load(sys.stdin); t=[x for x in s['tasks'] if x['title']=='Pick up my sister']
print('alarm task status:', t[0]['status'] if t else 'missing')" >> "$OUT/steps.txt" 2>&1
adb shell dumpsys alarm | grep -A3 "$PKG" > "$OUT/alarms.txt"
adb shell am broadcast -n $PKG/.BootReceiver -a android.intent.action.BOOT_COMPLETED >> "$OUT/steps.txt" 2>&1; sleep 4
adb shell dumpsys alarm | grep -A3 "$PKG" > "$OUT/alarms-after-boot.txt"
if crashed; then log "CRASH in reminder path"; fi

# Creature and wake word: overlay allowed, Hey Atlas on (this emulator has no microphone input, so it can load but not hear)
log "atlas on screen and wake"
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
launch atlas; sleep 8
adb shell input keyevent 3; sleep 4; shot orb-home
# what "Hey Atlas" does: the orb grows and listens (this emulator has no speech service, so it then says why and shrinks)
adb shell am startservice -n $PKG/.AvatarService -a com.anjy.atlas.WAKE >> "$OUT/steps.txt" 2>&1; sleep 1; shot orb-listening
sleep 5; shot orb-after-listening
adb shell dumpsys activity services $PKG > "$OUT/services.txt"
# was the orb drawn by the graphics chip, or did it fall back to the processor? (and why)
adb logcat -d -s AtlasOrb:V > "$OUT/orb-log.txt" 2>&1
adb shell "ls -la /data/data/$PKG/files/" > "$OUT/files.txt" 2>&1
if crashed; then log "CRASH in atlas on screen"; fi

# Hey Atlas: switching it on downloads its model; then the presence service loads it and opens the microphone
log "hey atlas model download"
launch me
FOUND=0
for i in 1 2 3 4 5 6 7 8; do if tap_desc '“Hey Atlas” (beta)'; then FOUND=1; break; fi; up; done
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
if adb logcat -d -b crash | grep -q "(atlas-hotword)"; then
  log "EMULATOR LIMIT: the wake word engine (ARM code) hit an instruction this x86 emulator can't translate"
  sleep 15
  N=$(adb logcat -d -b crash | grep -c "Fatal signal.*(atlas-hotword)")
  adb shell "cat /data/data/$PKG/files/atlas-state.json" | grep -o '"wakeWord":[a-z]*' > "$OUT/wakeword-after-crash.txt"
  log "wake word engine crashes: $N (it runs in its own process, which may not restart the microphone in the background, so it should stop at 1); setting now: $(cat "$OUT/wakeword-after-crash.txt")"
  shot hey-atlas-after-guard
fi
if adb shell pidof $PKG >/dev/null; then log "main app still running after the wake word engine: yes"; else log "main app still running after the wake word engine: NO"; fi
adb shell ps -A | grep "$PKG" > "$OUT/processes.txt"

# Sync with the real cloud: link a new device, let it sync, keep the link code so the records can be checked
log "sync"
launch me
for i in 1 2 3 4 5 6 7 8 9 10; do if tap_desc "Link a New Device"; then log "linked"; break; fi; up; done
sleep 20; shot sync-linked
adb shell "cat /data/data/$PKG/shared_prefs/atlas_ui.xml" | grep -o 'ATLS-[A-Z0-9-]*' | head -1 > "$OUT/sync-code.txt"
adb shell "cat /data/data/$PKG/files/sync-state.json" | head -c 300 > "$OUT/sync-state.txt" 2>&1
adb logcat -d -s AtlasSync:V > "$OUT/sync-log.txt" 2>&1
log "sync code: $(cat "$OUT/sync-code.txt")"

# any "not responding" reports, with what each thread was doing
adb shell "ls -la /data/anr/" > "$OUT/anr-list.txt" 2>&1
mkdir -p "$OUT/anr" && adb pull /data/anr/ "$OUT/anr/" >/dev/null 2>&1
adb shell dumpsys gfxinfo $PKG > "$OUT/gfxinfo.txt" 2>&1

adb logcat -d -b crash > "$OUT/crash.txt"
adb logcat -d > "$OUT/logcat.txt"
grep -E "AndroidRuntime|FATAL|$PKG|Atlas" "$OUT/logcat.txt" | head -600 > "$OUT/logcat-atlas.txt"
if crashed; then log "RESULT: CRASHED"; exit 1; fi
log "RESULT: NO CRASHES"
