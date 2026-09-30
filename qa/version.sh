#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
# Review first: this workspace already contained edits to the service and accessibility XML.
git diff --check
git diff --stat
git add -- .gitignore .gitattributes app/build.gradle.kts settings.gradle.kts \
  app/src/main/java/com/daniel/tvdeinsight/service/ocr/OcrBitmapPreprocessor.kt \
  app/src/main/java/com/daniel/tvdeinsight/service/ocr/OpenCvOcrPreprocessor.kt \
  app/src/main/java/com/daniel/tvdeinsight/service/ocr/UberVisionFields.kt \
  app/src/main/java/com/daniel/tvdeinsight/service/accessibility/AppWindowTarget.kt \
  app/src/main/java/com/daniel/tvdeinsight/service/accessibility/UberOfferAccessibilityService.kt \
  app/src/main/java/com/daniel/tvdeinsight/service/overlay/DecisionOverlayManager.kt \
  app/src/main/java/com/daniel/tvdeinsight/data/local/TripDao.kt \
  app/src/main/java/com/daniel/tvdeinsight/data/repository/RoomOfferAnalysisStore.kt \
  app/src/main/java/com/daniel/tvdeinsight/data/screenshot/OfferScreenshotStore.kt \
  app/src/main/java/com/daniel/tvdeinsight/ui/screens/HistoryScreen.kt \
  app/src/main/java/com/daniel/tvdeinsight/worker/OfferScreenshotCleanupWorker.kt \
  app/src/main/java/com/daniel/tvdeinsight/TvdeInsightApplication.kt \
  app/src/main/res/xml/uber_offer_accessibility_service.xml \
  app/src/test/java/com/daniel/tvdeinsight/service/ocr/UberVisionFieldsTest.kt \
  app/src/androidTest/java/com/daniel/tvdeinsight/OcrIntegrationTest.kt qa
git diff --cached --stat
git commit -m "fix(ocr): preprocess Uber window ROI with OpenCV adaptive thresholding" \
  -m "Bound native frames, explicitly release Mats and recycle terminal OCR bitmaps; retain private screenshot associations across Sheets sync and anchor dismissible cards per display. Add debug-only IPC stress QA and version 0.5.40."
