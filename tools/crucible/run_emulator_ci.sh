#!/usr/bin/env bash
# Běží UVNITŘ emulátoru v CI: instrumentační testy + smoke přes ADB. Pravdivý návratový kód.
set -uo pipefail
# KAŽDÉ volání adb má limit 60 s: když emulátor zmizí (CI běh #1), čekalo „- waiting for device -“ až do limitu jobu
ADB_BIN="$(command -v adb)"   # timeout spouští program, ne funkci → absolutní cesta
adb() { timeout 60 "$ADB_BIN" "$@"; }
OUT=crucible-runtime.md
# paměť hostitele každých 10 s (zda emulátor nezabil nedostatek RAM runneru)
( while true; do echo "$(date +%T) $(free -m | awk '/Mem:/{print "host used="$3"MB free="$4"MB avail="$7"MB"}') $(adb devices | grep -c 'emulator.*device$') zařízení"; sleep 10; done ) > host-monitor.log 2>&1 &
MON=$!
adb shell pm grant cz.axiom.omega.debug android.permission.POST_NOTIFICATIONS 2>/dev/null || true
echo "[$(date +%T)] emulátor: $(adb shell getprop sys.boot_completed | tr -d '\r') (boot_completed)"
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
APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
echo "[$(date +%T)] smoke…"
timeout 900 tools/crucible/emulator_smoke.sh "$APK" smoke.md
smoke=$?
cat smoke.md >> "$OUT"
kill $MON 2>/dev/null; { echo; echo "### Hostitel (paměť, počet zařízení)"; echo '```'; tail -40 host-monitor.log; echo '```'; } >> "$OUT"
cat "$OUT"
[ $inst -eq 0 ] && [ $smoke -eq 0 ]
