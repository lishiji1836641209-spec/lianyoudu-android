#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
serial=${1:?Pass a disposable emulator serial, e.g. emulator-5554}
case "$serial" in emulator-*) ;; *) echo 'Only disposable emulator targets are accepted.' >&2; exit 1;; esac
qa_package=com.lianyoudu.app.qa
./gradlew -PdeviceAudit assembleDebug assembleDebugAndroidTest lintDebug
adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$serial" shell pm clear "$qa_package"
api=$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')
if [ "$api" -ge 33 ]; then
  adb -s "$serial" shell pm grant "$qa_package" android.permission.POST_NOTIFICATIONS
fi
mkdir -p review
adb -s "$serial" shell am instrument -w "$qa_package.test/com.lianyoudu.app.AuditInstrumentation" > review/instrumentation.txt
cat review/instrumentation.txt
grep -q '^PASS ' review/instrumentation.txt
adb -s "$serial" exec-out run-as "$qa_package" cat files/audit.json > review/audit.json
