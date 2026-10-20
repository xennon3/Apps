#!/bin/sh
. "$(dirname "$0")/_find-adb.sh"
OUT="$LOGS/snapshot-$STAMP.txt"
echo "Gathering a snapshot into $OUT ..."
{
  echo "===== Device ====="; "$ADB" shell getprop ro.product.model; "$ADB" shell getprop ro.build.version.release
  "$ADB" shell dumpsys package app.murmur | grep -E "versionName|versionCode"
  echo "===== Murmur log (recent) ====="
  PID="$("$ADB" shell pidof app.murmur | tr -d '\r')"
  [ -n "$PID" ] && "$ADB" logcat -d -v time --pid="$PID" -t 4000 || echo "(Murmur isn't running right now)"
  echo "===== Why Android stopped Murmur ====="; "$ADB" shell dumpsys activity exit-info app.murmur
  echo "===== Ambient service ====="; "$ADB" shell dumpsys activity services app.murmur
  echo "===== Battery ====="; "$ADB" shell dumpsys battery; "$ADB" shell dumpsys deviceidle whitelist
  "$ADB" shell dumpsys power | grep -E "mWakefulness|mBatterySaverEnabled|mDeviceIdleMode"
  echo "===== Permissions ====="; "$ADB" shell dumpsys package app.murmur; "$ADB" shell appops get app.murmur
  echo "===== Memory ====="; "$ADB" shell dumpsys meminfo app.murmur
  echo "===== Location ====="; "$ADB" shell dumpsys location
} > "$OUT" 2>&1
echo "Done: $OUT"
