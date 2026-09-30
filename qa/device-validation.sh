#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
ADB=${ADB:-adb}
"$ADB" get-state >/dev/null
# Does not uninstall, clear app data, enable accessibility, or change overlay permissions.
"$ADB" install -r app/build/outputs/apk/client/debug/app-client-debug.apk
"$ADB" install -r app/build/outputs/apk/androidTest/client/debug/app-client-debug-androidTest.apk
"$ADB" shell am instrument -w -r \
  -e class com.daniel.tvdeinsight.OcrIntegrationTest \
  com.daniel.tvdeinsight.test/androidx.test.runner.AndroidJUnitRunner

# Manual acceptance before distribution:
# 1. API 33 + API 34/35: Uber light/dark, Selecionar/Aceitar, 1h, bonus, charging distance.
# 2. Split screen: swap Uber/Bolt, resize divider, rotate. Each card stays inside its app, at top.
# 3. DeX external display: move each app to another monitor; verify card monitor/position.
# 4. Simultaneous Uber/Bolt: both decisions visible; tapping one removes ONLY that card.
# 5. Save capture; stop/restart app; sync Sheet; clear CACHE (not app data); reopen same trip.
# 6. Keep WhatsApp/TVDE foreground: no offer screenshots should be captured.
# 7. Return to Uber with active analysis, then run qa/stress_test.sh; await 100 completed frames.
