#!/bin/sh
. "$(dirname "$0")/_find-adb.sh"
APK="$(ls "$here"/../*.apk 2>/dev/null | head -1)"
if [ -z "$APK" ]; then
  echo "Downloading the newest Murmur build..."
  APK="$here/../murmur.apk"
  curl -L -o "$APK" https://github.com/xennon3/Apps/releases/download/murmur-latest/Murmur.apk
fi
echo "Installing $APK (your messages and settings stay)..."
"$ADB" install -r "$APK"
