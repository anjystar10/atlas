#!/usr/bin/env bash
# Deep test on the emulator (deep.yml): installs Atlas, keeps a full logcat, and runs one part:
#   crawl   tools/deep/crawl.py: the stand-in AI's every error shape, offline, rotation, background, process death,
#           don't-keep-activities, light/dark/night, big fonts, a small screen, permissions taken away, then a crawl
#           that taps everything it can reach and types odd text everywhere, then each destructive button
#   monkey  `monkey` with several seeds (random taps, swipes and keys inside Atlas), StrictMode on
# Then it gathers crashes, ANR traces, the dropbox, memory and frame stats, and writes findings.md (tools/deep/triage.py).
# Usage: deep.sh crawl|monkey OUTDIR   (env: APK, MINUTES, SEEDS, EVENTS). Never fails the job: findings are the output.
set -u
PART=${1:-crawl}; OUT=${2:-deep-out}
ADB_BIN=$(type -P adb)
adb() { timeout 180 "$ADB_BIN" "$@"; }
PKG=com.anjy.atlas
mkdir -p "$OUT"
APK=${APK:-$(ls apk/*.apk | head -1)}
log() { echo "== $(date +%T) $*" | tee -a "$OUT/steps.txt"; }

adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
for i in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 2; done
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put secure show_ime_with_hard_keyboard 0
# crash and "isn't responding" dialogs show (the crawl notes and dismisses them), even for background work
adb shell settings put global anr_show_background 1
{ echo "abi: $(adb shell getprop ro.product.cpu.abilist)"; echo "sdk: $(adb shell getprop ro.build.version.sdk)";
  echo "release: $(adb shell getprop ro.build.version.release)"; echo "tz: $(adb shell getprop persist.sys.timezone)";
  echo "translation: $(adb shell getprop ro.dalvik.vm.native.bridge)"; adb shell wm size; adb shell wm density; } > "$OUT/device.txt" 2>&1
cat "$OUT/device.txt"

adb logcat -c; adb logcat -b crash -c 2>/dev/null
# the whole run's log, kept on this machine (the phone's buffer would roll over)
"$ADB_BIN" logcat -v threadtime -b main,system,crash,events > "$OUT/logcat.txt" 2>/dev/null &
LOGCAT_PID=$!

log "install $APK"
if ! adb install -r -g "$APK" > "$OUT/install.txt" 2>&1; then
  cat "$OUT/install.txt"; log "INSTALL FAILED (see install.txt)"
  printf '# Deep test: %s\n\nINSTALL FAILED: %s\n' "$PART" "$(cat "$OUT/install.txt")" > "$OUT/findings.md"
  kill $LOGCAT_PID 2>/dev/null; exit 0
fi
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell cmd appops set $PKG POST_NOTIFICATION allow 2>/dev/null
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell dumpsys package $PKG | grep -E "versionName|versionCode|targetSdk" | head -3 >> "$OUT/device.txt"

# realistic, odd data: many scripts, markup that must stay text, a very long title, a long chat
owner() { adb shell stat -c %u /data/data/$PKG | tr -d '\r'; }
put_file() { adb push "$1" /data/local/tmp/put.tmp >/dev/null
  adb shell "mkdir -p \$(dirname /data/data/$PKG/$2) && cp /data/local/tmp/put.tmp /data/data/$PKG/$2 && chown -R $(owner):$(owner) /data/data/$PKG/$(dirname $2) && chmod 600 /data/data/$PKG/$2 && restorecon -R /data/data/$PKG"; }
adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1; sleep 6; adb shell am force-stop $PKG   # first launch makes the folders
NOW=$(( $(adb shell date +%s | tr -d '\r') * 1000 ))
python3 tools/deep/seed.py "$NOW" "$OUT/seed"
put_file "$OUT/seed/atlas-state.json" files/atlas-state.json
put_file "$OUT/seed/chat.json" files/chat.json

# the stand-in for the AI services, reached from the phone as its own 127.0.0.1:8765
python3 tools/deep/stub.py 8765 "$OUT/stub" > "$OUT/stub.log" 2>&1 &
STUB_PID=$!
adb reverse tcp:8765 tcp:8765

case "$PART" in
  crawl)
    log "crawl for ${MINUTES:-45} minutes"
    python3 tools/deep/crawl.py "$OUT" --minutes "${MINUTES:-45}" --stub-port 8765 2>&1 | tee "$OUT/crawl.log"
    ;;
  monkey)
    # StrictMode for every app (best effort: violations go to the log and the dropbox)
    adb shell setprop persist.sys.strictmode.visual 0
    adb shell setprop debug.strictmode 1 2>/dev/null
    adb shell "echo '<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?><map><boolean name=\"reduceMotion\" value=\"true\" /><string name=\"gemini_key\">AIzaDeepTestKey-not-real-0000000000000</string><string name=\"gemini_endpoint\">http://127.0.0.1:8765/v1beta/models/</string></map>' > /data/local/tmp/prefs.xml"
    adb pull /data/local/tmp/prefs.xml "$OUT/prefs.xml" >/dev/null 2>&1 && put_file "$OUT/prefs.xml" shared_prefs/atlas_ui.xml
    for SEED in ${SEEDS:-101 202 303}; do
      log "monkey seed $SEED (${EVENTS:-20000} events)"
      adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1; sleep 4
      adb shell dumpsys meminfo $PKG > "$OUT/meminfo-before-$SEED.txt" 2>&1
      # the stand-in cycles its answers while the monkey types and taps
      ( for m in ok md 429 500 malformed slow empty html502 long; do curl -s "http://127.0.0.1:8765/__mode?m=$m" >/dev/null; sleep 40; done ) &
      CYC=$!
      timeout 3000 "$ADB_BIN" shell monkey -p $PKG -s "$SEED" --pct-syskeys 0 --throttle 50 \
        --ignore-crashes --ignore-timeouts --ignore-security-exceptions --monitor-native-crashes \
        -v "${EVENTS:-20000}" > "$OUT/monkey-$SEED.txt" 2>&1
      echo "exit $?" >> "$OUT/monkey-$SEED.txt"
      kill $CYC 2>/dev/null; curl -s "http://127.0.0.1:8765/__mode?m=ok" >/dev/null
      adb exec-out screencap -p > "$OUT/monkey-$SEED-end.png"
      adb shell dumpsys meminfo $PKG > "$OUT/meminfo-after-$SEED.txt" 2>&1
      # whatever the monkey switched off comes back for the next seed
      adb shell cmd connectivity airplane-mode disable; adb shell svc wifi enable; adb shell svc data enable
      adb shell settings put system font_scale 1.0; adb shell wm size reset; adb shell wm density reset
      tail -3 "$OUT/monkey-$SEED.txt"
    done
    ;;
esac

log "gather"
adb shell dumpsys gfxinfo $PKG > "$OUT/gfxinfo.txt" 2>&1
adb shell dumpsys meminfo $PKG > "$OUT/meminfo-final.txt" 2>&1
adb shell "ls -la /data/anr/" > "$OUT/anr-list.txt" 2>&1
mkdir -p "$OUT/anr" && adb pull /data/anr/ "$OUT/anr/" >/dev/null 2>&1
mkdir -p "$OUT/tombstones" && adb shell "ls /data/tombstones/" > "$OUT/tombstones/list.txt" 2>&1
for t in $(adb shell "ls /data/tombstones/ 2>/dev/null" | tr -d '\r' | grep -v '\.pb$' | head -20); do adb shell "head -60 /data/tombstones/$t" > "$OUT/tombstones/$t.txt" 2>&1; done
adb shell dumpsys dropbox --print data_app_crash data_app_anr data_app_native_crash data_app_strictmode data_app_wtf > "$OUT/dropbox.txt" 2>&1
adb logcat -d -b crash > "$OUT/crash.txt" 2>&1
adb shell "ls -la /data/data/$PKG/files/ /data/data/$PKG/shared_prefs/" > "$OUT/files.txt" 2>&1
adb shell "ls -la /data/data/$PKG/files/chats/ | tail -20" >> "$OUT/files.txt" 2>&1
adb shell dumpsys activity services $PKG > "$OUT/services.txt" 2>&1
adb shell dumpsys notification --noredact 2>/dev/null | grep -B2 -A14 "pkg=$PKG" | head -300 > "$OUT/notifications.txt"
adb shell dumpsys alarm | grep -B1 -A4 "$PKG" | head -120 > "$OUT/alarms.txt" 2>&1
adb shell dumpsys jobscheduler | grep -A6 "$PKG" | head -120 > "$OUT/jobs.txt" 2>&1
sleep 2; kill $LOGCAT_PID 2>/dev/null; kill $STUB_PID 2>/dev/null
python3 tools/deep/triage.py "$OUT" "$PART" > "$OUT/findings.md" 2> "$OUT/triage-errors.txt" || echo "triage failed: $(tail -3 "$OUT/triage-errors.txt")" >> "$OUT/findings.md"
head -80 "$OUT/findings.md"
exit 0
