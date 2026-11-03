#!/usr/bin/env bash
# Běží UVNITŘ emulátoru v CI: instrumentační testy + smoke přes ADB. Pravdivý návratový kód.
set -uo pipefail
OUT=crucible-runtime.md
echo "[$(date +%T)] emulátor: $(adb shell getprop sys.boot_completed | tr -d '\r') (boot_completed)"
adb logcat -c
echo "[$(date +%T)] instrumentační testy…"
# limit 25 min: zaseknutý test (čekání na odpověď, ANR) skončí chybou a log se uloží
timeout 1500 ./gradlew :app:connectedDebugAndroidTest -Pomega.abis=x86_64 --no-daemon --stacktrace > instrumentation.log 2>&1
inst=$?
[ $inst -eq 124 ] && echo "INSTRUMENTACE VYPRŠELA (25 min)" >> instrumentation.log
echo "[$(date +%T)] instrumentace skončila: $inst"; tail -30 instrumentation.log
adb logcat -d > logcat-instrumentation.txt 2>/dev/null
{
  echo "## Instrumentační testy (emulátor) – $( [ $inst -eq 0 ] && echo 'PROŠLY' || echo 'SELHALY' )"
  grep -E "Tests? on|tests completed|FAILED|PASSED|> Task :app:connectedDebugAndroidTest" instrumentation.log | tail -20 | sed 's/^/    /'
  echo; echo "### Naměřeno v testech (logcat System.out)"
  adb logcat -d -s System.out:I | grep -o "CRUCIBLE.*" | sed 's/^/- /'
  echo
} > "$OUT"
APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
echo "[$(date +%T)] smoke…"
timeout 900 tools/crucible/emulator_smoke.sh "$APK" smoke.md
smoke=$?
cat smoke.md >> "$OUT"
cat "$OUT"
[ $inst -eq 0 ] && [ $smoke -eq 0 ]
