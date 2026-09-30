#!/usr/bin/env bash
set -euo pipefail
# LeakCanary measures managed references, NOT Mat/Bitmap native allocations.
# A >5000 KiB delta is a strict regression alert, not proof of a specific missing release().
# Usage: ADB=/path/adb PACKAGE=com.daniel.tvdeinsight.admin bash qa/stress_test.sh
ADB=${ADB:-adb}
PACKAGE=${PACKAGE:-com.daniel.tvdeinsight}
ACTION=com.mqzsolutions.app.STRESS_TEST_OCR
ITERATIONS=100
WARMUP=10
LIMIT_KB=5000
REPORT=${REPORT:-ocr-native-heap.csv}
LOG=$(mktemp -t tvde-ocr-stress.XXXXXX)
LOG_PID=
cleanup() {
  if [[ -n "$LOG_PID" ]]; then kill "$LOG_PID" 2>/dev/null || true; wait "$LOG_PID" 2>/dev/null || true; fi
  rm -f -- "$LOG"
}
trap cleanup EXIT INT TERM
"$ADB" get-state >/dev/null
PID=$("$ADB" shell pidof "$PACKAGE" | tr -d '\r')
[[ "$PID" =~ ^[0-9]+$ ]] || { echo "ERROR: open the debug app and enable accessibility first" >&2; exit 2; }
"$ADB" logcat -c
"$ADB" logcat -v brief 'AccessibilityService:I' '*:S' >"$LOG" 2>&1 &
LOG_PID=$!

native_kb() {
  local current value
  current=$("$ADB" shell pidof "$PACKAGE" | tr -d '\r')
  [[ "$current" == "$PID" ]] || { echo "ERROR: process restarted; invalid memory test" >&2; exit 3; }
  # Detailed Native Heap row: last three columns = Heap Size, Heap Alloc, Heap Free.
  value=$("$ADB" shell dumpsys meminfo "$PACKAGE" | tr -d '\r' |
    awk '$1 == "Native" && $2 == "Heap" && NF >= 8 { print $(NF-1); exit }')
  [[ "$value" =~ ^[0-9]+$ ]] || { echo "ERROR: Native Heap Alloc unavailable" >&2; exit 4; }
  printf '%s\n' "$value"
}

trigger_frame() {
  local iteration=$1 attempt token status tick
  for ((attempt=1; attempt<=30; attempt++)); do
    token="stress_$$_${iteration}_${attempt}"
    "$ADB" shell am broadcast -a "$ACTION" -p "$PACKAGE" --es token "$token" >/dev/null
    status=
    for ((tick=0; tick<100; tick++)); do
      status=$(awk -v token="token=$token " 'index($0,token) { sub(/.*status=/, ""); print $1; exit }' "$LOG")
      [[ -n "$status" ]] && break
      sleep 0.2
    done
    case "$status" in
      OK) return 0 ;;
      RETRY) sleep 0.25 ;;
      *) echo "ERROR: frame $iteration status=${status:-TIMEOUT}; no completed OCR acknowledgement" >&2; exit 5 ;;
    esac
  done
  echo "ERROR: OCR remained busy" >&2
  exit 6
}

# Keep Uber visible with an offer; settings ON, valid licence (or admin). No permission bypass.
for ((i=1; i<=WARMUP; i++)); do trigger_frame "warmup_$i"; done
baseline=$(native_kb)
printf 'frame,native_heap_alloc_kb,delta_kb\n0,%s,0\n' "$baseline" >"$REPORT"
for ((i=1; i<=ITERATIONS; i++)); do
  trigger_frame "$i"
  current=$(native_kb)
  printf '%s,%s,%s\n' "$i" "$current" "$((current-baseline))" | tee -a "$REPORT"
done
final=$(native_kb)
delta=$((final-baseline))
printf 'Baseline=%s KiB Final=%s KiB Delta=%s KiB\n' "$baseline" "$final" "$delta"
if ((delta>LIMIT_KB)); then
  echo "MEMORY LEAK ALERT: native heap delta > 5000 KiB; investigate retained allocations/caches" >&2
  exit 1
fi
echo "PASS: 100 completed frames; native heap delta <= 5000 KiB (not a 24/7 stability guarantee)"
