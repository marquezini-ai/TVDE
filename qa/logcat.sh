#!/usr/bin/env bash
set -euo pipefail
ADB=${ADB:-adb}
"$ADB" logcat -c
"$ADB" logcat -v threadtime | grep --line-buffered -E 'OpenCV|MLKit|Regex|LeakCanary|AccessibilityService'
