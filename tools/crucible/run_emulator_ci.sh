#!/usr/bin/env bash
# Běží UVNITŘ emulátoru v CI: instrumentační testy + smoke přes ADB. Pravdivý návratový kód.
set -uo pipefail
# KAŽDÉ volání adb má limit 60 s: když emulátor zmizí (CI běh #1), čekalo „- waiting for device -“ až do limitu jobu
ADB_BIN="$(command -v adb)"   # timeout spouští program, ne funkci → absolutní cesta
adb() { timeout 60 "$ADB_BIN" "$@"; }
OUT=crucible-runtime.md
# paměť a disk hostitele + obsazení /data v emulátoru a data AVIORu každých 10 s
# (běh #8: disk hostitele −8 GB za ~100 s po startu AVIORu, pak emulátor zmizel → kdo zapisuje?)
( while true; do echo "$(date +%T) $(free -m | awk '/Mem:/{print "host used="$3"MB free="$4"MB avail="$7"MB"}') disk=$(df -m --output=avail "$HOME" | tail -1 | tr -d ' ')MB avd=$(du -sm "$HOME/.android/avd" 2>/dev/null | cut -f1)MB guest/data=$(timeout 8 "$ADB_BIN" shell df -m /data 2>/dev/null | awk 'NR==2{print $3"/"$2}')MB app=$(timeout 8 "$ADB_BIN" shell run-as cz.axiom.omega.debug du -sm . 2>/dev/null | cut -f1)MB $(adb devices | grep -c 'emulator.*device$') zařízení"; sleep 10; done ) > host-monitor.log 2>&1 &
MON=$!
adb shell pm grant cz.axiom.omega.debug android.permission.POST_NOTIFICATIONS 2>/dev/null || true
echo "[$(date +%T)] emulátor: $(adb shell getprop sys.boot_completed | tr -d '\r') (boot_completed)"
# EXPERIMENT (běhy #1, #4: emulátor zmizel během instrumentace): smoke NEJDŘÍV – spadne-li i při obyčejném
# spuštění aplikace, spouštěčem je aplikace/WebView; jinak vazba na testovací platformu
# KONTROLA (běh #5: emulátor zmizel ~100 s po bootu už při smoke, bez crash dumpu, RAM hostitele OK):
# 180 s emulátor BEZ aplikace. Zmizí-li i tak → příčina je emulátor/runner, ne AVIOR.
echo "[$(date +%T)] kontrola: 180 s nečinný emulátor bez AVIORu…"
idle_ok=1
for i in $(seq 18); do sleep 10; [ "$(timeout 10 "$ADB_BIN" get-state 2>/dev/null)" = "device" ] || { idle_ok=0; break; }; done
if [ $idle_ok -eq 1 ]; then echo "[$(date +%T)] kontrola: emulátor bez aplikace přežil 180 s"
else echo "[$(date +%T)] kontrola: emulátor ZMIZEL BEZ AVIORu (po ~$((i*10)) s) → příčina mimo aplikaci"; fi
{ echo "## Kontrola bez aplikace"; [ $idle_ok -eq 1 ] && echo "- emulátor bez AVIORu přežil 180 s" || echo "- ❌ emulátor zmizel BEZ AVIORu po ~$((i*10)) s → příčina: emulátor/runner, ne aplikace"; echo; } > control.md
APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
echo "[$(date +%T)] smoke…"
timeout 900 tools/crucible/emulator_smoke.sh "$APK" smoke.md
smoke=$?
echo "[$(date +%T)] po smoke · zařízení: $(adb devices | tail -n +2 | tr '\n' ' ')"
adb logcat -c
echo "[$(date +%T)] instrumentační testy…"
# limit 25 min: zaseknutý test (čekání na odpověď, ANR) skončí chybou a log se uloží
timeout 1500 ./gradlew :app:connectedDebugAndroidTest -Pomega.abis=x86_64 --no-daemon --stacktrace > instrumentation.log 2>&1
inst=$?
[ $inst -eq 124 ] && echo "INSTRUMENTACE VYPRŠELA (25 min)" >> instrumentation.log
echo "[$(date +%T)] instrumentace skončila: $inst · zařízení: $(adb devices | tail -n +2 | tr '\n' ' ')"; tail -30 instrumentation.log
adb logcat -d > logcat-instrumentation.txt 2>/dev/null
{
  echo "## Instrumentační testy (emulátor) – $( [ $inst -eq 0 ] && echo 'PROŠLY' || echo 'SELHALY' )"
  grep -E "Tests? on|tests completed|FAILED|PASSED|> Task :app:connectedDebugAndroidTest" instrumentation.log | tail -20 | sed 's/^/    /'
  echo; echo "### Naměřeno v testech (logcat System.out)"
  timeout 30 "$ADB_BIN" logcat -d -s System.out:I CRUCIBLE:I | grep -o "CRUCIBLE.*" | sed 's/^/- /'
  grep -h -o "CRUCIBLE.*" app/build/outputs/androidTest-results/connected/debug/*/logcat-*.txt 2>/dev/null | sort -u | sed 's/^/- /'
  echo
} > "$OUT"
cat control.md smoke.md >> "$OUT" 2>/dev/null
kill $MON 2>/dev/null; { echo; echo "### Hostitel (paměť, počet zařízení)"; echo '```'; tail -120 host-monitor.log; echo '```'; } >> "$OUT"
cat "$OUT"
[ $inst -eq 0 ] && [ $smoke -eq 0 ]
