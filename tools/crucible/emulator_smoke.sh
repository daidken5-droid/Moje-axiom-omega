#!/usr/bin/env bash
# CRUCIBLE – smoke test AVIORu na běžícím emulátoru/zařízení přes ADB. Pravdivý návratový kód: selže při pádu/ANR.
# Použití: tools/crucible/emulator_smoke.sh <debug.apk> [report.md]
set -uo pipefail
APK="$1"; OUT="${2:-crucible-runtime.md}"
PKG="cz.axiom.omega.debug"; ACT="$PKG/cz.axiom.omega.MainActivity"
fail=0
log() { echo "$*" | tee -a "$OUT"; }
check_crash() {
  if adb logcat -d -b crash 2>/dev/null | grep -q "$PKG"; then log "- ❌ $1: PÁD (logcat crash buffer)"; adb logcat -d -b crash | tail -40 >> "$OUT"; fail=1; adb logcat -b crash -c; return 1; fi
  if adb logcat -d | grep -q "ANR in $PKG"; then log "- ❌ $1: ANR"; fail=1; return 1; fi
  log "- ✅ $1: bez pádu"
}
# každý krok má časový limit – zaseknutí = jasná chyba, ne tiché čekání do limitu CI
start_ms() { timeout 60 adb shell am start -W -n "$ACT" 2>/dev/null | awk -F': ' '/TotalTime/{print $2}' | tr -d '\r'; }
A() { timeout 60 adb "$@"; }

: > "$OUT"
log "## CRUCIBLE smoke ($(adb shell getprop ro.product.model | tr -d '\r'), Android $(adb shell getprop ro.build.version.release | tr -d '\r'), ABI $(adb shell getprop ro.product.cpu.abi | tr -d '\r'))"
log "- krok: instalace ($(date +%T))"
timeout 180 adb install -r -g "$APK" >/dev/null || { log "- ❌ instalace selhala nebo vypršela"; exit 1; }
adb logcat -c; adb logcat -b crash -c

# 1) studený start ×3
cold=()
for i in 1 2 3; do adb shell am force-stop "$PKG"; sleep 1; t=$(start_ms); cold+=("$t"); sleep 4; done
log "- studený start (am start -W, TotalTime): ${cold[*]} ms"
check_crash "studený start"

# 2) teplý start ×3 (aplikace na pozadí → zpět)
warm=()
for i in 1 2 3; do adb shell input keyevent KEYCODE_HOME; sleep 2; t=$(start_ms); warm+=("$t"); sleep 2; done
log "- teplý start: ${warm[*]} ms"
check_crash "teplý start"

# 3) paměť po startu
pss=$(adb shell dumpsys meminfo "$PKG" | awk '/TOTAL PSS:/{print $3; exit} /TOTAL:/{print $2; exit}' | tr -d '\r')
log "- paměť (TOTAL PSS po startu): ${pss:-?} kB"

# 4) otočení displeje (změna konfigurace)
adb shell settings put system accelerometer_rotation 0
for r in 1 0 1 0; do adb shell settings put system user_rotation $r; sleep 2; done
check_crash "otočení displeje ×4"

# 5) ukončení procesu systémem na pozadí → návrat
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am kill "$PKG"; sleep 2
alive=$(adb shell pidof "$PKG" | tr -d '\r')
log "- proces po am kill: ${alive:-ukončen}"
t=$(start_ms); log "- start po ukončení procesu: ${t:-?} ms"; sleep 4
check_crash "návrat po ukončení procesu"

# 6) paměťový tlak (TRIM_MEMORY) – aplikace musí přežít
adb shell am send-trim-memory "$PKG" RUNNING_CRITICAL 2>/dev/null || true; sleep 2
adb shell input keyevent KEYCODE_HOME; adb shell am send-trim-memory "$PKG" COMPLETE 2>/dev/null || true; sleep 2
t=$(start_ms); sleep 3
check_crash "paměťový tlak (trim-memory) a návrat"
pss2=$(adb shell dumpsys meminfo "$PKG" | awk '/TOTAL PSS:/{print $3; exit} /TOTAL:/{print $2; exit}' | tr -d '\r')
log "- paměť po trim-memory a návratu: ${pss2:-?} kB"

# 7) chyby JS/WebView v logu
js=$(adb logcat -d | grep -E "chromium.*(Uncaught|SyntaxError|TypeError)" | grep -v "^$" | head -5)
if [ -n "$js" ]; then log "- ❌ chyby JavaScriptu:"; echo '```' >> "$OUT"; echo "$js" >> "$OUT"; echo '```' >> "$OUT"; fail=1; else log "- ✅ bez chyb JavaScriptu v logcatu"; fi

log ""; [ $fail -eq 0 ] && log "**VÝSLEDEK: smoke prošel**" || log "**VÝSLEDEK: smoke SELHAL**"
exit $fail
